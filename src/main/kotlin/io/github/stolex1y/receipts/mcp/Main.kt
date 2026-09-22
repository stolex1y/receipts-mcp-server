package io.github.stolex1y.receipts.mcp

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val SERVER_VERSION = "0.1.0"
private const val DEFAULT_HOST = "127.0.0.1"
private const val DEFAULT_PORT = 3002
private const val MCP_PATH = "/mcp"

fun main() {
    val host = System.getenv("MCP_HOST")?.takeIf(String::isNotBlank) ?: DEFAULT_HOST
    val port = System.getenv("MCP_PORT")
        ?.toIntOrNull()
        ?.takeIf { it in 1..65_535 }
        ?: DEFAULT_PORT
    val server = createServer()

    embeddedServer(Netty, host = host, port = port) {
        install(ContentNegotiation) {
            json(McpJson)
        }
        mcpStreamableHttp(MCP_PATH, false, emptyList(), emptyList(), null) { server }
    }.start(wait = true)
}

private fun createServer(): Server = Server(
    serverInfo = Implementation(
        name = "receipts-mcp-server",
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
        description = "Ищет чеки по тексту и периоду.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "query",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Текстовый запрос")
                    },
                )
                put(
                    "from",
                    buildJsonObject {
                        put("type", "string")
                        put("format", "date")
                    },
                )
                put(
                    "to",
                    buildJsonObject {
                        put("type", "string")
                        put("format", "date")
                    },
                )
            },
            required = listOf("query"),
        ),
        toolAnnotations = ToolAnnotations(
            readOnlyHint = true,
            idempotentHint = true,
            openWorldHint = false,
        ),
    ) {
        CallToolResult(
            content = listOf(
                TextContent(
                    text = "[{\"id\":\"receipt-001\",\"merchant\":\"Магазин\",\"amount_minor\":34900,\"currency\":\"RUB\"}]",
                ),
            ),
        )
    }

    addTool(
        name = "get-receipt",
        description = "Возвращает чек по его идентификатору.",
        inputSchema = ToolSchema(
            properties = buildJsonObject {
                put(
                    "receipt_id",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "Идентификатор чека")
                    },
                )
            },
            required = listOf("receipt_id"),
        ),
        toolAnnotations = ToolAnnotations(
            readOnlyHint = true,
            idempotentHint = true,
            openWorldHint = false,
        ),
    ) {
        CallToolResult(
            content = listOf(
                TextContent(
                    text = "{\"id\":\"receipt-001\",\"merchant\":\"Магазин\",\"items\":[]}",
                ),
            ),
        )
    }
}
