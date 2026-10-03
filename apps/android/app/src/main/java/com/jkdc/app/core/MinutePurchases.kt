package com.jkdc.app.core

import java.util.Currency
import java.util.Locale
import kotlinx.coroutines.flow.Flow

internal val minuteUUID = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
internal val minuteIdentifier = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}")
internal fun validPurchaseToken(value: String) = value.length in 1..4096 && value.all { it.code in 33..126 }
private const val MAX_MINUTE_MS = 86_400_000L

/** Token-billing catalog kinds (services/api pack-products.ts). The unlock is a
    one-time purchase; the token pack credits prepaid nanoUSD service credit.
    Prices and credit are server constants — the client never does pack math:
    the unlock always echoes credit 0, the pack its fixed nanoUSD credit. */
const val PACK_UNLOCK_KIND = "byok_unlock"
const val PACK_TOKEN_KIND = "token_pack"
internal val PACK_KINDS = setOf(PACK_UNLOCK_KIND, PACK_TOKEN_KIND)
internal val nanoUSDString = Regex("[0-9]{1,16}")
internal fun validPackCredit(kind: String, creditNanoUSD: Long) =
    (kind == PACK_TOKEN_KIND && creditNanoUSD > 0) || (kind == PACK_UNLOCK_KIND && creditNanoUSD == 0L)

data class MinuteProduct(val sku: String, val providerProduct: String, val minutes: Int,
    val currency: String, val totalMinor: Long, val environment: String, val aiValue: AIValueEntitlement? = null,
    val entitlementKind: String? = null, val creditNanoUSD: Long = 0) {
    val isPack: Boolean get() = entitlementKind != null
    init {
        require(sku.length <= 128 && minuteIdentifier.matches(sku) && minuteIdentifier.matches(providerProduct))
        require(Regex("[a-z]{3}").matches(currency) && totalMinor in 1..100_000_000)
        require(environment == "test" || environment == "live")
        if (entitlementKind == null) {
            require(minutes > 0 && (aiValue != null || minutes <= 1440) && creditNanoUSD == 0L)
            require(aiValue == null || (aiValue.quote.currency == currency && aiValue.quote.totalMinor == totalMinor && aiValue.displayMinutes == minutes))
        } else {
            // Pack catalog entries carry no minute math of their own: kind, price
            // and credit are validated server constants echoed back verbatim.
            require(entitlementKind in PACK_KINDS && minutes == 0 && aiValue == null && validPackCredit(entitlementKind, creditNanoUSD))
        }
    }
    fun expectedMicros(): Long? = try {
        val exponent = Currency.getInstance(currency.uppercase(Locale.ROOT)).defaultFractionDigits
        if (exponent !in 0..3) null else totalMinor * when (exponent) { 0 -> 1_000_000; 1 -> 100_000; 2 -> 10_000; else -> 1_000 }
    } catch (_: IllegalArgumentException) { null }
}
data class MinuteCatalog(val available: Boolean, val products: List<MinuteProduct>) {
    init {
        require(products.size <= 100 && available == products.isNotEmpty())
        require(products.distinctBy { it.sku }.size == products.size)
    }
}
data class PlayOrderBinding(val orderID: String, val obfuscatedAccountID: String, val obfuscatedProfileID: String) {
    init { require(minuteUUID.matches(orderID) && Regex("[a-f0-9]{64}").matches(obfuscatedAccountID) && Regex("[a-f0-9]{64}").matches(obfuscatedProfileID)) }
    override fun toString() = "PlayOrderBinding(redacted)"
}
data class MinuteOrder(val orderID: String, val minutes: Int, val currency: String, val totalMinor: Long, val payment: PlayOrderBinding,
    val aiValue: AIValueEntitlement? = null, val entitlementKind: String? = null, val creditNanoUSD: Long = 0) {
    init {
        require(minuteUUID.matches(orderID) && payment.orderID == orderID)
        require(Regex("[a-z]{3}").matches(currency) && totalMinor in 1..100_000_000)
        if (entitlementKind == null) {
            require(minutes > 0 && (aiValue != null || minutes <= 1440) && creditNanoUSD == 0L)
            require(aiValue == null || (aiValue.quote.currency == currency && aiValue.quote.totalMinor == totalMinor && aiValue.displayMinutes == minutes))
        } else {
            require(entitlementKind in PACK_KINDS && minutes == 0 && aiValue == null && validPackCredit(entitlementKind, creditNanoUSD))
        }
    }
    fun matches(product: MinuteProduct) = minutes == product.minutes && currency == product.currency &&
        totalMinor == product.totalMinor && aiValue == product.aiValue &&
        entitlementKind == product.entitlementKind && creditNanoUSD == product.creditNanoUSD
    override fun toString() = "MinuteOrder(redacted)"
}
data class MinutePurchaseStatus(val orderID: String, val state: String, val grantedMilliseconds: Long,
    val reversedMilliseconds: Long, val reversalOutstandingMilliseconds: Long, val fulfillmentRecorded: Boolean,
    val aiValue: AIValueFulfillment? = null, val entitlementKind: String? = null,
    val grantedNanoUSD: Long = 0, val reversedNanoUSD: Long = 0, val unlockedAt: String? = null) {
    val isPack: Boolean get() = entitlementKind != null
    init {
        require(minuteUUID.matches(orderID) && state in listOf("created", "pending", "purchased", "voided"))
        require(grantedMilliseconds in 0..MAX_MINUTE_MS && grantedMilliseconds % 60_000L == 0L)
        require(reversedMilliseconds in 0..grantedMilliseconds && reversalOutstandingMilliseconds in 0..(grantedMilliseconds - reversedMilliseconds))
        require(state !in listOf("created", "pending") || !fulfillmentRecorded)
        require(state !in listOf("created", "pending") || grantedMilliseconds == 0L)
        if (entitlementKind == null) {
            require(fulfillmentRecorded == (aiValue?.recorded ?: (grantedMilliseconds > 0)))
            require(aiValue == null || (grantedMilliseconds == 0L && reversedMilliseconds == 0L && reversalOutstandingMilliseconds == 0L))
            require(grantedNanoUSD == 0L && reversedNanoUSD == 0L && unlockedAt == null)
        } else {
            // Pack statuses mirror pack-products.ts: money fields are nanoUSD, and
            // only a live-environment unlock is ever recorded as fulfilled.
            require(entitlementKind in PACK_KINDS && aiValue == null &&
                grantedMilliseconds == 0L && reversedMilliseconds == 0L && reversalOutstandingMilliseconds == 0L)
            require(grantedNanoUSD >= 0 && reversedNanoUSD in 0..grantedNanoUSD)
            require(unlockedAt == null || (entitlementKind == PACK_UNLOCK_KIND && unlockedAt.length <= 40))
            require(fulfillmentRecorded == if (entitlementKind == PACK_UNLOCK_KIND) unlockedAt != null else grantedNanoUSD > 0)
            require(state !in listOf("created", "pending") || (grantedNanoUSD == 0L && unlockedAt == null))
        }
    }
}
interface MinuteCommerceService {
    suspend fun catalog(): MinuteCatalog
    suspend fun create(session: AccountSession, sku: String, idempotencyKey: String): MinuteOrder
    suspend fun status(session: AccountSession, orderID: String): MinutePurchaseStatus
    suspend fun verify(session: AccountSession, orderID: String, token: String): MinutePurchaseStatus
    suspend fun recover(session: AccountSession, token: String): MinutePurchaseStatus
    suspend fun balance(session: AccountSession): MinuteBalance
}

/** A short-lived handle refers to SDK ProductDetails retained only inside the store adapter. */
data class MinuteStoreOffer(val handle: String, val productID: String, val currency: String, val priceMicros: Long, val formattedPrice: String) {
    init {
        require(minuteUUID.matches(handle) && minuteIdentifier.matches(productID) && Regex("[A-Z]{3}").matches(currency))
        require(priceMicros in 1..100_000_000_000_000 && formattedPrice.isNotBlank() && formattedPrice.length <= 100 && formattedPrice.none { it.isISOControl() })
    }
    fun matches(product: MinuteProduct) = productID == product.providerProduct && currency == product.currency.uppercase(Locale.ROOT) && priceMicros == product.expectedMicros()
}
data class PreparedMinutePurchase(val product: MinuteProduct, val order: MinuteOrder, val offer: MinuteStoreOffer) {
    init { require(order.matches(product) && offer.matches(product)) }
    override fun toString() = "PreparedMinutePurchase(redacted)"
}
enum class MinuteStoreOutcome { OPENED, PURCHASES_UPDATED, CANCELED, UNAVAILABLE, ALREADY_OWNED, FAILED }
enum class MinuteStorePurchaseState { PENDING, PURCHASED }
class MinuteStorePurchase(val token: String, val state: MinuteStorePurchaseState) {
    init { require(validPurchaseToken(token)) }
    override fun toString() = "MinuteStorePurchase(redacted)"
}
data class MinuteStoreEvent(val outcome: MinuteStoreOutcome, val purchases: List<MinuteStorePurchase> = emptyList()) {
    init { require(purchases.size <= 100) }
    override fun toString() = "MinuteStoreEvent($outcome, count=${purchases.size})"
}
interface MinuteStoreGateway {
    val events: Flow<MinuteStoreEvent>
    suspend fun connect()
    suspend fun offers(productIDs: List<String>): List<MinuteStoreOffer>
    suspend fun purchases(): List<MinuteStorePurchase>
    fun close()
}
sealed class MinuteCommerceFailure : Exception() {
    data object Unavailable : MinuteCommerceFailure()
    data object InvalidResponse : MinuteCommerceFailure()
    data object SignInRequired : MinuteCommerceFailure()
    data object PriceChanged : MinuteCommerceFailure()
    class Http(val status: Int, val code: String?) : MinuteCommerceFailure()
}
