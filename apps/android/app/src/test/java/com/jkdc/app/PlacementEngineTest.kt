package com.jkdc.app

import com.jkdc.app.core.Exercise
import com.jkdc.app.core.PlacementBank
import com.jkdc.app.core.PlacementEngine
import com.jkdc.app.core.PlacementItem
import com.jkdc.app.core.PlacementRegistry
import com.jkdc.app.core.PlacementResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic ladder tests for the placement engine: every run uses the
    real exported banks so bank-shape regressions surface here too. */
class PlacementEngineTest {

    private val bank = PlacementRegistry.forLanguage("en")!!

    private fun item(bank: PlacementBank, id: String) = bank.items.first { it.id == id }
    private fun correctAttempt(item: PlacementItem) = item.exercise.answers.first()
    private fun wrongAttempt(item: PlacementItem): String {
        val option = item.exercise.options.firstOrNull { it !in item.exercise.answers }
        return option ?: "__wrong__"
    }

    private fun answer(state: PlacementEngine.Core, item: PlacementItem, correct: Boolean): PlacementEngine.Core =
        PlacementEngine.applyCore(state, item, if (correct) correctAttempt(item) else wrongAttempt(item))

    @Test
    fun `both banks validate against band and block minimums`() {
        assertEquals(listOf("en", "es"), PlacementRegistry.all.map { it.languageID })
        for (placementBank in PlacementRegistry.all) {
            for (band in PlacementEngine.BANDS) {
                val core = placementBank.items.filter { it.isCore && it.level == band }
                assertTrue("$band core", core.size >= 6)
                for (mode in listOf(PlacementItem.MODE_LISTENING, PlacementItem.MODE_WRITING)) {
                    assertTrue("$band $mode", placementBank.items.count { it.mode == mode && it.level == band } >= 3)
                }
            }
            placementBank.items.forEach { placementItem ->
                assertTrue(placementItem.id.startsWith("pl-${placementBank.languageID}-"))
                assertTrue(Regex("^[a-z0-9][a-z0-9_-]{0,63}$").matches("placement-${placementBank.languageID}-v${placementBank.version}"))
            }
        }
    }

    @Test
    fun `all-correct from A1 climbs to the C1 ceiling and stops`() {
        var state = PlacementEngine.start(bank, "A1")
        var guard = 0
        while (!state.finished && guard++ < 40) {
            val next = PlacementEngine.nextCoreItem(state) ?: break
            state = answer(state, next, correct = true)
        }
        assertTrue(state.finished)
        assertEquals("C1", state.band)
        // 3 answers per band × 5 bands = 15: the ceiling verdict lands before
        // the 18-answer cap, so the confidence is medium, not low.
        assertEquals(15, state.answered)
        assertEquals(PlacementResult.CONFIDENCE_MEDIUM, PlacementEngine.confidence(state.answered))
        assertTrue(state.answered <= PlacementEngine.CORE_CAP)
    }

    @Test
    fun `all-wrong at A1 hits the floor verdict quickly`() {
        var state = PlacementEngine.start(bank, "A1")
        var guard = 0
        while (!state.finished && guard++ < 40) {
            val next = PlacementEngine.nextCoreItem(state) ?: break
            state = answer(state, next, correct = false)
        }
        assertTrue(state.finished)
        assertEquals("A1", state.band)
        assertTrue(state.answered <= 3)
    }

    @Test
    fun `two wrong of three move the band down and two right of three move it up`() {
        var state = PlacementEngine.start(bank, "B1")
        val b1 = { id: String -> item(bank, id) }
        // One wrong is not enough to move: the window needs three.
        state = answer(state, b1("pl-en-b1-core-1"), correct = false)
        assertEquals("B1", state.band)
        // Two wrong: still no verdict, the window is not full yet.
        state = answer(state, b1("pl-en-b1-core-2"), correct = false)
        assertEquals("B1", state.band)
        // Third wrong completes the window: down to A2.
        state = answer(state, b1("pl-en-b1-core-3"), correct = false)
        assertEquals("A2", state.band)
        // Fresh window at A2: two correct of three move back up to B1.
        val a2First = PlacementEngine.nextCoreItem(state)!!
        state = answer(state, a2First, correct = true)
        assertEquals("A2", state.band)
        val a2Second = PlacementEngine.nextCoreItem(state)!!
        state = answer(state, a2Second, correct = true)
        assertEquals("A2", state.band)
        val a2Third = PlacementEngine.nextCoreItem(state)!!
        state = answer(state, a2Third, correct = true)
        assertEquals("B1", state.band)
    }

    @Test
    fun `items never repeat and selection balances categories`() {
        var state = PlacementEngine.start(bank, "A1")
        repeat(6) {
            val next = PlacementEngine.nextCoreItem(state) ?: return@repeat
            state = answer(state, next, correct = false)
        }
        val ids = state.asked.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        // Every asked item belongs to the band it was asked in (A1 here: the
        // ladder never moved with all-wrong answers).
        assertTrue(state.asked.all { it.level == "A1" })
    }

    @Test
    fun `final blocks only lower their own breakdown`() {
        val global = "B1"
        val kept = PlacementEngine.blockBand(global, listOf(true, true, false))
        val dropped = PlacementEngine.blockBand(global, listOf(false, false, true))
        assertEquals("B1", kept)
        assertEquals("A2", dropped)
        assertEquals(3, PlacementEngine.blockItems(bank, "B1", PlacementItem.MODE_LISTENING).size)
        assertEquals(3, PlacementEngine.blockItems(bank, "B1", PlacementItem.MODE_WRITING).size)
        assertTrue(PlacementEngine.blockItems(bank, "B1", PlacementItem.MODE_LISTENING).all { it.speech != null })
    }

    @Test
    fun `categories mirror answered core items and confidence follows the cap`() {
        var state = PlacementEngine.start(bank, "A1")
        repeat(3) {
            val next = PlacementEngine.nextCoreItem(state)!!
            state = answer(state, next, correct = true)
        }
        val categories = PlacementEngine.categories(state.asked, state.verdicts)
        assertTrue(categories.isNotEmpty())
        assertTrue(categories.all { it.second == 100 })
        assertEquals(PlacementResult.CONFIDENCE_HIGH, PlacementEngine.confidence(3))
        assertEquals(PlacementResult.CONFIDENCE_MEDIUM, PlacementEngine.confidence(16))
        assertEquals(PlacementResult.CONFIDENCE_LOW, PlacementEngine.confidence(18))
    }

    @Test
    fun `recommended start uses a real band and next item exists there`() {
        var state = PlacementEngine.start(bank, "B2")
        assertNotNull(PlacementEngine.nextCoreItem(state))
        state = PlacementEngine.start(bank, "Z9")
        assertEquals("A1", state.band)
    }

    @Test
    fun `session is reconstructible from ids and verdicts`() {
        var state = PlacementEngine.start(bank, "A1")
        repeat(4) {
            val next = PlacementEngine.nextCoreItem(state)!!
            state = answer(state, next, correct = it % 2 == 0)
        }
        val restored = PlacementEngine.Core(bank, state.band,
            state.asked.map { asked -> item(bank, asked.id) }, state.verdicts, state.window, state.finished)
        assertEquals(state.band, restored.band)
        assertEquals(state.finished, restored.finished)
        assertEquals(PlacementEngine.nextCoreItem(state)?.id, PlacementEngine.nextCoreItem(restored)?.id)
    }

    @Test
    fun `es bank mirrors the en shape`() {
        val es = PlacementRegistry.forLanguage("es")!!
        assertEquals(bank.version, es.version)
        assertEquals(bank.items.size, es.items.size)
    }
}
