package com.jkdc.app.data

import com.jkdc.app.core.Archive
import com.jkdc.app.core.ArchiveCodec

/**
 * DB-side archive merge: applies an incoming [Archive] onto the database with
 * EXACTLY ArchiveCodec.merge's semantics — by construction, because the codec
 * itself performs the merge.
 *
 * Implementation choice (the phase-B contract asks for it documented): export
 * the current rows as an archive, run the real [ArchiveCodec.merge] in memory,
 * then rewrite the database to the merged archive inside ONE transaction. A
 * keyed "only changed rows" diff cannot reach every merged state:
 *
 *  - merge fuses same-key expressions into an ExpressionState built WITHOUT a
 *    languageID, so a fused ("es", norm) row becomes a legacy NULL-language
 *    row — its (norm_key, language_id) identity moves to a different key,
 *    which a keyed upsert cannot follow (it would insert a second row and
 *    leave the stale one behind; ArchiveMapper.import's no-wipe upsert is
 *    exactly the wrong tool for that reason).
 *  - merge re-sorts completions by `at` and errors by count (descending,
 *    stable). Archive list order is rowid order in this schema, so those
 *    tables must be physically rewritten in merge order for export to match
 *    the codec byte for byte.
 *
 * The rewrite therefore deletes and re-inserts sessions (fragments follow by
 * ON DELETE CASCADE), expressions, lesson_state, completions and skill_errors
 * in merged order, and rewrites preferences plus the mastery meta rows. The
 * schema's only foreign key is fragments→sessions; usage_outbox and
 * assessment_tickets are platform-local, unrelated by FK, and stay untouched.
 * Rowids of the rewritten tables are reassigned — nothing references them,
 * and pinning export order to merge order is the point.
 *
 * Failure semantics: merge (validate(incoming), the MAXIMUM_SESSIONS guard,
 * the final validate(out) + size check) throws ArchiveError before any write,
 * and the transaction rolls back any partial rewrite — a failed merge leaves
 * the database exactly as it was. One pathological input fails later than the
 * codec would: a merged archive reusing a fragment id across sessions passes
 * the in-memory codec but hits the fragments PRIMARY KEY here; that throws
 * SQLException and rolls back (loud, no corruption — never a silent divergence).
 *
 * Known, schema-pinned deviations (the only places where the exported rows
 * differ from decode(encode(merged))):
 *  - LessonState.bars/dueAt are not stored in schema v1 (lesson_state), so the
 *    persisted projection reads them back as 0/0.0 — the same normalization
 *    ArchiveMapper.import/export pinned in Tasks 1-2.
 *  - The completions PRIMARY KEY (kind, item_id, at) keeps only the FIRST mark
 *    of a same-instant cross-language pair, while merge's completionKey keeps
 *    both; the survivor is the first of the pair in merge order. Lifting this
 *    needs a schema decision, not a merger change.
 *
 * Returns the persisted projection (ArchiveMapper.export) after the merge, so
 * callers see exactly what the database now holds.
 */
object ArchiveMerger {
    /** RESTORE/IMPORT path only (phase D backup import, settings import).
        Never call per-turn at runtime — it rewrites all content tables; normal
        runtime writes go through the DAO upserts (phase C). */
    fun apply(db: SqlDatabase, incoming: Archive): Archive = db.transaction { tx ->
        val merged = ArchiveCodec.merge(ArchiveMapper.export(tx), incoming)
        rewriteInTx(tx, merged)
        LearnerOwnershipDao.activeIdentity(tx)?.let {
            OwnerBootstrapEligibilityDao.clearBootstrapIntentInTransaction(tx, it)
        }
        ArchiveMapper.export(tx)
    }

    private fun rewriteInTx(db: SqlDatabase, archive: Archive) {
        db.update("DELETE FROM sessions WHERE ${ownerClause(db)}", ownerArguments(db)) // fragments follow by ON DELETE CASCADE
        archive.sessions.forEach { session ->
            SessionsDao.upsert(db, session)
            FragmentsDao.replaceInTx(db, session.id, session.fragments)
        }
        // adoptLegacy = false on a wiped table: the merged archive is written
        // verbatim, exactly the coexistence ArchiveCodec.validate just accepted.
        db.update("DELETE FROM expressions WHERE ${ownerClause(db)}", ownerArguments(db))
        archive.expressions.forEach { ExpressionsDao.upsert(db, it, adoptLegacy = false) }
        db.update("DELETE FROM lesson_state WHERE ${ownerClause(db)}", ownerArguments(db))
        archive.lessons.forEach { LessonStateDao.upsert(db, it) }
        // add (insert-or-ignore) keeps the FIRST mark of a same-instant
        // cross-language pair — the schema-pinned narrowing documented above.
        db.update("DELETE FROM completions WHERE ${ownerClause(db)}", ownerArguments(db))
        archive.completions?.forEach { CompletionsDao.add(db, it) }
        db.update("DELETE FROM skill_errors WHERE ${ownerClause(db)}", ownerArguments(db))
        archive.errors?.forEach { SkillErrorsDao.upsert(db, it) }
        PreferencesDao.put(db, archive.preferences)
        MetaDao.replaceMastery(db, archive.masteryCache ?: emptyMap())
        MetaDao.ensureSchemaFlags(db)
    }
}
