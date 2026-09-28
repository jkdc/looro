import SwiftUI
import LearningCore

/// The Listening tab: authored texts read aloud by the local voice, answered in
/// writing. The transcript stays hidden while answering and is revealed at the
/// end; finishing records one completion with the score.
struct ListeningView: View {
    let coordinator: ConversationCoordinator
    @State private var openTextID: String?

    private var texts: [ListeningText] { ListeningRegistry.forLanguage(coordinator.language.id) }

    var body: some View {
        if let id = openTextID, let item = ListeningRegistry.text(id: id) {
            ListeningDetailView(coordinator: coordinator, item: item, onBack: { openTextID = nil })
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    PageHeading(eyebrow: "Your ears", title: "Listen and answer",
                                subtitle: "Hear a short text, then answer the questions in writing.")
                    ForEach(texts) { text in
                        Button { openTextID = text.id } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "headphones")
                                    .font(.system(size: 24, weight: .light)).foregroundStyle(AppColor.secondary)
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(text.title).font(.subheadline.weight(.semibold))
                                    Text("\(text.level) · \(text.questions.count) questions")
                                        .font(.caption).foregroundStyle(AppColor.secondary)
                                }
                                Spacer()
                                if LevelEngine.medal(bestScore: bestScore(for: text.id)) >= 1 {
                                    Image(systemName: "star.fill").font(.caption2).foregroundStyle(AppColor.secondary)
                                }
                                Text(bestLabel(text.id)).font(.caption2).foregroundStyle(AppColor.secondary)
                            }.padding(22).background(.white.opacity(0.8), in: RoundedRectangle(cornerRadius: 26))
                        }.buttonStyle(.plain).accessibilityIdentifier("listening-" + text.id)
                    }
                }.padding(.horizontal, 24).padding(.top, 12).padding(.bottom, 110)
            }.scrollIndicators(.hidden)
        }
    }

    private func bestScore(for id: String) -> Int? {
        (coordinator.store.archive.completions ?? [])
            .filter { $0.kind == ProgressEngine.listening && $0.itemID == id }
            .map(\.bestScore).max()
    }

    private func bestLabel(_ id: String) -> String {
        bestScore(for: id).map { "Best \($0)%" } ?? "New"
    }
}

private struct ListeningDetailView: View {
    let coordinator: ConversationCoordinator
    let item: ListeningText
    let onBack: () -> Void
    @State private var phase = 0
    @State private var index = 0
    @State private var answer = ""
    @State private var selected: Int?
    @State private var verdict: Bool?
    @State private var correctCount = 0
    @State private var showTranscript = false
    @State private var recorded = false
    private let reader = LessonReader()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Button { reader.stop(); onBack() } label: {
                        Image(systemName: "chevron.backward").font(.headline.weight(.semibold))
                            .padding(12).background(.white.opacity(0.8), in: Circle())
                    }.buttonStyle(.plain).accessibilityLabel("Back to the listening list")
                    Spacer()
                    Text(item.title).font(.caption).foregroundStyle(AppColor.secondary)
                }
                Text(item.title).font(.largeTitle.weight(.semibold))
                Text("\(item.level) · \(item.questions.count) questions")
                    .font(.caption).foregroundStyle(AppColor.secondary)
                if phase == 0 {
                    Text("Listen as many times as you need. The text stays hidden while you answer — try to understand it with your ears first.")
                        .font(.body)
                    Button {
                        reader.read(item.text, locale: coordinator.language.locale,
                                    rate: Float(coordinator.store.preferences.effectiveSpeechRate()))
                    } label: {
                        Text("Play the text").font(.headline)
                            .frame(maxWidth: .infinity).padding(18)
                            .background(AppColor.orange, in: Capsule())
                    }.buttonStyle(.plain).accessibilityIdentifier("listening-play")
                    Button { reader.stop() } label: {
                        Text("Stop").font(.subheadline).frame(maxWidth: .infinity).padding(14)
                    }.buttonStyle(.bordered).tint(AppColor.secondary).accessibilityIdentifier("listening-stop")
                    Button { reader.stop(); phase = 1 } label: {
                        Text("I'm ready — show the questions").font(.headline)
                            .frame(maxWidth: .infinity).padding(16)
                            .background(AppColor.secondary, in: Capsule())
                            .foregroundStyle(AppColor.cream)
                    }.buttonStyle(.plain).accessibilityIdentifier("listening-questions")
                } else if index >= item.questions.count {
                    summary
                } else {
                    let question = item.questions[index]
                    Text("Question \(index + 1) of \(item.questions.count)")
                        .font(.caption).foregroundStyle(AppColor.secondary)
                    questionCard(question)
                }
            }.padding(.horizontal, 24).padding(.top, 14).padding(.bottom, 110)
        }.scrollIndicators(.hidden)
    }

    @ViewBuilder
    private var summary: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("You answered \(correctCount) of \(item.questions.count) correctly.")
                .font(.title2.weight(.semibold))
            Toggle("Show the transcript", isOn: $showTranscript).font(.subheadline)
            if showTranscript { Text(item.text).font(.body) }
            Button { reader.stop(); onBack() } label: {
                Text("Finish").font(.headline).frame(maxWidth: .infinity).padding(18)
                    .background(AppColor.orange, in: Capsule())
            }.buttonStyle(.plain).accessibilityIdentifier("listening-finish")
        }
    }

    @ViewBuilder
    private func questionCard(_ question: Exercise) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(question.prompt).font(.title3.weight(.medium))
            if verdict == nil {
                switch question.kind {
                case ExerciseEngine.choice:
                    ForEach(Array(question.options.enumerated()), id: \.offset) { optionIndex, option in
                        Button { selected = optionIndex } label: {
                            Text(option).font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity, alignment: .leading).padding(16)
                                .background(selected == optionIndex ? AppColor.sage : AppColor.cream,
                                    in: RoundedRectangle(cornerRadius: 18))
                        }.buttonStyle(.plain)
                    }
                default:
                    TextField("Your answer", text: $answer)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .padding(14).background(.white, in: RoundedRectangle(cornerRadius: 18))
                        .accessibilityIdentifier("listening-answer")
                }
                Button {
                    let attempt: String
                    switch question.kind {
                    case ExerciseEngine.choice: attempt = selected.flatMap { question.options[$0] } ?? ""
                    default: attempt = answer
                    }
                    verdict = ExerciseEngine.isCorrect(exercise: question, attempt: attempt)
                    if verdict == true { correctCount += 1 }
                } label: {
                    Text("Check").font(.headline).frame(maxWidth: .infinity).padding(16)
                        .background(AppColor.orange, in: Capsule())
                }.buttonStyle(.plain)
                    .disabled(question.kind == ExerciseEngine.choice ? selected == nil
                        : answer.trimmingCharacters(in: .whitespaces).isEmpty)
                    .accessibilityIdentifier("listening-check")
            } else {
                Text(verdict == true ? "Correct!" : "Not yet — here is why:")
                    .font(.title3.weight(.semibold)).foregroundStyle(verdict == true ? AppColor.secondary : .red)
                Text(question.explain).font(.subheadline).foregroundStyle(AppColor.secondary)
                Button {
                    if index + 1 >= item.questions.count && !recorded {
                        recorded = true
                        let percent = correctCount * 100 / item.questions.count
                        coordinator.store.recordCompletion(kind: ProgressEngine.listening, itemID: item.id, bestScore: percent)
                    }
                    index += 1; answer = ""; selected = nil; verdict = nil
                } label: {
                    Text("Next").font(.headline).frame(maxWidth: .infinity).padding(16)
                        .background(AppColor.orange, in: Capsule())
                }.buttonStyle(.plain)
            }
        }
    }
}
