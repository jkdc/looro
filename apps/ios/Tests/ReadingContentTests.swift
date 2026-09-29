import XCTest
@testable import LearningCore

final class ReadingContentTests: XCTestCase {
    func testTenReadingsAreRegisteredForEnglishWithClosedShapes() {
        let texts = ReadingRegistry.forLanguage("en")
        XCTAssertEqual(10, texts.count)
        for text in texts {
            XCTAssertTrue((40 ... 220).contains(text.text.split(separator: " ").count), text.id)
            XCTAssertGreaterThanOrEqual(text.questions.count, 4, text.id)
            XCTAssertFalse(text.topic.isEmpty, text.id)
            XCTAssertEqual(text.questions.count, Set(text.questions.map(\.id)).count, text.id)
            for question in text.questions {
                XCTAssertTrue([ExerciseEngine.choice, ExerciseEngine.written].contains(question.kind), question.id)
                XCTAssertFalse(question.answers.isEmpty, question.id)
                if question.kind == ExerciseEngine.choice {
                    XCTAssertTrue(question.answers.allSatisfy { question.options.contains($0) }, question.id)
                }
            }
        }
    }

    func testEveryReadingQuestionGradesItsOwnAnswersCorrect() {
        for text in ReadingRegistry.all {
            for question in text.questions {
                XCTAssertTrue(ExerciseEngine.isCorrect(exercise: question, attempt: question.answers.first!),
                              "\(question.id): '\(question.answers.first!)' must grade correct")
            }
        }
    }

    func testEveryReadingSkillReferenceResolvesInTheShippedCatalog() {
        for text in ReadingRegistry.all {
            for ref in text.skills + text.exposureSkills {
                XCTAssertTrue(SkillsData.categories.keys.contains(ref), "\(text.id): \(ref) missing from SkillsData")
            }
        }
    }

    func testReadingsSortByLevelThenUnitOrderThenID() {
        let texts = ReadingRegistry.forLanguage("en")
        let levels = texts.map(\.level)
        XCTAssertEqual("levels must not regress", levels, levels.sorted())
        let firstA2 = texts.firstIndex { $0.level == "A2" } ?? texts.count
        let firstA1 = texts.firstIndex { $0.level == "A1" } ?? texts.count
        XCTAssertTrue(firstA2 > firstA1)
    }
}

final class CheckpointContentTests: XCTestCase {
    func testEnglishCarriesTheA1AndA2CheckpointsWithClosedShapes() {
        let checkpoints = CheckpointRegistry.forLanguage("en")
        XCTAssertEqual(["checkpoint-en-a1", "checkpoint-en-a2"], checkpoints.map(\.id))
        for checkpoint in checkpoints {
            XCTAssertGreaterThanOrEqual(checkpoint.questions.count, 12, checkpoint.id)
            XCTAssertTrue(checkpoint.thresholds.keys.contains("overall"), checkpoint.id)
            for question in checkpoint.questions {
                XCTAssertTrue(SkillsData.categories.keys.contains(question.skill), question.exercise.id)
                XCTAssertFalse(question.exercise.answers.isEmpty, question.exercise.id)
            }
        }
    }

    func testGradingAppliesTheOverallThresholdAndCategoryFloors() {
        let checkpoint = CheckpointRegistry.checkpoint(id: "checkpoint-en-a1")!
        let perfect = CheckpointEngine.grade(checkpoint: checkpoint,
                                             answers: checkpoint.questions.map { $0.exercise.answers.first! })
        XCTAssertEqual(100, perfect.overall)
        XCTAssertTrue(perfect.passed)
        let half = CheckpointEngine.grade(checkpoint: checkpoint,
                                          answers: checkpoint.questions.map { $0.exercise.kind == ExerciseEngine.written ? "" : $0.exercise.answers.first! })
        XCTAssertFalse(half.passed)
    }

    func testVerdictsAlignWithTheQuestionOrder() {
        let checkpoint = CheckpointRegistry.checkpoint(id: "checkpoint-en-a1")!
        XCTAssertTrue(CheckpointEngine.verdicts(checkpoint: checkpoint,
                                                answers: checkpoint.questions.map { _ in "" }).allSatisfy { !$0 })
        XCTAssertTrue(CheckpointEngine.verdicts(checkpoint: checkpoint,
                                                answers: checkpoint.questions.map { $0.exercise.answers.first! }).allSatisfy { $0 })
    }
}
