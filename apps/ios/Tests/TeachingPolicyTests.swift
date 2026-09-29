import XCTest
@testable import LearningCore

/// Mirrors the JVM `TeachingPolicyTest`; runs under the macOS gate.
final class TeachingPolicyTests: XCTestCase {
    private let language = LanguageRegistry.all.first!
    private let learner = LearnerState(challenge: 1, observationCount: 0, nextGoal: "A goal",
                                       capabilities: [], words: [])
    private let theme = ConversationTheme("coffee", "A coffee?", "Something warm, please", "cup",
                                          "Everyday", "You work in a cosy café. Help the learner order, then chat naturally.", 0)

    func testAChosenThemeAnchorsTheSessionInstructions() {
        let prompt = TeachingPolicy.voice(language: language, learner: learner, theme: theme,
                                          interests: "", meaningLanguage: "English")
        XCTAssertTrue(prompt.contains("Theme: A coffee?"))
        XCTAssertTrue(prompt.contains(theme.situation))
        XCTAssertTrue(prompt.contains(TeachingPolicy.themeSteering(language: language)))
        XCTAssertTrue(prompt.contains("drifts too far"))
        XCTAssertFalse(prompt.contains(TeachingPolicy.freeConversationContext()))
    }

    func testWithoutAThemeTheInstructionsStayFreeConversation() {
        let prompt = TeachingPolicy.voice(language: language, learner: learner, theme: nil,
                                          interests: "", meaningLanguage: "English")
        XCTAssertTrue(prompt.contains(TeachingPolicy.freeConversationContext()))
        XCTAssertFalse(prompt.contains(TeachingPolicy.themeSteering(language: language)))
    }

    func testTheOpeningGreetingStepsIntoTheThemeOnlyWhenOneIsSet() {
        let themed = TeachingPolicy.greeting(language: language, theme: theme)
        XCTAssertTrue(themed.contains(TeachingPolicy.themeOpening()))
        XCTAssertTrue(themed.contains(language.greeting))
        XCTAssertFalse(TeachingPolicy.greeting(language: language).contains(TeachingPolicy.themeOpening()))
    }

    func testMidSessionThemeSwitchAnchorsTheConversation() {
        let instruction = TeachingPolicy.theme(theme, language: language)
        XCTAssertTrue(instruction.contains("Anchor the conversation here"))
        XCTAssertTrue(instruction.contains("drifts too far"))
        XCTAssertTrue(instruction.contains(theme.situation))
        XCTAssertFalse(TeachingPolicy.theme(nil, language: language).contains(theme.situation))
    }

    private let lesson = CourseRegistry.lesson(id: "en-foundations-u1-to-be")!

    func testPracticePromptCarriesTheRigorPolicyAndEveryQuestion() {
        let prompt = TeachingPolicy.lessonPractice(lesson: lesson, language: language)
        XCTAssertTrue(prompt.contains("IN ORDER, one at a time"))
        XCTAssertTrue(prompt.contains("say explicitly whether it was correct"))
        XCTAssertTrue(prompt.contains("recast the correct form"))
        XCTAssertTrue(prompt.contains(lesson.title))
        XCTAssertTrue(prompt.contains(lesson.goal))
        for question in lesson.practice.questions {
            XCTAssertTrue(prompt.contains(question.prompt), lesson.id)
            XCTAssertTrue(prompt.contains(question.focus), lesson.id)
            XCTAssertTrue(prompt.contains(question.watch), lesson.id)
        }
    }

    func testLessonOpeningIntroducesTheLesson() {
        let opening = TeachingPolicy.lessonOpening(lesson: lesson, language: language)
        XCTAssertTrue(opening.contains(lesson.title))
        XCTAssertTrue(opening.contains("first practice question"))
        XCTAssertTrue(opening.contains(language.name))
    }

    func testTypedReplyReferencesTheLessonOnlyWhenOneIsSet() {
        XCTAssertTrue(TeachingPolicy.typedReply(language: language, lesson: lesson).contains(lesson.title))
        XCTAssertTrue(TeachingPolicy.typedReply(language: language, lesson: lesson).contains(lesson.goal))
        XCTAssertFalse(TeachingPolicy.typedReply(language: language).contains("lesson"))
    }
}
