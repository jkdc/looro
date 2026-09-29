package com.jkdc.app.core

import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RecallEngineTest {
    private fun archive() = Archive()
    private fun lesson() = CourseRegistry.lesson("en-foundations-u1-to-be")!!

    @Test fun `lesson practice seeds expressions idempotently`() {
        val a = archive()
        val now = 1_000.0
        RecallEngine.seedFromLesson(a, lesson(), now)
        assertEquals(lesson().expressions.size, a.expressions.size)
        assertTrue(a.expressions.all { it.source == "lesson" && it.lessonID == lesson().id && it.bars == 0 && it.dueAt == now })
        val before = a.expressions.size
        RecallEngine.seedFromLesson(a, lesson(), now + 5)
        assertEquals(before, a.expressions.size)
    }

    @Test fun `lookup save upserts on normalized text`() {
        val a = archive()
        assertTrue(RecallEngine.saveLookup(a, "  Grandmother ", "the mother of your parent", 100.0))
        assertTrue(RecallEngine.saveLookup(a, "grandmother", "the mother of your parent", 200.0))
        assertEquals(1, a.expressions.size)
        assertEquals("lookup", a.expressions.single().source)
        assertNull(a.expressions.single().lessonID)
    }

    @Test fun `grading promotes on the ladder and lapses drop to bar one`() {
        val a = archive()
        RecallEngine.saveLookup(a, "soup", "a hot liquid food", 0.0)
        RecallEngine.grade(a, " SOUP ", correct = true, now = 100.0)
        val first = a.expressions.single()
        assertEquals(1, first.bars)
        assertEquals(100.0 + 1 * 86400, first.dueAt, 0.001)
        RecallEngine.grade(a, "soup", correct = true, now = 200.0)
        assertEquals(2, a.expressions.single().bars)
        assertEquals(200.0 + 4 * 86400, a.expressions.single().dueAt, 0.001)
        RecallEngine.grade(a, "soup", correct = false, now = 300.0)
        assertEquals(1, a.expressions.single().bars)
        assertEquals(300.0 + 1 * 86400, a.expressions.single().dueAt, 0.001)
        assertEquals(3, a.expressions.single().attempts)
    }

    @Test fun `due queue only holds due expressions oldest first`() {
        val a = archive()
        RecallEngine.saveLookup(a, "soup", "a hot liquid food", 100.0)
        RecallEngine.saveLookup(a, "grandmother", "the mother of your parent", 200.0)
        RecallEngine.grade(a, "soup", correct = true, now = 100.0)   // due tomorrow
        RecallEngine.grade(a, "grandmother", correct = true, now = 200.0) // due tomorrow+1
        assertEquals(0, RecallEngine.dueCount(a, 150.0))
        val dueNow = RecallEngine.due(a, 100.0 + 3 * 86400)
        assertEquals(2, dueNow.size)
        assertEquals("soup", dueNow.first().text)
        assertTrue(dueNow.zipWithNext().all { (x, y) -> x.dueAt <= y.dueAt })
    }

    @Test fun `merge upserts expressions by normalized text`() {
        val current = archive().apply { RecallEngine.saveLookup(this, "Soup", "a hot liquid food", 100.0) }
        val incoming = ArchiveCodec.decode(ArchiveCodec.encode(current))
        incoming.expressions.clear()
        incoming.expressions += ExpressionState(text = "soup", meaning = "a hot liquid food",
            source = "lookup", bars = 3, attempts = 9, lastSeen = 900.0, dueAt = 900.0,
            languageID = current.preferences.learningLanguageID)
        val merged = ArchiveCodec.merge(current, incoming)
        assertEquals(1, merged.expressions.size)
        assertEquals(3, merged.expressions.single().bars)
        assertEquals(9, merged.expressions.single().attempts)
    }

    @Test fun `old archives without expressions still decode`() {
        val fixture = File("../../../shared/fixtures/cross-platform/archive.json").readText()
        val root = Json.parseToJsonElement(fixture).jsonObject
        val legacyElement = buildJsonObject { root.forEach { (key, value) -> if (key != "expressions") put(key, value) } }
        val legacy = ArchiveCodec.decode(legacyElement.toString())
        assertTrue(legacy.expressions.isEmpty())
        val archive = ArchiveCodec.decode(fixture)
        assertEquals(2, archive.expressions.size)
        assertEquals("en-foundations-u1-to-be", archive.expressions.first { it.source == "lesson" }.lessonID)
    }

    @Test fun `notebook capacity is bounded`() {
        val a = archive()
        repeat(RecallEngine.MAXIMUM_EXPRESSIONS) { i ->
            assertTrue(RecallEngine.saveLookup(a, "word $i", "meaning", i.toDouble()))
        }
        assertFalse(RecallEngine.saveLookup(a, "one more", "meaning", 1.0))
        assertEquals(RecallEngine.MAXIMUM_EXPRESSIONS, a.expressions.size)
    }
}
