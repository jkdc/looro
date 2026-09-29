import Foundation
import LearningCore

/// AI report submission, mirroring the Android client: credential-free,
/// 202 with the echoed reportID is success.
struct AIReportSubmission: Encodable, Equatable {
    enum Reason: String, CaseIterable, Identifiable {
        case offensive, incorrect
        case wrongLanguage = "wrong_language"
        case other
        var id: String { rawValue }
        var label: String {
            switch self {
            case .offensive: "Offensive or hurtful"
            case .incorrect: "Incorrect or misleading"
            case .wrongLanguage: "Wrong language"
            case .other: "Something else"
            }
        }
    }

    let reportID = UUID()
    let languageID: String
    let reason: Reason
    let excerpt: String
    let consentVersion = "ai-report-v1"

    init(languageID: String, reason: Reason, excerpt: String) {
        self.languageID = languageID
        self.reason = reason
        self.excerpt = String(excerpt.prefix(2_000))
    }
}

struct ReportClient {
    private let configuration: ManagedAccountConfiguration
    private let http = ManagedAccountHTTP()
    init?(configuration: ManagedAccountConfiguration?) {
        guard let configuration else { return nil }
        self.configuration = configuration
    }

    func capabilities() async throws -> Bool {
        struct Wire: Decodable { let aiReports: Bool }
        let request = try make("/v1/feedback/capabilities", body: nil)
        let (data, response) = try await http.send(request)
        guard (200...299).contains(response.statusCode),
              let wire = try? JSONDecoder().decode(Wire.self, from: data)
        else { return false }
        return wire.aiReports
    }

    /// Returns the echoed report identifier on acceptance (HTTP 202).
    func submit(_ submission: AIReportSubmission) async throws -> UUID {
        struct Echo: Decodable { let accepted: Bool; let reportID: UUID }
        let body = try JSONEncoder().encode(submission)
        let request = try make("/v1/feedback/ai", body: body)
        let (data, response) = try await http.send(request)
        guard response.statusCode == 202,
              let echo = try? JSONDecoder().decode(Echo.self, from: data),
              echo.accepted, echo.reportID == submission.reportID
        else { throw ReportError.notAccepted }
        return echo.reportID
    }

    private func make(_ path: String, body: Data?) throws -> URLRequest {
        var request = URLRequest(url: try configuration.endpoint(path))
        request.httpMethod = body == nil ? "GET" : "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = body
        }
        return request
    }

    enum ReportError: LocalizedError {
        case notAccepted
        var errorDescription: String? {
            switch self {
            case .notAccepted: "The report couldn’t be submitted right now. Please try again later."
            }
        }
    }
}

/// Report flow for an assistant passage: reason, editable excerpt (≤2000
/// characters, prefilled), consent, submit. Mirrors the Android sheet.
struct ReportSheet: View {
    let languageID: String
    let passage: Passage
    @Environment(\.dismiss) private var dismiss
    @State private var reason = AIReportSubmission.Reason.incorrect
    @State private var excerpt = ""
    @State private var consented = false
    @State private var submitting = false
    @State private var submitted = false
    @State private var message: String?
    private let client = ReportClient(configuration: ManagedAccountConfiguration.load())

    var body: some View {
        NavigationStack {
            Form {
                if submitted {
                    Text("Thank you. The report was sent for review.")
                        .font(.body).foregroundStyle(AppColor.secondary)
                } else {
                    Section("Why are you reporting this?") {
                        Picker("Reason", selection: $reason) {
                            ForEach(AIReportSubmission.Reason.allCases) { reason in
                                Text(reason.label).tag(reason)
                            }
                        }.pickerStyle(.inline).labelsHidden()
                    }
                    Section("What was said") {
                        TextField("Excerpt", text: $excerpt, axis: .vertical).lineLimit(4...8)
                        Text("\(excerpt.count)/2000").font(.caption2).foregroundStyle(AppColor.secondary)
                    }
                    Section {
                        Toggle("I agree to send this excerpt for review", isOn: $consented)
                        Button(submitting ? "Sending…" : "Send report") { submit() }
                            .disabled(!consented || submitting || excerpt.trimmingCharacters(in: .whitespaces).isEmpty)
                        if let message { Text(message).font(.footnote).foregroundStyle(AppColor.secondary) }
                    }
                }
            }.navigationTitle("Report").navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button(submitted ? "Done" : "Cancel") { dismiss() } }
                }
        }.presentationDetents([.medium, .large])
    }

    private func submit() {
        submitting = true; message = nil
        Task {
            defer { submitting = false }
            guard let client else { message = ReportClient.ReportError.notAccepted.localizedDescription; return }
            do {
                _ = try await client.submit(AIReportSubmission(languageID: languageID, reason: reason, excerpt: excerpt))
                submitted = true
            } catch { message = error.localizedDescription }
        }
    }
}
