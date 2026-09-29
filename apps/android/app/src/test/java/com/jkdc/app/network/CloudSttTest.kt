package com.jkdc.app.network

import com.jkdc.app.engines.local.SttResult
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CloudSttTest {
    private val canned = Interceptor { chain ->
        // Answer WITHOUT hitting the network (proceed would call the real API).
        okhttp3.Response.Builder()
            .request(chain.request())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200).message("OK")
            .body("""{"text": "Hola, ¿cómo estás?"}""".toResponseBody("application/json".toMediaType()))
            .build()
    }

    private fun client() = OkHttpClient.Builder().addInterceptor(canned).build()

    @Test fun `wav header carries pcm16 mono 16k fields`() {
        val bytes = WavEncoder.encode(floatArrayOf(0f, 0.5f, -1f))
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals(16, readInt(bytes, 16))            // PCM
        assertEquals(1, readShort(bytes, 20))           // mono
        assertEquals(16_000, readInt(bytes, 24))        // sample rate
        assertEquals(16, readShort(bytes, 34))          // bits
        assertEquals(6, readInt(bytes, 40))             // 3 samples × 2 bytes
        assertEquals(6, bytes.size - 44)
        assertEquals(0, readShort(bytes, 44))           // 0f → 0
        assertEquals(16384, readShort(bytes, 46))       // 0.5 → rounds to half scale
        assertEquals(-32767, readShort(bytes, 48))      // -1f → full scale (symmetric)
    }

    @Test fun `transcribe posts multipart with key model and language and parses text`() = runBlocking {
        var captured: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            okhttp3.Response.Builder()
                .request(chain.request())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"text": "Hola, ¿cómo estás?"}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val stt = CloudStt(apiKey = { "sk-test" }, client = client)
        val result = stt.transcribe(FloatArray(16_000), "es")
        assertEquals("Hola, ¿cómo estás?", result.text)
        assertTrue(result.processingMs >= 0)
        val request = captured!!
        assertEquals("Bearer sk-test", request.header("Authorization"))
        val multipart = request.body as MultipartBody
        fun partValue(name: String): String? {
            val part = multipart.parts.firstOrNull { part ->
                part.headers.toString().contains("name=\"$name\"")
            } ?: return null
            val buffer = okio.Buffer()
            part.body.writeTo(buffer)
            return buffer.readUtf8()
        }
        fun partFilename(name: String): String? {
            val part = multipart.parts.firstOrNull { part ->
                part.headers.toString().contains("name=\"$name\"")
            } ?: return null
            val disposition = part.headers?.get("Content-Disposition") ?: return null
            return Regex("filename=\"([^\"]+)\"").find(disposition)?.groupValues?.get(1)
        }
        assertEquals("whisper-1", partValue("model"))
        assertEquals("es", partValue("language"))
        assertTrue(partValue("file")!!.startsWith("RIFF"))
        assertEquals("turn.wav", partFilename("file"))
    }

    @Test fun `missing key fails before any request`() = runBlocking {
        val stt = CloudStt(apiKey = { null }, client = client())
        try {
            stt.transcribe(FloatArray(16_000), "es"); fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("no API key"))
        }
    }

    @Test fun `parse text tolerates unknown fields`() {
        assertEquals("hola", CloudStt.parseText("""{"id":1,"text":"hola","extra":[1,2]}"""))
        assertNull(CloudStt.parseText("""{"id":1}"""))
        assertNull(CloudStt.parseText("not json"))
    }

    private fun readInt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun readShort(bytes: ByteArray, at: Int): Int {
        val unsigned = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        return if (unsigned >= 0x8000) unsigned - 0x10000 else unsigned
    }
}
