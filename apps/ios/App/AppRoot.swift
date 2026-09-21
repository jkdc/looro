import SwiftUI

@main struct LooroApp: App {
    @State private var store: LearningStore?
    @State private var startupError: String?
    @State private var confirmingErase = false
    init() {
        do { _store = State(initialValue: try makeStore()) }
        catch { _startupError = State(initialValue: "Looro couldn’t open its learning record. Your existing data has not been replaced.") }
    }
    private func makeStore() throws -> LearningStore {
        #if DEBUG
        return try LearningStore(inMemory: ProcessInfo.processInfo.arguments.contains("--preview") || AudioVerification.requested)
        #else
        return try LearningStore(inMemory: false)
        #endif
    }
    /// Reachable only through the confirmation dialog below; the damaged record is the one thing erased.
    private func eraseAndRestart() {
        do { try LearningStore.eraseStore() }
        catch { startupError = "Looro couldn’t erase its learning data. Please try again."; return }
        do { store = try makeStore(); startupError = nil }
        catch { startupError = "Looro couldn’t open a fresh learning record. Please try again." }
    }
    var body: some Scene {
        WindowGroup {
            if let store { RootView(store: store).preferredColorScheme(.light) }
            else {
                ContentUnavailableView {
                    Label("Let’s try again", systemImage: "externaldrive.badge.exclamationmark")
                } description: {
                    Text(startupError ?? "The learning record is unavailable.")
                } actions: {
                    Button("Erase learning data and start fresh", role: .destructive) { confirmingErase = true }
                }
                .preferredColorScheme(.light)
                .confirmationDialog("Erase all learning data on this iPhone?", isPresented: $confirmingErase, titleVisibility: .visible) {
                    Button("Erase learning data", role: .destructive) { eraseAndRestart() }
                } message: {
                    Text("This permanently removes every conversation, word and progress record on this iPhone. It cannot be undone.")
                }
            }
        }
    }
}
