package com.jkdc.app.core.engines

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the release/debug execution policy as a pure, parametrized decision
 * (audit rev.3: the release branch must be testable without a release
 * build). */
class EngineSelectionPolicyTest {

    @Test
    fun `release normalizes every BYOK build default to the api provider`() {
        assertEquals("api", EngineSelectionPolicy.defaultEngine("openai", debug = false))
        assertEquals("api", EngineSelectionPolicy.defaultEngine("glm", debug = false))
        assertEquals("api", EngineSelectionPolicy.defaultEngine("opencode", debug = false))
        assertEquals("api", EngineSelectionPolicy.defaultEngine("deepseek", debug = false))
    }

    @Test
    fun `release keeps proxy-backed build defaults and debug keeps everything`() {
        assertEquals("api", EngineSelectionPolicy.defaultEngine("api", debug = false))
        assertEquals("hosted-proxy", EngineSelectionPolicy.defaultEngine("hosted-proxy", debug = false))
        assertEquals("glm", EngineSelectionPolicy.defaultEngine("glm", debug = true))
        assertEquals("openai", EngineSelectionPolicy.defaultEngine("openai", debug = true))
    }

    @Test
    fun `a persisted BYOK selection resolves back to the release default`() {
        assertEquals("api", EngineSelectionPolicy.effectiveSelection("glm", defaultId = "api", debug = false))
        assertEquals("api", EngineSelectionPolicy.effectiveSelection("opencode", defaultId = "api", debug = false))
        // Proxy-backed selections survive, and debug keeps the persisted id.
        assertEquals("hosted-proxy", EngineSelectionPolicy.effectiveSelection("hosted-proxy", defaultId = "api", debug = false))
        assertEquals("api", EngineSelectionPolicy.effectiveSelection("api", defaultId = "api", debug = false))
        assertEquals("glm", EngineSelectionPolicy.effectiveSelection("glm", defaultId = "glm", debug = true))
    }

    @Test
    fun `the selector may only persist proxy-backed engines on release`() {
        assertFalse(EngineSelectionPolicy.selectable("glm", debug = false))
        assertFalse(EngineSelectionPolicy.selectable("opencode", debug = false))
        assertFalse(EngineSelectionPolicy.selectable("deepseek", debug = false))
        assertTrue(EngineSelectionPolicy.selectable("api", debug = false))
        assertTrue(EngineSelectionPolicy.selectable("hosted-proxy", debug = false))
        assertTrue(EngineSelectionPolicy.selectable("glm", debug = true))
    }
}
