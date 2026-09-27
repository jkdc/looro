import Foundation
import LearningCore

/// Ships content-free usage batches to the fixed managed origin. Failures are
/// ordinary booleans: telemetry must never surface as a user-facing error.
struct UsageClient {
    private let configuration: ManagedAccountConfiguration
    private let http = ManagedAccountHTTP()
    init?(configuration: ManagedAccountConfiguration?) {
        guard let configuration else { return nil }
        self.configuration = configuration
    }

    private struct AcceptedWire: Decodable { let accepted: Int }

    func send(_ batch: String, bearer: String) async -> Bool {
        guard let url = try? configuration.endpoint("/v1/usage/events") else { return false }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("no-store", forHTTPHeaderField: "Cache-Control")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        request.httpBody = Data(batch.utf8)
        guard let (data, response) = try? await http.send(request),
              (200 ... 299).contains(response.statusCode),
              let wire = try? JSONDecoder().decode(AcceptedWire.self, from: data)
        else { return false }
        return wire.accepted >= 0
    }
}
