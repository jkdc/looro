import Foundation
import CryptoKit

public enum LevelTestSection: String, Codable, CaseIterable, Sendable {
    case recognition, reading, listening, writing
}

public struct LevelTestPassage: Codable, Equatable, Sendable {
    public var id: String
    public var title: String
    public var text: String
}

public struct LevelTestItem: Codable, Equatable, Sendable {
    public var id: String
    public var section: LevelTestSection
    public var exercise: Exercise
    public var unitID: String?
    public var passageId: String?
    public var speech: String?
}

public struct LevelTestLevel: Codable, Equatable, Sendable {
    public var languageID: String
    public var level: String
    public var version: Int
    public var thresholds: [String: Int]
    public var passages: [LevelTestPassage]
    public var pool: [LevelTestItem]
}

public struct LevelTestBank: Codable, Equatable, Sendable {
    public var languageID: String
    public var levels: [LevelTestLevel]
}

public struct LevelTestResult: Codable, Equatable, Sendable {
    public var languageID: String
    public var level: String
    public var weekIndex: Int
    public var overall: Int
    public var sections: [String: Int]
    public var passed: Bool
    public var nextBand: String?
    public var weakSections: [String]
    public var weakUnitIDs: [String]
    public var at: Int64
}

public enum LevelTestsRegistry {
    public static var all: [LevelTestBank] { LevelTestsData.all }
    public static func forLanguage(_ languageID: String) -> LevelTestBank? {
        all.first { $0.languageID == languageID }
    }
    public static func level(languageID: String, level: String) -> LevelTestLevel? {
        forLanguage(languageID)?.levels.first { $0.level == level }
    }
}

public enum LevelTestEngine {
    public static let recognitionCount = 12
    public static let readingPassages = 2
    public static let readingPerPassage = 3
    public static let listeningPassages = 2
    public static let listeningPerPassage = 2
    public static let writingCount = 3
    public static let bands = ["A1", "A2", "B1", "B2", "C1"]

    public struct Exam: Sendable {
        public let weekIndex: Int
        public let level: String
        public let items: [LevelTestItem]
        public let passageById: [String: LevelTestPassage]
        public let readingPassageIDs: [String]
        public let listeningPassageIDs: [String]
    }

    public static func weekIndex(date: Date = .now, calendar: Calendar = Calendar(identifier: .iso8601)) -> Int {
        let utcCalendar: Calendar = {
            var value = calendar
            value.timeZone = TimeZone(secondsFromGMT: 0)!
            return value
        }()
        let year = utcCalendar.component(.yearForWeekOfYear, from: date)
        let week = utcCalendar.component(.weekOfYear, from: date)
        return year * 100 + week
    }

    public static func compose(level: LevelTestLevel, weekIndex: Int) -> Exam {
        let recognition = stableBlock(level.pool.filter { $0.section == .recognition }, version: level.version,
                                      count: recognitionCount, weekIndex: weekIndex)
        let writing = stableBlock(level.pool.filter { $0.section == .writing }, version: level.version,
                                  count: writingCount, weekIndex: weekIndex)
        let readingReady = stablePassages(level, section: .reading, perPassage: readingPerPassage)
        let readingIDs = selectedPassageBlock(readingReady, count: readingPassages, weekIndex: weekIndex)
        let readingItems = level.pool.filter { $0.section == .reading && readingIDs.contains($0.passageId ?? "") }
            .sorted {
                let lhsPassage = $0.passageId ?? ""
                let rhsPassage = $1.passageId ?? ""
                if lhsPassage != rhsPassage { return lhsPassage < rhsPassage }
                return stableHash(level.version, $0.id) < stableHash(level.version, $1.id)
            }
        let listeningReady = stablePassages(level, section: .listening, perPassage: listeningPerPassage)
            .filter { !readingIDs.contains($0.id) }
        let listeningIDs = selectedPassageBlock(listeningReady, count: listeningPassages, weekIndex: weekIndex)
        let listeningItems = level.pool.filter { $0.section == .listening && listeningIDs.contains($0.passageId ?? "") }
            .sorted {
                let lhsPassage = $0.passageId ?? ""
                let rhsPassage = $1.passageId ?? ""
                if lhsPassage != rhsPassage { return lhsPassage < rhsPassage }
                return stableHash(level.version, $0.id) < stableHash(level.version, $1.id)
            }
        return Exam(weekIndex: weekIndex, level: level.level,
                    items: recognition + readingItems + listeningItems + writing,
                    passageById: Dictionary(uniqueKeysWithValues: level.passages.map { ($0.id, $0) }),
                    readingPassageIDs: readingIDs, listeningPassageIDs: listeningIDs)
    }

    public static func stableHash(_ version: Int, _ value: String) -> String {
        SHA256.hash(data: Data("\(version)|\(value)".utf8)).map { String(format: "%02x", $0) }.joined()
    }

    public static func nextBand(_ level: String) -> String? {
        guard let index = bands.firstIndex(of: level), bands.indices.contains(index + 1) else { return nil }
        return bands[index + 1]
    }

    public static func grade(level: LevelTestLevel, exam: Exam, attempts: [String: String], at: Int64 = 0) -> LevelTestResult {
        let verdicts = exam.items.map { item in
            ExerciseEngine.isCorrect(exercise: item.exercise, attempt: attempts[item.id] ?? "")
        }
        let overall = percent(verdicts)
        let scores = Dictionary(uniqueKeysWithValues: LevelTestSection.allCases.map { section in
            let sectionVerdicts = exam.items.enumerated().compactMap { index, item in item.section == section ? verdicts[index] : nil }
            return (section.rawValue, percent(sectionVerdicts))
        })
        let passed = overall >= (level.thresholds["overall"] ?? 70) && scores.allSatisfy {
            $0.value >= (level.thresholds[$0.key] ?? 60)
        }
        let weakSections = scores.filter { $0.value < (level.thresholds[$0.key] ?? 60) }
            .sorted { $0.value == $1.value ? $0.key < $1.key : $0.value < $1.value }.map(\.key)
        var seen = Set<String>()
        let weakUnits = exam.items.enumerated().compactMap { index, item -> String? in
            guard !verdicts[index], let unitID = item.unitID, seen.insert(unitID).inserted else { return nil }
            return unitID
        }.prefix(4)
        return LevelTestResult(languageID: level.languageID, level: exam.level, weekIndex: exam.weekIndex,
                               overall: overall, sections: scores, passed: passed,
                               nextBand: passed ? nextBand(exam.level) : nil,
                               weakSections: weakSections, weakUnitIDs: Array(weakUnits), at: at)
    }

    public static func gradeByAttempts(level: LevelTestLevel, exam: Exam, attempts: [String], at: Int64 = 0) -> LevelTestResult {
        let keyed = Dictionary(uniqueKeysWithValues: exam.items.enumerated().map {
            ($0.element.id, attempts.indices.contains($0.offset) ? attempts[$0.offset] : "")
        })
        return grade(level: level, exam: exam, attempts: keyed, at: at)
    }

    private static func stableBlock(_ items: [LevelTestItem], version: Int, count: Int, weekIndex: Int) -> [LevelTestItem] {
        let ordered = items.enumerated().sorted {
            let a = stableHash(version, "\($0.element.section.rawValue.uppercased())|\($0.element.id)")
            let b = stableHash(version, "\($1.element.section.rawValue.uppercased())|\($1.element.id)")
            return a == b ? $0.offset < $1.offset : a < b
        }.map(\.element)
        guard count > 0, !ordered.isEmpty else { return [] }
        let chunks = stride(from: 0, to: ordered.count, by: count).map { Array(ordered[$0..<min($0 + count, ordered.count)]) }
        let block = chunks[positiveModulo(weekIndex, chunks.count)]
        return block.count == count ? block : Array(ordered.prefix(count))
    }

    private static func stablePassages(_ level: LevelTestLevel, section: LevelTestSection, perPassage: Int) -> [LevelTestPassage] {
        level.passages.enumerated().filter { _, passage in
            level.pool.filter { $0.section == section && $0.passageId == passage.id }.count >= perPassage
        }.sorted {
            let a = stableHash(level.version, "\(section.rawValue.uppercased())|\($0.element.id)")
            let b = stableHash(level.version, "\(section.rawValue.uppercased())|\($1.element.id)")
            return a == b ? $0.offset < $1.offset : a < b
        }.map(\.element)
    }

    private static func selectedPassageBlock(_ passages: [LevelTestPassage], count: Int, weekIndex: Int) -> [String] {
        guard count > 0, !passages.isEmpty else { return [] }
        let chunks = stride(from: 0, to: passages.count, by: count).map { Array(passages[$0..<min($0 + count, passages.count)]) }
        let block = chunks[positiveModulo(weekIndex, chunks.count)]
        return (block.count == count ? block : Array(passages.prefix(count))).map(\.id)
    }

    private static func positiveModulo(_ value: Int, _ divisor: Int) -> Int {
        ((value % divisor) + divisor) % divisor
    }
    private static func percent(_ values: [Bool]) -> Int {
        values.isEmpty ? 0 : values.filter { $0 }.count * 100 / values.count
    }
}
