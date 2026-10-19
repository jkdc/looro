import XCTest
@testable import LearningCore

/// BackupManager on real SQLite (GRDB), file-backed like the production
/// database — the Swift mirror of Android's `BackupManagerTest`: the
/// export→wipe→import roundtrip that must hand back the original archive,
/// the validation gate (corrupt bytes, a future user_version, a database
/// without the canonical tables), the crash-safe stage-then-swap install
/// with its startup adoption, and the per-language delete whose survivors
/// pin the semantics — other languages, legacy NULL-language rows and the
/// languageless queues stay exactly where they were.
final class BackupManagerTests: XCTestCase {
    private var databases: [SqlDatabase] = []
    private var directories: [URL] = []

    override func tearDown() {
        databases.forEach { $0.close() }
        databases = []
        directories.forEach { try? FileManager.default.removeItem(at: $0) }
        directories = []
    }

    // MARK: helpers

    private func makeDirectory() throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("backup-tests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        directories.append(directory)
        return directory
    }

    /// A file-backed learning database, schema v1 — the production opener.
    private func fileBacked(_ url: URL) throws -> SqlDatabase {
        let db = try LearningDatabase.open(at: url.path)
        databases.append(db)
        return db
    }

    private func count(_ db: SqlDatabase, _ table: String) throws -> Int64 {
        try db.query("SELECT COUNT(*) AS n FROM \(table)").first!.long("n")
    }

    private func tableCounts(_ db: SqlDatabase) throws -> [String: Int64] {
        var counts: [String: Int64] = [:]
        for table in BackupManager.canonicalTables { counts[table] = try count(db, table) }
        return counts
    }

    private func assertInvalidBackup(_ issue: BackupIssue, _ body: () throws -> Void,
                                     file: StaticString = #filePath, line: UInt = #line) {
        do { try body(); XCTFail("the backup must be rejected", file: file, line: line) }
        catch let failure as InvalidBackup { XCTAssertEqual(issue, failure.issue, file: file, line: line) }
        catch { XCTFail("unexpected error \(error)", file: file, line: line) }
    }

    // Deterministic session ids, stable across the fixture and its assertions.
    private let sEN1 = UUID(uuidString: "11111111-1111-4111-8111-000000000001")!
    private let sEN2 = UUID(uuidString: "11111111-1111-4111-8111-000000000002")!
    private let sES1 = UUID(uuidString: "11111111-1111-4111-8111-000000000003")!

    /// One deterministic archive covering three languages plus the legacy
    /// NULL-language rows the delete must conserve.
    private func seededArchive() -> Archive {
        func session(_ id: UUID, _ languageID: String, fragments: Int) -> SessionRecord {
            var record = SessionRecord(languageID: languageID)
            record.id = id
            record.startedAt = Date(timeIntervalSinceReferenceDate: 800_000_000)
            record.endedAt = Date(timeIntervalSinceReferenceDate: 800_000_100)
            record.endReason = "Completed"
            for i in 0..<fragments {
                record.append(Fragment(id: "f\(i)", speaker: .user, text: "text \(i)",
                                       startMS: i * 1000, endMS: i * 1000 + 500,
                                       receivedAt: Date(timeIntervalSinceReferenceDate: 800_000_001 + Double(i))))
            }
            return record
        }
        var archive = Archive()
        archive.preferences.learningLanguageID = "en"
        archive.sessions = [
            session(sEN1, "en", fragments: 2),
            session(sEN2, "en", fragments: 1),
            session(sES1, "es", fragments: 2),
        ]
        archive.lessons = [
            LessonState(lessonID: "en-foundations-u1-to-be", attempts: 2, lastSeen: 100),
            LessonState(lessonID: "en-foundations-u1-questions", attempts: 1, lastSeen: 90),
            LessonState(lessonID: "es-foundations-u1-ser", attempts: 3, lastSeen: 80),
            LessonState(lessonID: "de-foundations-u1-sein", attempts: 1, lastSeen: 70),
            LessonState(lessonID: "zz-legacy-lesson", attempts: 4, lastSeen: 60),
        ]
        archive.expressions = [
            ExpressionState(text: "to be or not to be", meaning: "ser o no ser", source: "lesson",
                            lessonID: "en-foundations-u1-to-be", lastSeen: 100, dueAt: 200,
                            languageID: "en"),
            ExpressionState(text: "nice to meet you", meaning: "encantado de conocerte", source: "lesson",
                            lessonID: "es-foundations-u1-ser", lastSeen: 90, dueAt: 190,
                            languageID: "es"),
            // legacy: NULL language, visible and adoptable in every language
            ExpressionState(text: "ciao", meaning: "hola", source: "lookup",
                            lastSeen: 80, dueAt: 180, languageID: nil),
        ]
        archive.completions = [
            CompletionMark(kind: ProgressEngine.lesson, itemID: "en-foundations-u1-to-be",
                           at: 100, bestScore: 90, languageID: "en"),
            CompletionMark(kind: ProgressEngine.lesson, itemID: "es-foundations-u1-ser",
                           at: 99, bestScore: 80, languageID: "es"),
            CompletionMark(kind: "recall_round", itemID: "legacy-thing",
                           at: 98, bestScore: 0, languageID: nil),
        ]
        return archive
    }

    private func sampleExam(_ examID: String, _ contentID: String, _ language: String) -> ExamSnapshotRecord {
        ExamSnapshotRecord(examID: examID, contentID: contentID, contentHash: "hash-\(contentID)",
            schemaVersion: 1, contentRevision: 1, gradingVersion: 1, source: "bundled",
            languageID: language, level: "B1", weekID: 202641,
            contentJSON: "{\"question\":\"frozen\"}", createdAt: 1)
    }

    /// Imports the archive, then adds the languageless platform queues:
    /// retry-ledger rows (including an orphan whose session row does not
    /// exist — the delete's LEFT-JOIN semantics must keep it) and two
    /// usage-outbox events. Returns the archive the database was seeded from.
    @discardableResult
    private func seed(_ db: SqlDatabase) throws -> Archive {
        let archive = seededArchive()
        try ArchiveMapper.import(archive, db)
        try AssessmentTicketsDao.upsert(db, AssessmentTicketRow(sEN1.uuidString, attempts: 1, lastAttemptAt: 100.5))
        try AssessmentTicketsDao.upsert(db, AssessmentTicketRow(sES1.uuidString, attempts: 0, lastAttemptAt: nil))
        try AssessmentTicketsDao.upsert(db, AssessmentTicketRow("orphan", attempts: 2, lastAttemptAt: 5))
        try UsageOutboxDao.enqueue(db, "app_open", 1_700_000_000_000)
        try UsageOutboxDao.enqueue(db, "lesson_completed", 1_700_000_001_000)
        return archive
    }

    // MARK: export / import roundtrip

    func testExportWipeImportRoundtripReturnsTheOriginalArchive() throws {
        let directory = try makeDirectory()
        let dbURL = directory.appendingPathComponent("learning.db")
        let db = try fileBacked(dbURL)
        try seed(db)
        _ = try ExamHistoryDao.start(db, snapshot: sampleExam("backup-exam", "backup-content", "en"),
            attempt: ExamAttemptRecord(attemptID: "backup-attempt", examID: "backup-exam", startedAt: 12))
        _ = try ExamHistoryDao.saveAnswer(db, attemptID: "backup-attempt", questionID: "q1",
            responseJSON: "{\"answer\":\"saved\"}", position: 1, savedAt: 13)
        let original = try ArchiveMapper.export(db)

        // GRDB's DatabaseQueue runs rollback-journal (delete) mode by default,
        // so the export's PRAGMA wal_checkpoint(TRUNCATE) is a no-op here —
        // it does real work only on a WAL-mode database (Android's). The
        // roundtrip below is the proof the no-op path still exports whole;
        // an Android-exported WAL file is checkpointed by the same call.
        let backup = try BackupManager.exportTo(db, databaseFile: dbURL,
                                                openForVerify: BackupManager.openForVerification)
        XCTAssertEqual(backup, try Data(contentsOf: dbURL),
                       "the exported bytes are the database file's bytes")
        db.close()

        // wipe: the file is gone and a fresh empty schema takes its place
        try FileManager.default.removeItem(at: dbURL)
        let fresh = try fileBacked(dbURL)
        XCTAssertEqual(Int64(0), try count(fresh, "sessions"))
        XCTAssertEqual(Int64(0), try count(fresh, "expressions"))
        fresh.close()

        // restore: install the (already validated) bytes and reopen — the
        // store side of LearningStore.importDb
        try BackupManager.installDatabase(dbURL, bytes: backup)
        let restored = try fileBacked(dbURL)
        XCTAssertEqual(try original.encoded(), try ArchiveMapper.export(restored).encoded(),
                       "the roundtrip hands back the original archive")
        let restoredExam = try ExamHistoryDao.load(restored, attemptID: "backup-attempt")!
        XCTAssertEqual("{\"question\":\"frozen\"}", restoredExam.snapshot.text("content_json"))
        XCTAssertEqual("{\"answer\":\"saved\"}", restoredExam.answers.first!.text("response_json"))
    }

    func testExportIsDeterministicAndItsCopyPassesTheImportGate() throws {
        let directory = try makeDirectory()
        let dbURL = directory.appendingPathComponent("learning.db")
        let db = try fileBacked(dbURL)
        try seed(db)

        let first = try BackupManager.exportTo(db, databaseFile: dbURL,
                                               openForVerify: BackupManager.openForVerification)
        let second = try BackupManager.exportTo(db, databaseFile: dbURL,
                                                openForVerify: BackupManager.openForVerification)
        XCTAssertEqual(first, second, "two exports of an unchanged database are byte-identical")

        let validated = try BackupManager.readValidatedBackup(
            from: dbURL, workDirectory: directory, openForVerify: BackupManager.openForVerification)
        XCTAssertEqual(validated, first, "the gate hands back exactly the bytes it verified")
    }

    // MARK: import validation rejections

    func testImportRejectsCorruptBytes() throws {
        let directory = try makeDirectory()
        let file = directory.appendingPathComponent("corrupt.db")
        try Data("esto no es una base de datos sqlite".utf8).write(to: file)
        assertInvalidBackup(.corrupt) {
            try BackupManager.readValidatedBackup(from: file, workDirectory: directory,
                                                  openForVerify: BackupManager.openForVerification)
        }
        // A file that cannot even be opened is not a validation rejection:
        // the failure propagates raw (the Kotlin mirror's plain exception),
        // and the store maps it to the generic restore-failed notice.
        let missing = directory.appendingPathComponent("absent.db")
        XCTAssertThrowsError(try BackupManager.readValidatedBackup(
            from: missing, workDirectory: directory,
            openForVerify: BackupManager.openForVerification)) { error in
            XCTAssertFalse(error is InvalidBackup,
                           "an unreadable file is not a validation rejection")
        }
    }

    func testImportRejectsAnOversizedBackup() throws {
        let directory = try makeDirectory()
        let file = directory.appendingPathComponent("huge.db")
        try Data(count: BackupManager.maximumDatabaseBytes + 1).write(to: file)
        assertInvalidBackup(.tooLarge) {
            try BackupManager.readValidatedBackup(from: file, workDirectory: directory,
                                                  openForVerify: BackupManager.openForVerification)
        }
    }

    func testImportRejectsAFutureSchemaVersion() throws {
        let directory = try makeDirectory()
        let file = directory.appendingPathComponent("future.db")
        let db = try fileBacked(file)
        try db.exec("PRAGMA user_version = \(LearningDatabase.version + 1)")
        db.close()

        assertInvalidBackup(.futureVersion) {
            try BackupManager.readValidatedBackup(from: file, workDirectory: directory,
                                                  openForVerify: BackupManager.openForVerification)
        }
    }

    func testImportRejectsADatabaseWithoutTheCanonicalTables() throws {
        // a well-formed SQLite file at the right user_version that is not a
        // learning database: the table inventory is what catches it. The
        // tables go child-first so the schema's foreign key never objects.
        let directory = try makeDirectory()
        let file = directory.appendingPathComponent("empty.db")
        let db = try fileBacked(file)
        for table in BackupManager.canonicalTables.reversed() {
            try db.exec("DROP TABLE \(table)")
        }
        db.close()

        assertInvalidBackup(.missingTables) {
            try BackupManager.readValidatedBackup(from: file, workDirectory: directory,
                                                  openForVerify: BackupManager.openForVerification)
        }
    }

    func testImportAcceptsAV1BackupWhichMigratesOnTheNextOpen() throws {
        // the shipped-release backup: schema v1 with a real transcript, no FTS
        let directory = try makeDirectory()
        let v1URL = directory.appendingPathComponent("v1.db")
        let v1 = try LearningDatabase.openV1(at: v1URL.path)
        XCTAssertEqual(Int64(1), try v1.query("PRAGMA user_version").first!.long("user_version"))
        let sessionID = UUID()
        _ = try v1.insert("sessions", ["id": sessionID.uuidString, "language_id": "es",
                                       "started_at": 1.0, "title": "Historia"])
        _ = try v1.insert("fragments", ["id": "f1", "session_id": sessionID.uuidString,
                                        "sort_index": 0, "speaker": "user",
                                        "text": "palabra buscable", "revision": 0,
                                        "start_ms": 0, "end_ms": 1, "received_at": 1.0])
        XCTAssertThrowsError(try v1.query(
            "SELECT rowid FROM fragments_fts WHERE fragments_fts MATCH 'buscable'"))
        v1.close()

        // the gate accepts v1 (a v1 backup migrates on open) and hands the
        // exact bytes back
        let bytes = try BackupManager.readValidatedBackup(
            from: v1URL, workDirectory: directory,
            openForVerify: BackupManager.openForVerification)

        // install + reopen: the open migrates in place and the transcript the
        // v1 file already held is searchable through the FTS index
        let live = directory.appendingPathComponent("learning.db")
        try BackupManager.installDatabase(live, bytes: bytes)
        let reopened = try fileBacked(live)
        XCTAssertEqual(Int64(LearningDatabase.version),
                       try reopened.query("PRAGMA user_version").first!.long("user_version"))
        XCTAssertEqual([sessionID], try SearchDao.search(reopened, "buscable", languageID: "es")
            .map { $0.session.id })
    }

    func testImportAcceptsAV2BackupWithoutExamTables() throws {
        let directory = try makeDirectory()
        let url = directory.appendingPathComponent("v2.db")
        let db = try LearningDatabase.openV1(at: url.path)
        try db.exec("PRAGMA user_version = 2")
        db.close()
        let bytes = try BackupManager.readValidatedBackup(
            from: url, workDirectory: directory,
            openForVerify: BackupManager.openForVerification)
        XCTAssertEqual(bytes, try Data(contentsOf: url))
    }

    // MARK: per-language delete

    func testDeleteByLanguageKeepsOtherLanguagesLegacyRowsAndQueues() throws {
        let db = try LearningDatabase.openInMemory()
        databases.append(db)
        let seeded = try seed(db)
        _ = try ExamHistoryDao.start(db, snapshot: sampleExam("en-exam", "en-content", "en"),
            attempt: ExamAttemptRecord(attemptID: "en-attempt", examID: "en-exam", startedAt: 10))
        _ = try ExamHistoryDao.start(db, snapshot: sampleExam("es-exam", "es-content", "es"),
            attempt: ExamAttemptRecord(attemptID: "es-attempt", examID: "es-exam", startedAt: 11))
        _ = try ExamHistoryDao.saveAnswer(db, attemptID: "en-attempt", questionID: "q1",
            responseJSON: "{\"answer\":\"private\"}", position: 0, savedAt: 12)

        let deletion = try BackupManager.deleteLearningDataForLanguage(db, languageID: "en")
        XCTAssertEqual(Int64(2), deletion.sessions)
        XCTAssertEqual(Int64(3), deletion.fragments)
        XCTAssertEqual(Int64(1), deletion.completions)
        XCTAssertEqual(Int64(1), deletion.expressions)
        XCTAssertEqual(Int64(2), deletion.lessonStates)
        XCTAssertEqual(Int64(1), deletion.tickets)
        XCTAssertEqual(Int64(1), deletion.examAttempts)
        XCTAssertEqual(Int64(1), deletion.examSnapshots)
        XCTAssertEqual(Int64(1), try count(db, "exam_attempts"))
        XCTAssertEqual(Int64(1), try count(db, "exam_snapshots"))
        XCTAssertEqual("es-attempt", try db.query("SELECT attempt_id FROM exam_attempts").first!.text("attempt_id"))

        // the survivors, row by row
        XCTAssertEqual([sES1.uuidString],
                       try db.query("SELECT id FROM sessions ORDER BY rowid").map { $0.text("id") })
        XCTAssertEqual(Int64(2), try count(db, "fragments"),
                       "es fragments rode along, the en ones went with their sessions")
        XCTAssertEqual([sES1.uuidString, "orphan"],
                       try AssessmentTicketsDao.all(db).map(\.sessionID))
        XCTAssertEqual(Int64(2), try count(db, "usage_outbox"),
                       "the usage outbox carries no language and is never touched")
        XCTAssertEqual(["es-foundations-u1-ser", "legacy-thing"],
                       try CompletionsDao.all(db).map(\.itemID),
                       "legacy NULL-language completions are global activity and stay")
        XCTAssertEqual(["nice to meet you", "ciao"],
                       try ExpressionsDao.all(db).map(\.text),
                       "legacy NULL-language expressions are visible everywhere and stay")
        XCTAssertEqual(["es-foundations-u1-ser", "de-foundations-u1-sein", "zz-legacy-lesson"],
                       try LessonStateDao.all(db).map(\.lessonID),
                       "lesson_state follows the language prefix; other languages and non-prefixed ids stay")

        // export(DB) == the archive with the SAME rows purged — the invariant
        // LearningStore's working-copy purge must reproduce
        var expected = seeded
        expected.sessions.removeAll { $0.languageID == "en" }
        let completions = (expected.completions ?? []).filter { $0.languageID != "en" }
        expected.completions = completions.isEmpty ? nil : completions
        expected.expressions = (expected.expressions ?? []).filter { $0.languageID != "en" }
        expected.lessons = (expected.lessons ?? []).filter { !$0.lessonID.hasPrefix("en-") }
        XCTAssertEqual(try expected.encoded(), try ArchiveMapper.export(db).encoded())
    }

    func testDeleteByLanguageIsIdempotentAndSafeForLanguagesWithoutData() throws {
        let db = try LearningDatabase.openInMemory()
        databases.append(db)
        try seed(db)
        try BackupManager.deleteLearningDataForLanguage(db, languageID: "en")
        let afterFirst = try tableCounts(db)

        let again = try BackupManager.deleteLearningDataForLanguage(db, languageID: "en")
        XCTAssertEqual(LanguageDeletion(sessions: 0, fragments: 0, completions: 0,
                                        expressions: 0, lessonStates: 0, tickets: 0),
                       again, "every count is zero on the second pass")

        let noData = try BackupManager.deleteLearningDataForLanguage(db, languageID: "fr")
        XCTAssertEqual(LanguageDeletion(sessions: 0, fragments: 0, completions: 0,
                                        expressions: 0, lessonStates: 0, tickets: 0),
                       noData, "a language with no rows deletes nothing")
        XCTAssertEqual(afterFirst, try tableCounts(db), "nothing moved")
    }

    func testDeleteByLanguageSurvivesAnExportRoundtrip() throws {
        // the composite the settings flow produces: delete, then back up what
        // is left, and the backup must be a valid learning database
        let directory = try makeDirectory()
        let dbURL = directory.appendingPathComponent("learning.db")
        let db = try fileBacked(dbURL)
        try seed(db)
        try BackupManager.deleteLearningDataForLanguage(db, languageID: "de")
        let remaining = try ArchiveMapper.export(db)

        let backup = try BackupManager.exportTo(db, databaseFile: dbURL,
                                                openForVerify: BackupManager.openForVerification)
        let validated = try BackupManager.readValidatedBackup(
            from: dbURL, workDirectory: directory, openForVerify: BackupManager.openForVerification)
        XCTAssertEqual(backup, validated)

        db.close()
        try FileManager.default.removeItem(at: dbURL)
        try BackupManager.installDatabase(dbURL, bytes: validated)
        let reopened = try fileBacked(dbURL)
        XCTAssertEqual(try remaining.encoded(), try ArchiveMapper.export(reopened).encoded())
        XCTAssertEqual(Int64(3), try count(reopened, "sessions"),
                       "the de seed has no sessions, so none went")
        XCTAssertEqual(0, try LessonStateDao.all(reopened).filter { $0.lessonID.hasPrefix("de-") }.count,
                       "de lesson_state rows went with the prefix")
    }

    // MARK: crash-safe install (stage-then-swap)

    func testStageAndSwapKeepsTheLiveFileUntilTheSwap() throws {
        let directory = try makeDirectory()
        let dbURL = directory.appendingPathComponent("learning.db")
        let db = try fileBacked(dbURL)
        try seed(db)
        let before = try Data(contentsOf: dbURL)
        db.close()

        // the incoming backup: a verified export of a second, empty database
        let freshURL = directory.appendingPathComponent("fresh.db")
        let fresh = try fileBacked(freshURL)
        let outgoing = try BackupManager.exportTo(fresh, databaseFile: freshURL,
                                                  openForVerify: BackupManager.openForVerification)
        fresh.close()
        let incoming = outgoing

        let staged = URL(fileURLWithPath: dbURL.path + ".install-tmp")
        try BackupManager.stageDatabase(staged, bytes: incoming)
        XCTAssertEqual(incoming, try Data(contentsOf: staged),
                       "the staged bytes exist beside the live file")
        XCTAssertEqual(before, try Data(contentsOf: dbURL),
                       "the live file is untouched until the swap")

        try BackupManager.installDatabase(staged, target: dbURL)
        XCTAssertFalse(FileManager.default.fileExists(atPath: staged.path),
                       "the swap consumes the staged file")
        XCTAssertEqual(incoming, try Data(contentsOf: dbURL),
                       "the live file now holds the incoming bytes")
    }

    func testStartupAdoptionRecoversAStrandedPreimportAfterACrashInTheRestoreWindow() throws {
        let directory = try makeDirectory()
        let dbURL = directory.appendingPathComponent("learning.db")
        let db = try fileBacked(dbURL)
        try seed(db)
        let original = try ArchiveMapper.export(db)
        db.close()

        // the exact crash signature: the live file was renamed aside and the
        // process died before the staged bytes were swapped in
        let keep = URL(fileURLWithPath: dbURL.path + ".preimport")
        try FileManager.default.moveItem(at: dbURL, to: keep)
        XCTAssertFalse(FileManager.default.fileExists(atPath: dbURL.path))

        XCTAssertEqual(true, LearningDbStore.adoptPreimportDatabase(at: dbURL),
                       "the stranded previous database is adopted back")
        XCTAssertTrue(FileManager.default.fileExists(atPath: dbURL.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: keep.path),
                       "adoption is a rename, not a copy")
        XCTAssertEqual(try original.encoded(), try ArchiveMapper.export(fileBacked(dbURL)).encoded(),
                       "the learner keeps the data they had")

        // both files missing: a fresh install, nothing to adopt
        XCTAssertEqual(false, LearningDbStore.adoptPreimportDatabase(
            at: directory.appendingPathComponent("absent.db")))

        // a completed import (both files exist): the installed bytes win and
        // a leftover keep-copy is never touched by adoption
        let live = try fileBacked(dbURL)
        try LessonStateDao.upsert(live, LessonState(lessonID: "en-foundations-u1-to-be",
                                                    attempts: 9, lastSeen: 1))
        try Data([0]).write(to: URL(fileURLWithPath: dbURL.path + ".preimport"))
        XCTAssertEqual(false, LearningDbStore.adoptPreimportDatabase(at: dbURL),
                       "adoption must not run when the live file exists")
        XCTAssertTrue(FileManager.default.fileExists(atPath: dbURL.path + ".preimport"),
                      "the stale keep-copy is left alone")
    }

    // MARK: LIKE-wildcard defense in the per-language delete

    func testDeleteByLanguageEscapesLikeWildcardsInThePrefix() throws {
        let db = try LearningDatabase.openInMemory()
        databases.append(db)
        // an unescaped '_' is a single-char wildcard: a pattern built from
        // the id "zz_z" would also match "zzaz-…" and delete rows that are
        // not the language's — the ESCAPE clause must keep it literal
        try LessonStateDao.upsert(db, LessonState(lessonID: "zz_z-lesson", attempts: 1, lastSeen: 10))
        try LessonStateDao.upsert(db, LessonState(lessonID: "zzaz-lesson", attempts: 2, lastSeen: 20))
        XCTAssertEqual("zz\\_z-%", try BackupManager.lessonStatePattern("zz_z"))

        try BackupManager.deleteLearningDataForLanguage(db, languageID: "zz_z")
        XCTAssertEqual(["zzaz-lesson"], try LessonStateDao.all(db).map(\.lessonID))
    }

    // MARK: lesson_state prefix derivation

    func testLessonStatePrefixIsDerivedAndVerifiedAgainstTheCourseRegistry() throws {
        XCTAssertEqual("en-%", try BackupManager.lessonStatePattern("en"))
        XCTAssertEqual("es-%", try BackupManager.lessonStatePattern("es"))
        // the premise itself, against the real generated content: every
        // authored lesson id starts with its language id (shared/courses)
        for course in CourseRegistry.all {
            for lesson in course.units.flatMap({ $0.lessons }) {
                XCTAssertTrue(lesson.id.hasPrefix("\(course.languageID)-"),
                              "\(lesson.id) must start with \"\(course.languageID)-\"")
            }
        }
        // an id with no registry courses falls back to the explicit prefix
        XCTAssertEqual("zz-%", try BackupManager.lessonStatePattern("zz"))
    }
}
