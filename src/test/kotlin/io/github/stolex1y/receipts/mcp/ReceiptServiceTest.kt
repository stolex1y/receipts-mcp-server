package io.github.stolex1y.receipts.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReceiptServiceTest {
    @Test
    fun privateListMapsRequestAndResponseThroughMockLkdr() = runBlocking {
        val (client, requests) = mockLkdr { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v1/receipt", request.url.encodedPath)
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            respondJson(
                """
                {
                  "brands": [{"id": 10, "name": "ДЕМО МАРКЕТ"}],
                  "receipts": [{
                    "brandId": 10,
                    "createdDate": "2026-09-10T12:00:00",
                    "fiscalDocumentNumber": "1001",
                    "fiscalDriveNumber": "999900010001",
                    "key": "upstream-key-001",
                    "kktOwner": "ДЕМО МАРКЕТ",
                    "receiveDate": "2026-09-10T12:00:00",
                    "totalSum": "349.00"
                  }],
                  "hasMore": true
                }
                """.trimIndent(),
            )
        }
        try {
            val service = ReceiptService(
                httpClient = client,
                mode = ReceiptMode.PRIVATE,
                apiBase = "https://mock.lkdr.test/api",
                sessionProvider = ReceiptSessionProvider { "test-token" },
            )
            val result = service.search(
                ReceiptSearchInput(
                    query = "ДЕМО",
                    from = "2026-09-01",
                    to = "2026-09-30",
                    limit = 25,
                    offset = 5,
                    orderBy = "RECEIVE_DATE:DESC",
                ),
            )

            assertEquals(1, result.receipts.size)
            assertEquals("upstream-key-001", result.receipts.single().receiptKey)
            assertEquals("ДЕМО МАРКЕТ", result.receipts.single().merchant)
            assertEquals(34_900, result.receipts.single().amountMinor)
            assertTrue(result.hasMore)

            val body = Json.parseToJsonElement(bodyText(requests.single())).jsonObject
            assertEquals("2026-09-01", body["dateFrom"]?.toString()?.trim('"'))
            assertEquals("2026-09-30", body["dateTo"]?.toString()?.trim('"'))
            assertEquals("ДЕМО", body["kktOwner"]?.toString()?.trim('"'))
            assertEquals("25", body["limit"]?.toString())
            assertEquals("5", body["offset"]?.toString())
            assertEquals("RECEIVE_DATE:DESC", body["orderBy"]?.toString()?.trim('"'))
            assertFalse(Json.encodeToString(result).contains("test-token"))
        } finally {
            client.close()
        }
    }

    @Test
    fun privateDetailNormalizesFiscalItemsAndDropsSensitiveFields() = runBlocking {
        val (client, requests) = mockLkdr { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v1/receipt/fiscal_data", request.url.encodedPath)
            assertEquals("Bearer test-token", request.headers[HttpHeaders.Authorization])
            respondJson(
                """
                {
                  "buyerAddress": "secret address",
                  "userInn": "secret-inn",
                  "dateTime": "2026-09-10T12:00:00",
                  "fiscalDocumentNumber": 1001,
                  "fiscalDriveNumber": "999900010001",
                  "fiscalSign": "700001",
                  "kktRegId": "0001112223334444",
                  "totalSum": 349.0,
                  "items": [{
                    "name": "Демо товар",
                    "nds": 20,
                    "paymentType": 1,
                    "price": 349.0,
                    "productType": 1,
                    "quantity": 1.0,
                    "sum": 349.0,
                    "providerData": {"providerPhone": ["secret-phone"]}
                  }]
                }
                """.trimIndent(),
            )
        }
        try {
            val service = ReceiptService(
                httpClient = client,
                mode = ReceiptMode.PRIVATE,
                apiBase = "https://mock.lkdr.test/api",
                sessionProvider = object : ReceiptSessionProvider {
                    override suspend fun accessToken(): String = "test-token"

                    override suspend fun authCookie(): String = "session-cookie=opaque"
                },
            )
            val result = service.getReceipt("upstream-key-001")
            assertEquals("session-cookie=opaque", requests.single().headers[HttpHeaders.Cookie])
            assertEquals("upstream-key-001", result.receiptKey)
            assertEquals(34_900, result.totalMinor)
            assertEquals("Демо товар", result.items.single().name)
            assertEquals(34_900, result.items.single().sumMinor)
            assertEquals("upstream-key-001", bodyText(requests.single()).let {
                Json.parseToJsonElement(it).jsonObject["key"]?.toString()?.trim('"')
            })
            val publicJson = Json.encodeToString(result)
            assertFalse(publicJson.contains("secret address"))
            assertFalse(publicJson.contains("secret-inn"))
            assertFalse(publicJson.contains("secret-phone"))
            assertFalse(publicJson.contains("test-token"))
        } finally {
            client.close()
        }
    }

    @Test
    fun invalidInputIsRejectedBeforeCallingMockLkdr() = runBlocking {
        var calls = 0
        val (client, _) = mockLkdr {
            calls += 1
            error("mock lkdr must not be called")
        }
        try {
            val service = ReceiptService(
                httpClient = client,
                mode = ReceiptMode.PRIVATE,
                apiBase = "https://mock.lkdr.test/api",
                sessionProvider = ReceiptSessionProvider { "test-token" },
            )

            assertFailsWith<IllegalArgumentException> {
                service.search(ReceiptSearchInput(from = "2026-09-30", to = "2026-09-01"))
            }
            assertFailsWith<IllegalArgumentException> {
                service.search(ReceiptSearchInput(limit = 101))
            }
            assertFailsWith<IllegalArgumentException> {
                service.getReceipt(" ")
            }
            assertEquals(0, calls)
        } finally {
            client.close()
        }
    }

    @Test
    fun redirectedAPIResponseDoesNotForwardBearerRequestToLocationTarget() = runBlocking {
        val requestHosts = mutableListOf<String>()
        val (client, requests) = mockLkdr { request ->
            requestHosts += request.url.host
            if (request.url.host == "mock.lkdr.test") {
                respond(
                    content = "",
                    status = HttpStatusCode.TemporaryRedirect,
                    headers = headersOf(HttpHeaders.Location, "https://attacker.example/collect"),
                )
            } else {
                respondJson("""{"brands":[],"receipts":[],"hasMore":false}""")
            }
        }
        try {
            val service = ReceiptService(
                httpClient = client,
                mode = ReceiptMode.PRIVATE,
                apiBase = "https://mock.lkdr.test/api",
                sessionProvider = ReceiptSessionProvider { "test-token" },
            )

            assertFailsWith<ReceiptIntegrationException> {
                service.search(ReceiptSearchInput())
            }

            assertEquals(listOf("mock.lkdr.test"), requestHosts)
            assertEquals(1, requests.size)
            assertEquals("Bearer test-token", requests.single().headers[HttpHeaders.Authorization])
        } finally {
            client.close()
        }
    }

    @Test
    fun upstreamErrorsDoNotExposeResponseSecrets() = runBlocking {
        val (client, _) = mockLkdr {
            respondJson(
                """
                {
                  "code": "upstream.failed",
                  "message": "access_token=secret-token sessionid=secret-session"
                }
                """.trimIndent(),
                status = HttpStatusCode.BadGateway,
            )
        }
        try {
            val service = ReceiptService(
                httpClient = client,
                mode = ReceiptMode.PRIVATE,
                apiBase = "https://mock.lkdr.test/api",
                sessionProvider = ReceiptSessionProvider { "test-token" },
            )

            val error = assertFailsWith<ReceiptIntegrationException> {
                service.search(ReceiptSearchInput())
            }
            assertEquals("Сервис чеков временно недоступен.", error.message)
            assertFalse(error.message.orEmpty().contains("upstream.failed"))
            assertFalse(error.message.orEmpty().contains("secret-token"))
            assertFalse(error.message.orEmpty().contains("secret-session"))
        } finally {
            client.close()
        }
    }

    @Test
    fun invalidUpstreamJsonIsReportedWithoutRawBody() = runBlocking {
        val (client, _) = mockLkdr {
            respondJson("""{"message":"access_token=secret-token"""")
        }
        try {
            val service = ReceiptService(
                httpClient = client,
                mode = ReceiptMode.PRIVATE,
                apiBase = "https://mock.lkdr.test/api",
                sessionProvider = ReceiptSessionProvider { "test-token" },
            )

            val error = assertFailsWith<ReceiptIntegrationException> {
                service.search(ReceiptSearchInput())
            }
            assertEquals("Сервис чеков вернул некорректный JSON.", error.message)
            assertFalse(error.message.orEmpty().contains("secret-token"))
        } finally {
            client.close()
        }
    }

    @Test
    fun transportFailureIsReportedWithoutCauseDetails() = runBlocking {
        val (client, _) = mockLkdr {
            error("access_token=secret-token")
        }
        try {
            val service = ReceiptService(
                httpClient = client,
                mode = ReceiptMode.PRIVATE,
                apiBase = "https://mock.lkdr.test/api",
                sessionProvider = ReceiptSessionProvider { "test-token" },
            )

            val error = assertFailsWith<ReceiptIntegrationException> {
                service.search(ReceiptSearchInput())
            }
            assertEquals("Не удалось обратиться к сервису чеков.", error.message)
            assertFalse(error.message.orEmpty().contains("secret-token"))
        } finally {
            client.close()
        }
    }

    @Test
    fun fakeModeIsDeterministicWithoutCallingNetwork() = runBlocking {
        val (client, _) = mockLkdr { error("fake mode must not call network") }
        try {
            val service = ReceiptService(httpClient = client)
            val result = service.search(
                ReceiptSearchInput(query = "маркет", from = "2026-09-01", to = "2026-09-30"),
            )
            assertEquals(listOf("receipt-001", "receipt-003"), result.receipts.map(ReceiptSummary::receiptKey))
            assertEquals(34_900, service.getReceipt("receipt-001").totalMinor)
        } finally {
            client.close()
        }
    }
}


private fun mockLkdr(
    responder: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): Pair<HttpClient, MutableList<HttpRequestData>> {
    val requests = mutableListOf<HttpRequestData>()
    val client = noRedirectHttpClient(
        MockEngine { request ->
            requests += request
            responder(request)
        },
    )
    return client to requests
}

private fun MockRequestHandleScope.respondJson(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
): HttpResponseData = respond(
    content = body,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
)

private fun bodyText(request: HttpRequestData): String = when (val body = request.body) {
    is TextContent -> body.text
    is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
    else -> error("Unexpected request body: ${body::class.qualifiedName}")
}
