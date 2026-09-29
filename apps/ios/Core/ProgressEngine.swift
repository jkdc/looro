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
    public static let recallRound = "recall_round"
    public static let wordsPractice = "words_practice"
    public static let assessment = "assessment"
    public static let kinds = [lesson, conversationTheme, exerciseSet, listening, reading, recallRound, wordsPractice, assessment]

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
                             readingIDs: Set<String> = [], calendar: Calendar = .current, now: Date = .now) -> ProgressStats {
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
        let done = Set(lessons.filter { $0.attempts > 0 }.map(\.lessonID))
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
            Set(completions.filter { $0.kind == kind && scoped($0) }.map(\.itemID)).count
        }
        return ProgressStats(
            courses: courseProgress,
            lessonsDone: courseProgress.reduce(0) { $0 + $1.lessonsDone },
            lessonsTotal: courseProgress.reduce(0) { $0 + $1.lessonsTotal },
            activeStreakDays: active, longestStreakDays: longest,
            last30DaysActivities: stride(from: 29, through: 0, by: -1).map { offset in
                languageMarks.filter { epochDay(Date(timeIntervalSinceReferenceDate: $0.at)) == today - offset }.count
            },
            themesPractised: Set(completions.filter { $0.kind == conversationTheme && $0.languageID == languageID }.map(\.itemID)).count,
            exercisesCompleted: distinctItems(exerciseSet),
            listeningCompleted: distinctItems(listening),
            readingCompleted: distinctItems(reading),
            practiceRounds: completions.filter { $0.kind == recallRound && $0.languageID == languageID }.count,
            wordsPracticeItems: distinctItems(wordsPractice),
            assessmentsPassed: Set(completions.filter { $0.kind == assessment && scoped($0) && $0.bestScore >= 100 }.map(\.itemID)).count)
    }
}

/// One cumulative wire entry for the server ledger: (kind, item) rollups merge
/// monotonically server-side, so cumulative counters are safe to resend.
public struct ProgressSyncEntry: Sendable {
    public var kind: String
    public var itemID: String
    public var atMilliseconds: Int64
    public var attempts: Int
    public var bestScore: Int
    public var language: String
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

public enum ProgressSync {
    public static let maximumEntries = 200

    /// Cumulative rollups for one language, from that language's marks + lesson
    /// counters, newest first, wire-capped. Lesson counters outrank the (capped)
    /// mark ledger so attempts never regress.
    public static func entries(lessons: [LessonState], completions: [CompletionMark], courses: [Course],
                               listeningIDs: Set<String>, languageID: String, readingIDs: Set<String> = []) -> [ProgressSyncEntry] {
        let contentIDs = Set(courses.flatMap { $0.units.flatMap { $0.lessons.map(\.id) } })
            .union(courses.map(\.id)).union(listeningIDs).union(readingIDs)
        func scoped(_ mark: CompletionMark) -> Bool {
            mark.languageID == languageID || (mark.languageID == nil && contentIDs.contains(mark.itemID))
        }
        let languageLessons = lessons.filter { $0.attempts > 0 && contentIDs.contains($0.lessonID) }
        struct Rollup { var attempts = 0; var firstAt = 0.0; var lastAt = 0.0; var bestScore = 0 }
        var rollups: [String: Rollup] = [:]
        func rollup(_ kind: String, _ item: String, _ at: Double, _ score: Int) -> Rollup {
            var entry = rollups["\(kind)|\(item)"] ?? Rollup(firstAt: at, lastAt: at)
            entry.attempts += 1
            entry.firstAt = min(entry.firstAt, at)
            entry.lastAt = max(entry.lastAt, at)
            entry.bestScore = max(entry.bestScore, score)
            rollups["\(kind)|\(item)"] = entry
            return entry
        }
        for mark in completions where scoped(mark) { _ = rollup(mark.kind, mark.itemID, mark.at, mark.bestScore) }
        for lesson in languageLessons {
            var mark = rollup(ProgressEngine.lesson, lesson.lessonID, lesson.lastSeen, 0)
            mark.attempts = max(mark.attempts, lesson.attempts)
            mark.lastAt = max(mark.lastAt, lesson.lastSeen)
            rollups["\(ProgressEngine.lesson)|\(lesson.lessonID)"] = mark
        }
        return rollups
            .map { key, value in ProgressSyncEntry(kind: String(key.split(separator: "|")[0]),
                itemID: String(key.split(separator: "|", maxSplits: 1)[1]),
                atMilliseconds: Int64((value.lastAt + 978_307_200) * 1000), attempts: value.attempts,
                bestScore: value.bestScore, language: languageID) }
            .sorted { $0.atMilliseconds > $1.atMilliseconds }
            .prefix(maximumEntries)
            .map { $0 }
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
