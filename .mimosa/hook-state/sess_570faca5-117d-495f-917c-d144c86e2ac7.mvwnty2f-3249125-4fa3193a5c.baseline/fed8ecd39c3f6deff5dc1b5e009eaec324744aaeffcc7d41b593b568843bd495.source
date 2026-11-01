package com.jkdc.app.data

import com.jkdc.app.core.CompletionMark
import com.jkdc.app.core.ExpressionState
import com.jkdc.app.core.LessonState
import com.jkdc.app.core.Preferences
import com.jkdc.app.core.SessionRecord
import com.jkdc.app.core.SkillError

/**
 * The read surface phase C wires into the runtime (the learning-store
 * cutover). Everything delegates to the DAOs, so list order is first-insertion
 * order (rowid; fragments by sort_index) unless a semantic orders otherwise
 * ([dueExpressions] is oldest-due-first, RecallEngine.due's order), and legacy
 * NULL-language rows are treated exactly like RecallEngine treats them:
 * visible in every language.
 *
 * [preferences] never returns null: a fresh database reads as the default
 * Preferences(), the same projection ArchiveMapper.export pins. The rows are
 * the durable form; [ArchiveMapper.export] rebuilds the whole Archive struct
 * when a full archive (backup, merge, encode) is what a caller needs.
 */
object LearningDbReader {
    fun allSessions(db: SqlDatabase): List<SessionRecord> = SessionsDao.all(db)

    fun sessionsFor(db: SqlDatabase, languageID: String): List<SessionRecord> =
        SessionsDao.byLanguage(db, languageID)

    /** Schema v1 projection: bars = 0 and dueAt = 0.0 (lesson_state stores
        attempts and last_seen only). */
    fun lessons(db: SqlDatabase): List<LessonState> = LessonStateDao.all(db)

    fun expressionsFor(db: SqlDatabase, languageID: String): List<ExpressionState> =
        ExpressionsDao.byLanguage(db, languageID)

    fun dueExpressions(db: SqlDatabase, languageID: String, now: Double): List<ExpressionState> =
        ExpressionsDao.due(db, languageID, now)

    fun completions(db: SqlDatabase): List<CompletionMark> = CompletionsDao.all(db)

    fun skillErrors(db: SqlDatabase): List<SkillError> = SkillErrorsDao.all(db)

    fun preferences(db: SqlDatabase): Preferences = PreferencesDao.get(db) ?: Preferences()

    /** The cached mastery projection (Archive.masteryCache's durable form). */
    fun masteryMeta(db: SqlDatabase): Map<String, Int> = MetaDao.mastery(db)
}
