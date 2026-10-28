import Foundation
import SwiftData
import Security
import Observation
import LearningCore

@Model final class StoredArchive {
    @Attribute(.unique) var key: String
    var payload: Data
    init(payload: Data) { key = SwiftDataMigrationSource.payloadKey; self.payload = payload }
    init(key: String, payload: Data) { self.key = key; self.payload = payload }
}

/// The legacy SwiftData payload as the DB migration's source — the iOS
/// counterpart of the Kotlin store's learning.json `Files` seam. "Exists"
/// means the `app-v1` record is present and no migrated marker record is;
/// "mark migrated" inserts that marker (the `learning.json →
/// learning.json.migrated` rename's SwiftData form — the payload record
/// itself is never destroyed, so a committed migration is never re-run and
/// a later database failure rethrows instead of promising a fallback that
/// no longer exists). Each call runs its own short-lived ModelContext so the
/// store's open path never borrows `mainContext` across isolation.
final class SwiftDataMigrationSource: LearningMigrationSource {
    static let payloadKey = "app-v1"
    static let migratedKey = "app-v1.migrated"
    private let container: ModelContainer

    init(container: ModelContainer) { self.container = container }

    var hasPayload: Bool {
        fetchRecord(Self.migratedKey) == nil && fetchRecord(Self.payloadKey) != nil
    }

    func readPayload() throws -> Data {
        guard let record = fetchRecord(Self.payloadKey) else { throw ArchiveError.invalid }
        return record.payload
    }

    func markMigrated() throws {
        guard fetchRecord(Self.migratedKey) == nil else { return }
        let context = ModelContext(container)
        context.insert(StoredArchive(key: Self.migratedKey, payload: Data()))
        try context.save()
    }

    private func fetchRecord(_ key: String) -> StoredArchive? {
        let context = ModelContext(container)
        let recordKey = key
        var descriptor = FetchDescriptor<StoredArchive>(predicate: #Predicate { $0.key == recordKey })
        descriptor.fetchLimit = 1
        guard let records = try? context.fetch(descriptor) else { return nil }
        return records.first
    }
}

@MainActor @Observable final class LearningStore {
    private(set) var archive = Archive()
    private(set) var progressRevision: UInt64 = 0
    var error: String?
    @ObservationIgnored var onSessionInvalidation: ((UUID) -> Void)?
    let container: ModelContainer
    private var document: StoredArchive?
    private let migrationBackupURL: URL?
    /// The learning database file (nil on the in-memory store): the path the
    /// phase-F backup/restore swaps bytes under, and the path
    /// `LearningDbStore.open` adopted `learning.db.preimport` back into.
    private let databaseURL: URL?
    private static let guestIdentityKey = "com.palique.localLearnerIdentity"
    private(set) var activeLearnerIdentity: String
    /// The learning database (phase E). Opened at init with the one-way
    /// SwiftData-payload migration and RE-opened by a successful
    /// `importDb` — the store is always the one source of the working copy;
    /// `jsonMode` means this run serves the legacy SwiftData path instead —
    /// `db` throws, so DB-mode-only code can never write to a database the
    /// next launch would not read.
    private var dbStore: LearningDbStore
    init(inMemory: Bool = false) throws {
        let memberIdentity = ManagedAccountStore().session?.accountID
        activeLearnerIdentity = memberIdentity.map { "member:\($0)" } ?? Self.stableGuestIdentity()
        migrationBackupURL = inMemory ? nil : URL.applicationSupportDirectory
            .appendingPathComponent("Palique", isDirectory: true)
            .appendingPathComponent("before-language-modules.json")
        container = try ModelContainer(for: StoredArchive.self, configurations: ModelConfiguration(isStoredInMemoryOnly: inMemory, cloudKitDatabase: .none))
        let context = container.mainContext
        let payloadKey = SwiftDataMigrationSource.payloadKey
        if let existing = try context.fetch(FetchDescriptor<StoredArchive>(
                predicate: #Predicate { $0.key == payloadKey })).first {
            document = existing
        } else if inMemory {
            let created = StoredArchive(payload: try Archive().encoded())
            context.insert(created)
            document = created
        }
        if inMemory {
            databaseURL = nil
            dbStore = try LearningDbStore.open(database: try LearningDatabase.openInMemory(),
                                               activeIdentity: activeLearnerIdentity)
        } else {
            let url = try Self.learningDatabaseURL()
            databaseURL = url
            dbStore = try LearningDbStore.open(at: url.path,
                                               source: SwiftDataMigrationSource(container: container),
                                               activeIdentity: activeLearnerIdentity)
        }
        if !dbStore.jsonMode {
            try ExamHistoryDao.assignOwnerlessToIdentityOnce(dbStore.db, ownerAccountID: activeLearnerIdentity)
        }
        if dbStore.jsonMode {
            // JSON fail-safe: the migration committed nothing, the SwiftData
            // record is intact and keeps serving this run (retiro en H).
            guard let document else {
                throw LearningDbStore.LearningDbStoreError.jsonModeUnavailable(
                    "fail-safe mode without the legacy record that caused it")
            }
            archive = try Archive.decode(document.payload)
            if let backup = migrationBackupURL,
               let root = try JSONSerialization.jsonObject(with: document.payload) as? [String: Any], root["schemaVersion"] as? Int == 1 {
                let directory = backup.deletingLastPathComponent()
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                if !FileManager.default.fileExists(atPath: backup.path) {
                    try document.payload.write(to: backup, options: [.atomic, .completeFileProtection])
                }
            }
            for i in archive.sessions.indices where archive.sessions[i].endedAt == nil {
                archive.sessions[i].endedAt = .now; archive.sessions[i].endReason = "App closed before finalization"
            }
            persist()
            error = dbStore.jsonModeNotice
        } else {
            // Database mode: one-shot startup export, then the recovery the
            // legacy load performed (sessions the app died inside get their
            // endedAt/endReason and their retry ticket, persisted).
            archive = try dbStore.recoverUnfinishedSessions(try dbStore.loadInitialArchive())
        }
    }
    var jsonMode: Bool { dbStore.jsonMode }

    /// Reads only the active member's durable remote-sync cursor.
    func remoteProgressSyncState(direction: String, ownerAccountID: String) throws -> ProgressSyncState? {
        guard !dbStore.jsonMode, activeLearnerIdentity == "member:\(ownerAccountID)" else { return nil }
        return try ProgressSyncStateDao.get(dbStore.db, ownerAccountID: ownerAccountID, direction: direction)
    }

    func hasLocalProgress(ownerAccountID: String) -> Bool {
        guard !dbStore.jsonMode, activeLearnerIdentity == "member:\(ownerAccountID)" else { return false }
        let examAttempts: Int
        do { examAttempts = try examHistory(ownerAccountID: ownerAccountID).count }
        catch { return true } // Unknown local state must never authorize a bootstrap.
        return ProgressClient.hasUserGeneratedProgress(sessions: archive.sessions.count,
            lessons: archive.lessons?.count ?? 0, completions: archive.completions?.count ?? 0,
            expressions: archive.expressions?.count ?? 0, examAttempts: examAttempts,
            preferences: archive.preferences)
    }

    /// Persists one remote page and its cursor atomically without creating local history.
    func applyRemoteProgressPage(_ rows: [RemoteProgressRollup], ownerAccountID: String,
                                 nextCursor: String?, passGeneration: Int64,
                                 updatedAtMilliseconds: Int64) throws {
        guard !dbStore.jsonMode, activeLearnerIdentity == "member:\(ownerAccountID)" else {
            throw SqlDatabaseError.unsupportedValue("progress owner changed")
        }
        try LearningDbWriter.remoteProgressPageApplied(dbStore.db, ownerAccountID: ownerAccountID,
            rollups: rows, nextCursor: nextCursor, passGeneration: passGeneration,
            updatedAtMilliseconds: updatedAtMilliseconds)
        progressRevision &+= 1
    }

    func acknowledgeRemoteProgressUpload(ownerAccountID: String, nextCursor: String?,
                                        passGeneration: Int64, updatedAtMilliseconds: Int64) throws {
        guard !dbStore.jsonMode, activeLearnerIdentity == "member:\(ownerAccountID)" else {
            throw SqlDatabaseError.unsupportedValue("progress owner changed")
        }
        try LearningDbWriter.remoteProgressUploadAcknowledged(dbStore.db, ownerAccountID: ownerAccountID,
            nextCursor: nextCursor, passGeneration: passGeneration,
            updatedAtMilliseconds: updatedAtMilliseconds)
    }

    func activateLearnerIdentity(_ accountID: String?) {
        let identity = accountID.map { "member:\($0)" } ?? Self.stableGuestIdentity()
        guard identity != activeLearnerIdentity else { return }
        activeLearnerIdentity = identity
        archive = Archive()
        guard !dbStore.jsonMode else { return }
        do {
            try LearnerOwnershipDao.activate(dbStore.db, identity: identity)
            try ProgressRecoveryDao.recover(dbStore.db, nowUnixMilliseconds: Int64(Date.now.timeIntervalSince1970 * 1000))
            archive = try dbStore.recoverUnfinishedSessions(dbStore.loadInitialArchive())
        } catch {
            self.error = error.localizedDescription
        }
    }

    private static func stableGuestIdentity() -> String {
        let defaults = UserDefaults.standard
        if let existing = defaults.string(forKey: guestIdentityKey), existing.hasPrefix("guest:"),
           existing.count > "guest:".count { return existing }
        let created = "guest:\(UUID().uuidString.lowercased())"
        defaults.set(created, forKey: guestIdentityKey)
        return created
    }

    /// Starts a frozen exam snapshot and its initial outbox record atomically.
    func startExam(snapshot: ExamSnapshotRecord, attempt: ExamAttemptRecord) throws -> ExamHistory {
        let accountOwner = activeLearnerIdentity.hasPrefix("member:")
            ? String(activeLearnerIdentity.dropFirst("member:".count)) : nil
        let ownedAttempt = ExamAttemptRecord(attemptID: attempt.attemptID, examID: attempt.examID,
            ownerAccountID: attempt.ownerAccountID ?? accountOwner, startedAt: attempt.startedAt)
        return try ExamHistoryDao.start(dbStore.db, snapshot: snapshot, attempt: ownedAttempt)
    }

    /// Persists the latest answer and position in the same transaction as its sync event.
    func saveExamAnswer(attemptID: String, questionID: String, responseJSON: String,
                        position: Int, savedAt: Double = Date.now.timeIntervalSince1970) throws -> Int64 {
        try ExamHistoryDao.saveAnswer(dbStore.db, attemptID: attemptID, questionID: questionID,
                                      responseJSON: responseJSON, position: position, savedAt: savedAt)
    }

    /// Persists the original grading result once; later identical completion is idempotent.
    func completeExam(attemptID: String, resultJSON: String,
                      completedAt: Double = Date.now.timeIntervalSince1970) throws -> Bool {
        try ExamHistoryDao.complete(dbStore.db, attemptID: attemptID, resultJSON: resultJSON,
                                    completedAt: completedAt)
    }

    /// Local history is isolated to the active account; nil selects anonymous attempts only.
    func examHistory(ownerAccountID: String? = nil) throws -> [ExamHistory] {
        try ExamHistoryDao.history(dbStore.db, ownerAccountID: ownerAccountID ?? currentExamAccountID)
    }

    /// Loads an attempt only inside the requested local/account ownership boundary.
    func examAttempt(attemptID: String, ownerAccountID: String? = nil) throws -> ExamHistory? {
        try ExamHistoryDao.load(dbStore.db, attemptID: attemptID, ownerAccountID: ownerAccountID ?? currentExamAccountID)
    }

    private var currentExamAccountID: String? {
        activeLearnerIdentity.hasPrefix("member:") ? String(activeLearnerIdentity.dropFirst("member:".count)) : nil
    }

    func pendingExamOutbox(ownerAccountID: String) throws -> [SqlRow] {
        try ExamHistoryDao.pendingOutbox(dbStore.db, ownerAccountID: ownerAccountID)
    }

    func acknowledgeExamOutbox(ownerAccountID: String, attemptID: String, eventID: String,
                               revision: Int64, acknowledgedAt: Double) throws -> Bool {
        try ExamHistoryDao.acknowledgeOutbox(dbStore.db, ownerAccountID: ownerAccountID, attemptID: attemptID,
            eventID: eventID, revision: revision, acknowledgedAt: acknowledgedAt)
    }

    func persistExamConflict(ownerAccountID: String, attemptID: String, serverAttemptJSON: String,
                             receivedAt: Double) throws -> Bool {
        try ExamHistoryDao.persistConflict(dbStore.db, ownerAccountID: ownerAccountID, attemptID: attemptID,
            serverAttemptJSON: serverAttemptJSON, receivedAt: receivedAt)
    }

    func restoreExamAttempts(records: [ExamRestoreRecord], ownerAccountID: String,
                             restoredAt: Double = Date.now.timeIntervalSince1970) throws -> [Bool] {
        try ExamHistoryDao.restorePage(dbStore.db, records: records,
                                       ownerAccountID: ownerAccountID, restoredAt: restoredAt)
    }

    func forkExamConflict(ownerAccountID: String, attemptID: String, forkAttemptID: String,
                          forkedAt: Double = Date.now.timeIntervalSince1970) throws -> Bool {
        try ExamHistoryDao.resolveConflictFork(dbStore.db, ownerAccountID: ownerAccountID,
            attemptID: attemptID, forkAttemptID: forkAttemptID, forkedAt: forkedAt)
    }

    func takeOverExamConflict(ownerAccountID: String, attemptID: String,
                              resolvedAt: Double = Date.now.timeIntervalSince1970) throws -> Bool {
        try ExamHistoryDao.resolveConflictTakeover(dbStore.db, ownerAccountID: ownerAccountID,
            attemptID: attemptID, resolvedAt: resolvedAt)
    }

    /// The learning database file's size in bytes (0 when unavailable): the
    /// coarse dbBytesBucket input of the daily archive_health heartbeat.
    var databaseByteCount: Int64 {
        guard let databaseURL else { return 0 }
        let attributes = try? FileManager.default.attributesOfItem(atPath: databaseURL.path)
        return (attributes?[.size] as? Int64) ?? 0
    }

    /// The daily archive_health heartbeat's gate: returns the bucketed props
    /// to write as the durable usage_outbox row exactly when the meta
    /// 'last_health_at' marker is missing or older than
    /// `ArchiveHealth.cadenceMilliseconds` (the marker is stamped here — one
    /// durable write, a single meta row, no transaction needed — so a crash
    /// just delays the next heartbeat to the next due window). The caller
    /// then writes the heartbeat's durable row (`enqueueDurableUsageEvent`) —
    /// the queue UsageReporter.flush drains, so a close between claim and
    /// upload loses nothing. TWO accepted loss windows delay the heartbeat to
    /// the next due window: a crash after the claim, and a failed enqueue
    /// (the marker is already stamped). JSON fail-safe mode has no database
    /// to serve from, so the heartbeat never fires there.
    func claimArchiveHealth(
        nowUnixMilliseconds: Int64 = Int64(Date.now.timeIntervalSince1970 * 1000)
    ) -> [String: UsageProp]? {
        guard !dbStore.jsonMode else { return nil }
        do {
            // GRDB opens the learning database in rollback-journal mode, so
            // the main file is the whole size story (Android adds -wal).
            return try ArchiveHealthDao.claimIfDue(
                dbStore.db, nowUnixMilliseconds: nowUnixMilliseconds,
                dbBytes: databaseByteCount, sessionCount: archive.sessions.count)
        } catch {
            print("LearningStore: archive_health gate failed — \(error)")
            return nil
        }
    }

    /// Writes one durable usage_outbox row (the archive_health heartbeat's
    /// single record — no in-memory double-write; UsageReporter.flush drains
    /// it, so a close right after this loses nothing). JSON fail-safe mode
    /// has no database. Never throws: a failed write just skips a heartbeat.
    func enqueueDurableUsageEvent(_ name: String, atUnixMilliseconds: Int64,
                                  props: [String: UsageProp] = [:]) {
        guard !dbStore.jsonMode else { return }
        do { try UsageOutboxDao.enqueue(dbStore.db, name, atUnixMilliseconds, props) }
        catch { print("LearningStore: usage_outbox enqueue failed — \(error)") }
    }

    /// The durable usage_outbox drain, read half (UsageReporter's closures):
    /// up to `cap` undispatched rows, oldest first. Empty in JSON fail-safe
    /// mode. Never throws: a drain failure must not break the telemetry flush.
    func pendingDurableUsageEvents(cap: Int) -> [DurableUsageEvent] {
        guard !dbStore.jsonMode else { return [] }
        do {
            return try UsageOutboxDao.pending(dbStore.db).prefix(cap)
                .map { DurableUsageEvent(rowId: $0.rowId, event: $0.event) }
        } catch {
            print("LearningStore: usage_outbox drain read failed — \(error)")
            return []
        }
    }

    /// The drain's delivery mark: runs after a successful upload; a failure
    /// just re-sends the same rows on the next flush (their stable
    /// rowid-derived ids dedupe server-side). Never throws.
    func markDurableUsageEventsDispatched(_ rowIds: [Int64]) {
        guard !dbStore.jsonMode else { return }
        do { try UsageOutboxDao.markDispatched(dbStore.db, rowIds) }
        catch { print("LearningStore: usage_outbox dispatch mark failed — \(error)") }
    }
    private static func learningDatabaseURL() throws -> URL {
        let base = URL.applicationSupportDirectory
        try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent(LearningDatabase.name)
    }
    var preferences: Preferences { archive.preferences }
    var language: LanguageModule { LanguageRegistry.module(for: preferences.learningLanguageID)! }
    var sessions: [SessionRecord] { archive.sessions.sorted { $0.startedAt > $1.startedAt } }
    var learningSessions: [SessionRecord] { sessions.filter { $0.languageID == language.id } }
    var learner: LearnerState { LearningEngine.project(archive.sessions, languageID: language.id, hiddenWords: archive.preferences.hiddenWords) }

    /// Phase G conversation search: the FTS index over the learning database,
    /// scoped to the learning language (`SearchDao` is the single search
    /// contract; the Kotlin mirror rides the same SQL). Empty in JSON
    /// fail-safe mode (no index serves this run) or when the query fails —
    /// the history view keeps showing the plain list rather than erroring.
    func searchConversations(_ query: String) -> [SearchHit] {
        guard !dbStore.jsonMode else { return [] }
        do {
            return try SearchDao.search(dbStore.db, query, languageID: language.id)
        } catch {
            print("LearningStore: conversation search failed — \(error)")
            return []
        }
    }

    func selectLanguage(_ id: String) {
        guard LanguageRegistry.module(for: id) != nil else { return }
        archive.preferences.learningLanguageID = id
        write { try LearningDbWriter.preferences($0, archive.preferences) }
    }
    func updatePreferences(_ change: (inout Preferences) -> Void) {
        change(&archive.preferences)
        write { try LearningDbWriter.preferences($0, archive.preferences) }
    }
    func save(_ session: SessionRecord) {
        if let i = archive.sessions.firstIndex(where: { $0.id == session.id }) { archive.sessions[i] = session }
        else { archive.sessions.append(session) }
        // The site replaces the session in place (or appends a new one at
        // the end), so an in-place upsert keeps the rowid position equal to
        // the archive list position; iOS keeps no runtime retry ledger, so
        // the ledger half of the Kotlin site has no mirror here.
        write { try LearningDbWriter.session($0, session, moveToEnd: false) }
    }
    func deleteSession(_ id: UUID) {
        onSessionInvalidation?(id)
        archive.sessions.removeAll { $0.id == id }
        // ticketRemoved is always true: deleting an absent ticket row is the
        // no-op that still cleans a recovered session's ticket with its
        // session (the Kotlin site's ledger filter outcome).
        write { try LearningDbWriter.sessionDeleted($0, id.uuidString, ticketRemoved: true) }
    }
    /// One attempt per finished practice session; mastery is never claimed here.
    func recordLessonAttempt(_ lessonID: String) {
        var lessons = archive.lessons ?? []
        let updated: LessonState
        if let index = lessons.firstIndex(where: { $0.lessonID == lessonID }) {
            lessons[index].attempts += 1
            lessons[index].lastSeen = Date.now.timeIntervalSinceReferenceDate
            updated = lessons[index]
        } else {
            updated = LessonState(lessonID: lessonID, attempts: 1,
                                  lastSeen: Date.now.timeIntervalSinceReferenceDate)
            lessons.append(updated)
        }
        archive.lessons = lessons
        // Lesson practice seeds the lesson's target expressions into the
        // spaced-recall notebook (idempotent; mirrors Android recordLessonAttempt).
        if let lesson = CourseRegistry.lesson(id: lessonID) {
            RecallEngine.seedFromLesson(&archive, lesson: lesson, now: Date.now.timeIntervalSinceReferenceDate)
        }
        let marks = appendedLedger(CompletionMark(kind: ProgressEngine.lesson,
                                                  itemID: ProgressEngine.completionKey(lessonID),
                                                  at: Date.now.timeIntervalSinceReferenceDate, bestScore: 0,
                                                  languageID: archive.preferences.learningLanguageID))
        archive.completions = marks
        // The seeds are exactly the entries the engine left in the archive
        // for the lesson's targets, in seeding order — the mirror carries
        // counters, the whole (post-sort) ledger and every seed in ONE
        // directed write (LearningDbWriter.lessonAttempt).
        let seeds = seededExpressions(lessonID: lessonID)
        write { try LearningDbWriter.lessonAttempt($0, lesson: updated, marks: marks, seeds: seeds) }
    }

    /// One completed learning event in the ledger; keeps only the newest marks.
    /// Marks carry the learning language.
    func recordCompletion(kind: String, itemID: String, bestScore: Int = 0) {
        let marks = appendedLedger(CompletionMark(kind: kind, itemID: ProgressEngine.completionKey(itemID),
                                                  at: Date.now.timeIntervalSinceReferenceDate, bestScore: bestScore,
                                                  languageID: archive.preferences.learningLanguageID))
        archive.completions = marks
        // The site appends and truncates (no re-sort — the ledger keeps
        // arrival order): the mirror rewrites the table to exactly that list.
        write { try LearningDbWriter.completions($0, marks) }
    }

    /// The recordCompletion archive mutation shared by both ledger sites:
    /// append, then keep the newest cap. The pre-cutover iOS ledger never
    /// re-sorted (unlike the Kotlin site) — the flow test pins arrival order.
    private func appendedLedger(_ mark: CompletionMark) -> [CompletionMark] {
        var marks = archive.completions ?? []
        marks.append(mark)
        if marks.count > Archive.maximumCompletions { marks = Array(marks.suffix(Archive.maximumCompletions)) }
        return marks
    }

    /// The expressions RecallEngine.seedFromLesson left in the archive for
    /// the lesson's targets (adoptions and appends alike), deduplicated on
    /// the (language, text) key — the lessonAttempt mirror's seed list.
    private func seededExpressions(lessonID: String) -> [ExpressionState] {
        let languageID = archive.preferences.learningLanguageID
        let seeds = (CourseRegistry.lesson(id: lessonID)?.expressions ?? []).compactMap { expression -> ExpressionState? in
            let key = RecallEngine.normalizeKey(expression.text)
            guard !key.isEmpty else { return nil }
            return (archive.expressions ?? []).first {
                RecallEngine.normalizeKey($0.text) == key && ($0.languageID == nil || $0.languageID == languageID)
            }
        }
        var seen = Set<String>()
        return seeds.filter { seen.insert(RecallEngine.expressionKey($0.text, $0.languageID)).inserted }
    }

    var progressStats: ProgressStats {
        _ = progressRevision
        let language = archive.preferences.learningLanguageID
        let (owner, remote) = remoteProgressAggregates(languageID: language)
        return ProgressEngine.stats(sessions: archive.sessions, lessons: archive.lessons ?? [],
                                    completions: archive.completions ?? [],
                                    courses: CourseRegistry.forLanguage(language),
                                    listeningIDs: Set(ListeningRegistry.forLanguage(language).map(\.id)),
                                    languageID: language,
                                    readingIDs: Set(ReadingRegistry.forLanguage(language).map(\.id)),
                                    ownerAccountID: owner, remote: remote)
    }

    func remoteProgressAggregates(languageID: String) -> (String?, [ProgressAggregate]) {
        _ = progressRevision
        guard !dbStore.jsonMode, activeLearnerIdentity.hasPrefix("member:") else { return (nil, []) }
        let owner = String(activeLearnerIdentity.dropFirst("member:".count))
        guard let rows = try? RemoteProgressRollupsDao.forLanguage(dbStore.db, ownerAccountID: owner, languageID: languageID) else { return (owner, []) }
        return (owner, rows.map { ProgressAggregate(ownerAccountID: $0.ownerAccountID, languageID: $0.languageID,
            kind: $0.kind, itemID: $0.itemID, firstAt: $0.firstAtMilliseconds,
            lastAt: $0.lastAtMilliseconds, attempts: $0.attempts, bestScore: $0.bestScore) })
    }

    /// Error memory: one wrong answer attributes a hit to every skill its lesson
    /// teaches. Local evidence — not synced in v1.
    func recordExerciseMistake(lessonID: String, prompt: String) {
        guard let lesson = CourseRegistry.lesson(id: lessonID) else { return }
        var errorsByKey: [String: SkillError] = Dictionary(uniqueKeysWithValues: (archive.errors ?? []).map { ($0.skillID, $0) })
        let now = Date.now.timeIntervalSinceReferenceDate
        for skill in lesson.skills where SkillsData.categories.keys.contains(skill) {
            let current = errorsByKey[skill]
            errorsByKey[skill] = SkillError(skillID: skill, count: (current?.count ?? 0) + 1,
                                            lastAt: now, lastPrompt: String(prompt.prefix(120)))
        }
        let merged = errorsByKey.values.sorted { $0.count > $1.count }
        let memory: [SkillError] = merged.isEmpty ? [] : Array(merged.prefix(Archive.maximumErrors))
        archive.errors = memory.isEmpty ? nil : memory
        // The site re-sorts and truncates: the error memory is rewritten in
        // archive order (LearningDbWriter.skillErrors), not upserted in place.
        write { try LearningDbWriter.skillErrors($0, memory) }
    }

    func errorFocus() -> String {
        (archive.errors ?? []).sorted { $0.count > $1.count }.prefix(3)
            .map { "\($0.skillID.split(separator: ":").last ?? Substring($0.skillID)) (\($0.count))" }
            .joined(separator: "; ")
    }

    func readingScore(for id: String) -> Int? {
        let local = (archive.completions ?? []).filter { $0.kind == ProgressEngine.reading && $0.itemID == id }.map(\.bestScore).max()
        let remote = remoteProgressAggregates(languageID: archive.preferences.learningLanguageID).1
            .filter { $0.kind == ProgressEngine.reading && $0.itemID == id }.map(\.bestScore).max()
        return [local, remote].compactMap { $0 }.max()
    }
    func listeningScore(for id: String) -> Int? {
        let local = (archive.completions ?? []).filter { $0.kind == ProgressEngine.listening && $0.itemID == id }.map(\.bestScore).max()
        let remote = remoteProgressAggregates(languageID: archive.preferences.learningLanguageID).1
            .filter { $0.kind == ProgressEngine.listening && $0.itemID == id }.map(\.bestScore).max()
        return [local, remote].compactMap { $0 }.max()
    }
    var completedLessonIDs: Set<String> {
        let language = archive.preferences.learningLanguageID
        let (owner, remote) = remoteProgressAggregates(languageID: language)
        let lessons = owner.map { ProgressProjection.effectiveLessons(ownerAccountID: $0, languageID: language,
            local: archive.lessons ?? [], remote: remote,
            allowedIDs: Set(CourseRegistry.forLanguage(language).flatMap { $0.units.flatMap { $0.lessons.map(\.id) } })) } ?? archive.lessons ?? []
        return Set(lessons.filter { $0.attempts > 0 }.map(\.lessonID))
    }
    func applyRemoteProgress(_ rows: [RemoteProgress]) {
        guard activeLearnerIdentity.hasPrefix("member:") else { return }
        let owner = String(activeLearnerIdentity.dropFirst("member:".count))
        let language = archive.preferences.learningLanguageID
        let rollups = rows.filter { $0.language == language }.compactMap { row -> RemoteProgressRollup? in
            guard let firstAt = ProgressProjection.unixMilliseconds(from: row.firstAt),
                  let lastAt = ProgressProjection.unixMilliseconds(from: row.lastAt) else { return nil }
            return RemoteProgressRollup(ownerAccountID: owner, languageID: language, kind: row.kind, itemID: row.itemID,
                firstAtMilliseconds: min(firstAt, lastAt), lastAtMilliseconds: max(firstAt, lastAt),
                attempts: row.attempts, bestScore: row.bestScore, sourceVersion: ProgressSyncContractData.version)
        }
        write { db in try db.transaction { tx in for row in rollups { try RemoteProgressRollupsDao.merge(tx, row) } } }
        progressRevision &+= 1
    }
    func hideWord(_ id: String) {
        archive.preferences.hiddenWords.append(id)
        write { try LearningDbWriter.preferences($0, archive.preferences) }
    }
    /// The spaced-recall notebook is per learning language (product decision:
    /// the all-languages view was the bug) — the language's rows plus the
    /// legacy NULL-language entries RecallEngine keeps reachable everywhere,
    /// i.e. ExpressionsDao.byLanguage's projection read from the working
    /// copy, newest use first.
    var notebookExpressions: [ExpressionState] {
        let languageID = archive.preferences.learningLanguageID
        return (archive.expressions ?? [])
            .filter { $0.languageID == nil || $0.languageID == languageID }
            .sorted { $0.lastSeen > $1.lastSeen }
    }
    /// Saves a looked-up phrase into the spaced-recall notebook — the single
    /// expression store (RecallEngine upsert on the (language, text) key, then
    /// persist). Returns false when a new entry cannot fit.
    func saveLookupExpression(text: String, meaning: String) -> Bool {
        let languageID = archive.preferences.learningLanguageID
        let key = RecallEngine.normalizeKey(text)
        let index = (archive.expressions ?? []).firstIndex {
            RecallEngine.normalizeKey($0.text) == key && ($0.languageID == nil || $0.languageID == languageID)
        }
        let saved = RecallEngine.saveLookup(&archive, text: text, meaning: meaning,
                                            now: Date.now.timeIntervalSinceReferenceDate)
        guard saved else { return false }
        // The engine replaced in place (index) or appended (last); either
        // way that one post-engine state is the site's whole DB write.
        let list = archive.expressions ?? []
        let state: ExpressionState?
        if let index, list.indices.contains(index) { state = list[index] } else { state = list.last }
        if let state {
            write { try LearningDbWriter.expressionUpserted($0, state) }
        }
        return true
    }
    /// Grades a typed recall attempt on the RecallEngine ladder, then persists.
    func gradeRecall(text: String, correct: Bool) {
        let languageID = archive.preferences.learningLanguageID
        let key = RecallEngine.normalizeKey(text)
        let index = (archive.expressions ?? []).firstIndex {
            RecallEngine.normalizeKey($0.text) == key && ($0.languageID == nil || $0.languageID == languageID)
        }
        RecallEngine.grade(&archive, text: text, correct: correct, now: Date.now.timeIntervalSinceReferenceDate)
        // The engine mutates that one entry in place (removing nothing), so
        // the index stays valid; only then is its post state the site's
        // whole DB write.
        if let index, let state = archive.expressions?[index] {
            write { try LearningDbWriter.expressionUpserted($0, state) }
        }
    }
    /// Removes an expression from the notebook (mirrors Android's removeExpression).
    func removeExpression(text: String) {
        let languageID = archive.preferences.learningLanguageID
        RecallEngine.remove(&archive, text: text)
        // The engine drops the language row AND the legacy NULL row for the
        // text; the mirror deletes both.
        write { try LearningDbWriter.expressionRemoved($0, text, languageID) }
    }
    /// One remove-languages dialog row (AppViewModel.expressionLanguageStats's
    /// mirror), computed from the working copy — every notebook read is
    /// read-your-writes — with the DAO's order (count descending, named
    /// languages alphabetical within a tie, the legacy bucket last), so the
    /// dialog shows exactly what a confirm would purge.
    func expressionLanguageStats() -> [ExpressionLanguageStats] {
        let grouped = Dictionary(grouping: archive.expressions ?? [], by: { $0.languageID })
        return grouped.map { languageID, rows in
            ExpressionLanguageStats(
                languageID: languageID,
                count: rows.count,
                approxBytes: rows.reduce(Int64(0)) {
                    $0 + Int64($1.text.utf8.count + $1.meaning.utf8.count)
                })
        }
        .sorted { lhs, rhs in
            if lhs.count != rhs.count { return lhs.count > rhs.count }
            switch (lhs.languageID, rhs.languageID) {
            case let (l?, r?): return l < r
            case (.some, .none): return true   // named languages before legacy
            default: return false
            }
        }
    }
    /// The notebook's "Remove languages…" action (mirrors Android's
    /// removeExpressionLanguages): deletes every row whose language_id is in
    /// `selected` EXACTLY (nil selects the legacy NULL-language bucket) on
    /// both sides of the cutover invariant — the ExpressionsDao transaction
    /// per language and the SAME predicate on the working copy. Legacy rows
    /// only go when explicitly selected; deleting a language never takes the
    /// shared legacy bucket with it. Returns false after a refusal or
    /// failure — the notice is on `error`.
    @discardableResult
    func removeExpressionLanguages(_ selected: [String?]) -> Bool {
        guard !dbStore.jsonMode else {
            error = Self.databaseUnavailableDeleteNotice
            return false
        }
        // No conversation-in-flight guard, deliberately (Android likewise has
        // no isRunning guard): expressions are not session-coupled (seeding
        // happens at session end), so a mid-conversation removal is user
        // intent and both sides stay consistent via the shared predicate.
        let doomed = Set(selected.compactMap { $0 })
        let includeLegacy = selected.contains(nil)
        archive.expressions = (archive.expressions ?? []).filter { expression in
            if let id = expression.languageID { return !doomed.contains(id) }
            return !includeLegacy
        }
        do {
            for id in doomed.sorted() {
                _ = try ExpressionsDao.deleteExpressionsForLanguage(try dbStore.db, languageID: id)
            }
            if includeLegacy {
                _ = try ExpressionsDao.deleteExpressionsLegacy(try dbStore.db)
            }
            error = nil
            return true
        } catch {
            // The working copy was purged but a transaction kept its rows:
            // reload the persisted projection so export(DB) == archive holds
            // again, then surface.
            archive = (try? dbStore.loadInitialArchive()) ?? archive
            print("LearningStore: expression-language removal failed — \(error)")
            self.error = "Palique couldn’t remove the selected expressions. Please try again."
            return false
        }
    }
    func correctPassage(sessionID: UUID, passageID: String, text: String) {
        guard let index = archive.sessions.firstIndex(where: { $0.id == sessionID }),
              let passage = archive.sessions[index].passages.first(where: { $0.id == passageID && $0.speaker == .user }) else { return }
        for (offset, fragment) in passage.fragments.enumerated() {
            archive.sessions[index].correctFragment(id: fragment.id, text: offset == 0 ? String(text.prefix(10_000)) : "")
        }
        onSessionInvalidation?(sessionID)
        // The corrections bump fragment revisions and clear the session's
        // translations cache — the whole session row is rewritten alongside
        // its transcript.
        let updated = archive.sessions[index]
        write { try LearningDbWriter.session($0, updated, moveToEnd: false) }
    }
    func deleteAll() {
        if let migrationBackupURL {
            do { try FileManager.default.removeItem(at: migrationBackupURL) }
            catch CocoaError.fileNoSuchFile { /* Most installations have no migration backup. */ }
            catch {
                self.error = "Palique couldn’t delete the older learning backup. Your conversations are still here. Please try again."
                return
            }
        }
        archive.sessions.forEach { onSessionInvalidation?($0.id) }
        archive.sessions = []
        var stored = archive.preferences
        stored.hiddenWords = []
        archive.preferences = stored
        // Faithful to the previous full-archive write: sessions (with their
        // fragments, by cascade) and the retry ledger go; lessons,
        // expressions, completions and skill errors are not this site's
        // learning data.
        write { try LearningDbWriter.learningDataDeleted($0, stored) }
    }
    /// Removes the on-disk SwiftData store (the default store plus its write-ahead files), the learning
    /// database and the migration backup, so the next init starts from an empty record. Only for the
    /// startup failure screen, behind the user’s explicit confirmation. The phase-F install/verify
    /// leftovers (a staged or verification copy a crash could strand) go too.
    static func eraseStore() throws {
        let base = URL.applicationSupportDirectory
        for path in ["default.store", "default.store-shm", "default.store-wal", "Palique/before-language-modules.json",
                     LearningDatabase.name, LearningDatabase.name + "-wal", LearningDatabase.name + "-shm",
                     LearningDatabase.name + ".preimport", LearningDatabase.name + ".install-tmp",
                     LearningDatabase.name + ".backup-verify"] {
            do { try FileManager.default.removeItem(at: base.appendingPathComponent(path)) }
            catch CocoaError.fileNoSuchFile { /* Fresh installations have no store yet. */ }
        }
    }
    func exportData() throws -> Data { try archive.encoded() }
    func importData(_ data: Data) throws {
        let imported = try Archive.decode(data)
        if dbStore.jsonMode {
            archive = try archive.merging(imported)
            persist()
        } else {
            // Restore path (the merger is restore-only): it merges against
            // the database and the working copy becomes the persisted
            // projection — export(DB) == archive holds through the swap.
            archive = try LearningDbWriter.archiveRestored(dbStore.db, imported)
        }
    }

    // MARK: phase F — learning.db backup/restore + per-language delete
    // (the Swift mirror of Android's AppViewModel exportDbTo/importDbFrom/
    // deleteLearningDataForLanguage. The iOS store's directed writes are
    // synchronous on the main actor, so — unlike the Android FIFO channel —
    // there is nothing to drain before the bytes move.) Every method here
    // follows the Android notice contract: it returns false/nil after a
    // refusal or failure and surfaces the user-facing copy on `error`
    // (RootView's alert); success clears the slot and lets the caller speak.

    // The refusal/failure notices (EN inline literals — iOS keeps no string
    // tables). Android parity: values/strings.xml's notice_db_* family, with
    // "legacy JSON learning file" worded as the iOS reality (the SwiftData
    // fallback this session keeps using).
    private static let databaseUnavailableExportNotice =
        "Database backup isn’t available: this session is using the previous local store."
    private static let databaseUnavailableRestoreNotice =
        "Database restore isn’t available: this session is using the previous local store."
    private static let databaseUnavailableDeleteNotice = databaseUnavailableExportNotice
    private static let storagePreparingNotice =
        "Storage is still being prepared. Try again in a moment."
    private static let exportFailedNotice = "The database backup couldn’t be exported."
    private static let restoreFailedNotice = "The database backup couldn’t be restored."

    /// The user-facing rejection copy for each validation issue — the mirror
    /// of Android's `backupNoticeRes` string-resource table.
    private static func notice(for issue: BackupIssue) -> String {
        switch issue {
        case .corrupt: return "That file isn’t a valid learning database."
        case .futureVersion:
            return "This backup is from a newer version of the app. Update the app and try again."
        case .missingTables: return "The backup is missing learning database tables."
        case .empty: return "The backup file is empty."
        case .tooLarge: return "The database backup is too large."
        }
    }

    /// The whole learning database as verified .db bytes — the mirror of
    /// Android's `exportDbTo`: BackupManager checkpoints the WAL, copies the
    /// file and verifies the COPY before handing the bytes over. Returns nil
    /// after a refusal or failure — the notice is on `error`. The legacy
    /// SwiftData fail-safe has no database to back up.
    func exportDb() -> Data? {
        guard !dbStore.jsonMode else {
            error = Self.databaseUnavailableExportNotice
            return nil
        }
        guard let databaseURL else {
            error = Self.storagePreparingNotice
            return nil
        }
        do {
            let bytes = try BackupManager.exportTo(try dbStore.db, databaseFile: databaseURL,
                                                   openForVerify: BackupManager.openForVerification)
            error = nil
            return bytes
        } catch {
            print("LearningStore: database export failed — \(error)")
            self.error = Self.exportFailedNotice
            return nil
        }
    }

    /**
     * Restores a learning database from `url` (a BackupManager-exported
     * .db). The live database is NEVER destroyed unless validation fully
     * passed, and the reopened store is the only source of the working copy:
     *
     *  1. read the bounded bytes and validate them against a THROWAWAY copy
     *     (integrity, user_version, foreign keys, canonical tables) — any
     *     InvalidBackup surfaces its user-facing notice and touches nothing;
     *  2. close the live connection, STAGE the new bytes beside the live
     *     file (both files exist from here on), set the current file aside
     *     (learning.db.preimport), clear stale journal siblings, then swap
     *     the staged bytes in — two back-to-back renames; a crash between
     *     them strands the old file as learning.db.preimport, which
     *     LearningDbStore adopts back at the next startup;
     *  3. reload the store exactly like startup (open → one-shot export →
     *     unfinished-session recovery); the reloaded store rejects the JSON
     *     fail-safe, so a database that somehow fails open (with the legacy
     *     record present) can never report success while the app is actually
     *     serving it — the import did NOT take;
     *  4. on ANY failure after step 2: put the previous file back (the
     *     keep-copy's rename atomically replaces whatever is in place — this
     *     path never deletes a database file) and reload it — the learner
     *     keeps the database they had. If even the keep-copy cannot be
     *     restored, the closed store refuses every write rather than letting
     *     one land on a database the next launch would not read.
     *
     * Returns false after a refusal or failure — the notice is on `error`.
     */
    @discardableResult
    func importDb(from url: URL) -> Bool {
        guard !dbStore.jsonMode else {
            error = Self.databaseUnavailableRestoreNotice
            return false
        }
        guard let databaseURL else {
            error = Self.storagePreparingNotice
            return false
        }
        do {
            let bytes = try BackupManager.readValidatedBackup(
                from: url, workDirectory: databaseURL.deletingLastPathComponent(),
                openForVerify: BackupManager.openForVerification)
            // Validation passed — now quiesce the platform queues exactly
            // like deleteAll's first step (and Android's order: validate,
            // then drain): firing onSessionInvalidation per current session
            // cancels the final-assessment queue's pending jobs, so a late
            // assessment can never land on the freshly restored copy. The
            // debounced save, the meaning lookups and the scenario review
            // are the coordinator's half of the quiesce.
            archive.sessions.forEach { onSessionInvalidation?($0.id) }
            let keep = URL(fileURLWithPath: databaseURL.path + ".preimport")
            let staged = URL(fileURLWithPath: databaseURL.path + ".install-tmp")
            defer { try? FileManager.default.removeItem(at: staged) }
            try dbStore.db.close()
            do {
                // stage FIRST: the live file is not moved aside until the
                // replacement is fully on disk beside it
                try BackupManager.stageDatabase(staged, bytes: bytes)
                try FileManager.default.renameItem(at: databaseURL, to: keep)
                Self.deleteJournalSiblings(of: databaseURL)
                try BackupManager.installDatabase(staged, target: databaseURL)
                try reloadAfterImport()
                try? FileManager.default.removeItem(at: keep)
            } catch {
                // This path NEVER deletes a database file: databaseURL may be
                // the intact original (the set-aside rename failed) or gone
                // (the swap failed) — either way the keep-copy's rename is
                // what recovers it, atomically replacing whatever is in
                // place. renameItem itself throws, so both of its outcomes
                // arrive here and are handled the same way.
                var restored = true
                if FileManager.default.fileExists(atPath: keep.path) {
                    restored = (try? FileManager.default.renameItem(at: keep, to: databaseURL)) != nil
                    if restored { Self.deleteJournalSiblings(of: databaseURL) }
                }
                if restored {
                    do { try reloadAfterImport() }
                    catch let reloadFailure {
                        // The previous database is back but cannot serve:
                        // the closed store refuses every write; the next
                        // launch opens the original file fresh.
                        throw reloadFailure
                    }
                }
                // (A keep-copy that cannot come back stays on disk, where
                // the next launch's adoption puts it back — the store keeps
                // refusing writes through its closed connection.)
                throw error
            }
            error = nil
            return true
        } catch let failure as InvalidBackup {
            print("LearningStore: database import rejected — \(failure.issue)")
            error = Self.notice(for: failure.issue)
            return false
        } catch {
            print("LearningStore: database import failed — \(error)")
            self.error = Self.restoreFailedNotice
            return false
        }
    }

    /// The post-import reload — exactly the startup path, keeping the
    /// reopened store the only source of the working copy (open → one-shot
    /// export → unfinished-session recovery, the recovery persisted like
    /// init does it). `dbStore` is reassigned only when the whole reload
    /// succeeded, so a failure leaves the closed connection in place — every
    /// write through it throws, which is the store's refuse-to-serve state.
    private func reloadAfterImport() throws {
        guard let databaseURL else {
            throw LearningDbStore.LearningDbStoreError.jsonModeUnavailable(
                "the learning database file is not known (in-memory store)")
        }
        let reloaded = try LearningDbStore.open(at: databaseURL.path,
                                                source: SwiftDataMigrationSource(container: container),
                                                activeIdentity: activeLearnerIdentity)
        guard !reloaded.jsonMode else {
            throw LearningDbStore.LearningDbStoreError.jsonModeUnavailable(
                "the restored database failed to open; the JSON fail-safe took over")
        }
        archive = try reloaded.recoverUnfinishedSessions(try reloaded.loadInitialArchive())
        dbStore = reloaded
    }

    /// Removes stale journal siblings before a replaced file is opened — the
    /// incoming bytes are self-contained (export checkpoints the WAL), so a
    /// leftover -wal/-shm/-journal from the previous database must not be
    /// replayed over them.
    private static func deleteJournalSiblings(of dbURL: URL) {
        for suffix in ["-wal", "-shm", "-journal"] {
            try? FileManager.default.removeItem(at: URL(fileURLWithPath: dbURL.path + suffix))
        }
    }

    /**
     * Deletes one language's learning data — the database transaction in
     * `BackupManager.deleteLearningDataForLanguage` mirrored onto the
     * working copy archive (the SAME rows, so export(DB) == archive keeps
     * holding): the language's sessions (fragments ride along), completions
     * and expressions (legacy NULL-language entries conserved on both
     * sides) and lesson_state rows whose lesson id starts with the language
     * prefix — the plain `hasPrefix`, not the LIKE pattern (escaping is a
     * SQL-only concern). `onSessionInvalidation` fires per removed session,
     * like deleteSession. Mastery meta and skill errors key on
     * language-neutral skill ids and stay, like the usage outbox.
     *
     * Returns false after a refusal or failure — the notice is on `error`.
     */
    @discardableResult
    func deleteLearningDataForLanguage(_ id: String) -> Bool {
        guard !dbStore.jsonMode else {
            error = Self.databaseUnavailableDeleteNotice
            return false
        }
        let doomedIDs = Set(archive.sessions.filter { $0.languageID == id }.map(\.id))
        doomedIDs.forEach { onSessionInvalidation?($0) }
        archive.sessions.removeAll { $0.languageID == id }
        let completions = (archive.completions ?? []).filter { $0.languageID != id }
        archive.completions = completions.isEmpty ? nil : completions
        archive.expressions = (archive.expressions ?? []).filter { $0.languageID != id }
        archive.lessons = (archive.lessons ?? []).filter { !$0.lessonID.hasPrefix("\(id)-") }
        do {
            _ = try BackupManager.deleteLearningDataForLanguage(try dbStore.db, languageID: id)
            error = nil
            return true
        } catch {
            // The working copy was purged but the transaction kept the
            // database's rows: reload the persisted projection so
            // export(DB) == archive holds again, then surface.
            archive = (try? dbStore.loadInitialArchive()) ?? archive
            print("LearningStore: per-language delete failed — \(error)")
            self.error = "Palique couldn’t delete the language’s learning data. Please try again."
            return false
        }
    }

    /// One mutation site's directed durable write: in database mode `work`
    /// persists ONLY what the site just changed, on the spot (the writes are
    /// small, single-statement transactions and the store already ran its
    /// saves synchronously on the main actor pre-cutover); in JSON fail-safe
    /// mode the site's write is replaced by the legacy full-payload SwiftData
    /// save — exactly the pre-cutover behavior.
    private func write(_ work: (SqlDatabase) throws -> Void) {
        if dbStore.jsonMode { persist(); return }
        do {
            try work(dbStore.db)
            error = nil
        } catch {
            // The working copy already holds the site's mutation but the
            // transaction kept the database's rows: reload the persisted
            // projection so export(DB) == archive holds again, then surface —
            // the same recovery as removeExpressionLanguages and the
            // per-language delete.
            archive = (try? dbStore.loadInitialArchive()) ?? archive
            print("LearningStore: directed write failed — \(error)")
            self.error = "Palique couldn’t save your progress. Please export a backup and try again."
        }
    }
    private func persist() {
        guard let document else { return }
        do {
            var payload = try archive.encoded()
            if payload.count > Archive.maximumEncodedBytes {
                // The importer refuses records beyond this cap on read; on write, drop the oldest
                // conversations (their evidence is stored inside them) until the record fits again.
                var trimmed = archive
                var pruned: [UUID] = []
                for session in trimmed.sessions.sorted(by: { $0.startedAt < $1.startedAt }) {
                    guard payload.count > Archive.maximumEncodedBytes else { break }
                    trimmed.sessions.removeAll { $0.id == session.id }
                    pruned.append(session.id)
                    payload = try trimmed.encoded()
                }
                guard payload.count <= Archive.maximumEncodedBytes else { throw ArchiveError.tooLarge }
                pruned.forEach { onSessionInvalidation?($0) }
                archive = trimmed
            }
            document.payload = payload; try container.mainContext.save(); error = nil
        }
        catch { self.error = "Palique couldn’t save your progress. Please export a backup and try again." }
    }
}

enum CredentialStore {
    private static let service = "com.jkdc.app.openai"
    private static var query: [String: Any] { [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: "owner", kSecAttrSynchronizable as String: false] }
    static func read() -> String? {
        var q = query; q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
    static var hasKey: Bool { read() != nil }
    static func save(_ key: String) throws {
        let value = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard value.hasPrefix("sk-"), value.count >= 20, !value.contains(where: \.isWhitespace) else { throw KeyError.invalid }
        let data = Data(value.utf8)
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var q = query; q[kSecValueData as String] = data
            q[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            guard SecItemAdd(q as CFDictionary, nil) == errSecSuccess else { throw KeyError.save }
        } else if status != errSecSuccess { throw KeyError.save }
    }
    static func delete() throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw KeyError.remove }
    }
    enum KeyError: LocalizedError {
        case invalid, save, remove
        var errorDescription: String? {
            switch self {
            case .invalid: "Enter a valid OpenAI API key."
            case .save: "The key couldn’t be saved to this device’s Keychain."
            case .remove: "The key couldn’t be removed. Unlock this iPhone and try again."
            }
        }
    }
}
