package com.jkdc.app.data

import com.jkdc.app.core.Archive
import com.jkdc.app.core.Preferences
import kotlinx.serialization.json.Json

/**
 * The Json configuration ArchiveCodec uses for archive payloads
 * (Models.kt): encodeDefaults keeps every field — including ones holding
 * their default value — explicit in the JSON columns, so decoding a column
 * always rebuilds the exact struct that was written instead of silently
 * re-deriving a default; ignoreUnknownKeys and prettyPrint round out the
 * identical configuration. All learning-database JSON columns go through
 * this one instance.
 */
val ArchiveJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

/**
 * Maps between the in-memory [Archive] struct and learning-database rows.
 * The structs do not change; the database is the durable form.
 *
 * [import] writes the archive inside ONE transaction — either the whole
 * archive lands or nothing does (and no session's fragment rewrite can
 * observe a half-applied import). It upserts by key without wiping tables,
 * so restoring over an existing database leaves rows the archive does not
 * mention (and the platform-local usage_outbox / assessment_tickets queues)
 * untouched; replacing vs merging at restore time is the caller's decision.
 * Mastery meta rows are the exception: the archive's masteryCache IS the
 * table's mastery content, so they are deleted and re-inserted. Merging
 * concurrent archives stays ArchiveCodec.merge's job.
 *
 * [export] rebuilds the archive with rows in first-insertion order (rowid;
 * fragments by sort_index). That order is stable for the life of the
 * database because this layer updates rows in place — INSERT OR REPLACE,
 * which reassigns rowids and would let export order drift, is never used.
 * Empty collections canonicalize to null for completions/errors/masteryCache,
 * matching ArchiveCodec's own canonical form, and lesson_state rows read
 * back with bars = 0 and dueAt = 0.0 (schema v1 does not store them).
 */
object ArchiveMapper {
    fun import(archive: Archive, db: SqlDatabase) {
        db.transaction { tx ->
            PreferencesDao.put(tx, archive.preferences)
            archive.sessions.forEach { session ->
                SessionsDao.upsert(tx, session)
                FragmentsDao.replaceInTx(tx, session.id, session.fragments)
            }
            archive.lessons.forEach { LessonStateDao.upsert(tx, it) }
            // adoptLegacy = false: an import preserves the archive verbatim, so a
            // legacy null-language expression and a language expression with the
            // same norm_key coexist as two rows instead of one adopted row.
            archive.expressions.forEach { ExpressionsDao.upsert(tx, it, adoptLegacy = false) }
            archive.completions?.forEach { CompletionsDao.add(tx, it) }
            archive.errors?.forEach { SkillErrorsDao.upsert(tx, it) }
            MetaDao.replaceMastery(tx, archive.masteryCache ?: emptyMap())
            MetaDao.ensureSchemaFlags(tx)
        }
    }

    fun export(db: SqlDatabase): Archive {
        val completions = CompletionsDao.all(db)
        val errors = SkillErrorsDao.all(db)
        val mastery = MetaDao.mastery(db)
        return Archive(
            schemaVersion = 2,
            sessions = SessionsDao.all(db).toMutableList(),
            preferences = PreferencesDao.get(db) ?: Preferences(),
            lessons = LessonStateDao.all(db).toMutableList(),
            expressions = ExpressionsDao.all(db).toMutableList(),
            completions = completions.takeIf { it.isNotEmpty() }?.toMutableList(),
            errors = errors.takeIf { it.isNotEmpty() }?.toMutableList(),
            masteryCache = mastery.takeIf { it.isNotEmpty() },
        )
    }
}
