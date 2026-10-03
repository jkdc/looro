package com.jkdc.app.data

import com.jkdc.app.core.APPLE_EPOCH_UNIX_SECONDS
import com.jkdc.app.core.ArchiveCodec
import com.jkdc.app.core.Assessment
import com.jkdc.app.core.CompletionMark
import com.jkdc.app.core.ExpressionState
import com.jkdc.app.core.Fragment
import com.jkdc.app.core.LessonState
import com.jkdc.app.core.PendingUsageEvent
import com.jkdc.app.core.Preferences
import com.jkdc.app.core.RecallEngine
import com.jkdc.app.core.ScenarioReview
import com.jkdc.app.core.SessionRecord
import com.jkdc.app.core.SkillError
import com.jkdc.app.core.Speaker
import com.jkdc.app.core.TopicBrief
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToLong

/**
 * Row access helpers for the learning database. Values arrive typed by the
 * backend (see [Row]): INTEGER reads back as Long, REAL as Double, TEXT as
 * String and NULL as null, so every column is narrowed at the DAO boundary.
 */
internal fun Row.bool(name: String): Boolean = (this[name] as Long) != 0L
internal fun Row.int(name: String): Int = (this[name] as Long).toInt()
internal fun Row.long(name: String): Long = this[name] as Long
internal fun Row.real(name: String): Double = this[name] as Double
internal fun Row.text(name: String): String = this[name] as String
internal fun Row.boolOrNull(name: String): Boolean? = (this[name] as Long?)?.let { it != 0L }
internal fun Row.intOrNull(name: String): Int? = (this[name] as Long?)?.toInt()
internal fun Row.realOrNull(name: String): Double? = this[name] as Double?
internal fun Row.textOrNull(name: String): String? = this[name] as String?

internal fun decodeStringList(json: String): List<String> =
    ArchiveJson.decodeFromString(ListSerializer(String.serializer()), json)

internal fun encodeStringList(value: List<String>): String =
    ArchiveJson.encodeToString(ListSerializer(String.serializer()), value)

/** Preferences occupy exactly one row (preferences.id = 1, CHECK-pinned) and
    carry all fourteen Models.kt fields — the seven v2 fields NOT NULL, the
    seven legacy-nullable fields NULL when unset. */
object PreferencesDao {
    fun put(db: SqlDatabase, p: Preferences) {
        db.insert("preferences", row(p), ConflictStrategy.REPLACE)
    }

    /** null when the learner has no preferences row yet (fresh database). */
    fun get(db: SqlDatabase): Preferences? {
        val row = db.query("SELECT * FROM preferences WHERE id = 1").firstOrNull() ?: return null
        return Preferences(
            learningLanguageID = row.text("learning_language_id"),
            meaningVisible = row.bool("meaning_visible"),
            meaningLanguage = row.text("meaning_language"),
            sessionMinutes = row.int("session_minutes"),
            hiddenWords = decodeStringList(row.text("hidden_words")),
            interests = row.text("interests"),
            hasOnboarded = row.bool("has_onboarded"),
            aiConsentVersion = row.intOrNull("ai_consent_version"),
            slowSpeech = row.boolOrNull("slow_speech"),
            listeningPractice = row.boolOrNull("listening_practice"),
            speechRate = row.realOrNull("speech_rate"),
            usageMetricsEnabled = row.boolOrNull("usage_metrics_enabled"),
            sttEngine = row.textOrNull("stt_engine"),
            pronunciationEnabled = row.boolOrNull("pronunciation_enabled"),
        )
    }

    private fun row(p: Preferences) = mapOf<String, Any?>(
        "id" to 1L,
        "learning_language_id" to p.learningLanguageID,
        "meaning_visible" to p.meaningVisible,
        "meaning_language" to p.meaningLanguage,
        "session_minutes" to p.sessionMinutes,
        "hidden_words" to encodeStringList(p.hiddenWords),
        "interests" to p.interests,
        "has_onboarded" to p.hasOnboarded,
        "ai_consent_version" to p.aiConsentVersion,
        "slow_speech" to p.slowSpeech,
        "listening_practice" to p.listeningPractice,
        "speech_rate" to p.speechRate,
        "usage_metrics_enabled" to p.usageMetricsEnabled,
        "stt_engine" to p.sttEngine,
        "pronunciation_enabled" to p.pronunciationEnabled,
    )
}

/** Sessions persist their scalar columns plus the four write-once JSON
    payloads (assessments, translations, topics, scenario review) exactly as
    ArchiveCodec encodes them; review_created_at mirrors
    ScenarioReview.createdAt for cheap queries only — reads always decode the
    JSON column, which stays the source of truth. */
object SessionsDao {
    fun upsert(db: SqlDatabase, session: SessionRecord) {
        db.insert("sessions", row(session), ConflictStrategy.REPLACE)
    }

    /** Fragments go with it (fragments.session_id ON DELETE CASCADE). */
    fun delete(db: SqlDatabase, id: String) {
        db.update("DELETE FROM sessions WHERE id = ?", listOf(id))
    }

    /** All sessions in insertion order, fragments ordered by sort_index. */
    fun all(db: SqlDatabase): List<SessionRecord> =
        db.query("SELECT * FROM sessions ORDER BY rowid").map { SessionsDao.session(it, db) }

    private fun session(row: Row, db: SqlDatabase): SessionRecord {
        val id = row.text("id")
        return SessionRecord(
            id = id,
            languageID = row.text("language_id"),
            providerID = row.textOrNull("provider_id"),
            startedAt = row.real("started_at"),
            endedAt = row.realOrNull("ended_at"),
            themeID = row.textOrNull("theme_id"),
            title = row.text("title"),
            fragments = FragmentsDao.bySession(db, id).toMutableList(),
            assessments = ArchiveJson.decodeFromString(
                ListSerializer(Assessment.serializer()), row.text("assessments_json")).toMutableList(),
            translations = ArchiveJson.decodeFromString(
                MapSerializer(String.serializer(), String.serializer()), row.text("translations_json")).toMutableMap(),
            topics = ArchiveJson.decodeFromString(
                ListSerializer(TopicBrief.serializer()), row.text("topics_json")).toMutableList(),
            voiceSeconds = row.real("voice_seconds"),
            usageFinal = row.bool("usage_final"),
            inputTokens = row.int("input_tokens"),
            outputTokens = row.int("output_tokens"),
            searchCalls = row.int("search_calls"),
            endReason = row.textOrNull("end_reason"),
            mode = row.textOrNull("mode"),
            lessonID = row.textOrNull("lesson_id"),
            scenarioID = row.textOrNull("scenario_id"),
            scenarioReview = row.textOrNull("scenario_review_json")
                ?.let { ArchiveJson.decodeFromString(ScenarioReview.serializer(), it) },
        )
    }

    private fun row(s: SessionRecord) = mapOf<String, Any?>(
        "id" to s.id,
        "language_id" to s.languageID,
        "provider_id" to s.providerID,
        "started_at" to s.startedAt,
        "ended_at" to s.endedAt,
        "theme_id" to s.themeID,
        "title" to s.title,
        "voice_seconds" to s.voiceSeconds,
        "usage_final" to s.usageFinal,
        "input_tokens" to s.inputTokens,
        "output_tokens" to s.outputTokens,
        "search_calls" to s.searchCalls,
        "end_reason" to s.endReason,
        "mode" to s.mode,
        "lesson_id" to s.lessonID,
        "scenario_id" to s.scenarioID,
        "assessments_json" to ArchiveJson.encodeToString(ListSerializer(Assessment.serializer()), s.assessments),
        "translations_json" to ArchiveJson.encodeToString(
            MapSerializer(String.serializer(), String.serializer()), s.translations),
        "topics_json" to ArchiveJson.encodeToString(ListSerializer(TopicBrief.serializer()), s.topics),
        "scenario_review_json" to s.scenarioReview?.let { ArchiveJson.encodeToString(ScenarioReview.serializer(), it) },
        "review_created_at" to s.scenarioReview?.createdAt,
    )
}

/** Fragments belong to one session and keep their list position in
    sort_index; [replaceBySession] rewrites the session's whole transcript
    (delete + insert) inside one transaction so a crash can never leave half
    a transcript behind. */
object FragmentsDao {
    fun bySession(db: SqlDatabase, sessionID: String): List<Fragment> =
        db.query("SELECT * FROM fragments WHERE session_id = ? ORDER BY sort_index", listOf(sessionID))
            .map(::fragment)

    fun replaceBySession(db: SqlDatabase, sessionID: String, fragments: List<Fragment>) {
        db.transaction { tx ->
            tx.update("DELETE FROM fragments WHERE session_id = ?", listOf(sessionID))
            fragments.forEachIndexed { index, f -> tx.insert("fragments", row(sessionID, index, f)) }
        }
    }

    private fun fragment(row: Row): Fragment = Fragment(
        id = row.text("id"),
        revision = row.int("revision"),
        previousTexts = decodeStringList(row.text("previous_texts")),
        speaker = Speaker.valueOf(row.text("speaker")),
        text = row.text("text"),
        startMS = row.int("start_ms"),
        endMS = row.int("end_ms"),
        receivedAt = row.real("received_at"),
        meaningVisible = row.bool("meaning_visible"),
        typed = row.bool("typed"),
    )

    private fun row(sessionID: String, sortIndex: Int, f: Fragment) = mapOf<String, Any?>(
        "id" to f.id,
        "session_id" to sessionID,
        "sort_index" to sortIndex,
        "speaker" to f.speaker.name,
        "text" to f.text,
        "revision" to f.revision,
        "previous_texts" to encodeStringList(f.previousTexts),
        "start_ms" to f.startMS,
        "end_ms" to f.endMS,
        "received_at" to f.receivedAt,
        "meaning_visible" to f.meaningVisible,
        "typed" to f.typed,
    )
}

/**
 * Saved expressions keyed on (norm_key, language_id) with norm_key =
 * [RecallEngine.normalizeKey]. The schema keeps NULL language rows distinct
 * (legacy entries, visible and adoptable in every language — RecallEngine
 * semantics), which has two consequences this upsert preserves:
 *
 *  - ADOPTION: when a language row is written and no exact (norm_key,
 *    language) row exists but a legacy (norm_key, NULL) row does, the legacy
 *    row is retargeted in place to the new language instead of duplicated —
 *    the same adopt-or-create RecallEngine.seedFromLesson/saveLookup perform.
 *    Its lesson_id column is left untouched, because adoption in the engine
 *    copies the legacy entry and only changes the language (and the meaning
 *    when blank, which the caller's state already carries).
 *  - COEXISTENCE: importers that must preserve an archive verbatim pass
 *    adoptLegacy = false, so a legacy row and a language row with the same
 *    norm_key stay two rows, exactly the two expressionKeys ArchiveCodec
 *    validates.
 *
 * The [RecallEngine.MAXIMUM_EXPRESSIONS] notebook cap is enforced here:
 * brand-new keys past the cap are a silent no-op returning false, while
 * updates to existing keys always land. Returns true when a row was written.
 */
object ExpressionsDao {
    fun upsert(db: SqlDatabase, expression: ExpressionState, adoptLegacy: Boolean = true): Boolean {
        val normKey = RecallEngine.normalizeKey(expression.text)
        val exactExists = if (expression.languageID == null) {
            db.query("SELECT 1 AS matched FROM expressions WHERE norm_key = ? AND language_id IS NULL",
                listOf(normKey)).isNotEmpty()
        } else {
            db.query("SELECT 1 AS matched FROM expressions WHERE norm_key = ? AND language_id = ?",
                listOf(normKey, expression.languageID)).isNotEmpty()
        }
        if (exactExists) {
            db.insert("expressions", row(normKey, expression), ConflictStrategy.REPLACE)
            return true
        }
        if (expression.languageID != null && adoptLegacy) {
            val legacy = db.query(
                "SELECT 1 AS matched FROM expressions WHERE norm_key = ? AND language_id IS NULL", listOf(normKey))
            if (legacy.isNotEmpty()) {
                // Retarget the legacy row; lesson_id is intentionally left as-is.
                db.update(
                    "UPDATE expressions SET language_id = ?, text = ?, meaning = ?, source = ?, " +
                        "bars = ?, attempts = ?, last_seen = ?, due_at = ? " +
                        "WHERE norm_key = ? AND language_id IS NULL",
                    listOf(expression.languageID, expression.text, expression.meaning, expression.source,
                        expression.bars, expression.attempts, expression.lastSeen, expression.dueAt, normKey))
                return true
            }
        }
        if (count(db) >= RecallEngine.MAXIMUM_EXPRESSIONS) return false
        db.insert("expressions", row(normKey, expression))
        return true
    }

    /** All expressions in insertion order (legacy NULL rows wherever the
        archive listed them). */
    fun all(db: SqlDatabase): List<ExpressionState> =
        db.query("SELECT * FROM expressions ORDER BY rowid").map(::expression)

    private fun count(db: SqlDatabase): Long = db.query("SELECT COUNT(*) AS n FROM expressions").single().long("n")

    private fun expression(row: Row): ExpressionState = ExpressionState(
        text = row.text("text"),
        meaning = row.text("meaning"),
        source = row.text("source"),
        lessonID = row.textOrNull("lesson_id"),
        bars = row.int("bars"),
        attempts = row.int("attempts"),
        lastSeen = row.real("last_seen"),
        dueAt = row.real("due_at"),
        languageID = row.textOrNull("language_id"),
    )

    private fun row(normKey: String, e: ExpressionState) = mapOf<String, Any?>(
        "norm_key" to normKey,
        "language_id" to e.languageID,
        "text" to e.text,
        "meaning" to e.meaning,
        "source" to e.source,
        "bars" to e.bars,
        "attempts" to e.attempts,
        "last_seen" to e.lastSeen,
        "due_at" to e.dueAt,
        "lesson_id" to e.lessonID,
    )
}

/** Per-lesson practice progress. Schema v1 persists attempts and last_seen
    only — LessonState.bars and dueAt are intentionally not stored yet and
    read back as zero (shared/db/schema.sql, lesson_state). */
object LessonStateDao {
    fun upsert(db: SqlDatabase, state: LessonState) {
        db.insert("lesson_state", mapOf(
            "lesson_id" to state.lessonID,
            "attempts" to state.attempts,
            "last_seen" to state.lastSeen,
        ), ConflictStrategy.REPLACE)
    }

    fun all(db: SqlDatabase): List<LessonState> =
        db.query("SELECT * FROM lesson_state ORDER BY rowid").map { row ->
            LessonState(
                lessonID = row.text("lesson_id"),
                bars = 0,
                attempts = row.int("attempts"),
                lastSeen = row.real("last_seen"),
                dueAt = 0.0,
            )
        }
}

/**
 * Completion marks (the append-only progress ledger), insert-or-ignore on the
 * (kind, item_id, at) primary key.
 *
 * DEDUP POLICY — languageID is stored on the row but is NOT part of the key,
 * unlike the inherited ArchiveCodec completionKey (kind|itemID|at|languageID).
 * Two marks that differ only by language are distinct to the archive but
 * collide in the table, and the first mark stored wins: writers append in
 * ArchiveCodec.merge's at-descending order, so "first" is the newest mark —
 * the same keep-newest outcome the archive's cap produces. A later mark at a
 * genuinely different `at` is its own row. Returns true when the mark landed.
 */
object CompletionsDao {
    fun add(db: SqlDatabase, mark: CompletionMark): Boolean =
        db.insert("completions", mapOf(
            "kind" to mark.kind,
            "item_id" to mark.itemID,
            "at" to mark.at,
            "best_score" to mark.bestScore,
            "language_id" to mark.languageID,
        ), ConflictStrategy.IGNORE) != -1L

    fun all(db: SqlDatabase): List<CompletionMark> =
        db.query("SELECT * FROM completions ORDER BY rowid").map { row ->
            CompletionMark(
                kind = row.text("kind"),
                itemID = row.text("item_id"),
                at = row.real("at"),
                bestScore = row.int("best_score"),
                languageID = row.textOrNull("language_id"),
            )
        }
}

/** Repeated-error patterns keyed by skill, kept to the newest
    [ArchiveCodec.MAXIMUM_ERRORS] rows by count — the same top-100 cap
    ArchiveCodec.merge applies, with rowid breaking count ties the same stable
    way merge's insertion-ordered sort does. */
object SkillErrorsDao {
    fun upsert(db: SqlDatabase, error: SkillError) {
        db.insert("skill_errors", mapOf(
            "skill_id" to error.skillID,
            "count" to error.count,
            "last_at" to error.lastAt,
            "last_prompt" to error.lastPrompt,
        ), ConflictStrategy.REPLACE)
        db.exec("DELETE FROM skill_errors WHERE skill_id NOT IN " +
            "(SELECT skill_id FROM skill_errors ORDER BY count DESC, rowid LIMIT ${ArchiveCodec.MAXIMUM_ERRORS})")
    }

    fun all(db: SqlDatabase): List<SkillError> =
        db.query("SELECT * FROM skill_errors ORDER BY rowid").map { row ->
            SkillError(row.text("skill_id"), row.int("count"), row.real("last_at"), row.text("last_prompt"))
        }
}

/** One queued usage event plus its durable row id ([PendingUsageEvent.id] is
    a client-generated string the schema does not store; the rowid takes over
    as identity and becomes the id used on the wire). */
data class QueuedUsageEvent(val rowId: Long, val event: PendingUsageEvent)

/**
 * Durable usage-outbox queue. [PendingUsageEvent.at] is unix milliseconds;
 * usage_outbox.at is Apple-epoch seconds REAL (the schema's one conversion
 * rule), applied here on write and reversed on read.
 */
object UsageOutboxDao {
    fun enqueue(db: SqlDatabase, name: String, atUnixMillis: Long,
                props: JsonObject = JsonObject(emptyMap())): Long =
        db.insert("usage_outbox", mapOf(
            "name" to name,
            "at" to atUnixMillis / 1000.0 - APPLE_EPOCH_UNIX_SECONDS,
            "props_json" to ArchiveJson.encodeToString(JsonObject.serializer(), props),
        ))

    /** Undispatched events, oldest first. */
    fun pending(db: SqlDatabase): List<QueuedUsageEvent> =
        db.query("SELECT rowid AS row_id, name, at, props_json FROM usage_outbox " +
            "WHERE dispatched = 0 ORDER BY at, row_id").map { row ->
            val rowId = row.long("row_id")
            QueuedUsageEvent(
                rowId = rowId,
                event = PendingUsageEvent(
                    id = rowId.toString(),
                    name = row.text("name"),
                    at = ((row.real("at") + APPLE_EPOCH_UNIX_SECONDS) * 1000.0).roundToLong(),
                    props = ArchiveJson.decodeFromString(JsonObject.serializer(), row.text("props_json")),
                ),
            )
        }

    fun markDispatched(db: SqlDatabase, rowIds: Collection<Long>) {
        if (rowIds.isEmpty()) return
        val placeholders = rowIds.joinToString(", ") { "?" }
        db.update("UPDATE usage_outbox SET dispatched = 1 WHERE rowid IN ($placeholders)", rowIds.toList())
    }
}

/** Final-assessment retry metadata, one row per session (the newest ticket
    wins on upsert, matching FinalAssessmentRecovery). passageID/revisionKey
    are not stored (schema v1): ticket matching degrades accordingly. */
data class AssessmentTicketRow(val sessionID: String, val attempts: Int, val lastAttemptAt: Double?)

object AssessmentTicketsDao {
    fun upsert(db: SqlDatabase, ticket: AssessmentTicketRow) {
        db.insert("assessment_tickets", mapOf(
            "session_id" to ticket.sessionID,
            "attempts" to ticket.attempts,
            "last_attempt_at" to ticket.lastAttemptAt,
        ), ConflictStrategy.REPLACE)
    }

    fun all(db: SqlDatabase): List<AssessmentTicketRow> =
        db.query("SELECT * FROM assessment_tickets ORDER BY rowid").map { row ->
            AssessmentTicketRow(row.text("session_id"), row.int("attempts"), row.realOrNull("last_attempt_at"))
        }

    fun clear(db: SqlDatabase) {
        db.exec("DELETE FROM assessment_tickets")
    }
}

/** Free-form meta rows: 'schema_flags' (JSON object) and one 'mastery:<key>'
    row per Archive.masteryCache entry, the Int encoded as TEXT. */
object MetaDao {
    const val SCHEMA_FLAGS_KEY = "schema_flags"
    const val MASTERY_PREFIX = "mastery:"

    fun put(db: SqlDatabase, key: String, value: String) {
        db.insert("meta", mapOf("key" to key, "value" to value), ConflictStrategy.REPLACE)
    }

    fun get(db: SqlDatabase, key: String): String? =
        db.query("SELECT value FROM meta WHERE key = ?", listOf(key)).firstOrNull()?.text("value")

    fun putMastery(db: SqlDatabase, mastery: Map<String, Int>) {
        mastery.forEach { (skill, level) -> put(db, "$MASTERY_PREFIX$skill", level.toString()) }
    }

    /** Every mastery row, keyed like Archive.masteryCache. */
    fun mastery(db: SqlDatabase): Map<String, Int> =
        db.query("SELECT key, value FROM meta WHERE key LIKE '$MASTERY_PREFIX%' ORDER BY key")
            .associate { it.text("key").removePrefix(MASTERY_PREFIX) to it.text("value").toInt() }

    /** Writes the schema-flags row once (INSERT OR IGNORE); v1 has no flags. */
    fun ensureSchemaFlags(db: SqlDatabase) {
        db.insert("meta", mapOf("key" to SCHEMA_FLAGS_KEY, "value" to "{}"), ConflictStrategy.IGNORE)
    }
}
