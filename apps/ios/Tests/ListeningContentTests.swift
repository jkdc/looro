import XCTest
@testable import LearningCore

final class ListeningContentTests: XCTestCase {
    func testTenTextsAreRegisteredForEnglishWithClosedShapes() {
        let texts = ListeningRegistry.forLanguage("en")
        XCTAssertEqual(10, texts.count)
        for text in texts {
            XCTAssertTrue((40 ... 200).contains(text.text.split(separator: " ").count), text.id)
            XCTAssertGreaterThanOrEqual(text.questions.count, 3, text.id)
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
        XCTAssertEqual("A coffee before work", ListeningRegistry.text(id: "en-listen-coffee-morning")?.title)
        XCTAssertNil(ListeningRegistry.text(id: "en-listen-missing"))
        XCTAssertTrue(ListeningRegistry.forLanguage("es").isEmpty)
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
}
