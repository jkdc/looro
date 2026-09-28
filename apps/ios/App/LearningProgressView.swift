import SwiftUI
import LearningCore

/// Read-only practice summary, projected from the local archive each time it opens.
struct LearningProgressView: View {
    let store: LearningStore
    private var stats: LearningStats { LearningEngine.stats(sessions: store.learningSessions, learner: store.learner) }
    private var progress: ProgressStats { store.progressStats }
    private var level: LevelStats? {
        guard let course = CourseRegistry.forLanguage(store.language.id).first else { return nil }
        return LevelEngine.stats(course: course, lessons: store.archive.lessons ?? [],
                                 completions: store.archive.completions ?? [])
    }
    private var medals: Int {
        ListeningRegistry.forLanguage(store.language.id).filter { text in
            let best = (store.archive.completions ?? [])
                .filter { $0.kind == ProgressEngine.listening && $0.itemID == text.id &&
                          ($0.languageID == nil || $0.languageID == store.language.id) }.map(\.bestScore).max()
            return LevelEngine.medal(bestScore: best) >= 1
        }.count
    }

    var body: some View {
        Form {
            if stats.totalSessions == 0 && progress.lessonsDone == 0 && progress.last30DaysActivities.allSatisfy({ $0 == 0 }) {
                Section {
                    Text("Start a conversation to see your progress.")
                        .foregroundStyle(AppColor.secondary)
                }
            } else {
                Section {
                    row("Conversations", "\(stats.totalSessions)")
                    row("Practice time", timeText)
                    row("Day streak", "\(stats.activeStreakDays)")
                    row("Longest streak", "\(stats.longestStreakDays)")
                } header: { Text("Practice") }
                if progress.lessonsTotal > 0 || progress.activeStreakDays > 0 {
                    Section {
                        if let level, progress.lessonsTotal > 0 {
                            row("Level", "Level \(level.level) · \(levelTitle(level.level))")
                        }
                        if progress.lessonsTotal > 0 { row("Lessons completed", "\(progress.lessonsDone)/\(progress.lessonsTotal)") }
                        if progress.themesPractised > 0 { row("Conversation themes", "\(progress.themesPractised)") }
                        if progress.exercisesCompleted > 0 { row("Exercises completed", "\(progress.exercisesCompleted)") }
                        if progress.listeningCompleted > 0 { row("Listening texts", "\(progress.listeningCompleted)") }
                        if medals > 0 { row("Listening medals", "\(medals)") }
                        row("Activity streak", "\(progress.activeStreakDays)")
                    } header: { Text("Course progress") }
                }
                Section {
                    ForEach(0..<4, id: \.self) { bars in
                        row(["New", "Fragile", "Growing", "Steady"][bars], "\(stats.wordsByBars[bars])")
                    }
                } header: { Text("Words by recall") } footer: { Text("Recall bars summarise repeated retrieval over time; three bars need spaced evidence across days and contexts.") }
                Section {
                    HStack(alignment: .bottom, spacing: 4) {
                        let peak = max(stats.last14DaysMinutes.max() ?? 1, 1)
                        ForEach(stats.last14DaysMinutes.indices, id: \.self) { index in
                            RoundedRectangle(cornerRadius: 3)
                                .fill(Color.accentColor.opacity(index == stats.last14DaysMinutes.count - 1 ? 1 : 0.35))
                                .frame(height: max(6, 52 * stats.last14DaysMinutes[index] / peak))
                                .frame(maxHeight: 58, alignment: .bottom)
                        }
                    }
                    .frame(height: 64)
                } header: { Text("Last 14 days") }
                if progress.last30DaysActivities.contains(where: { $0 > 0 }) {
                    Section {
                        HStack(alignment: .bottom, spacing: 2) {
                            let peak = max(progress.last30DaysActivities.max() ?? 1, 1)
                            ForEach(progress.last30DaysActivities.indices, id: \.self) { index in
                                RoundedRectangle(cornerRadius: 2)
                                    .fill(Color.accentColor.opacity(index == progress.last30DaysActivities.count - 1 ? 1 : 0.35))
                                    .frame(height: max(6, 52 * progress.last30DaysActivities[index] / peak))
                                    .frame(maxHeight: 58, alignment: .bottom)
                            }
                        }
                        .frame(height: 64)
                    } header: { Text("Last 30 days") }
                }
                if stats.capabilityCount > 0 {
                    Section("Can-do milestones: \(stats.capabilityCount)") {
                        if let latest = stats.latestCapability { Text(latest) }
                    }
                }
                Section {
                    Text("Totals and streaks span every language you practise. Words and milestones are for the current language.")
                        .font(.footnote).foregroundStyle(AppColor.secondary)
                }
            }
        }
        .navigationTitle("Your progress")
    }

    private var timeText: String {
        stats.totalVoiceMinutes >= 60 ? "\(stats.totalVoiceMinutes / 60) h \(stats.totalVoiceMinutes % 60) min" : "\(stats.totalVoiceMinutes) min"
    }

    private func row(_ label: String, _ value: String) -> some View {
        LabeledContent(label, value: value)
    }
}
