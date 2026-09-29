package com.jkdc.app.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Cloud speech-to-text through any OpenAI-compatible transcription endpoint
 *  (POST multipart, `{"text": "…"}` response). Falls back to the on-device
 *  whisper pass at the call site when the key is missing or the request fails.
 *  The endpoint/model/key are injected so providers stay one config row. */
class CloudStt(
    private val apiKey: () -> String?,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val model: String = DEFAULT_MODEL,
    private val client: OkHttpClient = defaultClient(),
) {

    suspend fun transcribe(samples: FloatArray, language: String): com.jkdc.app.engines.local.SttResult {
        val started = System.currentTimeMillis()
        val key = apiKey() ?: throw IOException("cloud STT: no API key")
        val wav = WavEncoder.encode(samples)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "turn.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("language", language.take(2))
            .build()
        val request = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .post(body).build()
        val text = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException("cloud STT http ${response.code}: ${payload.take(160)}")
                parseText(payload) ?: throw IOException("cloud STT: malformed response: ${payload.take(160)}")
            }
        }
        return com.jkdc.app.engines.local.SttResult(
            text = text.trim(),
            processingMs = System.currentTimeMillis() - started,
        )
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"
        const val DEFAULT_MODEL = "whisper-1"

        /** A hung provider must not stall the turn past the 18 s STT window the
            local pass would have used; the caller falls back to local on throw. */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(java.time.Duration.ofSeconds(20))
            .build()

        /** `{"text": "…"}` with a tolerant string extraction for provider quirks. */
        private val lenientJson: Json = Json { ignoreUnknownKeys = true }

        fun parseText(payload: String): String? = runCatching {
            lenientJson.parseToJsonElement(payload).jsonObject["text"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }
}

/** Encodes 16 kHz mono float samples (-1..1) as a minimal 16-bit PCM WAV, the
 *  format every OpenAI-compatible STT endpoint accepts. */
object WavEncoder {
    fun encode(samples: FloatArray): ByteArray {
        val out = ByteArrayOutputStream(44 + samples.size * 2)
        val dataLen = samples.size * 2
        out.write("RIFF".toByteArray())
        writeInt(out, 36 + dataLen)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        writeInt(out, 16)                      // PCM chunk size
        out.write(shortOf(1))                  // PCM format
        out.write(shortOf(1))                  // mono
        writeInt(out, 16_000)                  // sample rate
        writeInt(out, 32_000)                  // byte rate = rate * channels * 2
        out.write(shortOf(2))                  // block align
        out.write(shortOf(16))                 // bits per sample
        out.write("data".toByteArray())
        writeInt(out, dataLen)
        for (sample in samples) {
            val clamped = sample.coerceIn(-1f, 1f)
            writeShort(out, kotlin.math.round(clamped * Short.MAX_VALUE).toInt())
        }
        return out.toByteArray()
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write(value); out.write(value ushr 8); out.write(value ushr 16); out.write(value ushr 24)
    }

    private fun writeShort(out: ByteArrayOutputStream, value: Int) {
        out.write(value); out.write(value ushr 8)
    }

    private fun shortOf(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte(),
    )
}
