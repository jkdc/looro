package com.jkdc.app.data

import com.jkdc.app.core.ArchiveCodec
import com.jkdc.app.core.CompletionMark
import com.jkdc.app.core.ExpressionState
import com.jkdc.app.core.Fragment
import com.jkdc.app.core.Preferences
import com.jkdc.app.core.SkillError
import com.jkdc.app.core.Speaker
import com.jkdc.app.core.UsageEvents
import java.io.File
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

    @Test fun sessionUpsertUpdatesInPlaceAndNeverTouchesFragments() {
        db.insert("sessions", mapOf("id" to "s1", "language_id" to "es", "started_at" to 0.0, "title" to "Hola",
            "provider_id" to "p1", "ended_at" to 10.0, "end_reason" to "done"))
        val fragment = Fragment(id = "f1", speaker = Speaker.user, text = "hola", startMS = 0, endMS = 10)
        FragmentsDao.replaceBySession(db, "s1", listOf(fragment))
        // a session-end style update that also CLEARS nullable columns
        val updated = SessionsDao.all(db).single().copy(
            providerID = null, endedAt = null, endReason = null, inputTokens = 42, outputTokens = 7,
            searchCalls = 1, usageFinal = true, title = "Cambiado")
        SessionsDao.upsert(db, updated)
        assertEquals(updated, SessionsDao.all(db).single())
        // INSERT OR REPLACE would have cascade-deleted this row
        assertEquals(listOf(fragment), FragmentsDao.bySession(db, "s1"))
        assertNull(db.query("SELECT provider_id FROM sessions WHERE id = 's1'").single()["provider_id"])
    }

    @Test fun expressionsUpsertNormalizesTheKeyAndReplacesInPlace() {
        val expression = ExpressionState(text = "  Hola ", meaning = "hello", source = "lookup", dueAt = 5.0,
            languageID = "es")
        assertTrue(ExpressionsDao.upsert(db, expression))
        assertEquals("hola", db.query("SELECT norm_key FROM expressions").single()["norm_key"])
        val rowIdBefore = db.query("SELECT rowid AS rid FROM expressions").single().long("rid")
        assertTrue(ExpressionsDao.upsert(db, expression.copy(text = "Hola", bars = 2)))
        val rows = ExpressionsDao.all(db)
        assertEquals(1, rows.size)
        assertEquals("Hola", rows.single().text)
        assertEquals(2, rows.single().bars)
        // updated in place, never REPLACE-d: the rowid (export order) is stable
        assertEquals(rowIdBefore, db.query("SELECT rowid AS rid FROM expressions").single().long("rid"))
    }

    @Test fun expressionsUpsertAdoptsALegacyNullLanguageRowInPlace() {
        val legacy = ExpressionState(text = "hola", meaning = "hello", source = "lookup", bars = 2, attempts = 3,
            lastSeen = 10.0, dueAt = 20.0, languageID = null)
        assertTrue(ExpressionsDao.upsert(db, legacy))
        val legacyRowId = db.query("SELECT rowid AS rid FROM expressions").single().long("rid")
        // what saveLookup hands over after adopting: the language retargeted and a
        // fresh lookup state — the legacy row's progress must survive untouched
        val incoming = legacy.copy(bars = 0, attempts = 0, lastSeen = 0.0, dueAt = 500.0,
            meaning = "hi", languageID = "es")
        assertTrue(ExpressionsDao.upsert(db, incoming))
        val rows = ExpressionsDao.all(db)
        assertEquals(1, rows.size)
        val adopted = rows.single()
        assertEquals("es", adopted.languageID)
        // engine-faithful: only the language (and a blank meaning) change
        assertEquals(2, adopted.bars)
        assertEquals(3, adopted.attempts)
        assertEquals(10.0, adopted.lastSeen, 0.0)
        assertEquals(20.0, adopted.dueAt, 0.0)
        assertEquals("hello", adopted.meaning)
        // adopted in place, not duplicated and reinserted
        assertEquals(legacyRowId, db.query("SELECT rowid AS rid FROM expressions").single().long("rid"))
    }

    @Test fun expressionsAdoptionFillsOnlyABlankLegacyMeaning() {
        assertTrue(ExpressionsDao.upsert(db, ExpressionState(text = "hola", meaning = "", source = "lookup",
            bars = 1, dueAt = 5.0, languageID = null)))
        assertTrue(ExpressionsDao.upsert(db, ExpressionState(text = "hola", meaning = "hello", source = "lookup",
            dueAt = 9.0, languageID = "es")))
        val adopted = ExpressionsDao.all(db).single()
        assertEquals("hello", adopted.meaning) // blank legacy meaning takes the incoming one
        assertEquals(1, adopted.bars)          // legacy scheduling still wins
        assertEquals(5.0, adopted.dueAt, 0.0)
    }

    @Test fun expressionsAdoptionCollapsesDuplicateLegacyRowsIntoOne() {
        // the PK keeps NULLs distinct and cannot stop duplicate legacy rows
        db.insert("expressions", mapOf(
            "norm_key" to "hola", "language_id" to null, "text" to "hola", "meaning" to "",
            "source" to "lookup", "bars" to 1, "attempts" to 1, "last_seen" to 5.0, "due_at" to 10.0))
        db.insert("expressions", mapOf(
            "norm_key" to "hola", "language_id" to null, "text" to "Hola", "meaning" to "hello",
            "source" to "lookup", "bars" to 2, "attempts" to 3, "last_seen" to 10.0, "due_at" to 20.0,
            "lesson_id" to "l1"))
        assertTrue(ExpressionsDao.upsert(db, ExpressionState(text = "hola", meaning = "hi", source = "lookup",
            bars = 0, dueAt = 500.0, languageID = "es")))
        val merged = ExpressionsDao.all(db)
        assertEquals(1, merged.size)
        // ArchiveCodec.merge semantics across the collapsed rows, engine semantics vs the incoming
        assertEquals("es", merged.single().languageID)
        assertEquals("hola", merged.single().text)     // kept row (rowid order) wins
        assertEquals(2, merged.single().bars)
        assertEquals(3, merged.single().attempts)
        assertEquals(10.0, merged.single().lastSeen, 0.0)
        assertEquals(20.0, merged.single().dueAt, 0.0)
        assertEquals("hello", merged.single().meaning) // first non-blank meaning
        assertEquals("l1", merged.single().lessonID)   // first non-null lesson
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
        assertEquals("00000000-0000-4000-8000-000000000001", pending[0].event.id) // the rowid becomes the event id
        // ...in the UUID-V4 shape the server's usage endpoint accepts
        assertTrue(pending.all { it.event.id.matches(
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) })
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
        MetaDao.replaceMastery(db, mapOf("grammar:articles" to 2, "function:describe-people" to 1))
        assertEquals(mapOf("grammar:articles" to 2, "function:describe-people" to 1), MetaDao.mastery(db))
        // replace semantics: a re-import with fewer skills drops the stale rows
        MetaDao.replaceMastery(db, mapOf("grammar:articles" to 3))
        assertEquals(mapOf("grammar:articles" to 3), MetaDao.mastery(db))
    }

    // -- conversation search (schema v2) --------------------------------------

    /** The shared case-basic fixture, imported for real: two es sessions
        ("En el restaurante" 812573760, "The verb ser" 812620000 — newer) and
        one fr session ("Commander au café"). */
    private fun importCaseBasic() {
        val decoded = ArchiveCodec.decode(
            File("../../../shared/fixtures/db/migration/case-basic.json").readText())
        ArchiveMapper.import(decoded.copy(
            lessons = decoded.lessons.map { it.copy(bars = 0, dueAt = 0.0) }.toMutableList()), db)
    }

    @Test fun searchMatchesFixtureFragmentsFiltersByLanguageAndOrdersNewestFirst() {
        importCaseBasic()
        // a word from the older es conversation, in es: exactly that session
        // (the word matches TWO of its fragments; the session is ONE hit)
        val reservar = SearchDao.search(db, "reservar", "es")
        assertEquals(listOf("En el restaurante"), reservar.map { it.session.title })
        assertEquals(1, reservar.size)
        assertTrue("the snippet wraps the match",
            reservar.single().snippet.contains("«reservar"))
        // the same word from the fr conversation is invisible to es...
        assertEquals(0, SearchDao.search(db, "café", "es").size)
        // ...and found in fr
        assertEquals(listOf("Commander au café"), SearchDao.search(db, "café", "fr").map { it.session.title })
        // a token present in both es sessions: newest conversation first
        val de = SearchDao.search(db, "de", "es")
        assertEquals(listOf("The verb ser", "En el restaurante"), de.map { it.session.title })
    }

    @Test fun searchLimitsPrefixMatchesAndNeverThrowsOnFtsSyntax() {
        importCaseBasic()
        // LIMIT caps sessions, not fragment matches
        assertEquals(1, SearchDao.search(db, "de", "es", limit = 1).size)
        assertEquals(0, SearchDao.search(db, "de", "es", limit = 0).size)
        // prefix search: "reser" finds "reservar"
        assertEquals(1, SearchDao.search(db, "reser", "es").size)
        // FTS4 operator characters cannot throw or widen the match: the
        // sanitizer strips them, and a trailing star defuses the keywords
        assertEquals(0, SearchDao.search(db, "\"", "es").size)
        assertEquals(0, SearchDao.search(db, "  ", "es").size)
        assertEquals(0, SearchDao.search(db, "NEAR", "es").size)
        assertEquals(1, SearchDao.search(db, "\"(reservar)\"", "es").size)
        // the OR keyword is defused (terms AND together; no fragment holds both)
        assertEquals(0, SearchDao.search(db, "mesa OR fibra", "es").size)
        // case- and accent-insensitive (unicode61 diacrits off by default)
        assertEquals(1, SearchDao.search(db, "RESERVÁR", "es").size)
    }

    @Test fun searchMatchQueryEmitsBarePrefixTermsOnly() {
        assertEquals("qué* tal*", SearchDao.matchQuery("qué tal"))
        assertEquals("HO*", SearchDao.matchQuery("  \"HO?\" "))
        assertEquals("", SearchDao.matchQuery("  \"\"  "))
        // a term with the trailing star can never parse as a live FTS4 keyword
        assertEquals("NEAR* OR* NOT* AND*", SearchDao.matchQuery("NEAR OR NOT AND"))
    }

    // -- archive_health heartbeat ---------------------------------------------

    @Test fun archiveHealthClaimsTheDailySlotOncePerWindow() {
        val start = 1_700_000_000_000L
        // first launch: no meta row yet -> the claim returns the bucketed props
        // the caller enqueues as the durable usage_outbox row
        val props = ArchiveHealth.claimIfDue(db, start, dbBytes = 5 * 1024 * 1024,
            sessionCount = 7)!!
        assertEquals(buildJsonObject {
            put("dbBytesBucket", "1-10mb")
            put("sessionsBucket", "1-10")
        }, props)
        assertEquals(start.toString(), MetaDao.get(db, ArchiveHealth.LAST_HEALTH_AT_KEY))
        // the claim itself never writes the outbox row — that is the caller's
        // single durable write
        assertEquals(0L, count("usage_outbox"))
        // inside the 24h cadence: no claim, no new marker
        assertNull(ArchiveHealth.claimIfDue(db, start + ArchiveHealth.CADENCE_MILLISECONDS - 1,
            dbBytes = 5 * 1024 * 1024, sessionCount = 7))
        assertEquals(start.toString(), MetaDao.get(db, ArchiveHealth.LAST_HEALTH_AT_KEY))
        // past the cadence: claims again and moves the marker
        val next = start + ArchiveHealth.CADENCE_MILLISECONDS
        val second = ArchiveHealth.claimIfDue(db, next, dbBytes = 250 * 1024 * 1024,
            sessionCount = 500)!!
        assertEquals(buildJsonObject {
            put("dbBytesBucket", ">=100mb")
            put("sessionsBucket", ">200")
        }, second)
        assertEquals(next.toString(), MetaDao.get(db, ArchiveHealth.LAST_HEALTH_AT_KEY))
        assertEquals(0L, count("usage_outbox"))
        // the event is in the closed catalog
        assertTrue(UsageEvents.ARCHIVE_HEALTH in UsageEvents.all)
    }

    @Test fun archiveHealthHeartbeatLandsAsExactlyOneDurableRowPerWindow() {
        // the AppViewModel site, replayed: claim the 24h slot, then write the
        // durable row — the ONLY record of the heartbeat (no in-memory
        // double-write; UsageReporter.flush drains it, core-side tested)
        val start = 1_700_000_000_000L
        val props = ArchiveHealth.claimIfDue(db, start, dbBytes = 5 * 1024 * 1024,
            sessionCount = 7)!!
        UsageOutboxDao.enqueue(db, UsageEvents.ARCHIVE_HEALTH, start, props)
        var pending = UsageOutboxDao.pending(db)
        assertEquals(1, pending.size)
        assertEquals("archive_health", pending.single().event.name)
        assertEquals(start, pending.single().event.at)
        assertEquals(props, pending.single().event.props)
        // a close before the next flush loses nothing: the row is still
        // pending in a later run and drains then
        // inside the window: no claim -> no second row
        assertNull(ArchiveHealth.claimIfDue(db, start + ArchiveHealth.CADENCE_MILLISECONDS - 1,
            dbBytes = 5 * 1024 * 1024, sessionCount = 7))
        assertEquals(1L, count("usage_outbox"))
        // next window: exactly one more row, both still undispatched (the
        // drain marks them only after a successful upload)
        val next = start + ArchiveHealth.CADENCE_MILLISECONDS
        val secondProps = ArchiveHealth.claimIfDue(db, next, dbBytes = 250 * 1024 * 1024,
            sessionCount = 500)!!
        UsageOutboxDao.enqueue(db, UsageEvents.ARCHIVE_HEALTH, next, secondProps)
        pending = UsageOutboxDao.pending(db)
        assertEquals(2, pending.size)
        assertEquals(2L, count("usage_outbox"))
    }

    private fun count(table: String): Long = db.query("SELECT COUNT(*) AS n FROM $table").single().long("n")
}
