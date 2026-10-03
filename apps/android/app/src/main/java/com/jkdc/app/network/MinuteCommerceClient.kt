package com.jkdc.app.network

import com.jkdc.app.core.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer

class MinuteCommerceConfiguration private constructor(val origin: HttpUrl) {
    companion object {
        fun parse(value: String): MinuteCommerceConfiguration? {
            val url = value.toHttpUrlOrNull() ?: return null
            if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() || url.port != 443 ||
                url.encodedPath != "/" || url.query != null || url.fragment != null || url.host == "openai.com" || url.host.endsWith(".openai.com")) return null
            return MinuteCommerceConfiguration(url)
        }
    }
}

/** Fixed backend endpoints only; client price, quantity, account identity and payment state are never sent. */
class MinuteCommerceClient internal constructor(private val origin: HttpUrl, transport: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val channel: PurchaseChannel = PurchaseChannel.PLAY) : MinuteCommerceService, StripeCommerceService {
    constructor(config: MinuteCommerceConfiguration, channel: PurchaseChannel = PurchaseChannel.PLAY) : this(config.origin, OkHttpClient(), channel = channel)
    private val client = transport.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).cache(null).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .retryOnConnectionFailure(false).callTimeout(30, TimeUnit.SECONDS)
        .apply { interceptors().clear(); networkInterceptors().clear() }.build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun catalog(): MinuteCatalog = decoded {
        val body = request("GET", "minutes/products", providerQuery = true)
        val basis = body.text("billingBasis")
        if (basis !in listOf("connected-conversation-time", "actual-ai-usage", "prepaid-service-credit")) throw MinuteCommerceFailure.InvalidResponse
        val products = body["products"] as? JsonArray ?: throw MinuteCommerceFailure.InvalidResponse
        if (products.size > 100) throw MinuteCommerceFailure.InvalidResponse
        MinuteCatalog(body.boolean("available"), products.map { value ->
            val item = value as? JsonObject ?: throw MinuteCommerceFailure.InvalidResponse
            val kind = (item["entitlementKind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (kind in PACK_KINDS) parsePackProduct(item, kind!!)
            else if (basis == "prepaid-service-credit") throw MinuteCommerceFailure.InvalidResponse
            else {
                val ai = if (basis == "actual-ai-usage") parseAIValue(item) else null
                MinuteProduct(item.text("sku"), item.text("providerProduct"), ai?.displayMinutes ?: item.count("minutes", 1440).toInt(), item.text("currency"),
                    item.count("totalMinor", 100_000_000), item.text("environment"), ai)
            }
        })
    }
    /** Pack catalog entries echo server constants (kind, price, credit); any
        arithmetic of our own is forbidden, so the fields are validated as-is. */
    private fun parsePackProduct(item: JsonObject, kind: String): MinuteProduct {
        val credit = (item["creditNanoUSD"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw MinuteCommerceFailure.InvalidResponse
        val nano = credit.toLongOrNull()?.takeIf { it >= 0 && nanoUSDString.matches(credit) }
            ?: throw MinuteCommerceFailure.InvalidResponse
        return MinuteProduct(item.text("sku"), item.text("providerProduct"), 0, item.text("currency"),
            item.count("totalMinor", 100_000_000), item.text("environment"), entitlementKind = kind, creditNanoUSD = nano)
    }
    override suspend fun create(session: AccountSession, sku: String, idempotencyKey: String): MinuteOrder = decoded {
        if (channel != PurchaseChannel.PLAY) throw MinuteCommerceFailure.Unavailable
        if (sku.length > 128 || !minuteIdentifier.matches(sku) || !Regex("[A-Za-z0-9._:-]{8,128}").matches(idempotencyKey))
            throw MinuteCommerceFailure.InvalidResponse
        val body = request("POST", "minutes/orders", session, buildJsonObject { put("provider", "play"); put("sku", sku) }, idempotencyKey)
        val payment = body["payment"] as? JsonObject ?: throw MinuteCommerceFailure.InvalidResponse
        val binding = PlayOrderBinding(payment.text("orderID"), payment.text("obfuscatedAccountID"), payment.text("obfuscatedProfileID"))
        val pack = packFields(body)
        if (pack != null) MinuteOrder(body.text("orderID"), 0, body.text("currency"), body.count("totalMinor", 100_000_000), binding,
            entitlementKind = pack.first, creditNanoUSD = pack.second)
        else {
            val ai = if (body["entitlementKind"] == JsonPrimitive("ai_value")) parseAIValue(body) else null
            MinuteOrder(body.text("orderID"), ai?.displayMinutes ?: body.count("minutes", 1440).toInt(), body.text("currency"),
                body.count("totalMinor", 100_000_000), binding, ai)
        }
    }
    override suspend fun createStripe(session: AccountSession, sku: String, idempotencyKey: String): StripeMinuteOrder = decoded {
        if (channel != PurchaseChannel.STRIPE) throw MinuteCommerceFailure.Unavailable
        if (sku.length > 128 || !minuteIdentifier.matches(sku) || !minuteUUID.matches(idempotencyKey))
            throw MinuteCommerceFailure.InvalidResponse
        val body = request("POST", "minutes/orders", session,
            buildJsonObject { put("provider", "stripe"); put("sku", sku) }, idempotencyKey)
        val payment = body["payment"] as? JsonObject ?: throw MinuteCommerceFailure.InvalidResponse
        val checkout = StripeCheckoutURL.checked(payment.text("checkoutURL"))
        val pack = packFields(body)
        val order = if (pack != null)
            StripeMinuteOrder(body.text("orderID"), body.text("currency"), body.count("totalMinor", 100_000_000), checkout,
                entitlementKind = pack.first, creditNanoUSD = pack.second)
        else
            StripeMinuteOrder(body.text("orderID"), body.text("currency"), body.count("totalMinor", 100_000_000), checkout, parseAIValue(body))
        if (payment.text("orderID") != order.orderID) throw MinuteCommerceFailure.InvalidResponse
        return@decoded order
    }
    /** Order bodies for pack SKUs carry the entitlement kind and the credited
        nanoUSD instead of minutes; anything else is not a pack order. */
    private fun packFields(body: JsonObject): Pair<String, Long>? {
        val kind = (body["entitlementKind"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (kind !in PACK_KINDS) return null
        val credit = (body["creditNanoUSD"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw MinuteCommerceFailure.InvalidResponse
        val nano = credit.toLongOrNull()?.takeIf { it >= 0 && nanoUSDString.matches(credit) }
            ?: throw MinuteCommerceFailure.InvalidResponse
        return kind to nano
    }
    override suspend fun findStripeOrder(session: AccountSession, idempotencyKey: String): String? = decoded {
        if (channel != PurchaseChannel.STRIPE) throw MinuteCommerceFailure.Unavailable
        if (!minuteUUID.matches(idempotencyKey)) throw MinuteCommerceFailure.InvalidResponse
        try {
            request("GET", "minutes/orders/by-key/$idempotencyKey", session, providerQuery = true)
                .text("orderID").also(::validateID)
        } catch (error: MinuteCommerceFailure.Http) {
            if (error.status == 404) null else throw error
        }
    }
    override suspend fun status(session: AccountSession, orderID: String): MinutePurchaseStatus = decoded {
        validateID(orderID); parseStatus(request("GET", "minutes/orders/$orderID", session)).also {
            if (it.orderID != orderID) throw MinuteCommerceFailure.InvalidResponse
        }
    }
    override suspend fun verify(session: AccountSession, orderID: String, token: String): MinutePurchaseStatus = decoded {
        if (channel != PurchaseChannel.PLAY) throw MinuteCommerceFailure.Unavailable
        validateID(orderID); validateToken(token)
        parseStatus(request("POST", "minutes/orders/$orderID/play", session, buildJsonObject { put("purchaseToken", token) })).also {
            if (it.orderID != orderID) throw MinuteCommerceFailure.InvalidResponse
        }
    }
    override suspend fun recover(session: AccountSession, token: String): MinutePurchaseStatus = decoded {
        if (channel != PurchaseChannel.PLAY) throw MinuteCommerceFailure.Unavailable
        validateToken(token)
        parseStatus(request("POST", "minutes/play/recover", session, buildJsonObject { put("purchaseToken", token) }))
    }
    override suspend fun balance(session: AccountSession): MinuteBalance = decoded {
        val body = request("GET", "minutes", session)
        json.decodeFromJsonElement<MinuteBalance>(body)
    }
    /** Display hint only: the prepaid token wallet (GET /v1/wallet). No caching
        happens here; callers keep the value in memory for the current surface. */
    suspend fun tokenWallet(session: AccountSession): TokenWallet = decoded {
        val body = request("GET", "wallet", session)
        TokenWallet.parse(body.text("currency"), body.text("balanceNanoUSD"), body.text("reservedNanoUSD"),
            body.text("availableNanoUSD")) ?: throw MinuteCommerceFailure.InvalidResponse
    }
    private fun parseStatus(body: JsonObject): MinutePurchaseStatus {
        val kind = (body["entitlementKind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (kind in PACK_KINDS) {
            // Pack statuses (PackPurchaseStatus) carry nanoUSD money fields and no
            // credit field; a non-zero outstanding reversal is never sent today.
            if ((body["reversalOutstandingNanoUSD"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "0")
                throw MinuteCommerceFailure.InvalidResponse
            val unlockedAt = (body["unlockedAt"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
            return MinutePurchaseStatus(body.text("orderID"), body.text("state"), 0, 0, 0, body.boolean("fulfillmentRecorded"),
                entitlementKind = kind, grantedNanoUSD = body.nanoField("grantedNanoUSD"),
                reversedNanoUSD = body.nanoField("reversedNanoUSD"), unlockedAt = unlockedAt)
        }
        val ai = if (kind == "ai_value") AIValueFulfillment(body.text("grantedNanoUSD"),
            body.text("reversedNanoUSD"), body.text("reversalOutstandingNanoUSD")) else null
        return MinutePurchaseStatus(body.text("orderID"), body.text("state"),
            if (ai == null) body.count("grantedMilliseconds", 86_400_000) else 0,
            if (ai == null) body.count("reversedMilliseconds", 86_400_000) else 0,
            if (ai == null) body.count("reversalOutstandingMilliseconds", 86_400_000) else 0,
            body.boolean("fulfillmentRecorded"), ai)
    }
    /** Server money fields are quoted BigInt strings; anything else fails closed. */
    private fun JsonObject.nanoField(key: String): Long {
        val raw = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw MinuteCommerceFailure.InvalidResponse
        return raw.toLongOrNull()?.takeIf { it >= 0 && nanoUSDString.matches(raw) } ?: throw MinuteCommerceFailure.InvalidResponse
    }
    private fun parseAIValue(body: JsonObject): AIValueEntitlement {
        if (body.text("entitlementKind") != "ai_value" || body.text("billingBasis") != "actual-ai-usage" ||
            !body.boolean("estimate")) throw MinuteCommerceFailure.InvalidResponse
        val quote = body["quote"] as? JsonObject ?: throw MinuteCommerceFailure.InvalidResponse
        return AIValueEntitlement(body.text("aiValueNanoUSD"), body.count("estimatedMilliseconds", MAX_AI_ESTIMATE_MS),
            json.decodeFromJsonElement<AIValueQuote>(quote))
    }
    private fun validateID(value: String) { if (!minuteUUID.matches(value)) throw MinuteCommerceFailure.InvalidResponse }
    private fun validateToken(value: String) { if (!validPurchaseToken(value)) throw MinuteCommerceFailure.InvalidResponse }
    private suspend fun <T> decoded(block: suspend () -> T): T = try { block() }
        catch (error: IllegalArgumentException) { throw MinuteCommerceFailure.InvalidResponse }

    private suspend fun request(method: String, path: String, session: AccountSession? = null, body: JsonObject? = null,
        idempotencyKey: String? = null, providerQuery: Boolean = false): JsonObject {
        if (session != null && !session.isValid(now())) throw MinuteCommerceFailure.SignInRequired
        val url = origin.newBuilder().addPathSegments("v1/$path").apply { if (providerQuery) addQueryParameter("provider", channel.provider) }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json").header("Cache-Control", "no-store")
            .apply { session?.let { header("Authorization", "Bearer ${it.accessToken}") }; idempotencyKey?.let { header("Idempotency-Key", it) } }
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request); continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(MinuteCommerceFailure.Unavailable)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            val buffer = Buffer(); val source = it.body?.source() ?: throw MinuteCommerceFailure.InvalidResponse
                            if ((it.body?.contentLength() ?: 0) > 131_072) throw MinuteCommerceFailure.InvalidResponse
                            while (buffer.size <= 131_072) {
                                if (source.read(buffer, minOf(8192, 131_073 - buffer.size)) == -1L) break
                            }
                            if (buffer.size > 131_072) throw MinuteCommerceFailure.InvalidResponse
                            val parsed = try { json.parseToJsonElement(buffer.readUtf8()) as? JsonObject } catch (_: Exception) { null }
                            if (!it.isSuccessful) {
                                val code = ((parsed?.get("error") as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull
                                    ?.takeIf { value -> value.length <= 80 && Regex("[a-z_]+").matches(value) }
                                throw MinuteCommerceFailure.Http(it.code, code)
                            }
                            parsed ?: throw MinuteCommerceFailure.InvalidResponse
                        }
                        if (continuation.isActive) continuation.resume(value)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error as? MinuteCommerceFailure ?: MinuteCommerceFailure.InvalidResponse)
                    }
                }
            })
        }
    }
}
private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    ?: throw MinuteCommerceFailure.InvalidResponse
private fun JsonObject.count(key: String, max: Long = 9_007_199_254_740_991) =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0..max } ?: throw MinuteCommerceFailure.InvalidResponse
private fun JsonObject.boolean(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    ?: throw MinuteCommerceFailure.InvalidResponse
