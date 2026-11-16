import Foundation

public struct DailyPracticePackage: Codable, Equatable {
    public let schemaVersion: Int
    public let packageVersionId: String
    public let contentHash: String
    public let editorialDate: String
    public let language: String
    public let profileVersion: String
    public let topic: DailyTopic
    public let objective: String
    public let challengeReference: String
    public let estimatedMinutes: DailyEstimatedMinutes
    public let expressions: [DailyExpression]
    public let listening: DailyListening
    public let speaking: DailySpeaking
    public let goals: [DailyGoal]
    public let practice: DailyPracticeSection
    public let supportLocales: [String]

    public static func decode(_ data: Data) throws -> DailyPracticePackage {
        let package = try JSONDecoder().decode(Self.self, from: data)
        guard package.schemaVersion == 1 else { throw DailyPracticeError.unsupportedVersion }
        guard package.language == "en", package.profileVersion == "en-v1" else { throw DailyPracticeError.languageDisabled }
        guard package.expressions.count == 5, package.listening.questions.count == 3,
              package.goals.count == 3, package.practice.questions.count == 4 else { throw DailyPracticeError.invalidShape }
        guard package.listening.speechLocale.range(of: "^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$", options: .regularExpression) != nil,
              package.listening.speechLocale.split(separator: "-").first?.lowercased() == package.language else {
            throw DailyPracticeError.invalidShape
        }
        return package
    }
}

public enum DailyPracticeStage: String, Codable, CaseIterable, Hashable {
    case preparation, listening, speaking, practice, review
}

public enum DailySpeakingRole: String, Codable, Equatable { case learner, tutor }

public struct DailySpeakingFragment: Codable, Equatable {
    public let id: String
    public let role: DailySpeakingRole
    public let text: String
    public let voiceSegmentId: String
    public let sequence: Int
    public let transcriptionUncertain: Bool
    public init(id: String = UUID().uuidString.lowercased(), role: DailySpeakingRole, text: String,
                voiceSegmentId: String = UUID().uuidString.lowercased(), sequence: Int = 0,
                transcriptionUncertain: Bool = false) {
        self.id = id; self.role = role; self.text = text
        self.voiceSegmentId = voiceSegmentId; self.sequence = sequence
        self.transcriptionUncertain = transcriptionUncertain
    }
}

/// A local, owner-bound working copy. It deliberately has no time-based
/// expiry: editorial date changes never close or replace an interrupted attempt.
public struct DailyPracticeDraft: Codable, Equatable {
    public let attemptID: String
    public let idempotencyKey: String
    public let owner: String
    public let package: DailyPracticePackage
    public let explanationLanguage: String
    public var restoreGeneration: UInt64
    public var stage: DailyPracticeStage
    public var answers: [String: String]
    public var helpUsed: Set<String>
    public var completedStages: [DailyPracticeStage: String]
    public var speakingFragments: [DailySpeakingFragment]
    public var uploadedSpeakingFragmentIDs: Set<String>?
    public var speakingClosed: Bool
    public var revision: Int
    public var serverRevision: Int
    public var remoteConfirmed: Bool
    public var inputHash: String?
    public var syncBlocked: Bool
    public var acceptedServerResponse: Data?
    public var dailyConsentVersion: Int?
    public var dailyConsentAcceptedAt: Double?
    public var rebasedEventIDs: [String]?
    public var finishAfterPractice: Bool?

    public var expiresAt: Date? { nil }
    public var needsFinishRecovery: Bool { stage == .review && finishAfterPractice == true && inputHash == nil }
    public var isCompletedReview: Bool { stage == .review && inputHash != nil }

    public static func start(attemptID: String, idempotencyKey: String = UUID().uuidString.lowercased(),
                             owner: String, package: DailyPracticePackage,
                             explanationLanguage: String, restoreGeneration: UInt64) -> Self {
        Self(attemptID: attemptID, idempotencyKey: idempotencyKey, owner: owner, package: package, explanationLanguage: explanationLanguage,
             restoreGeneration: restoreGeneration, stage: .preparation, answers: [:], helpUsed: [],
             completedStages: [:], speakingFragments: [], uploadedSpeakingFragmentIDs: nil,
             speakingClosed: false, revision: 0,
             serverRevision: 0, remoteConfirmed: false, inputHash: nil, syncBlocked: false,
             acceptedServerResponse: nil, dailyConsentVersion: nil, dailyConsentAcceptedAt: nil,
             rebasedEventIDs: nil, finishAfterPractice: nil)
    }

    public static func adoptingRemoteAttempt(attemptID: String, owner: String, package: DailyPracticePackage,
                                             explanationLanguage: String, restoreGeneration: UInt64, revision: Int,
                                             state: String, completedStages: [DailyPracticeStage: String],
                                             inputHash: String?) -> Self {
        var copy = Self.start(attemptID: attemptID, owner: owner, package: package,
            explanationLanguage: explanationLanguage, restoreGeneration: restoreGeneration)
        copy.remoteConfirmed = true
        copy.serverRevision = revision
        copy.completedStages = completedStages
        copy.inputHash = inputHash
        copy.stage = ["completed", "partial", "review_pending"].contains(state)
            ? .review
            : (DailyPracticeStage.allCases.first(where: { $0 != .review && completedStages[$0] == nil }) ?? .review)
        return copy
    }

    public func confirmingRemoteCreation() -> Self {
        var copy = self
        copy.remoteConfirmed = true
        return copy
    }

    public func acceptingDailyConsent(at timestamp: Double) -> Self {
        var copy = self
        copy.dailyConsentVersion = 1
        copy.dailyConsentAcceptedAt = timestamp
        return copy
    }

    public func recordingAcceptedServerResponse(_ data: Data) -> Self {
        var copy = self
        copy.acceptedServerResponse = data
        return copy
    }

    public func acknowledgingSpeakingFragments(_ ids: [String]) -> Self {
        var copy = self
        copy.uploadedSpeakingFragmentIDs = (uploadedSpeakingFragmentIDs ?? []).union(ids)
        return copy
    }

    public func requestingFinishAfterPractice() -> Self {
        var copy = self
        copy.finishAfterPractice = true
        return copy
    }

    public func rebasingConfirmedEvent(eventID: String, toRevision revision: Int,
                                       remoteStage: DailyPracticeStage,
                                       remoteCompletedStages: [DailyPracticeStage: String],
                                       remoteInputHash: String?) -> Self? {
        guard remoteConfirmed, UUID(uuidString: eventID) != nil, revision > serverRevision,
              remoteCompletedStages.keys.allSatisfy({ $0 != .review }),
              (remoteInputHash.map { $0.range(of: "^[a-fA-F0-9]{64}$", options: .regularExpression) != nil } ?? true),
              !(rebasedEventIDs ?? []).contains(eventID) else { return nil }
        var copy = self
        copy.serverRevision = revision
        copy.stage = remoteStage
        copy.completedStages = remoteCompletedStages
        copy.speakingClosed = remoteCompletedStages[.speaking] != nil
        copy.inputHash = remoteInputHash
        copy.rebasedEventIDs = (rebasedEventIDs ?? []) + [eventID]
        return copy
    }

    public static func speakingFragmentBatches(_ fragments: [DailySpeakingFragment]) -> [[DailySpeakingFragment]] {
        guard !fragments.isEmpty else { return [[]] }
        let encoder = JSONEncoder()
        let maximumFragmentBytes = 22 * 1024
        var batches: [[DailySpeakingFragment]] = []
        var current: [DailySpeakingFragment] = []
        for fragment in fragments {
            let candidate = current + [fragment]
            let encodedSize = (try? encoder.encode(candidate).count) ?? .max
            if !current.isEmpty && (candidate.count > 20 || encodedSize > maximumFragmentBytes) {
                batches.append(current)
                current = [fragment]
            } else {
                current = candidate
            }
        }
        if !current.isEmpty { batches.append(current) }
        return batches
    }

    public func acknowledgingServerRevision(_ revision: Int) -> Self {
        var copy = self
        copy.serverRevision = max(copy.serverRevision, revision)
        return copy
    }

    public func freezingInputHash(_ hash: String?) -> Self {
        var copy = self
        copy.inputHash = hash
        return copy
    }

    public func restored(for generation: UInt64) -> Self {
        var copy = self
        copy.restoreGeneration = generation
        return copy
    }

    public func blockingSync() -> Self {
        var copy = self
        copy.syncBlocked = true
        return copy
    }

    public func answer(questionID: String, value: String, helpUsed: Set<String>) throws -> Self {
        guard stage == .listening || stage == .practice,
              questions(for: stage).contains(where: { $0.id == questionID }), value.count <= 120,
              helpUsed.allSatisfy({ id in questions(for: stage).contains(where: { $0.id == id }) })
        else { throw DailyPracticeError.invalidTransition }
        var copy = self
        copy.answers[questionID] = value
        copy.helpUsed.formUnion(helpUsed)
        copy.revision += 1
        return copy
    }

    public func recordHint(questionID: String) throws -> Self {
        guard (stage == .listening || stage == .practice),
              questions(for: stage).contains(where: { $0.id == questionID }) else {
            throw DailyPracticeError.invalidTransition
        }
        var copy = self
        copy.helpUsed.insert(questionID)
        copy.revision += 1
        return copy
    }

    public func markHelpUsed(_ ids: [String]) -> Self {
        var copy = self
        copy.helpUsed.formUnion(ids)
        copy.revision += 1
        return copy
    }

    public func appendSpeakingFragment(role: DailySpeakingRole, text: String) -> Self {
        guard stage == .speaking, !speakingClosed else { return self }
        var copy = self
        copy.speakingFragments.append(DailySpeakingFragment(role: role, text: text,
            voiceSegmentId: UUID().uuidString.lowercased(), sequence: speakingFragments.count))
        copy.revision += 1
        return copy
    }

    public func appendSpeakingFragment(_ fragment: DailySpeakingFragment) -> Self {
        guard stage == .speaking, !speakingClosed,
              UUID(uuidString: fragment.id) != nil, UUID(uuidString: fragment.voiceSegmentId) != nil,
              !speakingFragments.contains(where: { $0.id == fragment.id }),
              fragment.sequence > (speakingFragments.last(where: { $0.voiceSegmentId == fragment.voiceSegmentId })?.sequence ?? -1),
              !fragment.text.isEmpty else { return self }
        var copy = self
        copy.speakingFragments.append(fragment)
        copy.revision += 1
        return copy
    }

    public func closeSpeaking() throws -> Self {
        guard stage == .speaking else { throw DailyPracticeError.invalidTransition }
        var copy = self
        copy.speakingClosed = true
        copy.completedStages[.speaking] = "completed"
        copy.revision += 1
        return copy
    }

    public func skipCurrentStage() throws -> Self {
        guard stage != .review, !(stage == .speaking && speakingClosed) else {
            throw DailyPracticeError.invalidTransition
        }
        var copy = self
        copy.completedStages[stage] = "skipped"
        copy.stage = Self.next(after: stage)
        copy.revision += 1
        return copy
    }

    public func advance(to next: DailyPracticeStage) throws -> Self {
        guard stage != .review, Self.next(after: stage) == next,
              stage != .speaking || speakingClosed else { throw DailyPracticeError.invalidTransition }
        var copy = self
        if stage != .speaking { copy.completedStages[stage] = "completed" }
        copy.stage = next
        copy.revision += 1
        return copy
    }

    private func questions(for stage: DailyPracticeStage) -> [DailyQuestion] {
        switch stage {
        case .listening: package.listening.questions
        case .practice: package.practice.questions
        default: []
        }
    }

    private static func next(after stage: DailyPracticeStage) -> DailyPracticeStage {
        switch stage {
        case .preparation: .listening
        case .listening: .speaking
        case .speaking: .practice
        case .practice, .review: .review
        }
    }
}

public struct DailyTopic: Codable, Equatable { public let id, title, summary: String }
public struct DailyEstimatedMinutes: Codable, Equatable { public let preparation, listening, speaking, practice, review, total: Int }
public struct DailyLocales: Codable, Equatable { public let en, es: String }
public struct DailyExpression: Codable, Equatable { public let id, text: String; public let meanings, examples: DailyLocales }
public struct DailyListening: Codable, Equatable { public let speechLocale, transcript: String; public let questions: [DailyQuestion] }
public struct DailySpeaking: Codable, Equatable { public let role, opening: String; public let topicAnchors: [String]; public let closingCue: String }
public struct DailyGoal: Codable, Equatable { public let id, task: String; public let hints, sentenceStarters, exampleResponses: DailyLocales }
public struct DailyPracticeSection: Codable, Equatable { public let questions: [DailyQuestion] }
public struct DailyQuestion: Codable, Equatable { public let id, kind, prompt: String; public let options: [DailyOption]; public let hints: DailyLocales }
public struct DailyOption: Codable, Equatable { public let id, text: String }

public struct DailyReviewResult: Codable, Equatable {
    public struct Section: Codable, Equatable {
        public let stage, evidence: String
        public let correct, total: Int?
        public let helped: Bool
        public let explanation: String
    }
    public struct ErrorItem: Codable, Equatable {
        public let id, kind, quote, correction, explanation: String
        public let fragmentIds: [String]
        public let questionId: String?
        public let confidence: String
    }
    public struct Candidate: Codable, Equatable {
        public let candidateId, kind, text, meaning, origin, confidence: String
        public let evidenceIds: [String]
    }
    public let schemaVersion: Int
    public let reviewId, attemptId, packageVersionId, language, explanationLanguage: String
    public let inputHash, rubricVersion: String
    public let createdAt: Int
    public let outcome: String
    public let sections: [Section]
    public let strengths, priorities: [String]
    public let errors: [ErrorItem]
    /// Server-authoritative list with unsupported review candidates removed at decode;
    /// an ungrounded candidate never invalidates the whole review.
    public private(set) var wordCandidates: [Candidate]

    public static func decodeAndValidate(_ data: Data, for draft: DailyPracticeDraft) throws -> Self {
        guard data.count <= 32 * 1024 else { throw DailyPracticeError.invalidReview }
        let value = try JSONDecoder().decode(Self.self, from: data)
        let stages: Set<String> = ["listening", "speaking", "practice"]
        guard value.schemaVersion == 1, UUID(uuidString: value.reviewId) != nil,
              value.attemptId == draft.attemptID, value.packageVersionId == draft.package.packageVersionId,
              value.language == draft.package.language, value.explanationLanguage == draft.explanationLanguage,
              value.inputHash.range(of: "^[a-fA-F0-9]{64}$", options: .regularExpression) != nil,
              value.inputHash == draft.inputHash,
              !value.rubricVersion.isEmpty, value.createdAt >= 0, ["completed", "partial"].contains(value.outcome),
              value.sections.count == 3, Set(value.sections.map(\.stage)) == stages,
              value.sections.allSatisfy({ section in
                  ["sufficient", "limited", "not_observed"].contains(section.evidence) &&
                  section.explanation.count <= 500 &&
                  ((section.stage == "speaking" || section.evidence == "not_observed") ? section.correct == nil && section.total == nil :
                    section.correct.map { $0 >= 0 } == true && section.total.map { $0 >= 0 } == true && section.correct! <= section.total!)
              }),
              value.strengths.count <= 3, value.priorities.count <= 3,
              (value.strengths + value.priorities).allSatisfy({ !$0.isEmpty && $0.count <= 300 }),
              value.errors.count <= 6, value.wordCandidates.count <= 20 else {
            throw DailyPracticeError.invalidReview
        }
        let learnerFragments = Dictionary(uniqueKeysWithValues: draft.speakingFragments
            .filter { $0.role == .learner }.map { ($0.id, $0.text) })
        guard value.errors.allSatisfy({ error in
            guard !error.id.isEmpty, ["grammar", "vocabulary", "comprehension", "task"].contains(error.kind),
                  !error.quote.isEmpty, error.quote.count <= 300, error.correction.count <= 300,
                  error.explanation.count <= 500, ["supported", "uncertain"].contains(error.confidence),
                  Set(error.fragmentIds).count == error.fragmentIds.count,
                  error.fragmentIds.allSatisfy({ learnerFragments[$0] != nil }) else { return false }
            if let questionID = error.questionId {
                guard let answer = draft.answers[questionID], answer.contains(error.quote) else { return false }
            }
            if !error.fragmentIds.isEmpty && !error.fragmentIds.contains(where: { learnerFragments[$0]?.contains(error.quote) == true }) {
                return false
            }
            return error.questionId != nil || !error.fragmentIds.isEmpty
        }),
        value.wordCandidates.allSatisfy({ candidate in
            ["word", "expression"].contains(candidate.kind) && ["learner_mark", "review"].contains(candidate.origin) &&
            UUID(uuidString: candidate.candidateId) != nil &&
            candidate.confidence == "supported" && !candidate.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            candidate.text.count <= 120 && !candidate.meaning.isEmpty && candidate.meaning.count <= 300 &&
            candidate.evidenceIds.count > 0 && Set(candidate.evidenceIds).count == candidate.evidenceIds.count &&
            (candidate.origin == "learner_mark" ? candidate.evidenceIds == [candidate.candidateId] : true)
        }) else { throw DailyPracticeError.invalidReview }
        // Grounding is per candidate: an unsupported review candidate is dropped
        // while the rest of the review stays valid.
        var accepted = value
        accepted.wordCandidates = accepted.wordCandidates.filter { candidate in
            guard candidate.origin == "review" else { return true }
            return candidate.evidenceIds.allSatisfy { evidenceID in
                if let source = draft.speakingFragments.first(where: { $0.id == evidenceID }),
                   source.role == .learner, !source.transcriptionUncertain {
                    return source.text.contains(candidate.text) ||
                        value.errors.contains { $0.questionId == nil && $0.confidence == "supported" &&
                            $0.fragmentIds.contains(evidenceID) && $0.correction == candidate.text }
                }
                return value.errors.contains { $0.questionId == evidenceID && $0.correction == candidate.text }
            }
        }
        return accepted
    }

    public func importCandidates() -> DailyWordsReview {
        DailyWordsReview(reviewID: reviewId, languageID: language, candidates: wordCandidates.map {
            DailyWordCandidate(candidateID: $0.candidateId, kind: $0.kind, text: $0.text,
                               meaning: $0.meaning, confidence: $0.confidence, origin: $0.origin)
        })
    }
}

public enum DailyPracticeError: Error { case unsupportedVersion, languageDisabled, invalidShape, invalidTransition, invalidReview }
