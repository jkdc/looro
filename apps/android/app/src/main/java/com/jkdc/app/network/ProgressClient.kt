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

/** Ships progress rollups to the fixed managed origin and pulls the merged ledger
    back. Failures are ordinary values (booleans/null): sync must never surface as
    a user-facing error. */
class ProgressClient internal constructor(private val origin: HttpUrl, transport: OkHttpClient) {
    constructor(config: ManagedAccountConfiguration) : this(config.origin, OkHttpClient())
    private val client = transport.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).cache(null).authenticator(Authenticator.NONE)
        .retryOnConnectionFailure(false).callTimeout(25, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun send(session: AccountSession, batch: String): Boolean {
        val request = Request.Builder().url(origin.newBuilder().addPathSegments("v1/progress").build())
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
                        val merged = it.isSuccessful && try {
                            val value = json.parseToJsonElement(it.body?.string() ?: "").jsonObject["merged"]
                            value is JsonPrimitive && !value.isString && value.content.toIntOrNull() != null
                        } catch (_: Exception) { false }
                        if (continuation.isActive) continuation.resume(merged)
                    }
                }
            })
        }
    }

    suspend fun fetch(session: AccountSession): String? {
        val request = Request.Builder().url(origin.newBuilder().addPathSegments("v1/progress").build())
            .header("Accept", "application/json").header("Cache-Control", "no-store")
            .header("Authorization", "Bearer ${session.accessToken}").get().build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(null)
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = if (it.isSuccessful) it.body?.string() else null
                        if (continuation.isActive) continuation.resume(body)
                    }
                }
            })
        }
    }
}
