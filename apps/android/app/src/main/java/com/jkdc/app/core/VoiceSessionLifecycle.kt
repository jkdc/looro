package com.jkdc.app.core

/**
 * Pure policy for the talk-session pause/resume/restart flow. The ViewModel
 * owns the transport and mutable state; this stays JVM-testable.
 *
 * States: "idle" | "connecting" | "active" | "closing" | "paused" | "ended" |
 * "failed". Pausing closes the transport but keeps the conversation record so
 * it can be resumed (reconnected with its history) or restarted from zero.
 */
object VoiceSessionLifecycle {
    /** Leaving the Talk screen pauses running voice sessions (typed ones have nothing playing). */
    fun shouldPauseOnLeave(state: String, isVoiceSession: Boolean): Boolean =
        isVoiceSession && isRunningState(state)

    /** Terminal state written after the transport settles: paused keeps the conversation alive. */
    fun stateAfterClose(pausedByUser: Boolean): String = if (pausedByUser) "paused" else "ended"

    /** Transport work in flight; selectors disable and leave-pauses apply only in these states. */
    fun isRunningState(state: String): Boolean = state in listOf("connecting", "active", "closing")

    /** Resume reattaches the transport to the kept conversation; nothing else resumes. */
    fun canResume(state: String, hasSession: Boolean): Boolean = state == "paused" && hasSession

    /** Restart clears the pause (or idle) before a fresh voice start. */
    fun stateBeforeFreshStart(state: String): String = "idle"

    /** Typed replies continue the conversation while it is active or paused. */
    fun acceptsTyped(state: String): Boolean = state == "active" || state == "paused"
}
