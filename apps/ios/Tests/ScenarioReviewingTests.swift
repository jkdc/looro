import XCTest
@testable import LearningCore

/// Mirrors the JVM `ScenarioPracticeTest` review cases (parse, validate, caps and
/// the archive scenario fields); runs under the macOS gate.
final class ScenarioReviewingTests: XCTestCase {
    private func makeSession(userFragmentID: String, text: String) -> SessionRecord {
        var session = SessionRecord(languageID: "en")
        session.append(Fragment(id: userFragmentID, speaker: .user, text: text, startMS: 0, endMS: 1000))
        return session
    }

    func testReviewValidationKeepsOnlyVerifiableErrors() {
        let session = makeSession(userFragmentID: "u1", text: "I go yesterday to the cinema")
        let review = ScenarioReview(createdAt: 1.0, strengths: ["  Clear storytelling  "],
            errors: [ReviewedError(quote: "I go yesterday", correction: "I went yesterday", kind: "grammar",
                                   note: "Past events need the past tense.", sourceIDs: ["u1"]),
                     ReviewedError(quote: "she never said this", correction: "x", kind: "grammar", note: "n", sourceIDs: ["u1"]),
                     ReviewedError(quote: "I go yesterday", correction: "I went yesterday", kind: "spelling", note: "n", sourceIDs: ["u1"]),
                     ReviewedError(quote: "I go yesterday", correction: "I went yesterday", kind: "grammar", note: "n", sourceIDs: []),
                     ReviewedError(quote: "I go yesterday", correction: "I went yesterday", kind: "grammar", note: "n", sourceIDs: ["zz"])],
            keyPhrases: [ReviewedPhrase(phrase: " the day after tomorrow ", meaning: " pasado mañana"),
                         ReviewedPhrase(phrase: "", meaning: "dropped")],
            retryFocus: "  Drill past tense.  ")
        let valid = ScenarioReviewing.validate(review, session: session)
        XCTAssertEqual(valid.errors.count, 1)
        XCTAssertEqual(valid.errors[0].correction, "I went yesterday")
        XCTAssertEqual(valid.errors[0].note, "Past events need the past tense.")
        XCTAssertEqual(valid.strengths, ["Clear storytelling"])
        XCTAssertEqual(valid.keyPhrases.count, 1)
        XCTAssertEqual(valid.keyPhrases[0].phrase, "the day after tomorrow")
        XCTAssertEqual(valid.retryFocus, "Drill past tense.")
        // The fragment-map entry point keeps the same verdict as the session one.
        let byID = Dictionary(session.fragments.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
        XCTAssertEqual(ScenarioReviewing.validate(review: review, fragmentsByID: byID), valid)
    }

    func testReviewErrorsAndPhrasesAreCapped() {
        let session = makeSession(userFragmentID: "u1", text: "I go yesterday")
        let errors = (1...10).map { _ in
            ReviewedError(quote: "I go yesterday", correction: "fix", kind: "grammar", note: "", sourceIDs: ["u1"])
        }
        let phrases = (1...10).map { ReviewedPhrase(phrase: "phrase\($0)", meaning: "meaning") }
        let valid = ScenarioReviewing.validate(ScenarioReview(errors: errors, keyPhrases: phrases), session: session)
        XCTAssertEqual(valid.errors.count, ScenarioReviewing.maximumErrors)
        XCTAssertEqual(valid.keyPhrases.count, ScenarioReviewing.maximumKeyPhrases)
    }

    func testReviewParseRejectsProseAndAcceptsPayloads() {
        XCTAssertNil(ScenarioReviewing.parse("Sorry, here is your review as prose."))
        let parsed = ScenarioReviewing.parse(
            #"{"strengths":["Fluent ordering"],"errors":[],"keyPhrases":[{"phrase":"the bill, please","meaning":"la cuenta, por favor"}],"retryFocus":"Try the past tense.","unknownField":1}"#)
        XCTAssertNotNil(parsed)
        XCTAssertEqual(parsed?.strengths.count, 1)
        XCTAssertEqual(parsed?.keyPhrases.first?.phrase, "the bill, please")
        // Omitted groups fall back to the same defaults as Kotlin's decoder.
        let empty = ScenarioReviewing.parse("{}")
        XCTAssertEqual(empty?.strengths.count, 0)
        XCTAssertEqual(empty?.retryFocus, "")
    }

    func testPreviousErrorsFormatsQuoteCorrectionPairs() {
        let review = ScenarioReview(errors: [
            ReviewedError(quote: "a", correction: "b", kind: "grammar", note: "", sourceIDs: ["u"]),
            ReviewedError(quote: "c", correction: "d", kind: "vocabulary", note: "", sourceIDs: ["u"])])
        XCTAssertEqual(ScenarioReviewing.previousErrors(review), "a → b; c → d")
    }

    func testArchiveRoundTripPreservesScenarioSessionAndReview() throws {
        var session = SessionRecord(languageID: "en", scenarioID: "job_interview")
        session.mode = "scenario"
        session.append(Fragment(id: "u1", speaker: .user, text: "I have twenty five years", startMS: 0, endMS: 900))
        session.scenarioReview = ScenarioReview(createdAt: 10.0, strengths: ["Good openers"],
            errors: [ReviewedError(quote: "I have twenty five years", correction: "I am twenty-five years old",
                                   kind: "grammar", note: "Use be for age.", sourceIDs: ["u1"])],
            keyPhrases: [ReviewedPhrase(phrase: "I'm applying for", meaning: "Me presento a")],
            retryFocus: "Practise be vs have.")
        var archive = Archive(); archive.sessions = [session]
        let decoded = try Archive.decode(archive.encoded())
        let restored = try XCTUnwrap(decoded.sessions.first)
        XCTAssertTrue(restored.isScenario)
        XCTAssertEqual(restored.scenarioID, "job_interview")
        XCTAssertEqual(restored.scenarioReview?.errors.first?.correction, "I am twenty-five years old")
        XCTAssertEqual(restored.scenarioReview?.retryFocus, "Practise be vs have.")
        // The Android twin also roundtrips a `scenario_practice` CompletionMark; that
        // kind joins Archive.completionKinds with the ProgressEngine mirror (wiring task).
    }

    func testLegacyArchivesWithoutScenarioFieldsStillDecode() throws {
        let session = makeSession(userFragmentID: "u1", text: "hello")
        var archive = Archive(); archive.sessions = [session]
        let raw = try JSONSerialization.jsonObject(with: archive.encoded())
        var root = try XCTUnwrap(raw as? [String: Any])
        let rawSessions = try XCTUnwrap(root["sessions"] as? [[String: Any]])
        var sessions = rawSessions
        sessions = sessions.map { original in
            var stripped = original
            stripped.removeValue(forKey: "scenarioID")
            stripped.removeValue(forKey: "scenarioReview")
            return stripped
        }
        root["sessions"] = sessions
        let decoded = try Archive.decode(try JSONSerialization.data(withJSONObject: root))
        let restored = try XCTUnwrap(decoded.sessions.first)
        XCTAssertNil(restored.scenarioID)
        XCTAssertNil(restored.scenarioReview)
        XCTAssertFalse(restored.isScenario)
    }
}
