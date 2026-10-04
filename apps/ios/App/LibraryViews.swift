import SwiftUI
import UniformTypeIdentifiers
import LearningCore

struct ThemesView: View {
    let coordinator: ConversationCoordinator
    let choose: (ConversationTheme?) -> Void
    /// Marks a situation (curated or the learner's own) as the next practice and
    /// returns to the talk screen; the coordinator handles consent and the mic.
    let practiseScenario: (Scenario) -> Void
    @State private var search = ""
    @State private var category = "All"
    @State private var current = false
    @State private var custom = false
    @Environment(\.dynamicTypeSize) private var typeSize
    private var themes: [ConversationTheme] {
        coordinator.language.themes.filter { (category == "All" || $0.category == category) && (search.isEmpty || $0.title.localizedCaseInsensitiveContains(search) || $0.category.localizedCaseInsensitiveContains(search)) }
    }
    private var scenarios: [Scenario] {
        Scenarios.shared.filter { search.isEmpty || $0.title.localizedCaseInsensitiveContains(search) || $0.subtitle.localizedCaseInsensitiveContains(search) || $0.category.localizedCaseInsensitiveContains(search) }
    }
    private var categories: [String] { coordinator.language.themes.map(\.category).reduce(into: ["All"]) { if !$0.contains($1) { $0.append($1) } } }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                PageHeading(eyebrow: "A place to begin", title: "What’s on\nyour mind?", subtitle: "Same friend. Somewhere new.")
                Button { choose(nil) } label: {
                    HStack { Image(systemName: "waveform"); Text("Just talk"); Spacer(); Image(systemName: "arrow.up.right") }
                        .font(.headline).padding(22).background(.white.opacity(0.8), in: RoundedRectangle(cornerRadius: 26))
                }
                ScrollView(.horizontal) {
                    HStack(spacing: 8) {
                        ForEach(categories, id: \.self) { c in
                            Button(c) { category = c }.font(.caption).padding(.horizontal, 15).padding(.vertical, 11)
                                .background(category == c ? AppColor.peach : .white.opacity(0.65), in: Capsule())
                                .accessibilityAddTraits(category == c ? .isSelected : [])
                        }
                    }
                }.scrollIndicators(.hidden)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: typeSize.isAccessibilitySize ? 260 : 150), spacing: 12)], spacing: 12) {
                    ForEach(themes) { theme in
                        Button { if theme.id == "today" { current = true } else { choose(theme) } } label: {
                            VStack(alignment: .leading, spacing: 28) {
                                Image(systemName: theme.symbol).font(.system(size: 28, weight: .light)).foregroundStyle(AppColor.secondary)
                                VStack(alignment: .leading, spacing: 5) {
                                    Text(theme.title).font(.system(.headline, design: .rounded))
                                    Text(theme.subtitle).font(.caption).foregroundStyle(AppColor.secondary)
                                }
                            }.frame(maxWidth: .infinity, minHeight: 142, alignment: .leading).padding(19)
                                .background(AppColor.panels[theme.colorIndex], in: RoundedRectangle(cornerRadius: 27))
                        }.buttonStyle(.plain)
                    }
                }
                if themes.isEmpty { ContentUnavailableView.search(text: search) }
                if !scenarios.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Practise a situation").font(.system(.title2, design: .rounded, weight: .semibold))
                        Text("A guided rehearsal, then a review of your mistakes.").font(.subheadline).foregroundStyle(AppColor.secondary)
                    }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: typeSize.isAccessibilitySize ? 260 : 150), spacing: 12)], spacing: 12) {
                        ForEach(scenarios, id: \.id) { scenario in
                            Button { practiseScenario(scenario) } label: {
                                VStack(alignment: .leading, spacing: 28) {
                                    Image(systemName: Self.symbol(scenario.id)).font(.system(size: 28, weight: .light)).foregroundStyle(AppColor.secondary)
                                    VStack(alignment: .leading, spacing: 5) {
                                        Text(scenario.title).font(.system(.headline, design: .rounded))
                                        Text(scenario.subtitle).font(.caption).foregroundStyle(AppColor.secondary)
                                    }
                                }.frame(maxWidth: .infinity, minHeight: 142, alignment: .leading).padding(19)
                                    .background(AppColor.panels[scenario.colorIndex], in: RoundedRectangle(cornerRadius: 27))
                            }.buttonStyle(.plain).accessibilityIdentifier("scenario-\(scenario.id)")
                        }
                    }
                    Button { custom = true } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "sparkles").font(.system(size: 24, weight: .light)).foregroundStyle(AppColor.secondary)
                            VStack(alignment: .leading, spacing: 5) {
                                Text("Your own situation").font(.system(.headline, design: .rounded))
                                Text("Describe it and we’ll rehearse it").font(.caption).foregroundStyle(AppColor.secondary)
                            }
                            Spacer()
                            Image(systemName: "arrow.up.right").font(.subheadline).foregroundStyle(AppColor.secondary)
                        }.padding(22).frame(maxWidth: .infinity)
                            .background(AppColor.butter.opacity(0.8), in: RoundedRectangle(cornerRadius: 26))
                    }.buttonStyle(.plain).accessibilityIdentifier("scenario-custom")
                }
            }.padding(24)
        }.foregroundStyle(AppColor.ink)
            .searchable(text: $search, prompt: "Find a conversation")
            .sheet(isPresented: $current) { CurrentTopicView(coordinator: coordinator) { choose(coordinator.selectedTheme) } }
            .sheet(isPresented: $custom) { CustomScenarioView(coordinator: coordinator, practise: practiseScenario) }
    }

    /// The Core catalog carries Android glyph names; iOS mirrors the same card
    /// icons with the closest SF Symbols (same mapping as Android's scenarioSymbol).
    private static func symbol(_ id: String) -> String {
        switch id {
        case "job_interview": "star"
        case "presentation": "mic"
        case "sports": "sun"
        case "restaurant": "fork.knife"
        case "doctor": "leaf"
        case "phone_call": "waveform"
        case "small_talk": "cup.and.saucer"
        case "shopping": "square.grid.2x2"
        default: "sparkles"
        }
    }
}

/// The learner's own free-text situation: sanitized by `Scenarios.custom` before
/// it reaches the coordinator. Mirror of Android's CustomScenarioDialog.
struct CustomScenarioView: View {
    let coordinator: ConversationCoordinator
    let practise: (Scenario) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var goal = ""
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    PageHeading(eyebrow: "Your own situation", title: "What would you like to practise?", subtitle: "A job interview, asking for the bill, talking about your team…")
                    TextField("Describe the situation", text: Binding(get: { goal }, set: { goal = String($0.prefix(160)) }), axis: .vertical)
                        .lineLimit(2...4).padding(18).background(.white, in: RoundedRectangle(cornerRadius: 20))
                        .accessibilityIdentifier("scenario-custom-input")
                    Button { practise(Scenarios.custom(goal)); dismiss() } label: {
                        HStack { Text("Practise this"); Spacer(); Image(systemName: "waveform") }
                            .font(.headline).padding(18).background(AppColor.orange, in: Capsule())
                    }.disabled(goal.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        .accessibilityIdentifier("scenario-custom-start")
                    Text("Your description stays on this device and is used only to guide the practice.").font(.footnote).foregroundStyle(AppColor.secondary)
                }.padding(26)
            }.background(AppColor.cream).foregroundStyle(AppColor.ink)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        }.presentationDetents([.medium, .large])
    }
}

struct CurrentTopicView: View {
    let coordinator: ConversationCoordinator
    let selected: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var brief: TopicBrief?
    @State private var loading = false
    @State private var error: String?
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    PageHeading(eyebrow: "The world today", title: "A fresh conversation.", subtitle: "What would you like to talk about?")
                    TextField(coordinator.language.topicPlaceholder, text: $query, axis: .vertical).padding(18).background(.white, in: RoundedRectangle(cornerRadius: 20))
                    Button { find() } label: {
                        HStack { Text(loading ? "Finding something interesting…" : "Find a topic"); Spacer(); if loading { ProgressView() } else { Image(systemName: "sparkle.magnifyingglass") } }.padding(18).background(AppColor.peach, in: Capsule())
                    }.disabled(loading || query.trimmingCharacters(in: .whitespaces).isEmpty)
                    if let error { Text(error).font(.footnote).foregroundStyle(AppColor.secondary) }
                    if let brief {
                        Text(.init(brief.text)).font(.body).textSelection(.enabled)
                        SourcesView(sources: brief.sources, date: brief.retrievedAt)
                        Button("Talk about this", systemImage: "waveform") { coordinator.discuss(brief); selected(); dismiss() }
                            .font(.headline).padding(18).frame(maxWidth: .infinity).background(AppColor.orange, in: Capsule())
                    }
                    Text("Search uses your OpenAI API account. Sources stay attached to the topic.").font(.footnote).foregroundStyle(AppColor.secondary)
                }.padding(26)
            }.background(AppColor.cream).foregroundStyle(AppColor.ink)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        }
    }
    private func find() {
        loading = true; error = nil
        Task { do { brief = try await coordinator.currentTopic(query) } catch { self.error = error.localizedDescription }; loading = false }
    }
}

struct WordsView: View {
    let coordinator: ConversationCoordinator
    /// Empty-state CTAs; RootView wires them to the Learn and Talk tabs,
    /// mirroring Android's onOpenLessons/onOpenTalk.
    var onOpenLessons: () -> Void = {}
    var onOpenTalk: () -> Void = {}
    @State private var search = ""
    @State private var selected: WordState?
    @State private var sessions = false
    @State private var practising = false
    private var learner: LearnerState { coordinator.store.learner }
    private var words: [WordState] { learner.words.filter { search.isEmpty || $0.lemma.localizedCaseInsensitiveContains(search) || $0.meaning.localizedCaseInsensitiveContains(search) } }
    private var dueCount: Int { learner.words.filter { $0.dueAt < .now }.count }
    /// The spaced-recall notebook: saved expressions, most recently seen first —
    /// per learning language (the implemented product decision on both
    /// platforms; the all-languages view was the bug): the language's rows
    /// plus the legacy NULL-language entries (mirrors Android's
    /// notebookExpressions).
    private var expressions: [ExpressionState] {
        coordinator.store.notebookExpressions
    }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                PageHeading(eyebrow: "Little by little · \(coordinator.language.name)", title: "Your words.", subtitle: "Familiar words, ready for another conversation.")
                if dueCount > 0 {
                    Button { practising = true } label: {
                        Text("Practise writing (\(dueCount) due)").frame(maxWidth: .infinity)
                    }.buttonStyle(.borderedProminent).tint(AppColor.sage).foregroundStyle(AppColor.ink)
                        .accessibilityIdentifier("words-practice")
                }
                if words.isEmpty {
                    VStack(alignment: .leading, spacing: 18) {
                        Image(systemName: "leaf").font(.system(size: 34, weight: .light))
                        Text(search.isEmpty ? "They’ll grow from here." : "No matching words yet.").font(.system(.title2, design: .rounded, weight: .medium))
                        Text(search.isEmpty ? "As we talk, useful words and phrases find a home here. Their strength grows when you recall them over time." : "Try another \(coordinator.language.name) word or English meaning.").font(.subheadline).foregroundStyle(AppColor.secondary)
                    }.padding(26).frame(maxWidth: .infinity, alignment: .leading).background(AppColor.sage, in: RoundedRectangle(cornerRadius: 28))
                } else {
                    LazyVStack(spacing: 0) {
                        ForEach(words) { word in
                            Button { selected = word } label: {
                                HStack(spacing: 18) {
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text(word.lemma).font(.system(.title2, design: .rounded, weight: .medium))
                                        Text(word.meaning).font(.subheadline).foregroundStyle(AppColor.secondary)
                                    }
                                    Spacer(minLength: 10)
                                    VStack(alignment: .trailing, spacing: 8) { RecallBars(count: word.bars); Text(word.label).font(.caption2).foregroundStyle(AppColor.secondary) }
                                }.padding(.vertical, 20)
                            }.buttonStyle(.plain)
                            Divider().overlay(AppColor.peach)
                        }
                    }
                }
                HStack { Text("1 · Fragile"); Spacer(); Text("2 · Growing"); Spacer(); Text("3 · Steady") }.font(.caption).foregroundStyle(AppColor.secondary)
                Text("The bars estimate spoken recall, not permanent mastery. Using a word with visible meanings counts as supported practice.").font(.footnote).foregroundStyle(AppColor.secondary)
                Text("Saved expressions").font(.system(.title3, design: .rounded, weight: .semibold))
                    .accessibilityAddTraits(.isHeader)
                if expressions.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Save expressions from a lesson or the dictionary and they will return for recall.")
                            .font(.subheadline).foregroundStyle(AppColor.secondary)
                        HStack(spacing: 10) {
                            Button { onOpenLessons() } label: { Text("Practise a lesson").frame(maxWidth: .infinity) }
                                .buttonStyle(.borderedProminent).tint(AppColor.sage).foregroundStyle(AppColor.ink)
                                .accessibilityIdentifier("words-cta-lessons")
                            Button { onOpenTalk() } label: { Text("Start talking").frame(maxWidth: .infinity) }
                                .buttonStyle(.borderedProminent).tint(AppColor.sage).foregroundStyle(AppColor.ink)
                                .accessibilityIdentifier("words-cta-talk")
                        }
                    }
                } else {
                    LazyVStack(spacing: 12) {
                        ForEach(expressions, id: \.self) { expression in
                            HStack(spacing: 18) {
                                VStack(alignment: .leading, spacing: 6) {
                                    Text(expression.text).font(.system(.title2, design: .rounded, weight: .medium))
                                    Text(expression.meaning).font(.subheadline).foregroundStyle(AppColor.secondary)
                                    Text(sourceLabel(expression.source)).font(.caption2).foregroundStyle(AppColor.secondary)
                                }
                                Spacer(minLength: 10)
                                VStack(alignment: .trailing, spacing: 8) {
                                    RecallBars(count: expression.bars)
                                    Button("Remove") { coordinator.store.removeExpression(text: expression.text) }
                                        .font(.footnote)
                                        .accessibilityIdentifier("expression-remove-\(expression.text)")
                                }
                            }.padding(18).frame(maxWidth: .infinity, alignment: .leading)
                                .background(AppColor.butter, in: RoundedRectangle(cornerRadius: 20))
                        }
                    }
                }
                if !learner.capabilities.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Finding your voice").font(.system(.title3, design: .rounded, weight: .semibold))
                        ForEach(learner.capabilities, id: \.self) { Text($0).font(.subheadline) }
                        Text("Observed across conversations. These are provisional, not formal level certificates.").font(.footnote).foregroundStyle(AppColor.secondary)
                    }.padding(22).background(AppColor.butter, in: RoundedRectangle(cornerRadius: 24))
                }
                Button("Past conversations", systemImage: "clock.arrow.circlepath") { sessions = true }.font(.subheadline).padding(.vertical, 8)
            }.padding(26)
        }.foregroundStyle(AppColor.ink).searchable(text: $search, prompt: "Find a word")
            .sheet(item: $selected) { word in WordDetailView(word: word, store: coordinator.store) }
            .sheet(isPresented: $sessions) { SessionHistoryView(store: coordinator.store) }
            .sheet(isPresented: $practising) { WritingPracticeView(store: coordinator.store) }
    }

    /// Where the expression came from — mirror of Android's expressionSourceLabel.
    private func sourceLabel(_ source: String) -> String {
        switch source {
        case "lesson": return "From a lesson"
        case "lookup": return "Saved by you"
        default: return source
        }
    }
}

struct WordDetailView: View {
    let word: WordState
    let store: LearningStore
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 24) {
                Text(word.lemma).font(.system(.largeTitle, design: .rounded, weight: .medium))
                if store.language.id == "zh" { PinyinHelp(text: word.lemma) }
                Text(word.meaning).font(.title3).foregroundStyle(AppColor.secondary)
                HStack { RecallBars(count: word.bars); Text(word.label).font(.subheadline) }
                Text(word.explanation).font(.body)
                Text("“\(word.example)”").font(.system(.title3, design: .rounded)).padding(20).frame(maxWidth: .infinity, alignment: .leading).background(AppColor.peach, in: RoundedRectangle(cornerRadius: 22))
                Text("\(word.independentCount) independent uses · Last seen \(word.lastSeen.formatted(date: .abbreviated, time: .omitted))").font(.footnote).foregroundStyle(AppColor.secondary)
                Button("Remove from my words", role: .destructive) { store.hideWord(word.id); dismiss() }.font(.footnote)
                Spacer()
            }.padding(28).frame(maxWidth: .infinity, alignment: .leading).background(AppColor.cream).foregroundStyle(AppColor.ink)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }.presentationDetents([.medium, .large])
    }
}

struct SourcesView: View {
    var sources: [SourceLink]
    var date: Date
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Sources · \(date.formatted(date: .abbreviated, time: .omitted))").font(.caption).foregroundStyle(AppColor.secondary)
            ForEach(sources) { source in if let url = source.safeURL { Link(destination: url) { Label(source.title, systemImage: "arrow.up.right").font(.subheadline) } } }
        }
    }
}

struct TranscriptView: View {
    let session: SessionRecord?
    var meaningLanguage = "English"
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    if let session {
                        ForEach(session.passages) { passage in
                            VStack(alignment: .leading, spacing: 8) {
                                Text(passage.speaker == .assistant ? "PALIQUE" : "YOU").font(.caption).tracking(1).foregroundStyle(AppColor.secondary)
                                Text(passage.text).font(.system(.title3, design: .rounded)).textSelection(.enabled)
                                if session.languageID == "zh" { PinyinHelp(text: passage.text) }
                                if let translation = session.translations[MeaningRequest.cacheKey(revisionKey: passage.revisionKey, language: meaningLanguage)] ?? session.translations[passage.revisionKey] {
                                    Text(translation).font(.subheadline).foregroundStyle(AppColor.secondary)
                                }
                            }.frame(maxWidth: .infinity, alignment: .leading)
                        }
                        ForEach(session.topics) { topic in Text(.init(topic.text)); SourcesView(sources: topic.sources, date: topic.retrievedAt) }
                        if session.fragments.isEmpty && session.topics.isEmpty { Text("Your conversation will appear here.").foregroundStyle(AppColor.secondary) }
                    } else { Text("Start a conversation and your words will appear here.") }
                }.padding(26)
            }.background(AppColor.cream).foregroundStyle(AppColor.ink)
                .navigationTitle("Our conversation").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

struct SessionHistoryView: View {
    let store: LearningStore
    @State private var selected: SessionRecord?
    @State private var deleting: SessionRecord?
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List {
                if store.learningSessions.isEmpty { Text("Your \(store.language.name) conversations will appear here.").foregroundStyle(AppColor.secondary) }
                ForEach(store.learningSessions) { session in
                    Button { selected = session } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(session.title).font(.headline)
                            Text(session.startedAt.formatted(date: .abbreviated, time: .shortened)).font(.caption).foregroundStyle(AppColor.secondary)
                        }.padding(.vertical, 8)
                    }.swipeActions { Button("Delete", role: .destructive) { deleting = session }.disabled(session.endedAt == nil) }
                }
            }.scrollContentBackground(.hidden).background(AppColor.cream)
                .navigationTitle("Past conversations").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }.sheet(item: $selected) { session in EditableTranscriptView(sessionID: session.id, store: store) }
            .confirmationDialog("Delete this conversation and its learning evidence?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
                Button("Delete conversation", role: .destructive) { if let deleting { store.deleteSession(deleting.id) }; deleting = nil }
            }
    }
}

struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

/// The whole learning database (.db bytes) as a FileDocument — the phase-F
/// database backup's export payload, beside the JSON archive's
/// `BackupDocument` (kept until H retires it).
struct DatabaseBackupDocument: FileDocument {
    static var databaseBackupType: UTType { UTType(filenameExtension: "db") ?? .data }
    static var readableContentTypes: [UTType] { [databaseBackupType] }
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws { data = configuration.file.regularFileContents ?? Data() }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

struct EditableTranscriptView: View {
    let sessionID: UUID
    let store: LearningStore
    @Environment(\.dismiss) private var dismiss
    @State private var editingID: String?
    @State private var editedText = ""
    @State private var reporting: Passage?
    private var session: SessionRecord? { store.sessions.first { $0.id == sessionID } }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    ForEach(session?.passages ?? []) { passage in
                        VStack(alignment: .leading, spacing: 8) {
                            HStack {
                                Text(passage.speaker == .user ? "YOU" : "PALIQUE").font(.caption).tracking(1)
                                Spacer()
                                if passage.speaker == .user && session?.endedAt != nil {
                                    Button("Edit") { editedText = passage.text; editingID = passage.id }.font(.caption)
                                }
                                if passage.speaker == .assistant {
                                    Button { reporting = passage } label: { Image(systemName: "flag") }
                                        .font(.caption).accessibilityLabel("Report this reply")
                                }
                            }.foregroundStyle(AppColor.secondary)
                            Text(passage.text).font(.system(.title3, design: .rounded)).textSelection(.enabled)
                            if session?.languageID == "zh" { PinyinHelp(text: passage.text) }
                        }
                    }
                    ForEach(session?.topics ?? []) { topic in Text(.init(topic.text)); SourcesView(sources: topic.sources, date: topic.retrievedAt) }
                }.padding(26)
            }.background(AppColor.cream).foregroundStyle(AppColor.ink)
                .navigationTitle("Our conversation").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }.sheet(isPresented: Binding(get: { editingID != nil }, set: { if !$0 { editingID = nil } })) {
            NavigationStack {
                VStack(alignment: .leading, spacing: 20) {
                    TextField("What you said", text: $editedText, axis: .vertical).lineLimit(4...10).padding(18).background(.white, in: RoundedRectangle(cornerRadius: 20))
                    Text("Correct a misheard phrase. Learning evidence from the old wording will be removed; the original remains in your backup history.").font(.footnote).foregroundStyle(AppColor.secondary)
                    Spacer()
                }.padding(24).background(AppColor.cream).navigationTitle("What you said").navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { editingID = nil } }
                        ToolbarItem(placement: .confirmationAction) { Button("Save") { if let id = editingID { store.correctPassage(sessionID: sessionID, passageID: id, text: editedText) }; editingID = nil } }
                    }
            }.presentationDetents([.medium, .large])
        }
        .sheet(item: $reporting) { passage in
            ReportSheet(languageID: session?.languageID ?? "en", passage: passage)
        }
    }
}

struct SettingsView: View {
    let coordinator: ConversationCoordinator
    @Environment(\.dismiss) private var dismiss
    @State private var key = ""
    @State private var hasKey = CredentialStore.hasKey
    @State private var message: String?
    @State private var exporting = false
    @State private var importing = false
    @State private var backup: BackupDocument?
    @State private var deleting = false
    @State private var exportingDb = false
    @State private var importingDb = false
    @State private var dbBackup: DatabaseBackupDocument?
    @State private var deletingLanguage = false
    @State private var confirmingLanguageDelete = false
    @State private var deleteLanguageTarget = ""
    @State private var notices = false
    @State private var showingAPIKey = false
    @State private var tokens = TokensCommerceModel(configuration: ManagedAccountConfiguration.load())
    private var store: LearningStore { coordinator.store }
    private var deleteLanguageName: String {
        LanguageRegistry.module(for: deleteLanguageTarget)?.name ?? deleteLanguageTarget
    }
    /// palique-backup-YYYYMMDD.db — the suggested .db backup name (the
    /// Android SAF suggested name's mirror).
    private static let backupStamp: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyyMMdd"
        return formatter
    }()
    private var totalVoiceSeconds: Double { store.sessions.reduce(0) { $0 + $1.voiceSeconds } }
    /// Server display hint for the tokens row (GET /v1/wallet): nil when signed
    /// out or the read failed — never a cached balance.
    private var tokensChip: String? {
        guard !tokens.showSignIn, let wallet = tokens.wallet, let nano = Int64(wallet.availableNanoUSD) else { return nil }
        return nano > 0 ? String(format: "%@ of prepaid tokens available", tokenCreditText(nano) ?? "")
                        : "No prepaid tokens left"
    }
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LearningLanguagePicker(coordinator: coordinator)
                    Toggle("Meaning subtitles", isOn: Binding(get: { store.preferences.meaningVisible }, set: { value in
                        if value != store.preferences.meaningVisible { coordinator.toggleMeaning() }
                    }))
                    Picker("Meaning language", selection: Binding(get: { store.preferences.meaningLanguage }, set: { coordinator.selectMeaningLanguage($0) })) {
                        ForEach(MeaningLanguages.all, id: \.self) { Text($0) }
                    }
                    Picker("Speech rate", selection: Binding(get: {
                        store.preferences.effectiveSpeechRate().description
                    }, set: { value in
                        if let rate = Double(value) { store.updatePreferences { $0.speechRate = rate } }
                    })) {
                        Text("Very slow").tag(0.6.description)
                        Text("Slow").tag(0.8.description)
                        Text("Normal").tag(1.0.description)
                        Text("Fast").tag(1.2.description)
                    }
                    .disabled(coordinator.isRunning)
                    Toggle("Listening practice", isOn: Binding(get: { store.preferences.listeningPractice == true }, set: { value in
                        store.updatePreferences { $0.listeningPractice = value ? true : nil }
                    }))
                    .disabled(coordinator.isRunning)
                    Toggle("Pronunciation practice (beta)", isOn: Binding(get: { store.preferences.pronunciationEnabled == true }, set: { value in
                        store.updatePreferences { $0.pronunciationEnabled = value ? true : nil }
                    }))
                    .disabled(coordinator.isRunning)
                    Toggle("Share anonymous usage metrics", isOn: Binding(get: { store.preferences.usageMetricsEnabled != false }, set: { value in
                        coordinator.setUsageMetrics(enabled: value)
                    }))
                    LabeledContent("Corrections", value: "Gently, as we talk")
                    TextField("A few things you enjoy", text: Binding(get: { store.preferences.interests }, set: { value in store.updatePreferences { $0.interests = String(value.prefix(500)) } }), axis: .vertical)
                } header: { Text("Just your pace") } footer: { Text(coordinator.isRunning ? "End this conversation to switch languages. Each language keeps its own words and progress." : "Each language keeps its own words and progress. Palique finds your pace through conversation. Slower speech applies to new conversations. Listening practice tells a short story without meaning subtitles — the written story stays on screen, and you can still look words up.") }
                if ManagedAccountConfiguration.load() != nil {
                    Section {
                        NavigationLink { ManagedAccountView() } label: {
                            Label("Account", systemImage: "person.crop.circle")
                        }.disabled(coordinator.isRunning).accessibilityIdentifier("managed-account-settings")
                        if coordinator.hostedUIAvailable {
                            Picker("Conversation", selection: Binding(get: { coordinator.engineSelection },
                                                                      set: { coordinator.selectEngine($0) })) {
                                Text("Your OpenAI key").tag(ConversationCoordinator.EngineSelection.personalKey)
                                if coordinator.tokensUIAvailable {
                                    Text("Palique (tokens)").tag(ConversationCoordinator.EngineSelection.tokens)
                                }
                                Text("Included minutes").tag(ConversationCoordinator.EngineSelection.hostedMinutes)
                            }.disabled(coordinator.isRunning).accessibilityIdentifier("conversation-provider")
                            if coordinator.prefersHostedMinutes {
                                let balance = coordinator.hostedBalance
                                Text(balance == nil ? "Included minutes: sign in to check your balance."
                                     : balance!.canStartConversation ? "Included minutes available."
                                     : "No included minutes left.")
                                    .font(.footnote).foregroundStyle(AppColor.secondary)
                            }
                        }
                        // Commercial surface for the hosted-proxy engine ("Palique
                        // (tokens)"): engine SELECTION is the picker above; this row
                        // is only the paywall/pack entry with the server's balance
                        // display hint. Hidden when the hosted surface is disabled.
                        if coordinator.tokensUIAvailable {
                            Button {
                                coordinator.showTokensSheet = true
                            } label: {
                                HStack(spacing: 10) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("Palique (tokens)")
                                        Text("Prepaid tokens for the hosted model")
                                            .font(.footnote).foregroundStyle(AppColor.secondary)
                                    }
                                    Spacer()
                                    if let chip = tokensChip {
                                        Text(chip).font(.caption).weight(.medium)
                                            .padding(.horizontal, 10).padding(.vertical, 5)
                                            .background(AppColor.peach.opacity(0.7), in: Capsule())
                                            .accessibilityIdentifier("settings-tokens-balance")
                                    }
                                    Image(systemName: "chevron.right").font(.caption2).foregroundStyle(AppColor.secondary)
                                }
                            }.buttonStyle(.plain)
                                .disabled(coordinator.isRunning)
                                .accessibilityIdentifier("settings-tokens-row")
                        }
                    }
                }
                Section {
                    DisclosureGroup(isExpanded: $showingAPIKey) {
                        if hasKey { Label("Your key is saved on this iPhone", systemImage: "checkmark.shield") }
                        SecureField(hasKey ? "Replace OpenAI key" : "OpenAI API key", text: $key)
                            .textInputAutocapitalization(.never).autocorrectionDisabled().privacySensitive().accessibilityIdentifier("api-key")
                        Button(hasKey ? "Save replacement key" : "Save key") {
                            do { try CredentialStore.save(key); key = ""; hasKey = true; message = "Saved securely. Start a conversation to connect." }
                            catch { message = error.localizedDescription }
                        }.disabled(key.isEmpty || coordinator.isRunning)
                        Link("Open OpenAI API keys", destination: URL(string: "https://platform.openai.com/api-keys")!)
                        if hasKey {
                            Button("Remove key", role: .destructive) {
                                do { try CredentialStore.delete(); hasKey = false; message = "Your key has been removed." }
                                catch { message = error.localizedDescription }
                            }.disabled(coordinator.isRunning)
                        }
                        Text("Your OpenAI account pays for usage. The key stays in this iPhone’s Keychain and is sent only to OpenAI.")
                            .font(.footnote).foregroundStyle(AppColor.secondary)
                    } label: { Label("Use your own API key", systemImage: "key").accessibilityIdentifier("advanced-api-key") }
                    if let message { Text(message).font(.footnote).foregroundStyle(AppColor.secondary) }
                } header: { Text("Advanced") } footer: {
                    if !hasKey { Text("This version uses your OpenAI API key to start a conversation.") }
                }
                Section {
                    NavigationLink { LearningProgressView(store: store) } label: {
                        Label("Your progress", systemImage: "chart.bar")
                    }.disabled(coordinator.isRunning).accessibilityIdentifier("settings-progress")
                    Picker("Conversation limit", selection: Binding(get: { store.preferences.sessionMinutes }, set: { value in store.updatePreferences { $0.sessionMinutes = value } })) {
                        ForEach([5, 10, 15, 20, 30, 60], id: \.self) { Text("\($0) minutes").tag($0) }
                    }
                    LabeledContent("Recorded voice time", value: "\(Int(totalVoiceSeconds / 60)) min \(Int(totalVoiceSeconds) % 60) sec")
                    LabeledContent("Voice estimate", value: String(format: "$%.2f USD", totalVoiceSeconds / 60 * 0.05))
                    LabeledContent("Search calls recorded", value: "\(store.sessions.reduce(0) { $0 + $1.searchCalls })")
                    Link("OpenAI usage and billing", destination: URL(string: "https://platform.openai.com/usage")!)
                } header: { Text("Keep it comfortable") } footer: {
                    Text("Voice estimate uses $0.05/min as of 11 September 2026. Translation, teaching and search cost extra. Interrupted requests can be billed without a usage record here. Your OpenAI dashboard is authoritative. The time limit is local, not a billing cap.")
                }
                Section {
                    Button("Export learning backup", systemImage: "square.and.arrow.up") {
                        do { backup = BackupDocument(data: try store.exportData()); exporting = true } catch { message = error.localizedDescription }
                    }
                    Button("Import learning backup", systemImage: "square.and.arrow.down") { importing = true }.disabled(coordinator.isRunning)
                    Button("Delete all conversations and learning", role: .destructive) { deleting = true }.disabled(coordinator.isRunning)
                } header: { Text("Your words belong to you") } footer: {
                    Text("Backups include transcripts and learning evidence, never your API key. Import adds only conversations this iPhone doesn’t have, keeping their original IDs; existing conversations stay unchanged. There is no cloud sync.")
                }
                Section {
                    Button("Export database backup (.db)", systemImage: "square.and.arrow.up") {
                        if let data = store.exportDb() {
                            dbBackup = DatabaseBackupDocument(data: data); exportingDb = true
                        } else {
                            message = store.error ?? "The database backup couldn’t be exported."
                            store.error = nil
                        }
                    }
                    .disabled(coordinator.isRunning).accessibilityIdentifier("settings-export-db")
                    Button("Restore database backup (.db)", systemImage: "square.and.arrow.down") { importingDb = true }
                        .disabled(coordinator.isRunning).accessibilityIdentifier("settings-import-db")
                    Button("Delete a language’s learning data", role: .destructive) { deletingLanguage = true }
                        .disabled(coordinator.isRunning).accessibilityIdentifier("settings-delete-language")
                } header: { Text("Backup (database)") } footer: {
                    Text("One .db file with everything your learning holds: conversations, words, lessons and progress. Restoring a backup replaces what is on this device with the file’s contents. This device only — there is no cloud sync.")
                }
                Section {
                    Link("Privacy policy", destination: URL(string: "https://app.example/privacy/")!)
                        .accessibilityIdentifier("settings-privacy-policy")
                    Link("Terms of use", destination: URL(string: "https://app.example/terms/")!)
                        .accessibilityIdentifier("settings-terms")
                    Link("Contact support", destination: URL(string: "https://app.example/support/")!)
                        .accessibilityIdentifier("settings-support")
                } header: { Text("Help and privacy") }
                Section {
                    Text("Palique 0.1 · Personal build").font(.footnote)
                    Text("Voice: GPT-Live-1 · Teacher: GPT-5.6 Luna").font(.footnote)
                    Link("OpenAI data controls", destination: URL(string: "https://developers.openai.com/api/docs/guides/your-data")!)
                    Text("Audio and selected text go to OpenAI while you practise. Requests disable provider storage where supported; abuse-monitoring retention may still apply. Raw audio is not saved by Palique.").font(.footnote)
                    Button("Open-source notices") { notices = true }
                }
            }.scrollContentBackground(.hidden).background(AppColor.cream).tint(AppColor.secondary)
                .navigationTitle("Make yourself comfortable").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { key = ""; dismiss() } } }
        }
        .fileExporter(isPresented: $exporting, document: backup, contentType: .json, defaultFilename: "Palique-learning-backup") { result in if case .failure(let error) = result { message = error.localizedDescription } }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
            do {
                let url = try result.get(); let granted = url.startAccessingSecurityScopedResource(); defer { if granted { url.stopAccessingSecurityScopedResource() } }
                try store.importData(Archive.readImportData(from: url)); message = "Your backup has been imported."
            } catch { message = error.localizedDescription }
        }
        .fileExporter(isPresented: $exportingDb, document: dbBackup,
                      contentType: DatabaseBackupDocument.databaseBackupType,
                      defaultFilename: "palique-backup-\(Self.backupStamp.string(from: .now)).db") { result in if case .failure(let error) = result { message = error.localizedDescription } }
        .fileImporter(isPresented: $importingDb, allowedContentTypes: [DatabaseBackupDocument.databaseBackupType]) { result in
            do {
                let url = try result.get(); let granted = url.startAccessingSecurityScopedResource(); defer { if granted { url.stopAccessingSecurityScopedResource() } }
                if coordinator.importDb(from: url) {
                    message = "Backup restored: your learning data was replaced with the imported copy."
                } else {
                    message = store.error ?? "The database backup couldn’t be restored."
                    store.error = nil
                }
            } catch { message = error.localizedDescription }
        }
        .sheet(isPresented: Binding(get: { coordinator.showTokensSheet },
                                    set: { if !$0 { coordinator.dismissTokensSheet(); Task { await tokens.refresh() } } })) {
            TokensPurchaseSheet(coordinator: coordinator)
        }
        .task { await tokens.refresh() }
        .confirmationDialog("Delete all learning data on this phone?", isPresented: $deleting, titleVisibility: .visible) {
            Button("Delete all learning data", role: .destructive) { coordinator.deleteLearningData() }
        } message: { Text("This removes conversations, vocabulary and progress. Export a backup first if you want to keep them. Your API key and preferences remain.") }
        .confirmationDialog("Delete which language?", isPresented: $deletingLanguage, titleVisibility: .visible) {
            ForEach(LanguageRegistry.all) { language in
                Button(language.settingsTitle, role: .destructive) {
                    deleteLanguageTarget = language.id
                    // The confirm step presents after the picker's dismissal
                    // settles — a same-tick re-presentation is dropped.
                    DispatchQueue.main.async { confirmingLanguageDelete = true }
                }
            }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog("Delete \(deleteLanguageName) learning data?", isPresented: $confirmingLanguageDelete, titleVisibility: .visible) {
            Button("Delete \(deleteLanguageName) data", role: .destructive) {
                if coordinator.deleteLearningDataForLanguage(deleteLanguageTarget) {
                    message = "\(deleteLanguageName) learning data deleted."
                } else {
                    message = coordinator.store.error ?? coordinator.error
                        ?? "The learning data couldn’t be deleted."
                    coordinator.store.error = nil; coordinator.error = nil
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This removes the \(deleteLanguageName) conversations, words and lesson progress. Other languages, your key and your preferences remain. Export a backup first if you want to keep them.")
        }
        .sheet(isPresented: $notices) {
            NavigationStack {
                ScrollView { Text(Bundle.main.url(forResource: "ThirdPartyNotices", withExtension: "txt").flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? "Notices unavailable.").font(.footnote).padding(24).textSelection(.enabled) }
                    .navigationTitle("Open-source notices").navigationBarTitleDisplayMode(.inline)
            }
        }
    }
}

struct LearningLanguagePicker: View {
    let coordinator: ConversationCoordinator
    var body: some View {
        Picker("Learning language", selection: Binding(get: { coordinator.language.id }, set: { coordinator.selectLanguage($0) })) {
            ForEach(LanguageRegistry.all) { language in Text(language.settingsTitle).tag(language.id) }
        }
        .pickerStyle(.menu)
        .disabled(coordinator.isRunning)
        .accessibilityIdentifier("learning-language-picker")
    }
}
