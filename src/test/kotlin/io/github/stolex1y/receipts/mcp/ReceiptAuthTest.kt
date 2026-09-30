package io.github.stolex1y.receipts.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReceiptAuthTest {
    @Test
    fun fakeLegacyAndBrowserLoginStayDeterministicAndNetworkFree() = runBlocking {
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
            assertEquals("error", auth.login(ReceiptLoginRequest(phone = FAKE_PHONE, otp = "111111")).status)
            assertEquals("requires_password", auth.login(ReceiptLoginRequest(phone = FAKE_PHONE, otp = "000000")).status)
            assertEquals(
                "authenticated",
                auth.login(ReceiptLoginRequest(phone = FAKE_PHONE, otp = "000000", password = "demo")).status,
            )
            assertTrue(service.search(ReceiptSearchInput()).receipts.isNotEmpty())
            assertEquals("login_required", auth.logout().status)

            assertEquals("active", auth.startBrowserLogin().status)
            assertEquals(FAKE_ACCESS_TOKEN_FOR_TEST, auth.accessToken())
            assertTrue(service.search(ReceiptSearchInput()).receipts.isNotEmpty())
            assertEquals("login_required", auth.logout().status)
        } finally {
            client.close()
        }
    }

    @Test
    fun browserCaptureAcceptsOnlyExactFnsOriginMethodAndPath() {
        val accepted = listOf(
            "https://lkdr.nalog.gov.ru/api/v2/auth/challenge/phone/start",
            "https://LKDR.NALOG.GOV.RU:443/api/v2/auth/challenge/phone/start",
        )
        accepted.forEach { url ->
            assertTrue(isVerifiedFnsAuthRequest(url, "POST", FNS_AUTH_START_PATH), url)
        }
        val rejected = listOf(
            "http://lkdr.nalog.gov.ru/api/v2/auth/challenge/phone/start",
            "https://lkdr.nalog.ru/api/v2/auth/challenge/phone/start",
            "https://lkdr.nalog.gov.ru.evil.example/api/v2/auth/challenge/phone/start",
            "https://evil.example@lkdr.nalog.gov.ru/api/v2/auth/challenge/phone/start",
            "https://lkdr.nalog.gov.ru:444/api/v2/auth/challenge/phone/start",
            "https://lkdr.nalog.gov.ru/api/v2/auth/challenge/phone/start/extra",
            "https://lkdr.nalog.gov.ru/api/v2/auth/challenge/phone/start?redirect=elsewhere",
        )
        rejected.forEach { url ->
            assertFalse(isVerifiedFnsAuthRequest(url, "POST", FNS_AUTH_START_PATH), url)
        }
        assertFalse(
            isVerifiedFnsAuthRequest(
                "https://lkdr.nalog.gov.ru/api/v2/auth/challenge/phone/start",
                "GET",
                FNS_AUTH_START_PATH,
            ),
        )
        assertFalse(
            isVerifiedFnsAuthRequest(
                "https://lkdr.nalog.gov.ru/api/v2/auth/challenge/phone/start",
                "post",
                FNS_AUTH_START_PATH,
            ),
        )
        assertFalse(
            isVerifiedFnsAuthRequest(
                "https://lkdr.nalog.gov.ru/api/v2/auth/challenge/phone/verify",
                "POST",
                FNS_AUTH_START_PATH,
            ),
        )
    }

    @Test
    fun captureWhitelistsDeviceInfoAndNeverNeedsVerifyRequestBody() {
        val capture = ReceiptBrowserResponseCapture()
        var startBodyReads = 0
        var responseBodyReads = 0
        val requestBody = """
            {
              "phone":"+79990000000",
              "captchaToken":"captcha-marker",
              "deviceInfo":{
                "appVersion":"1.0.0",
                "sourceDeviceId":"device-marker",
                "sourceType":"WEB",
                "metaDetails":{"userAgent":"browser-marker","other":"not-saved"},
                "extra":"not-saved"
              }
            }
        """.trimIndent()

        capture.observeStart(
            url = "$FNS_AUTH_ORIGIN$FNS_AUTH_START_PATH",
            method = "POST",
            status = 200,
            requestBody = { startBodyReads += 1; requestBody },
        )
        val session = capture.observeVerify(
            url = "$FNS_AUTH_ORIGIN$FNS_AUTH_VERIFY_PATH",
            method = "POST",
            status = 200,
            responseBody = {
                responseBodyReads += 1
                """{
                  "token":"access-secret-marker",
                  "refreshToken":"refresh-secret-marker",
                  "tokenExpireIn":"2030-01-02T03:04:05Z",
                  "refreshTokenExpiresIn":"2030-02-03T04:05:06Z",
                  "extra":"not-saved"
                }"""
            },
        ) ?: error("successful verify response was not captured")

        assertEquals(1, startBodyReads)
        assertEquals(1, responseBodyReads)
        assertEquals(setOf("appVersion", "sourceDeviceId", "sourceType", "metaDetails"), session.deviceInfo?.keys)
        assertEquals(
            setOf("userAgent"),
            session.deviceInfo?.get("metaDetails")?.jsonObject?.keys,
        )
        assertEquals("browser-marker", session.deviceInfo?.get("metaDetails")?.jsonObject?.get("userAgent")?.jsonPrimitive?.content)
        assertEquals("2030-01-02T03:04:05Z", session.tokenExpireIn)
        assertEquals("2030-02-03T04:05:06Z", session.refreshTokenExpiresIn)
        val persisted = encodeEnvelope(session)
        assertContains(persisted, "access-secret-marker")
        assertContains(persisted, "refresh-secret-marker")
        assertFalse(persisted.contains("captcha-marker"))
        assertFalse(persisted.contains("+79990000000"))
        assertFalse(persisted.contains("not-saved"))
        assertFalse(session.toString().contains("access-secret-marker"))
        assertFalse(session.toString().contains("refresh-secret-marker"))
        assertFalse(session.toString().contains("device-marker"))
    }

    @Test
    fun receiptAuthorizationToStringRedactsTokenAndCookie() {
        val diagnostic = ReceiptAuthorization(
            accessToken = "access-secret-marker",
            authCookie = "cookie-secret-marker",
            authCookieHost = "mco.nalog.ru",
        ).toString()

        assertEquals("ReceiptAuthorization(redacted)", diagnostic)
        assertFalse(diagnostic.contains("access-secret-marker"))
        assertFalse(diagnostic.contains("cookie-secret-marker"))
    }

    @Test
    fun ignoredAndUnsuccessfulCaptureEventsDoNotReadBodies() {
        val capture = ReceiptBrowserResponseCapture()
        var bodyRead = false
        capture.observeStart(
            url = "https://evil.example${FNS_AUTH_START_PATH}",
            method = "POST",
            status = 200,
            requestBody = { bodyRead = true; error("must not read unverified body") },
        )
        capture.observeStart(
            url = "$FNS_AUTH_ORIGIN$FNS_AUTH_START_PATH",
            method = "GET",
            status = 200,
            requestBody = { bodyRead = true; error("must not read wrong-method body") },
        )
        capture.observeStart(
            url = "$FNS_AUTH_ORIGIN$FNS_AUTH_VERIFY_PATH",
            method = "POST",
            status = 200,
            requestBody = { bodyRead = true; error("must not read wrong-path body") },
        )
        assertFailsWith<IllegalStateException> {
            capture.observeStart(
                url = "$FNS_AUTH_ORIGIN$FNS_AUTH_START_PATH",
                method = "POST",
                status = 401,
                requestBody = { bodyRead = true; error("must not read unsuccessful body") },
            )
        }
        assertFalse(bodyRead)
        assertFailsWith<IllegalStateException> {
            capture.observeVerify(
                url = "$FNS_AUTH_ORIGIN$FNS_AUTH_VERIFY_PATH",
                method = "POST",
                status = 401,
                responseBody = { bodyRead = true; error("must not read unsuccessful response") },
            )
        }
        assertFalse(bodyRead)
    }

    @Test
    fun browserLoginPersistsOnlyAfterCaptureAndReturnsSanitizedStatus() = runBlocking {
        val store = MemoryReceiptSessionSecretStore()
        val client = HttpClient(MockEngine { error("browser login capture must not use the HTTP client") })
        val auth = RealReceiptAuthService(client, store, ReceiptBrowserLoginCapture { newEnvelope() })
        try {
            assertEquals("authenticating", auth.startBrowserLogin().status)
            val final = awaitStatus(auth, "active")
            assertTrue(final.authenticated)
            assertEquals(PERSISTENCE_PERSISTED, final.persistenceStatus)
            assertEquals("access-secret", auth.accessToken())
            assertContains(store.value.orEmpty(), "refresh-secret")
            assertFalse(final.toString().contains("access-secret"))
            assertFalse(final.toString().contains("refresh-secret"))
            assertNull(final.message)
        } finally {
            client.close()
        }
    }

    @Test
    fun keyringWriteFailureDoesNotReportSuccessfulLoginOrReplaceExistingSession() = runBlocking {
        val store = MemoryReceiptSessionSecretStore(encodeEnvelope(oldEnvelope())).apply { failWrites = true }
        val client = HttpClient(MockEngine { error("browser login capture must not use the HTTP client") })
        val auth = RealReceiptAuthService(client, store, ReceiptBrowserLoginCapture { newEnvelope() })
        try {
            auth.restore()
            val previousStoredValue = store.value
            val start = auth.startBrowserLogin()
            assertEquals("authenticating", start.status)
            val failed = awaitStatus(auth, "active")

            assertTrue(failed.authenticated)
            assertEquals("active", failed.status)
            assertFalse(failed.retryable)
            assertEquals(PERSISTENCE_UNAVAILABLE, failed.persistenceStatus)
            assertContains(failed.message.orEmpty(), "Не удалось сохранить")
            assertEquals("old-access", auth.accessToken())
            assertEquals(previousStoredValue, store.value)
        } finally {
            client.close()
        }
    }

    @Test
    fun browserCaptureFailureReturnsSafeMessageAndPreservesPriorSession() = runBlocking {
        val original = encodeEnvelope(oldEnvelope())
        val store = MemoryReceiptSessionSecretStore(original)
        val client = HttpClient(MockEngine { error("browser capture failure must not use HTTP") })
        val auth = RealReceiptAuthService(
            client,
            store,
            ReceiptBrowserLoginCapture { throw IllegalStateException("otp-secret-marker") },
        )
        try {
            auth.restore()
            assertEquals("authenticating", auth.startBrowserLogin().status)
            val final = awaitStatus(auth, "active")
            assertTrue(final.authenticated)
            assertFalse(final.retryable)
            assertContains(final.message.orEmpty(), "предыдущая session сохранена")
            assertFalse(final.message.orEmpty().contains("otp-secret-marker"))
            assertEquals(original, store.value)
            assertEquals("old-access", auth.accessToken())
        } finally {
            client.close()
        }
    }

    @Test
    fun browserCaptureFailureWithoutPriorSessionRequiresLogin() = runBlocking {
        val store = MemoryReceiptSessionSecretStore()
        val client = HttpClient(MockEngine { error("browser capture failure must not use HTTP") })
        val auth = RealReceiptAuthService(
            client,
            store,
            ReceiptBrowserLoginCapture { throw IllegalStateException("otp-secret-marker") },
        )
        try {
            assertEquals("authenticating", auth.startBrowserLogin().status)
            val final = awaitStatus(auth, "login_required")
            assertFalse(final.authenticated)
            assertFalse(final.retryable)
            assertContains(final.message.orEmpty(), "повторите browser login")
            assertFalse(final.message.orEmpty().contains("otp-secret-marker"))
            assertNull(store.value)
        } finally {
            client.close()
        }
    }

    @Test
    fun expiredPreV30V1KeyringBytesSurviveRestoreAndFailedBrowserLoginUnchanged() = runBlocking {
        val legacyBytes =
            """{"version":1,"access_token":"legacy-access","refresh_token":"legacy-refresh","session_id":"legacy-session","auth_cookie":"legacy-cookie","auth_cookie_host":"mco.nalog.ru","expires_in_seconds":3600,"token_issued_at_epoch_ms":1730000000000}"""
        assertFalse(legacyBytes.contains("device_info"))
        assertFalse(legacyBytes.contains("token_expire_in"))
        assertFalse(legacyBytes.contains("refresh_token_expires_in"))
        val store = MemoryReceiptSessionSecretStore(legacyBytes)
        val client = HttpClient(MockEngine { error("failed browser login must not use HTTP") })
        val auth = RealReceiptAuthService(
            client,
            store,
            ReceiptBrowserLoginCapture { throw IllegalStateException("capture-failure-marker") },
        )
        try {
            auth.restore()
            assertEquals("recoverable_error", auth.session().status)
            assertEquals(legacyBytes, store.value)
            assertEquals("authenticating", auth.startBrowserLogin().status)
            val failed = awaitStatus(auth, "recoverable_error")

            assertFalse(failed.authenticated)
            assertContains(failed.message.orEmpty(), "browser login")
            assertFalse(failed.message.orEmpty().contains("capture-failure-marker"))
            assertEquals("legacy-access", auth.accessToken())
            assertEquals(legacyBytes, store.value)
        } finally {
            client.close()
        }
    }

    @Test
    fun realLegacyJsonAuthIsDisabledWithoutNetworkAccess() = runBlocking {
        var calls = 0
        val client = HttpClient(MockEngine { calls += 1; error("legacy real login must not call network") })
        try {
            val auth = RealReceiptAuthService(client, MemoryReceiptSessionSecretStore())
            assertEquals("error", auth.login(ReceiptLoginRequest(phone = FAKE_PHONE, password = "p", otp = "o")).status)
            assertEquals("error", auth.resendOtp(FAKE_PHONE).status)
            assertEquals(0, calls)
            assertNull(auth.accessToken())
        } finally {
            client.close()
        }
    }

    @Test
    fun privateSearchRefreshesARejectedRestoredSessionWithPersistedRefreshContext() = runBlocking {
        val old = oldEnvelope()
        val store = MemoryReceiptSessionSecretStore(encodeEnvelope(old))
        val refreshCalls = AtomicInteger()
        val apiAuthorizations = CopyOnWriteArrayList<String?>()
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "lkdr.nalog.gov.ru") {
                refreshCalls.incrementAndGet()
                val body = Json.parseToJsonElement(bodyText(request)).jsonObject
                assertEquals(setOf("deviceInfo", "refreshToken"), body.keys)
                assertEquals(old.deviceInfo, body["deviceInfo"])
                assertEquals("old-refresh", body["refreshToken"]?.jsonPrimitive?.content)
                jsonResponse("""{"token":"rotated-access","refreshToken":"rotated-refresh"}""")
            } else {
                apiAuthorizations += request.headers[HttpHeaders.Authorization]
                if (request.headers[HttpHeaders.Authorization] == "Bearer old-access") {
                    jsonResponse("{}", HttpStatusCode.Unauthorized)
                } else {
                    jsonResponse("""{"brands":[],"receipts":[],"hasMore":false}""")
                }
            }
        })
        try {
            val auth = RealReceiptAuthService(client, store)
            auth.restore()
            assertEquals("active", auth.session().status)
            assertEquals(0, refreshCalls.get(), "session polling must remain network-free")
            val service = ReceiptService(client, ReceiptMode.PRIVATE, sessionProvider = auth)

            val result = service.search(ReceiptSearchInput())

            assertTrue(result.receipts.isEmpty())
            assertEquals(listOf<String?>("Bearer old-access", "Bearer rotated-access"), apiAuthorizations.toList())
            assertEquals(1, refreshCalls.get())
            assertEquals("rotated-access", auth.accessToken())
            assertContains(store.value.orEmpty(), "rotated-refresh")
            assertEquals("active", auth.session().status)
        } finally {
            client.close()
        }
    }

    @Test
    fun privateDetailRefreshesOnForbiddenAndRetriesOnlyOnce() = runBlocking {
        val store = MemoryReceiptSessionSecretStore(encodeEnvelope(oldEnvelope()))
        val refreshCalls = AtomicInteger()
        val apiAuthorizations = CopyOnWriteArrayList<String?>()
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "lkdr.nalog.gov.ru") {
                refreshCalls.incrementAndGet()
                jsonResponse("""{"token":"rotated-access","refreshToken":"rotated-refresh"}""")
            } else {
                apiAuthorizations += request.headers[HttpHeaders.Authorization]
                if (request.headers[HttpHeaders.Authorization] == "Bearer old-access") {
                    jsonResponse("{}", HttpStatusCode.Forbidden)
                } else {
                    jsonResponse(
                        """{
                          "dateTime":"2026-09-10T12:00:00",
                          "fiscalDocumentNumber":1001,
                          "fiscalDriveNumber":"999900010001",
                          "fiscalSign":"700001",
                          "items":[],
                          "kktRegId":"0001112223334444",
                          "totalSum":349.0
                        }""",
                    )
                }
            }
        })
        try {
            val auth = RealReceiptAuthService(client, store)
            auth.restore()
            val service = ReceiptService(client, ReceiptMode.PRIVATE, sessionProvider = auth)

            val result = service.getReceipt("synthetic-receipt-key")

            assertEquals("synthetic-receipt-key", result.receiptKey)
            assertEquals(listOf<String?>("Bearer old-access", "Bearer rotated-access"), apiAuthorizations.toList())
            assertEquals(1, refreshCalls.get())
            assertEquals("active", auth.session().status)
        } finally {
            client.close()
        }
    }

    @Test
    fun transientRefreshFailureStaysRecoverableInsteadOfRequiringLogin() = runBlocking {
        val original = encodeEnvelope(oldEnvelope())
        val store = MemoryReceiptSessionSecretStore(original)
        val refreshCalls = AtomicInteger()
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "lkdr.nalog.gov.ru") {
                refreshCalls.incrementAndGet()
                jsonResponse("{}", HttpStatusCode.BadGateway)
            } else {
                jsonResponse("{}", HttpStatusCode.Unauthorized)
            }
        })
        try {
            val auth = RealReceiptAuthService(client, store)
            auth.restore()
            val service = ReceiptService(client, ReceiptMode.PRIVATE, sessionProvider = auth)

            assertFailsWith<ReceiptIntegrationException> {
                service.search(ReceiptSearchInput())
            }

            val state = auth.session()
            assertEquals("recoverable_error", state.status)
            assertFalse(state.authenticated)
            assertTrue(state.retryable)
            assertEquals(1, refreshCalls.get())
            assertEquals(original, store.value)
        } finally {
            client.close()
        }
    }

    @Test
    fun rejectedRefreshRequiresLoginWithoutRetryLoop() = runBlocking {
        val store = MemoryReceiptSessionSecretStore(encodeEnvelope(oldEnvelope()))
        val apiCalls = AtomicInteger()
        val refreshCalls = AtomicInteger()
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "lkdr.nalog.gov.ru") {
                refreshCalls.incrementAndGet()
                jsonResponse("{}", HttpStatusCode.Forbidden)
            } else {
                apiCalls.incrementAndGet()
                jsonResponse("{}", HttpStatusCode.Unauthorized)
            }
        })
        try {
            val auth = RealReceiptAuthService(client, store)
            auth.restore()
            val service = ReceiptService(client, ReceiptMode.PRIVATE, sessionProvider = auth)

            assertFailsWith<ReceiptAuthenticationException> {
                service.search(ReceiptSearchInput())
            }

            assertEquals("login_required", auth.session().status)
            assertFalse(auth.session().authenticated)
            assertEquals(1, apiCalls.get())
            assertEquals(1, refreshCalls.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun secondPrivateRejectionAfterRefreshRequiresLoginAndDoesNotLoop() = runBlocking {
        val store = MemoryReceiptSessionSecretStore(encodeEnvelope(oldEnvelope()))
        val apiAuthorizations = CopyOnWriteArrayList<String?>()
        val refreshCalls = AtomicInteger()
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "lkdr.nalog.gov.ru") {
                refreshCalls.incrementAndGet()
                jsonResponse("""{"token":"rotated-access","refreshToken":"rotated-refresh"}""")
            } else {
                apiAuthorizations += request.headers[HttpHeaders.Authorization]
                jsonResponse("{}", HttpStatusCode.Unauthorized)
            }
        })
        try {
            val auth = RealReceiptAuthService(client, store)
            auth.restore()
            val service = ReceiptService(client, ReceiptMode.PRIVATE, sessionProvider = auth)

            assertFailsWith<ReceiptAuthenticationException> {
                service.search(ReceiptSearchInput())
            }

            assertEquals(listOf<String?>("Bearer old-access", "Bearer rotated-access"), apiAuthorizations.toList())
            assertEquals(1, refreshCalls.get())
            assertEquals("login_required", auth.session().status)
        } finally {
            client.close()
        }
    }


    @Test
    fun concurrentRejectedRequestsShareOneRefreshAndRetryWithTheRotatedToken() = runBlocking {
        val store = MemoryReceiptSessionSecretStore(encodeEnvelope(oldEnvelope()))
        val oldRequestsArrived = CompletableDeferred<Unit>()
        val oldRequestCount = AtomicInteger()
        val refreshCalls = AtomicInteger()
        val apiAuthorizations = CopyOnWriteArrayList<String?>()
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "lkdr.nalog.gov.ru") {
                refreshCalls.incrementAndGet()
                jsonResponse("""{"token":"rotated-access","refreshToken":"rotated-refresh"}""")
            } else {
                val authorization = request.headers[HttpHeaders.Authorization]
                apiAuthorizations += authorization
                if (authorization == "Bearer old-access") {
                    if (oldRequestCount.incrementAndGet() == 2) oldRequestsArrived.complete(Unit)
                    withTimeout(5_000) { oldRequestsArrived.await() }
                    jsonResponse("{}", HttpStatusCode.Unauthorized)
                } else {
                    jsonResponse("""{"brands":[],"receipts":[],"hasMore":false}""")
                }
            }
        })
        try {
            val auth = RealReceiptAuthService(client, store)
            auth.restore()
            val service = ReceiptService(client, ReceiptMode.PRIVATE, sessionProvider = auth)

            val results = listOf(
                async(Dispatchers.Default) { service.search(ReceiptSearchInput()) },
                async(Dispatchers.Default) { service.search(ReceiptSearchInput()) },
            ).awaitAll()

            assertEquals(2, results.size)
            assertEquals(2, oldRequestCount.get())
            assertEquals(1, refreshCalls.get())
            assertEquals(4, apiAuthorizations.size)
            assertEquals(2, apiAuthorizations.count { it == "Bearer old-access" })
            assertEquals(2, apiAuthorizations.count { it == "Bearer rotated-access" })
        } finally {
            client.close()
        }
    }

    @Test
    fun expiredMetadataIsReportedWithoutRefreshingDuringSessionPolling() = runBlocking {
        val expired = oldEnvelope().copy(tokenExpireIn = Instant.now().minusSeconds(60).toString())
        val store = MemoryReceiptSessionSecretStore(encodeEnvelope(expired))
        var httpCalls = 0
        val client = HttpClient(MockEngine { httpCalls += 1; error("session polling must not use HTTP") })
        try {
            val auth = RealReceiptAuthService(client, store)
            auth.restore()

            val state = auth.session()

            assertEquals("recoverable_error", state.status)
            assertFalse(state.authenticated)
            assertTrue(state.retryable)
            assertEquals(0, httpCalls)
        } finally {
            client.close()
        }
    }

    @Test
    fun realSessionPollingDoesNotRefreshAndExplicitRetryUsesRefreshTokenAndDeviceInfo() = runBlocking {
        val old = oldEnvelope()
        val store = MemoryReceiptSessionSecretStore(encodeEnvelope(old))
        var refreshCalls = 0
        var mockEngineFailure: Throwable? = null
        val client = HttpClient(MockEngine { request ->
            try {
                refreshCalls += 1
                assertEquals(HttpMethod.Post, request.method)
                val contentType = (request.body as? OutgoingContent)?.contentType?.toString().orEmpty()
                assertTrue(
                    contentType.contains("charset", ignoreCase = true) &&
                        contentType.contains("utf-8", ignoreCase = true),
                    "Unexpected refresh body Content-Type: $contentType",
                )
                assertEquals(FNS_AUTH_ORIGIN, "${request.url.protocol.name}://${request.url.host}")
                assertEquals(FNS_AUTH_REFRESH_URL.substringAfter(FNS_AUTH_ORIGIN), request.url.encodedPath)
                val body = Json.parseToJsonElement(bodyText(request)).jsonObject
                assertEquals(setOf("deviceInfo", "refreshToken"), body.keys)
                assertEquals("old-refresh", body["refreshToken"]?.jsonPrimitive?.content)
                assertEquals(old.deviceInfo, body["deviceInfo"])
                jsonResponse(
                    """{
                      "token":"new-access",
                      "refreshToken":"new-refresh",
                      "tokenExpireIn":"2031-01-02T03:04:05Z",
                      "refreshTokenExpiresIn":"2031-02-03T04:05:06Z",
                      "notPersisted":"extra-marker"
                    }""",
                )
            } catch (error: Throwable) {
                mockEngineFailure = error
                throw error
            }
        })
        try {
            val auth = RealReceiptAuthService(client, store)
            auth.restore()
            assertEquals("active", auth.session().status)
            repeat(3) { assertEquals("active", auth.session().status) }
            assertEquals(0, refreshCalls)
            assertEquals(encodeEnvelope(old), store.value)

            val retried = auth.retrySession()
            assertEquals("active", retried.status, mockEngineFailure?.toString() ?: retried.message)
            assertEquals(1, refreshCalls)
            assertEquals("new-access", auth.accessToken())
            assertContains(store.value.orEmpty(), "new-refresh")
            assertFalse(store.value.orEmpty().contains("extra-marker"))
            assertContains(store.value.orEmpty(), "device_info")
        } finally {
            client.close()
        }
    }

    @Test
    fun cancelledRefreshOwnerCleansItsFlightBeforeRetrying() = runBlocking {
        val old = oldEnvelope()
        val store = MemoryReceiptSessionSecretStore(encodeEnvelope(old))
        val firstRefreshStarted = CompletableDeferred<Unit>()
        val firstRefreshCancelled = CompletableDeferred<Unit>()
        val refreshCalls = AtomicInteger()
        val client = HttpClient(MockEngine { request ->
            assertEquals("lkdr.nalog.gov.ru", request.url.host)
            val refreshCall = refreshCalls.incrementAndGet()
            val body = Json.parseToJsonElement(bodyText(request)).jsonObject
            assertEquals(setOf("deviceInfo", "refreshToken"), body.keys)
            assertEquals("old-refresh", body["refreshToken"]?.jsonPrimitive?.content)
            assertEquals(old.deviceInfo, body["deviceInfo"])
            if (refreshCall == 1) {
                firstRefreshStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    firstRefreshCancelled.complete(Unit)
                }
            }
            jsonResponse("""{"token":"new-access","refreshToken":"new-refresh"}""")
        })
        try {
            val auth = RealReceiptAuthService(client, store)
            auth.restore()
            val mutex = RealReceiptAuthService::class.java
                .getDeclaredField("mutex")
                .apply { isAccessible = true }
                .get(auth) as Mutex
            val owner = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                auth.retrySession()
            }

            firstRefreshStarted.await()
            mutex.lock()
            try {
                owner.cancel()
                withTimeout(5_000) { firstRefreshCancelled.await() }
                assertFalse(owner.isCompleted, "refresh cleanup must wait for the held mutex")
            } finally {
                mutex.unlock()
            }
            withTimeout(5_000) { owner.join() }

            val retried = auth.retrySession()

            assertEquals("active", retried.status)
            assertEquals(2, refreshCalls.get())
            assertEquals("new-access", auth.accessToken())
            assertContains(store.value.orEmpty(), "new-refresh")
        } finally {
            client.close()
        }
    }

    @Test
    fun callerQueuedBeforeCancelledFlightCleanupBecomesNewRefreshOwner() = runBlocking {
        val old = oldEnvelope()
        val store = MemoryReceiptSessionSecretStore(encodeEnvelope(old))
        val firstRefreshStarted = CompletableDeferred<Unit>()
        val firstRefreshCancelled = CompletableDeferred<Unit>()
        val refreshCalls = AtomicInteger()
        val client = HttpClient(MockEngine { request ->
            assertEquals("lkdr.nalog.gov.ru", request.url.host)
            val refreshCall = refreshCalls.incrementAndGet()
            if (refreshCall == 1) {
                firstRefreshStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    firstRefreshCancelled.complete(Unit)
                }
            }
            jsonResponse("""{"token":"new-access","refreshToken":"new-refresh"}""")
        })
        try {
            val auth = RealReceiptAuthService(client, store)
            auth.restore()
            val mutex = RealReceiptAuthService::class.java
                .getDeclaredField("mutex")
                .apply { isAccessible = true }
                .get(auth) as Mutex
            val owner = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                auth.refreshAfterRejection("old-access", null)
            }

            firstRefreshStarted.await()
            val refreshFlight = RealReceiptAuthService::class.java
                .getDeclaredField("refreshFlight")
                .apply { isAccessible = true }
                .get(auth) ?: error("refresh flight must be reserved before its request starts")
            val flightResult = refreshFlight.javaClass
                .getDeclaredField("result")
                .apply { isAccessible = true }
                .get(refreshFlight) as CompletableDeferred<*>

            mutex.lock()
            val queuedCaller = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                auth.refreshAfterRejection("old-access", null)
            }
            try {
                assertFalse(queuedCaller.isCompleted, "new caller must queue on the held auth mutex")
                owner.cancel()
                withTimeout(5_000) { firstRefreshCancelled.await() }
                withTimeout(5_000) { flightResult.join() }
                assertTrue(flightResult.isCancelled, "owner cancellation must complete the old flight")
                assertFalse(owner.isCompleted, "old flight cleanup must still wait for the held mutex")
            } finally {
                mutex.unlock()
            }

            val authorization = withTimeout(5_000) { queuedCaller.await() }
            withTimeout(5_000) { owner.join() }

            assertEquals("new-access", authorization?.accessToken)
            assertEquals(2, refreshCalls.get())
            assertEquals("new-access", auth.accessToken())
            assertContains(store.value.orEmpty(), "new-refresh")
        } finally {
            client.close()
        }
    }


    @Test
    fun cancelledRefreshFailureOwnerCompletesWaitersBeforeRetrying() = runBlocking {
        val old = oldEnvelope()
        val store = MemoryReceiptSessionSecretStore(encodeEnvelope(old))
        val firstRefreshStarted = CompletableDeferred<Unit>()
        val allowFirstFailure = CompletableDeferred<Unit>()
        val refreshCalls = AtomicInteger()
        val client = HttpClient(MockEngine { request ->
            assertEquals("lkdr.nalog.gov.ru", request.url.host)
            val refreshCall = refreshCalls.incrementAndGet()
            if (refreshCall == 1) {
                firstRefreshStarted.complete(Unit)
                allowFirstFailure.await()
                jsonResponse("{}", HttpStatusCode.Unauthorized)
            } else {
                jsonResponse("""{"token":"new-access","refreshToken":"new-refresh"}""")
            }
        })
        try {
            val auth = RealReceiptAuthService(client, store)
            auth.restore()
            val mutex = RealReceiptAuthService::class.java
                .getDeclaredField("mutex")
                .apply { isAccessible = true }
                .get(auth) as Mutex
            val owner = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                auth.refreshAfterRejection("old-access", null)
            }

            firstRefreshStarted.await()
            val waiter = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                auth.refreshAfterRejection("old-access", null)
            }
            assertFalse(waiter.isCompleted, "concurrent request must wait for the active refresh")

            mutex.lock()
            try {
                allowFirstFailure.complete(Unit)
                assertFalse(owner.isCompleted, "failed refresh handler must wait for the held mutex")
                owner.cancel()
            } finally {
                mutex.unlock()
            }
            withTimeout(5_000) { owner.join() }
            withTimeout(5_000) { waiter.join() }
            assertTrue(waiter.isCancelled, "waiter must be released when the owner is cancelled")

            val retried = auth.retrySession()

            assertEquals("active", retried.status)
            assertEquals(2, refreshCalls.get())
            assertEquals("new-access", auth.accessToken())
            assertContains(store.value.orEmpty(), "new-refresh")
        } finally {
            allowFirstFailure.complete(Unit)
            client.close()
        }
    }


    @Test
    fun refreshFailureIsRetryableAndKeepsStoredSession() = runBlocking {
        val original = encodeEnvelope(oldEnvelope())
        val store = MemoryReceiptSessionSecretStore(original)
        val client = HttpClient(MockEngine { jsonResponse("{}", HttpStatusCode.BadGateway) })
        try {
            val auth = RealReceiptAuthService(client, store)
            auth.restore()
            assertEquals("active", auth.session().status)
            val failed = auth.retrySession()
            assertEquals("recoverable_error", failed.status)
            assertTrue(failed.retryable)
            assertTrue(failed.authenticated)
            assertEquals(original, store.value)
            assertEquals("old-access", auth.accessToken())
        } finally {
            client.close()
        }
    }

    @Test
    fun refreshRedirectDoesNotForwardRequestToLocationTarget() = runBlocking {
        val original = encodeEnvelope(oldEnvelope())
        val store = MemoryReceiptSessionSecretStore(original)
        val requestHosts = mutableListOf<String>()
        val client = noRedirectHttpClient(
            MockEngine { request ->
                requestHosts += request.url.host
                if (request.url.host == "lkdr.nalog.gov.ru") {
                    respond(
                        content = "",
                        status = HttpStatusCode.TemporaryRedirect,
                        headers = headersOf(HttpHeaders.Location, "https://attacker.example/collect"),
                    )
                } else {
                    jsonResponse("""{"token":"attacker-access","refreshToken":"attacker-refresh"}""")
                }
            },
        )
        try {
            val auth = RealReceiptAuthService(client, store)
            auth.restore()

            val failed = auth.retrySession()

            assertEquals("recoverable_error", failed.status)
            assertTrue(failed.retryable)
            assertEquals(listOf("lkdr.nalog.gov.ru"), requestHosts)
            assertEquals(original, store.value)
            assertEquals("old-access", auth.accessToken())
        } finally {
            client.close()
        }
    }

    @Test
    fun logoutDeletesTheAppOwnedSession() = runBlocking {
        val store = MemoryReceiptSessionSecretStore(encodeEnvelope(oldEnvelope()))
        val client = HttpClient(MockEngine { error("logout must not use network") })
        try {
            val auth = RealReceiptAuthService(client, store)
            auth.restore()
            val result = auth.logout()
            assertEquals("login_required", result.status)
            assertFalse(result.retryable)
            assertFalse(result.authenticated)
            assertNull(store.value)
            assertNull(auth.accessToken())
        } finally {
            client.close()
        }
    }

    @Test
    fun keyringDeleteFailureKeepsExistingSession() = runBlocking {
        val original = encodeEnvelope(oldEnvelope())
        val store = MemoryReceiptSessionSecretStore(original).apply { failDeletes = true }
        val client = HttpClient(MockEngine { error("logout must not use network") })
        try {
            val auth = RealReceiptAuthService(client, store)
            auth.restore()
            val result = auth.logout()
            assertEquals("logout_failed", result.status)
            assertFalse(result.retryable)
            assertTrue(result.authenticated)
            assertEquals(original, store.value)
            assertEquals("old-access", auth.accessToken())
        } finally {
            client.close()
        }
    }

    @Test
    fun crossOriginAuthActionsCannotStartLoginOrLogout() = testApplication {
        val auth = FakeReceiptAuthService()
        application {
            install(ContentNegotiation) { json() }
            routing { installReceiptAuthRoutes(auth, ReceiptMode.PRIVATE, expectedPort = 80) }
        }

        val rejectedLogin = client.post("/receipts/browser-login") {
            header(HttpHeaders.Origin, "https://attacker.example")
            header("Sec-Fetch-Site", "cross-site")
        }
        assertEquals(HttpStatusCode.Forbidden, rejectedLogin.status)
        assertEquals("login_required", auth.session().status)

        val rejectedRebindingLogin = client.post("/receipts/browser-login") {
            header("Host", "attacker.example")
            header(HttpHeaders.Origin, "http://attacker.example")
        }
        assertEquals(HttpStatusCode.Forbidden, rejectedRebindingLogin.status)
        assertEquals("login_required", auth.session().status)
        val rejectedLoopbackOriginHostMismatch = client.post("/receipts/browser-login") {
            header("Host", "localhost")
            header(HttpHeaders.Origin, "http://127.0.0.1")
        }
        assertEquals(HttpStatusCode.Forbidden, rejectedLoopbackOriginHostMismatch.status)
        assertEquals("login_required", auth.session().status)
        val rejectedLoopbackOriginPortMismatch = client.post("/receipts/browser-login") {
            header("Host", "localhost")
            header(HttpHeaders.Origin, "http://localhost:81")
        }
        assertEquals(HttpStatusCode.Forbidden, rejectedLoopbackOriginPortMismatch.status)
        assertEquals("login_required", auth.session().status)
        val rejectedOriginlessHostLogin = client.post("/receipts/browser-login") {
            header("Host", "attacker.example")
        }
        assertEquals(HttpStatusCode.Forbidden, rejectedOriginlessHostLogin.status)
        assertEquals("login_required", auth.session().status)
        val rejectedUnexpectedPort = client.post("/receipts/browser-login") {
            header("Host", "localhost:81")
        }
        assertEquals(HttpStatusCode.Forbidden, rejectedUnexpectedPort.status)
        assertEquals("login_required", auth.session().status)


        assertEquals(HttpStatusCode.OK, client.post("/receipts/browser-login") {
            header("Host", "localhost")
        }.status)
        assertEquals("active", auth.session().status)
        val rejectedOriginlessHostRetry = client.post("/receipts/session/retry") {
            header("Host", "attacker.example")
        }
        assertEquals(HttpStatusCode.Forbidden, rejectedOriginlessHostRetry.status)
        assertEquals("active", auth.session().status)

        val rejectedOriginlessHostLogout = client.post("/receipts/logout") {
            header("Host", "attacker.example")
        }
        assertEquals(HttpStatusCode.Forbidden, rejectedOriginlessHostLogout.status)
        assertEquals("active", auth.session().status)


        val rejectedLogout = client.post("/receipts/logout") {
            header(HttpHeaders.Origin, "https://attacker.example")
            header("Sec-Fetch-Site", "cross-site")
        }
        assertEquals(HttpStatusCode.Forbidden, rejectedLogout.status)
        assertEquals("active", auth.session().status)
    }

    @Test
    fun realRoutesStartBrowserLoginAndRejectLegacyBodiesBeforeReadingThem() = testApplication {
        val auth = FakeReceiptAuthService()
        application {
            install(ContentNegotiation) { json() }
            routing { installReceiptAuthRoutes(auth, ReceiptMode.PRIVATE, expectedPort = 80) }
        }

        val bodyfulBrowserLogin = client.post("/receipts/browser-login") {
            header("Host", "localhost")
            contentType(ContentType.Application.Json)
            setBody("{\"phone\":\"phone-marker\",\"password\":\"password-marker\",\"otp\":\"otp-marker\"}")
        }
        assertEquals(HttpStatusCode.BadRequest, bodyfulBrowserLogin.status)
        val rejectedBrowserBody = bodyfulBrowserLogin.bodyAsText()
        assertFalse(rejectedBrowserBody.contains("phone-marker"))
        assertFalse(rejectedBrowserBody.contains("otp-marker"))
        assertEquals("login_required", auth.session().status)

        val browserLogin = client.post("/receipts/browser-login") {
            header("Host", "localhost")
        }
        assertEquals(HttpStatusCode.OK, browserLogin.status)
        val browserLoginJson = browserLogin.bodyAsText()
        assertContains(browserLoginJson, "active")
        assertFalse(browserLoginJson.contains(FAKE_ACCESS_TOKEN_FOR_TEST))
        assertEquals(HttpStatusCode.MethodNotAllowed, client.get("/receipts/browser-login").status)

        val login = client.post("/receipts/login") {
            header("Host", "localhost")
            contentType(ContentType.Application.Json)
            setBody("{\"phone\":\"phone-marker\",\"password\":\"password-marker\",\"otp\":\"otp-marker\"}")
        }
        assertEquals(HttpStatusCode.Forbidden, login.status)
        val loginJson = login.bodyAsText()
        assertFalse(loginJson.contains("phone-marker"))
        assertFalse(loginJson.contains("password-marker"))

        val otp = client.post("/receipts/otp/resend") {
            header("Host", "localhost")
            contentType(ContentType.Application.Json)
            setBody("{\"phone\":\"+79990000000\",\"otp\":\"otp-marker\"}")
        }
        assertEquals(HttpStatusCode.Forbidden, otp.status)
        assertFalse(otp.bodyAsText().contains("otp-marker"))

        val session = client.get("/receipts/session")
        assertEquals(HttpStatusCode.OK, session.status)
        assertContains(session.bodyAsText(), "authenticated")
        val retry = client.post("/receipts/session/retry") {
            header("Host", "localhost")
        }
        assertEquals(HttpStatusCode.OK, retry.status)
        val logout = client.post("/receipts/logout") {
            header("Host", "localhost")
        }
        assertEquals(HttpStatusCode.OK, logout.status)
    }

    private companion object {
        const val FAKE_PHONE = "+79990000000"
        const val FAKE_ACCESS_TOKEN_FOR_TEST = "fake-receipts-session"
    }
}

private class MemoryReceiptSessionSecretStore(initialValue: String? = null) : ReceiptSessionSecretStore {
    var value: String? = initialValue
    var failWrites: Boolean = false
    var failDeletes: Boolean = false

    override fun read(): String? = value

    override fun write(value: String) {
        if (failWrites) throw ReceiptSessionSecretStoreUnavailableException()
        this.value = value
    }

    override fun delete() {
        if (failDeletes) throw ReceiptSessionSecretStoreUnavailableException()
        value = null
    }
}

private fun oldEnvelope() = ReceiptSessionEnvelope(
    accessToken = "old-access",
    refreshToken = "old-refresh",
    deviceInfo = testDeviceInfo(),
)

private fun newEnvelope() = ReceiptSessionEnvelope(
    accessToken = "access-secret",
    refreshToken = "refresh-secret",
    deviceInfo = testDeviceInfo(),
    tokenExpireIn = "2030-01-02T03:04:05Z",
    refreshTokenExpiresIn = "2030-02-03T04:05:06Z",
)

private fun testDeviceInfo() = extractReceiptDeviceInfo(
    """{
      "phone":"not-persisted",
      "captchaToken":"not-persisted",
      "deviceInfo":{
        "appVersion":"1.0.0",
        "sourceDeviceId":"device-marker",
        "sourceType":"WEB",
        "metaDetails":{"userAgent":"browser-marker"},
        "ignored":"not-persisted"
      }
    }""",
)

private fun encodeEnvelope(envelope: ReceiptSessionEnvelope): String = Json.encodeToString(envelope)

private suspend fun awaitStatus(auth: RealReceiptAuthService, status: String): ReceiptSessionResponse =
    withTimeout(5_000) {
        while (true) {
            val current = auth.session()
            if (current.status == status) return@withTimeout current
            delay(5)
        }
        error("unreachable")
    }

private fun MockRequestHandleScope.jsonResponse(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = body,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
)

private fun bodyText(request: io.ktor.client.request.HttpRequestData): String = when (val body = request.body) {
    is TextContent -> body.text
    is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
    else -> error("Unexpected request body: ${body::class.qualifiedName}")
}
