import Foundation
import CryptoKit

/// Creates the same immutable snapshot identity used by the API and Android
/// for the offline bundled exam bank.
public enum ExamSnapshotFactory {
    public struct RestoredExam {
        public let level: LevelTestLevel
        public let exam: LevelTestEngine.Exam
    }

    public struct PreparedExam {
        public let snapshot: ExamSnapshotRecord
        public let level: LevelTestLevel
        public let exam: LevelTestEngine.Exam
    }

    /// Verifies the server's canonical snapshot identity before preparing it for local persistence.
    public static func prepare(snapshotJSON: String, now: Date = .now) throws -> PreparedExam {
        guard let bytes = snapshotJSON.data(using: .utf8),
              let value = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any],
              let examID = value["examId"] as? String, !examID.isEmpty, examID.utf8.count <= 128,
              let contentID = value["contentId"] as? String,
              let contentHash = value["contentHash"] as? String,
              contentHash.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
              contentID == "sha256:\(contentHash)",
              let schemaVersion = (value["schemaVersion"] as? NSNumber)?.intValue,
              schemaVersion == 1,
              let contentRevision = (value["contentRevision"] as? NSNumber)?.intValue, contentRevision > 0,
              let gradingVersion = (value["gradingVersion"] as? NSNumber)?.intValue, gradingVersion > 0,
              let source = value["source"] as? String, ["bundled", "deterministic", "llm"].contains(source),
              let languageID = value["languageID"] as? String,
              let levelID = value["level"] as? String,
              let weekID = (value["weekId"] as? NSNumber)?.int64Value, weekID > 0 else {
            throw ExamHistoryError.invalidJSON
        }
        var content = value
        for key in ["examId", "contentId", "contentHash", "generationId"] {
            content.removeValue(forKey: key)
        }
        guard let canonical = try? JSONSerialization.data(withJSONObject: content, options: [.sortedKeys]),
              SHA256.hash(data: canonical).map({ String(format: "%02x", $0) }).joined() == contentHash else {
            throw ExamHistoryError.invalidJSON
        }
        let restored = try restore(contentJSON: snapshotJSON)
        let record = ExamSnapshotRecord(examID: examID, contentID: contentID, contentHash: contentHash,
            schemaVersion: schemaVersion, contentRevision: contentRevision, gradingVersion: gradingVersion,
            source: source, languageID: languageID, level: levelID, weekID: weekID,
            contentJSON: snapshotJSON, createdAt: now.timeIntervalSince1970)
        return PreparedExam(snapshot: record, level: restored.level, exam: restored.exam)
    }

    private struct SnapshotItem: Decodable {
        let id: String
        let section: LevelTestSection
        let kind: String
        let prompt: String
        let options: [String]?
        let answers: [String]
        let explain: String?
        let unitID: String?
        let passageId: String?
        let speech: String?
    }

    private struct SnapshotPayload: Decodable {
        let languageID: String
        let level: String
        let version: Int
        let weekId: Int
        let thresholds: [String: Int]
        let passages: [LevelTestPassage]
        let readingPassageIds: [String]
        let listeningPassageIds: [String]
        let items: [SnapshotItem]
    }

    /// Rehydrates the exam from its immutable stored payload, never today's bank.
    public static func restore(contentJSON: String) throws -> RestoredExam {
        guard let data = contentJSON.data(using: .utf8),
              let payload = try? JSONDecoder().decode(SnapshotPayload.self, from: data),
              payload.weekId > 0, !payload.items.isEmpty else { throw ExamHistoryError.invalidJSON }
        let items = payload.items.map { item in
            LevelTestItem(id: item.id, section: item.section,
                          exercise: Exercise(id: item.id, kind: item.kind, prompt: item.prompt,
                                             options: item.options ?? [], answers: item.answers,
                                             explain: item.explain ?? ""),
                          unitID: item.unitID, passageId: item.passageId, speech: item.speech)
        }
        let level = LevelTestLevel(languageID: payload.languageID, level: payload.level,
                                   version: payload.version, thresholds: payload.thresholds,
                                   passages: payload.passages, pool: items)
        let exam = LevelTestEngine.Exam(weekIndex: payload.weekId, level: payload.level, items: items,
                                        passageById: Dictionary(uniqueKeysWithValues: payload.passages.map { ($0.id, $0) }),
                                        readingPassageIDs: payload.readingPassageIds,
                                        listeningPassageIDs: payload.listeningPassageIds)
        return RestoredExam(level: level, exam: exam)
    }

    /// `source` lets tests forge a self-consistent snapshot of another origin —
    /// e.g. a generated exam whose week was retired server-side — while keeping
    /// the canonical hash valid for `prepare(_:)`.
    public static func bundled(level: LevelTestLevel, exam: LevelTestEngine.Exam,
                               now: Date = .now, source: String = "bundled") throws -> ExamSnapshotRecord {
        guard ["bundled", "deterministic", "llm"].contains(source) else {
            throw ExamHistoryError.invalidJSON
        }
        let passages: [[String: Any]] = level.passages.map { [
            "id": $0.id, "title": $0.title, "text": $0.text,
        ] }
        let items: [[String: Any]] = exam.items.map { item in
            var value: [String: Any] = [
                "id": item.id, "section": item.section.rawValue,
                "kind": item.exercise.kind, "prompt": item.exercise.prompt,
                "answers": item.exercise.answers, "explain": item.exercise.explain,
            ]
            if !item.exercise.options.isEmpty { value["options"] = item.exercise.options }
            if let unitID = item.unitID { value["unitID"] = unitID }
            if let passageID = item.passageId { value["passageId"] = passageID }
            if let speech = item.speech { value["speech"] = speech }
            return value
        }
        var content: [String: Any] = [
            "schemaVersion": 1,
            "languageID": level.languageID,
            "level": level.level,
            "version": level.version,
            "weekId": exam.weekIndex,
            "expiresAt": expiry(for: now),
            "thresholds": level.thresholds,
            "passages": passages,
            "readingPassageIds": exam.readingPassageIDs,
            "listeningPassageIds": exam.listeningPassageIDs,
            "items": items,
            "contentRevision": level.version,
            "gradingVersion": 1,
            "source": source,
        ]
        let canonicalBytes = try JSONSerialization.data(withJSONObject: content, options: [.sortedKeys])
        let hash = SHA256.hash(data: canonicalBytes).map { String(format: "%02x", $0) }.joined()
        let contentID = "sha256:\(hash)"
        content["examId"] = contentID
        content["contentId"] = contentID
        content["contentHash"] = hash
        let snapshotBytes = try JSONSerialization.data(withJSONObject: content, options: [.sortedKeys])
        guard let snapshotJSON = String(data: snapshotBytes, encoding: .utf8) else {
            throw ExamHistoryError.invalidJSON
        }
        return ExamSnapshotRecord(examID: contentID, contentID: contentID, contentHash: hash,
                                  schemaVersion: 1, contentRevision: level.version, gradingVersion: 1,
                                  source: source, languageID: level.languageID, level: level.level,
                                  weekID: Int64(exam.weekIndex), contentJSON: snapshotJSON,
                                  createdAt: now.timeIntervalSince1970)
    }

    private static func expiry(for date: Date) -> String {
        var calendar = Calendar(identifier: .iso8601)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let start = calendar.startOfDay(for: date)
        let weekday = calendar.component(.weekday, from: start)
        let daysUntilMonday = (9 - weekday) % 7
        let days = daysUntilMonday == 0 ? 7 : daysUntilMonday
        let nextMonday = calendar.date(byAdding: .day, value: days, to: start)!
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        return formatter.string(from: nextMonday)
    }
}
