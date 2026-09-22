import XCTest
@testable import LearningCore

/// Mirrors the JVM `CoursesTest`; runs under the macOS gate.
final class CoursesTests: XCTestCase {
    func testRegistryFoundationsCourseIsWellFormed() {
        let courses = CourseRegistry.forLanguage("en")
        XCTAssertEqual(courses.count, 1)
        let course = courses[0]
        XCTAssertEqual(course.id, "en-foundations")
        XCTAssertEqual(course.units.count, 6)
        for unit in course.units {
            XCTAssertEqual(unit.lessons.count, 3, unit.id)
        }
        XCTAssertEqual(course.units.reduce(0) { $0 + $1.lessons.count }, 18)
        XCTAssertGreaterThanOrEqual(course.units[0].themes.count, 2)
    }

    func testEveryUnitCarriesItsLevelLabel() {
        let units = CourseRegistry.forLanguage("en")[0].units
        XCTAssertTrue(units.allSatisfy { !$0.level.isEmpty })
        XCTAssertEqual(units.map(\.level), ["A1", "A1", "A1", "A1", "A2", "A2"])
    }

    func testLessonLookup() {
        XCTAssertEqual(CourseRegistry.lesson(id: "en-foundations-u1-to-be")?.title, "The verb “to be”")
        for unit in CourseRegistry.forLanguage("en")[0].units {
            XCTAssertNotNil(CourseRegistry.lesson(id: unit.lessons[0].id), unit.id)
        }
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

    func testArchivesWithoutLessonProgressStillDecode() throws {
        let directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("../../../shared/fixtures/cross-platform")
        let data = try Data(contentsOf: directory.appendingPathComponent("archive.json"))
        let archive = try Archive.decode(data)
        XCTAssertEqual(archive.lessons?.first?.lessonID, "en-foundations-u1-to-be")
        XCTAssertEqual(archive.lessons?.first?.attempts, 1)

        var legacy = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        legacy.removeValue(forKey: "lessons")
        let decoded = try Archive.decode(try JSONSerialization.data(withJSONObject: legacy))
        XCTAssertNil(decoded.lessons)
    }

    func testLessonSessionsRoundTrip() throws {
        var record = SessionRecord(languageID: "en", title: "The verb “to be”")
        record.mode = "lesson"
        record.lessonID = "en-foundations-u1-to-be"
        XCTAssertTrue(record.isLesson)
        XCTAssertFalse(record.isListening)
        let restored = try JSONDecoder().decode(SessionRecord.self, from: try JSONEncoder().encode(record))
        XCTAssertEqual(restored.lessonID, "en-foundations-u1-to-be")
        XCTAssertTrue(restored.isLesson)
    }
}
