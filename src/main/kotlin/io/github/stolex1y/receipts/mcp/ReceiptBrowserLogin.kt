package io.github.stolex1y.receipts.mcp

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Response
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.util.Locale
import java.util.function.Predicate

internal const val FNS_LOGIN_URL = "https://lkdr.nalog.gov.ru/login"
internal const val FNS_AUTH_ORIGIN = "https://lkdr.nalog.gov.ru"
internal const val FNS_AUTH_START_PATH = "/api/v2/auth/challenge/phone/start"
internal const val FNS_AUTH_VERIFY_PATH = "/api/v1/auth/challenge/phone/verify"
internal const val FNS_AUTH_REFRESH_URL = "$FNS_AUTH_ORIGIN/api/v1/auth/token"
private const val AUTH_WAIT_MILLIS = 300_000.0

internal fun interface ReceiptBrowserLoginCapture {
    fun capture(): ReceiptSessionEnvelope
}

internal class VisibleReceiptBrowserLoginCapture : ReceiptBrowserLoginCapture {
    override fun capture(): ReceiptSessionEnvelope {
        try {
            Playwright.create().use { playwright ->
                playwright.chromium().launch(
                    BrowserType.LaunchOptions().setHeadless(false),
                ).use { browser ->
                    browser.newContext().use { context ->
                        val page = context.newPage()
                        page.navigate(FNS_LOGIN_URL)
                        val capture = ReceiptBrowserResponseCapture()
                        val start = waitForAuthResponse(page, FNS_AUTH_START_PATH)
                        capture.observeStart(
                            url = start.url(),
                            method = start.request().method(),
                            status = start.status(),
                            requestBody = { start.request().postData() },
                        )

                        val verify = waitForAuthResponse(page, FNS_AUTH_VERIFY_PATH)
                        return capture.observeVerify(
                            url = verify.url(),
                            method = verify.request().method(),
                            status = verify.status(),
                            responseBody = verify::text,
                        ) ?: throw IllegalStateException("ФНС не подтвердила авторизацию.")
                    }
                }
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (_: Throwable) {
            throw IllegalStateException("Видимый вход ФНС не завершён; session не сохранена.")
        }
    }

    private fun waitForAuthResponse(page: Page, path: String): Response = try {
        page.waitForResponse(
            Predicate { response ->
                isVerifiedFnsAuthRequest(response.url(), response.request().method(), path)
            },
            Page.WaitForResponseOptions().setTimeout(AUTH_WAIT_MILLIS),
            Runnable { },
        )
    } catch (_: Throwable) {
        throw IllegalStateException("Ожидание ручного шага авторизации ФНС завершилось; session не сохранена.")
    }
}

internal class ReceiptBrowserResponseCapture {
    private var deviceInfo: JsonObject? = null

    fun observeStart(
        url: String,
        method: String,
        status: Int,
        requestBody: () -> String?,
    ) {
        if (!isVerifiedFnsAuthRequest(url, method, FNS_AUTH_START_PATH)) return
        if (status !in 200..299) throw IllegalStateException("ФНС не приняла начало авторизации.")
        if (deviceInfo == null) deviceInfo = extractReceiptDeviceInfo(requestBody())
    }

    fun observeVerify(
        url: String,
        method: String,
        status: Int,
        responseBody: () -> String,
    ): ReceiptSessionEnvelope? {
        if (!isVerifiedFnsAuthRequest(url, method, FNS_AUTH_VERIFY_PATH)) return null
        if (status !in 200..299) throw IllegalStateException("ФНС не подтвердила авторизацию.")
        val capturedDeviceInfo = deviceInfo
            ?: throw IllegalStateException("Данные устройства для авторизации отсутствуют.")
        return parseReceiptTokenResponse(responseBody(), capturedDeviceInfo)
    }
}

internal fun isVerifiedFnsAuthRequest(url: String, method: String, path: String): Boolean {
    if (method != "POST") return false
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) &&
        uri.host?.lowercase(Locale.ROOT) == "lkdr.nalog.gov.ru" &&
        (uri.port == -1 || uri.port == 443) &&
        uri.rawUserInfo == null &&
        uri.rawQuery == null &&
        uri.rawFragment == null &&
        uri.rawPath == path
}

internal fun extractReceiptDeviceInfo(startRequestBody: String?): JsonObject = try {
    val request = receiptAuthJson.parseToJsonElement(startRequestBody.orEmpty()).jsonObject
    val source = request["deviceInfo"]?.jsonObject ?: throw IllegalArgumentException()
    val appVersion = source.requiredString("appVersion")
    val sourceDeviceId = source.requiredString("sourceDeviceId")
    val sourceType = source.requiredString("sourceType")
    val userAgent = source["metaDetails"]?.jsonObject?.requiredString("userAgent")
        ?: throw IllegalArgumentException()
    buildJsonObject {
        put("appVersion", JsonPrimitive(appVersion))
        put("sourceDeviceId", JsonPrimitive(sourceDeviceId))
        put("sourceType", JsonPrimitive(sourceType))
        put("metaDetails", buildJsonObject { put("userAgent", JsonPrimitive(userAgent)) })
    }
} catch (_: Throwable) {
    throw IllegalStateException("Не удалось извлечь минимальные данные устройства; session не сохранена.")
}

internal fun parseReceiptTokenResponse(responseBody: String, deviceInfo: JsonObject): ReceiptSessionEnvelope = try {
    val response = receiptAuthJson.parseToJsonElement(responseBody).jsonObject
    ReceiptSessionEnvelope(
        accessToken = response.requiredString("token"),
        refreshToken = response.requiredString("refreshToken"),
        deviceInfo = deviceInfo,
        tokenExpireIn = response["tokenExpireIn"]?.jsonPrimitive?.contentOrNull,
        refreshTokenExpiresIn = response["refreshTokenExpiresIn"]?.jsonPrimitive?.contentOrNull,
    )
} catch (_: Throwable) {
    throw IllegalStateException("Ответ авторизации ФНС неполный или неподдерживаемый; session не сохранена.")
}

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException()

private val receiptAuthJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
