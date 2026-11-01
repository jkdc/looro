package com.jkdc.app.engines.local

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

    /**
     * Non-blocking read; returns null when the source is unavailable or no
     * frame is buffered yet. Never waits for audio to arrive, and a null is
     * "nothing buffered right now", never "end of audio".
     */
    fun readFrame(): FloatArray?

    /** Terminal: releases the source. Anything still buffered is discarded. */
    fun stop()

    /**
     * Stops capture temporarily while keeping the source resumable: audio
     * captured up to this point stays buffered, [readFrame] returns null, and
     * [drainStale] can discard it. Lets a voice pipeline avoid re-capturing its
     * own TTS playback without ever releasing the hardware mid-read.
     */
    fun pause()

    /**
     * Discards the audio retained across [pause]. Bounded and non-blocking by
     * contract: implementations must account for the finite retained samples
     * instead of looping until [readFrame] runs dry (a live recording source
     * never runs dry on its own).
     */
    fun drainStale()

    /** Restarts capture after [pause]/[drainStale]. False when unavailable. */
    fun resume(): Boolean
}

/**
 * Microphone capture for the local voice pipeline: mono 16 kHz PCM16 from
 * VOICE_RECOGNITION. The pipeline reads [readFrame] (non-blocking) on its own
 * loop, [pause]s capture while TTS plays back — discarding the audio retained
 * across the pause via [drainStale], so speaker output is never re-captured —
 * and calls [stop] when the session ends; the mic is released immediately then.
 *
 * Thread-safety contract: [stop] may be invoked from the caller's thread while
 * the loop is inside [readFrame] (the transport's prompt session-end path).
 * Every capture-state method is serialized on [captureLock], so a stop can
 * never release the AudioRecord while a native read is in flight — the same
 * platform-dependent hazard [pause] avoids by never releasing. Contention is
 * bounded: each locked region performs only non-blocking audio work, so a
 * stopper waits out at most one in-flight read.
 */
class MicRecorder : AudioFrameSource {

    private var audioRecord: AudioRecord? = null

    /**
     * Serializes [stop]/[pause]/[resume]/[drainStale] against in-flight
     * [readFrame] native reads: release during a read is platform-dependent,
     * so the stopper waits out at most one non-blocking read instead of
     * racing it.
     */
    private val captureLock = Any()

    /** Short capacity of the live AudioRecord's buffer, as allocated in [start]; 0 when stopped. */
    private var bufferShortsCapacity = 0

    @SuppressLint("MissingPermission") // gated by the app's permission flow
    override fun start(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) return false
        val capacity = maxOf(minBuffer, READ_CHUNK * 4)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                capacity,
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
        bufferShortsCapacity = capacity
        record.startRecording()
        return true
    }

    /** Non-blocking read; returns null when no frame is buffered yet or the recorder is unavailable. */
    override fun readFrame(): FloatArray? = synchronized(captureLock) {
        val record = audioRecord ?: return null
        val buffer = ShortArray(READ_CHUNK)
        val read = runCatching { record.read(buffer, 0, READ_CHUNK, AudioRecord.READ_NON_BLOCKING) }.getOrDefault(0)
        if (read <= 0) return null
        FloatArray(read) { buffer[it] / 32768.0f }
    }

    override fun stop() {
        synchronized(captureLock) {
            val record = audioRecord ?: return
            audioRecord = null
            bufferShortsCapacity = 0
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            }
            record.release()
        }
    }

    /**
     * Stops capture but keeps this instance: never releases the AudioRecord
     * here, because release during an in-flight read is platform-dependent.
     * Samples captured before the pause stay buffered until [drainStale]
     * discards them. Idempotent.
     */
    override fun pause() {
        synchronized(captureLock) {
            val record = audioRecord ?: return
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            }
        }
    }

    /**
     * Bounded, non-blocking discard of the samples retained across [pause]:
     * at most the allocated buffer capacity can be retained, and each sweep
     * read is non-blocking, so this always terminates.
     */
    override fun drainStale() {
        synchronized(captureLock) {
            val record = audioRecord ?: return
            val chunk = ShortArray(READ_CHUNK)
            var remaining = bufferShortsCapacity // explicit accounting: nothing beyond this can be retained
            while (remaining > 0) {
                val read = runCatching { record.read(chunk, 0, READ_CHUNK, AudioRecord.READ_NON_BLOCKING) }.getOrDefault(0)
                if (read <= 0) break
                remaining -= read
            }
        }
    }

    /**
     * Restarts capture after [pause]/[drainStale]. Returns false when the
     * source was terminally stopped or the hardware refuses to record again.
     */
    override fun resume(): Boolean = synchronized(captureLock) {
        val record = audioRecord ?: return false
        if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) return true
        val restarted = runCatching { record.startRecording() }.isSuccess
        restarted && record.recordingState == AudioRecord.RECORDSTATE_RECORDING
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
