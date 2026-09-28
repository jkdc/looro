import XCTest
@testable import LearningCore

final class ExerciseEngineTests: XCTestCase {
    private func exercise(_ kind: String, prompt: String = "p", options: [String] = [],
                          answers: [String], explain: String = "e") -> Exercise {
        Exercise(id: "test-exercise", kind: kind, prompt: prompt, options: options, answers: answers, explain: explain)
    }

    func testNormalizeStripsPunctuationCaseAndSpacing() {
        XCTAssertEqual("i'm ana", ExerciseEngine.normalize("  I'm Ana.  "))
        XCTAssertEqual("where are you from", ExerciseEngine.normalize("¿Where ARE you FROM?"))
        XCTAssertEqual("one two", ExerciseEngine.normalize("one   two"))
    }

    func testChoiceComparesThePickedOptionExactly() {
        let e = exercise(ExerciseEngine.choice, options: ["is", "are"], answers: ["is"])
        XCTAssertTrue(ExerciseEngine.isCorrect(exercise: e, attempt: "is"))
        XCTAssertTrue(ExerciseEngine.isCorrect(exercise: e, attempt: " is "))
        XCTAssertFalse(ExerciseEngine.isCorrect(exercise: e, attempt: "IS"))
        XCTAssertFalse(ExerciseEngine.isCorrect(exercise: e, attempt: "are"))
    }

    func testOrderComparesNormalizedTokenSequences() {
        let e = exercise(ExerciseEngine.order, answers: ["I am from Spain"])
        XCTAssertTrue(ExerciseEngine.isCorrect(exercise: e, attempt: "I am from Spain"))
        XCTAssertTrue(ExerciseEngine.isCorrect(exercise: e, attempt: "i am from spain"))
        XCTAssertFalse(ExerciseEngine.isCorrect(exercise: e, attempt: "am I from Spain"))
    }

    func testGapAcceptsTheNormalizedMissingWord() {
        let e = exercise(ExerciseEngine.gap, prompt: "They ___ my friends.", answers: ["are"])
        XCTAssertTrue(ExerciseEngine.isCorrect(exercise: e, attempt: "are"))
        XCTAssertTrue(ExerciseEngine.isCorrect(exercise: e, attempt: " Are "))
        XCTAssertFalse(ExerciseEngine.isCorrect(exercise: e, attempt: "is"))
    }

    func testWrittenAcceptsVariantsAndPlaceholderSpans() {
        let e = exercise(ExerciseEngine.written, answers: ["I'm <name>", "My name is <name>"])
        XCTAssertTrue(ExerciseEngine.isCorrect(exercise: e, attempt: "I'm Ana"))
        XCTAssertTrue(ExerciseEngine.isCorrect(exercise: e, attempt: "I am Maria."))
        XCTAssertTrue(ExerciseEngine.isCorrect(exercise: e, attempt: "my name is Inma"))
        XCTAssertFalse(ExerciseEngine.isCorrect(exercise: e, attempt: "I'm"))
        XCTAssertFalse(ExerciseEngine.isCorrect(exercise: e, attempt: "Ana am I"))
    }

    func testEveryAuthoredExerciseGradesItsOwnAnswersCorrect() {
        for lesson in CourseRegistry.all.flatMap(\.units).flatMap(\.lessons) {
            for exerciseItem in lesson.exercises {
                let attempt = exerciseItem.answers.first!
                XCTAssertTrue(ExerciseEngine.isCorrect(exercise: exerciseItem, attempt: attempt),
                              "\(exerciseItem.id): '\(attempt)' must grade correct")
            }
        }
    }
}
