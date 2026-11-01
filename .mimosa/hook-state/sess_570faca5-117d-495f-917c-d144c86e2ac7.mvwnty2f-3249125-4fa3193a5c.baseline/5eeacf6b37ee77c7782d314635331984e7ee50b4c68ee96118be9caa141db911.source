package com.jkdc.app.core.engines

import com.jkdc.app.core.TokenProxyBlock
import java.io.IOException

/** Failure raised by conversation engine adapters (never exposes key material).
    [proxyBlock] tags the token-proxy rejections (402/403) so the UI can open
    the matching commerce surface instead of a raw failure dialog. */
open class EngineException(message: String, val retryable: Boolean = false,
    val proxyBlock: TokenProxyBlock? = null) : IOException(message)
