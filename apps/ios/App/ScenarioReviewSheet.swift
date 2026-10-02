import SwiftUI
import LearningCore

/// The sheet identifies its presentation by the session that produced the review,
/// so a fresh attempt (new session id) presents fresh and the Preparing → Ready
/// transition re-renders in place.
extension ScenarioReviewState: Identifiable {
    var id: UUID { sessionID }
}

/// Muted brick red for the "you said" marker (Android AppColors.Red).
private let reviewErrorRed = Color(red: 0.702, green: 0.294, blue: 0.247)

/// Post-session review of a situation practice: verified errors ("you said →
/// better"), strengths, savable key phrases and a focus for the next attempt.
/// Mirror of Android's `ui/ScenarioReviewSheet.kt`; the three states come from
/// `ConversationCoordinator.scenarioReviewState`.
struct ScenarioReviewSheet: View {
    let state: ScenarioReviewState
    let coordinator: ConversationCoordinator
    /// Runs the coordinator's practiseAgain() and returns the app to the talk tab.
    let onPractiseAgain: () -> Void
    @State private var savedPhrases: Set<String> = []

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    switch state {
                    case .preparing(_, _, _): PreparingReview()
                    case .failed(_, _, _): FailedReview()
                    case .ready(_, _, let scenarioTitle, let review): readyReview(scenarioTitle, review)
                    }
                }.padding(26).frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("scenario-review")
            }.background(AppColor.cream).foregroundStyle(AppColor.ink)
                .navigationTitle("Your review").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { coordinator.dismissScenarioReview() } } }
        }.presentationDetents([.large])
    }

    private func readyReview(_ scenarioTitle: String, _ review: ScenarioReview) -> some View {
        VStack(alignment: .leading, spacing: 22) {
            Text("Situation: \(scenarioTitle)").font(.system(.title2, design: .rounded, weight: .semibold))
            if !review.retryFocus.isEmpty {
                VStack(alignment: .leading, spacing: 6) {
                    Text("For next time").font(.system(.headline, design: .rounded, weight: .semibold))
                    Text(review.retryFocus).font(.subheadline)
                }.padding(16).frame(maxWidth: .infinity, alignment: .leading)
                    .background(AppColor.peach, in: RoundedRectangle(cornerRadius: 20))
            }
            if !review.strengths.isEmpty {
                reviewSection("What went well") {
                    ForEach(Array(review.strengths.enumerated()), id: \.offset) { _, strength in
                        reviewLine("✓", strength, AppColor.sage)
                    }
                }
            }
            if !review.errors.isEmpty {
                reviewSection("To polish") {
                    ForEach(Array(review.errors.enumerated()), id: \.offset) { _, error in
                        VStack(alignment: .leading, spacing: 6) {
                            reviewLine("✗", error.quote, reviewErrorRed, label: "You said")
                            reviewLine("→", error.correction, AppColor.sage, label: "Better")
                            if !error.note.isEmpty { Text(error.note).font(.footnote).foregroundStyle(AppColor.secondary) }
                        }.padding(14).frame(maxWidth: .infinity, alignment: .leading)
                            .background(.white, in: RoundedRectangle(cornerRadius: 18))
                    }
                }
            }
            if !review.keyPhrases.isEmpty {
                reviewSection("Key phrases") {
                    ForEach(Array(review.keyPhrases.enumerated()), id: \.offset) { _, phrase in
                        phraseRow(phrase)
                    }
                }
            }
            VStack(spacing: 10) {
                Button { onPractiseAgain() } label: {
                    Text("Practise again").frame(maxWidth: .infinity)
                }.font(.headline).padding(18).background(AppColor.orange, in: Capsule())
                    .accessibilityIdentifier("scenario-review-again")
                Button("Done") { coordinator.dismissScenarioReview() }.frame(maxWidth: .infinity)
            }.padding(.top, 4)
        }
    }

    private func reviewSection(_ title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.system(.headline, design: .rounded, weight: .semibold))
            content()
        }
    }

    private func reviewLine(_ marker: String, _ text: String, _ tint: Color, label: String? = nil) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 9) {
            Text(marker).font(.system(.headline, design: .rounded)).foregroundStyle(tint)
            VStack(alignment: .leading, spacing: 3) {
                if let label { Text(label).font(.caption2).foregroundStyle(AppColor.secondary) }
                Text(text).font(.body)
            }
        }
    }

    private func phraseRow(_ phrase: ReviewedPhrase) -> some View {
        let saved = savedPhrases.contains(phrase.phrase)
        return HStack(alignment: .center, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(phrase.phrase).font(.system(.headline, design: .rounded))
                Text(phrase.meaning).font(.footnote).foregroundStyle(AppColor.secondary)
            }
            Spacer(minLength: 10)
            Button {
                if coordinator.store.saveLookupExpression(text: phrase.phrase, meaning: phrase.meaning) {
                    savedPhrases.insert(phrase.phrase)
                }
            } label: {
                Text(saved ? "Saved" : "Save").font(.footnote.weight(.medium))
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(saved ? AppColor.peach : AppColor.sage, in: Capsule())
            }.buttonStyle(.plain).disabled(saved)
        }
    }
}

private struct PreparingReview: View {
    var body: some View {
        VStack(spacing: 14) {
            ProgressView().tint(AppColor.secondary)
            Text("Preparing your review…").font(.system(.headline, design: .rounded))
            Text("We are listening back to your practice.").font(.subheadline).foregroundStyle(AppColor.secondary)
        }.frame(maxWidth: .infinity).padding(.vertical, 48)
    }
}

private struct FailedReview: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("The review could not be prepared").font(.system(.title2, design: .rounded, weight: .semibold))
            Text("Your practice is saved. Try again after your next attempt.").foregroundStyle(AppColor.secondary)
        }.padding(.vertical, 12)
    }
}
