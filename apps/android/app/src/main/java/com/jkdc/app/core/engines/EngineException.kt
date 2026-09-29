package com.jkdc.app.core.engines

import java.io.IOException

/** Failure raised by conversation engine adapters (never exposes key material). */
open class EngineException(message: String, val retryable: Boolean = false) : IOException(message)
