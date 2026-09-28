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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeParseException

private const val DEFAULT_API_BASE = "https://mco.nalog.ru/api"
private const val MAX_LIMIT = 100
private const val MAX_OFFSET = 10_000
private const val MAX_QUERY_LENGTH = 200
private val ORDER_BY_PATTERN = Regex("[A-Za-z0-9_:-]+")

private val apiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

internal class ReceiptService(
    private val httpClient: HttpClient,
    private val mode: ReceiptMode = ReceiptMode.FAKE,
    apiBase: String = DEFAULT_API_BASE,
    private val sessionProvider: ReceiptSessionProvider = EmptyReceiptSessionProvider,
    private val requireAuthenticatedSession: Boolean = false,
) {
    private val apiBase = apiBase.trimEnd('/')

    suspend fun search(input: ReceiptSearchInput): ReceiptSearchResponse {
        val validated = validate(input)
        return when (mode) {
            ReceiptMode.FAKE -> {
                requireAuthenticatedIfConfigured()
                fakeSearch(validated)
            }
            ReceiptMode.PRIVATE -> privateSearch(validated)
        }
    }

    suspend fun getReceipt(receiptKey: String): ReceiptDetail {
        val key = receiptKey.trim()
        require(key.isNotEmpty()) { "receipt_key обязателен." }
        require(key.length <= 200) { "receipt_key слишком длинный." }

        return when (mode) {
            ReceiptMode.FAKE -> {
                requireAuthenticatedIfConfigured()
                fakeDetails[key]
                    ?: throw IllegalArgumentException("Чек с указанным ключом не найден.")
            }
            ReceiptMode.PRIVATE -> privateGetReceipt(key)
        }
    }

    private suspend fun privateSearch(input: ReceiptSearchInput): ReceiptSearchResponse {
        val authorization = requireAuthorization()
        val source = postJson<UpstreamReceiptRequest, UpstreamReceiptListResponse>(
            path = "/v1/receipt",
            request = UpstreamReceiptRequest(
                dateFrom = input.from,
                dateTo = input.to,
                kktOwner = input.query,
                limit = input.limit,
                offset = input.offset,
                orderBy = input.orderBy,
            ),
            accessToken = authorization.accessToken,
            authCookie = authorization.authCookie,
        )
        val brands = source.brands.associateBy(UpstreamBrand::id)
        return ReceiptSearchResponse(
            receipts = source.receipts.map { it.toSummary(brands) },
            hasMore = source.hasMore,
        )
    }
    private suspend fun privateGetReceipt(receiptKey: String): ReceiptDetail {
        val authorization = requireAuthorization()
        val source = postJson<UpstreamFiscalDataRequest, UpstreamFiscalDataResponse>(
            path = "/v1/receipt/fiscal_data",
            request = UpstreamFiscalDataRequest(key = receiptKey),
            accessToken = authorization.accessToken,
            authCookie = authorization.authCookie,
        )
        return source.toDetail(receiptKey)
    }

    private suspend fun requireAuthenticatedIfConfigured() {
        if (requireAuthenticatedSession) requireAuthorization()
    }

    private suspend fun requireAuthorization(): ReceiptAuthorization =
        sessionProvider.authorization(upstreamHost(apiBase))
            ?: throw ReceiptAuthenticationException(
                "Сессия «Мои чеки онлайн» не настроена; запустите ручной browser login через /receipts/browser-login.",
            )

    private suspend inline fun <reified T, reified R> postJson(
        path: String,
        request: T,
        accessToken: String,
        authCookie: String?,
    ): R {
        val response = try {
            httpClient.post(apiBase + path) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                authCookie
                    ?.takeIf(String::isNotBlank)
                    ?.let { header(HttpHeaders.Cookie, it) }
                setBody(apiJson.encodeToString(request))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw ReceiptIntegrationException("Не удалось обратиться к сервису чеков.", error)
        }

        val body = try {
            response.bodyAsText()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw ReceiptIntegrationException("Не удалось прочитать ответ сервиса чеков.", error)
        }

        if (response.status.value !in 200..299) {
            if (response.status.value == 401 || response.status.value == 403) {
                sessionProvider.invalidateIfCurrent(accessToken)
                throw ReceiptAuthenticationException("Сессия сервиса чеков отклонена или истекла.")
            }
            throw ReceiptIntegrationException("Сервис чеков временно недоступен.")
        }

        return try {
            apiJson.decodeFromString<R>(body)
        } catch (error: Throwable) {
            throw ReceiptIntegrationException("Сервис чеков вернул некорректный JSON.", error)
        }
    }

    private fun validate(input: ReceiptSearchInput): ReceiptSearchInput {
        val query = input.query?.trim()?.takeIf(String::isNotEmpty)
        require(query == null || query.length <= MAX_QUERY_LENGTH) {
            "query слишком длинный."
        }
        val from = parseDate(input.from, "from")
        val to = parseDate(input.to, "to")
        if (from != null && to != null) {
            require(!to.isBefore(from)) { "to не может быть раньше from." }
        }
        require(input.limit in 1..MAX_LIMIT) { "limit должен быть от 1 до $MAX_LIMIT." }
        require(input.offset in 0..MAX_OFFSET) { "offset должен быть от 0 до $MAX_OFFSET." }
        val orderBy = input.orderBy.trim()
        require(orderBy.isNotEmpty() && orderBy.length <= 64 && ORDER_BY_PATTERN.matches(orderBy)) {
            "order_by имеет недопустимый формат."
        }
        return input.copy(
            query = query,
            from = from?.toString(),
            to = to?.toString(),
            orderBy = orderBy,
        )
    }

    private fun parseDate(value: String?, name: String): LocalDate? {
        if (value.isNullOrBlank()) return null
        return try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            throw IllegalArgumentException("$name должен быть датой YYYY-MM-DD.")
        }
    }

    private fun fakeSearch(input: ReceiptSearchInput): ReceiptSearchResponse {
        val from = input.from?.let(LocalDate::parse)
        val to = input.to?.let(LocalDate::parse)
        val query = input.query?.lowercase()
        val filtered = fakeReceipts.filter { receipt ->
            val date = LocalDate.parse(receipt.receivedAt.substringBefore('T'))
            val merchantMatches = query == null || receipt.merchant.lowercase().contains(query)
            val fromMatches = from == null || !date.isBefore(from)
            val toMatches = to == null || !date.isAfter(to)
            merchantMatches && fromMatches && toMatches
        }
        val page = filtered.drop(input.offset).take(input.limit)
        return ReceiptSearchResponse(
            receipts = page,
            hasMore = input.offset + page.size < filtered.size,
        )
    }

    private fun UpstreamReceipt.toSummary(brands: Map<Long, UpstreamBrand>): ReceiptSummary {
        val key = key.trim()
        if (key.isEmpty()) {
            throw ReceiptIntegrationException("Сервис чеков вернул чек без ключа.")
        }
        val merchant = kktOwner.trim()
            .takeIf(String::isNotEmpty)
            ?: brandId?.let { brands[it]?.name?.trim()?.takeIf(String::isNotEmpty) }
            ?: "Неизвестный продавец"
        val receivedAt = receiveDate.trim()
            .takeIf(String::isNotEmpty)
            ?: createdDate.trim().takeIf(String::isNotEmpty)
            ?: throw ReceiptIntegrationException("Сервис чеков вернул чек без даты.")
        return ReceiptSummary(
            receiptKey = key,
            merchant = merchant,
            receivedAt = receivedAt,
            amountMinor = parseMinor(totalSum, "totalSum"),
        )
    }

    private fun UpstreamFiscalDataResponse.toDetail(receiptKey: String): ReceiptDetail = ReceiptDetail(
        receiptKey = receiptKey,
        dateTime = dateTime,
        fiscalDocumentNumber = fiscalDocumentNumber,
        fiscalDriveNumber = fiscalDriveNumber,
        fiscalSign = fiscalSign,
        kktRegId = kktRegId,
        totalMinor = parseMinor(totalSum, "totalSum"),
        items = items.map { item ->
            ReceiptItem(
                name = item.name,
                quantity = item.quantity,
                priceMinor = parseMinor(item.price, "item.price"),
                sumMinor = parseMinor(item.sum, "item.sum"),
                vatRate = item.nds,
                paymentType = item.paymentType,
                productType = item.productType,
            )
        },
    )

    private fun parseMinor(value: String, field: String): Long = try {
        BigDecimal(value.replace(',', '.'))
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    } catch (error: Throwable) {
        throw ReceiptIntegrationException("Сервис чеков вернул некорректное поле $field.", error)
    }

    private fun parseMinor(value: Double, field: String): Long = try {
        BigDecimal.valueOf(value)
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    } catch (error: Throwable) {
        throw ReceiptIntegrationException("Сервис чеков вернул некорректное поле $field.", error)
    }

    private companion object {
        val fakeReceipts = listOf(
            ReceiptSummary(
                receiptKey = "receipt-001",
                merchant = "ДЕМО МАРКЕТ",
                receivedAt = "2026-09-10T12:00:00",
                amountMinor = 34900,
            ),
            ReceiptSummary(
                receiptKey = "receipt-002",
                merchant = "ДЕМО КАФЕ",
                receivedAt = "2026-09-14T18:30:00",
                amountMinor = 127500,
            ),
            ReceiptSummary(
                receiptKey = "receipt-003",
                merchant = "ДЕМО МАРКЕТ",
                receivedAt = "2026-09-20T09:15:00",
                amountMinor = 8900,
            ),
        )

        val fakeDetails = mapOf(
            "receipt-001" to ReceiptDetail(
                receiptKey = "receipt-001",
                dateTime = "2026-09-10T12:00:00",
                fiscalDocumentNumber = 1001,
                fiscalDriveNumber = "999900010001",
                fiscalSign = "700001",
                kktRegId = "0001112223334444",
                totalMinor = 34900,
                items = listOf(
                    ReceiptItem(
                        name = "Демо товар",
                        quantity = 1.0,
                        priceMinor = 34900,
                        sumMinor = 34900,
                        vatRate = 20,
                        paymentType = 1,
                        productType = 1,
                    ),
                ),
            ),
            "receipt-002" to ReceiptDetail(
                receiptKey = "receipt-002",
                dateTime = "2026-09-14T18:30:00",
                fiscalDocumentNumber = 1002,
                fiscalDriveNumber = "999900010002",
                fiscalSign = "700002",
                kktRegId = "0001112223334444",
                totalMinor = 127500,
                items = listOf(
                    ReceiptItem(
                        name = "Демо обед",
                        quantity = 1.0,
                        priceMinor = 127500,
                        sumMinor = 127500,
                        vatRate = 20,
                        paymentType = 1,
                        productType = 1,
                    ),
                ),
            ),
            "receipt-003" to ReceiptDetail(
                receiptKey = "receipt-003",
                dateTime = "2026-09-20T09:15:00",
                fiscalDocumentNumber = 1003,
                fiscalDriveNumber = "999900010003",
                fiscalSign = "700003",
                kktRegId = "0001112223334444",
                totalMinor = 8900,
                items = listOf(
                    ReceiptItem(
                        name = "Демо напиток",
                        quantity = 1.0,
                        priceMinor = 8900,
                        sumMinor = 8900,
                        vatRate = 20,
                        paymentType = 1,
                        productType = 1,
                    ),
                ),
            ),
        )
    }
}
