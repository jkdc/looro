import XCTest
@testable import LearningCore

final class ListeningContentTests: XCTestCase {
    func testTwentyThreeTextsAreRegisteredForEnglishWithClosedShapes() {
        let texts = ListeningRegistry.forLanguage("en")
        XCTAssertEqual(23, texts.count)
        for text in texts {
            XCTAssertTrue((40 ... 200).contains(text.text.split(separator: " ").count), text.id)
            XCTAssertGreaterThanOrEqual(text.questions.count, 5, text.id)
            XCTAssertFalse(text.topic.isEmpty, text.id)
            XCTAssertEqual(text.questions.count, Set(text.questions.map(\.id)).count, text.id)
            for question in text.questions {
                XCTAssertTrue([ExerciseEngine.choice, ExerciseEngine.written].contains(question.kind), question.id)
                XCTAssertFalse(question.answers.isEmpty, question.id)
                XCTAssertFalse(question.explain.isEmpty, question.id)
                if question.kind == ExerciseEngine.choice {
                    XCTAssertGreaterThanOrEqual(question.options.count, 2, question.id)
                    XCTAssertTrue(question.answers.allSatisfy { question.options.contains($0) }, question.id)
                } else {
                    XCTAssertTrue(question.options.isEmpty, question.id)
                }
            }
        }
    }

    func testLookupFindsTextsAndMissesUnknownOnes() {
        XCTAssertEqual("The language café", ListeningRegistry.text(id: "en-listen-language-cafe")?.title)
        XCTAssertNil(ListeningRegistry.text(id: "en-listen-missing"))
        XCTAssertTrue(ListeningRegistry.forLanguage("nb").isEmpty)
        for language in ["es", "fr", "it", "de", "pt"] {
            XCTAssertEqual(6, ListeningRegistry.forLanguage(language).count, language)
        }
    }

    func testEveryQuestionGradesItsOwnAnswersCorrect() {
        for text in ListeningRegistry.all {
            for question in text.questions {
                let attempt = question.answers.first!
                XCTAssertTrue(ExerciseEngine.isCorrect(exercise: question, attempt: attempt),
                              "\(question.id): '\(attempt)' must grade correct")
            }
        }
    }

    func testTextsSortByLevelThenUnitOrderThenID() {
        let texts = ListeningRegistry.forLanguage("en")
        let levels = texts.map(\.level)
        XCTAssertEqual("levels must not regress", levels, levels.sorted())
        XCTAssertEqual(["en-foundations-u1", "en-foundations-u1", "en-foundations-u2", "en-foundations-u2",
                        "en-foundations-u3", "en-foundations-u3", "en-foundations-u4", "en-foundations-u4",
                        "en-foundations-now", "en-foundations-now", "en-foundations-days", "en-foundations-days"],
                       texts.filter { $0.level == "A1" }.map(\.unitID))
        // Extras without a unit come after the unit-linked texts of their level.
        XCTAssertEqual(["en-foundations-u5", "en-foundations-u5", "en-foundations-u6", "en-foundations-u6",
                        "en-foundations-compare", "en-foundations-compare", "en-foundations-plans", "en-foundations-plans",
                        nil, nil, nil], texts.filter { $0.level == "A2" }.map(\.unitID))
    }

    func testUnitLinkedTextsPointAtASameLevelUnitOfTheirCourse() {
        for text in ListeningRegistry.all {
            let unit = text.unitID.flatMap { id in
                CourseRegistry.all.first { $0.languageID == text.languageID }?.units.first { $0.id == id }
            }
            if text.unitID != nil {
                XCTAssertNotNil(unit, "\(text.id): unit not found")
                XCTAssertEqual(text.level, unit?.level, "\(text.id): level mismatch")
            }
        }
    }
}
