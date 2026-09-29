import Foundation

/// Course temarios: authored teaching content. The data lives in
/// `CoursesData.swift` (generated from `shared/courses/*.json` by
/// `scripts/export_courses.py` — never edit it by hand); these are the
/// hand-written shapes and the lookup surface.

public struct Course: Identifiable, Hashable, Sendable {
    public var id: String
    public var languageID: String
    public var title: String
    public var subtitle: String
    public var symbol: String
    public var description: String
    public var units: [CourseUnit]
    public init(id: String, languageID: String, title: String, subtitle: String, symbol: String, description: String, units: [CourseUnit]) {
        self.id = id; self.languageID = languageID; self.title = title; self.subtitle = subtitle
        self.symbol = symbol; self.description = description; self.units = units
    }
}

public struct CourseUnit: Identifiable, Hashable, Sendable {
    public var id: String
    public var title: String
    public var subtitle: String
    public var level: String
    public var themes: [ConversationTheme]
    public var lessons: [Lesson]
    public init(id: String, title: String, subtitle: String, level: String, themes: [ConversationTheme], lessons: [Lesson]) {
        self.id = id; self.title = title; self.subtitle = subtitle; self.level = level
        self.themes = themes; self.lessons = lessons
    }
}

public struct Lesson: Identifiable, Hashable, Sendable {
    public var id: String
    public var title: String
    public var goal: String
    public var intro: LessonIntro
    public var expressions: [TargetExpression]
    public var situation: String
    public var practice: LessonPractice
    public var exercises: [Exercise]
    public init(id: String, title: String, goal: String, intro: LessonIntro, expressions: [TargetExpression],
                situation: String, practice: LessonPractice, exercises: [Exercise] = []) {
        self.id = id; self.title = title; self.goal = goal; self.intro = intro
        self.expressions = expressions; self.situation = situation; self.practice = practice
        self.exercises = exercises
    }
}

public struct LessonIntro: Hashable, Sendable {
    public var explanation: [String]
    public var examples: [ExampleLine]
    public init(explanation: [String], examples: [ExampleLine]) {
        self.explanation = explanation; self.examples = examples
    }
}

public struct ExampleLine: Hashable, Sendable {
    public var text: String
    public var meaning: String
    public init(text: String, meaning: String) { self.text = text; self.meaning = meaning }
}

public struct TargetExpression: Hashable, Sendable {
    public var text: String
    public var meaning: String
    public var note: String
    public init(text: String, meaning: String, note: String) {
        self.text = text; self.meaning = meaning; self.note = note
    }
}

public struct LessonPractice: Hashable, Sendable {
    public var questions: [PracticeQuestion]
    public init(questions: [PracticeQuestion]) { self.questions = questions }
}

public struct PracticeQuestion: Hashable, Sendable {
    public var prompt: String
    public var expected: [String]
    public var focus: String
    public var watch: String
    public init(prompt: String, expected: [String], focus: String, watch: String) {
        self.prompt = prompt; self.expected = expected; self.focus = focus; self.watch = watch
    }
}

/// Authored offline drill. `kind` is one of ExerciseEngine's choice/gap/order/written;
/// `options` is filled for choice only; `<name>` spans in answers accept any text.
public struct Exercise: Identifiable, Hashable, Sendable {
    public var id: String
    public var kind: String
    public var prompt: String
    public var options: [String]
    public var answers: [String]
    public var explain: String
    public init(id: String, kind: String, prompt: String, options: [String], answers: [String], explain: String) {
        self.id = id; self.kind = kind; self.prompt = prompt; self.options = options
        self.answers = answers; self.explain = explain
    }
}

public enum CourseRegistry {
    public static let all: [Course] = CoursesData.all

    public static func forLanguage(_ id: String) -> [Course] {
        all.filter { $0.languageID == id }
    }

    public static func lesson(id: String) -> Lesson? {
        for course in all {
            for unit in course.units {
                if let lesson = unit.lessons.first(where: { $0.id == id }) { return lesson }
            }
        }
        return nil
    }
}
