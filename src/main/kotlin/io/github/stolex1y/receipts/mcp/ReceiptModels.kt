package io.github.stolex1y.receipts.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal enum class ReceiptMode {
    FAKE,
    PRIVATE,
    ;

    companion object {
        fun fromEnvironment(value: String?): ReceiptMode = when (value?.trim()?.lowercase()) {
            "private", "real" -> PRIVATE
            else -> FAKE
        }
    }
}

internal data class ReceiptSearchInput(
    val query: String? = null,
    val from: String? = null,
    val to: String? = null,
    val limit: Int = 50,
    val offset: Int = 0,
    val orderBy: String = DEFAULT_ORDER_BY,
)

internal data class ReceiptAuthorization(
    val accessToken: String,
    val authCookie: String? = null,
    val authCookieHost: String? = null,
) {
    override fun toString(): String = "ReceiptAuthorization(redacted)"
}

internal fun interface ReceiptSessionProvider {
    suspend fun accessToken(): String?
    suspend fun authCookie(): String? = null
    suspend fun authCookieHost(): String? = null
    suspend fun authorization(apiHost: String? = null): ReceiptAuthorization? {
        val token = accessToken()?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val cookieHost = authCookieHost()?.trim()?.lowercase()
        val cookie = authCookie()?.takeIf(String::isNotBlank)
            ?.takeIf { cookieHost == null || apiHost == null || cookieHost == apiHost.lowercase() }
        return ReceiptAuthorization(token, cookie, cookieHost)
    }
    suspend fun invalidate() = Unit
    suspend fun invalidateIfCurrent(accessToken: String) = Unit
    suspend fun refreshAfterRejection(
        accessToken: String,
        apiHost: String? = null,
    ): ReceiptAuthorization? {
        invalidateIfCurrent(accessToken)
        return null
    }
}
internal object EmptyReceiptSessionProvider : ReceiptSessionProvider {
    override suspend fun accessToken(): String? = null
}

internal class ReceiptAuthenticationException(message: String) : RuntimeException(message)

internal class ReceiptIntegrationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

@Serializable
data class ReceiptSummary(
    @SerialName("receipt_key") val receiptKey: String,
    val merchant: String,
    @SerialName("received_at") val receivedAt: String,
    @SerialName("amount_minor") val amountMinor: Long,
    val currency: String = "RUB",
)

@Serializable
data class ReceiptSearchResponse(
    val receipts: List<ReceiptSummary>,
    @SerialName("has_more") val hasMore: Boolean,
)

@Serializable
data class ReceiptItem(
    val name: String,
    val quantity: Double,
    @SerialName("price_minor") val priceMinor: Long,
    @SerialName("sum_minor") val sumMinor: Long,
    @SerialName("vat_rate") val vatRate: Int,
    @SerialName("payment_type") val paymentType: Int,
    @SerialName("product_type") val productType: Int,
)

@Serializable
data class ReceiptDetail(
    @SerialName("receipt_key") val receiptKey: String,
    @SerialName("date_time") val dateTime: String,
    @SerialName("fiscal_document_number") val fiscalDocumentNumber: Long,
    @SerialName("fiscal_drive_number") val fiscalDriveNumber: String,
    @SerialName("fiscal_sign") val fiscalSign: String,
    @SerialName("kkt_reg_id") val kktRegId: String,
    @SerialName("total_minor") val totalMinor: Long,
    val currency: String = "RUB",
    val items: List<ReceiptItem>,
    @SerialName("settlement_place") val settlementPlace: String? = null,
)

@Serializable
internal data class UpstreamReceiptRequest(
    @SerialName("dateFrom") val dateFrom: String? = null,
    @SerialName("dateTo") val dateTo: String? = null,
    val inn: String? = null,
    @SerialName("kktOwner") val kktOwner: String? = null,
    val limit: Int,
    val offset: Int,
    @SerialName("orderBy") val orderBy: String,
)

@Serializable
internal data class UpstreamBrand(
    val id: Long = 0,
    val name: String = "",
    val description: String = "",
)

@Serializable
internal data class UpstreamReceipt(
    @SerialName("brandId") val brandId: Long? = null,
    val buyer: String = "",
    val buyerType: String = "",
    val createdDate: String = "",
    val fiscalDocumentNumber: String = "",
    val fiscalDriveNumber: String = "",
    val key: String = "",
    val kktOwner: String = "",
    val kktOwnerInn: String = "",
    val receiveDate: String = "",
    val totalSum: String = "",
)

@Serializable
internal data class UpstreamReceiptListResponse(
    val brands: List<UpstreamBrand>,
    val receipts: List<UpstreamReceipt>,
    val hasMore: Boolean,
)

@Serializable
internal data class UpstreamFiscalDataRequest(
    val key: String,
)

@Serializable
internal data class UpstreamFiscalItem(
    val name: String,
    val nds: Int,
    val paymentType: Int,
    val price: Double,
    val productType: Int,
    val quantity: Double,
    val sum: Double,
)

@Serializable
internal data class UpstreamFiscalDataResponse(
    val dateTime: String,
    val fiscalDocumentNumber: Long,
    val fiscalDriveNumber: String,
    val fiscalSign: String,
    val items: List<UpstreamFiscalItem>,
    val kktRegId: String,
    val totalSum: Double,
    val retailPlace: String? = null,
)

@Serializable
internal data class UpstreamError(
    val code: String? = null,
)

internal const val DEFAULT_ORDER_BY = "RECEIVE_DATE:DESC"
