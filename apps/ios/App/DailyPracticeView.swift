import SwiftUI
import LearningCore

private enum DailyTransition: Equatable { case none, listening, listeningSkipped, speaking, practice, review, finished }

struct DailyPracticeView: View {
    let store: LearningStore
    let accountStore: ManagedAccountStore
    let coordinator: ConversationCoordinator
    var onBack: () -> Void
    var onOpenWords: () -> Void

    @State private var package: DailyPracticePackage?
    @State private var draft: DailyPracticeDraft?
    @State private var loading = false
    @State private var message: String?
    @State private var saved = false
    @State private var review: DailyReviewResult?
    @State private var importSummary: DailyWordsImportSummary?
    @State private var reviewState = "not_started"
    @State private var eventPending = false
    @State private var revisionConflict = false
    @State private var remoteAttemptNeedsLocalCopy = false
    @State private var listeningReader = LessonReader()
    @State private var audioIsPlaying = false
    @State private var navigationGeneration = UUID()
    @State private var consentAccepted = false
    @State private var history: [DailyPracticeDraft] = []
    @State private var manualWordText = ""
    @State private var manualWordMeaning = ""
    @State private var manualWordKind = "word"
    @State private var stageStartedAtUptime = ProcessInfo.processInfo.systemUptime

    private var activeStageMilliseconds: Int {
        Int(max(0, ProcessInfo.processInfo.systemUptime - stageStartedAtUptime) * 1_000)
    }

    private var client: DailyPracticeClient? {
        ManagedAccountConfiguration.load().map { DailyPracticeClient(configuration: $0) }
    }

    private var reviewStateLabel: String {
        switch reviewState {
        case "queued": String(localized: "Queued")
        case "running": String(localized: "Processing")
        case "ready": String(localized: "Ready")
        case "failed": String(localized: "Failed")
        case "unknown": String(localized: "Unknown")
        case "unavailable": String(localized: "Unavailable")
        case "invalid": String(localized: "Could not validate")
        default: String(localized: "Not started")
        }
    }

    private var speakingStatusLabel: String {
        switch coordinator.dailySpeaking.status {
        case "Connecting microphone": String(localized: "Connecting microphone")
        case "Listening": String(localized: "Listening")
        case "Closing speaking session": String(localized: "Closing speaking session")
        case "Speaking session closed": String(localized: "Speaking session closed")
        case "Speaking paused; this attempt is still open": String(localized: "Speaking paused; this attempt is still open")
        case "Voice connection interrupted": String(localized: "Voice connection interrupted")
        case "Listening": String(localized: "Listening")
        default: String(localized: "Ready to speak")
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Button("Back", systemImage: "chevron.left", action: onBack)
                    .accessibilityIdentifier("daily-practice-back")
                Text("Daily practice").font(.largeTitle.bold())
                if let draft {
                    attemptContent(draft)
                } else if let package {
                    VStack(alignment: .leading, spacing: 12) {
                        Text(package.topic.title).font(.title2.bold())
                        Text(package.objective)
                        Text("The estimate is a guide, not a timer.")
                            .font(.footnote).foregroundStyle(AppColor.secondary)
                        Text("Daily practice sends your answers and speaking transcript to the configured AI provider for feedback. Your attempt and review stay available until you delete your account or data.")
                            .font(.footnote).foregroundStyle(AppColor.secondary)
                            .accessibilityIdentifier("daily-practice-data-notice")
                        Toggle("I agree to this daily-practice data use", isOn: $consentAccepted)
                            .accessibilityIdentifier("daily-practice-consent")
                        if remoteAttemptNeedsLocalCopy {
                            Text("This attempt already exists online, but the server does not provide its full resume state. Use the device that started it.")
                                .font(.footnote).accessibilityIdentifier("daily-practice-remote-restore-limitation")
                        } else {
                            Button("Start today’s practice") { Task { await start(package) } }
                                .buttonStyle(.borderedProminent).disabled(loading || !consentAccepted)
                                .accessibilityIdentifier("daily-practice-start")
                        }
                    }
                } else if loading {
                    ProgressView("Loading today’s practice")
                        .accessibilityIdentifier("daily-practice-loading")
                } else {
                    Text("Today’s practice is not available yet.")
                    Button("Try again") { Task { await load() } }
                        .accessibilityIdentifier("daily-practice-retry")
                }
                if let message {
                    Text(message).font(.footnote).foregroundStyle(AppColor.secondary)
                        .accessibilityIdentifier("daily-practice-message")
                }
                if draft != nil {
                    Text(saved ? "Saved on this device" : "Not saved yet")
                        .font(.caption).accessibilityIdentifier("daily-practice-save-status")
                        .accessibilityAddTraits(.updatesFrequently)
                }
                if !history.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Review history").font(.headline)
                        ForEach(history, id: \.attemptID) { past in
                            Button("Review result from \(past.package.editorialDate)") { openHistory(past) }
                                .accessibilityIdentifier("daily-history-\(past.attemptID)")
                        }
                    }.accessibilityIdentifier("daily-practice-history")
                }
            }
            .padding(24).frame(maxWidth: .infinity, alignment: .leading)
        }
        .onChange(of: draft?.stage) { _, _ in stageStartedAtUptime = ProcessInfo.processInfo.systemUptime }
        .task { restoreOrLoad() }
        .onChange(of: store.activeLearnerIdentity) { _, _ in
            navigationGeneration = UUID()
            stopListening()
            draft = nil; package = nil; saved = false; message = nil; review = nil; importSummary = nil; eventPending = false; revisionConflict = false
            consentAccepted = false
            remoteAttemptNeedsLocalCopy = false
            restoreOrLoad()
        }
        .onChange(of: store.dataGeneration) { _, _ in
            navigationGeneration = UUID()
            stopListening()
            draft = nil; package = nil; saved = false; message = nil; review = nil; importSummary = nil; eventPending = false; revisionConflict = false
            consentAccepted = false
            remoteAttemptNeedsLocalCopy = false
            restoreOrLoad()
        }
        .onChange(of: draft?.stage) { _, stage in
            if stage != .listening {
                stopListening()
            }
        }
        .onDisappear {
            navigationGeneration = UUID()
            stopListening()
            if let current = draft, current.stage == .speaking, coordinator.dailySpeaking.isActive {
                Task { await closeSpeaking(current, finalize: false) }
            } else {
                coordinator.closeDailySpeakingForInterruption()
            }
        }
    }

    @ViewBuilder private func attemptContent(_ draft: DailyPracticeDraft) -> some View {
        Text(draft.package.topic.title).font(.title2.bold())
        if !draft.remoteConfirmed {
            if remoteAttemptNeedsLocalCopy {
                Text("This attempt already exists online, but the server does not provide its full resume state. Use the device that started it.")
                    .accessibilityIdentifier("daily-practice-remote-restore-limitation")
            } else {
                Text("The attempt has not been confirmed yet. Retry with the same saved request.")
                    .accessibilityIdentifier("daily-practice-confirmation-pending")
                Button("Retry start") { Task { await confirmStart(draft) } }
                    .accessibilityIdentifier("daily-practice-start-retry")
            }
        } else {
            confirmedAttemptContent(draft)
        }
    }

    @ViewBuilder private func confirmedAttemptContent(_ draft: DailyPracticeDraft) -> some View {
        Text("Practice stage").font(.headline)
            .accessibilityIdentifier("daily-practice-stage")
        if eventPending {
            Text("A saved event is waiting for confirmation.").font(.footnote)
                .accessibilityIdentifier("daily-practice-offline-pending")
            Button("Retry saved progress") { Task { await syncPendingEvents() } }
                .accessibilityIdentifier("daily-practice-sync-retry")
        }
        switch draft.stage {
        case .preparation:
            Text(draft.package.challengeReference)
            ForEach(draft.package.goals, id: \.id) { goal in
                VStack(alignment: .leading) {
                    Text(goal.task)
                    Button("Show sentence starter") {
                        update { $0.markHelpUsed([goal.id]) }
                    }.accessibilityIdentifier("daily-practice-help-\(goal.id)")
                    if draft.helpUsed.contains(goal.id) {
                        Text(draft.explanationLanguage == "es" ? goal.sentenceStarters.es : goal.sentenceStarters.en).font(.caption)
                    }
                }
            }
            ForEach(draft.package.expressions, id: \.id) { expression in
                Button("Mark \(expression.text) for Words") {
                    Task { await markPreparedExpression(expression, draft: draft) }
                }.disabled(eventPending || revisionConflict)
            }
            Button("Continue to listening") {
                Task { await submitEvent(["type": "preparation_completed", "activeMs": activeStageMilliseconds,
                    "helpUsed": draft.helpUsed.intersection(Set(draft.package.goals.map(\.id))).sorted()], transition: .listening) }
            }.disabled(eventPending || revisionConflict)
        case .listening:
            let voiceAvailable = listeningReader.canRead(locale: draft.package.listening.speechLocale)
            Button(audioIsPlaying ? "Stop listening transcript" : (voiceAvailable ? "Play listening transcript" : "Retry listening transcript")) {
                playListeningTranscript(draft)
            }
            .disabled(revisionConflict)
            .accessibilityIdentifier("daily-listening-transcript-toggle")
            if !voiceAvailable {
                Text("The voice for this language is unavailable. Enable or install it, then retry or skip listening.")
                    .font(.footnote).accessibilityIdentifier("daily-listening-voice-unavailable")
                Button("Skip listening") { Task { await skipListening(draft) } }
                    .disabled(eventPending || revisionConflict)
                    .accessibilityIdentifier("daily-listening-skip")
            }
            ForEach(draft.package.listening.questions, id: \.id) { question in
                questionControl(question, draft: draft)
            }
            Button("Continue to speaking") { Task { await submitStage(.listening, draft: draft) } }.disabled(eventPending || revisionConflict)
        case .speaking:
            Text(draft.package.speaking.role)
            Text(draft.package.speaking.opening).padding(12).background(.white, in: RoundedRectangle(cornerRadius: 12))
            ForEach(draft.package.speaking.topicAnchors, id: \.self) { Text("• \($0)") }
            Text(draft.package.speaking.closingCue).font(.footnote)
            Text(speakingStatusLabel).accessibilityAddTraits(.updatesFrequently)
                .accessibilityIdentifier("daily-speaking-status")
            if coordinator.dailySpeaking.isActive || coordinator.dailySpeaking.isConnecting {
                HStack {
                    Button(coordinator.dailySpeaking.isMuted ? "Unmute microphone" : "Mute microphone") {
                        coordinator.dailySpeaking.toggleMute()
                    }.accessibilityIdentifier("daily-speaking-mute")
                    Button("Close speaking") { Task { await closeSpeaking(draft) } }
                        .disabled(eventPending || revisionConflict)
                        .accessibilityIdentifier("daily-speaking-close")
                }
                ForEach(draft.speakingFragments, id: \.id) { fragment in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(fragment.role == .learner ? "You" : "Palique").font(.caption.bold())
                        Text(fragment.text).font(.footnote)
                    }.accessibilityIdentifier("daily-speaking-fragment-\(fragment.id)")
                }
            } else if !draft.speakingClosed {
                Button("Start speaking") { Task { await startSpeaking(draft) } }
                    .disabled(coordinator.dailySpeaking.isConnecting || eventPending || revisionConflict)
                    .accessibilityIdentifier("daily-speaking-start")
                if coordinator.dailySpeaking.error != nil {
                    Text("Voice connection interrupted. Your attempt remains saved.").font(.footnote)
                        .accessibilityIdentifier("daily-speaking-error")
                }
                Button("Skip speaking") { Task { await skipSpeaking(draft) } }
                    .disabled(eventPending || revisionConflict)
                    .accessibilityIdentifier("daily-speaking-skip")
            }
        case .practice:
            ForEach(draft.package.practice.questions, id: \.id) { question in
                questionControl(question, draft: draft)
            }
            Button("Finish practice") { Task { await submitStage(.practice, draft: draft) } }.disabled(eventPending || revisionConflict)
        case .review:
            reviewContent(draft)
        }
        manualWordEditor(draft)
    }

    @ViewBuilder private func manualWordEditor(_ draft: DailyPracticeDraft) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Add a word or expression").font(.headline)
            TextField("Word or expression", text: $manualWordText)
                .textInputAutocapitalization(.never).accessibilityIdentifier("daily-manual-word-text")
            TextField("Meaning", text: $manualWordMeaning)
                .accessibilityIdentifier("daily-manual-word-meaning")
            Picker("Entry type", selection: $manualWordKind) {
                Text("Word").tag("word")
                Text("Expression").tag("expression")
            }.pickerStyle(.segmented)
            Button("Save to Words") { saveManualWord(draft) }
                .disabled(manualWordText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                          manualWordMeaning.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .accessibilityIdentifier("daily-manual-word-save")
        }
        .accessibilityIdentifier("daily-manual-word-editor")
    }

    @ViewBuilder private func questionControl(_ question: DailyQuestion, draft: DailyPracticeDraft) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(question.prompt).font(.headline)
            if question.options.isEmpty {
                TextField("Your answer", text: Binding(
                    get: { draft.answers[question.id] ?? "" },
                    set: { value in update { try $0.answer(questionID: question.id, value: value, helpUsed: []) } }
                ))
                .textFieldStyle(.roundedBorder)
                .disabled(revisionConflict || eventPending)
                .accessibilityIdentifier("daily-practice-answer-\(question.id)")
            } else {
                ForEach(question.options, id: \.id) { option in
                    Button(option.text) {
                        update { try $0.answer(questionID: question.id, value: option.id, helpUsed: []) }
                    }.buttonStyle(.bordered)
                        .disabled(revisionConflict || eventPending)
                        .accessibilityIdentifier("daily-practice-answer-\(question.id)-\(option.id)")
                }
            }
            if draft.answers[question.id] != nil { Text("Answer saved").font(.caption) }
            Button("Show a hint") {
                update { try $0.recordHint(questionID: question.id) }
                message = draft.explanationLanguage == "es" ? question.hints.es : question.hints.en
            }
                .disabled(revisionConflict || eventPending)
                .font(.caption).accessibilityIdentifier("daily-practice-hint-\(question.id)")
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
        .background(.white.opacity(0.8), in: RoundedRectangle(cornerRadius: 14))
    }

    @ViewBuilder private func reviewContent(_ draft: DailyPracticeDraft) -> some View {
        Text("Review status: \(reviewStateLabel)").accessibilityIdentifier("daily-review-status")
        Text("This feedback is based on the transcript sent from this device.")
            .font(.footnote).foregroundStyle(AppColor.secondary)
        if let review {
            ForEach(review.sections, id: \.stage) { section in
                VStack(alignment: .leading, spacing: 6) {
                    Text(section.stage.capitalized).font(.headline)
                    Text(section.explanation)
                    if let correct = section.correct, let total = section.total { Text("\(correct) / \(total)") }
                }.padding(12).frame(maxWidth: .infinity, alignment: .leading)
                    .background(.white.opacity(0.8), in: RoundedRectangle(cornerRadius: 14))
            }
            if !review.strengths.isEmpty {
                Text("What went well").font(.headline)
                ForEach(review.strengths, id: \.self) { Text("• \($0)") }
            }
            if !review.priorities.isEmpty {
                Text("Next to practise").font(.headline)
                ForEach(review.priorities, id: \.self) { Text("• \($0)") }
            }
            ForEach(review.errors, id: \.id) { item in
                VStack(alignment: .leading, spacing: 6) {
                    Text(item.confidence == "uncertain" ? "Observation to consider" : "A point to improve")
                        .font(.headline)
                    Text("You said: \(item.quote)")
                    Text("Try: \(item.correction)")
                    Text(item.explanation).font(.footnote)
                }.padding(12).background(.white, in: RoundedRectangle(cornerRadius: 14))
            }
            if let importSummary {
                Text(importSummary.pendingCapacity > 0
                    ? "Some terms could not be saved because Words is full."
                    : "Supported terms were added to Words on this device.")
                    .accessibilityIdentifier("daily-words-import-status")
                if importSummary.pendingCapacity > 0 {
                    Button("Retry saving terms to Words") { importWords(draft) }
                        .accessibilityIdentifier("daily-import-words-retry")
                }
                Button("Open Words", action: onOpenWords).accessibilityIdentifier("daily-open-words")
            } else if !review.wordCandidates.isEmpty {
                Button("Save supported words to Words") { importWords(draft) }
                    .accessibilityIdentifier("daily-import-words")
            }
        } else {
            Button("Refresh review") { Task { await fetchReview(draft) } }.disabled(eventPending)
                .accessibilityIdentifier("daily-review-refresh")
            if loading { ProgressView() }
        }
    }

    @MainActor private func startSpeaking(_ draft: DailyPracticeDraft) async {
        guard let session = accountStore.session, isCurrent(draft) else {
            message = "Sign in again to continue this attempt."; return
        }
        do {
            try await coordinator.startDailySpeaking(attemptID: draft.attemptID, package: draft.package,
                session: session) { fragment in
                    guard self.isCurrent(draft), fragment.voiceSegmentId == coordinator.dailySpeaking.voiceSegmentID else { return }
                    self.update { $0.appendSpeakingFragment(fragment) }
                }
        } catch {
            if isCurrent(draft) { message = error.localizedDescription }
        }
    }

    private func playListeningTranscript(_ draft: DailyPracticeDraft) {
        guard isCurrent(draft), !revisionConflict else { return }
        if audioIsPlaying {
            stopListening()
            return
        }
        audioIsPlaying = listeningReader.read(draft.package.listening.transcript,
            locale: draft.package.listening.speechLocale)
        if !audioIsPlaying {
            message = "The voice for this language is unavailable. Enable or install it, then retry or skip listening."
        } else {
            message = nil
        }
    }

    private func stopListening() {
        listeningReader.stop()
        audioIsPlaying = false
    }

    @MainActor private func skipListening(_ draft: DailyPracticeDraft) async {
        guard isCurrent(draft), !listeningReader.canRead(locale: draft.package.listening.speechLocale) else { return }
        _ = await submitEvent(["type": "stage_skipped", "stage": "listening", "activeMs": activeStageMilliseconds,
                               "reason": "local_tts_unavailable"], transition: .listeningSkipped)
    }

    @MainActor private func closeSpeaking(_ draft: DailyPracticeDraft, finalize: Bool = true) async {
        guard isCurrent(draft) else { return }
        do {
            guard let snapshot = try await DailyPracticeSpeakingCloseSnapshot.stopAndCapture(
                originalDraft: draft, segmentID: coordinator.dailySpeaking.voiceSegmentID,
                isCurrent: isCurrent, currentDraft: { self.draft },
                stop: { try await self.coordinator.stopDailySpeaking() }) else { return }
            let currentDraft = snapshot.draft
            let segment = snapshot.segmentID
            let fragments = snapshot.fragments
            var savedSegments = true
            for batch in DailyPracticeDraft.speakingFragmentBatches(fragments) {
                let payloadFragments = batch.map { fragment in
                    ["id": fragment.id, "role": fragment.role.rawValue, "text": fragment.text,
                     "voiceSegmentId": fragment.voiceSegmentId, "sequence": fragment.sequence,
                     "transcriptionUncertain": fragment.transcriptionUncertain] as [String: Any]
                }
                savedSegments = await submitEvent(["type": "speaking_segment_saved", "segmentId": segment,
                    "activeMs": activeStageMilliseconds, "fragments": payloadFragments,
                    "helpUsed": currentDraft.helpUsed.intersection(Set(currentDraft.package.goals.map(\.id))).sorted()], transition: .none)
                if savedSegments { stageStartedAtUptime = ProcessInfo.processInfo.systemUptime }
                if !savedSegments { break }
            }
            if savedSegments && finalize {
                _ = await submitEvent(["type": "speaking_closed"], transition: .practice)
            }
        } catch { message = "Speaking could not be confirmed closed. Keep the attempt open and try again." }
    }

    @MainActor private func skipSpeaking(_ draft: DailyPracticeDraft) async {
        guard isCurrent(draft) else { return }
        _ = await submitEvent(["type": "stage_skipped", "stage": "speaking", "activeMs": activeStageMilliseconds,
                               "reason": "learner_choice"], transition: .practice)
    }

    @MainActor private func submitStage(_ stage: DailyPracticeStage, draft: DailyPracticeDraft) async {
        var submissionDraft = draft
        if stage == .practice {
            submissionDraft = draft.requestingFinishAfterPractice()
            guard store.cacheDailyPracticeDraft(submissionDraft) else {
                message = "Your finish request could not be saved on this device."; return
            }
            self.draft = submissionDraft
        }
        let questions = stage == .listening ? draft.package.listening.questions : draft.package.practice.questions
        let answers = questions.map { question -> [String: Any] in
            guard let answer = draft.answers[question.id], !answer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                return ["questionId": question.id, "value": NSNull()]
            }
            return ["questionId": question.id, "value": answer]
        }
        let payload: [String: Any] = ["type": "stage_submitted", "stage": stage.rawValue,
            "activeMs": activeStageMilliseconds, "answers": answers,
            "helpUsed": draft.helpUsed.intersection(Set(questions.map(\.id))).sorted()]
        let next: DailyTransition = stage == .listening ? .speaking : .review
        guard await submitEvent(payload, transition: next) else { return }
        if stage == .practice {
            await finishPracticeIfNeeded(self.draft ?? submissionDraft)
        }
    }

    @MainActor private func finishPracticeIfNeeded(_ draft: DailyPracticeDraft) async {
        guard isCurrent(draft), draft.stage == .review, draft.finishAfterPractice == true,
              draft.inputHash == nil, !eventPending else { return }
        _ = await submitEvent(["type": "finished", "stopReason": "learner_choice"], transition: .finished)
    }

    @MainActor @discardableResult private func submitEvent(_ event: [String: Any], transition: DailyTransition) async -> Bool {
        guard !eventPending, !revisionConflict, let draft, draft.remoteConfirmed, let client, let session = accountStore.session,
              isCurrent(draft), let data = try? JSONSerialization.data(withJSONObject: [
                "schemaVersion": 1, "eventId": UUID().uuidString.lowercased(),
                "expectedRevision": draft.serverRevision, "event": event
              ], options: [.sortedKeys]),
              let eventID = store.enqueueDailyPracticeEvent(draft, envelope: data) else {
            message = "The saved progress could not be queued. Try again."; return false
        }
        eventPending = true
        let viewToken = navigationGeneration
        return await sendEnvelope(data, eventID: eventID, draft: draft, transition: transition,
                                  client: client, session: session, viewToken: viewToken)
    }

    @MainActor private func syncPendingEvents() async {
        guard let draft, let client, let session = accountStore.session, isCurrent(draft) else { return }
        for envelope in store.pendingDailyPracticeEvents(draft) {
            guard isCurrent(draft) else { return }
            guard let json = try? JSONSerialization.jsonObject(with: envelope) as? [String: Any],
                  let eventID = json["eventId"] as? String, let event = json["event"] as? [String: Any] else { continue }
            let transition = transition(for: event)
            let token = navigationGeneration
            guard await sendEnvelope(envelope, eventID: eventID, draft: draft, transition: transition,
                                     client: client, session: session, viewToken: token) else { return }
        }
        eventPending = !store.pendingDailyPracticeEvents(draft).isEmpty
    }

    @MainActor private func sendEnvelope(_ envelope: Data, eventID: String, draft: DailyPracticeDraft,
                                         transition: DailyTransition, client: DailyPracticeClient,
                                         session: ManagedAccountSession, viewToken: UUID) async -> Bool {
        guard isCurrent(draft), store.markDailyPracticeEvent(draft, eventID: eventID, state: "sending") else { return false }
        do {
            let data = try await client.appendEvent(session: session, attemptID: draft.attemptID, envelope: envelope)
            guard navigationGeneration == viewToken, isCurrent(draft),
                  let view = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  (view["attemptId"] as? String)?.lowercased() == draft.attemptID.lowercased(),
                  let revision = view["revision"] as? Int, revision >= draft.serverRevision else { return false }
            var changed = draft.acknowledgingServerRevision(revision)
            if let hash = view["inputHash"] as? String { changed = changed.freezingInputHash(hash) }
            if let envelopeObject = try? JSONSerialization.jsonObject(with: envelope) as? [String: Any],
               let event = envelopeObject["event"] as? [String: Any],
               event["type"] as? String == "speaking_segment_saved",
               let fragments = event["fragments"] as? [[String: Any]] {
                changed = changed.acknowledgingSpeakingFragments(fragments.compactMap { $0["id"] as? String })
            }
            changed = apply(transition, to: changed)
            guard store.acknowledgeDailyPracticeEvent(draft, eventID: eventID,
                acceptedDraft: changed, acceptedResponse: data) else { return false }
            self.draft = changed; saved = true; eventPending = !store.pendingDailyPracticeEvents(changed).isEmpty
            message = nil
            if transition == .finished {
                if changed.inputHash != nil { await fetchReview(changed) }
                else { message = "Your attempt is saved; the review is not ready yet." }
            }
            return true
        } catch let failure as DailyPracticeClientError {
            guard navigationGeneration == viewToken, isCurrent(draft) else { return false }
            if failure == .outcomeUnknown || failure == .unavailable || failure == .invalidResponse {
                _ = store.markDailyPracticeEvent(draft, eventID: eventID, state: "outcome_unknown")
                eventPending = true
                message = "The server outcome is not confirmed. Retry only the saved event; it will keep the same ID and content."
            } else if case .http(let status, _) = failure, status == 409 {
                _ = store.markDailyPracticeEvent(draft, eventID: eventID, state: "revision_conflict")
                if let rebased = await rebaseConfirmedConflict(envelope, eventID: eventID, draft: draft,
                    client: client, session: session, viewToken: viewToken) {
                    eventPending = true
                    return await sendEnvelope(rebased.envelope, eventID: eventID, draft: rebased.draft,
                        transition: transition, client: client, session: session, viewToken: viewToken)
                }
                blockAttempt(draft)
                message = "This attempt changed elsewhere and could not be safely reconciled. Keep the saved progress and review it on the updated attempt."
            } else if case .http(let status, _) = failure, status >= 500 {
                _ = store.markDailyPracticeEvent(draft, eventID: eventID, state: "outcome_unknown")
                eventPending = true; message = "The server outcome is not confirmed. Retry only the saved event."
            } else {
                _ = store.markDailyPracticeEvent(draft, eventID: eventID, state: "revision_conflict")
                blockAttempt(draft)
                message = "The saved event was rejected. This attempt is paused to protect your progress."
            }
            return false
        } catch {
            guard navigationGeneration == viewToken, isCurrent(draft) else { return false }
            _ = store.markDailyPracticeEvent(draft, eventID: eventID, state: "outcome_unknown")
            eventPending = true; message = "The server outcome is not confirmed. Retry only the saved event."
            return false
        }
    }

    @MainActor private func rebaseConfirmedConflict(_ envelope: Data, eventID: String, draft: DailyPracticeDraft,
                                                     client: DailyPracticeClient, session: ManagedAccountSession,
                                                     viewToken: UUID) async -> (draft: DailyPracticeDraft, envelope: Data)? {
        guard isCurrent(draft), let outer = try? JSONSerialization.jsonObject(with: envelope) as? [String: Any],
              let expected = outer["expectedRevision"] as? Int, expected == draft.serverRevision,
              let event = outer["event"] as? [String: Any], outer["eventId"] as? String == eventID,
              let type = event["type"] as? String else { return nil }
        let eventStage: DailyPracticeStage?
        switch type {
        case "preparation_completed": eventStage = .preparation
        case "stage_submitted", "stage_skipped": eventStage = (event["stage"] as? String).flatMap(DailyPracticeStage.init(rawValue:))
        case "speaking_segment_saved", "speaking_closed": eventStage = .speaking
        case "word_marked", "finished": eventStage = nil
        default: return nil
        }
        do {
            let data = try await client.attempt(session: session, attemptID: draft.attemptID)
            guard navigationGeneration == viewToken, isCurrent(draft),
                  let remote = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  (remote["attemptId"] as? String)?.lowercased() == draft.attemptID.lowercased(),
                  remote["packageVersionId"] as? String == draft.package.packageVersionId,
                  remote["state"] as? String == "in_progress",
                  let revision = remote["revision"] as? Int, revision > expected,
                  let rawStages = remote["completedStages"] as? [[String: Any]] else { return nil }
            var completed: [DailyPracticeStage: String] = [:]
            for item in rawStages {
                guard let rawStage = item["stage"] as? String, let stage = DailyPracticeStage(rawValue: rawStage),
                      stage != .review, let status = item["status"] as? String,
                      ["completed", "skipped"].contains(status) else { return nil }
                completed[stage] = status
            }
            if let eventStage, completed[eventStage] != nil { return nil }
            if type == "finished" && (completed[.practice] == nil || completed[.speaking] == nil) { return nil }
            let nextStage = DailyPracticeStage.allCases.first(where: { $0 != .review && completed[$0] == nil }) ?? .review
            guard let changed = draft.rebasingConfirmedEvent(eventID: eventID, toRevision: revision,
                remoteStage: nextStage, remoteCompletedStages: completed,
                remoteInputHash: remote["inputHash"] as? String),
                let changedEnvelope = store.rebaseDailyPracticeEvent(draft, eventID: eventID, acceptedDraft: changed) else {
                return nil
            }
            self.draft = changed; saved = true; revisionConflict = false
            return (changed, changedEnvelope)
        } catch { return nil }
    }

    private func transition(for event: [String: Any]) -> DailyTransition {
        switch event["type"] as? String {
        case "preparation_completed": .listening
        case "stage_submitted": event["stage"] as? String == "listening" ? .speaking : .review
        case "stage_skipped": event["stage"] as? String == "listening" ? .listeningSkipped : .practice
        case "speaking_closed": .practice
        case "finished": .finished
        default: .none
        }
    }

    private func apply(_ transition: DailyTransition, to draft: DailyPracticeDraft) -> DailyPracticeDraft {
        switch transition {
        case .none, .finished: draft
        case .listening: (try? draft.advance(to: .listening)) ?? draft
        case .listeningSkipped: (try? draft.skipCurrentStage()) ?? draft
        case .speaking: (try? draft.advance(to: .speaking)) ?? draft
        case .practice:
            if draft.stage == .speaking {
                return (try? draft.closeSpeaking().advance(to: .practice)) ?? draft
            }
            return (try? draft.skipCurrentStage()) ?? draft
        case .review: (try? draft.advance(to: .review)) ?? draft
        }
    }

    @MainActor private func fetchReview(_ draft: DailyPracticeDraft) async {
        guard let client, let session = accountStore.session, isCurrent(draft), draft.inputHash != nil else {
            message = "The review is not ready yet."; return
        }
        let viewToken = navigationGeneration
        loading = true
        defer { if navigationGeneration == viewToken, isCurrent(draft) { loading = false } }
        do {
            let result = try await client.review(session: session, attemptID: draft.attemptID)
            guard navigationGeneration == viewToken, isCurrent(draft) else { return }
            if result.status == 202 {
                let value = try JSONSerialization.jsonObject(with: result.data) as? [String: Any]
                reviewState = value?["state"] as? String ?? "queued"
                self.draft = draft; return
            }
            let validated = try DailyReviewResult.decodeAndValidate(result.data, for: draft)
            guard store.cacheDailyReview(validated, for: draft) else { return }
            review = validated; reviewState = "ready"
            self.draft = draft
        } catch let failure as DailyPracticeClientError {
            guard navigationGeneration == viewToken, isCurrent(draft) else { return }
            if case .http(_, let code) = failure, code == "review_failed" || code == "review_unknown" {
                reviewState = code == "review_failed" ? "failed" : "unknown"
            } else { reviewState = "unavailable" }
            message = "Your attempt remains saved. You can refresh the review later."
        } catch {
            guard navigationGeneration == viewToken, isCurrent(draft) else { return }
            reviewState = "invalid"
            message = "The review could not be validated, so no terms were imported."
        }
    }

    private func importWords(_ draft: DailyPracticeDraft) {
        guard let review, isCurrent(draft) else { return }
        importSummary = store.importDailyReview(review, for: draft)
        if importSummary == nil { message = "Supported terms could not be saved to Words on this device. Your review remains available." }
    }

    private func saveManualWord(_ draft: DailyPracticeDraft) {
        guard isCurrent(draft) else { return }
        let markID = UUID().uuidString.lowercased()
        let text = manualWordText.trimmingCharacters(in: .whitespacesAndNewlines)
        let meaning = manualWordMeaning.trimmingCharacters(in: .whitespacesAndNewlines)
        let kind = manualWordKind
        guard let summary = store.saveDailyWordMark(draft, markID: markID, text: manualWordText,
            meaning: manualWordMeaning, kind: manualWordKind) else {
            message = "The word or meaning could not be saved. Check the text and try again."
            return
        }
        importSummary = summary
        manualWordText = ""; manualWordMeaning = ""
        if draft.stage != .review {
            Task {
                _ = await submitEvent(["type": "word_marked", "markId": markID,
                    "text": text, "meaning": meaning, "kind": kind, "sourceQuestionId": NSNull()], transition: .none)
            }
        }
    }

    @MainActor private func blockAttempt(_ draft: DailyPracticeDraft) {
        let blocked = draft.blockingSync()
        _ = store.cacheDailyPracticeDraft(blocked)
        self.draft = blocked; revisionConflict = true; eventPending = false
    }

    private func isCurrent(_ draft: DailyPracticeDraft) -> Bool {
        draft.owner == store.activeLearnerIdentity && draft.restoreGeneration == store.dataGeneration &&
        store.isLearnerReady && self.draft?.attemptID == draft.attemptID
    }

    private func restoreOrLoad() {
        let cached = store.cachedDailyPracticeDrafts().filter { $0.owner == store.dailyPracticeOwnerKey }
        history = cached.filter(\.isCompletedReview).sorted { $0.package.editorialDate > $1.package.editorialDate }
        if let existing = cached.first(where: { !$0.isCompletedReview }) {
            let restored = existing.restored(for: store.dataGeneration)
            guard store.cacheDailyPracticeDraft(restored) else {
                message = "Your saved attempt could not be restored on this device."; return
            }
            draft = restored; package = restored.package; saved = true
            eventPending = !store.pendingDailyPracticeEvents(restored).isEmpty
            revisionConflict = restored.syncBlocked || store.dailyPracticeHasRevisionConflict(restored)
            if revisionConflict {
                message = "This attempt changed elsewhere and cannot safely continue here. Keep the saved attempt open on the original device."
            }
            if restored.stage == .review {
                Task {
                    await syncPendingEvents()
                    guard let current = self.draft, self.isCurrent(current) else { return }
                    await finishPracticeIfNeeded(current)
                    guard let latest = self.draft, self.isCurrent(latest) else { return }
                    await loadCachedOrFetchReview(latest)
                }
            }
        } else {
            eventPending = false; revisionConflict = false
            Task { await load() }
        }
    }

    private func openHistory(_ past: DailyPracticeDraft) {
        let restored = past.restored(for: store.dataGeneration)
        guard store.cacheDailyPracticeDraft(restored) else { message = "Your saved review could not be opened."; return }
        draft = restored; package = restored.package; review = nil; importSummary = nil; reviewState = "not_started"
        Task { await loadCachedOrFetchReview(restored) }
    }

    @MainActor private func markPreparedExpression(_ expression: DailyExpression, draft: DailyPracticeDraft) async {
        guard isCurrent(draft), !eventPending, !revisionConflict else { return }
        let markID = UUID().uuidString.lowercased()
        let meaning = draft.explanationLanguage == "es" ? expression.meanings.es : expression.meanings.en
        guard let summary = store.saveDailyWordMark(draft, markID: markID, text: expression.text,
            meaning: meaning, kind: "expression") else {
            message = "The expression could not be saved to Words on this device."
            return
        }
        importSummary = summary
        _ = await submitEvent(["type": "word_marked", "markId": markID,
            "text": expression.text, "meaning": meaning, "kind": "expression", "sourceQuestionId": NSNull()],
            transition: .none)
    }

    @MainActor private func loadCachedOrFetchReview(_ draft: DailyPracticeDraft) async {
        guard isCurrent(draft), draft.inputHash != nil else {
            message = "Your attempt is saved; the review is not ready yet."
            return
        }
        if let cached = store.cachedDailyReview(for: draft) {
            review = cached; reviewState = "ready"
        } else {
            await fetchReview(draft)
        }
    }

    @MainActor private func load() async {
        guard let client else { message = "Daily practice is not configured."; return }
        let token = navigationGeneration
        let generation = store.dataGeneration
        let owner = store.activeLearnerIdentity
        loading = true; message = nil
        defer { if token == navigationGeneration { loading = false } }
        do {
            let loaded = try await client.currentPackage(language: "en")
            guard token == navigationGeneration, generation == store.dataGeneration,
                  owner == store.activeLearnerIdentity, store.isLearnerReady else { return }
            package = loaded
            if let session = accountStore.session,
               let page = try? await client.attempts(session: session, language: "en", limit: 50),
               let json = try? JSONSerialization.jsonObject(with: page) as? [String: Any],
               let items = json["items"] as? [[String: Any]],
               items.contains(where: { ($0["packageVersionId"] as? String) == loaded.packageVersionId &&
                   ["in_progress", "completed"].contains($0["state"] as? String ?? "") }) {
                guard token == navigationGeneration, generation == store.dataGeneration,
                      owner == store.activeLearnerIdentity, store.isLearnerReady else { return }
                remoteAttemptNeedsLocalCopy = true
                message = "This attempt already exists online, but the server does not provide its full resume state. Use the device that started it."
            }
        } catch {
            guard token == navigationGeneration, generation == store.dataGeneration,
                  owner == store.activeLearnerIdentity else { return }
            message = "Today’s practice could not be loaded. Try again when you are online."
        }
    }

    @MainActor private func start(_ package: DailyPracticePackage) async {
        guard consentAccepted else {
            message = "Please review and accept the daily-practice data notice before starting."
            return
        }
        guard let client, let session = accountStore.session,
              let owner = store.dailyPracticeOwnerKey else {
            message = "Sign in to save and resume daily practice."; return
        }
        let token = navigationGeneration
        let generation = store.dataGeneration
        loading = true; message = nil
        defer { if token == navigationGeneration { loading = false } }
        let value = DailyPracticeDraft.start(attemptID: UUID().uuidString.lowercased(),
            idempotencyKey: UUID().uuidString.lowercased(), owner: owner, package: package,
            explanationLanguage: store.archive.preferences.meaningLanguage == "es" ? "es" : "en",
            restoreGeneration: store.dataGeneration)
            .acceptingDailyConsent(at: Date.now.timeIntervalSince1970 * 1000)
        guard token == navigationGeneration, generation == store.dataGeneration,
              value.owner == store.activeLearnerIdentity, store.cacheDailyPracticeDraft(value) else {
            message = "Your practice could not be saved on this device."; return
        }
        draft = value; saved = true
        eventPending = false; revisionConflict = false
        await confirmStart(value)
    }

    @MainActor private func confirmStart(_ draft: DailyPracticeDraft) async {
        guard let client, let session = accountStore.session else {
            message = "Sign in to confirm and resume daily practice."; return
        }
        loading = true; message = nil
        let generation = store.dataGeneration
        let owner = store.activeLearnerIdentity
        let viewToken = navigationGeneration
        defer { if viewToken == navigationGeneration { loading = false } }
        do {
            let data = try await client.createAttempt(session: session, attemptID: draft.attemptID,
                packageVersionID: draft.package.packageVersionId,
                explanationLanguage: draft.explanationLanguage, dailyConsentVersion: draft.dailyConsentVersion ?? 0,
                idempotencyKey: draft.idempotencyKey)
            guard let value = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let confirmedID = value["attemptId"] as? String else {
                throw DailyPracticeClientError.invalidResponse
            }
            guard confirmedID.lowercased() == draft.attemptID.lowercased() else {
                guard await adoptCanonicalAttempt(confirmedID, from: draft, client: client, session: session,
                    owner: owner, generation: generation, viewToken: viewToken) else {
                    remoteAttemptNeedsLocalCopy = true
                    message = "This attempt already exists online, but the server does not provide its full resume state. Use the device that started it."
                    return
                }
                return
            }
            guard
                  navigationGeneration == viewToken, self.draft?.attemptID == draft.attemptID,
                  store.dataGeneration == generation, store.activeLearnerIdentity == owner,
                  draft.owner == owner, draft.restoreGeneration == generation else {
                throw DailyPracticeClientError.invalidResponse
            }
            let confirmed = draft.confirmingRemoteCreation()
            guard store.cacheDailyPracticeDraft(confirmed) else { throw DailyPracticeClientError.unavailable }
            self.draft = confirmed; saved = true
        } catch {
            guard navigationGeneration == viewToken, store.dataGeneration == generation,
                  store.activeLearnerIdentity == owner else { return }
            if let failure = error as? DailyPracticeClientError,
               case .http(let status, let code) = failure, status == 409, code == "daily_attempt_limit" {
                if let canonicalID = await findCanonicalAttemptID(client: client, session: session,
                    package: draft.package, owner: owner, generation: generation, viewToken: viewToken,
                    provisionalAttemptID: draft.attemptID),
                   await adoptCanonicalAttempt(canonicalID, from: draft, client: client, session: session,
                    owner: owner, generation: generation, viewToken: viewToken) {
                    return
                }
                remoteAttemptNeedsLocalCopy = true
                message = "This attempt already exists online, but the server does not provide its full resume state. Use the device that started it."
                return
            }
            message = "The start could not be confirmed. Your saved request is ready to retry safely."
        }
    }

    @MainActor private func findCanonicalAttemptID(client: DailyPracticeClient, session: ManagedAccountSession,
        package: DailyPracticePackage, owner: String, generation: UInt64, viewToken: UUID,
        provisionalAttemptID: String) async -> String? {
        guard isCurrentDraftOwner(owner, generation: generation, viewToken: viewToken,
                                  attemptID: provisionalAttemptID) else { return nil }
        do {
            let data = try await client.attempts(session: session, language: package.language, limit: 50)
            guard isCurrentDraftOwner(owner, generation: generation, viewToken: viewToken,
                                      attemptID: provisionalAttemptID),
                  let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let items = object["items"] as? [[String: Any]] else { return nil }
            let matches = items.filter {
                ($0["packageVersionId"] as? String) == package.packageVersionId &&
                ($0["editorialDate"] as? String) == package.editorialDate &&
                ["in_progress", "completed"].contains($0["state"] as? String ?? "")
            }.compactMap { $0["attemptId"] as? String }
            return matches.count == 1 ? matches[0] : nil
        } catch { return nil }
    }

    @MainActor private func adoptCanonicalAttempt(_ attemptID: String, from provisional: DailyPracticeDraft,
        client: DailyPracticeClient, session: ManagedAccountSession, owner: String,
        generation: UInt64, viewToken: UUID) async -> Bool {
        guard UUID(uuidString: attemptID) != nil,
              isCurrentDraftOwner(owner, generation: generation, viewToken: viewToken,
                                  attemptID: provisional.attemptID) else { return false }
        do {
            let attemptData = try await client.attempt(session: session, attemptID: attemptID)
            guard isCurrentDraftOwner(owner, generation: generation, viewToken: viewToken,
                                      attemptID: provisional.attemptID),
                  let remote = try JSONSerialization.jsonObject(with: attemptData) as? [String: Any],
                  (remote["attemptId"] as? String)?.lowercased() == attemptID.lowercased(),
                  let packageID = remote["packageVersionId"] as? String,
                  let revision = remote["revision"] as? Int, revision >= 0,
                  let state = remote["state"] as? String, ["in_progress", "completed"].contains(state),
                  let stageRows = remote["completedStages"] as? [[String: Any]] else { return false }
            let packageData = try await client.attemptPackage(session: session, attemptID: attemptID)
            guard isCurrentDraftOwner(owner, generation: generation, viewToken: viewToken,
                                      attemptID: provisional.attemptID) else { return false }
            let snapshot = try DailyPracticePackage.decode(packageData)
            guard snapshot.packageVersionId == packageID, snapshot.language == provisional.package.language else { return false }
            var completed: [DailyPracticeStage: String] = [:]
            for row in stageRows {
                guard let rawStage = row["stage"] as? String,
                      let stage = DailyPracticeStage(rawValue: rawStage), stage != .review,
                      let status = row["status"] as? String, ["completed", "skipped"].contains(status) else { return false }
                completed[stage] = status
            }
            let adopted = DailyPracticeDraft.adoptingRemoteAttempt(attemptID: attemptID, owner: owner,
                package: snapshot, explanationLanguage: provisional.explanationLanguage,
                restoreGeneration: generation, revision: revision, state: state,
                completedStages: completed, inputHash: remote["inputHash"] as? String)
            guard isCurrentDraftOwner(owner, generation: generation, viewToken: viewToken,
                                      attemptID: provisional.attemptID),
                  store.adoptDailyPracticeAttempt(provisional, as: adopted) else { return false }
            draft = adopted; package = snapshot; saved = true; eventPending = false
            revisionConflict = false; remoteAttemptNeedsLocalCopy = false
            if adopted.stage == .review { await loadCachedOrFetchReview(adopted) }
            return true
        } catch { return false }
    }

    private func isCurrentDraftOwner(_ owner: String, generation: UInt64, viewToken: UUID,
                                     attemptID: String? = nil) -> Bool {
        navigationGeneration == viewToken && store.dataGeneration == generation &&
        store.activeLearnerIdentity == owner && store.isLearnerReady &&
        (attemptID == nil || draft?.attemptID == attemptID)
    }

    private func update(_ mutation: (DailyPracticeDraft) throws -> DailyPracticeDraft) {
        guard let draft else { return }
        do {
            let changed = try mutation(draft)
            self.draft = changed
            saved = store.cacheDailyPracticeDraft(changed)
            if !saved { message = "Your progress could not be saved on this device. Please try again." }
        } catch { message = "That step could not be saved yet." }
    }
}

@MainActor enum DailyPracticeSpeakingCloseSnapshot {
    struct Snapshot {
        let draft: DailyPracticeDraft
        let segmentID: String
        let fragments: [DailySpeakingFragment]
    }

    static func stopAndCapture(originalDraft: DailyPracticeDraft, segmentID: String?,
                               isCurrent: @MainActor (DailyPracticeDraft) -> Bool,
                               currentDraft: @MainActor () -> DailyPracticeDraft?,
                               stop: @MainActor () async throws -> Void) async throws -> Snapshot? {
        guard isCurrent(originalDraft) else { return nil }
        try await stop()
        guard isCurrent(originalDraft), let latestDraft = currentDraft(),
              latestDraft.attemptID == originalDraft.attemptID, latestDraft.stage == .speaking,
              isCurrent(latestDraft),
              let segmentID else { return nil }
        let uploaded = latestDraft.uploadedSpeakingFragmentIDs ?? []
        let fragments = latestDraft.speakingFragments.filter {
            $0.voiceSegmentId == segmentID && !uploaded.contains($0.id)
        }
        return Snapshot(draft: latestDraft, segmentID: segmentID, fragments: fragments)
    }
}
