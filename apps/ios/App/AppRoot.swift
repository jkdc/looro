import SwiftUI

@main struct AppRoot: App {
    @State private var store: LearningStore?
    @State private var startupError: String?
    @State private var confirmingErase = false
    init() {
        do { _store = State(initialValue: try makeStore()) }
        catch { _startupError = State(initialValue: String(localized: "Palique couldn’t open its learning record. Your existing data has not been replaced.")) }
    }
    private func makeStore() throws -> LearningStore {
        #if DEBUG
        return try LearningStore(inMemory: ProcessInfo.processInfo.arguments.contains("--preview") || AudioVerification.requested)
        #else
        return try LearningStore(inMemory: false)
        #endif
    }
    private func retryOpeningStore() {
        do { store = try makeStore(); startupError = nil }
        catch { startupError = String(localized: "Palique still couldn’t open your learning data. Nothing was erased. Please try again.") }
    }
    /// Reachable only through the confirmation dialog below; the damaged record is the one thing erased.
    private func eraseAndRestart() {
        do { try LearningStore.eraseStore() }
        catch { startupError = String(localized: "Palique couldn’t erase its learning data. Please try again."); return }
        do { store = try makeStore(); startupError = nil }
        catch { startupError = String(localized: "Palique couldn’t open a fresh learning record. Please try again.") }
    }
    var body: some Scene {
        WindowGroup {
            if let store { RootView(store: store).preferredColorScheme(.light).appUiLanguage() }
            else {
                ContentUnavailableView {
                    Label("Let’s try again", systemImage: "externaldrive.badge.exclamationmark")
                } description: {
                    Text(startupError ?? String(localized: "The learning record is unavailable."))
                } actions: {
                    Button("Retry without deleting data") { retryOpeningStore() }
                    Button("Erase learning data and start fresh", role: .destructive) { confirmingErase = true }
                }
                .preferredColorScheme(.light)
                .appUiLanguage()
                .confirmationDialog("Erase all learning data on this iPhone?", isPresented: $confirmingErase, titleVisibility: .visible) {
                    Button("Erase learning data", role: .destructive) { eraseAndRestart() }
                } message: {
                    Text("This permanently removes every conversation, word and progress record on this iPhone. It cannot be undone.")
                }
            }
        }
    }
}
