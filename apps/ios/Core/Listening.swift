import Foundation

/// Listening comprehension: authored texts read aloud by the local voice and
/// answered in writing. Questions reuse the Exercise shape so ExerciseEngine
/// grades them unchanged; the data lives in `ListeningData` (generated from the
/// canonical JSON in shared/listening by scripts/export_listening.py — never
/// edit it by hand). `topic` labels the text; `unitID` links it to a course
/// unit when one exists.
public struct ListeningText: Identifiable, Hashable, Sendable {
    public var id: String
    public var languageID: String
    public var title: String
    public var level: String
    public var topic: String
    public var unitID: String?
    public var text: String
    public var questions: [Exercise]
    public init(id: String, languageID: String, title: String, level: String, topic: String,
                unitID: String? = nil, text: String, questions: [Exercise]) {
        self.id = id; self.languageID = languageID; self.title = title; self.level = level
        self.topic = topic; self.unitID = unitID
        self.text = text; self.questions = questions
    }
}

public enum ListeningRegistry {
    static let levelOrder = ["A1", "A2", "B1", "B2", "C1", "C2"]
    public static let all: [ListeningText] = ListeningData.all

    /// A language's texts in teaching order: level, then course-unit order,
    /// then id — so the Listen tab can group by level and follow the temario.
    public static func forLanguage(_ id: String) -> [ListeningText] {
        let unitOrder = CourseRegistry.all.first { $0.languageID == id }?.units.map(\.id) ?? []
        func key(_ text: ListeningText) -> (Int, Int, String) {
            let level = levelOrder.firstIndex(of: text.level) ?? levelOrder.count
            let unit = text.unitID.flatMap { unitOrder.firstIndex(of: $0) } ?? Int.max
            return (level, unit, text.id)
        }
        return all.filter { $0.languageID == id }.sorted { key($0) < key($1) }
    }

    public static func text(id: String) -> ListeningText? { all.first { $0.id == id } }
}
