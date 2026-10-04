import Foundation

/// The JSON configuration for the archive payloads stored in the
/// learning-database JSON columns: the same encoder `Archive.encoded()` uses
/// (pretty-printed, sorted keys), so every field — including ones holding
/// their default value — stays explicit and decoding a column always rebuilds
/// the exact struct that was written instead of silently re-deriving a
/// default. The decoder ignores unknown keys, like the Kotlin ArchiveJson's
/// ignoreUnknownKeys. All learning-database JSON columns go through these
/// helpers (the mirror of the Kotlin `ArchiveJson` instance).
public enum DatabaseJSON {
    public static func encode<T: Encodable>(_ value: T) throws -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return String(data: try encoder.encode(value), encoding: .utf8)!
    }

    public static func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder().decode(type, from: Data(json.utf8))
    }
}

/// Maps between the in-memory `Archive` struct and learning-database rows.
/// The structs do not change; the database is the durable form.
///
/// `import` writes the archive inside ONE transaction — either the whole
/// archive lands or nothing does (and no session's fragment rewrite can
/// observe a half-applied import). It upserts by key without wiping tables,
/// so restoring over an existing database leaves rows the archive does not
/// mention (and the platform-local usage_outbox / assessment_tickets queues)
/// untouched; replacing vs merging at restore time is the caller's decision.
/// Mastery meta rows are the exception: the archive's masteryCache IS the
/// table's mastery content, so they are deleted and re-inserted. Merging
/// concurrent archives stays the archive merge's job.
///
/// The iOS `Archive` model does not carry `masteryCache` yet (the Kotlin one
/// does), so callers who hold the cache — the decoded fixture JSON today, the
/// model when it grows the field — pass it explicitly; the default nil is
/// exactly what the Kotlin mapper writes for an archive without a cache.
/// `export` mirrors the Kotlin mapper over the fields the iOS model has;
/// `MetaDao.mastery` reads the rows back for whoever needs them.
///
/// `export` rebuilds the archive with rows in first-insertion order (rowid;
/// fragments by sort_index). That order is stable for the life of the
/// database because this layer updates rows in place — INSERT OR REPLACE,
/// which reassigns rowids and would let export order drift, is never used.
/// Empty collections canonicalize to nil for completions/errors, matching the
/// archive's own canonical form, and lesson_state rows read back with
/// bars = 0 and dueAt = 0.0 (schema v1 does not store them).
public enum ArchiveMapper {
    public static func `import`(_ archive: Archive, _ db: SqlDatabase,
                                masteryCache: [String: Int]? = nil) throws {
        try db.transaction { tx in
            try PreferencesDao.put(tx, archive.preferences)
            for session in archive.sessions {
                try SessionsDao.upsert(tx, session)
                try FragmentsDao.replaceInTx(tx, session.id.uuidString, session.fragments)
            }
            for lesson in archive.lessons ?? [] {
                try LessonStateDao.upsert(tx, lesson)
            }
            // adoptLegacy = false: an import preserves the archive verbatim, so a
            // legacy null-language expression and a language expression with the
            // same norm_key coexist as two rows instead of one adopted row.
            for expression in archive.expressions ?? [] {
                _ = try ExpressionsDao.upsert(tx, expression, adoptLegacy: false)
            }
            for mark in archive.completions ?? [] {
                _ = try CompletionsDao.add(tx, mark)
            }
            for error in archive.errors ?? [] {
                try SkillErrorsDao.upsert(tx, error)
            }
            try MetaDao.replaceMastery(tx, masteryCache ?? [:])
            try MetaDao.ensureSchemaFlags(tx)
        }
    }

    public static func export(_ db: SqlDatabase) throws -> Archive {
        let completions = try CompletionsDao.all(db)
        let errors = try SkillErrorsDao.all(db)
        var archive = Archive()
        archive.schemaVersion = 2
        archive.sessions = try SessionsDao.all(db)
        archive.preferences = try PreferencesDao.get(db) ?? Preferences()
        archive.lessons = try LessonStateDao.all(db)
        archive.expressions = try ExpressionsDao.all(db)
        archive.completions = completions.isEmpty ? nil : completions
        archive.errors = errors.isEmpty ? nil : errors
        return archive
    }
}
