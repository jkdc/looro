package com.jkdc.app.data

import com.jkdc.app.core.ArchiveCodec
import com.jkdc.app.core.CompletionMark
import com.jkdc.app.core.ExpressionState
import com.jkdc.app.core.LessonState
import com.jkdc.app.core.Preferences
import com.jkdc.app.core.SessionRecord
import com.jkdc.app.core.SkillError
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phase-C read surface, over the shared migration fixture on real SQLite
 * (sqlite-jdbc): per-language session and expression reads (legacy NULL rows
 * visible in every language), the RecallEngine.due ordering, and the
 * first-insertion-order list reads.
 */
class LearningDbReaderTest {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    private val db = LearningDb.openJvm(connection)

    @After fun closeConnection() { connection.close() }

    private fun fixture(name: String): String =
        File("../../../shared/fixtures/db/migration/$name").readText()

    @Test fun sessionsAndLessonsAndListsReadInInsertionOrder() {
        ArchiveMapper.import(ArchiveCodec.decode(fixture("case-basic.json")), db)
        assertEquals(listOf("7f6c2a1e-58d4-4b3a-9e2f-0c8d1a4b6e90",
                "a3d94b12-6f57-4c08-b1a2-93e4f5a6b7c8", "c5e16f28-7a09-4d31-82b3-a4f5b6c7d8e9"),
            LearningDbReader.allSessions(db).map { it.id })
        assertEquals(listOf("7f6c2a1e-58d4-4b3a-9e2f-0c8d1a4b6e90", "a3d94b12-6f57-4c08-b1a2-93e4f5a6b7c8"),
            LearningDbReader.sessionsFor(db, "es").map { it.id })
        assertEquals(listOf("c5e16f28-7a09-4d31-82b3-a4f5b6c7d8e9"),
            LearningDbReader.sessionsFor(db, "fr").map { it.id })
        assertEquals(emptyList<String>(), LearningDbReader.sessionsFor(db, "de").map { it.id })
        // schema v1 projection: bars/dueAt read as zero
        assertEquals(listOf(
                LessonState("es-foundations-u1-ser", attempts = 3, lastSeen = 812539200.0),
                LessonState("fr-foundations-u1-etre", attempts = 5, lastSeen = 812547840.0)),
            LearningDbReader.lessons(db))
        assertEquals(listOf("lesson", "listening", "reading", "scenario_practice", "conversation_theme"),
            LearningDbReader.completions(db).map { it.kind })
        assertEquals(92, LearningDbReader.completions(db).first().bestScore)
        assertEquals(listOf("grammar:articles", "function:describe-people"),
            LearningDbReader.skillErrors(db).map { it.skillID })
        assertEquals(7, LearningDbReader.skillErrors(db).first().count)
        val preferences = LearningDbReader.preferences(db)
        assertEquals("es", preferences.learningLanguageID)
        assertEquals(0.85, preferences.speechRate!!, 0.0)
        assertTrue(preferences.hasOnboarded)
        assertEquals(mapOf("grammar:articles" to 2, "function:describe-people" to 1),
            LearningDbReader.masteryMeta(db))
    }

    @Test fun expressionsForIncludesLegacyNullRowsInEveryLanguage() {
        ArchiveMapper.import(ArchiveCodec.decode(fixture("case-basic.json")), db)
        // es rows plus the legacy one, in insertion order
        assertEquals(listOf("Buenos días", "important", "¿Qué tal?"),
            LearningDbReader.expressionsFor(db, "es").map { it.text })
        assertEquals(listOf<String?>(null, "es", "es"),
            LearningDbReader.expressionsFor(db, "es").map { it.languageID })
        // the same legacy row is visible to French too
        assertEquals(listOf("Buenos días", "important"),
            LearningDbReader.expressionsFor(db, "fr").map { it.text })
        assertEquals(listOf<String?>(null, "fr"),
            LearningDbReader.expressionsFor(db, "fr").map { it.languageID })
    }

    @Test fun dueExpressionsFollowsRecallEngineDueSemanticsAndOrder() {
        ArchiveMapper.import(ArchiveCodec.decode(fixture("case-basic.json")), db)
        val now = 812600000.0
        // due = es-or-legacy rows with bars < 3 and dueAt <= now, oldest due first:
        // ¿Qué tal? (dueAt 0) before Buenos días (812491200); the es "important"
        // row is not due yet (812625600)
        assertEquals(listOf("¿Qué tal?", "Buenos días"),
            LearningDbReader.dueExpressions(db, "es", now).map { it.text })
        // boundary pins: dueAt == now is due; bars == 3 never is
        ExpressionsDao.upsert(db, ExpressionState(text = "graduado", meaning = "done",
            source = "lookup", bars = 3, dueAt = 0.0, languageID = "es"))
        ExpressionsDao.upsert(db, ExpressionState(text = "exacto", meaning = "exact",
            source = "lookup", bars = 2, dueAt = now, languageID = "es"))
        assertEquals(listOf("¿Qué tal?", "Buenos días", "exacto"),
            LearningDbReader.dueExpressions(db, "es", now).map { it.text })
    }

    @Test fun aFreshDatabaseReadsDefaultsThroughEveryReaderApi() {
        assertEquals(emptyList<SessionRecord>(), LearningDbReader.allSessions(db))
        assertEquals(emptyList<SessionRecord>(), LearningDbReader.sessionsFor(db, "es"))
        assertEquals(emptyList<LessonState>(), LearningDbReader.lessons(db))
        assertEquals(emptyList<ExpressionState>(), LearningDbReader.expressionsFor(db, "es"))
        assertEquals(emptyList<ExpressionState>(), LearningDbReader.dueExpressions(db, "es", 0.0))
        assertEquals(emptyList<CompletionMark>(), LearningDbReader.completions(db))
        assertEquals(emptyList<SkillError>(), LearningDbReader.skillErrors(db))
        assertEquals(Preferences(), LearningDbReader.preferences(db))
        assertEquals(emptyMap<String, Int>(), LearningDbReader.masteryMeta(db))
    }
}
