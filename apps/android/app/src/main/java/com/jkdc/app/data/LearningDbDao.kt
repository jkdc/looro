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
import com.jkdc.app.core.UsageBuckets
import com.jkdc.app.core.UsageEvents
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

/**
 * UPDATE with NULL assignments spelled as literals. [SqlDatabase.update]
 * rejects null bind arguments, and updates must clear columns too (an ended
 * session going back to ended_at IS NULL), so absent values become
 * `column = NULL` while present ones bind. Column names come only from the
 * fixed per-table row builders in this file, never from user data.
 */
private fun updateRow(db: SqlDatabase, table: String, row: Map<String, Any?>,
                      where: String, whereArgs: List<Any?>) {
    val sets = StringBuilder()
    val args = mutableListOf<Any?>()
    for ((column, value) in row) {
        if (sets.isNotEmpty()) sets.append(", ")
        if (value == null) sets.append(column).append(" = NULL")
        else { sets.append(column).append(" = ?"); args.add(value) }
    }
    db.update("UPDATE $table SET $sets WHERE $where", args + whereArgs)
}

/**
 * DAOs for the learning database (schema v1).
 *
 * Contract for everything below: keyed writes are select-then-update/insert.
 * This layer NEVER uses INSERT OR REPLACE — REPLACE re-inserts the row (a
 * fresh rowid, which would make export order drift) and its internal DELETE
 * fires foreign-key cascades, so REPLACE-ing a session would silently wipe
 * its fragments. Rowids are therefore stable for the life of a row and
 * ORDER BY rowid reads back insertion order forever.
 */
object PreferencesDao {
    fun put(db: SqlDatabase, p: Preferences) {
        val exists = db.query("SELECT 1 AS matched FROM preferences WHERE id = 1").isNotEmpty()
        if (exists) updateRow(db, "preferences", row(p), "id = 1", emptyList())
        else db.insert("preferences", row(p))
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
    /** Select-then-update/insert: never REPLACE, whose internal DELETE would
        cascade-destroy the session's fragments (fragments.session_id ON
        DELETE CASCADE) on every session-end token/endedAt update. */
    fun upsert(db: SqlDatabase, session: SessionRecord) {
        val exists = db.query("SELECT 1 AS matched FROM sessions WHERE id = ?",
            listOf(session.id)).isNotEmpty()
        if (exists) updateRow(db, "sessions", row(session), "id = ?", listOf(session.id))
        else db.insert("sessions", row(session))
    }

    /** Fragments go with it (fragments.session_id ON DELETE CASCADE). */
    fun delete(db: SqlDatabase, id: String) {
        db.update("DELETE FROM sessions WHERE id = ?", listOf(id))
    }

    /** All sessions in insertion order, fragments ordered by sort_index. */
    fun all(db: SqlDatabase): List<SessionRecord> =
        db.query("SELECT * FROM sessions ORDER BY rowid").map { SessionsDao.session(it, db) }

    /** Sessions of one learning language in insertion order. */
    fun byLanguage(db: SqlDatabase, languageID: String): List<SessionRecord> =
        db.query("SELECT * FROM sessions WHERE language_id = ? ORDER BY rowid", listOf(languageID))
            .map { SessionsDao.session(it, db) }

    internal fun session(row: Row, db: SqlDatabase): SessionRecord {
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
    a transcript behind. [replaceInTx] is the same rewrite for callers that
    already run inside a transaction (ArchiveMapper.import) — transactions
    do not nest. */
object FragmentsDao {
    fun bySession(db: SqlDatabase, sessionID: String): List<Fragment> =
        db.query("SELECT * FROM fragments WHERE session_id = ? ORDER BY sort_index", listOf(sessionID))
            .map(::fragment)

    fun replaceBySession(db: SqlDatabase, sessionID: String, fragments: List<Fragment>) {
        db.transaction { tx -> replaceInTx(tx, sessionID, fragments) }
    }

    internal fun replaceInTx(db: SqlDatabase, sessionID: String, fragments: List<Fragment>) {
        db.update("DELETE FROM fragments WHERE session_id = ?", listOf(sessionID))
        fragments.forEachIndexed { index, f -> db.insert("fragments", row(sessionID, index, f)) }
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
 * semantics), which shapes this upsert in three ways:
 *
 *  - EXACT MATCH: an existing (norm_key, language) row is UPDATEd in place —
 *    INSERT OR REPLACE cannot see a NULL-language conflict (NULLs are
 *    distinct in the key) and would duplicate every legacy row on re-import,
 *    producing an archive whose duplicate expressionKeys ArchiveCodec
 *    rejects.
 *  - ADOPTION: when a language row is written, no exact row exists but legacy
 *    (norm_key, NULL) row(s) do, the first legacy row (rowid order) is
 *    retargeted to the new language — exactly the adopt RecallEngine
 *    performs (seedFromLesson, saveLookup): only languageID changes, plus a
 *    meaning fill-in when the legacy meaning is blank; text, source,
 *    lesson_id, bars, attempts, lastSeen and dueAt stay the legacy entry's,
 *    never the incoming state's. If several legacy rows share the norm_key
 *    (the PK cannot prevent them), they collapse into that one row with
 *    ArchiveCodec.merge's semantics (max bars/attempts/lastSeen/dueAt, first
 *    non-blank meaning, first non-null lesson_id).
 *  - COEXISTENCE: importers that must preserve an archive verbatim pass
 *    adoptLegacy = false, so a legacy row and a language row with the same
 *    norm_key stay two rows, exactly the two expressionKeys ArchiveCodec
 *    validates.
 *
 * The [RecallEngine.MAXIMUM_EXPRESSIONS] notebook cap is enforced here:
 * brand-new keys past the cap are a silent no-op returning false, while
 * updates and adoptions always land. Returns true when a row was written.
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
            // language_id already matches the key; the row() value for it is
            // rewritten as-is (NULL literal or bound) and lesson_id may clear.
            updateRow(db, "expressions", row(normKey, expression),
                if (expression.languageID == null) "norm_key = ? AND language_id IS NULL"
                else "norm_key = ? AND language_id = ?",
                if (expression.languageID == null) listOf(normKey) else listOf(normKey, expression.languageID))
            return true
        }
        if (expression.languageID != null && adoptLegacy &&
            adoptLegacyRows(db, normKey, expression, expression.languageID)) return true
        if (count(db) >= RecallEngine.MAXIMUM_EXPRESSIONS) return false
        db.insert("expressions", row(normKey, expression))
        return true
    }

    /** Engine-faithful adoption of the first legacy row, collapsing any
        duplicate legacy rows into it. Returns false when none exist. */
    private fun adoptLegacyRows(db: SqlDatabase, normKey: String, incoming: ExpressionState,
                                languageID: String): Boolean {
        val legacy = db.query(
            "SELECT rowid AS rid, meaning, lesson_id, bars, attempts, last_seen, due_at " +
                "FROM expressions WHERE norm_key = ? AND language_id IS NULL ORDER BY rowid",
            listOf(normKey))
        if (legacy.isEmpty()) return false
        val keptId = legacy.first().long("rid")
        // languageID is the only semantic change (RecallEngine adoption); the
        // meaning fills a blank legacy meaning (saveLookup). The remaining
        // values come from the legacy rows themselves — max across them, which
        // is the row's own value in the single-row case — never the incoming.
        val meaning = legacy.map { it.text("meaning") }.firstOrNull { it.isNotBlank() }
            ?: incoming.meaning
        val lessonID = legacy.mapNotNull { it.textOrNull("lesson_id") }.firstOrNull()
        db.update(
            "UPDATE expressions SET language_id = ?, meaning = ?, bars = ?, attempts = ?, " +
                "last_seen = ?, due_at = ? WHERE rowid = ?",
            listOf(languageID, meaning,
                legacy.maxOf { it.int("bars") }, legacy.maxOf { it.int("attempts") },
                legacy.maxOf { it.real("last_seen") }, legacy.maxOf { it.real("due_at") }, keptId))
        if (lessonID == null) db.exec("UPDATE expressions SET lesson_id = NULL WHERE rowid = $keptId")
        else db.update("UPDATE expressions SET lesson_id = ? WHERE rowid = ?", listOf(lessonID, keptId))
        db.update("DELETE FROM expressions WHERE norm_key = ? AND language_id IS NULL AND rowid != ?",
            listOf(normKey, keptId))
        return true
    }

    /** Deletes every row visible to [languageID] for [text]'s norm_key — the
        language row AND any legacy NULL-language row — exactly what
        RecallEngine.remove removes from the archive (its predicate is
        language-or-legacy). Returns the removed row count (JVM only; -1 on
        Android). */
    fun removeVisible(db: SqlDatabase, text: String, languageID: String): Long =
        db.update("DELETE FROM expressions WHERE norm_key = ? AND (language_id = ? OR language_id IS NULL)",
            listOf(RecallEngine.normalizeKey(text), languageID))

    /** All expressions in insertion order (legacy NULL rows wherever the
        archive listed them). */
    fun all(db: SqlDatabase): List<ExpressionState> =
        db.query("SELECT * FROM expressions ORDER BY rowid").map(::expression)

    /** Expressions visible in [languageID]: its own rows plus the legacy
        NULL-language entries RecallEngine keeps reachable everywhere,
        insertion order. */
    fun byLanguage(db: SqlDatabase, languageID: String): List<ExpressionState> =
        db.query("SELECT * FROM expressions WHERE language_id = ? OR language_id IS NULL ORDER BY rowid",
            listOf(languageID)).map(::expression)

    /** RecallEngine.due over the table: the language's rows plus legacy NULL
        entries, bars < 3, dueAt reached, oldest due first. `ORDER BY due_at,
        rowid` reproduces the engine's stable sort over rowid order. */
    fun due(db: SqlDatabase, languageID: String, now: Double): List<ExpressionState> =
        db.query("SELECT * FROM expressions WHERE (language_id = ? OR language_id IS NULL) " +
            "AND bars < 3 AND due_at <= ? ORDER BY due_at, rowid", listOf(languageID, now)).map(::expression)

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
        val exists = db.query("SELECT 1 AS matched FROM lesson_state WHERE lesson_id = ?",
            listOf(state.lessonID)).isNotEmpty()
        val row = mapOf<String, Any?>(
            "lesson_id" to state.lessonID,
            "attempts" to state.attempts,
            "last_seen" to state.lastSeen,
        )
        if (exists) updateRow(db, "lesson_state", row, "lesson_id = ?", listOf(state.lessonID))
        else db.insert("lesson_state", row)
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
 * DEDUP POLICY — schema-pinned, and honestly narrower than the archive: the
 * inherited ArchiveCodec completionKey is kind|itemID|at|languageID, so merge
 * and validate keep TWO marks that differ only by language at the same
 * instant. This table's primary key (kind, item_id, at) cannot — the first
 * mark stored wins and the other is dropped, whichever order that happens to
 * be. Writers append in ArchiveCodec.merge's at-descending order, so for
 * merge-produced archives the survivor is the first in merge order (stable
 * position for equal at), but that is an artifact of write order, not an
 * equivalent semantic. A genuinely
 * different `at` is always its own row. Returns true when the mark landed.
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

    /** Rewrites the table to exactly [marks], in list order (callers inside a
        transaction use this directly — transactions do not nest). The runtime
        ledger re-sorts by `at` descending and truncates on every append
        (recordCompletion), so an insert-only write would drift from the
        archive's order and cap; the archive's ledger IS the table's content,
        and rowid order must equal list order for export to rebuild it. */
    fun replaceAllInTx(db: SqlDatabase, marks: List<CompletionMark>) {
        db.exec("DELETE FROM completions")
        marks.forEach { add(db, it) }
    }
}

/** Repeated-error patterns keyed by skill, kept to the newest
    [ArchiveCodec.MAXIMUM_ERRORS] rows by count — the same top-100 cap
    ArchiveCodec.merge applies, with rowid breaking count ties the same stable
    way merge's insertion-ordered sort does. Updates are in-place (no
    REPLACE), so rows keep their insertion-order rowid. */
object SkillErrorsDao {
    fun upsert(db: SqlDatabase, error: SkillError) {
        val exists = db.query("SELECT 1 AS matched FROM skill_errors WHERE skill_id = ?",
            listOf(error.skillID)).isNotEmpty()
        val row = mapOf<String, Any?>(
            "skill_id" to error.skillID,
            "count" to error.count,
            "last_at" to error.lastAt,
            "last_prompt" to error.lastPrompt,
        )
        if (exists) updateRow(db, "skill_errors", row, "skill_id = ?", listOf(error.skillID))
        else db.insert("skill_errors", row)
        db.exec("DELETE FROM skill_errors WHERE skill_id NOT IN " +
            "(SELECT skill_id FROM skill_errors ORDER BY count DESC, rowid LIMIT ${ArchiveCodec.MAXIMUM_ERRORS})")
    }

    fun all(db: SqlDatabase): List<SkillError> =
        db.query("SELECT * FROM skill_errors ORDER BY rowid").map { row ->
            SkillError(row.text("skill_id"), row.int("count"), row.real("last_at"), row.text("last_prompt"))
        }

    /** Rewrites the table to exactly [errors], in list order (callers inside a
        transaction use this directly — transactions do not nest). The runtime
        error memory re-sorts by count descending and truncates on every hit
        (recordExerciseMistake), so the archive's list IS the table's content
        and rowid order must equal list order for export to rebuild it. */
    fun replaceAllInTx(db: SqlDatabase, errors: List<SkillError>) {
        db.exec("DELETE FROM skill_errors")
        errors.forEach { upsert(db, it) }
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
        val exists = db.query("SELECT 1 AS matched FROM assessment_tickets WHERE session_id = ?",
            listOf(ticket.sessionID)).isNotEmpty()
        val row = mapOf<String, Any?>(
            "session_id" to ticket.sessionID,
            "attempts" to ticket.attempts,
            "last_attempt_at" to ticket.lastAttemptAt,
        )
        if (exists) updateRow(db, "assessment_tickets", row, "session_id = ?", listOf(ticket.sessionID))
        else db.insert("assessment_tickets", row)
    }

    fun all(db: SqlDatabase): List<AssessmentTicketRow> =
        db.query("SELECT * FROM assessment_tickets ORDER BY rowid").map { row ->
            AssessmentTicketRow(row.text("session_id"), row.int("attempts"), row.realOrNull("last_attempt_at"))
        }

    /** Removes one session's ticket — the mirror of FinalAssessmentRecovery
        enqueue's removal branch and of the ledger filters in deleteSession. */
    fun delete(db: SqlDatabase, sessionID: String) {
        db.update("DELETE FROM assessment_tickets WHERE session_id = ?", listOf(sessionID))
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
        val exists = db.query("SELECT 1 AS matched FROM meta WHERE key = ?", listOf(key)).isNotEmpty()
        if (exists) updateRow(db, "meta", mapOf("key" to key, "value" to value), "key = ?", listOf(key))
        else db.insert("meta", mapOf("key" to key, "value" to value))
    }

    fun get(db: SqlDatabase, key: String): String? =
        db.query("SELECT value FROM meta WHERE key = ?", listOf(key)).firstOrNull()?.text("value")

    /** The archive's mastery cache IS the table's mastery content: existing
        mastery rows are removed first so a re-import never leaves stale
        skills behind (export = the archive, not a union). Callers inside a
        transaction (ArchiveMapper.import) make that atomic. */
    fun replaceMastery(db: SqlDatabase, mastery: Map<String, Int>) {
        db.exec("DELETE FROM meta WHERE key LIKE '$MASTERY_PREFIX%'")
        mastery.forEach { (skill, level) -> put(db, "$MASTERY_PREFIX$skill", level.toString()) }
    }

    /** Every mastery row, keyed like Archive.masteryCache. */
    fun mastery(db: SqlDatabase): Map<String, Int> =
        db.query("SELECT key, value FROM meta WHERE key LIKE '$MASTERY_PREFIX%' ORDER BY key")
            .associate { it.text("key").removePrefix(MASTERY_PREFIX) to it.text("value").toInt() }

    /** Writes the schema-flags row once; v1 has no flags. */
    fun ensureSchemaFlags(db: SqlDatabase) {
        if (get(db, SCHEMA_FLAGS_KEY) == null) put(db, SCHEMA_FLAGS_KEY, "{}")
    }
}

/** One conversation-search result: the session to open plus a short context
    window around the best match (the same snippet the iOS mirror produces —
    markers '«'/'»' wrap the matched token, '…' marks elided text). */
data class SearchHit(val session: SessionRecord, val snippet: String)

/**
 * Full-text conversation search over the fragments FTS index (schema v2,
 * the FTS4 external-content table). MATCH resolves docids in fragments_fts
 * (an external-content FTS4 table's rowid IS the content row's rowid), the
 * JOIN walks back to the fragment's session, the language filter rides the
 * session (a fragment has no language of its own), and results order
 * newest-conversation-first with [limit] capping SESSIONS, not fragment
 * matches: rows come back one per matching fragment (snippet() must ride the
 * plain MATCH query — SQLite refuses it under a GROUP BY) and this DAO
 * collapses each session to its first matching fragment in transcript order.
 * snippet() is FTS4's six-argument form (table, start, end, ellipsis,
 * column, tokens); it reads the original text through the content option.
 * The query is a plain user string, sanitized into bare prefix terms (see
 * [matchQuery]) so FTS4 syntax characters can neither throw nor widen the
 * match.
 */
object SearchDao {
    const val SNIPPET_START = "«"
    const val SNIPPET_END = "»"
    const val SNIPPET_ELLIPSIS = "…"
    const val DEFAULT_LIMIT = 20

    fun search(db: SqlDatabase, query: String, languageID: String,
               limit: Int = DEFAULT_LIMIT): List<SearchHit> {
        val match = matchQuery(query)
        if (match.isEmpty() || limit <= 0) return emptyList()
        val hits = mutableListOf<SearchHit>()
        val seen = HashSet<String>()
        for (row in db.query(
            "SELECT s.*, snippet(fragments_fts, ?, ?, ?, 0, 16) AS fts_snippet " +
                "FROM fragments_fts " +
                "JOIN fragments ON fragments.rowid = fragments_fts.rowid " +
                "JOIN sessions s ON s.id = fragments.session_id " +
                "WHERE fragments_fts MATCH ? AND s.language_id = ? " +
                "ORDER BY s.started_at DESC, fragments.rowid",
            listOf(SNIPPET_START, SNIPPET_END, SNIPPET_ELLIPSIS, match, languageID),
        )) {
            // one hit per session: the first (newest conversation, earliest
            // fragment) match decides the snippet
            if (seen.add(row.text("id"))) {
                hits.add(SearchHit(SessionsDao.session(row, db), row.text("fts_snippet")))
                if (hits.size == limit) break
            }
        }
        return hits
    }

    /** "qué tal HO?" → "qué* tal* HO*" — one BARE prefix term per token:
        FTS4 has no escaped quoting (a quoted phrase matches whole tokens
        only — the prefix star is ignored inside quotes), and a term that
        carries the trailing star can never parse as the NEAR/OR/AND/NOT
        keywords, while the operator symbols ( ) : ^ - are stripped outright.
        A blank (or only-blank) query searches nothing. */
    internal fun matchQuery(query: String): String =
        query.split(Regex("\\s+"))
            .mapNotNull { token ->
                token.filter { it.isLetterOrDigit() }.takeIf { it.isNotEmpty() }
            }
            .joinToString(" ") { token -> "$token*" }
}

/**
 * The archive_health usage event: one coarse, content-free heartbeat per
 * install per day (the returned props are [UsageBuckets.dbBytes]/
 * [UsageBuckets.sessions] bucket strings — never exact sizes or counts).
 * The event itself rides the app's in-memory UsageReporter — the only queue
 * anything actually drains and uploads — so the durable usage_outbox is NOT
 * touched here: writing it there would grow forever (it has no consumer
 * yet). The 24h cadence keys off the meta 'last_health_at' row (unix
 * milliseconds as TEXT); the metrics-enabled choice stays the caller's.
 */
object ArchiveHealth {
    const val LAST_HEALTH_AT_KEY = "last_health_at"
    const val CADENCE_MILLISECONDS: Long = 24 * 60 * 60_000L

    /** Claims the daily slot: when the last marker is missing or older than
        [CADENCE_MILLISECONDS], stamps the marker and returns the bucketed
        props for the caller to record through its UsageReporter; otherwise
        returns null. A crash after the claim simply delays the next
        heartbeat to the next due window — acceptable for a heartbeat. The
        ONLY durable write is that single meta row (a put), atomic on its
        own — no transaction required. */
    fun claimIfDue(db: SqlDatabase, nowUnixMillis: Long, dbBytes: Long,
                   sessionCount: Int): JsonObject? {
        val last = MetaDao.get(db, LAST_HEALTH_AT_KEY)?.toLongOrNull()
        if (last != null && nowUnixMillis - last < CADENCE_MILLISECONDS) return null
        MetaDao.put(db, LAST_HEALTH_AT_KEY, nowUnixMillis.toString())
        return buildJsonObject {
            put("dbBytesBucket", UsageBuckets.dbBytes(dbBytes))
            put("sessionsBucket", UsageBuckets.sessions(sessionCount))
        }
    }
}
