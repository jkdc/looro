package com.jkdc.app

import com.jkdc.app.core.engines.EngineModel
import com.jkdc.app.core.engines.EngineModelCatalog
import com.jkdc.app.core.engines.resolveEngineModelSelection
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EngineModelCatalogTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    /** OpenAI-style list payload; each argument is one entry of `data`. */
    private fun payload(vararg models: String) =
        """{"object":"list","data":[${models.joinToString()}]}"""

    @Test
    fun `parses the catalog and marks zero input and output pricing as free`() = runTest {
        server.enqueue(MockResponse().setBody(payload(
            """{"id":"glm-5.3","name":"GLM-5.3",
                "cost":{"text":{"input":{"price":1.4,"cache":{"read":0.26}},"output":{"price":4.4}}}}""",
            """{"id":"beam-free","name":"Beam Free",
                "cost":{"text":{"input":{"price":0},"output":{"price":0}}}}""",
        )))

        val models = EngineModelCatalog.fetchModels(server.url("/zen/go/v1/"), "test-key", OkHttpClient())

        assertEquals(listOf(
            EngineModel("glm-5.3", "GLM-5.3", isFree = false),
            EngineModel("beam-free", "Beam Free", isFree = true),
        ), models)
        val recorded = server.takeRequest()
        assertEquals("/zen/go/v1/models", recorded.path)
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
    }

    @Test
    fun `missing key sends no authorization header`() = runTest {
        server.enqueue(MockResponse().setBody(payload("""{"id":"m1","name":"M1"}""")))

        EngineModelCatalog.fetchModels(server.url("/zen/go/v1/"), null, OkHttpClient())

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `zero input price alone is not free`() {
        val models = EngineModelCatalog.parseModels(payload(
            """{"id":"half-free","name":"Half Free",
                "cost":{"text":{"input":{"price":0},"output":{"price":2.0}}}}""",
        ))!!
        assertEquals(false, models.single().isFree)
    }

    @Test
    fun `models without pricing are never marked free`() {
        val models = EngineModelCatalog.parseModels(payload(
            """{"id":"m1","name":"M1"}""",
            """{"id":"m2"}""",
        ))!!
        assertEquals(
            listOf(EngineModel("m1", "M1", isFree = false), EngineModel("m2", "m2", isFree = false)),
            models,
        )
    }

    @Test
    fun `http error returns null so callers fall back to the preset`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        assertNull(EngineModelCatalog.fetchModels(server.url("/zen/go/v1/"), "k", OkHttpClient()))
    }

    @Test
    fun `unreachable host returns null so callers fall back to the preset`() = runTest {
        // Nothing listens here.
        assertNull(EngineModelCatalog.fetchModels(server.url("/").newBuilder().port(1).build(), null, OkHttpClient()))
    }

    @Test
    fun `malformed payloads return null instead of an empty catalog`() {
        assertNull(EngineModelCatalog.parseModels("not json"))
        assertNull(EngineModelCatalog.parseModels("""{"unexpected":true}"""))
        assertNull(EngineModelCatalog.parseModels("""{"data":[]}"""))
        // An entry without an id is unusable: an all-unusable list is a failure, not an empty picker.
        assertNull(EngineModelCatalog.parseModels("""{"data":[{}]}"""))
    }

    @Test
    fun `fallback preset covers the models the server preset knows`() {
        val ids = EngineModelCatalog.OPENCODE_FALLBACK.map { it.id }
        assertTrue("glm-5.3-flash" in ids)
        assertTrue("glm-5.3" in ids)
        assertTrue("kimi-k3" in ids)
        assertTrue("deepseek-v4.1-flash" in ids)
        assertTrue("longcat-2.0" in ids)
    }

    @Test
    fun `stored model selection only survives while the model is still offered`() {
        assertEquals("glm-5.3",
            resolveEngineModelSelection("glm-5.3", listOf("glm-5.3", "beam-free"), "glm-5.3-flash"))
        // A catalog that dropped the stored model falls back to the default.
        assertEquals("glm-5.3-flash",
            resolveEngineModelSelection("gone", listOf("glm-5.3"), "glm-5.3-flash"))
        assertEquals("glm-5.3-flash",
            resolveEngineModelSelection(null, listOf("glm-5.3"), "glm-5.3-flash"))
        // Blank stored values never win.
        assertEquals("glm-5.3-flash",
            resolveEngineModelSelection("  ", listOf("glm-5.3"), "glm-5.3-flash"))
    }
}
