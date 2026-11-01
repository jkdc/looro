package com.jkdc.app.core.engines

/** Pure build-type policy for engine execution (audited A1/A2): one place
 * decides what a build may run. Parameterized by the build type so the JVM
 * suite can exercise the release branch without a release build.
 *
 * Release builds execute only the proxy-backed providers: the model, the
 * quota and the metering live behind the backend proxy, and BYOK engines are
 * a debug/benchmarking surface whose keys must never ship. */
object EngineSelectionPolicy {

    /** Engines a release build may execute. */
    val RELEASE_ALLOWED = setOf("api", "hosted-proxy")

    /** The build-time default, normalized per build type: a BYOK build
     * default (openai, glm, deepseek, opencode…) falls back to the
     * proxy-backed provider on release. Debug keeps the build choice. */
    fun defaultEngine(buildEngineId: String, debug: Boolean): String =
        if (debug || buildEngineId in RELEASE_ALLOWED) buildEngineId else "api"

    /** Whether an engine id may execute in this build type. */
    fun executable(engineId: String, debug: Boolean): Boolean =
        debug || engineId in RELEASE_ALLOWED

    /** A persisted selection normalized for this build type: a BYOK id saved
     * by a debug build resolves back to the release default. */
    fun effectiveSelection(requestedId: String, defaultId: String, debug: Boolean): String =
        if (executable(requestedId, debug)) requestedId else defaultId

    /** Whether the Settings selector may persist this id in this build. */
    fun selectable(engineId: String, debug: Boolean): Boolean = executable(engineId, debug)
}
