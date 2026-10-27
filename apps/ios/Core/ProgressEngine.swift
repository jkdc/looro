import Foundation

/// Progress metrics projected from the completion ledger; a pure projection, never stored.
public struct CourseProgress: Hashable, Sendable {
    public var courseID: String
    public var title: String
    public var lessonsDone: Int
    public var lessonsTotal: Int
    public var percent: Int { lessonsTotal == 0 ? 0 : lessonsDone * 100 / lessonsTotal }
}

public struct ProgressStats: Hashable, Sendable {
    public var courses: [CourseProgress]
    public var lessonsDone: Int
    public var lessonsTotal: Int
    public var activeStreakDays: Int
    public var longestStreakDays: Int
    public var last30DaysActivities: [Int]
    public var themesPractised: Int
    public var exercisesCompleted: Int
    public var listeningCompleted: Int
    public var practiceRounds: Int
    public var wordsPracticeItems: Int
    public var assessmentsPassed: Int
    public var readingCompleted: Int
}

/// The completion catalog every surface shares: the local ledger, the server
/// `user_progress.kind` CHECK and the sync wire all use exactly these names.
public enum ProgressEngine {
    public static let lesson = "lesson"
    public static let conversationTheme = "conversation_theme"
    public static let exerciseSet = "exercise_set"
    public static let listening = "listening"
    public static let reading = "reading"
    public static let pronunciation = "pronunciation"
    public static let recallRound = "recall_round"
    public static let wordsPractice = "words_practice"
    public static let assessment = "assessment"
    public static let scenarioPractice = "scenario_practice"
    public static let kinds = [lesson, conversationTheme, exerciseSet, listening, reading, pronunciation, recallRound, wordsPractice, assessment, scenarioPractice]

    /// Stable ledger/wire key: readable slugs pass through; anything else (a word
    /// lemma with accents or spaces) collapses to 16 hex chars of SHA-256.
    public static func completionKey(_ raw: String) -> String {
        if raw.range(of: "^[a-z0-9][a-z0-9_-]{0,63}$", options: .regularExpression) != nil { return raw }
        let digest = SHA256.hash(data: Data(raw.utf8))
        return digest.prefix(8).map { String(format: "%02x", $0) }.joined()
    }

    /// Day boundaries follow the local calendar on both platforms (tests pin UTC).
    /// Content ids (lessons, exercises, listening, reading, course ids) embed the
    /// language, so legacy nil-language marks inside them stay unambiguous;
    /// ambiguous kinds (themes, recall rounds, word hashes, "free" assessments)
    /// need the stamp.
    public static func stats(sessions: [SessionRecord], lessons: [LessonState], completions: [CompletionMark],
                             courses: [Course], listeningIDs: Set<String>, languageID: String,
                             readingIDs: Set<String> = [], calendar: Calendar = .current, now: Date = .now,
                             ownerAccountID: String? = nil, remote: [ProgressAggregate] = []) -> ProgressStats {
        func epochDay(_ date: Date) -> Int {
            let epoch = calendar.startOfDay(for: Date(timeIntervalSinceReferenceDate: 0))
            return calendar.dateComponents([.day], from: epoch, to: calendar.startOfDay(for: date)).day ?? 0
        }
        let contentIDs = Set(courses.flatMap { $0.units.flatMap { $0.lessons.map(\.id) } })
            .union(courses.map(\.id)).union(listeningIDs).union(readingIDs)
        func scoped(_ mark: CompletionMark) -> Bool {
            mark.languageID == languageID || (mark.languageID == nil && contentIDs.contains(mark.itemID))
        }
        let languageSessions = sessions.filter { $0.languageID == languageID }
        let languageMarks = completions.filter { $0.languageID == nil || $0.languageID == languageID }
        let readLessons = ownerAccountID.map { ProgressProjection.effectiveLessons(ownerAccountID: $0, languageID: languageID, local: lessons, remote: remote, allowedIDs: contentIDs) } ?? lessons
        let readCompletions = ownerAccountID.map { completions.filter { scoped($0) } + ProgressProjection.effectiveCompletions(ownerAccountID: $0, languageID: languageID, local: [], remote: remote) } ?? completions
        let done = Set(readLessons.filter { $0.attempts > 0 }.map(\.lessonID))
        let courseProgress = courses.map { course -> CourseProgress in
            let all = course.units.flatMap(\.lessons)
            return CourseProgress(courseID: course.id, title: course.title,
                                  lessonsDone: all.filter { done.contains($0.id) }.count, lessonsTotal: all.count)
        }
        let today = epochDay(now)
        var activeDays = Set(languageMarks.map { epochDay(Date(timeIntervalSinceReferenceDate: $0.at)) })
        for session in languageSessions where session.voiceSeconds > 0 { activeDays.insert(epochDay(session.startedAt)) }
        var active = 0
        var cursor = activeDays.contains(today) ? today : today - 1
        while activeDays.contains(cursor) { active += 1; cursor -= 1 }
        var longest = 0, run = 0, previous = Int.min
        for day in activeDays.sorted() {
            run = day == previous + 1 ? run + 1 : 1
            longest = max(longest, run)
            previous = day
        }
        func distinctItems(_ kind: String) -> Int {
            Set(readCompletions.filter { $0.kind == kind && scoped($0) }.map(\.itemID)).count
        }
        return ProgressStats(
            courses: courseProgress,
            lessonsDone: courseProgress.reduce(0) { $0 + $1.lessonsDone },
            lessonsTotal: courseProgress.reduce(0) { $0 + $1.lessonsTotal },
            activeStreakDays: active, longestStreakDays: longest,
            last30DaysActivities: stride(from: 29, through: 0, by: -1).map { offset in
                languageMarks.filter { epochDay(Date(timeIntervalSinceReferenceDate: $0.at)) == today - offset }.count
            },
            themesPractised: Set(readCompletions.filter { $0.kind == conversationTheme && $0.languageID == languageID }.map(\.itemID)).count,
            exercisesCompleted: distinctItems(exerciseSet),
            listeningCompleted: distinctItems(listening),
            readingCompleted: distinctItems(reading),
            practiceRounds: completions.filter { $0.kind == recallRound && $0.languageID == languageID }.count,
            wordsPracticeItems: distinctItems(wordsPractice),
            assessmentsPassed: Set(readCompletions.filter { $0.kind == assessment && scoped($0) && $0.bestScore >= 100 }.map(\.itemID)).count)
    }
}

/// One cumulative wire entry for the server ledger: (kind, item) rollups merge
/// monotonically server-side, so cumulative counters are safe to resend.
public struct ProgressSyncEntry: Sendable {
    public var kind: String
    public var itemID: String
    public var firstAtMilliseconds: Int64
    public var lastAtMilliseconds: Int64
    public var atMilliseconds: Int64 { lastAtMilliseconds }
    public var attempts: Int
    public var bestScore: Int
    public var language: String

    public init(kind: String, itemID: String, firstAtMilliseconds: Int64, lastAtMilliseconds: Int64,
                attempts: Int, bestScore: Int, language: String) {
        self.kind = kind; self.itemID = itemID; self.firstAtMilliseconds = firstAtMilliseconds
        self.lastAtMilliseconds = lastAtMilliseconds; self.attempts = attempts
        self.bestScore = bestScore; self.language = language
    }
}

public struct RemoteProgress: Sendable {
    public var kind: String
    public var itemID: String
    public var firstAt: Double
    public var lastAt: Double
    public var attempts: Int
    public var bestScore: Int
    public var language: String?
}

/// Owner/language-scoped stored aggregate. It is evidence for read models, not
/// a local completion event.
public struct ProgressAggregate: Hashable, Sendable {
    public var ownerAccountID: String
    public var languageID: String?
    public var kind: String
    public var itemID: String
    public var firstAt: Int64
    public var lastAt: Int64
    public var attempts: Int
    public var bestScore: Int

    public init(ownerAccountID: String, languageID: String?, kind: String, itemID: String,
                firstAt: Int64, lastAt: Int64, attempts: Int, bestScore: Int) {
        self.ownerAccountID = ownerAccountID; self.languageID = languageID; self.kind = kind; self.itemID = itemID
        self.firstAt = firstAt; self.lastAt = lastAt; self.attempts = attempts; self.bestScore = bestScore
    }
}

public enum ProgressProjection {
    public static func unixMilliseconds(from appleReferenceSeconds: Double) -> Int64? {
        guard appleReferenceSeconds.isFinite else { return nil }
        let milliseconds = ((appleReferenceSeconds + ProgressSyncContractData.appleEpochOffsetSeconds) * 1000).rounded()
        guard milliseconds.isFinite, milliseconds >= 0, milliseconds <= 9_007_199_254_740_991 else { return nil }
        return Int64(milliseconds)
    }
    public static func merge(ownerAccountID: String, languageID: String,
                             local: [ProgressAggregate], remote: [ProgressAggregate]) -> [ProgressAggregate] {
        var result: [String: ProgressAggregate] = [:]
        for row in local + remote where row.ownerAccountID == ownerAccountID && row.languageID == languageID {
            let key = "\(row.kind)|\(row.itemID)"
            if let old = result[key] {
                result[key] = ProgressAggregate(ownerAccountID: ownerAccountID, languageID: languageID,
                    kind: row.kind, itemID: row.itemID, firstAt: min(old.firstAt, row.firstAt),
                    lastAt: max(old.lastAt, row.lastAt), attempts: max(old.attempts, row.attempts),
                    bestScore: max(old.bestScore, row.bestScore))
            } else { result[key] = row }
        }
        return result.values.sorted { ($0.kind, $0.itemID) < ($1.kind, $1.itemID) }
    }

    public static func effectiveCompletions(ownerAccountID: String, languageID: String,
        local: [CompletionMark], remote: [ProgressAggregate]) -> [CompletionMark] {
        let localRows = local.filter { $0.languageID == languageID }.compactMap { mark -> ProgressAggregate? in
            guard let ms = unixMilliseconds(from: mark.at) else { return nil }
            return ProgressAggregate(ownerAccountID: ownerAccountID, languageID: languageID, kind: mark.kind,
                itemID: mark.itemID, firstAt: ms, lastAt: ms, attempts: 1, bestScore: mark.bestScore)
        }
        return merge(ownerAccountID: ownerAccountID, languageID: languageID, local: localRows, remote: remote).flatMap { row in
            let instants = row.kind == ProgressEngine.lesson && row.firstAt != row.lastAt ? [row.firstAt, row.lastAt] : [row.lastAt]
            return instants.map { instant in CompletionMark(kind: row.kind, itemID: row.itemID,
                at: Double(instant) / 1000 - ProgressSyncContractData.appleEpochOffsetSeconds,
                bestScore: row.bestScore, languageID: row.languageID) }
        }
    }

    public static func effectiveLessons(ownerAccountID: String, languageID: String,
        local: [LessonState], remote: [ProgressAggregate], allowedIDs: Set<String>) -> [LessonState] {
        let localRows = local.filter { allowedIDs.contains($0.lessonID) }.compactMap { lesson -> ProgressAggregate? in
            guard let ms = unixMilliseconds(from: lesson.lastSeen) else { return nil }
            return ProgressAggregate(ownerAccountID: ownerAccountID, languageID: languageID, kind: ProgressEngine.lesson,
                itemID: lesson.lessonID, firstAt: ms, lastAt: ms, attempts: lesson.attempts, bestScore: 0)
        }
        return merge(ownerAccountID: ownerAccountID, languageID: languageID, local: localRows, remote: remote)
            .filter { $0.kind == ProgressEngine.lesson }.map { row in
                LessonState(lessonID: row.itemID, attempts: row.attempts,
                    lastSeen: max(local.first(where: { $0.lessonID == row.itemID })?.lastSeen ?? 0,
                        Double(row.lastAt) / 1000 - ProgressSyncContractData.appleEpochOffsetSeconds))
            }
    }
}

public enum ProgressSync {
    public static let maximumEntries = 200

    /// Cumulative rollups for one language, from that language's marks + lesson
    /// counters, newest first, wire-capped. Lesson counters outrank the (capped)
    /// mark ledger so attempts never regress.
    public static func entries(lessons: [LessonState], completions: [CompletionMark], courses: [Course],
                               listeningIDs: Set<String>, languageID: String, readingIDs: Set<String> = [],
                               ownerAccountID: String = "", remote: [ProgressAggregate] = [],
                               nowUnixMilliseconds: Int64 = Int64(Date.now.timeIntervalSince1970 * 1000)) -> [ProgressSyncEntry] {
        let contentIDs = Set(courses.flatMap { $0.units.flatMap { $0.lessons.map(\.id) } })
            .union(courses.map(\.id)).union(listeningIDs).union(readingIDs)
        func scoped(_ mark: CompletionMark) -> Bool {
            mark.languageID == languageID || (mark.languageID == nil && contentIDs.contains(mark.itemID))
        }
        let languageLessons = lessons.filter { $0.attempts > 0 && contentIDs.contains($0.lessonID) }
        struct Rollup { var attempts = 0; var firstAtMilliseconds: Int64; var lastAtMilliseconds: Int64; var bestScore = 0 }
        var rollups: [String: Rollup] = [:]
        func rollup(_ kind: String, _ item: String, _ at: Double, _ score: Int) -> Rollup? {
            guard let milliseconds = ProgressTime.toUnixMilliseconds(at, nowUnixMilliseconds: Double(nowUnixMilliseconds)) else { return nil }
            var entry = rollups["\(kind)|\(item)"] ?? Rollup(firstAtMilliseconds: milliseconds, lastAtMilliseconds: milliseconds)
            entry.attempts += 1
            entry.firstAtMilliseconds = min(entry.firstAtMilliseconds, milliseconds)
            entry.lastAtMilliseconds = max(entry.lastAtMilliseconds, milliseconds)
            entry.bestScore = max(entry.bestScore, score)
            rollups["\(kind)|\(item)"] = entry
            return entry
        }
        for mark in completions where scoped(mark) { _ = rollup(mark.kind, mark.itemID, mark.at, mark.bestScore) }
        for lesson in languageLessons {
            guard var mark = rollup(ProgressEngine.lesson, lesson.lessonID, lesson.lastSeen, 0) else { continue }
            mark.attempts = max(mark.attempts, lesson.attempts)
            rollups["\(ProgressEngine.lesson)|\(lesson.lessonID)"] = mark
        }
        let local = rollups.map { key, value in
            ProgressAggregate(ownerAccountID: ownerAccountID, languageID: languageID,
                kind: String(key.split(separator: "|")[0]), itemID: String(key.split(separator: "|", maxSplits: 1)[1]),
                firstAt: value.firstAtMilliseconds, lastAt: value.lastAtMilliseconds,
                attempts: value.attempts, bestScore: value.bestScore)
        }
        return ProgressProjection.merge(ownerAccountID: ownerAccountID, languageID: languageID, local: local, remote: remote)
            .map { row in ProgressSyncEntry(kind: row.kind, itemID: row.itemID,
                firstAtMilliseconds: row.firstAt, lastAtMilliseconds: row.lastAt,
                attempts: row.attempts, bestScore: row.bestScore, language: languageID) }
            .sorted { ($0.kind, $0.itemID, $0.language) < ($1.kind, $1.itemID, $1.language) }
    }

    /// Applies a server pull for the current language: rows of other languages are
    /// ignored, lessons merge counter-wise, and exercise/listening items the device
    /// has never seen come back as one restored mark (per-day history does not
    /// survive a reinstall — accepted v1 limit).
    public static func mergeRemote(archive: Archive, rows: [RemoteProgress], languageID: String) -> Archive {
        var candidate = archive
        var lessons = candidate.lessons ?? []
        var marks = candidate.completions ?? []
        for row in rows where row.language == nil || row.language == languageID {
            switch row.kind {
            case ProgressEngine.lesson:
                if let index = lessons.firstIndex(where: { $0.lessonID == row.itemID }) {
                    lessons[index].attempts = max(lessons[index].attempts, row.attempts)
                    lessons[index].lastSeen = max(lessons[index].lastSeen, row.lastAt)
                } else if row.attempts > 0 {
                    lessons.append(LessonState(lessonID: row.itemID, attempts: row.attempts, lastSeen: row.lastAt))
                }
            case ProgressEngine.exerciseSet, ProgressEngine.listening, ProgressEngine.reading:
                if !marks.contains(where: { $0.kind == row.kind && $0.itemID == row.itemID }) {
                    marks.append(CompletionMark(kind: row.kind, itemID: row.itemID, at: row.lastAt,
                                                bestScore: row.bestScore, languageID: languageID))
                }
            default: break
            }
        }
        candidate.lessons = lessons.isEmpty ? nil : lessons
        candidate.completions = marks.isEmpty ? candidate.completions : marks
        return candidate
    }
}
