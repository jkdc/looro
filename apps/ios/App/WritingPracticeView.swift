import SwiftUI
import MuralCore

/// Typed drill over due words (up to 10, earliest due first). Attempts are not
/// stored in this change; recall evidence stays conversation-derived.
struct WritingPracticeView: View {
    let store: LearningStore
    @Environment(\.dismiss) private var dismiss
    @State private var due: [WordState] = []
    @State private var index = 0
    @State private var answer = ""
    @State private var missed = false
    @State private var correctCount = 0

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 22) {
                if due.isEmpty {
                    Text("No words are due right now. Have a conversation, and the words will come back when they are ready.")
                        .font(.subheadline).foregroundStyle(MuralColor.secondary)
                } else if index >= due.count {
                    Text("You practised \(due.count) words and wrote \(correctCount) correctly.")
                        .font(.system(.title3, design: .rounded, weight: .medium))
                } else {
                    let word = due[index]
                    Text("Word \(index + 1) of \(due.count)").font(.footnote).foregroundStyle(MuralColor.secondary)
                    Text("Write the word that means:").font(.subheadline)
                    Text(word.meaning).font(.system(.title, design: .rounded, weight: .medium))
                    if !WordPractice.maskedExample(word.example, form: word.form).isEmpty {
                        Text(WordPractice.maskedExample(word.example, form: word.form))
                            .font(.subheadline).foregroundStyle(MuralColor.secondary)
                    }
                    TextField("Your answer", text: $answer)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .padding(14).background(.white, in: RoundedRectangle(cornerRadius: 18))
                        .accessibilityIdentifier("practice-answer")
                    if missed {
                        Text("It is written: \(word.form)").font(.system(.title3, design: .rounded, weight: .medium))
                        Button { advance() } label: {
                            Text("Next word").frame(maxWidth: .infinity)
                        }.buttonStyle(.borderedProminent).tint(MuralColor.orange)
                    } else {
                        Button {
                            if WordPractice.isCorrect(answer: answer, form: word.form, lemma: word.lemma) {
                                correctCount += 1; advance()
                            } else { missed = true }
                        } label: {
                            Text("Check").frame(maxWidth: .infinity)
                        }.buttonStyle(.borderedProminent).tint(MuralColor.orange).disabled(answer.trimmingCharacters(in: .whitespaces).isEmpty)
                            .accessibilityIdentifier("practice-check")
                    }
                }
                Spacer()
            }.padding(28).frame(maxWidth: .infinity, alignment: .leading).background(MuralColor.cream).foregroundStyle(MuralColor.ink)
                .navigationTitle("Writing practice")
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
                .onAppear {
                    guard due.isEmpty else { return }
                    due = Array(store.learner.words.filter { $0.dueAt < .now }.sorted { $0.dueAt < $1.dueAt }.prefix(10))
                }
        }.presentationDetents([.medium, .large])
    }

    private func advance() {
        index += 1; answer = ""; missed = false
    }
}
