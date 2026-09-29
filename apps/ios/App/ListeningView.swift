import SwiftUI
import LearningCore

/// The Listen tab: a small library with two shelves. Listen = authored texts read
/// aloud by the local voice, answered in writing with the transcript hidden until
/// the end. Read = the reading skill's texts, visible while answering. Both
/// finish with one completion mark carrying the score.
struct ListeningView: View {
    let coordinator: ConversationCoordinator
    @State private var shelf = "listen"
    @State private var openTextID: String?
    @State private var openReadingID: String?

    private var texts: [ListeningText] { ListeningRegistry.forLanguage(coordinator.language.id) }
    private var readings: [ReadingText] { ReadingRegistry.forLanguage(coordinator.language.id) }
    private var unitOrder: [CourseUnit] {
        CourseRegistry.all.first { $0.languageID == coordinator.language.id }?.units ?? []
    }
    /// Consecutive runs of the sorted list — one section per level.
    private func sections<T: Identifiable & Hashable>(_ items: [T], level: (T) -> String) -> [(level: String, texts: [T])] {
        items.reduce(into: []) { result, item in
            if let last = result.last, last.level == level(item) {
                result[result.count - 1].texts.append(item)
            } else {
                result.append((level(item), [item]))
            }
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            shelfPicker
            if let id = openReadingID, let item = ReadingRegistry.text(id: id) {
                ReadingDetailView(coordinator: coordinator, item: item, unitOrder: unitOrder,
                                  onBack: { openReadingID = nil })
            } else if let id = openTextID, let item = ListeningRegistry.text(id: id) {
                ListeningDetailView(coordinator: coordinator, item: item, unitOrder: unitOrder,
                                    onBack: { openTextID = nil })
            } else if shelf == "read" {
                readingLibrary
            } else {
                listeningLibrary
            }
        }
    }

    private var shelfPicker: some View {
        HStack(spacing: 8) {
            shelfChip("Listen", selected: shelf == "listen") { shelf = "listen" }
            shelfChip("Read", selected: shelf == "read") { shelf = "read" }
        }.padding(.horizontal, 24).padding(.vertical, 10)
    }

    private func shelfChip(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).font(.subheadline.weight(selected ? .semibold : .regular))
                .frame(maxWidth: .infinity).padding(.vertical, 10)
                .background(selected ? AppColor.secondary : .white.opacity(0.8), in: Capsule())
                .foregroundStyle(selected ? AppColor.cream : AppColor.secondary)
        }.buttonStyle(.plain)
    }

    private var listeningLibrary: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                PageHeading(eyebrow: "Your ears", title: "Listen and answer",
                            subtitle: "Hear a short text, then answer the questions in writing.")
                ForEach(sections(texts, level: \.level), id: \.level) { section in
                    Text(section.level)
                        .font(.subheadline.weight(.semibold)).foregroundStyle(AppColor.secondary)
                        .padding(.top, 8)
                    ForEach(section.texts) { text in
                        Button { openTextID = text.id } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "headphones")
                                    .font(.system(size: 24, weight: .light)).foregroundStyle(AppColor.secondary)
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(text.title).font(.subheadline.weight(.semibold))
                                    Text(shelfSubtitle(unitRank: text.unitID.flatMap { id in unitOrder.firstIndex(where: { $0.id == id }) },
                                                       unitTitle: text.unitID.flatMap { id in unitOrder.first(where: { $0.id == id })?.title },
                                                       level: text.level, topic: text.topic, count: text.questions.count))
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
                }
            }.padding(.horizontal, 24).padding(.top, 4).padding(.bottom, 110)
        }.scrollIndicators(.hidden)
    }

    private var readingLibrary: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                PageHeading(eyebrow: "Your eyes", title: "Read and answer",
                            subtitle: "The text stays visible — read it as many times as you need and answer the questions.")
                ForEach(sections(readings, level: \.level), id: \.level) { section in
                    Text(section.level)
                        .font(.subheadline.weight(.semibold)).foregroundStyle(AppColor.secondary)
                        .padding(.top, 8)
                    ForEach(section.texts) { text in
                        Button { openReadingID = text.id } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "book")
                                    .font(.system(size: 24, weight: .light)).foregroundStyle(AppColor.secondary)
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(text.title).font(.subheadline.weight(.semibold))
                                    Text(shelfSubtitle(unitRank: text.unitID.flatMap { id in unitOrder.firstIndex(where: { $0.id == id }) },
                                                       unitTitle: text.unitID.flatMap { id in unitOrder.first(where: { $0.id == id })?.title },
                                                       level: text.level, topic: text.topic, count: text.questions.count))
                                        .font(.caption).foregroundStyle(AppColor.secondary)
                                }
                                Spacer()
                                Text(bestLabel(readScore(for: text.id))).font(.caption2).foregroundStyle(AppColor.secondary)
                            }.padding(22).background(.white.opacity(0.8), in: RoundedRectangle(cornerRadius: 26))
                        }.buttonStyle(.plain).accessibilityIdentifier("reading-" + text.id)
                    }
                }
            }.padding(.horizontal, 24).padding(.top, 4).padding(.bottom, 110)
        }.scrollIndicators(.hidden)
    }

    /// "Theme 3 · <unit title> · N questions" for unit-linked texts;
    /// "A2 · <topic> · N questions" for topic-only extras.
    private func shelfSubtitle(unitRank: Int?, unitTitle: String?, level: String, topic: String, count: Int) -> String {
        if let unitRank, let unitTitle { return "Theme \(unitRank + 1) · \(unitTitle) · \(count) questions" }
        return "\(level) · \(topic) · \(count) questions"
    }

    private func bestScore(for id: String) -> Int? {
        (coordinator.store.archive.completions ?? [])
            .filter { $0.kind == ProgressEngine.listening && $0.itemID == id }
            .map(\.bestScore).max()
    }

    private func readScore(for id: String) -> Int? {
        (coordinator.store.archive.completions ?? [])
            .filter { $0.kind == ProgressEngine.reading && $0.itemID == id }
            .map(\.bestScore).max()
    }

    private func bestLabel(_ score: Int?) -> String {
        score.map { "Best \($0)%" } ?? "New"
    }
}

/// Reading keeps the text visible the whole time — comprehension, not memory.
private struct ReadingDetailView: View {
    let coordinator: ConversationCoordinator
    let item: ReadingText
    let unitOrder: [CourseUnit]
    let onBack: () -> Void
    @State private var index = 0
    @State private var answer = ""
    @State private var selected: Int?
    @State private var verdict: Bool?
    @State private var correctCount = 0
    @State private var recorded = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Button(action: onBack) {
                        Image(systemName: "chevron.backward").font(.headline.weight(.semibold))
                            .padding(12).background(.white.opacity(0.8), in: Circle())
                    }.buttonStyle(.plain).accessibilityLabel("Back to the reading list")
                    Spacer()
                    Text(item.title).font(.caption).foregroundStyle(AppColor.secondary)
                }
                Text(item.title).font(.largeTitle.weight(.semibold))
                Text(subtitle).font(.caption).foregroundStyle(AppColor.secondary)
                Text(item.text).font(.body)
                if item.questions.isEmpty {
                    EmptyView()
                } else if index >= item.questions.count {
                    VStack(alignment: .leading, spacing: 14) {
                        Text("You answered \(correctCount) of \(item.questions.count) correctly.")
                            .font(.title2.weight(.semibold))
                        Button { onBack() } label: {
                            Text("Finish").font(.headline).frame(maxWidth: .infinity).padding(18)
                                .background(AppColor.orange, in: Capsule())
                        }.buttonStyle(.plain).accessibilityIdentifier("reading-finish")
                    }
                } else {
                    let question = item.questions[index]
                    Text("Question \(index + 1) of \(item.questions.count)")
                        .font(.caption).foregroundStyle(AppColor.secondary)
                    questionCard(question)
                }
            }.padding(.horizontal, 24).padding(.top, 14).padding(.bottom, 110)
        }.scrollIndicators(.hidden)
    }

    private var subtitle: String {
        if let unitID = item.unitID, let rank = unitOrder.firstIndex(where: { $0.id == unitID }) {
            return "Theme \(rank + 1) · \(unitOrder[rank].title) · \(item.level) · \(item.questions.count) questions"
        }
        return "\(item.level) · \(item.topic) · \(item.questions.count) questions"
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
                        .accessibilityIdentifier("reading-answer")
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
                    .accessibilityIdentifier("reading-check")
            } else {
                Text(verdict == true ? "Correct!" : "Not yet — here is why:")
                    .font(.title3.weight(.semibold)).foregroundStyle(verdict == true ? AppColor.secondary : .red)
                Text(question.explain).font(.subheadline).foregroundStyle(AppColor.secondary)
                Button {
                    if index + 1 >= item.questions.count && !recorded {
                        recorded = true
                        let percent = correctCount * 100 / item.questions.count
                        coordinator.store.recordCompletion(kind: ProgressEngine.reading, itemID: item.id, bestScore: percent)
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

/// The listening flow: play the authored text aloud (local voice), answer with
/// the transcript hidden, reveal at the end; one completion mark with the score.
private struct ListeningDetailView: View {
    let coordinator: ConversationCoordinator
    let item: ListeningText
    let unitOrder: [CourseUnit]
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
                Text(subtitle).font(.caption).foregroundStyle(AppColor.secondary)
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
                } else {
                    let question = item.questions[index]
                    Text("Question \(index + 1) of \(item.questions.count)")
                        .font(.caption).foregroundStyle(AppColor.secondary)
                    questionCard(question)
                }
            }.padding(.horizontal, 24).padding(.top, 14).padding(.bottom, 110)
        }.scrollIndicators(.hidden)
    }

    private var subtitle: String {
        if let unitID = item.unitID, let rank = unitOrder.firstIndex(where: { $0.id == unitID }) {
            return "Theme \(rank + 1) · \(unitOrder[rank].title) · \(item.level) · \(item.questions.count) questions"
        }
        return "\(item.level) · \(item.topic) · \(item.questions.count) questions"
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
