package com.jkdc.app.network

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal enum class RemoteLevelTestFailure { NOT_FOUND, TIMEOUT, NETWORK, INVALID_RESPONSE, HTTP_ERROR }

internal sealed interface RemoteLevelTestResponse {
    data class Updated(val body: String, val etag: String?, val maxAgeSeconds: Long?) : RemoteLevelTestResponse
    data class NotModified(val maxAgeSeconds: Long?) : RemoteLevelTestResponse
    data class Failed(val reason: RemoteLevelTestFailure) : RemoteLevelTestResponse
}

/** Public content-only client. It deliberately has no account, cookie, or redirect behavior. */
internal class LevelTestRemoteClient(
    private val origin: HttpUrl,
    transport: OkHttpClient = OkHttpClient(),
    timeoutMillis: Long = 5_000,
) {
    private val client = transport.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .authenticator(Authenticator.NONE)
        .retryOnConnectionFailure(false)
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    suspend fun fetch(language: String, level: String, weekID: Long, variant: Int, etag: String?): RemoteLevelTestResponse {
        if (variant !in 1..5) return RemoteLevelTestResponse.Failed(RemoteLevelTestFailure.INVALID_RESPONSE)
        val url = origin.newBuilder().addPathSegments("v1/level-tests/hybrid")
            .addQueryParameter("language", language)
            .addQueryParameter("level", level)
            .addQueryParameter("variant", variant.toString())
            .build()
        val builder = Request.Builder().url(url).header("Accept", "application/json")
            .header("Cache-Control", "no-cache").get()
        etag?.takeIf { it.length <= 512 && it.isNotBlank() }?.let { builder.header("If-None-Match", it) }
        val request = builder.build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (!continuation.isActive) return
                    val failure = if (error is InterruptedIOException)
                        RemoteLevelTestFailure.TIMEOUT else RemoteLevelTestFailure.NETWORK
                    continuation.resume(RemoteLevelTestResponse.Failed(failure))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use { received ->
                        val maxAge = maxAgeSeconds(received.header("Cache-Control"))
                        when {
                            received.code == 304 -> RemoteLevelTestResponse.NotModified(maxAge)
                            received.code == 404 -> RemoteLevelTestResponse.Failed(RemoteLevelTestFailure.NOT_FOUND)
                            received.code != 200 -> RemoteLevelTestResponse.Failed(RemoteLevelTestFailure.HTTP_ERROR)
                            else -> try {
                                val bytes = received.body?.byteStream()?.use(::readBounded) ?: throw IOException()
                                if (bytes.size > MAX_BODY_BYTES) RemoteLevelTestResponse.Failed(RemoteLevelTestFailure.INVALID_RESPONSE)
                                else RemoteLevelTestResponse.Updated(bytes.toString(Charsets.UTF_8), received.header("ETag"), maxAge)
                            } catch (error: IOException) {
                                val reason = if (error is InterruptedIOException) RemoteLevelTestFailure.TIMEOUT else RemoteLevelTestFailure.INVALID_RESPONSE
                                RemoteLevelTestResponse.Failed(reason)
                            }
                        }
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_BODY_BYTES) return ByteArray(MAX_BODY_BYTES + 1)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun maxAgeSeconds(header: String?): Long? = header?.split(',')
        ?.map(String::trim)
        ?.firstOrNull { it.startsWith("max-age=", ignoreCase = true) }
        ?.substringAfter('=')?.trim()?.trim('"')?.toLongOrNull()?.coerceIn(0, 86_400)

    companion object { const val MAX_BODY_BYTES = 1_048_576 }
}

internal data class CachedLevelTestSnapshot(
    val language: String,
    val level: String,
    val variant: Int,
    val body: String,
    val etag: String?,
    val expiresAtMillis: Long,
)

/** Disposable public catalog cache; attempt snapshots live in the durable exam store, never here. */
internal interface LevelTestCatalogSnapshotStore {
    fun read(language: String, level: String, variant: Int): CachedLevelTestSnapshot?
    fun write(entry: CachedLevelTestSnapshot): Boolean
}

internal class LevelTestCatalogCache(private val directory: File) : LevelTestCatalogSnapshotStore {
    private val json = Json { ignoreUnknownKeys = true }

    override fun read(language: String, level: String, variant: Int): CachedLevelTestSnapshot? {
        val file = cacheFile(language, level, variant)
        if (!file.isFile || file.length() > LevelTestRemoteClient.MAX_BODY_BYTES + 4_096) return null
        return try {
            val obj = json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
            val storedLanguage = obj.string("language") ?: return null
            val storedLevel = obj.string("level") ?: return null
            val storedVariant = obj.number("variant")?.toIntOrNull() ?: return null
            val body = obj.string("body") ?: return null
            val expiry = obj.number("expiresAtMillis")?.toLongOrNull() ?: return null
            val etag = obj.string("etag")
            if (storedLanguage != language || storedLevel != level || storedVariant != variant ||
                body.toByteArray(Charsets.UTF_8).size > LevelTestRemoteClient.MAX_BODY_BYTES || (etag?.length ?: 0) > 512) null
            else CachedLevelTestSnapshot(language, level, variant, body, etag, expiry)
        } catch (_: Exception) { null }
    }

    override fun write(entry: CachedLevelTestSnapshot): Boolean {
        if (entry.variant !in 1..5 || entry.body.toByteArray(Charsets.UTF_8).size > LevelTestRemoteClient.MAX_BODY_BYTES ||
            (entry.etag?.length ?: 0) > 512) return false
        return try {
            directory.mkdirs()
            val file = cacheFile(entry.language, entry.level, entry.variant)
            val temporary = File(directory, file.name + ".tmp")
            val encoded = buildJsonObject {
                put("language", entry.language); put("level", entry.level); put("variant", entry.variant)
                put("body", entry.body); put("expiresAtMillis", entry.expiresAtMillis)
                entry.etag?.let { put("etag", it) }
            }.toString()
            temporary.outputStream().use { stream ->
                stream.write(encoded.toByteArray(Charsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
            try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            true
        } catch (_: Exception) { false }
    }

    private fun cacheFile(language: String, level: String, variant: Int): File {
        val rawKey = "$language|$level|$variant".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(rawKey).joinToString("") { "%02x".format(it) }
        return File(directory, "level-test-$digest.json")
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.number(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull
}
