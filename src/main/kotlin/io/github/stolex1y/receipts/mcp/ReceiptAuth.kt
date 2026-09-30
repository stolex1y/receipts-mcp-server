package io.github.stolex1y.receipts.mcp

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.time.Instant
import java.util.Locale

private const val FAKE_PHONE = "+79990000000"
private const val FAKE_OTP = "000000"
private const val FAKE_PASSWORD = "demo"
private const val FAKE_ACCESS_TOKEN = "fake-receipts-session"
private val RECEIPTS_ALLOWED_UPSTREAM_HOSTS = setOf("mco.nalog.ru")

internal fun validateReceiptUpstreamUrl(value: String, field: String): String {
    val trimmed = value.trim()
    require(trimmed.isNotEmpty()) { "$field не задан." }
    val uri = runCatching { URI(trimmed) }.getOrNull()
        ?: throw IllegalArgumentException("$field имеет недопустимый URL.")
    val host = uri.host?.lowercase(Locale.ROOT)
    require(uri.scheme.equals("https", ignoreCase = true)) {
        "$field должен использовать HTTPS."
    }
    require(host in RECEIPTS_ALLOWED_UPSTREAM_HOSTS) {
        "$field указывает на неподтверждённый host."
    }
    require(uri.port == -1 || uri.port == 443) {
        "$field использует недопустимый port."
    }
    require(uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null) {
        "$field не должен содержать userinfo, query или fragment."
    }
    require(uri.path.isNotBlank() && uri.path != "/") {
        "$field должен содержать endpoint path."
    }
    return trimmed
}

internal fun upstreamHost(value: String): String? =
    runCatching { URI(value).host?.lowercase(Locale.ROOT) }.getOrNull()


internal const val PERSISTENCE_NOT_CONFIGURED = "not_configured"
internal const val PERSISTENCE_AVAILABLE = "available"
internal const val PERSISTENCE_PERSISTED = "persisted"
internal const val PERSISTENCE_MEMORY_ONLY = "memory_only"
internal const val PERSISTENCE_UNAVAILABLE = "unavailable"

@Serializable
data class ReceiptLoginRequest(
    val phone: String = "",
    val password: String = "",
    val otp: String = "",
)

@Serializable
data class ReceiptLoginResponse(
    val status: String,
    val message: String,
    @SerialName("requires_otp") val requiresOtp: Boolean = false,
    @SerialName("requires_password") val requiresPassword: Boolean = false,
    val authenticated: Boolean = false,
    @SerialName("persistence_status") val persistenceStatus: String = PERSISTENCE_NOT_CONFIGURED,
    @SerialName("persistence_message") val persistenceMessage: String? = null,
)

@Serializable
data class ReceiptSessionResponse(
    val authenticated: Boolean,
    val status: String = "login_required",
    val retryable: Boolean = false,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Long = 0,
    @SerialName("persistence_status") val persistenceStatus: String = PERSISTENCE_NOT_CONFIGURED,
    @SerialName("persistence_message") val persistenceMessage: String? = null,
    @SerialName("message") val message: String? = null,
)

@Serializable
internal data class ReceiptSessionEnvelope(
    val version: Int = 1,
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("auth_cookie") val authCookie: String = "",
    @SerialName("auth_cookie_host") val authCookieHost: String? = null,
    @SerialName("expires_in_seconds") val expiresInSeconds: Long? = null,
    @SerialName("token_issued_at_epoch_ms") val tokenIssuedAtEpochMs: Long? = null,
    @SerialName("device_info") val deviceInfo: JsonObject? = null,
    @SerialName("token_expire_in") val tokenExpireIn: String? = null,
    @SerialName("refresh_token_expires_in") val refreshTokenExpiresIn: String? = null,
) {
    override fun toString(): String = "ReceiptSessionEnvelope(redacted)"
}

internal interface ReceiptAuthService : ReceiptSessionProvider {
    suspend fun restore()
    suspend fun startBrowserLogin(): ReceiptSessionResponse
    suspend fun login(request: ReceiptLoginRequest): ReceiptLoginResponse
    suspend fun resendOtp(phone: String): ReceiptLoginResponse
    suspend fun logout(): ReceiptSessionResponse
    suspend fun session(): ReceiptSessionResponse
    suspend fun retrySession(): ReceiptSessionResponse
}


internal enum class FakeReceiptLoginStage {
    PHONE,
    OTP,
    PASSWORD,
    AUTHENTICATED,
}

internal class FakeReceiptAuthService : ReceiptAuthService {
    private val mutex = Mutex()
    private var stage = FakeReceiptLoginStage.PHONE
    private var authenticated = false

    override suspend fun restore() = Unit
    override suspend fun startBrowserLogin(): ReceiptSessionResponse = mutex.withLock {
        authenticated = true
        stage = FakeReceiptLoginStage.AUTHENTICATED
        sessionResponse()
    }

    override suspend fun accessToken(): String? = mutex.withLock {
        FAKE_ACCESS_TOKEN.takeIf { authenticated }
    }

    override suspend fun login(request: ReceiptLoginRequest): ReceiptLoginResponse = mutex.withLock {
        if (normalizePhone(request.phone) != FAKE_PHONE) {
            return@withLock errorResponse("Login не выполнен.")
        }
        when (stage) {
            FakeReceiptLoginStage.PHONE -> {
                if (request.otp.isNotBlank() || request.password.isNotBlank()) {
                    errorResponse("Login не выполнен.")
                } else {
                    authenticated = false
                    stage = FakeReceiptLoginStage.OTP
                    otpResponse()
                }
            }
            FakeReceiptLoginStage.AUTHENTICATED ->
                errorResponse("Сессия уже активна; сначала выполните logout.")
            FakeReceiptLoginStage.OTP -> {
                if (request.otp != FAKE_OTP) {
                    errorResponse("Неверный одноразовый код.")
                } else {
                    stage = FakeReceiptLoginStage.PASSWORD
                    if (request.password.isBlank()) passwordResponse() else authenticate(request.password)
                }
            }
            FakeReceiptLoginStage.PASSWORD -> {
                if (request.otp != FAKE_OTP) {
                    errorResponse("Неверный одноразовый код.")
                } else {
                    authenticate(request.password)
                }
            }
        }
    }

    override suspend fun resendOtp(phone: String): ReceiptLoginResponse = mutex.withLock {
        if (normalizePhone(phone) != FAKE_PHONE) {
            return@withLock errorResponse("Login не выполнен.")
        }
        if (stage == FakeReceiptLoginStage.PASSWORD || stage == FakeReceiptLoginStage.AUTHENTICATED) {
            return@withLock errorResponse("Повторная отправка SMS доступна только на шаге OTP.")
        }
        authenticated = false
        stage = FakeReceiptLoginStage.OTP
        otpResponse()
    }

    override suspend fun logout(): ReceiptSessionResponse = mutex.withLock {
        authenticated = false
        stage = FakeReceiptLoginStage.PHONE
        sessionResponse()
    }

    override suspend fun session(): ReceiptSessionResponse = mutex.withLock { sessionResponse() }

    override suspend fun retrySession(): ReceiptSessionResponse = mutex.withLock { sessionResponse() }

    private fun authenticate(password: String): ReceiptLoginResponse {
        if (password != FAKE_PASSWORD) return errorResponse("Login не выполнен.")
        authenticated = true
        stage = FakeReceiptLoginStage.AUTHENTICATED
        return ReceiptLoginResponse(
            status = "authenticated",
            message = "Read-only receipts session создана.",
            authenticated = true,
            persistenceStatus = PERSISTENCE_MEMORY_ONLY,
            persistenceMessage = "Fake session живёт только в памяти процесса.",
        )
    }

    private fun sessionResponse(): ReceiptSessionResponse = ReceiptSessionResponse(
        authenticated = authenticated,
        status = if (authenticated) "active" else "login_required",
        persistenceStatus = PERSISTENCE_MEMORY_ONLY,
        persistenceMessage = "Fake session живёт только в памяти процесса.",
    )

    private fun otpResponse() = ReceiptLoginResponse(
        status = "requires_otp",
        message = "Одноразовый код отправлен. Введите его и повторите login.",
        requiresOtp = true,
        persistenceStatus = PERSISTENCE_MEMORY_ONLY,
    )

    private fun passwordResponse() = ReceiptLoginResponse(
        status = "requires_password",
        message = "Введите пароль и повторите login.",
        requiresPassword = true,
        persistenceStatus = PERSISTENCE_MEMORY_ONLY,
    )

    private fun errorResponse(message: String) = ReceiptLoginResponse(
        status = "error",
        message = message,
        persistenceStatus = PERSISTENCE_MEMORY_ONLY,
    )
}

internal class RealReceiptAuthService(
    private val httpClient: HttpClient,
    private val sessionSecretStore: ReceiptSessionSecretStore = KeyringReceiptSessionSecretStore(),
    private val browserLoginCapture: ReceiptBrowserLoginCapture = VisibleReceiptBrowserLoginCapture(),
) : ReceiptAuthService {
    private class RefreshFlight(
        val accessToken: String,
        val sourceEnvelope: ReceiptSessionEnvelope,
        var accessTokenRejected: Boolean,
        val result: CompletableDeferred<RefreshResult>,
    )

    private sealed interface RefreshResult {
        data class Success(val authorization: ReceiptAuthorization?) : RefreshResult
        data class Failure(val error: Throwable) : RefreshResult
    }

    private sealed interface RefreshReservation {
        data class Owner(val flight: RefreshFlight) : RefreshReservation
        data class Waiter(val flight: RefreshFlight) : RefreshReservation
        data class Ready(val authorization: ReceiptAuthorization?) : RefreshReservation
    }

    private var rejectedAccessToken = false
    private var refreshFlight: RefreshFlight? = null
    private val mutex = Mutex()
    private val browserLoginScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var browserLoginJob: Job? = null
    private var envelope: ReceiptSessionEnvelope? = null
    private var credentialsRejected = false
    private var sessionStatus: String? = null
    private var sessionRetryable = false
    private var sessionMessage: String? = null
    private var persistenceStatus: String = PERSISTENCE_NOT_CONFIGURED
    private var persistenceMessage: String? = null

    override suspend fun restore() = mutex.withLock {
        browserLoginJob?.cancel()
        browserLoginJob = null
        credentialsRejected = false
        sessionMessage = null
        rejectedAccessToken = false
        sessionStatus = null
        sessionRetryable = false
        try {
            persistenceStatus = PERSISTENCE_AVAILABLE
            persistenceMessage = null
            val stored = sessionSecretStore.read().orEmpty()
            if (stored.isBlank()) {
                envelope = null
                return@withLock
            }
            val restored = authJson.decodeFromString<ReceiptSessionEnvelope>(stored)
            require(restored.version == 1) { "Unsupported receipt session envelope version." }
            require(restored.accessToken.isNotBlank()) { "Incomplete receipt session envelope." }
            envelope = restored
            persistenceStatus = PERSISTENCE_PERSISTED
        } catch (error: CancellationException) {
            throw error
        } catch (_: ReceiptSessionSecretStoreUnavailableException) {
            envelope = null
            markPersistenceUnavailable()
        } catch (_: Throwable) {
            envelope = null
            if (persistenceStatus != PERSISTENCE_UNAVAILABLE) {
                persistenceMessage = "Сохранённая receipts session недействительна; выполните browser login."
            }
        }
    }

    override suspend fun startBrowserLogin(): ReceiptSessionResponse = mutex.withLock {
        if (browserLoginJob?.isActive == true) {
            return@withLock sessionResponse(isAuthenticated(), "authenticating")
        }
        sessionMessage = null
        sessionStatus = "authenticating"
        sessionRetryable = false
        val attempt = browserLoginScope.launch(start = CoroutineStart.LAZY) {
            val attemptJob = coroutineContext[Job]
            try {
                val captured = runInterruptible { browserLoginCapture.capture() }
                coroutineContext.ensureActive()
                completeBrowserLogin(attemptJob, captured)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                failBrowserLogin(attemptJob)
            }
        }
        browserLoginJob = attempt
        attempt.start()
        sessionResponse(isAuthenticated(), "authenticating")
    }

    override suspend fun accessToken(): String? = mutex.withLock {
        envelope?.accessToken?.takeUnless { credentialsRejected }
    }

    override suspend fun authCookie(): String? = mutex.withLock {
        envelope?.authCookie?.takeIf(String::isNotBlank)?.takeUnless { credentialsRejected }
    }

    override suspend fun authorization(apiHost: String?): ReceiptAuthorization? = mutex.withLock {
        authorizationLocked(envelope, apiHost)
    }

    override suspend fun refreshAfterRejection(
        accessToken: String,
        apiHost: String?,
    ): ReceiptAuthorization? = refreshToken(accessToken, apiHost, tokenRejected = true)

    private suspend fun refreshToken(
        accessToken: String,
        apiHost: String?,
        tokenRejected: Boolean,
    ): ReceiptAuthorization? {
        val reservation = mutex.withLock {
            val current = envelope ?: return@withLock RefreshReservation.Ready(null)
            if (credentialsRejected) return@withLock RefreshReservation.Ready(null)
            if (current.accessToken != accessToken) {
                return@withLock RefreshReservation.Ready(authorizationLocked(current, apiHost))
            }
            if (tokenRejected) rejectedAccessToken = true
            sessionStatus = "recoverable_error"
            sessionRetryable = true
            sessionMessage = if (tokenRejected) {
                "Сервис чеков отклонил access token; выполняется безопасное обновление."
            } else {
                "Выполняется обновление receipts session."
            }
            val active = refreshFlight?.takeIf {
                !it.result.isCompleted &&
                    it.accessToken == accessToken && it.sourceEnvelope === current
            }
            if (active != null) {
                if (tokenRejected) active.accessTokenRejected = true
                RefreshReservation.Waiter(active)
            } else {
                val flight = RefreshFlight(accessToken, current, tokenRejected, CompletableDeferred())
                refreshFlight = flight
                RefreshReservation.Owner(flight)
            }
        }
        return when (reservation) {
            is RefreshReservation.Ready -> reservation.authorization
            is RefreshReservation.Waiter -> {
                reservation.flight.result.await().unwrapRefreshResult()
                mutex.withLock { authorizationLocked(envelope, apiHost) }
            }
            is RefreshReservation.Owner -> runRefreshFlight(reservation.flight, apiHost)
        }
    }

    override suspend fun invalidate() = mutex.withLock {
        credentialsRejected = true
        rejectedAccessToken = false
        sessionStatus = "login_required"
        sessionRetryable = false
        sessionMessage = "Сессия receipts отклонена; выполните browser login."
    }

    override suspend fun invalidateIfCurrent(accessToken: String) = mutex.withLock {
        if (envelope?.accessToken == accessToken) {
            credentialsRejected = true
            rejectedAccessToken = false
            sessionStatus = "login_required"
            sessionRetryable = false
            sessionMessage = "Сессия receipts отклонена; выполните browser login."
        }
    }

    override suspend fun logout(): ReceiptSessionResponse = mutex.withLock {
        browserLoginJob?.cancel()
        browserLoginJob = null
        try {
            sessionSecretStore.delete()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            markPersistenceUnavailable()
            sessionStatus = "logout_failed"
            sessionMessage = "Не удалось удалить receipts session; сохранённые данные не изменены."
            sessionRetryable = false
            return@withLock sessionResponse(isAuthenticated(), "logout_failed")
        }
        envelope = null
        credentialsRejected = false
        rejectedAccessToken = false
        sessionStatus = null
        sessionMessage = null
        sessionRetryable = false
        persistenceStatus = PERSISTENCE_AVAILABLE
        persistenceMessage = null
        sessionResponse(false, "login_required")
    }

    override suspend fun session(): ReceiptSessionResponse = mutex.withLock {
        if (browserLoginJob?.isActive == true) {
            return@withLock sessionResponse(isAuthenticated(), "authenticating")
        }
        if (credentialsRejected) {
            return@withLock sessionResponse(false, "login_required")
        }
        if (rejectedAccessToken || envelope?.let(::hasExpiredAccessToken) == true) {
            return@withLock sessionResponse(
                authenticated = false,
                status = "recoverable_error",
                retryable = true,
                message = sessionMessage ?: "Access token истёк; повторите retry session или выполните запрос чеков.",
            )
        }
        val currentStatus = sessionStatus
        if (currentStatus != null) {
            return@withLock sessionResponse(
                authenticated = isAuthenticated(),
                status = currentStatus,
                retryable = sessionRetryable,
            )
        }
        sessionResponse(isAuthenticated(), if (isAuthenticated()) "active" else "login_required")
    }

    override suspend fun retrySession(): ReceiptSessionResponse {
        val current = mutex.withLock {
            if (browserLoginJob?.isActive == true) {
                return@withLock null
            }
            envelope?.takeUnless { credentialsRejected }
        } ?: return session()
        return try {
            refreshToken(current.accessToken, null, tokenRejected = false)
            session()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            session()
        }
    }

    private suspend fun runRefreshFlight(
        flight: RefreshFlight,
        apiHost: String?,
    ): ReceiptAuthorization? {
        try {
            val refreshed = requestRefresh(flight.sourceEnvelope)
            val authorization = mutex.withLock {
                val current = envelope
                if (current === flight.sourceEnvelope) {
                    persistCandidate(refreshed)
                    envelope = refreshed
                    credentialsRejected = false
                    rejectedAccessToken = false
                    sessionStatus = null
                    sessionRetryable = false
                    sessionMessage = null
                    authorizationLocked(refreshed, apiHost)
                } else {
                    authorizationLocked(current, apiHost)
                }
            }
            flight.result.complete(RefreshResult.Success(authorization))
            return authorization
        } catch (error: CancellationException) {
            flight.result.cancel(error)
            throw error
        } catch (error: ReceiptAuthenticationException) {
            mutex.withLock {
                if (envelope === flight.sourceEnvelope) {
                    credentialsRejected = true
                    rejectedAccessToken = false
                    sessionStatus = "login_required"
                    sessionRetryable = false
                    sessionMessage = "Refresh token receipts отклонён; выполните ручной browser login."
                }
            }
            flight.result.complete(RefreshResult.Failure(error))
            throw error
        } catch (error: Throwable) {
            val safeError = error as? ReceiptIntegrationException
                ?: ReceiptIntegrationException("Не удалось обновить receipts session.", error)
            mutex.withLock {
                if (envelope === flight.sourceEnvelope) {
                    if (flight.accessTokenRejected) rejectedAccessToken = true
                    sessionStatus = "recoverable_error"
                    sessionRetryable = true
                    sessionMessage = "Не удалось обновить receipts session; повторите явный retry."
                }
            }
            flight.result.complete(RefreshResult.Failure(safeError))
            throw safeError
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (!flight.result.isCompleted) {
                        flight.result.cancel(
                            CancellationException("Обновление receipts session завершилось без результата."),
                        )
                    }
                    if (refreshFlight === flight) refreshFlight = null
                }
            }
        }
    }

    private suspend fun requestRefresh(current: ReceiptSessionEnvelope): ReceiptSessionEnvelope {
        val refreshToken = current.refreshToken
            ?.takeIf(String::isNotBlank)
            ?: throw ReceiptAuthenticationException("Refresh token отсутствует.")
        val deviceInfo = current.deviceInfo
            ?: throw ReceiptAuthenticationException("Данные устройства отсутствуют.")
        val requestBody = buildJsonObject {
            put("deviceInfo", deviceInfo)
            put("refreshToken", JsonPrimitive(refreshToken))
        }
        val response = try {
            httpClient.post(FNS_AUTH_REFRESH_URL) {
                contentType(ContentType.Application.Json.withParameter("charset", "UTF-8"))
                setBody(authJson.encodeToString(JsonObject.serializer(), requestBody))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw ReceiptIntegrationException("Не удалось обновить receipts session.", error)
        }
        if (response.status.value == 401 || response.status.value == 403) {
            throw ReceiptAuthenticationException("Refresh token receipts отклонён.")
        }
        if (response.status.value !in 200..299) {
            throw ReceiptIntegrationException("Сервис refresh receipts временно недоступен.")
        }
        val body = safeBody(response)
        return try {
            parseReceiptTokenResponse(body, deviceInfo)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw ReceiptIntegrationException("Сервис refresh receipts вернул неподдерживаемый ответ.", error)
        }
    }


    private suspend fun completeBrowserLogin(
        attempt: Job?,
        candidate: ReceiptSessionEnvelope,
    ) = mutex.withLock {
        if (browserLoginJob !== attempt) return@withLock
        try {
            persistCandidate(candidate)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            browserLoginJob = null
            val hasPreviousSession = isAuthenticated()
            sessionStatus = if (hasPreviousSession) "active" else "login_required"
            sessionRetryable = false
            sessionMessage = if (hasPreviousSession) {
                "Не удалось сохранить новую session; предыдущая session сохранена."
            } else {
                "Не удалось сохранить receipts session в OS credential store."
            }
            return@withLock
        }
        envelope = candidate
        credentialsRejected = false
        rejectedAccessToken = false
        browserLoginJob = null
        sessionStatus = null
        sessionRetryable = false
        sessionMessage = null
    }

    private suspend fun failBrowserLogin(attempt: Job?) = mutex.withLock {
        if (browserLoginJob !== attempt) return@withLock
        browserLoginJob = null
        val hasPreviousSession = isAuthenticated()
        sessionStatus = if (hasPreviousSession) "active" else "login_required"
        sessionRetryable = false
        sessionMessage = if (hasPreviousSession) {
            "Ручной вход ФНС не завершён; предыдущая session сохранена."
        } else {
            "Ручной вход ФНС не завершён; повторите browser login."
        }
    }

    private fun persistCandidate(candidate: ReceiptSessionEnvelope) {
        try {
            sessionSecretStore.write(authJson.encodeToString(candidate))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            markPersistenceUnavailable()
            throw ReceiptIntegrationException("Не удалось сохранить receipts session в OS credential store.", error)
        }
        persistenceStatus = PERSISTENCE_PERSISTED
        persistenceMessage = null
    }

    override suspend fun login(request: ReceiptLoginRequest): ReceiptLoginResponse =
        legacyLoginDisabled()

    override suspend fun resendOtp(phone: String): ReceiptLoginResponse =
        legacyLoginDisabled()

    private fun legacyLoginDisabled() = ReceiptLoginResponse(
        status = "error",
        message = "Real receipts auth доступна только через ручной visible browser login.",
        persistenceStatus = persistenceStatus,
        persistenceMessage = persistenceMessage,
    )

    private fun authorizationLocked(
        current: ReceiptSessionEnvelope?,
        apiHost: String?,
    ): ReceiptAuthorization? {
        if (current == null || credentialsRejected) return null
        val cookieHost = current.authCookieHost
        val cookie = current.authCookie.takeIf(String::isNotBlank)
            ?.takeIf { cookieHost != null && apiHost != null && cookieHost == apiHost.lowercase(Locale.ROOT) }
        return ReceiptAuthorization(
            accessToken = current.accessToken,
            authCookie = cookie,
            authCookieHost = cookieHost,
        )
    }

    private fun hasExpiredAccessToken(current: ReceiptSessionEnvelope): Boolean {
        val now = Instant.now()
        val tokenExpiry = current.tokenExpireIn?.let { value ->
            runCatching { Instant.parse(value) }.getOrNull()
        }
        if (tokenExpiry != null) return !tokenExpiry.isAfter(now)
        val issuedAt = current.tokenIssuedAtEpochMs
        val expiresIn = current.expiresInSeconds
        if (issuedAt != null && expiresIn != null) {
            val expiry = runCatching { Instant.ofEpochMilli(issuedAt).plusSeconds(expiresIn) }.getOrNull()
            if (expiry != null) return !expiry.isAfter(now)
        }
        return false
    }

    private fun RefreshResult.unwrapRefreshResult(): ReceiptAuthorization? = when (this) {
        is RefreshResult.Success -> authorization
        is RefreshResult.Failure -> throw error
    }

    private fun isAuthenticated(): Boolean =
        envelope?.let { !credentialsRejected && !rejectedAccessToken && !hasExpiredAccessToken(it) } == true

    private fun sessionResponse(
        authenticated: Boolean,
        status: String,
        retryable: Boolean = false,
        message: String? = sessionMessage,
    ) = ReceiptSessionResponse(
        authenticated = authenticated,
        status = status,
        retryable = retryable,
        persistenceStatus = persistenceStatus,
        persistenceMessage = persistenceMessage,
        message = message,
    )

    private fun markPersistenceUnavailable() {
        persistenceStatus = PERSISTENCE_UNAVAILABLE
        persistenceMessage = "OS credential store недоступен; real receipts session не подтверждена для restart."
    }

    private suspend fun safeBody(response: io.ktor.client.statement.HttpResponse): String =
        try {
            response.bodyAsText()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw ReceiptIntegrationException("Сервис refresh receipts вернул неполный ответ.", error)
        }

    private companion object {
        val authJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}

private fun normalizePhone(value: String): String {
    val compact = value.trim().replace(Regex("[\\s()\\-]"), "")
    return when {
        compact.matches(Regex("\\+7\\d{10}")) -> compact
        compact.matches(Regex("7\\d{10}")) -> "+$compact"
        compact.matches(Regex("8\\d{10}")) -> "+7${compact.drop(1)}"
        else -> compact
    }
}
