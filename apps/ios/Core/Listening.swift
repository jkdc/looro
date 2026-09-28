import Foundation

/// Listening comprehension: authored texts read aloud by the local voice and
/// answered in writing. Questions reuse the Exercise shape so ExerciseEngine
/// grades them unchanged; the data lives in `ListeningData` (generated from the
/// canonical JSON in shared/listening by scripts/export_listening.py — never
/// edit it by hand).
public struct ListeningText: Identifiable, Hashable, Sendable {
    public var id: String
    public var languageID: String
    public var title: String
    public var level: String
    public var text: String
    public var questions: [Exercise]
    public init(id: String, languageID: String, title: String, level: String, text: String, questions: [Exercise]) {
        self.id = id; self.languageID = languageID; self.title = title; self.level = level
        self.text = text; self.questions = questions
    }
}

public enum ListeningRegistry {
    public static let all: [ListeningText] = ListeningData.all
    public static func forLanguage(_ id: String) -> [ListeningText] { all.filter { $0.languageID == id } }
    public static func text(id: String) -> ListeningText? { all.first { $0.id == id } }
}
