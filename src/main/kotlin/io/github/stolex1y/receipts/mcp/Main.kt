package io.github.stolex1y.receipts.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val SERVER_VERSION = "0.1.0"

fun main(): Unit = runBlocking {
    val server = Server(
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
            description = "Ищет синтетические чеки по тексту и периоду для учебной интеграции.",
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
                        text = "[{\"id\":\"demo-receipt-1\",\"merchant\":\"Демо-магазин\",\"amount_minor\":34900,\"currency\":\"RUB\"}]",
                    ),
                ),
            )
        }

        addTool(
            name = "get-receipt",
            description = "Возвращает синтетический чек по его идентификатору.",
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
                        text = "{\"id\":\"demo-receipt-1\",\"merchant\":\"Демо-магазин\",\"items\":[]}",
                    ),
                ),
            )
        }
    }

    server.createSession(
        StdioServerTransport(
            inputStream = System.`in`.asSource().buffered(),
            outputStream = System.out.asSink().buffered(),
        ),
    )
    awaitCancellation()
}
