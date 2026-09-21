package com.jkdc.looro.core.voice

/**
 * Energy VAD with a noise-floor tracker and hysteresis, replacing the fixed
 * RMS threshold of the on-device pipeline. The floor follows quiet frames
 * (only quiet frames, so sustained speech never raises it), and the speech
 * thresholds ride on it: opening a turn needs a clearly-above-the-room level,
 * keeping a turn open needs less — soft trailing syllables are not cut off.
 *
 * Single-threaded by contract: only the listen loop calls [observe].
 */
class AdaptiveVad(private val baseRms: Float = BASE) {
    var floor: Float = baseRms
        private set

    val startThreshold: Float get() = maxOf(baseRms, floor * START_FACTOR)
    val continueThreshold: Float get() = maxOf(baseRms * CONTINUE_BASE_FACTOR, floor * CONTINUE_FACTOR)

    /**
     * Feeds one frame; returns whether it counts as speech given whether the
     * turn is already open ([inSpeech]). Quiet frames pull the floor toward
     * their level so a room change re-arms within a few seconds.
     */
    fun observe(rms: Float, inSpeech: Boolean): Boolean {
        val speech = if (inSpeech) rms >= continueThreshold else rms >= startThreshold
        if (!speech) floor += (rms - floor) * FLOOR_ALPHA
        return speech
    }

    companion object {
        /** Quiet-room guard: the threshold never drops below this. */
        const val BASE = 0.012f

        /** Opening a turn demands clearly-above-the-room energy. */
        const val START_FACTOR = 3f

        /** Keeping a turn open tolerates trailing soft speech. */
        const val CONTINUE_BASE_FACTOR = 0.5f
        const val CONTINUE_FACTOR = 1.8f

        /** Per-frame floor smoothing (~6 s time constant at 128 ms frames). */
        const val FLOOR_ALPHA = 0.02f
    }
}
