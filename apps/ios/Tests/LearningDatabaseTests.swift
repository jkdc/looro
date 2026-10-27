import XCTest
@testable import LearningCore

/// The learning schema must open verbatim through the shared statement list,
/// and the GRDB backend must honor the `SqlDatabase` contract — the Swift
/// mirror of Android's `LearningDbTest` (opener) and `JvmSqlDatabase`
/// behavior (conflict strategies, counts, transactions, null-arg rejection).
final class LearningDatabaseTests: XCTestCase {
    private var db: SqlDatabase!

    override func setUpWithError() throws {
        db = try LearningDatabase.openInMemory()
    }

    override func tearDown() {
        db?.close()
        db = nil
    }

    // MARK: opener

    func testSchemaOpensAtVersionSevenWithExactlyThePinnedTables() throws {
        XCTAssertEqual(Int64(7), try db.query("PRAGMA user_version").first!.long("user_version"))
        let tables = try db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .map { $0.text("name") }
        // fragments_fts is the pinned v2 virtual table; fragments_fts_* are its
        // FTS4 shadow tables (SQLite internals, like sqlite_sequence).
        XCTAssertEqual(["assessment_tickets", "completions", "exam_answers", "exam_attempts",
                        "exam_results", "exam_snapshot_ids", "exam_snapshots", "exam_sync_outbox",
                        "expressions", "fragments",
                        "fragments_fts", "fragments_fts_docsize", "fragments_fts_segdir",
                        "fragments_fts_segments", "fragments_fts_stat",
                        "lesson_state", "meta", "preferences", "progress_recovery", "progress_sync_state",
                        "remote_progress_rollups", "sessions", "skill_errors",
                        "usage_outbox"],
                       tables)
        XCTAssertFalse(tables.contains("words"),
                       "words must never be stored; hidden words live in preferences")
        let virtual = try db.query(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'fragments_fts'").first!
        // FTS4 — not FTS5 — because Android's framework SQLite enables the
        // FTS3/FTS4 module only (GRDB's bundled SQLite ships both, so this
        // assertion is what keeps a future edit from regressing to FTS5).
        XCTAssertTrue(virtual.text("sql").contains("USING fts4"))
        XCTAssertTrue(virtual.text("sql").contains("content='fragments'"))
        XCTAssertTrue(virtual.text("sql").contains("tokenize='unicode61'"))
        XCTAssertFalse(virtual.text("sql").contains("fts5"),
                       "FTS5 does not exist in the Android framework build")
        XCTAssertEqual(["assessment_tickets_owner_insert", "completions_owner_insert",
                        "exam_answers_owner_insert", "exam_attempts_owner_insert", "exam_results_owner_insert",
                        "exam_snapshot_ids_no_update", "exam_snapshots_no_update", "exam_sync_conflicts_owner_insert",
                        "exam_sync_outbox_owner_insert", "expressions_owner_insert", "fragments_fts_after_insert",
                        "fragments_fts_after_update",
                        "fragments_fts_before_delete", "fragments_fts_before_update", "fragments_owner_insert",
                        "lesson_state_owner_insert", "preferences_owner_insert", "progress_sync_state_owner_insert",
                        "remote_progress_rollups_owner_insert", "sessions_owner_insert", "skill_errors_owner_insert",
                        "usage_outbox_owner_insert"],
                       try db.query("SELECT name FROM sqlite_master WHERE type = 'trigger' ORDER BY name")
                           .map { $0.text("name") })
    }

    func testFreshIndexStaysInSyncThroughTheTriggers() throws {
        _ = try db.insert("sessions", ["id": "s1", "language_id": "es", "started_at": 1.0, "title": "T"])
        _ = try db.insert("fragments", ["id": "f1", "session_id": "s1", "sort_index": 0,
                                        "speaker": "user", "text": "Humo de prueba", "revision": 0,
                                        "start_ms": 0, "end_ms": 1, "received_at": 1.0])
        XCTAssertEqual(Int64(1), try ftsCount("humo"))
        // an edit reindexes both directions (delete + insert trigger)
        _ = try db.update("UPDATE fragments SET text = 'Cambio limpio' WHERE id = 'f1'")
        XCTAssertEqual(Int64(0), try ftsCount("humo"))
        XCTAssertEqual(Int64(1), try ftsCount("limpio"))
        // the cascade delete takes the index entry with the row
        try db.exec("DELETE FROM sessions")
        XCTAssertEqual(Int64(0), try ftsCount("limpio"))
    }

    func testOwnerlessRowsBackfillOnceAndAccountSwitchHidesPriorPreferences() throws {
        try PreferencesDao.put(db, Preferences())
        try LearnerOwnershipDao.activate(db, identity: "member:first-account")
        XCTAssertEqual("member:first-account", try db.query("SELECT owner_key FROM preferences").first!.text("owner_key"))
        XCTAssertNotNil(try PreferencesDao.get(db))

        try LearnerOwnershipDao.activate(db, identity: "member:later-account")
        XCTAssertNil(try PreferencesDao.get(db))
        XCTAssertEqual("member:first-account", try db.query("SELECT owner_key FROM preferences").first!.text("owner_key"))
    }

    private func ftsCount(_ term: String) throws -> Int64 {
        return try db.query("SELECT COUNT(*) AS n FROM fragments_fts WHERE fragments_fts MATCH ?",
                            args: [term]).first!.long("n")
    }

    func testUpgradeFromV1KeepsTranscriptsSearchableAndConvergesOnTheFreshInventory() throws {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("learningdb-upgrade-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent(LearningDatabase.name).path
        // the session id is a UUID string on this platform (the DAO re-parses it)
        let sessionID = "11111111-1111-1111-1111-111111111111"

        // a real shipped-v1 database, with a transcript and no FTS to search it
        let v1 = try LearningDatabase.openV1(at: path)
        XCTAssertEqual(Int64(1), try v1.query("PRAGMA user_version").first!.long("user_version"))
        _ = try v1.insert("sessions", ["id": sessionID, "language_id": "es", "started_at": 1.0,
                                       "title": "Historia"])
        _ = try v1.insert("fragments", ["id": "f1", "session_id": sessionID, "sort_index": 0,
                                        "speaker": "user", "text": "palabra heredada", "revision": 0,
                                        "start_ms": 0, "end_ms": 1, "received_at": 1.0])
        XCTAssertThrowsError(try v1.query("SELECT rowid FROM fragments_fts WHERE fragments_fts MATCH 'heredada'"))
        v1.close()

        // reopening with the current opener migrates in place: same version,
        // same objects in sqlite_master, and the OLD text is searchable
        let migrated = try LearningDatabase.open(at: path)
        XCTAssertEqual(Int64(LearningDatabase.version),
                       try migrated.query("PRAGMA user_version").first!.long("user_version"))
        let hits = try SearchDao.search(migrated, "heredada", languageID: "es")
        XCTAssertEqual([sessionID], hits.map { $0.session.id.uuidString })
        // a NEW write lands in the index too (the insert trigger)
        _ = try migrated.insert("fragments", ["id": "f2", "session_id": sessionID, "sort_index": 1,
                                              "speaker": "assistant", "text": "respuesta nueva",
                                              "revision": 0, "start_ms": 1, "end_ms": 2, "received_at": 2.0])
        XCTAssertEqual([sessionID], try SearchDao.search(migrated, "respuesta", languageID: "es")
            .map { $0.session.id.uuidString })

        let fresh = try LearningDatabase.openInMemory()
        func inventory(_ target: SqlDatabase) throws -> [String] {
            return try target.query(
                "SELECT type, name, COALESCE(sql, '') AS sql FROM sqlite_master "
                    + "WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name")
                .map { "\($0.text("type"))|\($0.text("name"))|\($0.text("sql"))" }
        }
        XCTAssertEqual(try inventory(fresh), try inventory(migrated))
        migrated.close()
        fresh.close()
    }

    func testUpgradeRejectsVersionsItCannotReach() throws {
        XCTAssertThrowsError(try LearningDatabase.upgrade(db, from: 0)) { error in
            XCTAssertEqual(LearningDatabaseError.noUpgradePath(found: 0, want: 6),
                           error as? LearningDatabaseError)
        }
        XCTAssertThrowsError(try LearningDatabase.upgrade(db, from: LearningDatabase.version)) { error in
            XCTAssertEqual(LearningDatabaseError.noUpgradePath(found: 6, want: 6),
                           error as? LearningDatabaseError)
        }
    }

    func testEverySupportedPriorVersionUpgradesToTheFreshInventory() throws {
        func inventory(_ target: SqlDatabase) throws -> [String] {
            try target.query("SELECT type, name, COALESCE(sql, '') AS sql FROM sqlite_master " +
                             "WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name")
                .map { "\($0.text("type"))|\($0.text("name"))|\($0.text("sql"))" }
        }
        let fresh = try LearningDatabase.openInMemory()
        let expected = try inventory(fresh)
        for version in 1..<LearningDatabase.version {
            let directory = URL(fileURLWithPath: NSTemporaryDirectory())
                .appendingPathComponent("learningdb-v\(version)-\(UUID().uuidString)")
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: directory) }
            let path = directory.appendingPathComponent(LearningDatabase.name).path
            let old = try LearningDatabase.openV1(at: path)
            if version > 1 {
                for nextVersion in 2...version {
                    for statement in SchemaData.schemaUpgrades[nextVersion] ?? [] {
                        try old.exec(statement)
                    }
                    try old.exec("PRAGMA user_version = \(nextVersion)")
                }
            }
            try LearningDatabase.upgrade(old, from: version)
            XCTAssertEqual(expected, try inventory(old), "upgrade from v\(version)")
            old.close()
        }
        fresh.close()
    }

    func testReopeningRestoredDatabaseRebindsTheAuthenticatedOwnerBeforeProjection() throws {
        let db = try LearningDatabase.openInMemory()
        try LearnerOwnershipDao.activate(db, identity: "member:backup-owner")
        var session = SessionRecord(languageID: "en", title: "Backup owner's session")
        session.id = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!
        session.startedAt = Date(timeIntervalSince1970: 10)
        try SessionsDao.upsert(db, session)

        let restored = try LearningDbStore.open(database: db, activeIdentity: "member:authenticated-owner")

        XCTAssertEqual("member:authenticated-owner", try LearnerOwnershipDao.activeIdentity(db))
        XCTAssertTrue(try restored.loadInitialArchive().sessions.isEmpty)
    }

    func testPageAndCursorRollbackTogether() throws {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("progress-page-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent(LearningDatabase.name).path
        var target = try LearningDatabase.open(at: path)
        let row = RemoteProgressRollup(ownerAccountID: "owner-a", languageID: "es", kind: "exercise",
                                       itemID: "item-a", firstAtMilliseconds: 100, lastAtMilliseconds: 200,
                                       attempts: 2, bestScore: 40, sourceVersion: 2)
        XCTAssertThrowsError(try LearningDbWriter.remoteProgressPageApplied(target, ownerAccountID: "owner-a",
            rollups: [row], nextCursor: "cursor-1", passGeneration: -1, updatedAtMilliseconds: 300))
        XCTAssertTrue(try RemoteProgressRollupsDao.all(target, ownerAccountID: "owner-a").isEmpty)
        XCTAssertNil(try ProgressSyncStateDao.get(target, ownerAccountID: "owner-a", direction: "download"))

        try LearningDbWriter.remoteProgressPageApplied(target, ownerAccountID: "owner-a", rollups: [row],
            nextCursor: "cursor-1", passGeneration: 1, updatedAtMilliseconds: 300)
        target.close()
        target = try LearningDatabase.open(at: path)
        XCTAssertEqual([row], try RemoteProgressRollupsDao.all(target, ownerAccountID: "owner-a"))
        XCTAssertEqual("cursor-1", try ProgressSyncStateDao.get(target, ownerAccountID: "owner-a",
                                                                  direction: "download")?.cursor)
        target.close()
    }

    func testUncertainAckReplaysWithoutStarvation() throws {
        let row = RemoteProgressRollup(ownerAccountID: "owner-a", languageID: "es", kind: "exercise",
                                       itemID: "item-a", firstAtMilliseconds: 100, lastAtMilliseconds: 200,
                                       attempts: 2, bestScore: 40, sourceVersion: 2)
        try LearningDbWriter.remoteProgressPageApplied(db, ownerAccountID: "owner-a", rollups: [row],
            nextCursor: "page-1", passGeneration: 1, updatedAtMilliseconds: 300)
        for _ in 0..<3 { try RemoteProgressRollupsDao.merge(db, row) }
        XCTAssertEqual(1, try RemoteProgressRollupsDao.all(db, ownerAccountID: "owner-a").count)
        XCTAssertNil(try ProgressSyncStateDao.get(db, ownerAccountID: "owner-a", direction: "upload"))
        try LearningDbWriter.remoteProgressUploadAcknowledged(db, ownerAccountID: "owner-a",
            nextCursor: "upload-1", passGeneration: 1, updatedAtMilliseconds: 400)
        XCTAssertEqual("upload-1", try ProgressSyncStateDao.get(db, ownerAccountID: "owner-a",
                                                                 direction: "upload")?.cursor)
        XCTAssertEqual(row, try RemoteProgressRollupsDao.all(db, ownerAccountID: "owner-a").first)
        try RemoteProgressRollupsDao.merge(db, RemoteProgressRollup(ownerAccountID: "owner-b", languageID: "es",
            kind: "exercise", itemID: "item-a", firstAtMilliseconds: 1, lastAtMilliseconds: 900,
            attempts: 9, bestScore: 99, sourceVersion: 2))
        XCTAssertEqual([row], try RemoteProgressRollupsDao.forLanguage(db, ownerAccountID: "owner-a", languageID: "es"))
    }

    func testUpgradeFromV5PreservesExamOutboxAndConflicts() throws {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("learningdb-v5-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent(LearningDatabase.name).path
        let old = try LearningDatabase.openV1(at: path)
        for version in 2...5 {
            for statement in SchemaData.schemaUpgrades[version] ?? [] { try old.exec(statement) }
            try old.exec("PRAGMA user_version = \(version)")
        }
        _ = try old.insert("exam_snapshots", ["content_id": "content-a", "content_hash": "hash-a",
            "schema_version": 1, "content_revision": 1, "grading_version": 1, "source": "bundled",
            "language_id": "es", "level": "A1", "week_id": 1, "content_json": "{}", "created_at": 1.0])
        _ = try old.insert("exam_snapshot_ids", ["exam_id": "exam-a", "content_id": "content-a", "created_at": 1.0])
        _ = try old.insert("exam_attempts", ["attempt_id": "attempt-a", "exam_id": "exam-a", "owner_account_id": "owner-a",
            "state": "completed", "sync_state": "conflict", "position": 0, "revision": 1, "started_at": 1.0,
            "updated_at": 2.0, "completed_at": 2.0])
        _ = try old.insert("exam_sync_outbox", ["event_id": "event-a", "attempt_id": "attempt-a", "revision": 1,
            "event_type": "completed", "payload_json": "{}", "delivery_state": "pending", "created_at": 2.0])
        _ = try old.insert("exam_sync_conflicts", ["attempt_id": "attempt-a", "owner_account_id": "owner-a",
            "server_revision": 2, "server_attempt_json": "{}", "local_attempt_json": "{}", "received_at": 3.0,
            "resolution": "takeover", "resolved_at": 4.0])
        _ = try old.insert("completions", ["kind": "exercise", "item_id": "pre-v6", "at": 1_700_000_000.0,
                                             "best_score": 40, "language_id": "es"])
        _ = try old.insert("lesson_state", ["lesson_id": "legacy-lesson", "attempts": 1,
                                              "last_seen": 1_700_000_000.0])
        try LearningDatabase.upgrade(old, from: 5)
        XCTAssertEqual("pending", try old.query("SELECT delivery_state FROM exam_sync_outbox WHERE event_id = 'event-a'")
            .first!.text("delivery_state"))
        XCTAssertEqual("takeover", try old.query("SELECT resolution FROM exam_sync_conflicts WHERE attempt_id = 'attempt-a'")
            .first!.text("resolution"))
        XCTAssertEqual(1_700_000_000, try old.query("SELECT at FROM completions WHERE item_id = 'pre-v6'")
            .first!.real("at"), accuracy: 0)
        XCTAssertEqual(1_700_000_000, try old.query("SELECT last_seen FROM lesson_state WHERE lesson_id = 'legacy-lesson'")
            .first!.real("last_seen"), accuracy: 0)
        old.close()

        let reopened = try LearningDatabase.open(at: path)
        XCTAssertEqual(1_700_000_000, try reopened.query("SELECT at FROM completions WHERE item_id = 'pre-v6'")
            .first!.real("at"), accuracy: 0)
        XCTAssertNil(try reopened.query("SELECT at_source FROM completions WHERE item_id = 'pre-v6'")
            .first!.textOrNull("at_source"))
        XCTAssertEqual(1_700_000_000, try reopened.query("SELECT last_seen FROM lesson_state WHERE lesson_id = 'legacy-lesson'")
            .first!.real("last_seen"), accuracy: 0)
        XCTAssertNil(try reopened.query("SELECT last_seen_source FROM lesson_state WHERE lesson_id = 'legacy-lesson'")
            .first!.textOrNull("last_seen_source"))
        XCTAssertEqual(Int64(2), try reopened.query("SELECT COUNT(*) AS n FROM progress_recovery WHERE state = 'excluded'")
            .first!.long("n"))
        reopened.close()
    }

    func testRecoveryRepairsExplicitUnixLessonEvidenceExactlyOnce() throws {
        let nowMilliseconds: Int64 = 1_800_000_000_000
        let unixSeconds = 1_750_000_000.125
        let expectedAppleSeconds = 771_692_800.125
        try LearnerOwnershipDao.activate(db, identity: "member:owner-a")
        try LessonStateDao.upsert(db, LessonState(lessonID: "untagged-legacy", attempts: 1,
            lastSeen: unixSeconds))
        try LessonStateDao.upsert(db, LessonState(lessonID: "evidenced-legacy", attempts: 1,
            lastSeen: unixSeconds), evidence: TimestampEvidence(source: "ios.recordLessonAttempt",
                sourceVersion: 1, unit: "unix_seconds"))
        try LessonStateDao.upsert(db, LessonState(lessonID: "valid-current", attempts: 1,
            lastSeen: expectedAppleSeconds))

        try runRecovery(db, nowMilliseconds)
        try runRecovery(db, nowMilliseconds + 1)

        let untagged = try db.query("SELECT last_seen, last_seen_source, last_seen_source_version, last_seen_unit " +
            "FROM lesson_state WHERE lesson_id = 'untagged-legacy'").first!
        XCTAssertEqual(unixSeconds, try untagged.real("last_seen"), accuracy: 0)
        XCTAssertNil(try untagged.textOrNull("last_seen_source"))
        let repaired = try db.query("SELECT last_seen, last_seen_source_version, last_seen_unit " +
            "FROM lesson_state WHERE lesson_id = 'evidenced-legacy'").first!
        XCTAssertEqual(expectedAppleSeconds, try repaired.real("last_seen"), accuracy: 0.0001)
        XCTAssertEqual(1, try repaired.int("last_seen_source_version"))
        XCTAssertEqual("unix_seconds", try repaired.textOrNull("last_seen_unit"))
        XCTAssertEqual(["evidenced-legacy", "valid-current"],
            try LessonStateDao.syncEligible(db, nowUnixMilliseconds: nowMilliseconds).map(\.lessonID).sorted())
        XCTAssertEqual(Int64(1), try db.query("SELECT COUNT(*) AS n FROM progress_recovery WHERE " +
            "source_table = 'lesson_state' AND source_key = 'evidenced-legacy' AND state = 'repaired'")
            .first!.long("n"))
    }

    func testAmbiguousTimestampIsRetainedAndValidRowsStillSync() throws {
        _ = try db.insert("completions", ["kind": "exercise", "item_id": "ambiguous", "at": 123.0,
                                           "best_score": 40, "language_id": "es"])
        try LearningDbWriter.progressRecoveryRecorded(db, ProgressRecoveryRecord(ownerAccountID: nil,
            sourceTable: "completions", sourceKey: "ambiguous", sourceVersion: 1, state: "unresolved",
            reason: "missing source version evidence", updatedAtMilliseconds: 500))
        try LearningDbWriter.remoteProgressPageApplied(db, ownerAccountID: "owner-a", rollups: [
            RemoteProgressRollup(ownerAccountID: "owner-a", languageID: "es", kind: "exercise", itemID: "valid",
                firstAtMilliseconds: 100, lastAtMilliseconds: 200, attempts: 2, bestScore: 90, sourceVersion: 2)],
            nextCursor: nil, passGeneration: 1, updatedAtMilliseconds: 500)
        XCTAssertEqual(Int64(123), try db.query("SELECT CAST(at AS INTEGER) AS at FROM completions WHERE item_id = 'ambiguous'")
            .first!.long("at"))
        XCTAssertEqual(["valid"], try RemoteProgressRollupsDao.forLanguage(db, ownerAccountID: "owner-a", languageID: "es")
            .map(\.itemID))
    }

    func testNewLocalCompletionPersistsTimestampEvidenceWithoutRelabelingOldRows() throws {
        let columns = try db.query("PRAGMA table_info(completions)").map { try $0.text("name") }
        XCTAssertTrue(Set(["at_source", "at_source_version", "at_unit"]).isSubset(of: Set(columns)),
                      "completion timestamp provenance columns are required")

        let legacy = CompletionMark(kind: "exercise", itemID: "legacy", at: 700_000_000,
                                    bestScore: 40, languageID: "es")
        let current = CompletionMark(kind: "exercise", itemID: "current", at: 800_000_000,
                                     bestScore: 90, languageID: "es")
        _ = try CompletionsDao.add(db, legacy)
        try LearningDbWriter.completions(db, [legacy, current])

        let rows = try db.query("SELECT item_id, at, at_source, at_source_version, at_unit FROM completions ORDER BY item_id")
        let old = try XCTUnwrap(rows.first { try $0.text("item_id") == "legacy" })
        XCTAssertEqual(700_000_000, try old.real("at"), accuracy: 0)
        XCTAssertNil(try old.textOrNull("at_source"))
        let new = try XCTUnwrap(rows.first { try $0.text("item_id") == "current" })
        XCTAssertEqual("ios.recordCompletion", try new.text("at_source"))
        XCTAssertEqual(2, try new.int("at_source_version"))
        XCTAssertEqual("apple_reference_seconds", try new.text("at_unit"))

        let lessonMark = CompletionMark(kind: "lesson", itemID: "lesson-a", at: 900_000_000,
                                        bestScore: 0, languageID: "es")
        try LearningDbWriter.lessonAttempt(db, lesson: LessonState(lessonID: "lesson-a", attempts: 1,
            lastSeen: 900_000_000), marks: [lessonMark], seeds: [])
        let lesson = try db.query("SELECT last_seen_source, last_seen_source_version, last_seen_unit " +
                                  "FROM lesson_state WHERE lesson_id = 'lesson-a'").first!
        XCTAssertEqual("ios.recordLessonAttempt", try lesson.text("last_seen_source"))
        XCTAssertEqual(3, try lesson.int("last_seen_source_version"))
        XCTAssertEqual("apple_reference_seconds", try lesson.text("last_seen_unit"))
    }

    func testRecoverySecondRunIsIdempotent() throws {
        let record = ProgressRecoveryRecord(ownerAccountID: "owner-a", sourceTable: "completions",
            sourceKey: "item-a", sourceVersion: 2, state: "unresolved", reason: "ambiguous timestamp",
            updatedAtMilliseconds: 500)
        try LearningDbWriter.progressRecoveryRecorded(db, record)
        try LearningDbWriter.progressRecoveryRecorded(db, record)
        XCTAssertEqual(Int64(1), try db.query("SELECT COUNT(*) AS n FROM progress_recovery").first!.long("n"))
        XCTAssertEqual("unresolved", try db.query("SELECT state FROM progress_recovery").first!.text("state"))
    }

    func testRecoveryExcludesInvalidTimestampsWithoutRewritingLegacyValues() throws {
        let now = Int64(Date.now.timeIntervalSince1970 * 1000)
        let valid = CompletionMark(kind: "exercise", itemID: "valid", at: Date.now.timeIntervalSinceReferenceDate,
                                   bestScore: 40, languageID: "es")
        let invalidLegacy = CompletionMark(kind: "exercise", itemID: "invalid-legacy", at: 1_700_000_000,
                                           bestScore: 40, languageID: "es")
        let invalidCurrent = CompletionMark(kind: "exercise", itemID: "invalid-current", at: 1_700_000_000,
                                            bestScore: 90, languageID: "es")
        _ = try CompletionsDao.add(db, invalidLegacy)
        try LearningDbWriter.completions(db, [invalidLegacy, invalidCurrent, valid])
        try LearningDbWriter.remoteProgressPageApplied(db, ownerAccountID: "owner-a", rollups: [
            RemoteProgressRollup(ownerAccountID: "owner-a", languageID: "es", kind: "exercise",
                itemID: "remote-valid", firstAtMilliseconds: now, lastAtMilliseconds: now,
                attempts: 1, bestScore: 90, sourceVersion: 2)], nextCursor: nil, passGeneration: 1,
            updatedAtMilliseconds: now)

        try LearningDbWriter.progressRecoveryRun(db, nowUnixMilliseconds: now)
        try LearningDbWriter.progressRecoveryRun(db, nowUnixMilliseconds: now + 1)

        let values = try db.query("SELECT item_id, at FROM completions ORDER BY item_id")
            .reduce(into: [String: Double]()) { $0[$1.text("item_id")] = $1.real("at") }
        XCTAssertEqual(1_700_000_000, values["invalid-legacy"] ?? 0, accuracy: 0)
        XCTAssertEqual(1_700_000_000, values["invalid-current"] ?? 0, accuracy: 0)
        XCTAssertEqual(Set(["valid"]), Set(try CompletionsDao.syncEligible(db, nowUnixMilliseconds: now).map(\.itemID)))
        XCTAssertEqual([Int64(0), Int64(2)], try db.query("SELECT source_version FROM progress_recovery " +
            "WHERE state = 'excluded' ORDER BY source_version").map { $0.long("source_version") })
        XCTAssertEqual(["remote-valid"], try RemoteProgressRollupsDao.forLanguage(db, ownerAccountID: "owner-a",
            languageID: "es").map(\.itemID))
    }

    func testRecoveryMarkerFailureRollsBackAndReplayIsIdempotent() throws {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("progress-recovery-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent(LearningDatabase.name).path
        var target = try LearningDatabase.open(at: path)
        let now = Int64(Date.now.timeIntervalSince1970 * 1000)
        let good = CompletionMark(kind: "exercise", itemID: "good", at: Date.now.timeIntervalSinceReferenceDate,
                                  bestScore: 1, languageID: "es")
        let invalidLegacy = CompletionMark(kind: "exercise", itemID: "invalid-legacy", at: 1_700_000_000,
                                           bestScore: 1, languageID: "es")
        let invalidCurrent = CompletionMark(kind: "exercise", itemID: "invalid-current", at: 1_700_000_000,
                                            bestScore: 2, languageID: "es")
        _ = try CompletionsDao.add(target, invalidLegacy)
        try LearningDbWriter.completions(target, [invalidLegacy, invalidCurrent, good])
        let unixSeconds = 1_750_000_000.125
        try LessonStateDao.upsert(target, LessonState(lessonID: "evidenced-legacy", attempts: 1,
            lastSeen: unixSeconds), evidence: TimestampEvidence(source: "ios.recordLessonAttempt",
                sourceVersion: 1, unit: "unix_seconds"))
        try target.exec("CREATE TRIGGER abort_recovery BEFORE INSERT ON progress_recovery " +
            "WHEN NEW.source_version = 1 BEGIN SELECT RAISE(ABORT, 'injected recovery failure'); END")
        XCTAssertThrowsError(try LearningDbWriter.progressRecoveryRun(target, nowUnixMilliseconds: now))
        XCTAssertEqual(Int64(0), try target.query("SELECT COUNT(*) AS n FROM progress_recovery").first!.long("n"))
        XCTAssertEqual(unixSeconds, try target.query(
            "SELECT last_seen FROM lesson_state WHERE lesson_id = 'evidenced-legacy'").first!.real("last_seen"), accuracy: 0)
        target.close()

        target = try LearningDatabase.open(at: path)
        XCTAssertEqual(1_700_000_000, try target.query("SELECT at FROM completions WHERE item_id = 'invalid-legacy'")
            .first!.real("at"), accuracy: 0)
        try target.exec("DROP TRIGGER abort_recovery")
        try LearningDbWriter.progressRecoveryRun(target, nowUnixMilliseconds: now)
        XCTAssertEqual(771_692_800.125, try target.query(
            "SELECT last_seen FROM lesson_state WHERE lesson_id = 'evidenced-legacy'").first!.real("last_seen"), accuracy: 0.0001)
        XCTAssertEqual("repaired", try target.query("SELECT state FROM progress_recovery WHERE " +
            "source_table = 'lesson_state' AND source_key = 'evidenced-legacy'").first!.text("state"))
        let first = try target.query("SELECT source_version, state FROM progress_recovery ORDER BY source_version")
            .map { "\($0.long("source_version")):\($0.text("state"))" }
        try LearningDbWriter.progressRecoveryRun(target, nowUnixMilliseconds: now + 1)
        XCTAssertEqual(first, try target.query("SELECT source_version, state FROM progress_recovery ORDER BY source_version")
            .map { "\($0.long("source_version")):\($0.text("state"))" })
        target.close()
    }

    func testProjectionDoesNotAppendLocalEvents() throws {
        _ = try db.insert("completions", ["kind": "exercise", "item_id": "item-a", "at": 1.0,
                                           "best_score": 40, "language_id": "es"])
        let before = try db.query("SELECT kind, item_id, at, best_score FROM completions ORDER BY rowid")
        let input = [ProgressAggregate(ownerAccountID: "owner-a", languageID: "es", kind: "exercise",
                                        itemID: "item-a", firstAt: 100, lastAt: 200, attempts: 1, bestScore: 40)]
        let projected = ProgressProjection.merge(ownerAccountID: "owner-a", languageID: "es", local: input,
            remote: [ProgressAggregate(ownerAccountID: "owner-a", languageID: "es", kind: "exercise",
                                       itemID: "item-a", firstAt: 50, lastAt: 300, attempts: 3, bestScore: 90)])
        XCTAssertEqual(90, projected.first?.bestScore)
        XCTAssertEqual(before.map { "\($0.text("kind"))|\($0.text("item_id"))|\($0.real("at"))|\($0.int("best_score"))" },
                       try db.query("SELECT kind, item_id, at, best_score FROM completions ORDER BY rowid")
                           .map { "\($0.text("kind"))|\($0.text("item_id"))|\($0.real("at"))|\($0.int("best_score"))" })
    }

    func testForeignKeysAreEnforcedOnTheConnection() throws {
        XCTAssertEqual(Int64(1), try db.query("PRAGMA foreign_keys").first!.long("foreign_keys"))
    }

    func testForeignKeysRejectOrphanFragments() throws {
        XCTAssertThrowsError(try db.insert("fragments", [
            "id": "f1", "session_id": "missing", "sort_index": 0, "speaker": "user",
            "text": "Hola", "revision": 0, "start_ms": 0, "end_ms": 100, "received_at": 1.0,
        ]))
    }

    func testAFileDatabasePersistsAndNeverDowngrades() throws {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("learningdb-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent(LearningDatabase.name).path

        let fileDb = try LearningDatabase.open(at: path)
        XCTAssertEqual(Int64(LearningDatabase.version),
                       try fileDb.query("PRAGMA user_version").first!.long("user_version"))
        _ = try fileDb.insert("meta", ["key": "k", "value": "persisted"])
        try fileDb.exec("PRAGMA user_version = \(LearningDatabase.version + 1)")
        // using a closed database throws (the backup/restore close-before-replace contract)
        fileDb.close()
        XCTAssertThrowsError(try fileDb.query("SELECT COUNT(*) AS n FROM meta"))
        // the bumped user_version persisted, so the reopen must refuse it
        XCTAssertThrowsError(try LearningDatabase.open(at: path)) { error in
            XCTAssertEqual(LearningDatabaseError
                               .neverDowngrades(found: LearningDatabase.version + 1,
                                                want: LearningDatabase.version),
                           error as? LearningDatabaseError)
        }
    }

    // MARK: SqlDatabase contract

    func testInsertResolvesConflictsWithTheRequestedStrategy() throws {
        _ = try db.insert("meta", ["key": "k", "value": "first"])
        XCTAssertEqual(Int64(-1), try db.insert("meta", ["key": "k", "value": "second"], strategy: .ignore))
        XCTAssertEqual("first", try db.query("SELECT value FROM meta WHERE key = 'k'").first!.text("value"))
        XCTAssertThrowsError(try db.insert("meta", ["key": "k", "value": "third"], strategy: .abort)) { error in
            // the database rejected it, not the contract
            XCTAssertFalse(error is SqlDatabaseError, "\(error)")
        }
    }

    func testInsertReturnsTheNewRowIdAndRejectsUnsupportedValues() throws {
        let rowId = try db.insert("meta", ["key": "k", "value": "first"])
        XCTAssertEqual(Int64(1), rowId)
        XCTAssertEqual("00000000-0000-0000-0000-000000000001",
                       QueuedUsageEvent.eventID(forRowId: rowId).uuidString.lowercased())
        XCTAssertThrowsError(try db.insert("meta", ["key": "k2", "value": [1, 2]])) { error in
            XCTAssertTrue(error is SqlDatabaseError, "\(error)")
            XCTAssertTrue(String(describing: error).contains("Unsupported SQLite value"), "\(error)")
        }
        // booleans store as 0/1, the schema's one boolean convention
        _ = try db.insert("completions", ["kind": "lesson", "item_id": "i", "at": 1.0, "best_score": true])
        XCTAssertEqual(Int64(1), try db.query("SELECT best_score FROM completions").first!.long("best_score"))
    }

    func testInsertCarriesNullColumnsAlongsideValues() throws {
        // the row's [String: Any?] values are optionals inside the dictionary:
        // a present-key nil must bind as SQL NULL while its neighbors bind as
        // data (a boxed Optional here would make every insert throw)
        _ = try db.insert("completions", [
            "kind": "lesson", "item_id": "es-foundations-u1-ser", "at": 100.0,
            "best_score": 90, "language_id": nil,
        ])
        let row = try db.query(
            "SELECT kind, item_id, at, best_score, language_id FROM completions").first!
        XCTAssertEqual("lesson", row.text("kind"))
        XCTAssertEqual("es-foundations-u1-ser", row.text("item_id"))
        XCTAssertEqual(100.0, row.real("at"))
        XCTAssertEqual(90, row.int("best_score"))
        XCTAssertNil(row["language_id"])
        XCTAssertNil(row.textOrNull("language_id"))
    }

    func testUpdateReportsAffectedRows() throws {
        _ = try db.insert("meta", ["key": "a", "value": "1"])
        _ = try db.insert("meta", ["key": "b", "value": "1"])
        _ = try db.insert("meta", ["key": "c", "value": "2"])
        XCTAssertEqual(Int64(2), try db.update("UPDATE meta SET value = '9' WHERE value = ?", args: ["1"]))
        XCTAssertEqual(Int64(2), try db.query("SELECT COUNT(*) AS n FROM meta WHERE value = '9'").first!.long("n"))
        XCTAssertEqual(Int64(0), try db.update("UPDATE meta SET value = '9' WHERE key = 'zz'"))
    }

    func testTransactionCommitsTheWorkDoneInsideTheBlock() throws {
        _ = try db.transaction { tx in try tx.insert("meta", ["key": "k", "value": "kept"]) }
        XCTAssertEqual("kept", try db.query("SELECT value FROM meta WHERE key = 'k'").first!.text("value"))
    }

    func testTransactionReturnsTheBlockResultEvenWhenItIsNil() throws {
        // T may itself be Optional; a nil result must come back as nil
        let value: String? = try db.transaction { _ -> String? in nil }
        XCTAssertNil(value)
        let kept: String? = try db.transaction { _ -> String? in "kept" }
        XCTAssertEqual("kept", kept)
    }

    func testTransactionRollsBackOnFailureAndRejectsNesting() throws {
        struct Boom: Error {}
        XCTAssertThrowsError(try db.transaction { tx in
            _ = try tx.insert("meta", ["key": "k", "value": "lost"])
            throw Boom()
        })
        XCTAssertEqual(Int64(0), try count("meta"))
        XCTAssertThrowsError(try db.transaction { tx in
            try tx.transaction { _ in }
        }) { error in
            XCTAssertEqual(SqlDatabaseError.nestedTransaction, error as? SqlDatabaseError)
        }
        XCTAssertEqual(Int64(0), try count("meta"))
    }

    func testNullBindArgumentsAreRejectedOnQueryAndUpdate() throws {
        _ = try db.insert("meta", ["key": "k", "value": "v"])
        XCTAssertThrowsError(try db.query("SELECT value FROM meta WHERE key != ?", args: [nil])) { error in
            XCTAssertEqual(SqlDatabaseError.nullBindArgument(position: 1), error as? SqlDatabaseError)
        }
        XCTAssertThrowsError(try db.update("UPDATE meta SET value = 'x' WHERE key != ?", args: [nil])) { error in
            XCTAssertEqual(SqlDatabaseError.nullBindArgument(position: 1), error as? SqlDatabaseError)
        }
        // inserts still carry null values: that is column data, not a comparison
        _ = try db.insert("completions", ["kind": "lesson", "item_id": "i1", "at": 1.0, "language_id": nil])
        XCTAssertEqual(Int64(1), try count("completions"))
    }

    private func count(_ table: String) throws -> Int64 {
        return try db.query("SELECT COUNT(*) AS n FROM \(table)").first!.long("n")
    }
}
