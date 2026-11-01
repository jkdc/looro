package com.jkdc.app.network

import com.jkdc.app.BuildConfig
import okhttp3.HttpUrl

/** Local development only (DEBUG builds): the Android emulator reaches an API
    running in host Docker at http://10.0.2.2:<port>, which the production
    hardening below forbids. Release builds never accept anything but the
    pinned https origin rules, regardless of the gradle property value. */
internal fun HttpUrl?.isLocalDebugOrigin(): Boolean {
    if (this == null || !BuildConfig.DEBUG) return false
    if (scheme != "http" || username.isNotEmpty() || password.isNotEmpty() ||
        encodedPath != "/" || query != null || fragment != null) return false
    // Loopback: the emulator's alias for the host plus local names.
    if (host in setOf("10.0.2.2", "localhost", "127.0.0.1")) return true
    // A physical phone on the same WiFi: any private (RFC 1918) address,
    // e.g. the development machine's LAN IP. Never routable from the internet.
    val octets = host.split('.').mapNotNull { it.toIntOrNull() }
    return octets.size == 4 && octets.all { it in 0..255 } && (
        octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) ||
        (octets[0] == 192 && octets[1] == 168))
}
