import Foundation
import XCTest
@testable import App
import LearningCore

private actor ProgressFixtureTransport: ManagedAccountRequestTransport {
    private var responseData: Data
    private var status: Int
    private(set) var requests: [URLRequest] = []

    init(status: Int, body: String) { self.status = status; responseData = Data(body.utf8) }
    func send(_ request: URLRequest, limit: Int) async throws -> (Data, HTTPURLResponse) {
        requests.append(request)
        return (responseData, HTTPURLResponse(url: request.url!, statusCode: status,
            httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!)
    }

    func uploadedItemIDs() -> [String] {
        requests.flatMap { request in
            guard let body = request.httpBody,
                  let payload = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
                  let entries = payload["entries"] as? [[String: Any]] else { return [] }
            return entries.compactMap { $0["itemID"] as? String }
        }
    }

    func requestedCursors() -> [String?] {
        requests.map { request in
            URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?
                .queryItems?.first(where: { $0.name == "cursor" })?.value
        }
    }
}

final class ProgressClientTests: XCTestCase {
    private func openProgressDatabase(_ url: URL, owner: String) throws -> SqlDatabase {
        let db = try LearningDatabase.open(at: url.path)
        _ = try LearningDbStore.open(database: db, activeIdentity: "member:\(owner)")
        return db
    }

    private func configuration() -> ManagedAccountConfiguration {
        try! ManagedAccountConfiguration(apiURL: "https://account-fixture.invalid", bundleID: "com.example.app",
            registeredURLSchemes: [], appleCapabilityEnabled: false)
    }

    func testAll201UploadsSurviveRestart() async throws {
        let rows = (0..<201).map { index in ProgressSyncEntry(kind: "lesson", itemID: "lesson-\(index)",
            firstAtMilliseconds: 1_792_929_600_000, lastAtMilliseconds: 1_792_929_600_000,
            attempts: 1, bestScore: 0, language: index.isMultiple(of: 2) ? "en" : "fr") }
            .sorted { ($0.kind, $0.itemID, $0.language) < ($1.kind, $1.itemID, $1.language) }
        let batches = ProgressClient.uploadBatches(rows)
        XCTAssertEqual([200, 1], batches.map(\.count))
        let owner = "account-1"
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("progress-upload-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: file) }
        var db = try openProgressDatabase(file, owner: owner)
        let firstTransport = ProgressFixtureTransport(status: 202, body: #"{"merged":200}"#)
        let firstClient = ProgressClient(configuration: configuration(), transport: firstTransport)!
        if case .success(200) = await firstClient.upload(batches[0], bearer: "fixture") {} else {
            XCTFail("First bounded batch must be acknowledged")
        }
        try LearningDbWriter.remoteProgressUploadAcknowledged(db, ownerAccountID: owner,
            nextCursor: ProgressUploadCursor(batches[0].last!).encoded, passGeneration: 0,
            updatedAtMilliseconds: 1_792_929_600_000)
        db.close()

        db = try openProgressDatabase(file, owner: owner)
        let persisted = try ProgressSyncStateDao.get(db, ownerAccountID: owner, direction: "upload")!
        let resumed = ProgressUploadCursor.pending(rows, after: persisted.cursor)
        XCTAssertEqual(Array(rows.dropFirst(200)).map(\.itemID), resumed.map(\.itemID))
        let secondTransport = ProgressFixtureTransport(status: 202, body: #"{"merged":1}"#)
        let secondClient = ProgressClient(configuration: configuration(), transport: secondTransport)!
        if case .success(1) = await secondClient.upload(resumed, bearer: "fixture") {} else {
            XCTFail("Resumed batch must be acknowledged")
        }
        try LearningDbWriter.remoteProgressUploadAcknowledged(db, ownerAccountID: owner,
            nextCursor: nil, passGeneration: 1, updatedAtMilliseconds: 1_792_929_600_001)
        let firstUploaded = await firstTransport.uploadedItemIDs()
        let resumedUploaded = await secondTransport.uploadedItemIDs()
        let uploaded = firstUploaded + resumedUploaded
        XCTAssertEqual(rows.map(\.itemID), uploaded)
        XCTAssertEqual(rows.count, Set(uploaded).count)
        db.close()
        db = try openProgressDatabase(file, owner: owner)
        let completed = try ProgressSyncStateDao.get(db, ownerAccountID: owner, direction: "upload")!
        XCTAssertNil(completed.cursor)
        XCTAssertEqual(1, completed.passGeneration)
        db.close()
    }

    func testOnlyExactUnavailableResponseDowngrades() async {
        let unavailable = ProgressFixtureTransport(status: 503,
            body: #"{"error":{"code":"progress_sync_version_unavailable"}}"#)
        let client = ProgressClient(configuration: configuration(), transport: unavailable)!
        if case .protocolUnavailable = await client.fetchPage(cursor: nil, bearer: "fixture") {} else {
            XCTFail("Exact protocol-disabled response must be classified distinctly")
        }
        let other = ProgressFixtureTransport(status: 503, body: #"{"error":{"code":"service_unavailable"}}"#)
        let otherClient = ProgressClient(configuration: configuration(), transport: other)!
        if case .failure = await otherClient.fetchPage(cursor: nil, bearer: "fixture") {} else {
            XCTFail("Unrelated 503 must not trigger compatibility fallback")
        }
    }

    func testMalformedPageLeavesCursorUnchanged() async {
        let transport = ProgressFixtureTransport(status: 200,
            body: #"{"version":2,"progress":[],"nextCursor":"same","complete":false}"#)
        let client = ProgressClient(configuration: configuration(), transport: transport)!
        if case .failure = await client.fetchPage(cursor: "same", bearer: "fixture") {} else {
            XCTFail("A page that makes no cursor progress must fail validation")
        }
    }

    func testAll1001DownloadsResumeFromPersistedCursorAfterDatabaseRestart() async throws {
        let owner = "account-1"
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("progress-download-\(UUID().uuidString).sqlite")
        defer { try? FileManager.default.removeItem(at: file) }
        var db = try openProgressDatabase(file, owner: owner)
        let ids = (0...1000).map { String(format: "lesson-%04d", $0) }
        func row(_ id: String) -> String {
            #"{"kind":"lesson","itemID":"\#(id)","firstAt":1792929600000,"lastAt":1792929600000,"attempts":1,"bestScore":0,"language":"en"}"#
        }
        let firstRows = ids.prefix(1000).map(row).joined(separator: ",")
        let first = ProgressFixtureTransport(status: 200,
            body: "{\"version\":2,\"progress\":[\(firstRows)],\"nextCursor\":\"page-1\",\"complete\":false}")
        let client = ProgressClient(configuration: configuration(), transport: first)!
        guard case .success(let page1) = await client.fetchPage(cursor: nil, bearer: "fixture") else {
            return XCTFail("Expected first page")
        }
        XCTAssertEqual(1000, page1.rows.count)
        try LearningDbWriter.remoteProgressPageApplied(db, ownerAccountID: owner, rollups: page1.rows,
            nextCursor: page1.nextCursor, passGeneration: 0, updatedAtMilliseconds: 1_792_929_600_000)
        db.close()

        db = try openProgressDatabase(file, owner: owner)
        let persisted = try ProgressSyncStateDao.get(db, ownerAccountID: owner, direction: "download")!
        XCTAssertEqual(page1.nextCursor, persisted.cursor)
        let malformed = ProgressFixtureTransport(status: 200,
            body: #"{"version":2,"progress":[{"kind":"lesson","itemID":"bad","firstAt":2,"lastAt":1,"attempts":1,"bestScore":0,"language":"en"}],"nextCursor":null,"complete":true}"#)
        let malformedClient = ProgressClient(configuration: configuration(), transport: malformed)!
        if case .failure = await malformedClient.fetchPage(cursor: persisted.cursor, bearer: "fixture") {} else {
            XCTFail("Invalid continuation page must fail without changing the durable cursor")
        }
        let unchanged = try ProgressSyncStateDao.get(db, ownerAccountID: owner, direction: "download")
        XCTAssertEqual(persisted, unchanged)
        let second = ProgressFixtureTransport(status: 200,
            body: "{\"version\":2,\"progress\":[\(row(ids.last!))],\"nextCursor\":null,\"complete\":true}")
        let lastClient = ProgressClient(configuration: configuration(), transport: second)!
        guard case .success(let page2) = await lastClient.fetchPage(cursor: persisted.cursor, bearer: "fixture") else {
            return XCTFail("Expected final page after the saved cursor")
        }
        XCTAssertTrue(page2.complete)
        XCTAssertEqual(1001, page1.rows.count + page2.rows.count)
        let requestedCursors = await second.requestedCursors()
        XCTAssertEqual([persisted.cursor], requestedCursors)
        try LearningDbWriter.remoteProgressPageApplied(db, ownerAccountID: owner, rollups: page2.rows,
            nextCursor: nil, passGeneration: 1, updatedAtMilliseconds: 1_792_929_600_001)
        db.close()

        db = try openProgressDatabase(file, owner: owner)
        let savedRows = try RemoteProgressRollupsDao.all(db, ownerAccountID: owner)
        let savedState = try ProgressSyncStateDao.get(db, ownerAccountID: owner, direction: "download")!
        XCTAssertEqual(Set(ids), Set(savedRows.map(\.itemID)))
        XCTAssertEqual(1001, savedRows.count)
        XCTAssertNil(savedState.cursor)
        XCTAssertEqual(1, savedState.passGeneration)
        db.close()
    }

    func testCompletedLegacyBootstrapMarkerPreventsAnotherFetch() {
        let state = ProgressSyncState(ownerAccountID: "account-1", direction: "download",
            cursor: "opaque-cursor", passGeneration: 1, updatedAtMilliseconds: 1_792_929_600_000)
        XCTAssertFalse(ProgressClient.shouldBootstrap(localProgressExists: false, state: state))
        XCTAssertEqual("opaque-cursor", state.cursor, "Compatibility completion must not advance the v2 cursor")
        XCTAssertTrue(ProgressClient.shouldBootstrap(localProgressExists: false, state: nil))
        XCTAssertFalse(ProgressClient.shouldBootstrap(localProgressExists: true, state: nil))
    }

    func testUploadResumeUsesTupleOrderingForPrefixIds() {
        let entries = ["a", "a-1", "b"].map { id in
            ProgressSyncEntry(kind: "lesson", itemID: id, firstAtMilliseconds: 1_792_929_600_000,
                lastAtMilliseconds: 1_792_929_600_000, attempts: 1, bestScore: 0, language: "en")
        }.sorted { ($0.kind, $0.itemID, $0.language) < ($1.kind, $1.itemID, $1.language) }
        let pending = ProgressUploadCursor.pending(entries, after: "lesson|a|en")
        XCTAssertEqual(["a-1", "b"], pending.map(\.itemID))
    }

    func testMalformedUploadCursorRestartsFromFirstEntry() {
        let entries = ["a", "b"].map { id in
            ProgressSyncEntry(kind: "lesson", itemID: id, firstAtMilliseconds: 1_792_929_600_000,
                lastAtMilliseconds: 1_792_929_600_000, attempts: 1, bestScore: 0, language: "en")
        }
        XCTAssertEqual(["a", "b"], ProgressUploadCursor.pending(entries, after: "z|bad")
            .map(\.itemID))
    }

    func testBootstrapEligibilityCountsEveryLocalProgressCategoryButNotDefaultPreferences() {
        let defaults = Preferences()
        XCTAssertFalse(ProgressClient.hasUserGeneratedProgress(sessions: 0, lessons: 0,
            completions: 0, expressions: 0, examAttempts: 0, preferences: defaults))
        for counts in [(1, 0, 0, 0, 0), (0, 1, 0, 0, 0), (0, 0, 1, 0, 0),
                       (0, 0, 0, 1, 0), (0, 0, 0, 0, 1)] {
            XCTAssertTrue(ProgressClient.hasUserGeneratedProgress(sessions: counts.0,
                lessons: counts.1, completions: counts.2, expressions: counts.3,
                examAttempts: counts.4, preferences: defaults))
        }
        var changedPreferences = defaults
        changedPreferences.meaningVisible = false
        XCTAssertTrue(ProgressClient.hasUserGeneratedProgress(sessions: 0, lessons: 0,
            completions: 0, expressions: 0, examAttempts: 0, preferences: changedPreferences))
    }
}
