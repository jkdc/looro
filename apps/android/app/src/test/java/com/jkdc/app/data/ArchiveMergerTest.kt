package com.jkdc.app.data

import com.jkdc.app.core.Archive
import com.jkdc.app.core.ArchiveCodec
import com.jkdc.app.core.ArchiveError
import com.jkdc.app.core.Assessment
import com.jkdc.app.core.CompletionMark
import com.jkdc.app.core.EvidenceKind
import com.jkdc.app.core.ExpressionState
import com.jkdc.app.core.Fragment
import com.jkdc.app.core.LessonState
import com.jkdc.app.core.Outcome
import com.jkdc.app.core.Preferences
import com.jkdc.app.core.RecallEngine
import com.jkdc.app.core.SessionRecord
import com.jkdc.app.core.SkillError
import com.jkdc.app.core.Speaker
import com.jkdc.app.core.WordProposal
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE phase-B contract: for the shared fixtures plus synthetic archives,
 * `decode(encode(ArchiveCodec.merge(base, incoming)))` must equal what the
 * database exports after ArchiveMerger.apply — struct-level, list orders
 * included — on real SQLite (sqlite-jdbc).
 *
 * One normalization is applied on both sides of every comparison: schema v1
 * does not store LessonState.bars/dueAt (lesson_state keeps attempts and
 * last_seen only), so the persisted projection reads them back as zero — the
 * same pin ArchiveMapper's roundtrip tests carry. One schema-pinned narrowing
 * has its own test: a same-instant cross-language completion pair, which the
 * codec keeps as two marks and the completions PRIMARY KEY keeps as one.
 */
class ArchiveMergerTest {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    private val db = LearningDb.openJvm(connection)

    @After fun closeConnection() { connection.close() }

    private fun fixture(name: String): String =
        File("../../../shared/fixtures/db/migration/$name").readText()

    private fun decodeFixture(name: String): Archive = ArchiveCodec.decode(fixture(name))

    /** Schema v1's pinned projection: bars/dueAt are not stored (shared/db/schema.sql,
        lesson_state), so the database's durable form always reads them as zero. */
    private fun persistedProjection(a: Archive): Archive =
        a.copy(lessons = a.lessons.map { it.copy(bars = 0, dueAt = 0.0) }.toMutableList())

    private fun seedBase(base: Archive) {
        ArchiveMapper.import(base, db)
        assertEquals("the merger's `current` input must be exactly what the database holds",
            base, ArchiveMapper.export(db))
    }

    /** The contract, for inputs without the cross-language completion narrowing. */
    private fun assertParity(base: Archive, incoming: Archive): Archive {
        seedBase(base)
        val expected = ArchiveCodec.decode(
            ArchiveCodec.encode(ArchiveCodec.merge(base, incoming)))
        ArchiveMerger.apply(db, incoming)
        assertEquals(persistedProjection(expected), ArchiveMapper.export(db))
        return expected
    }

    private fun newSession(id: String, languageID: String, title: String): SessionRecord =
        SessionRecord(id = id, languageID = languageID, title = title,
            startedAt = 812700000.0, endedAt = 812703600.0, voiceSeconds = 120.0,
            fragments = mutableListOf(
                Fragment(id = UUID.randomUUID().toString(), speaker = Speaker.assistant,
                    text = "Saludos", startMS = 0, endMS = 1500, receivedAt = 812700001.0)))

    @Test fun caseBasicWithOverlappingIncomingMatchesCodecMerge() {
        val base = persistedProjection(decodeFixture("case-basic.json"))
        val incoming = Archive(
            preferences = Preferences(learningLanguageID = "fr", sessionMinutes = 20,
                interests = "incoming interests", hasOnboarded = true),
            sessions = mutableListOf(
                newSession("39481f2a-3c05-4a86-9ac1-6f20b7d40e11", "es", "Nueva conversación"),
                // a STORED id, letter-cased differently: merge's known-set is
                // lowercase and skips it — the stored session is never replaced
                newSession("A3D94B12-6F57-4C08-B1A2-93E4F5A6B7C8", "es", "NO DEBE GANAR")),
            lessons = mutableListOf(
                LessonState("es-foundations-u1-ser", bars = 1, attempts = 9,
                    lastSeen = 812600000.0, dueAt = 812700000.0),
                LessonState("nb-foundations-u1-hallo", attempts = 1, lastSeen = 812700000.0)),
            expressions = mutableListOf(
                // same (norm_key, es) identity as a base row: codec fusion, and the
                // fused row loses its language (merge rebuilds it without languageID)
                ExpressionState(text = "important", meaning = "muy importante", source = "lesson",
                    lessonID = "other-lesson", bars = 3, attempts = 1, lastSeen = 812600000.0,
                    dueAt = 812700000.0, languageID = "es"),
                // same norm_key as the base LEGACY row but its own language key:
                // merge keeps both (coexistence), it does not adopt
                ExpressionState(text = "buenos días", meaning = "buenos días (es)", source = "lookup",
                    dueAt = 812700000.0, languageID = "es"),
                ExpressionState(text = "gracias", meaning = "thank you", source = "lookup",
                    dueAt = 812700000.0, languageID = "es")),
            completions = mutableListOf(
                // same completionKey as the stored mark (different bestScore): the
                // seen-set skips it and the stored 92 stays
                CompletionMark("lesson", "es-foundations-u1-ser", 812577400.0, 99, "es"),
                CompletionMark("listening", "nb-listen-1", 812800000.0, 88, "nb")),
            errors = mutableListOf(
                SkillError("grammar:articles", count = 5, lastAt = 812580000.0, lastPrompt = "más nuevo"),
                SkillError("function:describe-people", count = 9, lastAt = 812000000.0, lastPrompt = "más viejo"),
                SkillError("vocabulary:food", count = 1, lastAt = 812700000.0, lastPrompt = "comida")),
            // merge ignores the incoming masteryCache entirely
            masteryCache = mapOf("grammar:articles" to 9, "brand:new" to 4),
        )
        val expected = assertParity(base, incoming)
        assertTrue("the lesson projection must be doing real work on this input",
            expected.lessons.any { it.bars != 0 || it.dueAt != 0.0 })

        val exported = ArchiveMapper.export(db)
        // incoming preferences and masteryCache are ignored by merge
        assertEquals(base.preferences, exported.preferences)
        assertEquals(base.masteryCache, exported.masteryCache)
        // sessions: stored ids win (the uppercase twin was skipped), adds append in order
        assertEquals(base.sessions.map { it.id } + "39481f2a-3c05-4a86-9ac1-6f20b7d40e11",
            exported.sessions.map { it.id })
        // expression fusion: cur's text/meaning/source/lessonID kept, numbers maxed,
        // and the fused row is LEGACY (languageID null — the pinned merge quirk)
        val fused = exported.expressions.single { it.text == "important" && it.languageID == null }
        assertEquals("importante", fused.meaning)
        assertEquals("lesson", fused.source)
        assertEquals("es-foundations-u1-ser", fused.lessonID)
        assertEquals(3, fused.bars)
        assertEquals(2, fused.attempts)
        assertEquals(812600000.0, fused.lastSeen, 0.0)
        assertEquals(812700000.0, fused.dueAt, 0.0)
        // coexistence: the incoming es row sits beside the legacy one, in merge order
        assertEquals(listOf(null as String?, "es"),
            exported.expressions.filter { RecallEngine.normalizeKey(it.text) == "buenos días" }
                .map { it.languageID })
        // completions dedup kept the STORED mark's bestScore
        assertEquals(92, exported.completions.orEmpty()
            .single { it.kind == "lesson" && it.itemID == "es-foundations-u1-ser" }.bestScore)
        // errors: max count, lastAt maxed, prompt from whichever side is newer,
        // re-sorted by count descending exactly like the codec's output
        val errors = exported.errors.orEmpty()
        assertEquals(listOf("function:describe-people", "grammar:articles", "vocabulary:food"),
            errors.map { it.skillID })
        assertEquals(9, errors[0].count)
        assertEquals(812379200.0, errors[0].lastAt, 0.0)
        assertEquals("Describe a tu mejor amigo en una frase", errors[0].lastPrompt)
        assertEquals(7, errors[1].count)
        assertEquals(812580000.0, errors[1].lastAt, 0.0)
        assertEquals("más nuevo", errors[1].lastPrompt)
    }

    @Test fun emptyBaseWithCaseBasicIncomingMatchesCodecMerge() {
        val base = decodeFixture("case-empty.json")
        val incoming = decodeFixture("case-basic.json")
        val expected = assertParity(base, incoming)
        assertTrue("the lesson projection must be doing real work on this input",
            expected.lessons.any { it.bars != 0 || it.dueAt != 0.0 })
        val exported = ArchiveMapper.export(db)
        // all three fixture sessions are adds: merge deep-copies each, runs
        // invalidateChangedAssessments and LearningEngine.validate — the fixture's
        // only assessment survives, its word downgraded independent→assisted
        // because the learner fragment it quotes is typed
        assertEquals(incoming.sessions.map { it.id }, exported.sessions.map { it.id })
        val assessment = exported.sessions.single { it.assessments.isNotEmpty() }.assessments.single()
        assertEquals(EvidenceKind.assisted, assessment.words.single().kind)
        assertEquals("reservar", assessment.words.single().lemma)
    }

    @Test fun crossLanguageSameInstantCompletionsAndInvalidAssessmentsFollowTheCodec() {
        val base = decodeFixture("case-empty.json")
        val assistant = Fragment(id = "f-assistant-1", speaker = Speaker.assistant,
            text = "¿Cómo estás?", startMS = 0, endMS = 2000, receivedAt = 812700001.0)
        val user = Fragment(id = "f-user-1", speaker = Speaker.user, text = "Bien, gracias",
            startMS = 3000, endMS = 5000, receivedAt = 812700004.0, typed = true)
        val word = WordProposal("bien", "well", "Bien", EvidenceKind.exposure, 0.9,
            listOf(user.id), "Bien", language = "es")
        val weakWord = word.copy(confidence = 0.5) // below 0.8 — LearningEngine drops it
        val valid = Assessment(user.id, "${user.id}:0", Outcome.success, 2, "g", "A1",
            listOf(word, weakWord), 812700010.0, "free")
        val levelOutOfRange = valid.copy(suggestedLevel = 7)          // LearningEngine rejects 0..5
        val staleRevision = valid.copy(revisionKey = "missing-passage:9") // invalidateChangedAssessments drops it
        val incoming = Archive(
            preferences = Preferences(learningLanguageID = "es", sessionMinutes = 30),
            sessions = mutableListOf(
                SessionRecord(id = "2b0e8e46-8a1e-4c3b-9d2f-5a7b8c9d0e1f", languageID = "es",
                    startedAt = 812700000.0, endedAt = 812703600.0, voiceSeconds = 60.0,
                    fragments = mutableListOf(assistant, user),
                    assessments = mutableListOf(levelOutOfRange, staleRevision, valid)),
                SessionRecord(id = "3c1f9d57-9b2f-4d4c-ae3a-6b8c9d0e1f2a", languageID = "fr",
                    startedAt = 812710000.0, mode = "lesson", lessonID = "fr-u1")),
            lessons = mutableListOf(
                LessonState("es-foundations-u2", bars = 3, attempts = 2, lastSeen = 812700000.0)),
            expressions = mutableListOf(
                ExpressionState(text = "hola de nuevo", meaning = "hi again", source = "lookup",
                    dueAt = 812700000.0, languageID = "es")),
            completions = mutableListOf(
                CompletionMark("lesson", "es-u2", 812900000.0, 90, "es"),
                // same instant, same item, two languages: merge keeps BOTH …
                CompletionMark("reading", "es-read-1", 812800000.0, 80, "es"),
                CompletionMark("reading", "es-read-1", 812800000.0, 70, "fr"),
                CompletionMark("listening", "es-listen-1", 812700000.0, 60, null)),
            errors = mutableListOf(
                SkillError("vocabulary:food", count = 2, lastAt = 812700000.0, lastPrompt = "p")),
            masteryCache = mapOf("grammar:x" to 1),
        )
        seedBase(base)
        val expected = ArchiveCodec.decode(
            ArchiveCodec.encode(ArchiveCodec.merge(base, incoming)))
        ArchiveMerger.apply(db, incoming)
        val actual = ArchiveMapper.export(db)

        // everything except completions: full parity, the pinned lesson projection included
        assertEquals(persistedProjection(expected).copy(completions = null),
            actual.copy(completions = null))
        // incoming preferences ignored: the empty base's defaults stay
        assertEquals("nb", actual.preferences.learningLanguageID)
        // the added sessions landed in order, their assessments filtered: the
        // level-out-of-range and stale-revision proposals are gone, the weak word too
        assertEquals(listOf("2b0e8e46-8a1e-4c3b-9d2f-5a7b8c9d0e1f", "3c1f9d57-9b2f-4d4c-ae3a-6b8c9d0e1f2a"),
            actual.sessions.map { it.id })
        val merged = actual.sessions.first().assessments
        assertEquals(1, merged.size)
        assertEquals(listOf("bien"), merged.single().words.map { it.lemma })

        // THE documented narrowing: merge's completionKey keeps the cross-language
        // pair (4 marks, es first by the stable at-sort) …
        assertEquals(4, expected.completions.orEmpty().size)
        assertEquals(listOf("es", "fr"), expected.completions.orEmpty()
            .filter { it.at == 812800000.0 }.map { it.languageID })
        // … while the completions PRIMARY KEY (kind, item_id, at) keeps only the
        // FIRST of the pair in merge order. Everything else matches exactly.
        val kept = expected.completions.orEmpty().toMutableList().apply { removeAt(2) }
        assertEquals(kept, actual.completions.orEmpty())
        assertEquals(listOf("es-u2", "es-read-1", "es-listen-1"),
            actual.completions.orEmpty().map { it.itemID })
    }

    @Test fun sessionOverflowThrowsTooLargeAndLeavesTheDatabaseUntouched() {
        val base = persistedProjection(decodeFixture("case-basic.json"))
        seedBase(base)
        // 9998 new sessions into a 3-session database: merge's MAXIMUM_SESSIONS
        // import guard throws before a single row is written
        val overflow = Archive(sessions = List(9_998) {
            SessionRecord(id = UUID.randomUUID().toString(), languageID = "es")
        }.toMutableList())
        val thrown = assertThrows(ArchiveError::class.java) { ArchiveMerger.apply(db, overflow) }
        assertEquals(ArchiveError.Kind.TOO_LARGE, thrown.kind)
        assertEquals(base, ArchiveMapper.export(db))
    }

    @Test fun expressionOverflowThrowsInvalidAndLeavesTheDatabaseUntouched() {
        val base = persistedProjection(decodeFixture("case-basic.json"))
        seedBase(base)
        // 4 stored + 1997 new = 2001 expressions: merge's final validate(out)
        // throws — the late failure path — and the transaction still leaves
        // the database byte-identical
        val overflow = Archive(expressions = List(1_997) { i ->
            ExpressionState(text = "k$i", meaning = "m", source = "lookup", dueAt = 1.0,
                languageID = "es")
        }.toMutableList())
        val thrown = assertThrows(ArchiveError::class.java) { ArchiveMerger.apply(db, overflow) }
        assertEquals(ArchiveError.Kind.INVALID, thrown.kind)
        assertEquals(base, ArchiveMapper.export(db))
    }

    @Test fun freshDatabaseMergesItsFirstArchiveLikeTheCodec() {
        val base = ArchiveMapper.export(db) // a fresh database exports the default archive
        assertEquals(Archive(), base)
        val incoming = decodeFixture("case-empty.json")
        assertParity(base, incoming)
        assertEquals(incoming, ArchiveMapper.export(db))
    }
}
