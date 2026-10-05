import XCTest
@testable import LearningCore

/// The durable usage_outbox drain through UsageReporter.flush — the Swift
/// mirror of Android's `UsageMetricsTest` drain cases: durable rows upload in
/// the same POST batches as the in-memory queue, mark dispatched only after a
/// successful send, stay pending on failure, read ≤200 rows per flush and
/// never make a POST exceed the server's 100-event batch cap.
final class UsageMetricsTests: XCTestCase {
    /// A random install uuid as the meta 'install_id' row stores it.
    private let installA = "0f3e9d2c-1b4a-4c8d-9e2f-a1b2c3d4e5f6"

    /// The durable-outbox stub: rows leave `rows` only through markDispatched,
    /// exactly like DB rows leave dispatched = 0.
    private final class DurableStub {
        var rows: [DurableUsageEvent] = []
        var dispatched: [Int64] = []
        var requestedCap = 0
        var nextRowId: Int64 = 1

        func enqueue(_ name: String, _ props: [String: UsageProp] = [:],
                     installID: String = "0f3e9d2c-1b4a-4c8d-9e2f-a1b2c3d4e5f6") {
            let rowId = nextRowId; nextRowId += 1
            rows.append(DurableUsageEvent(rowId: rowId, event: PendingUsageEvent(
                id: UUID(uuidString: durableUsageEventID(installID: installID, rowId: rowId))!,
                name: name, at: 5_000, props: props)))
        }

        var outbox: DurableUsageOutbox {
            DurableUsageOutbox(pending: { [self] cap in
                requestedCap = cap
                return Array(rows.prefix(cap))
            }, markDispatched: { [self] rowIds in
                dispatched.append(contentsOf: rowIds)
                rows.removeAll { rowIds.contains($0) }
            })
        }
    }

    private static let uuidV4 = try! NSRegularExpression(
        pattern: "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    private func isUUIDV4(_ id: String) -> Bool {
        let range = NSRange(id.startIndex..., in: id)
        return Self.uuidV4.firstMatch(in: id, range: range) != nil
    }

    private func stringProp(_ props: [String: UsageProp]?, _ key: String) -> String? {
        guard case .string(let value)? = props?[key] else { return nil }
        return value
    }

    func testDurableIdsFoldTheInstallIntoTheFreeHexGroupsAndStayGloballyUnique() {
        // the server's client-id unique index spans ALL installs: the free
        // groups must carry a per-install random component, not just the rowid
        let installB = "7c2a1b0d-3e4f-4a5b-8c9d-0e1f2a3b4c5d"
        let a = durableUsageEventID(installID: installA, rowId: 1)
        let b = durableUsageEventID(installID: installB, rowId: 1)
        XCTAssertNotEqual(a, b) // two fresh installs' rowid 1 can no longer collide
        XCTAssertEqual(a, durableUsageEventID(installID: installA, rowId: 1)) // stable per (install, row)
        XCTAssertTrue(isUUIDV4(a.lowercased())) // the server accepts UUID-V4 ids only
        XCTAssertTrue(isUUIDV4(b.lowercased()))
        XCTAssertEqual("0f3e9d2c-1b4a", String(a.prefix(13))) // install hex in the free groups...
        XCTAssertEqual("7c2a1b0d-3e4f", String(b.prefix(13)))
        XCTAssertEqual("-4000-8000-", String(a.dropFirst(13).prefix(11))) // ...markers fixed...
        XCTAssertEqual("000000000001", String(a.suffix(12))) // ...rowid in the tail
    }

    func testFlushUploadsDurableRowsAndMarksThemDispatchedWithoutAnInMemoryRecord() async {
        let durable = DurableStub()
        durable.enqueue(UsageEvents.archiveHealth,
                        ["dbBytesBucket": .string("1-10mb"), "sessionsBucket": .string("1-10")])
        durable.enqueue(UsageEvents.appOpen)
        durable.enqueue(UsageEvents.sessionResumed)
        var batches: [[PendingUsageEvent]] = []
        let reporter = UsageReporter(platform: "ios", appVersion: "1.0 (1)",
            durable: durable.outbox,
            send: { _, events in batches.append(events); return true })
        let delivered = await reporter.flush()
        // one POST with the three durable rows (rowid order), the in-memory
        // queue never involved: archive_health rides its durable row alone
        XCTAssertTrue(delivered)
        XCTAssertEqual(1, batches.count)
        XCTAssertEqual(["archive_health", "app_open", "session_resumed"],
                       batches.first?.map(\.name))
        XCTAssertEqual("1-10mb", stringProp(batches.first?.first?.props, "dbBytesBucket"))
        XCTAssertEqual("1-10", stringProp(batches.first?.first?.props, "sessionsBucket"))
        XCTAssertTrue((batches.first ?? []).allSatisfy { isUUIDV4($0.id.uuidString.lowercased()) },
                      "the server accepts UUID-V4 ids only")
        // all three marked dispatched; nothing left pending for the next flush
        XCTAssertEqual([1, 2, 3], durable.dispatched)
        XCTAssertTrue(durable.rows.isEmpty)
    }

    func testFailedFlushKeepsDurableRowsPendingAndTheNextFlushRetriesThem() async {
        let durable = DurableStub()
        for _ in 0..<3 { durable.enqueue(UsageEvents.appOpen) }
        var succeed = false
        var batches: [[PendingUsageEvent]] = []
        let reporter = UsageReporter(platform: "ios", appVersion: "1.0 (1)",
            durable: durable.outbox,
            send: { _, events in batches.append(events); return succeed })
        let first = await reporter.flush() // send fails -> rows stay pending
        XCTAssertFalse(first)
        XCTAssertTrue(durable.dispatched.isEmpty)
        XCTAssertEqual(3, durable.rows.count)
        succeed = true
        let second = await reporter.flush() // the next flush retries all three
        XCTAssertTrue(second)
        XCTAssertEqual(2, batches.count)
        XCTAssertEqual(3, batches.last?.count)
        XCTAssertEqual([1, 2, 3], durable.dispatched)
        XCTAssertTrue(durable.rows.isEmpty)
    }

    func testDurableDrainReadsAtMostTwoHundredRowsAndPostsAtMostOneHundredEach() async {
        let durable = DurableStub()
        for _ in 0..<250 { durable.enqueue(UsageEvents.appOpen) }
        var batches: [[PendingUsageEvent]] = []
        let reporter = UsageReporter(platform: "ios", appVersion: "1.0 (1)",
            durable: durable.outbox,
            send: { _, events in batches.append(events); return true })
        let delivered = await reporter.flush()
        XCTAssertTrue(delivered)
        XCTAssertEqual(UsageReporter.durableBatch, durable.requestedCap) // one capped read per flush
        XCTAssertEqual([100, 100], batches.map(\.count)) // ≤ the server's batch cap
        XCTAssertEqual(200, durable.dispatched.count)
        XCTAssertEqual(50, durable.rows.count) // the tail waits for a later flush
    }

    func testThePostCapIsSharedBetweenInMemoryAndDurableRows() async {
        let box = UsageOutbox()
        let durable = DurableStub()
        durable.enqueue(UsageEvents.appOpen)
        var batches: [[PendingUsageEvent]] = []
        let reporter = UsageReporter(platform: "ios", appVersion: "1.0 (1)", outbox: box,
            durable: durable.outbox,
            send: { _, events in batches.append(events); return true })
        reporter.enabled = true
        for _ in 0..<100 { reporter.record(UsageEvents.sessionResumed) } // fills a whole batch
        let delivered = await reporter.flush()
        XCTAssertTrue(delivered)
        // first POST: the 100 in-memory events, 0 durable (cap shared); the
        // durable row rides the next POST and gets marked with it
        XCTAssertEqual([100, 1], batches.map(\.count))
        XCTAssertEqual("app_open", batches.last?.first?.name)
        XCTAssertEqual([1], durable.dispatched)
        XCTAssertEqual(0, box.size)
    }
}
