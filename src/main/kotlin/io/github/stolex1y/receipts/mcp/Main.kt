package io.github.stolex1y.receipts.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.URI
import java.util.Locale

private const val SERVER_VERSION = "0.2.0"
private const val DEFAULT_HOST = "127.0.0.1"
private const val DEFAULT_PORT = 3002
private const val MCP_PATH = "/mcp"

private val LOOPBACK_ORIGIN_HOSTS = setOf("127.0.0.1", "localhost")

private fun ApplicationCall.hasTrustedLoopbackOrigin(expectedPort: Int): Boolean {
    val request = this.request
    if (request.headers["Sec-Fetch-Site"].equals("cross-site", ignoreCase = true)) return false
    val targetHost = request.headers["Host"] ?: return false
    val targetUri = runCatching { URI("http://$targetHost") }.getOrNull() ?: return false
    val targetHostName = targetUri.host?.lowercase(Locale.ROOT) ?: return false
    val targetPort = effectiveOriginPort(targetUri)
    if (
        targetHostName !in LOOPBACK_ORIGIN_HOSTS ||
        targetPort != expectedPort ||
        targetUri.rawUserInfo != null ||
        targetUri.rawQuery != null ||
        targetUri.rawFragment != null ||
        (!targetUri.rawPath.isNullOrEmpty() && targetUri.rawPath != "/")
    ) {
        return false
    }
    val origin = request.headers["Origin"] ?: return true
    val originUri = runCatching { URI(origin) }.getOrNull() ?: return false
    val originHost = originUri.host?.lowercase(Locale.ROOT) ?: return false
    return originUri.scheme.equals("http", ignoreCase = true) &&
        originHost in LOOPBACK_ORIGIN_HOSTS &&
        originHost == targetHostName &&
        effectiveOriginPort(originUri) == targetPort &&
        originUri.rawUserInfo == null &&
        originUri.rawQuery == null &&
        originUri.rawFragment == null &&
        (originUri.rawPath.isNullOrEmpty() || originUri.rawPath == "/")
}

private fun effectiveOriginPort(uri: URI): Int = if (uri.port == -1) 80 else uri.port

internal fun noRedirectHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    expectSuccess = false
    followRedirects = false
}

fun main() {
    val httpClient = noRedirectHttpClient(CIO.create { })
    val authHttpClient = noRedirectHttpClient(CIO.create { })
    val auth = RealReceiptAuthService(authHttpClient)
    runBlocking { auth.restore() }
    startReceiptServer(
        httpClient = httpClient,
        auth = auth,
        mode = ReceiptMode.PRIVATE,
        apiBase = validateReceiptUpstreamUrl(
            System.getenv("RECEIPTS_API_BASE")
                ?.takeIf(String::isNotBlank)
                ?: "https://mco.nalog.ru/api",
            "RECEIPTS_API_BASE",
        ),
        serverName = "receipts-mcp-server",
        port = configuredPort(),
    )
}

internal fun startReceiptServer(
    httpClient: HttpClient,
    auth: ReceiptAuthService,
    mode: ReceiptMode,
    apiBase: String,
    serverName: String,
    port: Int,
) {
    val service = ReceiptService(
        httpClient = httpClient,
        mode = mode,
        apiBase = apiBase,
        sessionProvider = auth,
        requireAuthenticatedSession = true,
    )
    val server = createServer(service, serverName)

    embeddedServer(Netty, host = DEFAULT_HOST, port = port) {
        install(ContentNegotiation) {
            json(McpJson)
        }
        routing {
            installReceiptAuthRoutes(auth, mode, port)
        }
        mcpStreamableHttp(MCP_PATH, false, emptyList(), emptyList(), null) { server }
    }.start(wait = true)
}
internal fun Route.installReceiptAuthRoutes(
    auth: ReceiptAuthService,
    mode: ReceiptMode,
    expectedPort: Int,
) {
    post("/receipts/browser-login") {
        if (!call.hasTrustedLoopbackOrigin(expectedPort)) {
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        val hasBody = (contentLength ?: 0L) > 0L ||
            call.request.headers[HttpHeaders.TransferEncoding] != null
        if (hasBody) {
            call.respond(HttpStatusCode.BadRequest, invalidBrowserLoginRequestResponse())
            return@post
        }
        call.respond(auth.startBrowserLogin())
    }
    post("/receipts/login") {
        if (!call.hasTrustedLoopbackOrigin(expectedPort)) {
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        if (mode == ReceiptMode.PRIVATE) {
            call.respond(HttpStatusCode.Forbidden, legacyAuthDisabledResponse())
            return@post
        }
        val request = try {
            call.receive<ReceiptLoginRequest>()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            call.respond(HttpStatusCode.BadRequest, invalidLoginRequestResponse())
            return@post
        }
        call.respond(auth.login(request))
    }
    post("/receipts/otp/resend") {
        if (!call.hasTrustedLoopbackOrigin(expectedPort)) {
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        if (mode == ReceiptMode.PRIVATE) {
            call.respond(HttpStatusCode.Forbidden, legacyAuthDisabledResponse())
            return@post
        }
        val request = try {
            call.receive<ReceiptLoginRequest>()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            call.respond(HttpStatusCode.BadRequest, invalidLoginRequestResponse())
            return@post
        }
        call.respond(auth.resendOtp(request.phone))
    }
    post("/receipts/logout") {
        if (!call.hasTrustedLoopbackOrigin(expectedPort)) {
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        call.respond(auth.logout())
    }
    get("/receipts/session") {
        call.respond(auth.session())
    }
    post("/receipts/session/retry") {
        if (!call.hasTrustedLoopbackOrigin(expectedPort)) {
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        call.respond(auth.retrySession())
    }
}

private fun invalidBrowserLoginRequestResponse() = ReceiptSessionResponse(
    authenticated = false,
    status = "login_required",
)

private fun legacyAuthDisabledResponse() = ReceiptLoginResponse(
    status = "error",
    message = "Real receipts auth доступна только через ручной visible browser login.",
    persistenceStatus = PERSISTENCE_NOT_CONFIGURED,
)


private fun invalidLoginRequestResponse() = ReceiptLoginResponse(
    status = "error",
    message = "Некорректный JSON login request.",
    persistenceStatus = PERSISTENCE_NOT_CONFIGURED,
)

private fun configuredPort(): Int = System.getenv("MCP_PORT")
    ?.toIntOrNull()
    ?.takeIf { it in 1..65_535 }
    ?: DEFAULT_PORT

internal fun createServer(
    service: ReceiptService,
    serverName: String = "receipts-mcp-server",
): Server = Server(
    serverInfo = Implementation(
        name = serverName,
        version = SERVER_VERSION,
    ),
    options = ServerOptions(
        capabilities = ServerCapabilities(
            tools = ServerCapabilities.Tools(listChanged = false),
        ),
    ),
) {
    addTool(
        name = "search-receipts",
        description = "Ищет чеки по продавцу и периоду и возвращает краткий список.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "query",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Текст продавца или бренда")
                    },
                )
                put(
                    "from",
                    buildJsonObject {
                        put("type", "string")
                        put("format", "date")
                        put("description", "Начало периода включительно, YYYY-MM-DD")
                    },
                )
                put(
                    "to",
                    buildJsonObject {
                        put("type", "string")
                        put("format", "date")
                        put("description", "Конец периода включительно, YYYY-MM-DD")
                    },
                )
                put(
                    "limit",
                    buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", 100)
                        put("default", 50)
                    },
                )
                put(
                    "offset",
                    buildJsonObject {
                        put("type", "integer")
                        put("minimum", 0)
                        put("maximum", 10_000)
                        put("default", 0)
                    },
                )
                put(
                    "order_by",
                    buildJsonObject {
                        put("type", "string")
                        put("default", DEFAULT_ORDER_BY)
                    },
                )
            },
            required = emptyList(),
        ),
        toolAnnotations = readOnlyAnnotations(),
    ) { request ->
        runTool {
            val args = request.arguments ?: JsonObject(emptyMap())
            val input = ReceiptSearchInput(
                query = optionalStringArgument(args, "query"),
                from = optionalStringArgument(args, "from"),
                to = optionalStringArgument(args, "to"),
                limit = optionalIntArgument(args, "limit", 50),
                offset = optionalIntArgument(args, "offset", 0),
                orderBy = optionalStringArgument(args, "order_by") ?: DEFAULT_ORDER_BY,
            )
            apiJsonForOutput.encodeToString(service.search(input))
        }
    }

    addTool(
        name = "get-receipt",
        description = "Возвращает фискальные реквизиты и позиции чека.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "receipt_key",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Ключ чека из результата search-receipts")
                    },
                )
            },
            required = listOf("receipt_key"),
        ),
        toolAnnotations = readOnlyAnnotations(),
    ) { request ->
        runTool {
            val args = request.arguments ?: JsonObject(emptyMap())
            val receiptKey = requiredStringArgument(args, "receipt_key")
            apiJsonForOutput.encodeToString(service.getReceipt(receiptKey))
        }
    }
}

private val apiJsonForOutput = kotlinx.serialization.json.Json {
    encodeDefaults = true
    explicitNulls = false
}

private fun readOnlyAnnotations() = ToolAnnotations(
    readOnlyHint = true,
    idempotentHint = true,
    openWorldHint = false,
)

private fun requiredStringArgument(args: JsonObject, name: String): String =
    optionalStringArgument(args, name)?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("$name обязателен.")

private fun optionalStringArgument(args: JsonObject, name: String): String? {
    val value = args[name] ?: return null
    val primitive = value as? JsonPrimitive
    if (primitive == null || !primitive.isString) {
        throw IllegalArgumentException("$name должен быть строкой.")
    }
    return primitive.contentOrNull
}

private fun optionalIntArgument(args: JsonObject, name: String, default: Int): Int {
    val value = args[name] ?: return default
    val primitive = value as? JsonPrimitive
    val parsed = primitive?.contentOrNull?.toIntOrNull()
    if (primitive == null || primitive.isString || parsed == null) {
        throw IllegalArgumentException("$name должен быть целым числом.")
    }
    return parsed
}

private suspend fun runTool(block: suspend () -> String): CallToolResult =
    try {
        CallToolResult(content = listOf(TextContent(text = block())))
    } catch (error: CancellationException) {
        throw error
    } catch (error: IllegalArgumentException) {
        CallToolResult(
            content = listOf(TextContent(text = error.message ?: "Некорректные параметры tool.")),
            isError = true,
        )
    } catch (error: ReceiptAuthenticationException) {
        CallToolResult(
            content = listOf(TextContent(text = error.message ?: "Сессия сервиса чеков недоступна.")),
            isError = true,
        )
    } catch (error: ReceiptIntegrationException) {
        CallToolResult(
            content = listOf(TextContent(text = error.message ?: "Сервис чеков временно недоступен.")),
            isError = true,
        )
    } catch (_: Throwable) {
        CallToolResult(
            content = listOf(TextContent(text = "Не удалось выполнить запрос к сервису чеков.")),
            isError = true,
        )
    }
