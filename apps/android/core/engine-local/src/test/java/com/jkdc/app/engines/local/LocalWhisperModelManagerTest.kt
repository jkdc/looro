package com.jkdc.app.engines.local

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalWhisperModelManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun manager() = LocalWhisperModelManager(tmp.root)

    @Test fun `base is preferred over small while decoding is the bottleneck`() {
        // Reverted 2026-09-28: small measured 12-16 s STT per turn on a Pixel 9.
        tmp.newFile("ggml-base-q5_1.bin"); tmp.newFile("ggml-small-q5_1.bin")
        assertEquals("ggml-base-q5_1.bin", manager().pick()!!.name)
    }

    @Test fun `multilingual base outranks english-only small`() {
        // english-only demoted below every multilingual model (app coaches many languages)
        tmp.newFile("ggml-small.en.bin"); tmp.newFile("ggml-base-q5_1.bin")
        assertEquals("ggml-base-q5_1.bin", manager().pick()!!.name)
    }

    @Test fun `base falls back when small is absent`() {
        tmp.newFile("ggml-base-q5_1.bin")
        assertEquals("ggml-base-q5_1.bin", manager().pick()!!.name)
    }

    @Test fun `quantized variants win the size tiebreak within a family`() {
        // Same rank → the larger file wins (less quantization, better accuracy):
        // f16 is bigger than its q5_1 variant.
        tmp.newFile("ggml-small.bin").writeText("x".repeat(400))
        tmp.newFile("ggml-small-q5_1.bin").writeText("x".repeat(180))
        assertEquals("ggml-small.bin", manager().pick()!!.name)
    }

    @Test fun `rank keeps base ahead of small and english-only variants`() {
        assertTrue(LocalWhisperModelManager.preferenceRank("ggml-base-q5_1.bin") <
            LocalWhisperModelManager.preferenceRank("ggml-small-q5_1.bin"))
        assertTrue(LocalWhisperModelManager.preferenceRank("ggml-base-q5_1.bin") <
            LocalWhisperModelManager.preferenceRank("ggml-small.en.bin"))
        assertTrue(LocalWhisperModelManager.preferenceRank("ggml-base.en-q5_1.bin") <
            LocalWhisperModelManager.preferenceRank("ggml-small.en-q5_1.bin"))
    }

    @Test fun `no models picks null`() {
        assertNull(manager().pick())
    }
}
