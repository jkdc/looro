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
}

final class ProgressClientTests: XCTestCase {
    private func configuration() -> ManagedAccountConfiguration {
        try! ManagedAccountConfiguration(apiURL: "https://account-fixture.invalid", bundleID: "com.example.app",
            registeredURLSchemes: [], appleCapabilityEnabled: false)
    }

    func testAll201UploadsSurviveRestart() async {
        let rows = (0..<201).map { index in ProgressSyncEntry(kind: "lesson", itemID: "lesson-\(index)",
            firstAtMilliseconds: 1_792_929_600_000, lastAtMilliseconds: 1_792_929_600_000,
            attempts: 1, bestScore: 0, language: index.isMultiple(of: 2) ? "en" : "fr") }
            .sorted { ($0.kind, $0.itemID, $0.language) < ($1.kind, $1.itemID, $1.language) }
        let batches = ProgressClient.uploadBatches(rows)
        XCTAssertEqual([200, 1], batches.map(\.count))
        let first = ProgressSyncState(ownerAccountID: "account-1", direction: "upload",
            cursor: "\(batches[0].last!.kind)|\(batches[0].last!.itemID)|\(batches[0].last!.language)",
            passGeneration: 0, updatedAtMilliseconds: 1_792_929_600_000)
        let resumed = Array(rows.filter { "\($0.kind)|\($0.itemID)|\($0.language)" > (first.cursor ?? "") })
        XCTAssertEqual(["lesson-200"], resumed.map(\.itemID))
        let firstTransport = ProgressFixtureTransport(status: 202, body: #"{"merged":200}"#)
        let firstClient = ProgressClient(configuration: configuration(), transport: firstTransport)!
        if case .success(200) = await firstClient.upload(batches[0], bearer: "fixture") {} else {
            XCTFail("First bounded batch must be acknowledged")
        }
        let secondTransport = ProgressFixtureTransport(status: 202, body: #"{"merged":1}"#)
        let secondClient = ProgressClient(configuration: configuration(), transport: secondTransport)!
        if case .success(1) = await secondClient.upload(resumed, bearer: "fixture") {} else {
            XCTFail("Resumed batch must be acknowledged")
        }
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

    func testAll1001DownloadsCanAdvanceAcrossPages() async {
        let ids = (0...1000).map { "lesson-\($0)" }.sorted()
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
        let second = ProgressFixtureTransport(status: 200,
            body: "{\"version\":2,\"progress\":[\(row(ids.last!))],\"nextCursor\":null,\"complete\":true}")
        let lastClient = ProgressClient(configuration: configuration(), transport: second)!
        guard case .success(let page2) = await lastClient.fetchPage(cursor: page1.nextCursor, bearer: "fixture") else {
            return XCTFail("Expected final page after the saved cursor")
        }
        XCTAssertTrue(page2.complete)
        XCTAssertEqual(1001, page1.rows.count + page2.rows.count)
    }

    func testCompletedLegacyBootstrapMarkerPreventsAnotherFetch() {
        let state = ProgressSyncState(ownerAccountID: "account-1", direction: "download",
            cursor: "opaque-cursor", passGeneration: 1, updatedAtMilliseconds: 1_792_929_600_000)
        XCTAssertFalse(ProgressClient.shouldBootstrap(localProgressExists: false, state: state))
        XCTAssertEqual("opaque-cursor", state.cursor, "Compatibility completion must not advance the v2 cursor")
        XCTAssertTrue(ProgressClient.shouldBootstrap(localProgressExists: false, state: nil))
        XCTAssertFalse(ProgressClient.shouldBootstrap(localProgressExists: true, state: nil))
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
