package io.github.stolex1y.receipts.mcp

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val FAKE_PHONE = "+79990000000"
private const val FAKE_OTP = "000000"
private const val FAKE_PASSWORD = "demo"
private const val FAKE_ACCESS_TOKEN = "fake-receipts-session"
private const val DEFAULT_ACCESS_TOKEN_LIFETIME_SECONDS = 7_199L

private val RECEIPTS_ALLOWED_UPSTREAM_HOSTS = setOf(
    "lkdr.nalog.ru",
    "mco.nalog.ru",
)

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

private const val ACCESS_TOKEN_REFRESH_MARGIN_SECONDS = 600L

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
)

internal interface ReceiptAuthService : ReceiptSessionProvider {
    suspend fun restore()
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
    private val loginUrl: String? = System.getenv("RECEIPTS_LOGIN_URL")?.takeIf(String::isNotBlank),
    private val refreshUrl: String? = System.getenv("RECEIPTS_REFRESH_URL")?.takeIf(String::isNotBlank),
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) : ReceiptAuthService {
    private val mutex = Mutex()
    private var envelope: ReceiptSessionEnvelope? = null
    private var pendingAuthCookie: String? = null
    private var pendingAuthCookieHost: String? = null
    private var persistenceStatus: String = PERSISTENCE_NOT_CONFIGURED
    private var persistenceMessage: String? = null

    init {
        loginUrl?.let { validateReceiptUpstreamUrl(it, "RECEIPTS_LOGIN_URL") }
        refreshUrl?.let { validateReceiptUpstreamUrl(it, "RECEIPTS_REFRESH_URL") }
    }

    override suspend fun restore() = mutex.withLock {
        pendingAuthCookie = null
        pendingAuthCookieHost = null
        try {
            persistenceStatus = PERSISTENCE_AVAILABLE
            persistenceMessage = null
            val stored = sessionSecretStore.read().orEmpty()
            if (stored.isBlank()) return@withLock
            val restored = authJson.decodeFromString<ReceiptSessionEnvelope>(stored)
            require(restored.version == 1) { "Unsupported receipt session envelope version." }
            require(restored.accessToken.isNotBlank()) { "Incomplete receipt session envelope." }
            envelope = restored
            persistenceStatus = PERSISTENCE_PERSISTED
        } catch (_: ReceiptSessionSecretStoreUnavailableException) {
            envelope = null
            markPersistenceUnavailable()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            envelope = null
            deletePersistedSession()
            if (persistenceStatus != PERSISTENCE_UNAVAILABLE) {
                persistenceMessage = "Сохранённая receipts session недействительна; выполните login."
            }
        }
    }

    override suspend fun accessToken(): String? = mutex.withLock {
        val current = envelope ?: return@withLock null
        if (tokenNeedsRefresh(current)) {
            refreshLocked(current)
        }
        envelope?.accessToken
    }

    override suspend fun authCookie(): String? = mutex.withLock {
        envelope?.authCookie?.takeIf(String::isNotBlank)
    }

    override suspend fun authorization(apiHost: String?): ReceiptAuthorization? = mutex.withLock {
        val current = envelope ?: return@withLock null
        if (tokenNeedsRefresh(current)) {
            refreshLocked(current)
        }
        envelope?.let {
            val cookieHost = it.authCookieHost
            val cookie = it.authCookie.takeIf(String::isNotBlank)
                ?.takeIf { cookieHost != null && apiHost != null && cookieHost == apiHost.lowercase(Locale.ROOT) }
            ReceiptAuthorization(
                accessToken = it.accessToken,
                authCookie = cookie,
                authCookieHost = cookieHost,
            )
        }
    }

    override suspend fun invalidate() = mutex.withLock {
        envelope = null
        pendingAuthCookie = null
        pendingAuthCookieHost = null
        deletePersistedSession()
    }

    override suspend fun invalidateIfCurrent(accessToken: String) = mutex.withLock {
        if (envelope?.accessToken == accessToken) {
            envelope = null
            pendingAuthCookie = null
            pendingAuthCookieHost = null
            deletePersistedSession()
        }
    }

    override suspend fun login(request: ReceiptLoginRequest): ReceiptLoginResponse = mutex.withLock {

        val endpoint = loginUrl ?: return@withLock unsupportedLoginResponse()
        val endpointHost = upstreamHost(endpoint)
        val response = try {
            httpClient.post(endpoint) {
                contentType(ContentType.Application.Json)
                pendingAuthCookie
                    ?.takeIf(String::isNotBlank)
                    ?.let { header(HttpHeaders.Cookie, it) }
                setBody(authJson.encodeToString(request))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return@withLock ReceiptLoginResponse(
                status = "error",
                message = "Не удалось обратиться к сервису авторизации receipts.",
                persistenceStatus = persistenceStatus,
                persistenceMessage = persistenceMessage,
            )
        }
        val body = safeBody(response)
        if (response.status.value !in 200..299) {
            return@withLock ReceiptLoginResponse(
                status = "error",
                message = "Сервис авторизации receipts отклонил login.",
                persistenceStatus = persistenceStatus,
                persistenceMessage = persistenceMessage,
            )
        }
        val json = parseObject(body) ?: return@withLock safeAuthError()
        val status = json.string("status")?.lowercase(Locale.ROOT)
        val requiresOtp = json.boolean("requires_otp") || status == "requires_otp"
        val requiresPassword = json.boolean("requires_password") || status == "requires_password"
        if (requiresOtp || requiresPassword) {
            if (response.headers.getAll(HttpHeaders.SetCookie).orEmpty().isNotEmpty()) {
                pendingAuthCookie = mergeAuthCookies(pendingAuthCookie, response)
                pendingAuthCookieHost = pendingAuthCookie?.let { endpointHost }
            }
            return@withLock ReceiptLoginResponse(
                status = if (requiresOtp) "requires_otp" else "requires_password",
                message = if (requiresOtp) {
                    "Одноразовый код отправлен. Продолжите login."
                } else {
                    "Введите пароль и продолжите login."
                },
                requiresOtp = requiresOtp,
                requiresPassword = requiresPassword,
                persistenceStatus = persistenceStatus,
                persistenceMessage = persistenceMessage,
            )
        }
        val token = json.string("access_token") ?: json.string("accessToken")
            ?: return@withLock safeAuthError()
        val fallbackCookie = mergeAuthCookies(pendingAuthCookie, response)
        applyToken(
            json = json,
            accessToken = token,
            fallbackAuthCookie = fallbackCookie,
            fallbackAuthCookieHost = if (json.string("auth_cookie") != null) {
                endpointHost
            } else {
                fallbackCookie?.let { endpointHost ?: pendingAuthCookieHost }
            },
        )
        pendingAuthCookie = null
        pendingAuthCookieHost = null
        persistCurrent()
        authenticatedResponse()
    }

    override suspend fun resendOtp(phone: String): ReceiptLoginResponse =
        login(ReceiptLoginRequest(phone = phone))

    override suspend fun logout(): ReceiptSessionResponse = mutex.withLock {
        try {
            sessionSecretStore.delete()
        } catch (_: ReceiptSessionSecretStoreUnavailableException) {
            markPersistenceUnavailable()
            return@withLock ReceiptSessionResponse(
                authenticated = envelope != null,
                status = "logout_failed",
                retryable = true,
                persistenceStatus = persistenceStatus,
                persistenceMessage = persistenceMessage,
            )
        }
        envelope = null
        pendingAuthCookie = null
        pendingAuthCookieHost = null
        sessionResponse(authenticated = false, status = "login_required")
    }

    override suspend fun session(): ReceiptSessionResponse = mutex.withLock {
        val current = envelope ?: return@withLock sessionResponse(false, "login_required")
        try {
            if (tokenNeedsRefresh(current)) refreshLocked(current)
            sessionResponse(true, "active")
        } catch (_: ReceiptAuthenticationException) {
            envelope = null
            deletePersistedSession()
            sessionResponse(false, "login_required")
        } catch (_: ReceiptIntegrationException) {
            sessionResponse(false, "recoverable_error", retryable = true)
        }
    }

    override suspend fun retrySession(): ReceiptSessionResponse = session()

    private suspend fun refreshLocked(current: ReceiptSessionEnvelope) {
        val endpoint = refreshUrl
            ?: run {
                envelope = null
                deletePersistedSession()
                throw ReceiptAuthenticationException("Refresh endpoint receipts не настроен; выполните login.")
            }
        val refreshToken = current.refreshToken
            ?.takeIf(String::isNotBlank)
            ?: run {
                envelope = null
                deletePersistedSession()
                throw ReceiptAuthenticationException("В receipts session отсутствует refresh token.")
            }
        val response = try {
            httpClient.post(endpoint) {
                contentType(ContentType.Application.Json)
                if (current.authCookieHost == upstreamHost(endpoint)) {
                    current.authCookie
                        .takeIf(String::isNotBlank)
                        ?.let { header(HttpHeaders.Cookie, it) }
                }
                setBody(
                    """{"grant_type":"refresh_token","refresh_token":${authJson.encodeToString(refreshToken)}}""",
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw ReceiptIntegrationException("Не удалось обновить receipts session.", error)
        }
        val body = safeBody(response)
        if (response.status.value == 401 || response.status.value == 403) {
            envelope = null
            deletePersistedSession()
            throw ReceiptAuthenticationException("Refresh token receipts отклонён или истёк.")
        }
        if (response.status.value !in 200..299) {
            val errorCode = parseObject(body)?.string("error")
                ?: parseObject(body)?.string("code")
            if (response.status.value == 400 && errorCode in DEFINITIVE_REFRESH_ERRORS) {
                envelope = null
                deletePersistedSession()
                throw ReceiptAuthenticationException("Refresh token receipts отклонён или истёк.")
            }
            throw ReceiptIntegrationException("Сервис refresh receipts временно недоступен.")
        }
        val json = parseObject(body) ?: throw ReceiptIntegrationException("Сервис refresh receipts вернул некорректный JSON.")
        val token = json.string("access_token") ?: json.string("accessToken")
            ?: throw ReceiptIntegrationException("Сервис refresh receipts вернул неполный ответ.")
        val responseCookie = json.string("auth_cookie")
        val fallbackCookie = if (current.authCookieHost == upstreamHost(endpoint)) {
            mergeAuthCookies(current.authCookie, response)
        } else {
            current.authCookie.takeIf(String::isNotBlank)
        }
        applyToken(
            json = json,
            accessToken = token,
            fallbackRefreshToken = refreshToken,
            fallbackSessionId = current.sessionId,
            fallbackAuthCookie = fallbackCookie,
            fallbackAuthCookieHost = if (responseCookie != null) {
                upstreamHost(endpoint)
            } else {
                current.authCookieHost
            },
        )
        persistCurrent()
    }

    private fun applyToken(
        json: JsonObject,
        accessToken: String,
        fallbackRefreshToken: String? = null,
        fallbackSessionId: String? = null,
        fallbackAuthCookie: String? = null,
        fallbackAuthCookieHost: String? = null,
    ) {
        val jsonCookie = json.string("auth_cookie")
        val resolvedCookie = jsonCookie ?: fallbackAuthCookie.orEmpty()
        val resolvedCookieHost = if (resolvedCookie.isNotBlank()) {
            fallbackAuthCookieHost
        } else {
            null
        }
        envelope = ReceiptSessionEnvelope(
            accessToken = accessToken,
            refreshToken = json.string("refresh_token")
                ?: json.string("refreshToken")
                ?: fallbackRefreshToken,
            sessionId = json.string("session_id")
                ?: json.string("sessionId")
                ?: fallbackSessionId,
            authCookie = resolvedCookie,
            authCookieHost = resolvedCookieHost,
            expiresInSeconds = json.long("expires_in") ?: json.long("expiresIn")
                ?: DEFAULT_ACCESS_TOKEN_LIFETIME_SECONDS,
            tokenIssuedAtEpochMs = nowEpochMs(),
        )
    }

    private fun persistCurrent() {
        val current = envelope ?: return
        try {
            sessionSecretStore.write(authJson.encodeToString(current))
            persistenceStatus = PERSISTENCE_PERSISTED
            persistenceMessage = null
        } catch (_: ReceiptSessionSecretStoreUnavailableException) {
            markPersistenceUnavailable()
        }
    }

    private fun deletePersistedSession() {
        try {
            sessionSecretStore.delete()
            if (persistenceStatus != PERSISTENCE_UNAVAILABLE) {
                persistenceStatus = PERSISTENCE_AVAILABLE
                persistenceMessage = null
            }
        } catch (_: ReceiptSessionSecretStoreUnavailableException) {
            markPersistenceUnavailable()
        }
    }

    private fun tokenNeedsRefresh(current: ReceiptSessionEnvelope): Boolean {
        val issuedAt = current.tokenIssuedAtEpochMs ?: return current.refreshToken != null
        val expiresIn = current.expiresInSeconds ?: DEFAULT_ACCESS_TOKEN_LIFETIME_SECONDS
        val ageSeconds = ((nowEpochMs() - issuedAt) / 1_000L).coerceAtLeast(0L)
        val refreshLead = minOf(ACCESS_TOKEN_REFRESH_MARGIN_SECONDS, (expiresIn / 2).coerceAtLeast(1L))
        return ageSeconds >= (expiresIn - refreshLead).coerceAtLeast(1L)
    }

    private fun authenticatedResponse() = ReceiptLoginResponse(
        status = "authenticated",
        message = "Read-only receipts session создана.",
        authenticated = true,
        persistenceStatus = persistenceStatus,
        persistenceMessage = persistenceMessage,
    )

    private fun unsupportedLoginResponse() = ReceiptLoginResponse(
        status = "error",
        message = "Real receipts login не настроен: задайте подтверждённый RECEIPTS_LOGIN_URL.",
        persistenceStatus = persistenceStatus,
        persistenceMessage = persistenceMessage,
    )

    private fun safeAuthError() = ReceiptLoginResponse(
        status = "error",
        message = "Сервис авторизации receipts вернул неполный ответ.",
        persistenceStatus = persistenceStatus,
        persistenceMessage = persistenceMessage,
    )

    private fun sessionResponse(
        authenticated: Boolean,
        status: String,
        retryable: Boolean = false,
    ) = ReceiptSessionResponse(
        authenticated = authenticated,
        status = status,
        retryable = retryable,
        persistenceStatus = persistenceStatus,
        persistenceMessage = persistenceMessage,
    )

    private fun markPersistenceUnavailable() {
        persistenceStatus = PERSISTENCE_UNAVAILABLE
        persistenceMessage = "OS credential store недоступен; real receipts session будет действовать только до restart."
    }

    private fun mergeAuthCookies(
        existing: String?,
        response: io.ktor.client.statement.HttpResponse,
    ): String? {
        val jar = linkedMapOf<String, String>()
        existing.orEmpty().split(';').mapNotNull(::parseCookiePair).forEach { (name, value) ->
            jar[name] = value
        }
        response.headers.getAll(HttpHeaders.SetCookie).orEmpty().forEach { headerValue ->
            val pair = headerValue.substringBefore(';').trim()
            val parsed = parseCookiePair(pair) ?: return@forEach
            val attributes = headerValue.substringAfter(';', "").lowercase(Locale.ROOT)
            val maxAge = Regex("""(?:^|[;\s])max-age\s*=\s*(-?\d+)""")
                .find(attributes)
                ?.groupValues
                ?.getOrNull(1)
                ?.toLongOrNull()
            if (parsed.second.isBlank() || maxAge != null && maxAge <= 0L || expiresCookie(attributes)) {
                jar.remove(parsed.first)
            } else {
                jar[parsed.first] = parsed.second
            }
        }
        return jar.entries.joinToString("; ") { (name, value) -> "$name=$value" }
            .takeIf(String::isNotBlank)
    }

    private fun expiresCookie(attributes: String): Boolean {
        val value = Regex("""(?:^|[;,\s])expires\s*=\s*([^;]+)""")
            .find(attributes)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?: return false
        val expiry = runCatching {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        }.getOrNull()
        return expiry?.isBefore(Instant.now()) == true
    }

    private fun parseCookiePair(raw: String): Pair<String, String>? {
        val separator = raw.indexOf('=')
        if (separator <= 0) return null
        val name = raw.substring(0, separator).trim()
        val value = raw.substring(separator + 1).trim()
        if (!name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) return null
        if (value.length > 4096 || value.any { it == '\r' || it == '\n' }) return null
        return name to value
    }

    private suspend fun safeBody(response: io.ktor.client.statement.HttpResponse): String =
        try {
            response.bodyAsText()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            ""
        }

    private fun parseObject(body: String): JsonObject? = runCatching {
        authJson.parseToJsonElement(body).jsonObject
    }.getOrNull()

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

    private fun JsonObject.boolean(name: String): Boolean =
        (this[name] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false

    private fun JsonObject.long(name: String): Long? =
        (this[name] as? JsonPrimitive)?.longOrNull

    private companion object {
        val authJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        val DEFINITIVE_REFRESH_ERRORS = setOf(
            "invalid_grant",
            "invalid_token",
            "session_expired",
            "refresh_token_expired",
        )
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
