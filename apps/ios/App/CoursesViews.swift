import SwiftUI
import AVFoundation
import LearningCore

/// Learn tab: the course for the current language, its lessons and focused themes.
/// Read-aloud uses local speech synthesis; a missing course-language voice degrades
/// to silent reading. Practice routes through the coordinator's consent flow.
struct CoursesView: View {
    let coordinator: ConversationCoordinator
    var onThemeChosen: () -> Void
    @State private var openLessonID: String?
    @State private var exercisesOpen = false
    @State private var audioOn = true
    @State private var reader = LessonReader()

    var body: some View {
        let courses = CourseRegistry.forLanguage(coordinator.language.id)
        Group {
            if let id = openLessonID, let lesson = CourseRegistry.lesson(id: id), exercisesOpen {
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
                        ForEach(courses) { course in
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
                                LevelCardView(level: LevelEngine.stats(course: course,
                                    lessons: coordinator.store.archive.lessons ?? [],
                                    completions: coordinator.store.archive.completions ?? [],
                                    listeningTexts: ListeningRegistry.forLanguage(course.languageID)))
                                ForEach(course.units) { unit in
                                    UnitSection(coordinator: coordinator, unit: unit,
                                        level: LevelEngine.stats(course: course,
                                            lessons: coordinator.store.archive.lessons ?? [],
                                            completions: coordinator.store.archive.completions ?? [],
                                            listeningTexts: ListeningRegistry.forLanguage(course.languageID))) { openLessonID = $0; reader.stop() }
                                }
                            }.padding(22)
                                .background(.white.opacity(0.8), in: RoundedRectangle(cornerRadius: 26))
                        }
                    }.padding(.horizontal, 24).padding(.bottom, 110)
                }.scrollIndicators(.hidden)
            }
        }.foregroundStyle(AppColor.ink)
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
                        Text(attemptLabel(lesson.id)).font(.caption2).foregroundStyle(AppColor.secondary)
                    }.padding(16).background(AppColor.cream, in: RoundedRectangle(cornerRadius: 18))
                }.buttonStyle(.plain)
            }
        }
    }

    private func attemptLabel(_ lessonID: String) -> String {
        let attempts = coordinator.store.archive.lessons?.first(where: { $0.lessonID == lessonID })?.attempts ?? 0
        return attempts == 0 ? "New" : "Practised \(attempts)×"
    }
}

private struct LessonDetailView: View {
    let coordinator: ConversationCoordinator
    let lesson: Lesson
    @Binding var audioOn: Bool
    let reader: LessonReader
    let onBack: () -> Void
    let onPractice: (String) -> Void
    let onTheme: (ConversationTheme) -> Void
    let onExercises: () -> Void

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
