package com.jkdc.app.network

import com.jkdc.app.BuildConfig
import okhttp3.HttpUrl

/** Local development only (DEBUG builds): the Android emulator reaches an API
    running in host Docker at http://10.0.2.2:<port>, which the production
    hardening below forbids. Release builds never accept anything but the
    pinned https origin rules, regardless of the gradle property value. */
internal fun HttpUrl?.isLocalDebugOrigin(): Boolean {
    if (this == null || !BuildConfig.DEBUG) return false
    return scheme == "http" && username.isEmpty() && password.isEmpty() &&
        encodedPath == "/" && query == null && fragment == null &&
        host in setOf("10.0.2.2", "localhost", "127.0.0.1")
}
