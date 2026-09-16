package chat.mural.engines.local

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs

/** One captured turn: mono 16 kHz float32 samples, silence-trimmed. */
data class RecordedAudio(val samples: FloatArray, val durationMs: Long)

/** Frame source seam so pipelines are testable without hardware. */
interface AudioFrameSource {
    fun start(): Boolean
    fun readFrame(): FloatArray?
    fun stop()
}

/**
 * Microphone capture for the local voice pipeline: mono 16 kHz PCM16 from
 * VOICE_RECOGNITION. The pipeline reads [readFrame] on its own loop and calls
 * [stop] when the turn ends; the mic is released immediately.
 */
class MicRecorder : AudioFrameSource {

    private var audioRecord: AudioRecord? = null

    @SuppressLint("MissingPermission") // gated by the app's permission flow
    override fun start(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) return false
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                maxOf(minBuffer, READ_CHUNK * 4),
            )
        } catch (_: SecurityException) {
            return false
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        audioRecord = record
        record.startRecording()
        return true
    }

    /** Non-blocking read; returns null when the recorder is unavailable. */
    override fun readFrame(): FloatArray? {
        val record = audioRecord ?: return null
        val buffer = ShortArray(READ_CHUNK)
        val read = runCatching { record.read(buffer, 0, READ_CHUNK) }.getOrDefault(0)
        if (read <= 0) return null
        return FloatArray(read) { buffer[it] / 32768.0f }
    }

    override fun stop() {
        val record = audioRecord ?: return
        audioRecord = null
        runCatching {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
        }
        record.release()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val READ_CHUNK = 2048 // shorts (~128 ms)

        fun rms(samples: FloatArray): Float {
            if (samples.isEmpty()) return 0f
            var sum = 0.0
            for (s in samples) sum += s.toDouble() * s
            return kotlin.math.sqrt(sum / samples.size).toFloat()
        }

        fun trimSilence(samples: FloatArray, threshold: Float = 0.008f, marginSamples: Int = 80 * SAMPLE_RATE / 1000): FloatArray {
            val first = samples.indexOfFirst { abs(it) > threshold }
            if (first < 0) return samples
            val last = samples.indexOfLast { abs(it) > threshold }
            return samples.copyOfRange(
                maxOf(0, first - marginSamples),
                minOf(samples.size, last + 1 + marginSamples),
            )
        }
    }
}
