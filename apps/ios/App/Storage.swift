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
    var error: String?
    @ObservationIgnored var onSessionInvalidation: ((UUID) -> Void)?
    let container: ModelContainer
    private var document: StoredArchive?
    private let migrationBackupURL: URL?
    /// The learning database (phase E). Opened once at init with the one-way
    /// SwiftData-payload migration; `jsonMode` means this run serves the
    /// legacy SwiftData path instead — `db` throws, so DB-mode-only code can
    /// never write to a database the next launch would not read.
    private let dbStore: LearningDbStore
    init(inMemory: Bool = false) throws {
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
            dbStore = try LearningDbStore.open(database: try LearningDatabase.openInMemory())
        } else {
            dbStore = try LearningDbStore.open(at: try Self.learningDatabaseURL().path,
                                               source: SwiftDataMigrationSource(container: container))
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
            lessons[index].lastSeen = Date.now.timeIntervalSince1970
            updated = lessons[index]
        } else {
            updated = LessonState(lessonID: lessonID, attempts: 1, lastSeen: Date.now.timeIntervalSince1970)
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
        let language = archive.preferences.learningLanguageID
        return ProgressEngine.stats(sessions: archive.sessions, lessons: archive.lessons ?? [],
                                    completions: archive.completions ?? [],
                                    courses: CourseRegistry.forLanguage(language),
                                    listeningIDs: Set(ListeningRegistry.forLanguage(language).map(\.id)),
                                    languageID: language,
                                    readingIDs: Set(ReadingRegistry.forLanguage(language).map(\.id)))
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
        (archive.completions ?? []).filter { $0.kind == ProgressEngine.reading && $0.itemID == id }.map(\.bestScore).max()
    }
    var completedLessonIDs: Set<String> {
        Set((archive.lessons ?? []).filter { $0.attempts > 0 }.map(\.lessonID))
    }
    func applyRemoteProgress(_ rows: [RemoteProgress]) {
        let lessonsBefore = archive.lessons ?? []
        let markCountBefore = archive.completions?.count ?? 0
        archive = ProgressSync.mergeRemote(archive: archive, rows: rows,
                                           languageID: archive.preferences.learningLanguageID)
        // ProgressSync.mergeRemote updates lessons in place or appends them,
        // and appends unseen marks — the mirror upserts the changed/appended
        // lessons and inserts the appended marks (append-only: no re-sort,
        // rowid order stays archive order).
        let changedLessons = (archive.lessons ?? []).enumerated()
            .filter { $0.offset >= lessonsBefore.count || $0.element != lessonsBefore[$0.offset] }
            .map { $0.element }
        let appendedMarks = Array((archive.completions ?? []).dropFirst(markCountBefore))
        write { try LearningDbWriter.progressMerged($0, lessons: changedLessons, marks: appendedMarks) }
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
    /// startup failure screen, behind the user’s explicit confirmation.
    static func eraseStore() throws {
        let base = URL.applicationSupportDirectory
        for path in ["default.store", "default.store-shm", "default.store-wal", "Palique/before-language-modules.json",
                     LearningDatabase.name, LearningDatabase.name + "-wal", LearningDatabase.name + "-shm",
                     LearningDatabase.name + ".preimport"] {
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
