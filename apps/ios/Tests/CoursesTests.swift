import XCTest
@testable import MuralCore

/// Mirrors the JVM `CoursesTest`; runs under the macOS gate.
final class CoursesTests: XCTestCase {
    func testRegistryPilotCourseIsWellFormed() {
        let courses = CourseRegistry.forLanguage("en")
        XCTAssertEqual(courses.count, 1)
        let course = courses[0]
        XCTAssertEqual(course.id, "en-foundations")
        XCTAssertEqual(course.units.count, 1)
        let unit = course.units[0]
        XCTAssertEqual(unit.lessons.count, 1)
        XCTAssertGreaterThanOrEqual(unit.themes.count, 2)
    }

    func testLessonLookup() {
        let lesson = CourseRegistry.lesson(id: "en-foundations-u1-to-be")
        XCTAssertEqual(lesson?.title, "The verb “to be”")
        XCTAssertFalse(lesson!.intro.explanation.isEmpty)
        XCTAssertFalse(lesson!.practice.questions.isEmpty)
        XCTAssertNil(CourseRegistry.lesson(id: "missing"))
    }

    func testEveryPracticeQuestionCarriesGuidance() {
        for course in CourseRegistry.all {
            for unit in course.units {
                for lesson in unit.lessons {
                    XCTAssertFalse(lesson.practice.questions.isEmpty, lesson.id)
                    for question in lesson.practice.questions {
                        XCTAssertFalse(question.expected.isEmpty, lesson.id)
                        XCTAssertFalse(question.focus.isEmpty, lesson.id)
                        XCTAssertFalse(question.watch.isEmpty, lesson.id)
                    }
                }
            }
        }
    }
}
