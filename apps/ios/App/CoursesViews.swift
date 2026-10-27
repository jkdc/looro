import SwiftUI
import AVFoundation
import Foundation
import LearningCore

#if DEBUG
private struct PronunciationUITestCleanupBadge: View {
    @ObservedObject private var probe = PronunciationUITestProbe.shared

    var body: some View {
        Text("Cleanup count: \(probe.cleanupCount)")
            .font(.caption2).padding(4).background(.white.opacity(0.9), in: Capsule())
            .accessibilityIdentifier("pronunciation-cleanup-count")
    }
}
#endif

/// Learn tab: the course for the current language, its lessons and focused themes.
/// Read-aloud uses local speech synthesis; a missing course-language voice degrades
/// to silent reading. Practice routes through the coordinator's consent flow.
struct CoursesView: View {
    let coordinator: ConversationCoordinator
    let accountStore: ManagedAccountStore
    var onThemeChosen: () -> Void
    @State private var openLessonID: String?
    @State private var exercisesOpen = false
    @State private var recallOpen = false
    @State private var weeklyExamsOpen = false
    @State private var audioOn = true
    @State private var reader = LessonReader()

    private func projectedCourseData(for course: Course) -> (level: LevelStats, lessons: [LessonState]) {
        let (owner, remote) = coordinator.store.remoteProgressAggregates(languageID: course.languageID)
        let localLessons = coordinator.store.archive.lessons ?? []
        let lessonIDs = Set(course.units.flatMap { $0.lessons.map(\.id) })
        let lessons = owner.map {
            ProgressProjection.effectiveLessons(ownerAccountID: $0, languageID: course.languageID,
                local: localLessons, remote: remote, allowedIDs: lessonIDs)
        } ?? localLessons.filter { lessonIDs.contains($0.lessonID) }
        let level = LevelEngine.stats(course: course, lessons: localLessons,
            completions: coordinator.store.archive.completions ?? [],
            listeningTexts: ListeningRegistry.forLanguage(course.languageID),
            ownerAccountID: owner, remote: remote)
        return (level, lessons)
    }

    var body: some View {
        let courses = CourseRegistry.forLanguage(coordinator.language.id)
        let dueRecall = RecallEngine.dueCount(coordinator.store.archive, now: Date.now.timeIntervalSinceReferenceDate)
        Group {
            if weeklyExamsOpen {
                WeeklyExamHistoryView(store: coordinator.store, accountStore: accountStore,
                                      languageID: coordinator.language.id,
                                      onBack: { weeklyExamsOpen = false })
            } else if recallOpen {
                RecallSessionView(store: coordinator.store, onBack: { recallOpen = false })
            } else if let id = openLessonID, let lesson = CourseRegistry.lesson(id: id), exercisesOpen {
                ExerciseView(coordinator: coordinator, lesson: lesson, onBack: { exercisesOpen = false })
            } else if let id = openLessonID, let lesson = CourseRegistry.lesson(id: id) {
                LessonDetailView(coordinator: coordinator, lesson: lesson, audioOn: $audioOn, reader: reader,
                    onBack: { openLessonID = nil; reader.stop() },
                    onPractice: { id in
                        reader.stop(); coordinator.selectLesson(id); coordinator.start(); onThemeChosen()
                    },
                    onTheme: { theme in
                        reader.stop(); coordinator.chooseTheme(theme); onThemeChosen()
                    },
                    onExercises: { exercisesOpen = true })
            } else if courses.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    LanguageSwitcher(coordinator: coordinator)
                    Text("No courses yet").font(.headline).padding(.top, 8)
                    Text("Courses for this language are on the way. Meanwhile, Themes is the place to practise.")
                        .font(.subheadline).foregroundStyle(AppColor.secondary)
                }.padding(24)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 24) {
                        PageHeading(eyebrow: "Your course", title: "Learn it step by step",
                                    subtitle: "Read a short lesson, then practise it in a real conversation.")
                        LanguageSwitcher(coordinator: coordinator)
                        Button { weeklyExamsOpen = true } label: {
                            Label("Weekly exams and results", systemImage: "checkmark.seal")
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(16)
                                .background(.white.opacity(0.8), in: RoundedRectangle(cornerRadius: 18))
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("weekly-exams-entry")
                        if dueRecall > 0 {
                            RecallCardView(count: dueRecall) { recallOpen = true }
                        }
                        ForEach(courses) { course in
                            let projection = projectedCourseData(for: course)
                            VStack(alignment: .leading, spacing: 14) {
                                HStack(spacing: 12) {
                                    Image(systemName: "graduationcap")
                                        .font(.system(size: 26, weight: .light)).foregroundStyle(AppColor.secondary)
                                    VStack(alignment: .leading) {
                                        Text(course.title).font(.headline)
                                        Text(course.subtitle).font(.caption).foregroundStyle(AppColor.secondary)
                                    }
                                }
                                Text(course.description).font(.subheadline)
                                if let progress = coordinator.store.progressStats.courses.first(where: { $0.courseID == course.id }),
                                   progress.lessonsTotal > 0 {
                                    Text("Completed: \(progress.percent)%").font(.caption2).foregroundStyle(AppColor.secondary)
                                }
                                LevelCardView(level: projection.level)
                                ForEach(course.units) { unit in
                                    UnitSection(coordinator: coordinator, unit: unit, level: projection.level,
                                        lessons: projection.lessons) { openLessonID = $0; reader.stop() }
                                }
                            }.padding(22)
                                .background(.white.opacity(0.8), in: RoundedRectangle(cornerRadius: 26))
                        }
                    }.padding(.horizontal, 24).padding(.bottom, 110)
                }.scrollIndicators(.hidden)
            }
        }.foregroundStyle(AppColor.ink)
#if DEBUG
        .overlay(alignment: .bottomTrailing) {
            if ProcessInfo.processInfo.arguments.contains("--pronunciation-lifecycle-ui-test") {
                PronunciationUITestCleanupBadge()
            }
        }
#endif
    }
}

/// Local exam history entry point. Attempt details always come from the stored
/// immutable snapshot rather than the current week's exam catalog.
private struct WeeklyExamHistoryView: View {
    let store: LearningStore
    let accountStore: ManagedAccountStore
    let languageID: String
    let onBack: () -> Void
    @State private var attempts: [ExamHistory] = []
    @State private var selected: ExamHistory?
    @State private var activeExam: ActiveLocalExam?
    @State private var loadError: String?
    @State private var isStartingExam = false

    var body: some View {
        Group {
            if let activeExam {
                LocalExamRunView(store: store, run: activeExam) {
                    self.activeExam = nil
                    loadHistory()
                }
            } else {
                VStack(alignment: .leading, spacing: 16) {
                    Button(action: onBack) { Label("Back to Learn", systemImage: "chevron.left") }
                        .accessibilityIdentifier("weekly-exams-back")
                    Text("Weekly exams").font(.largeTitle.bold()).accessibilityIdentifier("weekly-exam-title")
                    Text(accountStore.session == nil
                         ? "These attempts are saved on this device. Sign in to view account-synced exam history."
                         : "Exam history is scoped to the signed-in account. Pending attempts stay on this device until synchronization succeeds.")
                        .font(.subheadline).foregroundStyle(AppColor.secondary)
                    if let loadError {
                        ContentUnavailableView("History unavailable", systemImage: "externaldrive.badge.exclamationmark",
                                               description: Text(loadError))
                    } else {
                        ScrollView {
                            VStack(alignment: .leading, spacing: 12) {
                                Text("Start an exam").font(.headline)
                                ForEach(LevelTestsRegistry.all, id: \.languageID) { bank in
                                    ForEach(bank.levels, id: \.level) { level in
                                        Button { start(level: level) } label: {
                                            Label("\(bank.languageID.uppercased()) · \(level.level) weekly exam", systemImage: "play.circle")
                                                .frame(maxWidth: .infinity, alignment: .leading)
                                                .padding(14).background(.white.opacity(0.8), in: RoundedRectangle(cornerRadius: 16))
                                        }.buttonStyle(.plain)
                                            .disabled(isStartingExam)
                                            .accessibilityIdentifier("weekly-exam-start-\(bank.languageID)-\(level.level)")
                                    }
                                }
                                Text("Saved attempts").font(.headline).padding(.top, 12)
                                if accountStore.session != nil {
                                    #if DEBUG && targetEnvironment(simulator)
                                    if accountStore.isExamRecoveryUITestFixture {
                                        Button("Switch test account") {
                                            accountStore.switchExamRecoveryUITestAccount()
                                        }
                                        .accessibilityIdentifier("exam-recovery-switch-account")
                                    }
                                    #endif
                                    Button("Sync saved attempts") {
                                        accountStore.syncExamHistory(using: store) { loadHistory() }
                                    }
                                    .buttonStyle(.bordered)
                                    .accessibilityIdentifier("weekly-exam-sync")
                                    .disabled(accountStore.isBusy)
                                    Button("Restore saved attempts") {
                                        accountStore.restoreExamHistory(using: store) { loadHistory() }
                                    }
                                    .buttonStyle(.bordered)
                                    .accessibilityIdentifier("weekly-exam-restore")
                                    .disabled(accountStore.isBusy)
                                    if let message = accountStore.message {
                                        Text(message).font(.footnote).foregroundStyle(AppColor.secondary)
                                            .accessibilityIdentifier("weekly-exam-sync-message")
                                    }
                                }
                                if attempts.isEmpty {
                                    Text("No saved exams yet").font(.subheadline).foregroundStyle(AppColor.secondary)
                                }
                                ForEach(attempts.indices, id: \.self) { index in
                                    let history = attempts[index]
                                    VStack(alignment: .leading, spacing: 8) {
                                        Button { open(history) } label: {
                                            VStack(alignment: .leading, spacing: 4) {
                                                Text("\(history.snapshot["level"] as? String ?? "Exam") · week \(history.snapshot["week_id"] as? Int64 ?? 0)")
                                                Text(historyStatus(history))
                                                    .font(.caption).foregroundStyle(AppColor.secondary)
                                            }.frame(maxWidth: .infinity, alignment: .leading)
                                        }.buttonStyle(.plain)
                                            .accessibilityIdentifier("exam-history-\(history.attempt["attempt_id"] as? String ?? "unknown")")
                                        if history.attempt["sync_state"] as? String == "conflict",
                                           let accountID = accountStore.session?.accountID,
                                           let attemptID = history.attempt["attempt_id"] as? String {
                                            HStack {
                                                Button("Keep both versions") {
                                                    do {
                                                        guard try store.forkExamConflict(ownerAccountID: accountID,
                                                            attemptID: attemptID,
                                                            forkAttemptID: UUID().uuidString.lowercased()) else {
                                                            throw ExamHistoryError.conflictResolutionFailed
                                                        }
                                                        loadHistory()
                                                        accountStore.syncExamHistory(using: store) { loadHistory() }
                                                    } catch {
                                                        loadError = "The exam versions could not be separated. Neither version was discarded."
                                                    }
                                                }
                                                .accessibilityIdentifier("exam-conflict-fork-\(attemptID)")
                                                Button("Use account version") {
                                                    do {
                                                        guard try store.takeOverExamConflict(ownerAccountID: accountID,
                                                                                             attemptID: attemptID) else {
                                                            throw ExamHistoryError.conflictResolutionFailed
                                                        }
                                                        loadHistory()
                                                    } catch {
                                                        loadError = "The account version could not be applied. Your saved version was not changed."
                                                    }
                                                }
                                                .accessibilityIdentifier("exam-conflict-takeover-\(attemptID)")
                                            }.buttonStyle(.bordered)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.padding(24)
            }
        }
        .task { loadHistory() }
        .onChange(of: accountStore.session?.accountID) { _, _ in
            // Never keep the previous account's history visible after an account
            // switch or sign-out. A running attempt is already durably autosaved.
            activeExam = nil
            selected = nil
            loadHistory()
        }
    }

    private func loadHistory() {
        do { attempts = try store.examHistory(ownerAccountID: accountStore.session?.accountID) }
        catch { loadError = "Your saved exam data is still on this device, but couldn’t be opened." }
    }

    private func historyStatus(_ history: ExamHistory) -> String {
        let state = history.attempt["state"] as? String ?? "in_progress"
        let syncState = history.attempt["sync_state"] as? String ?? "local_only"
        let progress = state == "completed" ? "Completed · original result saved" : "In progress"
        switch syncState {
        case "synced": return "\(progress) · synced to account"
        case "pending": return "\(progress) · waiting to sync"
        case "conflict": return "\(progress) · sync conflict needs review"
        default: return "\(progress) · on this device only"
        }
    }

    private func start(level: LevelTestLevel) {
        guard !isStartingExam else { return }
        isStartingExam = true
        loadError = nil
        Task { @MainActor in
            defer { isStartingExam = false }
            do {
                let prepared: ExamSnapshotFactory.PreparedExam
                do {
                    if let remote = try await accountStore.fetchWeeklyExam(languageID: level.languageID,
                                                                           level: level.level) {
                        prepared = remote
                    } else {
                        prepared = try bundledExam(for: level)
                    }
                } catch let error as ManagedAccountError where
                    error == .transport || error == .server("service_unavailable") {
                    prepared = try bundledExam(for: level)
                }
                let startedAt = Date.now.timeIntervalSince1970
                let attemptID = UUID().uuidString.lowercased()
                let history = try store.startExam(snapshot: prepared.snapshot,
                    attempt: ExamAttemptRecord(attemptID: attemptID, examID: prepared.snapshot.examID,
                                               ownerAccountID: accountStore.session?.accountID,
                                               startedAt: startedAt))
                activeExam = ActiveLocalExam(history: history, level: prepared.level, exam: prepared.exam,
                                             answers: [:], position: 0, completion: nil)
            } catch {
                loadError = "The exam couldn’t be validated or saved, so it hasn’t started. Please try again."
            }
        }
    }

    private func bundledExam(for level: LevelTestLevel) throws -> ExamSnapshotFactory.PreparedExam {
        let exam = LevelTestEngine.compose(level: level, weekIndex: LevelTestEngine.weekIndex())
        let snapshot = try ExamSnapshotFactory.bundled(level: level, exam: exam)
        return ExamSnapshotFactory.PreparedExam(snapshot: snapshot, level: level, exam: exam)
    }

    private func open(_ history: ExamHistory) {
        do {
            guard let contentJSON = history.snapshot["content_json"] as? String else {
                throw ExamHistoryError.invalidJSON
            }
            let restored = try ExamSnapshotFactory.restore(contentJSON: contentJSON)
            let answers = Dictionary(uniqueKeysWithValues: history.answers.compactMap { row -> (String, String)? in
                guard let questionID = row["question_id"] as? String,
                      let data = (row["response_json"] as? String)?.data(using: .utf8),
                      let response = try? JSONSerialization.jsonObject(with: data) as? [String: String],
                      let answer = response["answer"] else { return nil }
                return (questionID, answer)
            })
            let position = Int(history.attempt["position"] as? Int64 ?? 0)
            let completion = (history.result?["result_json"] as? String).flatMap {
                try? JSONDecoder().decode(ExamCompletionRecord.self, from: Data($0.utf8))
            }
            activeExam = ActiveLocalExam(history: history, level: restored.level, exam: restored.exam,
                                         answers: answers, position: position, completion: completion)
        } catch { loadError = "This saved exam couldn’t be opened. Its original data has not been changed." }
    }
}

private struct ExamCompletionRecord: Codable {
    let result: LevelTestResult
    let answers: [String: String]
    let verdicts: [String: Bool]
}

private struct ActiveLocalExam {
    let history: ExamHistory
    let level: LevelTestLevel
    let exam: LevelTestEngine.Exam
    var answers: [String: String]
    var position: Int
    var completion: ExamCompletionRecord?
}

private struct LocalExamRunView: View {
    let store: LearningStore
    @State var run: ActiveLocalExam
    let onClose: () -> Void
    @State private var answer = ""
    @State private var saveError: String?

    private var isReview: Bool { run.completion != nil }
    private var currentItem: LevelTestItem? {
        run.exam.items.indices.contains(run.position) ? run.exam.items[run.position] : nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Button(action: onClose) { Label("Back to history", systemImage: "chevron.left") }
                .accessibilityIdentifier("weekly-exam-attempt-back")
            if let completion = run.completion {
                Text("Original exam result").font(.title2.bold())
                Text("Score: \(completion.result.overall)% · \(completion.result.passed ? "Passed" : "Keep practising")")
                    .font(.headline).accessibilityIdentifier("weekly-exam-result")
                ScrollView {
                    ForEach(run.exam.items, id: \.id) { item in
                        let correct = completion.verdicts[item.id] ?? false
                        VStack(alignment: .leading, spacing: 5) {
                            if let passageID = item.passageId, let passage = run.exam.passageById[passageID] {
                                Text(passage.text).font(.body).accessibilityIdentifier("weekly-exam-review-passage-\(item.id)")
                                    .padding(12).background(.white.opacity(0.7), in: RoundedRectangle(cornerRadius: 12))
                            }
                            Text(item.exercise.prompt).font(.headline)
                            Text("Your answer: \(completion.answers[item.id] ?? "—")")
                            Text(correct ? "Correct" : "Incorrect · \(item.exercise.answers.joined(separator: ", "))")
                                .foregroundStyle(correct ? AppColor.ink : AppColor.secondary)
                            if !item.exercise.explain.isEmpty { Text(item.exercise.explain).font(.caption) }
                        }.padding(.vertical, 8)
                    }
                }
            } else if let item = currentItem {
                Text("\(run.level.level) · question \(run.position + 1) of \(run.exam.items.count)")
                    .font(.caption).foregroundStyle(AppColor.secondary)
                if let passageID = item.passageId, let passage = run.exam.passageById[passageID] {
                    ScrollView { Text(passage.text).font(.body).padding(12) }
                        .frame(maxHeight: 180).background(.white.opacity(0.7), in: RoundedRectangle(cornerRadius: 12))
                }
                Text(item.exercise.prompt).font(.title3.bold())
                    .accessibilityIdentifier("weekly-exam-current-prompt")
                if item.exercise.options.isEmpty {
                    TextField("Your answer", text: $answer, axis: .vertical).textFieldStyle(.roundedBorder)
                        .accessibilityIdentifier("weekly-exam-written-answer")
                } else {
                    ForEach(item.exercise.options, id: \.self) { option in
                        Button { answer = option } label: {
                            HStack { Text(option); Spacer(); if answer == option { Image(systemName: "checkmark.circle.fill") } }
                                .padding(12).background(.white.opacity(0.8), in: RoundedRectangle(cornerRadius: 12))
                        }.buttonStyle(.plain).accessibilityIdentifier("weekly-exam-option")
                    }
                }
                if let saveError { Text(saveError).foregroundStyle(AppColor.secondary).font(.caption) }
                Button(run.position + 1 == run.exam.items.count ? "Finish exam" : "Save and continue") {
                    saveCurrentAnswer()
                }.buttonStyle(.borderedProminent).accessibilityIdentifier("weekly-exam-save")
            } else {
                Text("Your answers are saved. The result still needs to be recorded.").font(.headline)
                if let saveError { Text(saveError).foregroundStyle(AppColor.secondary).font(.caption) }
                Button("Save result") { complete() }.buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("weekly-exam-save-result")
            }
        }.padding(24).onAppear {
            if let item = currentItem { answer = run.answers[item.id] ?? "" }
        }
    }

    private func saveCurrentAnswer() {
        guard let item = currentItem else { return }
        let previous = run.answers
        run.answers[item.id] = answer
        do {
            let bytes = try JSONSerialization.data(withJSONObject: ["answer": answer], options: [.sortedKeys])
            guard let responseJSON = String(data: bytes, encoding: .utf8) else { throw ExamHistoryError.invalidJSON }
            let next = run.position + 1
            _ = try store.saveExamAnswer(attemptID: run.history.attempt["attempt_id"] as? String ?? "",
                                         questionID: item.id, responseJSON: responseJSON, position: next)
            saveError = nil
            if next == run.exam.items.count { run.position = next; complete() }
            else {
                run.position = next
                answer = run.answers[run.exam.items[next].id] ?? ""
            }
        } catch {
            run.answers = previous
            saveError = "Your answer wasn’t saved. It is still on this screen; try again."
        }
    }

    private func complete() {
        let at = Int64(Date.now.timeIntervalSince1970)
        let result = LevelTestEngine.grade(level: run.level, exam: run.exam, attempts: run.answers, at: at)
        let verdicts = Dictionary(uniqueKeysWithValues: run.exam.items.map {
            ($0.id, ExerciseEngine.isCorrect(exercise: $0.exercise, attempt: run.answers[$0.id] ?? ""))
        })
        let completion = ExamCompletionRecord(result: result, answers: run.answers, verdicts: verdicts)
        do {
            let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
            let bytes = try encoder.encode(completion)
            guard let json = String(data: bytes, encoding: .utf8) else { throw ExamHistoryError.invalidJSON }
            _ = try store.completeExam(attemptID: run.history.attempt["attempt_id"] as? String ?? "",
                                       resultJSON: json, completedAt: Date.now.timeIntervalSince1970)
            run.completion = completion
        } catch {
            saveError = "The result couldn’t be saved. Your answers remain saved on this device."
        }
    }
}

/// Engagement level titles — presentation only, never a language-proficiency claim.
func levelTitle(_ level: Int) -> String {
    ["Newcomer", "First steps", "Finding the rhythm", "On the move", "Steady pace",
     "Halfway up", "Getting good", "High ground", "Almost there", "The summit", "Foundations champion"][min(max(level, 0), 10)]
}

/// Quick learning-language switcher: every language keeps its own progress.
private struct LanguageSwitcher: View {
    let coordinator: ConversationCoordinator

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(LanguageRegistry.all, id: \.id) { module in
                    let selected = module.id == coordinator.language.id
                    Button {
                        coordinator.selectLanguage(module.id)
                    } label: {
                        Text(module.nativeName).font(.caption.weight(selected ? .semibold : .regular))
                            .padding(.horizontal, 14).padding(.vertical, 8)
                            .background(selected ? AppColor.sage : .white.opacity(0.8), in: Capsule())
                            .foregroundStyle(selected ? AppColor.ink : AppColor.secondary)
                    }.buttonStyle(.plain).disabled(coordinator.isRunning)
                        .accessibilityIdentifier("language-" + module.id)
                }
            }.scrollIndicators(.hidden)
        }
    }
}

private struct LevelCardView: View {
    let level: LevelStats
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                ZStack {
                    Circle().fill(AppColor.sage).frame(width: 46, height: 46)
                    Text("\(level.level)").font(.headline)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text("Level \(level.level) · \(levelTitle(level.level))").font(.subheadline.weight(.semibold))
                    if let toNext = level.pointsToNext {
                        Text(toNext == 1 ? "1 point to Level \(level.level + 1)"
                                : "\(toNext) points to Level \(level.level + 1)")
                            .font(.caption).foregroundStyle(AppColor.secondary)
                    } else {
                        Text("Course complete!").font(.caption).foregroundStyle(AppColor.secondary)
                    }
                    Text("\(level.lessonPoints) lesson points · \(level.listeningPoints) listening points")
                        .font(.caption).foregroundStyle(AppColor.secondary)
                }
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(AppColor.cream)
                    Capsule().fill(AppColor.secondary)
                        .frame(width: geo.size.width * CGFloat(min(1, max(0, level.progressInLevel))))
                }
            }.frame(height: 6)
        }
    }
}

private struct MasteryStars: View {
    let tier: Int
    var body: some View {
        HStack(spacing: 2) {
            ForEach(0..<3, id: \.self) { index in
                Image(systemName: index < tier ? "star.fill" : "star")
                    .font(.caption2)
                    .foregroundStyle(AppColor.secondary.opacity(index < tier ? 1 : 0.25))
            }
        }.padding(.trailing, 8)
    }
}

private struct UnitSection: View {
    let coordinator: ConversationCoordinator
    let unit: CourseUnit
    let level: LevelStats
    let lessons: [LessonState]
    let open: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(unit.title).font(.subheadline.weight(.semibold))
                Spacer()
                if !unit.lessons.isEmpty {
                    let done = unit.lessons.filter { coordinator.store.completedLessonIDs.contains($0.id) }.count
                    Text("Completed: \(done * 100 / unit.lessons.count)%")
                        .font(.caption2).foregroundStyle(AppColor.secondary)
                }
            }
            ForEach(unit.lessons) { lesson in
                Button { open(lesson.id) } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(lesson.title).font(.subheadline.weight(.semibold))
                            Text(lesson.goal).font(.caption).foregroundStyle(AppColor.secondary)
                        }
                        Spacer()
                        let tier = level.mastery.first { $0.lessonID == lesson.id }?.tier ?? 0
                        if tier > 0 { MasteryStars(tier: tier) }
                        Text(attemptLabel(lesson.id, lessons: lessons))
                            .font(.caption2).foregroundStyle(AppColor.secondary)
                    }.padding(16).background(AppColor.cream, in: RoundedRectangle(cornerRadius: 18))
                }.buttonStyle(.plain).accessibilityIdentifier("lesson-open-\(lesson.id)")
            }
        }
    }

    private func attemptLabel(_ lessonID: String, lessons: [LessonState]) -> String {
        let attempts = lessons.first(where: { $0.lessonID == lessonID })?.attempts ?? 0
        return attempts == 0 ? "New" : "Practised \(attempts)×"
    }
}

private struct LessonDetailView: View {
    @Environment(\.scenePhase) private var scenePhase
    let coordinator: ConversationCoordinator
    let lesson: Lesson
    @Binding var audioOn: Bool
    let reader: LessonReader
    let onBack: () -> Void
    let onPractice: (String) -> Void
    let onTheme: (ConversationTheme) -> Void
    let onExercises: () -> Void
    @StateObject private var drill = PronunciationCheckController()

    private var lessonCourseLanguage: String {
        CourseRegistry.all.first { $0.units.flatMap(\.lessons).contains { $0.id == lesson.id } }?.languageID ?? "en"
    }

    private var unit: CourseUnit? {
        CourseRegistry.all.flatMap(\.units).first { $0.lessons.contains { $0.id == lesson.id } }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Button { onBack() } label: {
                        Image(systemName: "chevron.backward").font(.headline.weight(.semibold))
                            .padding(12).background(.white.opacity(0.8), in: Circle())
                    }.buttonStyle(.plain).accessibilityLabel("Back to lessons")
                    Spacer()
                    Toggle(isOn: $audioOn) { Text("Read aloud").font(.caption).foregroundStyle(AppColor.secondary) }
                        .toggleStyle(.switch).labelsHidden()
                        .accessibilityLabel("Read aloud")
                        .onChange(of: audioOn) { _, on in if !on { reader.stop() } }
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text(lesson.title).font(.largeTitle.weight(.semibold))
                    Text(lesson.goal).font(.subheadline).foregroundStyle(AppColor.secondary)
                }
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(Array(lesson.intro.explanation.enumerated()), id: \.offset) { _, paragraph in
                        Text(paragraph).font(.body)
                    }
                }
                sectionHeading("Examples")
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(lesson.intro.examples.enumerated()), id: \.offset) { _, example in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(example.text).font(.subheadline.weight(.semibold))
                            Text(example.meaning).font(.caption).foregroundStyle(AppColor.secondary)
                        }
                    }
                }
                sectionHeading("Target expressions")
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(lesson.expressions.enumerated()), id: \.offset) { _, expression in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(expression.text + "  —  " + expression.meaning).font(.subheadline.weight(.semibold))
                            Text(expression.note).font(.caption).foregroundStyle(AppColor.secondary)
                        }
                    }
                }
                sectionHeading("Practice situation")
                Text(lesson.situation).font(.body)
                if coordinator.store.preferences.pronunciationEnabled == true,
                   !lesson.skills.filter({ $0.hasPrefix("pron:") }).isEmpty {
                    sectionHeading("Pronounce it")
                    Text("Tap a phrase to hear it, then Check and say it out loud.")
                        .font(.caption).foregroundStyle(AppColor.secondary)
                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(Array(lesson.expressions.prefix(3).enumerated()), id: \.offset) { _, expression in
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Text(expression.text).font(.subheadline.weight(.semibold))
                                    Spacer()
                                    if drill.state.isBusy {
                                        Text(drill.state == .recognizing ? "Checking…" : "Listening…")
                                            .font(.caption).foregroundStyle(AppColor.secondary)
                                    } else {
                                        Button {
                                            reader.stop()
                                            drill.check(target: expression.text, lessonID: lesson.id,
                                                        language: lessonCourseLanguage,
                                                        store: coordinator.store) { _ in }
                                        } label: {
                                            Text("Check").font(.caption.weight(.semibold))
                                                .padding(.horizontal, 12).padding(.vertical, 6)
                                                .background(AppColor.orange, in: Capsule())
                                        }.buttonStyle(.plain).accessibilityIdentifier("pronunciation-check")
                                    }
                                }
                                Text(expression.note).font(.caption).foregroundStyle(AppColor.secondary)
                                if let statusMessage = drill.statusMessage {
                                    Text(statusMessage).font(.caption).foregroundStyle(AppColor.secondary)
                                        .accessibilityIdentifier("pronunciation-status")
                                }
                                if let hit = drill.result, hit.target == expression.text {
                                    Text(PronunciationEngine.passed(hit.score)
                                         ? "Pronunciation: \(hit.score)% — passed, well said!"
                                         : "Pronunciation: \(hit.score)% — not quite, listen again and try once more")
                                        .font(.caption).foregroundStyle(AppColor.secondary)
                                }
                            }
                        }
                    }
                }
                if !lesson.exercises.isEmpty {
                    sectionHeading("Exercises")
                    Button { onExercises() } label: {
                        Text("Practise exercises").font(.headline).frame(maxWidth: .infinity).padding(18)
                            .background(AppColor.orange, in: Capsule())
                    }.buttonStyle(.plain)
                }
                if let unit {
                    sectionHeading("Extra conversation themes")
                    ScrollView(.horizontal) {
                        HStack(spacing: 10) {
                            ForEach(unit.themes) { theme in
                                Button { onTheme(theme) } label: {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(theme.title).font(.subheadline.weight(.semibold))
                                        Text(theme.subtitle).font(.caption)
                                    }.padding(14).background(AppColor.panels[theme.colorIndex],
                                        in: RoundedRectangle(cornerRadius: 20))
                                }.buttonStyle(.plain)
                            }
                        }.scrollIndicators(.hidden)
                    }
                }
                Button { onPractice(lesson.id) } label: {
                    Text("Practise speaking").font(.headline).frame(maxWidth: .infinity).padding(18)
                        .background(AppColor.orange, in: Capsule())
                }.buttonStyle(.plain).padding(.top, 6)
            }.padding(.horizontal, 24).padding(.top, 14).padding(.bottom, 110)
        }.scrollIndicators(.hidden)
        .onAppear { if audioOn { read() } }
        .onChange(of: audioOn) { _, on in if on { read() } }
        .onChange(of: scenePhase) { _, phase in if phase == .background { drill.cancel() } }
        .onDisappear { drill.cancel() }
    }

    private func read() {
        reader.read(lesson.title + "   " + lesson.intro.explanation.joined(separator: "   ")
            + "   " + lesson.intro.examples.map(\.text).joined(separator: "   "),
            locale: coordinator.language.locale,
            rate: Float(coordinator.store.preferences.effectiveSpeechRate()))
    }

    private func sectionHeading(_ text: String) -> some View {
        Text(text).font(.subheadline.weight(.semibold)).foregroundStyle(AppColor.secondary)
    }
}

/// Authored offline exercises for a lesson: choice, written gap, word ordering and
/// free written answer — graded locally by ExerciseEngine, no AI involved. Finishing
/// the set records one completion with the score.
struct ExerciseView: View {
    let coordinator: ConversationCoordinator
    let lesson: Lesson
    let onBack: () -> Void
    @State private var index = 0
    @State private var answer = ""
    @State private var selected: Int?
    @State private var verdict: Bool?
    @State private var correctCount = 0
    @State private var tokens: [String] = []
    @State private var chosen: [Int] = []

    private var exercise: Exercise? { lesson.exercises.indices.contains(index) ? lesson.exercises[index] : nil }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Button { onBack() } label: {
                        Image(systemName: "chevron.backward").font(.headline.weight(.semibold))
                            .padding(12).background(.white.opacity(0.8), in: Circle())
                    }.buttonStyle(.plain).accessibilityLabel("Back to the lesson")
                    Spacer()
                    Text(lesson.title).font(.caption).foregroundStyle(AppColor.secondary)
                }
                Text("Exercises").font(.largeTitle.weight(.semibold))
                if let exercise {
                    Text("Exercise \(index + 1) of \(lesson.exercises.count)")
                        .font(.caption).foregroundStyle(AppColor.secondary)
                    card(exercise)
                } else if !lesson.exercises.isEmpty {
                    Text("You answered \(correctCount) of \(lesson.exercises.count) correctly.")
                        .font(.title2.weight(.semibold))
                    Button { onBack() } label: {
                        Text("Finish").font(.headline).frame(maxWidth: .infinity).padding(18)
                            .background(AppColor.orange, in: Capsule())
                    }.buttonStyle(.plain)
                }
            }.padding(.horizontal, 24).padding(.top, 14).padding(.bottom, 110)
        }.scrollIndicators(.hidden)
        .onAppear { prepare() }
        .accessibilityIdentifier("exercise-screen")
    }

    @ViewBuilder
    private func card(_ exercise: Exercise) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(exercise.prompt).font(.title3.weight(.medium))
            if verdict == nil {
                switch exercise.kind {
                case ExerciseEngine.choice:
                    ForEach(Array(exercise.options.enumerated()), id: \.offset) { optionIndex, option in
                        Button { selected = optionIndex } label: {
                            Text(option).font(.subheadline.weight(.semibold)).frame(maxWidth: .infinity, alignment: .leading)
                                .padding(16).background(selected == optionIndex ? AppColor.sage : AppColor.cream,
                                    in: RoundedRectangle(cornerRadius: 18))
                        }.buttonStyle(.plain)
                    }
                case ExerciseEngine.order:
                    Text(chosen.map { tokens[$0] }.joined(separator: " "))
                        .font(.subheadline.weight(.semibold)).frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                        .padding(16).background(.white, in: RoundedRectangle(cornerRadius: 18))
                    FlowLike(tokens: tokens, chosen: chosen) { tokenIndex in
                        if !chosen.contains(tokenIndex) { chosen.append(tokenIndex) }
                    }
                    if !chosen.isEmpty {
                        Button("Clear") { chosen.removeAll() }.font(.caption)
                    }
                default:
                    TextField("Your answer", text: $answer)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .padding(14).background(.white, in: RoundedRectangle(cornerRadius: 18))
                        .accessibilityIdentifier("exercise-answer")
                }
                Button {
                    let attempt: String
                    switch exercise.kind {
                    case ExerciseEngine.choice: attempt = selected.flatMap { exercise.options[$0] } ?? ""
                    case ExerciseEngine.order: attempt = chosen.map { tokens[$0] }.joined(separator: " ")
                    default: attempt = answer
                    }
                    verdict = ExerciseEngine.isCorrect(exercise: exercise, attempt: attempt)
                    if verdict == true { correctCount += 1 } else { coordinator.store.recordExerciseMistake(lessonID: lesson.id, prompt: exercise.prompt) }
                } label: {
                    Text("Check").font(.headline).frame(maxWidth: .infinity).padding(16)
                        .background(AppColor.orange, in: Capsule())
                }.buttonStyle(.plain).disabled(!canCheck(exercise))
                    .accessibilityIdentifier("exercise-check")
            } else {
                Text(verdict == true ? "Correct!" : "Not yet — here is why:")
                    .font(.title3.weight(.semibold)).foregroundStyle(verdict == true ? AppColor.secondary : .red)
                Text(exercise.explain).font(.subheadline).foregroundStyle(AppColor.secondary)
                Button {
                    if index + 1 >= lesson.exercises.count {
                        let percent = correctCount * 100 / lesson.exercises.count
                        coordinator.store.recordCompletion(kind: ProgressEngine.exerciseSet, itemID: lesson.id, bestScore: percent)
                    }
                    index += 1; answer = ""; selected = nil; verdict = nil; prepare()
                } label: {
                    Text("Next").font(.headline).frame(maxWidth: .infinity).padding(16)
                        .background(AppColor.orange, in: Capsule())
                }.buttonStyle(.plain)
                    .accessibilityIdentifier("exercise-next")
            }
        }
    }

    private func canCheck(_ exercise: Exercise) -> Bool {
        switch exercise.kind {
        case ExerciseEngine.choice: return selected != nil
        case ExerciseEngine.order: return !tokens.isEmpty && chosen.count == tokens.count
        default: return !answer.trimmingCharacters(in: .whitespaces).isEmpty
        }
    }

    private func prepare() {
        guard let exercise else { return }
        if exercise.kind == ExerciseEngine.order {
            tokens = (exercise.answers.first ?? "").split(separator: " ").map(String.init).shuffled()
        } else {
            tokens = []
        }
        chosen = []
    }
}

/// Simple wrap-avoiding token row: order exercises have short sentences, so a
/// wrapped HStack is enough without bringing in a flow layout.
private struct FlowLike: View {
    let tokens: [String]
    let chosen: [Int]
    let tap: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(stride(from: 0, to: tokens.count, by: 3)), id: \.self) { start in
                HStack(spacing: 8) {
                    ForEach(start..<min(start + 3, tokens.count), id: \.self) { tokenIndex in
                        Button { tap(tokenIndex) } label: {
                            Text(tokens[tokenIndex]).font(.subheadline.weight(.semibold))
                                .padding(12).background(AppColor.panels.first ?? AppColor.cream,
                                    in: RoundedRectangle(cornerRadius: 14))
                                .opacity(chosen.contains(tokenIndex) ? 0.35 : 1)
                        }.buttonStyle(.plain).disabled(chosen.contains(tokenIndex))
                    }
                }
            }
        }
    }
}

/// Local study-card synthesis, independent of the conversation engine.
/// Without a voice for the course locale the card simply stays silent.
@Observable final class LessonReader {
    private let synthesizer = AVSpeechSynthesizer()

    func read(_ text: String, locale: String, rate: Float = 1) {
        stop()
        guard AVSpeechSynthesisVoice(language: locale) != nil else { return }
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: locale)
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate * rate
        synthesizer.speak(utterance)
    }

    func stop() {
        synthesizer.stopSpeaking(at: .immediate)
    }
}
