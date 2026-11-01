package com.jkdc.app

import com.jkdc.app.core.engines.ApiKeysStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ApiKeysStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `reads key from properties file`() {
        val f = tmp.newFile("api_keys.properties")
        f.writeText("ZHIPUAI_API_KEY=abc123\nDEEPSEEK_API_KEY= xyz456\n")
        assertEquals("abc123", ApiKeysStore.readFromFile(f, "ZHIPUAI_API_KEY"))
        assertEquals("xyz456", ApiKeysStore.readFromFile(f, "DEEPSEEK_API_KEY"))
    }

    @Test
    fun `missing file or key returns null`() {
        assertNull(ApiKeysStore.readFromFile(File(tmp.root, "nope.properties"), "A"))
        val f = tmp.newFile("keys.properties")
        f.writeText("OTHER=1\n")
        assertNull(ApiKeysStore.readFromFile(f, "ZHIPUAI_API_KEY"))
    }

    @Test
    fun `blank values are treated as missing`() {
        val f = tmp.newFile("keys.properties")
        f.writeText("ZHIPUAI_API_KEY=   \n")
        assertNull(ApiKeysStore.readFromFile(f, "ZHIPUAI_API_KEY"))
    }
}
