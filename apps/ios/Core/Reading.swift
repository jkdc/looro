import Foundation

/// Reading comprehension: authored texts that stay visible while the reader
/// answers comprehension questions — the written sibling of the listenings.
/// Questions reuse the Exercise shape so ExerciseEngine grades them unchanged;
/// the data lives in `ReadingData` (generated from the canonical JSON in
/// shared/readings by scripts/export_readings.py — never edit it by hand).
/// `topic` labels the text; `unitID` links it to a course unit when one exists;
/// `skills`/`exposureSkills` reference the shared skill catalog.
public struct ReadingText: Identifiable, Hashable, Sendable {
    public var id: String
    public var languageID: String
    public var title: String
    public var level: String
    public var topic: String
    public var unitID: String?
    public var text: String
    public var questions: [Exercise]
    public var skills: [String]
    public var exposureSkills: [String]
    public init(id: String, languageID: String, title: String, level: String, topic: String,
                unitID: String? = nil, text: String, questions: [Exercise],
                skills: [String] = [], exposureSkills: [String] = []) {
        self.id = id; self.languageID = languageID; self.title = title; self.level = level
        self.topic = topic; self.unitID = unitID
        self.text = text; self.questions = questions
        self.skills = skills; self.exposureSkills = exposureSkills
    }
}

public enum ReadingRegistry {
    static let levelOrder = ["A1", "A2", "B1", "B2", "C1", "C2"]
    public static let all: [ReadingText] = ReadingData.all

    /// A language's texts in teaching order: level, then course-unit order,
    /// then id — the same projection the Listen tab uses.
    public static func forLanguage(_ id: String) -> [ReadingText] {
        let unitOrder = CourseRegistry.all.first { $0.languageID == id }?.units.map(\.id) ?? []
        func key(_ text: ReadingText) -> (Int, Int, String) {
            let level = levelOrder.firstIndex(of: text.level) ?? levelOrder.count
            let unit = text.unitID.flatMap { unitOrder.firstIndex(of: $0) } ?? Int.max
            return (level, unit, text.id)
        }
        return all.filter { $0.languageID == id }.sorted { key($0) < key($1) }
    }

    public static func text(id: String) -> ReadingText? { all.first { $0.id == id } }
}
