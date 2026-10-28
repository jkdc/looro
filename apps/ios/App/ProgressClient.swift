import Foundation
import LearningCore

enum ProgressClientOutcome<Value: Sendable>: Sendable {
    case success(Value)
    case protocolUnavailable
    case cancelled
    case failure
}

struct ProgressPage: Sendable {
    let rows: [RemoteProgressRollup]
    let nextCursor: String?
    let complete: Bool
}

struct ProgressUploadCursor: Comparable, Equatable {
    let kind: String
    let itemID: String
    let language: String

    static func < (lhs: Self, rhs: Self) -> Bool {
        (lhs.kind, lhs.itemID, lhs.language) < (rhs.kind, rhs.itemID, rhs.language)
    }

    init(_ entry: ProgressSyncEntry) {
        kind = entry.kind; itemID = entry.itemID; language = entry.language
    }

    init?(legacyValue: String?) {
        guard let legacyValue else { return nil }
        let fields = legacyValue.split(separator: "|", omittingEmptySubsequences: false).map(String.init)
        guard fields.count == 3, fields.allSatisfy({ !$0.isEmpty }),
              ProgressEngine.kinds.contains(fields[0]),
              LanguageRegistry.all.contains(where: { $0.id == fields[2] }) else { return nil }
        kind = fields[0]; itemID = fields[1]; language = fields[2]
    }

    var encoded: String { "\(kind)|\(itemID)|\(language)" }

    static func pending(_ entries: [ProgressSyncEntry], after encodedCursor: String?) -> [ProgressSyncEntry] {
        guard let cursor = ProgressUploadCursor(legacyValue: encodedCursor) else { return entries }
        return entries.filter { cursor < ProgressUploadCursor($0) }
    }
}

/// Versioned progress transport. The adapter classifies only the server's exact
/// disabled-protocol response as a compatibility signal.
struct ProgressClient {
    private let configuration: ManagedAccountConfiguration
    private let http: any ManagedAccountRequestTransport

    init?(configuration: ManagedAccountConfiguration?, transport: any ManagedAccountRequestTransport = ManagedAccountHTTP()) {
        guard let configuration else { return nil }
        self.configuration = configuration
        self.http = transport
    }

    private struct ErrorEnvelope: Decodable { struct Detail: Decodable { let code: String }; let error: Detail }
    private struct UploadResponse: Decodable { let merged: Int }
    private struct PageWire: Decodable { let version: Int; let progress: [RowWire]; let nextCursor: String?; let complete: Bool }
    private struct RowWire: Decodable {
        let kind: String; let itemID: String; let firstAt: Int64; let lastAt: Int64
        let attempts: Int; let bestScore: Int; let language: String?
    }
    private struct EntryWire: Encodable {
        let kind: String; let itemID: String; let language: String
        let firstAt: Int64; let lastAt: Int64; let attempts: Int; let bestScore: Int
    }
    private struct UploadWire: Encodable { let version = 2; let entries: [EntryWire] }

    static func shouldBootstrap(localProgressExists: Bool, state: ProgressSyncState?) -> Bool {
        if let state { return state.passGeneration == 0 }
        return !localProgressExists
    }

    static func hasUserGeneratedProgress(sessions: Int, lessons: Int, completions: Int,
                                         expressions: Int, examAttempts: Int,
                                         preferences: Preferences) -> Bool {
        sessions > 0 || lessons > 0 || completions > 0 || expressions > 0 || examAttempts > 0 ||
            preferences.learningLanguageID != Preferences().learningLanguageID ||
            preferences.meaningVisible != Preferences().meaningVisible ||
            preferences.meaningLanguage != Preferences().meaningLanguage ||
            preferences.sessionMinutes != Preferences().sessionMinutes ||
            !preferences.hiddenWords.isEmpty || !preferences.interests.isEmpty ||
            preferences.hasOnboarded || preferences.aiConsentVersion != nil ||
            preferences.slowSpeech != nil || preferences.speechRate != nil ||
            preferences.listeningPractice != nil || preferences.usageMetricsEnabled != nil ||
            preferences.sttEngine != nil || preferences.pronunciationEnabled != nil
    }

    static func uploadBatches(_ entries: [ProgressSyncEntry]) -> [[ProgressSyncEntry]] {
        stride(from: 0, to: entries.count, by: 200).map { start in
            Array(entries[start..<min(start + 200, entries.count)])
        }
    }

    func upload(_ entries: [ProgressSyncEntry], bearer: String) async -> ProgressClientOutcome<Int> {
        guard entries.count <= 200, entries.allSatisfy({ $0.firstAtMilliseconds >= 0 &&
            $0.lastAtMilliseconds >= $0.firstAtMilliseconds && $0.lastAtMilliseconds <= 9_007_199_254_740_991 &&
            $0.attempts >= 1 && $0.attempts <= 10_000 && $0.bestScore >= 0 && $0.bestScore <= 100 }) else { return .failure }
        let body = UploadWire(entries: entries.map { EntryWire(kind: $0.kind, itemID: $0.itemID, language: $0.language,
            firstAt: $0.firstAtMilliseconds, lastAt: $0.lastAtMilliseconds,
            attempts: $0.attempts, bestScore: $0.bestScore) })
        guard let url = try? configuration.endpoint("/v1/progress"), let encoded = try? JSONEncoder().encode(body) else { return .failure }
        var request = request(url, bearer: bearer)
        request.httpMethod = "POST"; request.setValue("application/json", forHTTPHeaderField: "Content-Type"); request.httpBody = encoded
        switch await perform(request) {
        case .success(let data, let response):
            guard (200...299).contains(response.statusCode), let decoded = try? JSONDecoder().decode(UploadResponse.self, from: data),
                  decoded.merged == entries.count else { return .failure }
            return .success(decoded.merged)
        case .protocolUnavailable: return .protocolUnavailable
        case .cancelled: return .cancelled
        case .failure: return .failure
        }
    }

    func uploadLegacy(_ entries: [ProgressSyncEntry], bearer: String) async -> ProgressClientOutcome<Int> {
        guard entries.count <= 200 else { return .failure }
        struct LegacyEntry: Encodable { let kind: String; let itemID: String; let at: Int64; let attempts: Int; let bestScore: Int; let language: String }
        struct LegacyBatch: Encodable { let entries: [LegacyEntry] }
        let payload = LegacyBatch(entries: entries.map { LegacyEntry(kind: $0.kind, itemID: $0.itemID,
            at: $0.lastAtMilliseconds, attempts: $0.attempts, bestScore: $0.bestScore, language: $0.language) })
        guard let url = try? configuration.endpoint("/v1/progress"), let encoded = try? JSONEncoder().encode(payload) else { return .failure }
        var request = request(url, bearer: bearer); request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type"); request.httpBody = encoded
        switch await perform(request) {
        case .success(let data, let response):
            guard (200...299).contains(response.statusCode), let decoded = try? JSONDecoder().decode(UploadResponse.self, from: data),
                  decoded.merged == entries.count else { return .failure }
            return .success(decoded.merged)
        case .protocolUnavailable: return .protocolUnavailable
        case .cancelled: return .cancelled
        case .failure: return .failure
        }
    }

    func fetchPage(cursor: String?, bearer: String) async -> ProgressClientOutcome<ProgressPage> {
        guard let endpoint = try? configuration.endpoint("/v1/progress"),
              var components = URLComponents(url: endpoint, resolvingAgainstBaseURL: false) else { return .failure }
        components.queryItems = [URLQueryItem(name: "version", value: "2")]
        if let cursor { components.queryItems?.append(URLQueryItem(name: "cursor", value: cursor)) }
        guard let url = components.url else { return .failure }
        var request = request(url, bearer: bearer); request.httpMethod = "GET"
        switch await perform(request) {
        case .success(let data, let response):
            guard (200...299).contains(response.statusCode), let wire = try? JSONDecoder().decode(PageWire.self, from: data),
                  wire.version == 2, wire.progress.count <= 1000,
                  wire.complete == (wire.nextCursor == nil),
                  (wire.complete || (wire.nextCursor?.isEmpty == false && (wire.nextCursor?.count ?? 0) <= 2048)),
                  (wire.complete || wire.nextCursor != cursor) else { return .failure }
            let rows = wire.progress.map { row in RemoteProgressRollup(ownerAccountID: "", languageID: row.language,
                kind: row.kind, itemID: row.itemID, firstAtMilliseconds: row.firstAt, lastAtMilliseconds: row.lastAt,
                attempts: row.attempts, bestScore: row.bestScore, sourceVersion: 2) }
            guard rows.allSatisfy({ !$0.kind.isEmpty && !$0.itemID.isEmpty && $0.firstAtMilliseconds >= 0 &&
                $0.lastAtMilliseconds >= $0.firstAtMilliseconds && $0.lastAtMilliseconds <= 9_007_199_254_740_991 &&
                $0.attempts >= 1 && $0.attempts <= 10_000 && $0.bestScore >= 0 && $0.bestScore <= 100 }) else { return .failure }
            let keys = rows.map { "\($0.kind)|\($0.itemID)|\($0.languageID ?? "")" }
            guard Set(keys).count == keys.count, keys == keys.sorted() else { return .failure }
            return .success(ProgressPage(rows: rows, nextCursor: wire.nextCursor, complete: wire.complete))
        case .protocolUnavailable: return .protocolUnavailable
        case .cancelled: return .cancelled
        case .failure: return .failure
        }
    }

    /// V1 is retained only as an explicit compatibility path; it never returns a cursor.
    func fetchLegacy(bearer: String) async -> ProgressClientOutcome<[RemoteProgressRollup]> {
        guard let url = try? configuration.endpoint("/v1/progress") else { return .failure }
        var request = request(url, bearer: bearer); request.httpMethod = "GET"
        switch await perform(request) {
        case .success(let data, let response):
            struct Wire: Decodable { let progress: [Row] }; struct Row: Decodable {
                let kind: String; let itemID: String; let firstAt: Int64?; let lastAt: Int64?
                let at: Int64?; let attempts: Int; let bestScore: Int; let language: String?
            }
            guard (200...299).contains(response.statusCode), let wire = try? JSONDecoder().decode(Wire.self, from: data), wire.progress.count <= 1000 else { return .failure }
            let rows = wire.progress.compactMap { row -> RemoteProgressRollup? in
                guard !row.kind.isEmpty, !row.itemID.isEmpty,
                      let last = row.lastAt ?? row.at, let first = row.firstAt ?? row.at ?? row.lastAt,
                      first >= 0, last >= first, last <= 9_007_199_254_740_991,
                      row.attempts >= 1, row.attempts <= 10_000, row.bestScore >= 0, row.bestScore <= 100 else { return nil }
                return RemoteProgressRollup(ownerAccountID: "", languageID: row.language, kind: row.kind,
                    itemID: row.itemID, firstAtMilliseconds: first, lastAtMilliseconds: last,
                    attempts: row.attempts, bestScore: row.bestScore, sourceVersion: 1)
            }
            guard rows.count == wire.progress.count else { return .failure }
            return .success(rows)
        case .protocolUnavailable: return .protocolUnavailable
        case .cancelled: return .cancelled
        case .failure: return .failure
        }
    }

    // Kept for the coordinator's injectable compatibility adapter. Production
    // integration uses the typed v2 methods above.
    func send(_ entries: [ProgressSyncEntry], bearer: String) async -> Bool {
        if case .success = await upload(entries, bearer: bearer) { return true }
        return false
    }

    func fetch(bearer: String) async -> [RemoteProgress]? {
        switch await fetchLegacy(bearer: bearer) {
        case .success(let rows):
            return rows.map { RemoteProgress(kind: $0.kind, itemID: $0.itemID,
                firstAt: Double($0.firstAtMilliseconds) / 1000 - 978_307_200,
                lastAt: Double($0.lastAtMilliseconds) / 1000 - 978_307_200,
                attempts: $0.attempts, bestScore: $0.bestScore, language: $0.languageID) }
        case .protocolUnavailable, .cancelled, .failure: return nil
        }
    }

    private enum HTTPResult { case success(Data, HTTPURLResponse); case protocolUnavailable; case cancelled; case failure }
    private func perform(_ request: URLRequest) async -> HTTPResult {
        do {
            let (data, response) = try await http.send(request, limit: 1_000_000)
            if response.statusCode == 503,
               let envelope = try? JSONDecoder().decode(ErrorEnvelope.self, from: data),
               envelope.error.code == "progress_sync_version_unavailable" { return .protocolUnavailable }
            return .success(data, response)
        } catch is CancellationError { return .cancelled }
        catch let error as ManagedAccountError where error == .cancelled { return .cancelled }
        catch { return .failure }
    }

    private func request(_ url: URL, bearer: String) -> URLRequest {
        var request = URLRequest(url: url)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        return request
    }
}
