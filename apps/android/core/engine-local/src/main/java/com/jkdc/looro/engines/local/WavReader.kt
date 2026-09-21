package com.jkdc.looro.engines.local

import java.io.RandomAccessFile

/** Minimal PCM WAV reader + linear resampler (POC port; used by STT tests). */
object WavReader {

    data class Pcm(val samples: FloatArray, val sampleRate: Int)

    fun read16bitMono(file: java.io.File): Pcm = RandomAccessFile(file, "r").use { raf ->
        val riff = ByteArray(12)
        raf.readFully(riff)
        check(String(riff, 0, 4) == "RIFF" && String(riff, 8, 4) == "WAVE") { "Not a WAV file" }

        var sampleRate = 16_000
        var channels = 1
        var data: ShortArray? = null

        while (data == null) {
            val header = ByteArray(8)
            if (raf.read(header) < 8) break
            val chunkId = String(header, 0, 4)
            val chunkSize = (header[4].toInt() and 0xFF) or ((header[5].toInt() and 0xFF) shl 8) or
                ((header[6].toInt() and 0xFF) shl 16) or ((header[7].toInt() and 0xFF) shl 24)
            when (chunkId) {
                "fmt " -> {
                    val fmt = ByteArray(chunkSize)
                    raf.readFully(fmt)
                    channels = (fmt[2].toInt() and 0xFF) or ((fmt[3].toInt() and 0xFF) shl 8)
                    sampleRate = (fmt[4].toInt() and 0xFF) or ((fmt[5].toInt() and 0xFF) shl 8) or
                        ((fmt[6].toInt() and 0xFF) shl 16) or ((fmt[7].toInt() and 0xFF) shl 24)
                }
                "data" -> {
                    val bytes = ByteArray(chunkSize)
                    raf.readFully(bytes)
                    val count = bytes.size / 2
                    val shorts = ShortArray(count)
                    for (i in 0 until count) {
                        shorts[i] = ((bytes[2 * i].toInt() and 0xFF) or
                            ((bytes[2 * i + 1].toInt() and 0xFF) shl 8)).toShort()
                    }
                    data = shorts
                }
                else -> raf.seek(raf.filePointer + chunkSize + (chunkSize and 1))
            }
        }

        val pcm = data ?: error("No data chunk in WAV")
        val mono = if (channels == 1) {
            FloatArray(pcm.size) { pcm[it] / 32768.0f }
        } else {
            FloatArray(pcm.size / channels) { i ->
                var sum = 0f
                for (c in 0 until channels) sum += pcm[i * channels + c] / 32768.0f
                sum / channels
            }
        }
        Pcm(samples = mono, sampleRate = sampleRate)
    }

    /** Linear resample (POC-grade) to [targetRate]. */
    fun resample(samples: FloatArray, fromRate: Int, targetRate: Int = MicRecorder.SAMPLE_RATE): FloatArray {
        if (fromRate == targetRate || samples.isEmpty()) return samples
        val ratio = fromRate.toDouble() / targetRate
        val outLen = (samples.size / ratio).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in out.indices) {
            val pos = i * ratio
            val i0 = pos.toInt()
            val i1 = (i0 + 1).coerceAtMost(samples.size - 1)
            val frac = pos - i0
            out[i] = (samples[i0] * (1 - frac) + samples[i1] * frac).toFloat()
        }
        return out
    }
}
