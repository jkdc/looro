import Foundation

/// Closed usage-event catalog, mirrored from services/api/src/usage.ts (and the
/// Android UsageEvents object). The API rejects unknown names, unknown prop keys
/// and any free-text value, so an event cannot carry conversation content.
public enum UsageEvents {
    public static let appOpen = "app_open"
    public static let sessionStarted = "session_started"
    public static let sessionEnded = "session_ended"
    public static let sessionResumed = "session_resumed"
    public static let themeSelected = "theme_selected"
    public static let lessonStarted = "lesson_started"
    public static let lessonCompleted = "lesson_completed"
    public static let assessmentCompleted = "assessment_completed"
    public static let practiceCompleted = "practice_completed"
    public static let wordLookup = "word_lookup"
    public static let replayUsed = "replay_used"
    public static let ttsSpeedChanged = "tts_speed_changed"
    public static let helpRequested = "help_requested"
    public static let reportRequested = "report_requested"
    public static let paywallViewed = "paywall_viewed"

    public static let all: [String] = [appOpen, sessionStarted, sessionEnded, sessionResumed, themeSelected,
        lessonStarted, lessonCompleted, assessmentCompleted, practiceCompleted, wordLookup,
        replayUsed, ttsSpeedChanged, helpRequested, reportRequested, paywallViewed]
}

/// Coarse buckets shared with the Android core; never exact durations, counts or
/// scores, so the wire carries no reconstructable transcript shape.
public enum UsageBuckets {
    public static func duration(seconds: Int) -> Int {
        switch seconds {
        case ..<30: 0
        case ..<120: 1
        case ..<300: 2
        case ..<900: 3
        case ..<1800: 4
        default: 5
        }
    }

    public static func turns(userTurns: Int) -> Int {
        switch userTurns {
        case ..<3: 0
        case ..<10: 1
        case ..<20: 2
        case ..<40: 3
        case ..<60: 4
        default: 5
        }
    }

    public static func score(percent: Int) -> Int {
        switch percent {
        case 80...: 4
        case 60..<80: 3
        case 40..<60: 2
        case 20..<40: 1
        default: 0
        }
    }
}

public enum UsageProp: Sendable {
    case string(String)
    case int(Int)
    case bool(Bool)
}

public struct PendingUsageEvent: Sendable {
    public let id: UUID
    public let name: String
    public let at: Int64
    public var props: [String: UsageProp]

    public init(id: UUID, name: String, at: Int64, props: [String: UsageProp] = [:]) {
        self.id = id; self.name = name; self.at = at; self.props = props
    }
}

/// Wire envelope for POST /v1/usage/events. Key order is irrelevant to the server.
public func usageBatchJSON(platform: String, appVersion: String, events: [PendingUsageEvent]) -> String? {
    func propValue(_ prop: UsageProp) -> Any {
        switch prop {
        case .string(let value): value
        case .int(let value): value
        case .bool(let value): value
        }
    }
    let eventObjects: [[String: Any]] = events.map { event in
        var object: [String: Any] = ["id": event.id.uuidString.lowercased(), "name": event.name, "at": event.at]
        if !event.props.isEmpty {
            object["props"] = event.props.mapValues(propValue)
        }
        return object
    }
    let envelope: [String: Any] = ["platform": platform, "appVersion": appVersion, "events": eventObjects]
    guard JSONSerialization.isValidJSONObject(envelope),
          let data = try? JSONSerialization.data(withJSONObject: envelope, fragments: [])
    else { return nil }
    return String(data: data, encoding: .utf8)
}

/// Bounded queue of content-free usage events: age-pruned, capacity-capped,
/// order-preserving when a flush fails.
public final class UsageOutbox: @unchecked Sendable {
    private let lock = NSLock()
    private var pending: [PendingUsageEvent] = []
    private let capacity: Int
    private let ttlMilliseconds: Int64

    public init(capacity: Int = 2_000, ttlMilliseconds: Int64 = 7 * 24 * 60 * 60_000) {
        self.capacity = capacity
        self.ttlMilliseconds = ttlMilliseconds
    }

    public var size: Int { lock.withLock { pending.count } }
    public func snapshot() -> [PendingUsageEvent] { lock.withLock { pending } }

    public func record(id: UUID, name: String, at: Int64, props: [String: UsageProp] = [:]) {
        guard UsageEvents.all.contains(name) else { return }
        lock.withLock {
            guard !pending.contains(where: { $0.id == id }) else { return }
            pending.append(PendingUsageEvent(id: id, name: name, at: at, props: props))
            while pending.count > capacity { pending.removeFirst() }
        }
    }

    public func prune(nowMilliseconds: Int64) {
        lock.withLock { pending.removeAll { nowMilliseconds - $0.at > ttlMilliseconds } }
    }

    /// Removes the oldest events for sending; put them back with restore(_:) on failure.
    public func take(_ batch: Int) -> [PendingUsageEvent] {
        lock.withLock {
            let taken = Array(pending.prefix(batch))
            pending.removeFirst(taken.count)
            return taken
        }
    }

    public func restore(_ events: [PendingUsageEvent]) {
        lock.withLock { pending.insert(contentsOf: events.reversed(), at: 0) }
    }
}

/// Records and ships content-free usage events. Recording never throws and
/// never blocks a feature; a failed flush keeps events queued for next time.
public final class UsageReporter: @unchecked Sendable {
    public var enabled = false
    private let platform: String
    private let appVersion: String
    private let outbox: UsageOutbox
    private let send: (String, [PendingUsageEvent]) async -> Bool

    public init(platform: String, appVersion: String, outbox: UsageOutbox = UsageOutbox(),
                send: @escaping (String, [PendingUsageEvent]) async -> Bool) {
        self.platform = platform; self.appVersion = appVersion; self.outbox = outbox; self.send = send
    }

    public func record(_ name: String, props: [String: UsageProp] = [:]) {
        guard enabled else { return }
        outbox.record(id: UUID(), name: name, at: Int64(Date.now.timeIntervalSince1970 * 1000), props: props)
    }

    /// Returns true when every taken batch was delivered.
    public func flush(batch: Int = 100) async -> Bool {
        outbox.prune(nowMilliseconds: Int64(Date.now.timeIntervalSince1970 * 1000))
        var delivered = true
        while true {
            let events = outbox.take(batch)
            guard !events.isEmpty else { break }
            guard let body = usageBatchJSON(platform: platform, appVersion: appVersion, events: events),
                  await send(body, events)
            else {
                outbox.restore(events); delivered = false; break
            }
        }
        return delivered
    }
}
