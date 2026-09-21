package com.jkdc.looro.core

object SessionLimits {
    fun endsForInactivity(voice: Boolean, idleSeconds: Double): Boolean = voice && idleSeconds > 120
}
