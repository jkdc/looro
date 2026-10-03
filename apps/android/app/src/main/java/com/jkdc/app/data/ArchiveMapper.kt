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
 * [import] writes the archive into the tables without wiping them first —
 * restoring over an existing database upserts by key and leaves rows the
 * archive does not mention (and the platform-local usage_outbox /
 * assessment_tickets queues) untouched. Merging concurrent archives stays
 * ArchiveCodec.merge's job. It also writes the 'schema_flags' meta row and
 * one 'mastery:<key>' meta row per masteryCache entry.
 *
 * [export] rebuilds the archive from the rows in insertion order; list
 * order is preserved through rowid (fragments through sort_index). Empty
 * collections canonicalize to null for completions/errors/masteryCache,
 * matching ArchiveCodec's own canonical form, and lesson_state rows read
 * back with bars = 0 and dueAt = 0.0 (schema v1 does not store them).
 */
object ArchiveMapper {
    fun import(archive: Archive, db: SqlDatabase) {
        PreferencesDao.put(db, archive.preferences)
        archive.sessions.forEach { session ->
            SessionsDao.upsert(db, session)
            FragmentsDao.replaceBySession(db, session.id, session.fragments)
        }
        archive.lessons.forEach { LessonStateDao.upsert(db, it) }
        // adoptLegacy = false: an import preserves the archive verbatim, so a
        // legacy null-language expression and a language expression with the
        // same norm_key coexist as two rows instead of one adopted row.
        archive.expressions.forEach { ExpressionsDao.upsert(db, it, adoptLegacy = false) }
        archive.completions?.forEach { CompletionsDao.add(db, it) }
        archive.errors?.forEach { SkillErrorsDao.upsert(db, it) }
        MetaDao.putMastery(db, archive.masteryCache ?: emptyMap())
        MetaDao.ensureSchemaFlags(db)
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
