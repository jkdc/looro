import Foundation
import XCTest
@testable import App
import LearningCore

@MainActor final class DailyPracticeAppTests: XCTestCase {
    private func package() throws -> DailyPracticePackage {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .appendingPathComponent("../../shared/contracts/daily-practice/fixtures/day-en.json")
        return try DailyPracticePackage.decode(Data(contentsOf: url))
    }

    func testDraftIsPersistedAndRestoredOnlyForCapturedOwnerAndGeneration() throws {
        let store = try LearningStore(inMemory: true)
        let owner = try XCTUnwrap(store.dailyPracticeOwnerKey)
        let draft = DailyPracticeDraft.start(attemptID: UUID().uuidString.lowercased(), owner: owner,
            package: try package(), explanationLanguage: "en", restoreGeneration: store.dataGeneration)
            .confirmingRemoteCreation()
        XCTAssertTrue(store.cacheDailyPracticeDraft(draft))
        XCTAssertEqual(store.cachedDailyPracticeDrafts(), [draft])

        store.activateLearnerIdentity("a0000000-0000-4000-8000-000000000001")
        XCTAssertFalse(store.cacheDailyPracticeDraft(draft))
        XCTAssertTrue(store.cachedDailyPracticeDrafts().isEmpty)
    }

    func testDailyHostedResponseDecodesServerVoiceSegmentIdCasing() throws {
        let id = UUID().uuidString.lowercased()
        let data = try JSONSerialization.data(withJSONObject: ["dailyVoiceSegmentId": id])
        XCTAssertEqual(HostedSessions.parseDailyVoiceSegmentId(data), id)
        let wrongCase = try JSONSerialization.data(withJSONObject: ["dailyVoiceSegmentID": id])
        XCTAssertNil(HostedSessions.parseDailyVoiceSegmentId(wrongCase))
    }

    func testCanonicalAdoptionReplacesUnconfirmedLocalAttemptOwnerSafely() throws {
        let store = try LearningStore(inMemory: true)
        let owner = try XCTUnwrap(store.dailyPracticeOwnerKey)
        let package = try package()
        let provisional = DailyPracticeDraft.start(attemptID: UUID().uuidString.lowercased(), owner: owner,
            package: package, explanationLanguage: "en", restoreGeneration: store.dataGeneration)
        XCTAssertTrue(store.cacheDailyPracticeDraft(provisional))
        let adopted = DailyPracticeDraft.adoptingRemoteAttempt(attemptID: UUID().uuidString.lowercased(), owner: owner,
            package: package, explanationLanguage: "en", restoreGeneration: store.dataGeneration, revision: 2,
            state: "in_progress", completedStages: [.preparation: "completed"], inputHash: nil)
        XCTAssertTrue(store.adoptDailyPracticeAttempt(provisional, as: adopted))
        XCTAssertEqual(store.cachedDailyPracticeDrafts(), [adopted])
    }

    func testManualWordsMarkSavesArbitrarySupportedUserEntryImmediately() throws {
        let store = try LearningStore(inMemory: true)
        let owner = try XCTUnwrap(store.dailyPracticeOwnerKey)
        let draft = DailyPracticeDraft.start(attemptID: UUID().uuidString.lowercased(), owner: owner,
            package: try package(), explanationLanguage: "en", restoreGeneration: store.dataGeneration)
            .confirmingRemoteCreation()
        let summary = try XCTUnwrap(store.saveDailyWordMark(draft, markID: UUID().uuidString.lowercased(),
            text: "community greenhouse", meaning: "a shared place to grow plants", kind: "expression"))
        XCTAssertEqual(summary.imported, 1)
        XCTAssertTrue(store.notebookExpressions.contains { $0.text == "community greenhouse" })
    }

    func testPreparedExpressionReceiptPreventsDuplicateReviewImportAndManualReaddClearsSuppression() throws {
        let store = try LearningStore(inMemory: true)
        let owner = try XCTUnwrap(store.dailyPracticeOwnerKey)
        let package = try package()
        let inputHash = String(repeating: "a", count: 64)
        let draft = DailyPracticeDraft.start(attemptID: UUID().uuidString.lowercased(), owner: owner,
            package: package, explanationLanguage: "en", restoreGeneration: store.dataGeneration)
            .confirmingRemoteCreation().freezingInputHash(inputHash)
        let markID = UUID().uuidString.lowercased()
        let prepared = try XCTUnwrap(store.saveDailyWordMark(draft, markID: markID,
            text: "community greenhouse", meaning: "a shared growing space", kind: "expression"))
        XCTAssertEqual(prepared.imported, 1)

        let reviewData = try JSONSerialization.data(withJSONObject: [
            "schemaVersion": 1, "reviewId": UUID().uuidString.lowercased(), "attemptId": draft.attemptID,
            "packageVersionId": package.packageVersionId, "language": "en", "explanationLanguage": "en",
            "inputHash": inputHash, "rubricVersion": "daily-review-v1", "createdAt": 1, "outcome": "partial",
            "sections": ["listening", "speaking", "practice"].map { stage in
                ["stage": stage, "evidence": "not_observed", "correct": NSNull(), "total": NSNull(),
                 "helped": false, "explanation": "Not observed."] as [String: Any]
            },
            "strengths": [], "priorities": [], "errors": [],
            "wordCandidates": [["candidateId": markID, "kind": "expression", "text": "community greenhouse",
                "meaning": "a shared growing space", "origin": "learner_mark", "confidence": "supported",
                "evidenceIds": [markID]]]
        ])
        let review = try DailyReviewResult.decodeAndValidate(reviewData, for: draft)
        let repeated = try XCTUnwrap(store.importDailyReview(review, for: draft))
        XCTAssertEqual(repeated.imported, 0)
        XCTAssertEqual(store.notebookExpressions.filter { $0.text == "community greenhouse" }.count, 1)

        store.removeExpression(text: "community greenhouse")
        let restored = try XCTUnwrap(store.saveDailyWordMark(draft, markID: UUID().uuidString.lowercased(),
            text: "community greenhouse", meaning: "a shared growing space", kind: "expression"))
        XCTAssertEqual(restored.imported, 1)
        XCTAssertTrue(store.notebookExpressions.contains { $0.text == "community greenhouse" })
    }

    func testPersistedReviewFinishIntentRemainsRecoverableUntilInputHashIsFrozen() throws {
        let store = try LearningStore(inMemory: true)
        let owner = try XCTUnwrap(store.dailyPracticeOwnerKey)
        var pending = DailyPracticeDraft.start(attemptID: UUID().uuidString.lowercased(), owner: owner,
            package: try package(), explanationLanguage: "en", restoreGeneration: store.dataGeneration)
            .confirmingRemoteCreation().requestingFinishAfterPractice()
        pending.stage = .review
        XCTAssertTrue(store.cacheDailyPracticeDraft(pending))

        let recovered = try XCTUnwrap(store.cachedDailyPracticeDrafts().first)
        XCTAssertTrue(recovered.needsFinishRecovery)
        XCTAssertFalse(recovered.isCompletedReview)

        let completed = recovered.freezingInputHash(String(repeating: "c", count: 64))
        XCTAssertTrue(store.cacheDailyPracticeDraft(completed))
        let history = try XCTUnwrap(store.cachedDailyPracticeDrafts().first)
        XCTAssertTrue(history.isCompletedReview)
        XCTAssertFalse(history.needsFinishRecovery)
    }

    func testPreparedExpressionMarkIsImmediateReviewIdempotentAndCanBeExplicitlyReadded() throws {
        let store = try LearningStore(inMemory: true)
        let owner = try XCTUnwrap(store.dailyPracticeOwnerKey)
        let package = try package()
        let draft = DailyPracticeDraft.start(attemptID: UUID().uuidString.lowercased(), owner: owner,
            package: package, explanationLanguage: "en", restoreGeneration: store.dataGeneration)
            .confirmingRemoteCreation().freezingInputHash(String(repeating: "a", count: 64))
        let markID = UUID().uuidString.lowercased()
        let first = try XCTUnwrap(store.saveDailyWordMark(draft, markID: markID,
            text: "community greenhouse", meaning: "a shared growing space", kind: "expression"))
        XCTAssertEqual(first.imported, 1)
        XCTAssertTrue(store.notebookExpressions.contains { $0.text == "community greenhouse" })

        let review = try DailyReviewResult.decodeAndValidate(try JSONSerialization.data(withJSONObject: [
            "schemaVersion": 1, "reviewId": UUID().uuidString.lowercased(), "attemptId": draft.attemptID,
            "packageVersionId": package.packageVersionId, "language": "en", "explanationLanguage": "en",
            "inputHash": String(repeating: "a", count: 64), "rubricVersion": "daily-review-v1",
            "createdAt": 1, "outcome": "partial",
            "sections": ["listening", "speaking", "practice"].map { stage in
                ["stage": stage, "evidence": "not_observed", "correct": NSNull(), "total": NSNull(),
                 "helped": false, "explanation": "Not observed."] as [String: Any]
            },
            "strengths": [], "priorities": [], "errors": [],
            "wordCandidates": [["candidateId": markID, "kind": "expression", "text": "community greenhouse",
                "meaning": "a shared growing space", "origin": "learner_mark", "confidence": "supported",
                "evidenceIds": [markID]]]
        ]), for: draft)
        let reviewSummary = try XCTUnwrap(store.importDailyReview(review, for: draft))
        XCTAssertEqual(reviewSummary.imported, 0)
        XCTAssertEqual(store.notebookExpressions.filter { $0.text == "community greenhouse" }.count, 1)

        store.removeExpression(text: "community greenhouse")
        let readded = try XCTUnwrap(store.saveDailyWordMark(draft, markID: UUID().uuidString.lowercased(),
            text: "community greenhouse", meaning: "a shared growing space", kind: "expression"))
        XCTAssertEqual(readded.imported, 1)
        XCTAssertTrue(store.notebookExpressions.contains { $0.text == "community greenhouse" })
    }

    func testEventOutboxPersistsImmutableIntentAndKeepsUnknownOutcomeForExactRetry() throws {
        let store = try LearningStore(inMemory: true)
        let owner = try XCTUnwrap(store.dailyPracticeOwnerKey)
        let draft = DailyPracticeDraft.start(attemptID: UUID().uuidString.lowercased(), owner: owner,
            package: try package(), explanationLanguage: "en", restoreGeneration: store.dataGeneration)
            .confirmingRemoteCreation()
        let eventID = UUID().uuidString.lowercased()
        let envelope = try JSONSerialization.data(withJSONObject: ["schemaVersion": 1, "eventId": eventID,
            "expectedRevision": 0, "event": ["type": "preparation_completed", "activeMs": 0]], options: [.sortedKeys])
        XCTAssertEqual(store.enqueueDailyPracticeEvent(draft, envelope: envelope), eventID)
        XCTAssertEqual(store.enqueueDailyPracticeEvent(draft, envelope: envelope), eventID)
        XCTAssertTrue(store.markDailyPracticeEvent(draft, eventID: eventID, state: "sending"))
        XCTAssertEqual(store.pendingDailyPracticeEvents(draft), [envelope], "a process restart must recover a send interrupted after durable queueing")
        XCTAssertTrue(store.markDailyPracticeEvent(draft, eventID: eventID, state: "outcome_unknown"))
        XCTAssertEqual(store.pendingDailyPracticeEvents(draft), [envelope])

        let changed = try JSONSerialization.data(withJSONObject: ["schemaVersion": 1, "eventId": eventID,
            "expectedRevision": 1, "event": ["type": "preparation_completed", "activeMs": 0]], options: [.sortedKeys])
        XCTAssertNil(store.enqueueDailyPracticeEvent(draft, envelope: changed))
        XCTAssertTrue(store.markDailyPracticeEvent(draft, eventID: eventID, state: "revision_conflict"))
        XCTAssertTrue(store.dailyPracticeHasRevisionConflict(draft))
        let blocked = draft.blockingSync()
        XCTAssertTrue(store.cacheDailyPracticeDraft(blocked))
        XCTAssertTrue(store.cachedDailyPracticeDrafts().first?.syncBlocked == true)
    }

    func testAcceptedEventAndDraftRevisionCommitAtomically() throws {
        let store = try LearningStore(inMemory: true)
        let owner = try XCTUnwrap(store.dailyPracticeOwnerKey)
        let draft = DailyPracticeDraft.start(attemptID: UUID().uuidString.lowercased(), owner: owner,
            package: try package(), explanationLanguage: "en", restoreGeneration: store.dataGeneration)
            .confirmingRemoteCreation()
        XCTAssertTrue(store.cacheDailyPracticeDraft(draft))
        let eventID = UUID().uuidString.lowercased()
        let envelope = try JSONSerialization.data(withJSONObject: ["schemaVersion": 1, "eventId": eventID,
            "expectedRevision": 0, "event": ["type": "preparation_completed", "activeMs": 0]], options: [.sortedKeys])
        XCTAssertEqual(store.enqueueDailyPracticeEvent(draft, envelope: envelope), eventID)
        XCTAssertTrue(store.markDailyPracticeEvent(draft, eventID: eventID, state: "sending"))
        let response = Data(#"{"attemptId":"accepted","revision":1}"#.utf8)
        let accepted = draft.acknowledgingServerRevision(1).recordingAcceptedServerResponse(response)
        XCTAssertTrue(store.acknowledgeDailyPracticeEvent(draft, eventID: eventID,
            acceptedDraft: accepted, acceptedResponse: response))
        XCTAssertEqual(store.cachedDailyPracticeDrafts().first?.serverRevision, 1)
        XCTAssertEqual(store.cachedDailyPracticeDrafts().first?.acceptedServerResponse, response)

        let nextID = UUID().uuidString.lowercased()
        let next = try JSONSerialization.data(withJSONObject: ["schemaVersion": 1, "eventId": nextID,
            "expectedRevision": 1, "event": ["type": "stage_skipped", "stage": "listening", "activeMs": 0,
                                                "reason": "learner_choice"]], options: [.sortedKeys])
        XCTAssertEqual(store.enqueueDailyPracticeEvent(accepted, envelope: next), nextID)
        XCTAssertTrue(store.markDailyPracticeEvent(accepted, eventID: nextID, state: "sending"))
        let secondResponse = Data(#"{"revision":2}"#.utf8)
        let rejectedAcceptance = accepted.acknowledgingServerRevision(2).recordingAcceptedServerResponse(secondResponse)
        XCTAssertFalse(store.acknowledgeDailyPracticeEvent(accepted, eventID: nextID,
            acceptedDraft: rejectedAcceptance, acceptedResponse: secondResponse, now: .nan))
        XCTAssertEqual(store.cachedDailyPracticeDrafts().first?.serverRevision, 1,
            "a failed local write must roll back the outbox acknowledgement too")
        XCTAssertEqual(store.pendingDailyPracticeEvents(accepted).count, 1)
    }

    func testConfirmedConflictRebasesPreconditionWithoutChangingEventIdentityOrPayload() throws {
        let store = try LearningStore(inMemory: true)
        let owner = try XCTUnwrap(store.dailyPracticeOwnerKey)
        let draft = DailyPracticeDraft.start(attemptID: UUID().uuidString.lowercased(), owner: owner,
            package: try package(), explanationLanguage: "en", restoreGeneration: store.dataGeneration)
            .confirmingRemoteCreation()
        XCTAssertTrue(store.cacheDailyPracticeDraft(draft))
        let eventID = UUID().uuidString.lowercased()
        let payload: [String: Any] = ["type": "preparation_completed", "activeMs": 0]
        let envelope = try JSONSerialization.data(withJSONObject: ["schemaVersion": 1, "eventId": eventID,
            "expectedRevision": 0, "event": payload], options: [.sortedKeys])
        XCTAssertEqual(store.enqueueDailyPracticeEvent(draft, envelope: envelope), eventID)
        XCTAssertTrue(store.markDailyPracticeEvent(draft, eventID: eventID, state: "sending"))
        XCTAssertTrue(store.markDailyPracticeEvent(draft, eventID: eventID, state: "revision_conflict"))
        let rebased = try XCTUnwrap(draft.rebasingConfirmedEvent(eventID: eventID, toRevision: 4,
            remoteStage: .listening, remoteCompletedStages: [.preparation: "completed"], remoteInputHash: nil))
        let updated = try XCTUnwrap(store.rebaseDailyPracticeEvent(draft, eventID: eventID, acceptedDraft: rebased))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: updated) as? [String: Any])
        XCTAssertEqual(json["eventId"] as? String, eventID)
        XCTAssertEqual(json["expectedRevision"] as? Int, 4)
        XCTAssertEqual(json["event"] as? NSDictionary, payload as NSDictionary)
        XCTAssertEqual(store.pendingDailyPracticeEvents(rebased), [updated])
    }

    func testReviewMustMatchTheFrozenAttemptInputBeforeItCanBeUsed() throws {
        let store = try LearningStore(inMemory: true)
        let owner = try XCTUnwrap(store.dailyPracticeOwnerKey)
        let package = try package()
        let draft = DailyPracticeDraft.start(attemptID: UUID().uuidString.lowercased(), owner: owner,
            package: package, explanationLanguage: "en", restoreGeneration: store.dataGeneration)
            .confirmingRemoteCreation()
            .freezingInputHash(String(repeating: "a", count: 64))
        let reviewID = UUID().uuidString.lowercased()
        let review: [String: Any] = [
            "schemaVersion": 1, "reviewId": reviewID, "attemptId": draft.attemptID,
            "packageVersionId": package.packageVersionId, "language": "en", "explanationLanguage": "en",
            "inputHash": String(repeating: "b", count: 64), "rubricVersion": "daily-review-v1",
            "createdAt": 1, "outcome": "completed",
            "sections": ["listening", "speaking", "practice"].map { stage in
                ["stage": stage, "evidence": "limited", "correct": 1, "total": 2,
                 "helped": false, "explanation": "Keep practising."] as [String: Any]
            },
            "strengths": [], "priorities": [], "errors": [], "wordCandidates": []
        ]
        let data = try JSONSerialization.data(withJSONObject: review)
        XCTAssertThrowsError(try DailyReviewResult.decodeAndValidate(data, for: draft))
    }

    func testReviewCandidateMustBeSupportedByCertainLearnerSpeech() throws {
        let store = try LearningStore(inMemory: true)
        let owner = try XCTUnwrap(store.dailyPracticeOwnerKey)
        let package = try package()
        let fragment = DailySpeakingFragment(role: .learner, text: "I enjoy community gardens.",
            voiceSegmentId: UUID().uuidString.lowercased(), sequence: 0)
        let draft = try DailyPracticeDraft.start(attemptID: UUID().uuidString.lowercased(), owner: owner,
            package: package, explanationLanguage: "en", restoreGeneration: store.dataGeneration)
            .advance(to: .listening).advance(to: .speaking).appendSpeakingFragment(fragment)
            .freezingInputHash(String(repeating: "a", count: 64))
        let review: [String: Any] = [
            "schemaVersion": 1, "reviewId": UUID().uuidString.lowercased(), "attemptId": draft.attemptID,
            "packageVersionId": package.packageVersionId, "language": "en", "explanationLanguage": "en",
            "inputHash": draft.inputHash!, "rubricVersion": "daily-review-v1", "createdAt": 1,
            "outcome": "completed",
            "sections": [
                ["stage": "listening", "evidence": "not_observed", "correct": NSNull(), "total": NSNull(), "helped": false, "explanation": "Not observed."],
                ["stage": "speaking", "evidence": "limited", "correct": NSNull(), "total": NSNull(), "helped": false, "explanation": "One response."],
                ["stage": "practice", "evidence": "not_observed", "correct": NSNull(), "total": NSNull(), "helped": false, "explanation": "Not observed."]
            ],
            "strengths": [], "priorities": [], "errors": [],
            "wordCandidates": [["candidateId": UUID().uuidString.lowercased(), "kind": "word",
                "text": "invented", "meaning": "invented", "origin": "review", "confidence": "supported",
                "evidenceIds": [fragment.id]]]
        ]
        XCTAssertThrowsError(try DailyReviewResult.decodeAndValidate(
            JSONSerialization.data(withJSONObject: review), for: draft))
    }

    func testSpeakingCloseUploadsFragmentReceivedWhileStopIsAwaited() async throws {
        let store = try LearningStore(inMemory: true)
        let owner = try XCTUnwrap(store.dailyPracticeOwnerKey)
        let segmentID = UUID().uuidString.lowercased()
        let original = try DailyPracticeDraft.start(attemptID: UUID().uuidString.lowercased(), owner: owner,
            package: package(), explanationLanguage: "en", restoreGeneration: store.dataGeneration)
            .advance(to: .listening).advance(to: .speaking)
        let finalFragment = DailySpeakingFragment(role: .learner, text: "A final response.",
            voiceSegmentId: segmentID, sequence: 0)
        var currentDraft = original

        let snapshot = try await DailyPracticeSpeakingCloseSnapshot.stopAndCapture(
            originalDraft: original, segmentID: segmentID,
            isCurrent: { $0.attemptID == original.attemptID },
            currentDraft: { currentDraft },
            stop: { currentDraft = currentDraft.appendSpeakingFragment(finalFragment) })

        XCTAssertEqual(snapshot?.draft.attemptID, original.attemptID)
        XCTAssertEqual(snapshot?.fragments.map(\.id), [finalFragment.id])
    }
}
