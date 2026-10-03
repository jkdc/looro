package com.jkdc.app.data

import com.jkdc.app.core.CompletionMark
import com.jkdc.app.core.ExpressionState
import com.jkdc.app.core.Fragment
import com.jkdc.app.core.Preferences
import com.jkdc.app.core.SkillError
import com.jkdc.app.core.Speaker
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DAO semantics on a real SQLite (sqlite-jdbc): upsert identities, the
 *  expressions cap and legacy adoption, the completions dedup policy, the
 *  skill-error top-100 trim and the usage-outbox epoch conversion. */
class LearningDbDaoTest {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    private val db = LearningDb.openJvm(connection)

    @After fun closeConnection() { connection.close() }

    @Test fun preferencesRoundTripAllFourteenFieldsNullableOnesIncluded() {
        assertNull(PreferencesDao.get(db))
        val populated = Preferences(
            learningLanguageID = "es", meaningVisible = false, meaningLanguage = "Spanish",
            sessionMinutes = 25, hiddenWords = listOf("es|hola|hello", "fr|chat|cat"),
            interests = "cooking, travel", hasOnboarded = true, aiConsentVersion = 2,
            slowSpeech = false, listeningPractice = true, speechRate = 0.85,
            usageMetricsEnabled = true, sttEngine = "system", pronunciationEnabled = false)
        PreferencesDao.put(db, populated)
        assertEquals(populated, PreferencesDao.get(db))
        // every legacy-nullable field NULL, exactly what a v1 fresh archive decodes to
        val legacy = Preferences()
        PreferencesDao.put(db, legacy)
        assertEquals(legacy, PreferencesDao.get(db))
        assertEquals(1L, count("preferences"))
    }

    @Test fun fragmentsReplaceBySessionRewritesSortIndexesInsideOneTransaction() {
        db.insert("sessions", mapOf("id" to "s1", "language_id" to "es", "started_at" to 0.0, "title" to "Hola"))
        val a = Fragment(id = "a", speaker = Speaker.user, text = "hola", startMS = 0, endMS = 10)
        val b = Fragment(id = "b", speaker = Speaker.assistant, text = "¿qué tal?", startMS = 20, endMS = 30,
            previousTexts = listOf("hola"), revision = 1, typed = true)
        FragmentsDao.replaceBySession(db, "s1", listOf(a, b))
        assertEquals(listOf(a, b), FragmentsDao.bySession(db, "s1"))
        // rewrite renumbers sort_index from the list position and drops stale rows
        FragmentsDao.replaceBySession(db, "s1", listOf(b, a))
        assertEquals(listOf(b, a), FragmentsDao.bySession(db, "s1"))
        assertEquals(0L, db.query("SELECT COUNT(*) AS n FROM fragments WHERE sort_index = 2").single()["n"])
        FragmentsDao.replaceBySession(db, "s1", emptyList())
        assertEquals(0L, count("fragments"))
    }

    @Test fun expressionsUpsertNormalizesTheKeyAndReplacesInPlace() {
        val expression = ExpressionState(text = "  Hola ", meaning = "hello", source = "lookup", dueAt = 5.0,
            languageID = "es")
        assertTrue(ExpressionsDao.upsert(db, expression))
        assertEquals("hola", db.query("SELECT norm_key FROM expressions").single()["norm_key"])
        assertTrue(ExpressionsDao.upsert(db, expression.copy(text = "Hola", bars = 2)))
        val rows = ExpressionsDao.all(db)
        assertEquals(1, rows.size)
        assertEquals("Hola", rows.single().text)
        assertEquals(2, rows.single().bars)
    }

    @Test fun expressionsUpsertAdoptsALegacyNullLanguageRowInPlace() {
        val legacy = ExpressionState(text = "hola", meaning = "hello", source = "lookup", bars = 2, attempts = 3,
            lastSeen = 10.0, dueAt = 20.0, languageID = null)
        assertTrue(ExpressionsDao.upsert(db, legacy))
        val legacyRowId = db.query("SELECT rowid AS rid FROM expressions").single().long("rid")
        // what the engine hands over after RecallEngine adoption: the legacy
        // entry retargeted to the current language
        assertTrue(ExpressionsDao.upsert(db, legacy.copy(languageID = "es")))
        val rows = ExpressionsDao.all(db)
        assertEquals(1, rows.size)
        val adopted = rows.single()
        assertEquals("es", adopted.languageID)
        assertEquals(2, adopted.bars)
        assertEquals(3, adopted.attempts)
        // adopted in place, not duplicated and reinserted
        assertEquals(legacyRowId, db.query("SELECT rowid AS rid FROM expressions").single().long("rid"))
    }

    @Test fun importedLegacyAndLanguageExpressionsCoexistWithoutAdoption() {
        val legacy = ExpressionState(text = "hola", meaning = "hello", source = "lookup", dueAt = 5.0, languageID = null)
        val spanish = ExpressionState(text = "hola", meaning = "hello", source = "lookup", bars = 1, dueAt = 6.0,
            languageID = "es")
        assertTrue(ExpressionsDao.upsert(db, legacy, adoptLegacy = false))
        assertTrue(ExpressionsDao.upsert(db, spanish, adoptLegacy = false))
        val languages = db.query("SELECT language_id FROM expressions WHERE norm_key = 'hola'")
            .map { it.textOrNull("language_id") }.toSet()
        assertEquals(setOf<String?>(null, "es"), languages)
    }

    @Test fun expressionsCapSkipsNewKeysAtMaximumButStillUpdatesExisting() {
        for (i in 0 until 2_000) {
            db.insert("expressions", mapOf(
                "norm_key" to "k$i", "language_id" to "es", "text" to "k$i", "meaning" to "m",
                "source" to "lesson", "lesson_id" to "l", "due_at" to 0.0))
        }
        assertTrue(ExpressionsDao.upsert(db, ExpressionState(text = "k7", meaning = "m", source = "lesson",
            lessonID = "l", dueAt = 1.0, languageID = "es")))
        assertFalse(ExpressionsDao.upsert(db, ExpressionState(text = "new", meaning = "m", source = "lookup",
            dueAt = 1.0, languageID = "es")))
        assertEquals(2_000L, count("expressions"))
        assertNull(db.query("SELECT norm_key FROM expressions WHERE norm_key = 'new'").firstOrNull())
    }

    @Test fun completionsIgnoreDuplicateInstantsAndLanguageStaysOutOfTheKey() {
        val mark = CompletionMark(kind = "lesson", itemID = "es-foundations-u1-ser", at = 100.0, bestScore = 90,
            languageID = "es")
        assertTrue(CompletionsDao.add(db, mark))
        // same instant, different language: distinct to the archive's completionKey,
        // one row here — the first stored (newest in merge order) wins
        assertFalse(CompletionsDao.add(db, mark.copy(bestScore = 50, languageID = "fr")))
        val stored = CompletionsDao.all(db)
        assertEquals(1, stored.size)
        assertEquals("es", stored.single().languageID)
        assertEquals(90, stored.single().bestScore)
        // a genuinely different instant is its own row
        assertTrue(CompletionsDao.add(db, mark.copy(at = 200.0)))
        assertEquals(2L, count("completions"))
    }

    @Test fun skillErrorsReplacePerSkillAndTrimToTheTopHundredByCount() {
        SkillErrorsDao.upsert(db, SkillError("s1", count = 2, lastAt = 1.0, lastPrompt = "a"))
        SkillErrorsDao.upsert(db, SkillError("s1", count = 5, lastAt = 2.0, lastPrompt = "b"))
        var only = SkillErrorsDao.all(db).single()
        assertEquals("b", only.lastPrompt)
        assertEquals(5, only.count)
        // 106 distinct skills: s1 (count 5, stored first) plus counts 0..104
        for (i in 0 until 105) SkillErrorsDao.upsert(db, SkillError("skill$i", count = i, lastAt = 3.0))
        val errors = SkillErrorsDao.all(db)
        assertEquals(100, errors.size)
        assertEquals("s1", errors.minByOrNull { it.count }?.skillID) // rowid breaks the count-5 tie
        assertNull(errors.firstOrNull { it.skillID == "skill5" })
        assertNotNull(errors.firstOrNull { it.skillID == "skill104" })
    }

    @Test fun usageOutboxConvertsUnixMillisToAppleSecondsAndBack() {
        val props = buildJsonObject { put("bucket", 3) }
        val first = UsageOutboxDao.enqueue(db, "session_started", 1_727_859_600_000L)
        val second = UsageOutboxDao.enqueue(db, "app_open", 1_727_859_600_123L, props)
        val stored = db.query("SELECT at FROM usage_outbox WHERE rowid = ?", listOf(second)).single()
        assertEquals(1_727_859_600.123 - com.jkdc.app.core.APPLE_EPOCH_UNIX_SECONDS, stored.real("at"), 0.0)
        val pending = UsageOutboxDao.pending(db)
        assertEquals(listOf(first, second), pending.map { it.rowId }) // oldest first
        assertEquals(1_727_859_600_000L, pending[0].event.at)
        assertEquals(1_727_859_600_123L, pending[1].event.at)
        assertEquals("app_open", pending[1].event.name)
        assertEquals(props, pending[1].event.props)
        assertEquals("1", pending[0].event.id) // the rowid becomes the event id
        UsageOutboxDao.markDispatched(db, listOf(first))
        assertEquals(listOf(second), UsageOutboxDao.pending(db).map { it.rowId })
        assertEquals(2L, count("usage_outbox"))
    }

    @Test fun assessmentTicketsUpsertPerSessionGetAndClear() {
        AssessmentTicketsDao.upsert(db, AssessmentTicketRow("s1", attempts = 0, lastAttemptAt = null))
        AssessmentTicketsDao.upsert(db, AssessmentTicketRow("s1", attempts = 1, lastAttemptAt = 10.0))
        AssessmentTicketsDao.upsert(db, AssessmentTicketRow("s2", attempts = 0, lastAttemptAt = null))
        val all = AssessmentTicketsDao.all(db)
        assertEquals(2, all.size)
        val s1 = all.single { it.sessionID == "s1" }
        assertEquals(1, s1.attempts)
        assertEquals(10.0, s1.lastAttemptAt!!, 0.0)
        AssessmentTicketsDao.clear(db)
        assertEquals(0, AssessmentTicketsDao.all(db).size)
    }

    @Test fun metaPutGetAndMasteryRows() {
        assertNull(MetaDao.get(db, "k"))
        MetaDao.put(db, "k", "v1")
        MetaDao.put(db, "k", "v2")
        assertEquals("v2", MetaDao.get(db, "k"))
        MetaDao.putMastery(db, mapOf("grammar:articles" to 2, "function:describe-people" to 1))
        assertEquals(mapOf("grammar:articles" to 2, "function:describe-people" to 1), MetaDao.mastery(db))
        assertEquals(1L, db.query("SELECT COUNT(*) AS n FROM meta WHERE key LIKE 'mastery:%' AND value = '2'").single()["n"])
    }

    private fun count(table: String): Long = db.query("SELECT COUNT(*) AS n FROM $table").single().long("n")
}
