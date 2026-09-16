package chat.mural

import chat.mural.core.engines.ConversationEngineFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConversationEngineFactoryTest {

    @Test
    fun `normalizes and validates known engine ids`() {
        assertEquals("openai", ConversationEngineFactory.validate("OpenAI"))
        assertEquals("local", ConversationEngineFactory.validate(" local "))
        assertEquals("deepseek", ConversationEngineFactory.validate("DeepSeek"))
        assertEquals("glm", ConversationEngineFactory.validate("GLM"))
    }

    @Test
    fun `unknown engine id fails listing the valid ones`() {
        try {
            ConversationEngineFactory.validate("ollama")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ollama"))
            assertTrue(e.message!!.contains("openai"))
            assertTrue(e.message!!.contains("local"))
            assertTrue(e.message!!.contains("deepseek"))
            assertTrue(e.message!!.contains("glm"))
        }
    }

    @Test
    fun `registry covers openai and both compatible providers`() {
        val ids = ConversationEngineFactory.available.map { it.id }
        assertTrue("openai" in ids)
        assertTrue("local" in ids)
        assertTrue("deepseek" in ids)
        assertTrue("glm" in ids)
    }
}
