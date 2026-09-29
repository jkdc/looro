import Foundation
import LearningCore

/// Ships progress rollups to the fixed managed origin and pulls the merged ledger
/// back. Failures are ordinary values: sync must never surface as a user-facing error.
struct ProgressClient {
    private let configuration: ManagedAccountConfiguration
    private let http = ManagedAccountHTTP()
    init?(configuration: ManagedAccountConfiguration?) {
        guard let configuration else { return nil }
        self.configuration = configuration
    }

    private struct MergedWire: Decodable { let merged: Int }
    private struct PullWire: Decodable { let progress: [RowWire] }
    private struct RowWire: Decodable {
        let kind: String
        let itemID: String
        let firstAt: Double
        let lastAt: Double
        let attempts: Int
        let bestScore: Int
        let language: String?
    }
    private struct EntryWire: Encodable {
        let kind: String
        let itemID: String
        let at: Int64
        let attempts: Int
        let bestScore: Int
        let language: String
    }
    private struct BatchWire: Encodable { let entries: [EntryWire] }

    func send(_ entries: [ProgressSyncEntry], bearer: String) async -> Bool {
        let batch = BatchWire(entries: entries.map {
            EntryWire(kind: $0.kind, itemID: $0.itemID, at: $0.atMilliseconds, attempts: $0.attempts,
                      bestScore: $0.bestScore, language: $0.language)
        })
        guard let url = try? configuration.endpoint("/v1/progress"),
              let body = try? JSONEncoder().encode(batch)
        else { return false }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        request.httpBody = body
        guard let (data, response) = try? await http.send(request),
              (200 ... 299).contains(response.statusCode),
              let wire = try? JSONDecoder().decode(MergedWire.self, from: data)
        else { return false }
        return wire.merged >= 0
    }

    func fetch(bearer: String) async -> [RemoteProgress]? {
        guard let url = try? configuration.endpoint("/v1/progress") else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        guard let (data, response) = try? await http.send(request),
              (200 ... 299).contains(response.statusCode),
              let wire = try? JSONDecoder().decode(PullWire.self, from: data)
        else { return nil }
        return wire.progress.map {
            RemoteProgress(kind: $0.kind, itemID: $0.itemID, firstAt: $0.firstAt, lastAt: $0.lastAt,
                           attempts: $0.attempts, bestScore: $0.bestScore, language: $0.language)
        }
    }
}
