import SwiftUI
import LearningCore

/// The Learn-tab recall card: visible only when expressions are due (mirrors
/// Android LearnScreen's "recall-card", hidden when nothing is due).
struct RecallCardView: View {
    let count: Int
    let open: () -> Void
    var body: some View {
        Button { open() } label: {
            HStack(spacing: 12) {
                Image(systemName: "book").font(.system(size: 26, weight: .light)).foregroundStyle(AppColor.secondary)
                VStack(alignment: .leading, spacing: 3) {
                    Text("Recall").font(.headline)
                    Text("\(count) expressions ready for review").font(.caption).foregroundStyle(AppColor.secondary)
                }
                Spacer(minLength: 0)
            }.padding(22).frame(maxWidth: .infinity, alignment: .leading)
                .background(AppColor.sage, in: RoundedRectangle(cornerRadius: 26))
        }.buttonStyle(.plain).accessibilityIdentifier("recall-card")
    }
}

/// The recall session: typed recall v1 over the due expression queue (mirror
/// of Android's RecallScreen). Grading is self-evident — the answer is
/// compared to the saved expression text (case/whitespace-insensitive) — and
/// schedules the ladder through RecallEngine. A finished round records one
/// recall-round completion; the empty state itself stays event-free.
struct RecallSessionView: View {
    let store: LearningStore
    let onBack: () -> Void
    @State private var queue: [ExpressionState] = []
    @State private var index = 0
    @State private var answer = ""
    @State private var correct: Bool?
    @State private var correctCount = 0

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Button { onBack() } label: {
                        Image(systemName: "chevron.backward").font(.headline.weight(.semibold))
                            .padding(12).background(.white.opacity(0.8), in: Circle())
                    }.buttonStyle(.plain).accessibilityLabel("Back to lessons")
                    Spacer()
                    Text("Recall").font(.headline)
                }
                if index >= queue.count {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Nothing due right now.").font(.headline)
                        Text("Expressions you save come back here when it matters.")
                            .font(.subheadline).foregroundStyle(AppColor.secondary)
                    }.padding(.top, 40)
                } else {
                    let item = queue[index]
                    Text("\(index + 1) of \(queue.count)").font(.caption).foregroundStyle(AppColor.secondary)
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Recall the expression for this meaning")
                            .font(.subheadline).foregroundStyle(AppColor.secondary)
                        Text(item.meaning).font(.title2.weight(.medium))
                    }
                    TextField("Your answer", text: $answer)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .padding(14).background(.white, in: RoundedRectangle(cornerRadius: 18))
                        .accessibilityIdentifier("recall-answer")
                    if let correct {
                        VStack(alignment: .leading, spacing: 10) {
                            VStack(alignment: .leading, spacing: 6) {
                                Text(correct ? String(localized: "Correct!") : String(localized: "It was: \(item.text)"))
                                    .font(.subheadline.weight(.semibold))
                                Text(item.meaning).font(.caption).foregroundStyle(AppColor.secondary)
                            }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
                                .background(AppColor.sage, in: RoundedRectangle(cornerRadius: 18))
                            Button { advance() } label: {
                                Text("Continue").font(.headline).frame(maxWidth: .infinity).padding(16)
                                    .background(AppColor.orange, in: Capsule())
                            }.buttonStyle(.plain).accessibilityIdentifier("recall-continue")
                        }.accessibilityIdentifier("recall-verdict")
                    } else {
                        Button {
                            let ok = RecallEngine.normalizeKey(answer) == RecallEngine.normalizeKey(item.text)
                            store.gradeRecall(text: item.text, correct: ok)
                            correct = ok
                            if ok { correctCount += 1 }
                        } label: {
                            Text("Check").font(.headline).frame(maxWidth: .infinity).padding(16)
                                .background(AppColor.orange, in: Capsule())
                        }.buttonStyle(.plain).disabled(answer.trimmingCharacters(in: .whitespaces).isEmpty)
                            .accessibilityIdentifier("recall-check")
                    }
                }
            }.padding(.horizontal, 24).padding(.top, 14).padding(.bottom, 110)
        }.scrollIndicators(.hidden)
            .onAppear {
                guard queue.isEmpty else { return }
                queue = RecallEngine.due(store.archive, now: Date.now.timeIntervalSinceReferenceDate)
            }
            .accessibilityIdentifier("recall-screen")
    }

    /// Advance past the graded item; the queue end reports the finished round once.
    private func advance() {
        index += 1; answer = ""; correct = nil
        if index >= queue.count {
            store.recordCompletion(kind: ProgressEngine.recallRound, itemID: "recall")
        }
    }
}
