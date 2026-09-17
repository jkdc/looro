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
        assertEquals("deepseek", ConversationEngineFactory.validate(" deepseek "))
        assertEquals("glm", ConversationEngineFactory.validate("GLM"))
    }

    @Test
    fun `unknown engine id fails listing the valid ones`() {
        try {
            ConversationEngineFactory.validate("bogus")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("bogus"))
            assertTrue(e.message!!.contains("openai"))
            assertTrue(e.message!!.contains("deepseek"))
            assertTrue(e.message!!.contains("glm"))
        }
    }

    @Test
    fun `registry covers openai and both compatible providers`() {
        val ids = ConversationEngineFactory.available.map { it.id }
        assertTrue("openai" in ids)
        assertTrue("deepseek" in ids)
        assertTrue("glm" in ids)
    }

    @Test
    fun `removed local engine id is rejected`() {
        try {
            ConversationEngineFactory.validate("local")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("local"))
        }
    }
}
