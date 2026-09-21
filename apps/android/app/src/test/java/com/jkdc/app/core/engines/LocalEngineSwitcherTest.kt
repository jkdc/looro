package com.jkdc.app.core.engines

import com.jkdc.app.network.APIResult
import com.jkdc.app.network.APIUsage
import com.jkdc.app.network.HelperPurpose
import com.jkdc.app.network.TeachingClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEngineSwitcherTest {
    private class FakeEngine(override val keyEnvVar: String?) : TeachingClient {
        var cancelled = false
        var calls = 0
        override fun cancelInFlight() { cancelled = true }
        override suspend fun respond(instructions: String, input: String, schema: JsonObject?,
                                     search: Boolean, purpose: HelperPurpose?): APIResult {
            calls++
            return APIResult("from-$keyEnvVar", emptyList(), APIUsage())
        }
    }

    private var selection = "glm"
    private val deepseek = FakeEngine("DEEPSEEK_API_KEY")
    private val glm = FakeEngine("ZHIPUAI_API_KEY")
    private val switcher = LocalEngineSwitcher(selected = { selection }, create = { id ->
        when (id) {
            "deepseek" -> deepseek
            "glm" -> glm
            else -> throw IllegalArgumentException(id)
        }
    })

    @Test fun `delegates to the selected engine and follows switches`() = runTest {
        assertEquals("ZHIPUAI_API_KEY", switcher.keyEnvVar)
        assertEquals("from-ZHIPUAI_API_KEY", switcher.respond("i", "in").text)
        selection = "deepseek"
        assertEquals("DEEPSEEK_API_KEY", switcher.keyEnvVar)
        assertEquals("from-DEEPSEEK_API_KEY", switcher.respond("i", "in").text)
        assertEquals(1, glm.calls)
        assertEquals(1, deepseek.calls)
    }

    @Test fun `cancelInFlight reaches every created engine`() = runTest {
        switcher.respond("i", "in")
        selection = "deepseek"
        switcher.respond("i", "in")
        switcher.cancelInFlight()
        assertTrue(deepseek.cancelled)
        assertTrue(glm.cancelled)
    }

    @Test fun `unknown selection surfaces no key instead of crashing the gate`() {
        selection = "nope"
        assertNull(switcher.keyEnvVar)
    }

    @Test fun `key env vars map to their engine ids`() {
        assertEquals("deepseek", localEngineIdForKeyEnv("DEEPSEEK_API_KEY"))
        assertEquals("glm", localEngineIdForKeyEnv("ZHIPUAI_API_KEY"))
        assertNull(localEngineIdForKeyEnv("OPENAI_API_KEY"))
        assertEquals("DEEPSEEK_API_KEY", localEngineKeyEnv("deepseek"))
        assertNull(localEngineKeyEnv("openai"))
    }
}

class EngineOverridesStoreValidationTest {
    @Test fun `normalizes and rejects blank whitespace or oversized keys`() {
        assertEquals("abc", EngineOverridesStore.normalizeKey("  abc  "))
        assertNull(EngineOverridesStore.normalizeKey("   "))
        assertNull(EngineOverridesStore.normalizeKey("a b"))
        assertNull(EngineOverridesStore.normalizeKey("x".repeat(513)))
        assertEquals("x".repeat(512), EngineOverridesStore.normalizeKey("x".repeat(512)))
    }
}
