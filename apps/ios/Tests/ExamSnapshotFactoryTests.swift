import XCTest
import CryptoKit
@testable import LearningCore

final class ExamSnapshotFactoryTests: XCTestCase {
    func testPublishedSnapshotIsValidatedAndPreparedWithItsServerIdentity() throws {
        let level = try XCTUnwrap(LevelTestsRegistry.level(languageID: "en", level: "B1"))
        let exam = LevelTestEngine.compose(level: level, weekIndex: 202641)
        let bundled = try ExamSnapshotFactory.bundled(level: level, exam: exam)
        var remote = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(bundled.contentJSON.utf8)) as? [String: Any])
        remote["source"] = "deterministic"
        remote["contentRevision"] = remote["version"]
        remote["gradingVersion"] = 1
        remote.removeValue(forKey: "examId")
        remote.removeValue(forKey: "contentId")
        remote.removeValue(forKey: "contentHash")
        let canonical = try JSONSerialization.data(withJSONObject: remote, options: [.sortedKeys])
        let hash = SHA256.hash(data: canonical).map { String(format: "%02x", $0) }.joined()
        let contentID = "sha256:\(hash)"
        remote["contentHash"] = hash
        remote["contentId"] = contentID
        remote["examId"] = contentID
        let response = try JSONSerialization.data(withJSONObject: remote, options: [.sortedKeys])
        let prepared = try ExamSnapshotFactory.prepare(snapshotJSON: String(decoding: response, as: UTF8.self))

        XCTAssertEqual(contentID, prepared.snapshot.examID)
        XCTAssertEqual(hash, prepared.snapshot.contentHash)
        XCTAssertEqual("deterministic", prepared.snapshot.source)
        XCTAssertEqual(exam.items.map(\.id), prepared.exam.items.map(\.id))
    }

    func testPublishedSnapshotRejectsChangedContentWithStaleHash() throws {
        let level = try XCTUnwrap(LevelTestsRegistry.level(languageID: "en", level: "B1"))
        let exam = LevelTestEngine.compose(level: level, weekIndex: 202641)
        let bundled = try ExamSnapshotFactory.bundled(level: level, exam: exam)
        var remote = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(bundled.contentJSON.utf8)) as? [String: Any])
        remote["items"] = []
        let response = try JSONSerialization.data(withJSONObject: remote, options: [.sortedKeys])
        XCTAssertThrowsError(try ExamSnapshotFactory.prepare(snapshotJSON: String(decoding: response, as: UTF8.self)))
    }

    func testBundledSnapshotUsesCanonicalIdentityAndCanBePersisted() throws {
        let level = try XCTUnwrap(LevelTestsRegistry.level(languageID: "en", level: "B1"))
        let exam = LevelTestEngine.compose(level: level, weekIndex: 202641)

        let first = try ExamSnapshotFactory.bundled(level: level, exam: exam)
        let second = try ExamSnapshotFactory.bundled(level: level, exam: exam)

        XCTAssertEqual(first.examID, first.contentID)
        XCTAssertEqual(first.contentID, "sha256:\(first.contentHash)")
        XCTAssertEqual(first.contentHash, second.contentHash)
        XCTAssertEqual(first.contentJSON, second.contentJSON)
        XCTAssertTrue(first.contentJSON.contains("\"weekId\":202641"))
        XCTAssertTrue(first.contentJSON.contains("\"source\":\"bundled\""))
        let restored = try ExamSnapshotFactory.restore(contentJSON: first.contentJSON)
        XCTAssertEqual(restored.exam.items.map(\.id), exam.items.map(\.id))
        XCTAssertEqual(restored.exam.items.map(\.exercise.answers), exam.items.map(\.exercise.answers))
        XCTAssertEqual(restored.level.thresholds, level.thresholds)
    }
}
