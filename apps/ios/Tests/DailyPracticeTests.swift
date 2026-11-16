import Foundation
import XCTest
@testable import LearningCore

final class DailyPracticeTests: XCTestCase {
    private func englishFixturePackage() throws -> DailyPracticePackage {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("../../../shared/contracts/daily-practice/fixtures/day-en.json")
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: root)) as? [String: Any])
        var listening = try XCTUnwrap(object["listening"] as? [String: Any])
        listening.removeValue(forKey: "audio")
        listening["speechLocale"] = "en-US"
        object["listening"] = listening
        return try DailyPracticePackage.decode(JSONSerialization.data(withJSONObject: object))
    }
    func testSharedEnglishFixtureDecodesWithCommonTopicAndStageCounts() throws {
        let package = try englishFixturePackage()
        XCTAssertEqual(package.topic.id, "city-gardens")
        XCTAssertEqual(package.expressions.count, 5)
        XCTAssertEqual(package.listening.questions.count, 3)
        XCTAssertEqual(package.practice.questions.count, 4)
    }

    func testListeningUsesProfileSpeechLocaleAndTranscriptInsteadOfAudioMetadata() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("../../../shared/contracts/daily-practice/fixtures/day-en.json")
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: root)) as? [String: Any])
        var listening = try XCTUnwrap(object["listening"] as? [String: Any])
        listening.removeValue(forKey: "audio")
        listening["speechLocale"] = "en-US"
        object["listening"] = listening

        let package = try DailyPracticePackage.decode(JSONSerialization.data(withJSONObject: object))
        XCTAssertEqual(package.listening.speechLocale, "en-US")
        XCTAssertFalse(package.listening.transcript.isEmpty)
    }

    func testDraftPersistsIncrementalAnswersAndResumesWithoutExpiry() throws {
        let package = try englishFixturePackage()
        var draft = try DailyPracticeDraft.start(attemptID: "attempt-1", owner: "member-1", package: package,
                                                 explanationLanguage: "es", restoreGeneration: 7)
            .advance(to: .listening)
        draft = try draft.answer(questionID: package.listening.questions[0].id, value: "answer", helpUsed: [])
        draft = try draft.advance(to: .speaking)
        let restored = try JSONDecoder().decode(DailyPracticeDraft.self, from: JSONEncoder().encode(draft))
        XCTAssertEqual(restored.stage, .speaking)
        XCTAssertEqual(restored.answers[package.listening.questions[0].id], "answer")
        XCTAssertEqual(restored.restoreGeneration, 7)
        XCTAssertNil(restored.expiresAt)
        XCTAssertEqual(restored.restored(for: 8).restoreGeneration, 8)
        XCTAssertEqual(restored.restored(for: 8).answers, restored.answers)
    }

    func testSpeakingMustBeExplicitlyClosedBeforeAdvancing() throws {
        let package = try englishFixturePackage()
        let draft = try DailyPracticeDraft.start(attemptID: "attempt-2", owner: "member-1", package: package,
                                                  explanationLanguage: "en", restoreGeneration: 2)
            .advance(to: .listening).advance(to: .speaking)
            .appendSpeakingFragment(role: .learner, text: "I like gardens.")
        XCTAssertThrowsError(try draft.advance(to: .practice))
        XCTAssertTrue(try draft.closeSpeaking().advance(to: .practice).speakingClosed)
    }

    func testSpeakingSequenceRestartsOnlyForANewServerSegmentAndKeepsPriorFragments() throws {
        let package = try englishFixturePackage()
        let draft = try DailyPracticeDraft.start(attemptID: "attempt-segments", owner: "member-1", package: package,
            explanationLanguage: "en", restoreGeneration: 0).advance(to: .listening).advance(to: .speaking)
        let firstSegment = UUID().uuidString.lowercased()
        let reconnectedSegment = UUID().uuidString.lowercased()
        var value = draft
        for sequence in 0...7 {
            value = value.appendSpeakingFragment(.init(role: .learner, text: "Turn \(sequence)",
                voiceSegmentId: firstSegment, sequence: sequence))
        }
        value = value.appendSpeakingFragment(.init(role: .learner, text: "Reconnect turn",
            voiceSegmentId: reconnectedSegment, sequence: 0))
        XCTAssertEqual(value.speakingFragments.count, 9)
        XCTAssertEqual(value.speakingFragments.last?.sequence, 0)
        XCTAssertEqual(value.speakingFragments.suffix(2).first?.voiceSegmentId, firstSegment)
        XCTAssertEqual(value.speakingFragments.last?.voiceSegmentId, reconnectedSegment)
    }

    func testDailyConsentMustBeRecordedOnTheDurableAttemptBeforeCreation() throws {
        let package = try englishFixturePackage()
        let draft = DailyPracticeDraft.start(attemptID: "attempt-consent", owner: "member-1", package: package,
            explanationLanguage: "en", restoreGeneration: 0)
        XCTAssertNil(draft.dailyConsentVersion)
        let accepted = draft.acceptingDailyConsent(at: 1234)
        XCTAssertEqual(accepted.dailyConsentVersion, 1)
        XCTAssertEqual(accepted.dailyConsentAcceptedAt, 1234)
        XCTAssertEqual(try JSONDecoder().decode(DailyPracticeDraft.self,
            from: JSONEncoder().encode(accepted)).dailyConsentAcceptedAt, 1234)
    }

    func testFinishIntentSurvivesCrashAfterPracticeAcknowledgement() throws {
        let package = try englishFixturePackage()
        let draft = DailyPracticeDraft.start(attemptID: UUID().uuidString.lowercased(), owner: "member-1", package: package,
            explanationLanguage: "en", restoreGeneration: 0).requestingFinishAfterPractice()
        let restored = try JSONDecoder().decode(DailyPracticeDraft.self, from: JSONEncoder().encode(draft))
        XCTAssertTrue(restored.finishAfterPractice)
        XCTAssertNil(restored.inputHash)
    }

    func testConfirmedConflictCanRebaseSameEventOnlyOnce() throws {
        let package = try englishFixturePackage()
        let eventID = UUID().uuidString.lowercased()
        let draft = DailyPracticeDraft.start(attemptID: UUID().uuidString.lowercased(), owner: "member-1", package: package,
            explanationLanguage: "en", restoreGeneration: 0).confirmingRemoteCreation()
        let remoteStages: [DailyPracticeStage: String] = [
            .preparation: "completed", .listening: "completed", .speaking: "completed"
        ]
        let reconciled = try XCTUnwrap(draft.rebasingConfirmedEvent(eventID: eventID, toRevision: 4,
            remoteStage: .practice, remoteCompletedStages: remoteStages,
            remoteInputHash: String(repeating: "a", count: 64)))
        XCTAssertEqual(reconciled.serverRevision, 4)
        XCTAssertEqual(reconciled.rebasedEventIDs, [eventID])
        XCTAssertEqual(reconciled.stage, .practice)
        XCTAssertEqual(reconciled.completedStages, remoteStages)
        XCTAssertTrue(reconciled.speakingClosed)
        XCTAssertEqual(reconciled.inputHash, String(repeating: "a", count: 64))
        XCTAssertNil(reconciled.rebasingConfirmedEvent(eventID: eventID, toRevision: 5,
            remoteStage: .practice, remoteCompletedStages: remoteStages,
            remoteInputHash: String(repeating: "a", count: 64)))
    }

    func testSpeakingUploadsSplitIntoBoundedBatchesWithoutDroppingFragments() throws {
        let segmentID = UUID().uuidString.lowercased()
        let fragments = (0..<47).map { index in
            DailySpeakingFragment(role: .learner, text: "fragment \(index)", voiceSegmentId: segmentID, sequence: index)
        }
        let batches = DailyPracticeDraft.speakingFragmentBatches(fragments)
        XCTAssertEqual(batches.map(\.count), [20, 20, 7])
        XCTAssertEqual(batches.flatMap { $0 }.map(\.id), fragments.map(\.id))
        XCTAssertTrue(batches.allSatisfy { $0.count <= 20 })
        XCTAssertEqual(DailyPracticeDraft.speakingFragmentBatches([]), [[]])

        let large = (0..<24).map { index in
            DailySpeakingFragment(role: .learner, text: String(repeating: "😀", count: 1_200),
                voiceSegmentId: segmentID, sequence: index)
        }
        let byteBounded = DailyPracticeDraft.speakingFragmentBatches(large)
        XCTAssertEqual(large.first?.text, String(repeating: "😀", count: 1_200))
        XCTAssertEqual(byteBounded.flatMap { $0 }.map(\.id), large.map(\.id))
        XCTAssertTrue(byteBounded.allSatisfy { $0.count <= 20 && ((try? JSONEncoder().encode($0).count) ?? .max) <= 22 * 1024 })
    }

    func testSpeakingUploadAcknowledgementPersistsUniqueFragmentIDs() throws {
        let package = try englishFixturePackage()
        let draft = DailyPracticeDraft.start(attemptID: UUID().uuidString.lowercased(), owner: "member-1", package: package,
            explanationLanguage: "en", restoreGeneration: 0)
        let first = UUID().uuidString.lowercased(), second = UUID().uuidString.lowercased()
        let accepted = draft.acknowledgingSpeakingFragments([first, second, first])
        XCTAssertEqual(accepted.uploadedSpeakingFragmentIDs, [first, second])
        XCTAssertNil(draft.uploadedSpeakingFragmentIDs)
    }

    func testReviewStageWithoutFrozenInputIsNotTerminalHistory() throws {
        let package = try englishFixturePackage()
        let draft = DailyPracticeDraft.start(attemptID: UUID().uuidString.lowercased(), owner: "member-1", package: package,
            explanationLanguage: "en", restoreGeneration: 0).requestingFinishAfterPractice()
        var pendingFinish = draft
        pendingFinish.stage = .review
        XCTAssertTrue(pendingFinish.needsFinishRecovery)
        XCTAssertFalse(pendingFinish.isCompletedReview)
        let completed = pendingFinish.freezingInputHash(String(repeating: "b", count: 64))
        XCTAssertFalse(completed.needsFinishRecovery)
        XCTAssertTrue(completed.isCompletedReview)
    }

    func testCanonicalAttemptAdoptionUsesServerStageAndSnapshotIdentity() throws {
        let package = try englishFixturePackage()
        let canonicalID = UUID().uuidString.lowercased()
        let adopted = DailyPracticeDraft.adoptingRemoteAttempt(attemptID: canonicalID, owner: "member-1",
            package: package, explanationLanguage: "en", restoreGeneration: 9, revision: 7,
            state: "in_progress", completedStages: [.preparation: "completed", .listening: "completed"], inputHash: nil)
        XCTAssertEqual(adopted.attemptID, canonicalID)
        XCTAssertEqual(adopted.stage, .speaking)
        XCTAssertEqual(adopted.serverRevision, 7)
        XCTAssertTrue(adopted.remoteConfirmed)
    }

    func testPracticeGapAnswerIsSavedIncrementally() throws {
        let package = try englishFixturePackage()
        let draft = try DailyPracticeDraft.start(attemptID: "attempt-3", owner: "member-1", package: package,
            explanationLanguage: "en", restoreGeneration: 0).advance(to: .listening).advance(to: .speaking)
            .closeSpeaking().advance(to: .practice)
        let gap = try XCTUnwrap(package.practice.questions.first(where: { $0.options.isEmpty }))
        let answered = try draft.answer(questionID: gap.id, value: "seedling", helpUsed: [])
        XCTAssertEqual(answered.answers[gap.id], "seedling")
    }

    func testHintsArePersistedAsStageEvidence() throws {
        let package = try englishFixturePackage()
        let question = try XCTUnwrap(package.listening.questions.first)
        let draft = try DailyPracticeDraft.start(attemptID: "attempt-hint", owner: "member-1", package: package,
            explanationLanguage: "en", restoreGeneration: 1).advance(to: .listening)
        XCTAssertEqual(try draft.recordHint(questionID: question.id).helpUsed, [question.id])
        XCTAssertThrowsError(try draft.recordHint(questionID: "unknown-question"))
    }

    func testReviewKeepsFeedbackAndDropsOnlyUnsupportedWordCandidates() throws {
        let package = try englishFixturePackage()
        let firstListening = try XCTUnwrap(package.listening.questions.first)
        let inputHash = String(repeating: "c", count: 64)
        var draft = try DailyPracticeDraft.start(attemptID: "attempt-review", owner: "member-1", package: package,
            explanationLanguage: "en", restoreGeneration: 3).advance(to: .listening)
        draft = try draft.answer(questionID: firstListening.id, value: "I walk to park yesterday", helpUsed: [])
        draft = try draft.advance(to: .speaking)
        let fragmentOne = DailySpeakingFragment(id: "31313131-3131-4131-8131-313131313131", role: .learner,
            text: "Yesterday I go to the park.", voiceSegmentId: "32323232-3232-4232-8232-323232323232", sequence: 0)
        let fragmentTwo = DailySpeakingFragment(id: "33333333-3333-4333-8333-333333333333", role: .learner,
            text: "The garden is beautiful.", voiceSegmentId: "32323232-3232-4232-8232-323232323232", sequence: 1)
        draft = draft.appendSpeakingFragment(fragmentOne)
        draft = draft.appendSpeakingFragment(fragmentTwo)
        draft = try draft.closeSpeaking().advance(to: .practice).advance(to: .review).freezingInputHash(inputHash)

        let questionError: [String: Any] = ["id": "error-question", "kind": "grammar",
            "quote": "I walk to park yesterday", "correction": "I walked to the park yesterday",
            "explanation": "Use the past tense.", "fragmentIds": [Any](),
            "questionId": firstListening.id, "confidence": "supported"]
        let speechError: [String: Any] = ["id": "error-speech", "kind": "grammar",
            "quote": "Yesterday I go to the park.", "correction": "Yesterday I went to the park.",
            "explanation": "Use the past tense.", "fragmentIds": [fragmentOne.id],
            "questionId": NSNull(), "confidence": "supported"]
        let markCandidate: [String: Any] = ["candidateId": "41414141-4141-4141-8141-414141414141", "kind": "word",
            "text": "garden", "meaning": "jardín", "origin": "learner_mark", "confidence": "supported",
            "evidenceIds": ["41414141-4141-4141-8141-414141414141"]]
        let questionCandidate: [String: Any] = ["candidateId": "42424242-4242-4242-8242-424242424242", "kind": "expression",
            "text": "I walked to the park yesterday", "meaning": "Caminé al parque ayer", "origin": "review",
            "confidence": "supported", "evidenceIds": [firstListening.id]]
        let fragmentCandidate: [String: Any] = ["candidateId": "43434343-4343-4343-8343-434343434343", "kind": "word",
            "text": "beautiful", "meaning": "hermoso", "origin": "review", "confidence": "supported",
            "evidenceIds": [fragmentTwo.id]]
        let unsupportedCandidate: [String: Any] = ["candidateId": "44444444-4444-4444-8444-444444444444", "kind": "word",
            "text": "totallyabsent", "meaning": "sin evidencia", "origin": "review", "confidence": "supported",
            "evidenceIds": [fragmentOne.id]]
        let review: [String: Any] = ["schemaVersion": 1, "reviewId": "51515151-5151-4151-8151-515151515151",
            "attemptId": "attempt-review", "packageVersionId": package.packageVersionId, "language": "en",
            "explanationLanguage": "en", "inputHash": inputHash, "rubricVersion": "daily-review-v1",
            "createdAt": 0, "outcome": "partial",
            "sections": [
                ["stage": "listening", "evidence": "sufficient", "correct": 0, "total": 1, "helped": false, "explanation": "Reviewed."],
                ["stage": "speaking", "evidence": "sufficient", "correct": NSNull(), "total": NSNull(), "helped": false, "explanation": "Reviewed."],
                ["stage": "practice", "evidence": "not_observed", "correct": NSNull(), "total": NSNull(), "helped": false, "explanation": "Not observed."]
            ],
            "strengths": [], "priorities": [],
            "errors": [questionError, speechError],
            "wordCandidates": [markCandidate, questionCandidate, fragmentCandidate, unsupportedCandidate]]
        let data = try JSONSerialization.data(withJSONObject: review)

        let result = try DailyReviewResult.decodeAndValidate(data, for: draft)
        XCTAssertEqual(result.errors.count, 2)
        XCTAssertEqual(result.wordCandidates.map(\.text), ["garden", "I walked to the park yesterday", "beautiful"])

        var structurallyBroken = review
        structurallyBroken["wordCandidates"] = [[ "candidateId": "46464646-4646-4646-8646-464646464646", "kind": "word",
            "text": "garden", "meaning": "jardín", "origin": "learner_mark", "confidence": "supported",
            "evidenceIds": ["other-id"]]]
        XCTAssertThrowsError(try DailyReviewResult.decodeAndValidate(
            JSONSerialization.data(withJSONObject: structurallyBroken), for: draft))
    }
}
