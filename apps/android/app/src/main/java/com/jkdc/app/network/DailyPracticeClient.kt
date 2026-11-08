package com.jkdc.app.network

import com.jkdc.app.core.AccountSession
import com.jkdc.app.core.DailyPracticeCodec
import com.jkdc.app.core.DailyPracticePackage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class DailyPracticeFailure : Exception() {
    class Http(val status: Int, val code: String?) : DailyPracticeFailure()
    object Unavailable : DailyPracticeFailure()
    object OutcomeUnknown : DailyPracticeFailure()
    object InvalidResponse : DailyPracticeFailure()
}

/** Fixed-origin client for the public daily package and owner-bound attempts. */
class DailyPracticeClient internal constructor(private val origin: HttpUrl, transport: OkHttpClient) {
    constructor(configuration: ManagedAccountConfiguration) : this(configuration.origin, OkHttpClient())
    private val client = transport.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(okhttp3.CookieJar.NO_COOKIES).cache(null).authenticator(okhttp3.Authenticator.NONE)
        .retryOnConnectionFailure(false).callTimeout(25, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = false; explicitNulls = true }

    suspend fun currentPackage(language: String): DailyPracticePackage {
        require(language.matches(Regex("^[a-z]{2}(?:-[A-Z]{2})?$"))) { "invalid language" }
        val url = origin.newBuilder().addPathSegments("v1/daily-practice/current")
            .addQueryParameter("language", language).build()
        val response = request("GET", url, null, null)
        return try {
            require(response["schemaVersion"]?.jsonPrimitive?.int == 1)
            DailyPracticeCodec.decodePackage(response["package"]!!.toString())
        } catch (_: Exception) { throw DailyPracticeFailure.InvalidResponse }
    }

    suspend fun createAttempt(session: AccountSession, attemptId: String, packageVersionId: String,
                              explanationLanguage: String, dailyConsentVersion: Int, idempotencyKey: String): JsonObject {
        requireUuid(attemptId); requireUuid(packageVersionId); requireUuid(idempotencyKey)
        require(explanationLanguage == "en" || explanationLanguage == "es")
        require(dailyConsentVersion == 1)
        val body = buildJsonObject {
            put("schemaVersion", 1); put("attemptId", attemptId); put("packageVersionId", packageVersionId)
            put("explanationLanguage", explanationLanguage); put("dailyConsentVersion", dailyConsentVersion)
        }
        return request("POST", endpoint("v1/daily-practice/attempts"), session, body, idempotencyKey)
    }

    suspend fun attempt(session: AccountSession, attemptId: String): JsonObject {
        requireUuid(attemptId)
        return request("GET", endpoint("v1/daily-practice/attempts/$attemptId"), session, null)
    }

    suspend fun attemptPackage(session: AccountSession, attemptId: String): DailyPracticePackage {
        requireUuid(attemptId)
        val response = request("GET", endpoint("v1/daily-practice/attempts/$attemptId/package"), session, null)
        return try { DailyPracticeCodec.decodePackage(response.toString()) }
        catch (_: Exception) { throw DailyPracticeFailure.InvalidResponse }
    }

    suspend fun appendEvent(session: AccountSession, attemptId: String, envelope: JsonObject): JsonObject {
        requireUuid(attemptId)
        return request("POST", endpoint("v1/daily-practice/attempts/$attemptId/events"), session, envelope)
    }

    suspend fun review(session: AccountSession, attemptId: String): JsonObject {
        requireUuid(attemptId)
        return request("GET", endpoint("v1/daily-practice/attempts/$attemptId/review"), session, null)
    }

    private fun endpoint(path: String) = origin.newBuilder().addPathSegments(path).build()

    private suspend fun request(method: String, url: HttpUrl, session: AccountSession?, body: JsonObject?, idempotencyKey: String? = null): JsonObject =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(url).header("Accept", "application/json")
                .header("Cache-Control", "no-store")
                .apply {
                    session?.let { header("Authorization", "Bearer ${it.accessToken}") }
                    idempotencyKey?.let { header("Idempotency-Key", it) }
                }
                .method(method, body?.toString()?.toRequestBody(JSON) ?: if (method == "GET") null else EMPTY_BODY)
                .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (!continuation.isActive) return
                    val failure = if (method == "GET") DailyPracticeFailure.Unavailable else DailyPracticeFailure.OutcomeUnknown
                    continuation.resumeWithException(failure)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val bytes = readBounded(it)
                            val parsed = try { json.parseToJsonElement(bytes.decodeToString()).jsonObject } catch (_: Exception) { null }
                            if (!it.isSuccessful) {
                                if (method != "GET" && it.code >= 500) throw DailyPracticeFailure.OutcomeUnknown
                                throw DailyPracticeFailure.Http(it.code, safeErrorCode(parsed))
                            }
                            parsed ?: throw DailyPracticeFailure.InvalidResponse
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (failure: Exception) {
                        val mapped = when {
                            failure is DailyPracticeFailure -> failure
                            failure is IOException && method != "GET" -> DailyPracticeFailure.OutcomeUnknown
                            else -> DailyPracticeFailure.InvalidResponse
                        }
                        if (continuation.isActive) continuation.resumeWithException(mapped)
                    }
                }
            })
        }

    private fun safeErrorCode(body: JsonObject?): String? {
        val error = body?.get("error")
        val value = (error as? JsonObject)?.get("code") as? JsonPrimitive
            ?: error as? JsonPrimitive
        return value?.content?.takeIf { it.length <= 80 && it.matches(Regex("^[a-z_]+$")) }
    }

    private fun readBounded(response: Response): ByteArray {
        val body = response.body ?: throw DailyPracticeFailure.InvalidResponse
        if (body.contentLength() > MAX_RESPONSE_BYTES) throw DailyPracticeFailure.InvalidResponse
        val output = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_RESPONSE_BYTES) throw DailyPracticeFailure.InvalidResponse
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun requireUuid(value: String) = require(UUID_PATTERN.matches(value)) { "invalid identifier" }

    private companion object {
        val JSON = "application/json".toMediaType()
        val EMPTY_BODY = ByteArray(0).toRequestBody(JSON)
        val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
        const val MAX_RESPONSE_BYTES = 128 * 1024L
    }
}
