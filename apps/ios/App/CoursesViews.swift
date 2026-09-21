import SwiftUI
import AVFoundation
import LooroCore

/// Learn tab: the course for the current language, its lessons and focused themes.
/// Read-aloud uses local speech synthesis; a missing course-language voice degrades
/// to silent reading. Practice routes through the coordinator's consent flow.
struct CoursesView: View {
    let coordinator: ConversationCoordinator
    var onThemeChosen: () -> Void
    @State private var openLessonID: String?
    @State private var audioOn = true
    @State private var reader = LessonReader()

    var body: some View {
        let courses = CourseRegistry.forLanguage(coordinator.language.id)
        Group {
            if let id = openLessonID, let lesson = CourseRegistry.lesson(id: id) {
                LessonDetailView(coordinator: coordinator, lesson: lesson, audioOn: $audioOn, reader: reader,
                    onBack: { openLessonID = nil; reader.stop() },
                    onPractice: { id in
                        reader.stop(); coordinator.selectLesson(id); coordinator.start(); onThemeChosen()
                    },
                    onTheme: { theme in
                        reader.stop(); coordinator.chooseTheme(theme); onThemeChosen()
                    })
            } else if courses.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("No courses yet").font(.headline)
                    Text("Courses for this language are on the way. Meanwhile, Themes is the place to practise.")
                        .font(.subheadline).foregroundStyle(LooroColor.secondary)
                }.padding(24)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 24) {
                        PageHeading(eyebrow: "Your course", title: "Learn it step by step",
                                    subtitle: "Read a short lesson, then practise it in a real conversation.")
                        ForEach(courses) { course in
                            VStack(alignment: .leading, spacing: 14) {
                                HStack(spacing: 12) {
                                    Image(systemName: "graduationcap")
                                        .font(.system(size: 26, weight: .light)).foregroundStyle(LooroColor.secondary)
                                    VStack(alignment: .leading) {
                                        Text(course.title).font(.headline)
                                        Text(course.subtitle).font(.caption).foregroundStyle(LooroColor.secondary)
                                    }
                                }
                                Text(course.description).font(.subheadline)
                                ForEach(course.units) { unit in
                                    UnitSection(coordinator: coordinator, unit: unit) { openLessonID = $0; reader.stop() }
                                }
                            }.padding(22)
                                .background(.white.opacity(0.8), in: RoundedRectangle(cornerRadius: 26))
                        }
                    }.padding(.horizontal, 24).padding(.bottom, 110)
                }.scrollIndicators(.hidden)
            }
        }.foregroundStyle(LooroColor.ink)
    }
}

private struct UnitSection: View {
    let coordinator: ConversationCoordinator
    let unit: CourseUnit
    let open: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(unit.title).font(.subheadline.weight(.semibold))
            ForEach(unit.lessons) { lesson in
                Button { open(lesson.id) } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(lesson.title).font(.subheadline.weight(.semibold))
                            Text(lesson.goal).font(.caption).foregroundStyle(LooroColor.secondary)
                        }
                        Spacer()
                        Text(attemptLabel(lesson.id)).font(.caption2).foregroundStyle(LooroColor.secondary)
                    }.padding(16).background(LooroColor.cream, in: RoundedRectangle(cornerRadius: 18))
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
                    Toggle(isOn: $audioOn) { Text("Read aloud").font(.caption).foregroundStyle(LooroColor.secondary) }
                        .toggleStyle(.switch).labelsHidden()
                        .accessibilityLabel("Read aloud")
                        .onChange(of: audioOn) { _, on in if !on { reader.stop() } }
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text(lesson.title).font(.largeTitle.weight(.semibold))
                    Text(lesson.goal).font(.subheadline).foregroundStyle(LooroColor.secondary)
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
                            Text(example.meaning).font(.caption).foregroundStyle(LooroColor.secondary)
                        }
                    }
                }
                sectionHeading("Target expressions")
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(lesson.expressions.enumerated()), id: \.offset) { _, expression in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(expression.text + "  —  " + expression.meaning).font(.subheadline.weight(.semibold))
                            Text(expression.note).font(.caption).foregroundStyle(LooroColor.secondary)
                        }
                    }
                }
                sectionHeading("Practice situation")
                Text(lesson.situation).font(.body)
                if let unit {
                    sectionHeading("Extra conversation themes")
                    ScrollView(.horizontal) {
                        HStack(spacing: 10) {
                            ForEach(unit.themes) { theme in
                                Button { onTheme(theme) } label: {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(theme.title).font(.subheadline.weight(.semibold))
                                        Text(theme.subtitle).font(.caption)
                                    }.padding(14).background(LooroColor.panels[theme.colorIndex],
                                        in: RoundedRectangle(cornerRadius: 20))
                                }.buttonStyle(.plain)
                            }
                        }.scrollIndicators(.hidden)
                    }
                }
                Button { onPractice(lesson.id) } label: {
                    Text("Practise speaking").font(.headline).frame(maxWidth: .infinity).padding(18)
                        .background(LooroColor.orange, in: Capsule())
                }.buttonStyle(.plain).padding(.top, 6)
            }.padding(.horizontal, 24).padding(.top, 14).padding(.bottom, 110)
        }.scrollIndicators(.hidden)
        .onAppear { if audioOn { read() } }
        .onChange(of: audioOn) { _, on in if on { read() } }
    }

    private func read() {
        reader.read(lesson.title + "   " + lesson.intro.explanation.joined(separator: "   ")
            + "   " + lesson.intro.examples.map(\.text).joined(separator: "   "),
            locale: coordinator.language.locale)
    }

    private func sectionHeading(_ text: String) -> some View {
        Text(text).font(.subheadline.weight(.semibold)).foregroundStyle(LooroColor.secondary)
    }
}

/// Local study-card synthesis, independent of the conversation engine.
/// Without a voice for the course locale the card simply stays silent.
@Observable final class LessonReader {
    private let synthesizer = AVSpeechSynthesizer()

    func read(_ text: String, locale: String) {
        stop()
        guard AVSpeechSynthesisVoice(language: locale) != nil else { return }
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: locale)
        synthesizer.speak(utterance)
    }

    func stop() {
        synthesizer.stopSpeaking(at: .immediate)
    }
}
