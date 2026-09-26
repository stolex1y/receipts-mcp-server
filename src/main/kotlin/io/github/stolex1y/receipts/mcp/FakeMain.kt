package io.github.stolex1y.receipts.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

fun main() {
    val httpClient = HttpClient(CIO) {
        expectSuccess = false
    }
    val auth = FakeReceiptAuthService()
    startReceiptServer(
        httpClient = httpClient,
        auth = auth,
        mode = ReceiptMode.FAKE,
        apiBase = "http://127.0.0.1/unused",
        serverName = "receipts-mcp-fake-server",
        port = System.getenv("MCP_PORT")
            ?.toIntOrNull()
            ?.takeIf { it in 1..65_535 }
            ?: 3002,
    )
}
