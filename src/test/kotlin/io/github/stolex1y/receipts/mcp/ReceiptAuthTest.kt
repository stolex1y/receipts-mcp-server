package io.github.stolex1y.receipts.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReceiptAuthTest {
    @Test
    fun fakeAuthRequiresOtpAndPasswordBeforeReceiptsCanBeRead() = runBlocking {
        val auth = FakeReceiptAuthService()
        val client = HttpClient(MockEngine { error("fake auth must not make HTTP calls") })
        try {
            val service = ReceiptService(
                httpClient = client,
                mode = ReceiptMode.FAKE,
                sessionProvider = auth,
                requireAuthenticatedSession = true,
            )

            assertEquals("login_required", auth.session().status)
            assertEquals("requires_otp", auth.login(ReceiptLoginRequest(phone = "+7 999 000-00-00")).status)
            assertEquals("error", auth.login(ReceiptLoginRequest(phone = FAKE_PHONE_FOR_TEST, otp = "111111")).status)
            assertEquals("requires_password", auth.login(ReceiptLoginRequest(phone = FAKE_PHONE_FOR_TEST, otp = "000000")).status)
            assertEquals(
                "error",
                auth.login(
                    ReceiptLoginRequest(phone = FAKE_PHONE_FOR_TEST, otp = "000000", password = "wrong"),
                ).status,
            )
            assertEquals(
                "authenticated",
                auth.login(
                    ReceiptLoginRequest(phone = FAKE_PHONE_FOR_TEST, otp = "000000", password = "demo"),
                ).status,
            )

            val result = service.search(ReceiptSearchInput())
            assertTrue(result.receipts.isNotEmpty())
            assertTrue(auth.session().authenticated)
            assertEquals("login_required", auth.logout().status)
            assertFalse(auth.session().authenticated)
        } finally {
            client.close()
        }
    }

    @Test
    fun fakeReceiptsRejectUnauthenticatedRequests() = runBlocking {
        val auth = FakeReceiptAuthService()
        val client = HttpClient(MockEngine { error("fake auth must not make HTTP calls") })
        try {
            val service = ReceiptService(
                httpClient = client,
                mode = ReceiptMode.FAKE,
                sessionProvider = auth,
                requireAuthenticatedSession = true,
            )
            val error = runCatching { service.search(ReceiptSearchInput()) }.exceptionOrNull()
            assertEquals(ReceiptAuthenticationException::class, error?.let { it::class })
        } finally {
            client.close()
        }
    }

    @Test
    fun realLoginRotatesAndPersistsRefreshToken() = runBlocking {
        val store = MemoryReceiptSessionSecretStore()
        var now = 1_000_000L
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/login" -> jsonResponse(
                    """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":60,"session_id":"session-1","auth_cookie":"session=one"}""",
                )
                "/refresh" -> jsonResponse(
                    """{"access_token":"access-2","refresh_token":"refresh-2","expires_in":7200,"session_id":"session-2","auth_cookie":"session=two"}""",
                )
                else -> error("unexpected auth path: ${request.url.encodedPath}")
            }
        })
        try {
            val auth = RealReceiptAuthService(
                httpClient = client,
                sessionSecretStore = store,
                loginUrl = "https://lkdr.nalog.ru/login",
                refreshUrl = "https://lkdr.nalog.ru/refresh",
                nowEpochMs = { now },
            )

            assertEquals("authenticated", auth.login(ReceiptLoginRequest(phone = "+79990000000")).status)
            assertContains(store.value.orEmpty(), "access-1")
            assertEquals("session=one", auth.authorization("lkdr.nalog.ru")?.authCookie)
            assertNull(auth.authorization("mco.nalog.ru")?.authCookie)
            now += 120_000L

            assertEquals("access-2", auth.accessToken())
            assertContains(store.value.orEmpty(), "access-2")
            assertContains(store.value.orEmpty(), "refresh-2")
            assertEquals("active", auth.session().status)

            val restored = RealReceiptAuthService(
                httpClient = client,
                sessionSecretStore = store,
                loginUrl = "https://lkdr.nalog.ru/login",
                refreshUrl = "https://lkdr.nalog.ru/refresh",
                nowEpochMs = { now },
            )
            restored.restore()
            assertEquals("active", restored.session().status)
            assertEquals("access-2", restored.accessToken())
            assertEquals("session=two", restored.authCookie())
        } finally {
            client.close()
        }
    }

    @Test
    fun realTemporaryRefreshFailureKeepsPersistedSession() = runBlocking {
        val store = MemoryReceiptSessionSecretStore()
        var now = 1_000_000L
        var refreshCalls = 0
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/login" -> jsonResponse(
                    """{"access_token":"access-1","refresh_token":"refresh-1","expires_in":60}""",
                )
                "/refresh" -> {
                    refreshCalls += 1
                    jsonResponse("{}", HttpStatusCode.BadGateway)
                }
                else -> error("unexpected auth path: ${request.url.encodedPath}")
            }
        })
        try {
            val auth = RealReceiptAuthService(
                httpClient = client,
                sessionSecretStore = store,
                loginUrl = "https://lkdr.nalog.ru/login",
                refreshUrl = "https://lkdr.nalog.ru/refresh",
                nowEpochMs = { now },
            )
            auth.login(ReceiptLoginRequest())
            val before = store.value
            now += 120_000L

            val tokenError = runCatching { auth.accessToken() }.exceptionOrNull()
            assertTrue(tokenError is ReceiptIntegrationException)
            assertEquals(1, refreshCalls)
            assertEquals(before, store.value)
            assertEquals("recoverable_error", auth.session().status)
            assertTrue(auth.session().retryable)
        } finally {
            client.close()
        }
    }

    @Test
    fun realLoginWithoutVerifiedEndpointIsExplicitlyUnavailable() = runBlocking {
        val store = MemoryReceiptSessionSecretStore()
        val client = HttpClient(MockEngine { error("login endpoint must not be guessed") })
        try {
            val auth = RealReceiptAuthService(client, store, loginUrl = null, refreshUrl = null)
            val response = auth.login(ReceiptLoginRequest())
            assertEquals("error", response.status)
            assertContains(response.message, "RECEIPTS_LOGIN_URL")
            assertNull(auth.accessToken())
            assertNotNull(auth.session())
        } finally {
            client.close()
        }
    }

    @Test
    fun realAuthRejectsUnverifiedTransportAndHostBeforeNetwork() {
        val client = HttpClient(MockEngine { error("unverified endpoint must not be called") })
        try {
            assertFailsWith<IllegalArgumentException> {
                RealReceiptAuthService(
                    httpClient = client,
                    sessionSecretStore = MemoryReceiptSessionSecretStore(),
                    loginUrl = "http://lkdr.nalog.ru/login",
                    refreshUrl = null,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                RealReceiptAuthService(
                    httpClient = client,
                    sessionSecretStore = MemoryReceiptSessionSecretStore(),
                    loginUrl = "https://evil.example/login",
                    refreshUrl = null,
                )
            }
        } finally {
            client.close()
        }
    }

    private companion object {
        const val FAKE_PHONE_FOR_TEST = "+79990000000"
    }
}

private class MemoryReceiptSessionSecretStore : ReceiptSessionSecretStore {
    var value: String? = null

    override fun read(): String? = value

    override fun write(value: String) {
        this.value = value
    }

    override fun delete() {
        value = null
    }
}

private fun MockRequestHandleScope.jsonResponse(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = body,
    status = status,
    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
)
