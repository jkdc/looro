package com.jkdc.app.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.jkdc.app.core.AccountSession
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Ships content-free usage batches to the fixed managed origin. Failures are
    ordinary booleans: telemetry must never surface as a user-facing error. */
class UsageClient internal constructor(private val origin: HttpUrl, transport: OkHttpClient) {
    constructor(config: ManagedAccountConfiguration) : this(config.origin, OkHttpClient())
    private val client = transport.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).cache(null).authenticator(Authenticator.NONE)
        .retryOnConnectionFailure(false).callTimeout(25, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun send(session: AccountSession, batch: String): Boolean {
        val request = Request.Builder().url(origin.newBuilder().addPathSegments("v1/usage/events").build())
            .header("Accept", "application/json").header("Cache-Control", "no-store")
            .header("Authorization", "Bearer ${session.accessToken}")
            .post(batch.toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(false)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val accepted = it.isSuccessful && try {
                            val value = json.parseToJsonElement(it.body?.string() ?: "").jsonObject["accepted"]
                            value is JsonPrimitive && !value.isString && value.content.toIntOrNull() != null
                        } catch (_: Exception) { false }
                        if (continuation.isActive) continuation.resume(accepted)
                    }
                }
            })
        }
    }
}
