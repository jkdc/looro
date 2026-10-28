import XCTest
@testable import App
import LearningCore
import Speech

private enum PronunciationFixtureError: Error {
    case recognitionFailed
}

private struct PronunciationFixtureTimeout {
    let delay: TimeInterval
    let callback: () -> Void
}

@MainActor private final class PronunciationFixture {
    var speechStatus: SFSpeechRecognizerAuthorizationStatus = .authorized
    var microphoneGranted = true
    var recordPermissionRequests = 0
    var recorderCanStart = true
    var recognizerAvailable = true
    var supportsOnDeviceRecognition = true
    var recognitionRequests = 0
    var requiresOnDeviceValues: [Bool] = []
    var recorderCreations = 0
    var recorderStarts = 0
    var recorderStops = 0
    var audioActivations = 0
    var audioReleases = 0
    var fileURLs: [URL] = []
    var removals: [URL] = []
    var timeouts: [PronunciationFixtureTimeout] = []
    var recognitionCallbacks: [(String?, Bool, Error?) -> Void] = []
    var recognitionTasks: [PronunciationFixtureCancellation] = []

    lazy var dependencies = PronunciationCheckDependencies(
        requestSpeechAuthorization: { completion in completion(self.speechStatus) },
        requestRecordPermission: { completion in
            self.recordPermissionRequests += 1
            completion(self.microphoneGranted)
        },
        makeRecorder: { url in
            self.recorderCreations += 1
            self.fileURLs.append(url)
            _ = FileManager.default.createFile(atPath: url.path, contents: Data())
            return PronunciationFixtureRecorder(fixture: self)
        },
        makeRecognizer: { _ in
            PronunciationFixtureRecognizer(fixture: self)
        },
        audioSession: PronunciationAudioSession(
            activate: { self.audioActivations += 1 },
            release: { self.audioReleases += 1 }),
        schedule: { delay, callback in
            self.timeouts.append(PronunciationFixtureTimeout(delay: delay, callback: callback))
            return PronunciationFixtureCancellation()
        },
        temporaryDirectory: FileManager.default.temporaryDirectory,
        removeFile: {
            self.removals.append($0)
            try? FileManager.default.removeItem(at: $0)
        })

    func completeRecognition(_ index: Int = 0, transcript: String? = "hello there", final: Bool = true,
                             error: Error? = nil) {
        recognitionCallbacks[index](transcript, final, error)
    }

    func fireRecordingTimeout(attempt: Int) {
        timeouts.filter { $0.delay == 4 }[attempt - 1].callback()
    }

    func fireRecognitionTimeout(attempt: Int) {
        timeouts.filter { $0.delay == 10 }[attempt - 1].callback()
    }
}

@MainActor private final class PronunciationFixtureRecorder: PronunciationRecording {
    let fixture: PronunciationFixture
    init(fixture: PronunciationFixture) { self.fixture = fixture }
    func record() -> Bool { fixture.recorderStarts += 1; return fixture.recorderCanStart }
    func stop() { fixture.recorderStops += 1 }
}

@MainActor private final class PronunciationFixtureRecognizer: PronunciationRecognizing {
    let fixture: PronunciationFixture
    init(fixture: PronunciationFixture) { self.fixture = fixture }
    var isAvailable: Bool { fixture.recognizerAvailable }
    var supportsOnDeviceRecognition: Bool { fixture.supportsOnDeviceRecognition }
    func recognize(url: URL, requiresOnDevice: Bool,
                   completion: @escaping (String?, Bool, Error?) -> Void) -> PronunciationRecognitionTask? {
        fixture.recognitionRequests += 1
        fixture.requiresOnDeviceValues.append(requiresOnDevice)
        fixture.recognitionCallbacks.append(completion)
        let task = PronunciationFixtureCancellation()
        fixture.recognitionTasks.append(task)
        return task
    }
}

@MainActor private final class PronunciationFixtureCancellation: PronunciationRecognitionTask {
    private(set) var cancelCount = 0
    func cancel() { cancelCount += 1 }
}

@MainActor final class PronunciationCheckControllerTests: XCTestCase {
    private func settle() async {
        for _ in 0..<5 { await Task.yield() }
    }

    func testDeniedPermissionNeverRecords() async throws {
        let fixture = PronunciationFixture()
        fixture.microphoneGranted = false
        let controller = PronunciationCheckController(dependencies: fixture.dependencies)

        controller.check(target: "hello there", lessonID: "lesson-fixture", language: "en",
                         store: try LearningStore(inMemory: true), onPass: { _ in XCTFail("denied audio must not score") })
        await settle()

        XCTAssertEqual(fixture.recorderCreations, 0)
        XCTAssertEqual(fixture.recorderStarts, 0)
        XCTAssertEqual(controller.state, .failed("Microphone permission was denied."))
        XCTAssertEqual(fixture.removals.count, 0)
    }

    func testDeniedSpeechAuthorizationNeverRequestsMicrophone() async throws {
        let fixture = PronunciationFixture()
        fixture.speechStatus = .denied
        let controller = PronunciationCheckController(dependencies: fixture.dependencies)

        controller.check(target: "hello there", lessonID: "lesson-fixture", language: "en",
                         store: try LearningStore(inMemory: true), onPass: { _ in XCTFail("denied speech must not score") })
        await settle()

        XCTAssertEqual(fixture.recordPermissionRequests, 0)
        XCTAssertEqual(fixture.recorderStarts, 0)
        XCTAssertNotNil(controller.statusMessage)
    }

    func testRecognizerErrorSettlesBusy() async throws {
        let fixture = PronunciationFixture()
        let controller = PronunciationCheckController(dependencies: fixture.dependencies)
        controller.check(target: "hello there", lessonID: "lesson-fixture", language: "en",
                         store: try LearningStore(inMemory: true), onPass: { _ in XCTFail("failed recognition must not score") })
        await settle()
        fixture.fireRecordingTimeout(attempt: 1)
        fixture.completeRecognition(transcript: nil, error: PronunciationFixtureError.recognitionFailed)
        await settle()

        XCTAssertEqual(controller.state, .failed("Pronunciation could not be checked. Please try again."))
        XCTAssertNil(controller.listeningTarget)
        XCTAssertNil(controller.result)
        XCTAssertEqual(fixture.recorderStops, 1)
        XCTAssertEqual(fixture.removals.count, 1)
        XCTAssertTrue(fixture.fileURLs.allSatisfy { !FileManager.default.fileExists(atPath: $0.path) })
        XCTAssertEqual(fixture.audioReleases, 1)
        XCTAssertEqual(fixture.recognitionTasks.first?.cancelCount, 1)
    }

    func testRecordingFailureSettlesAndReleasesOwnedAudio() async throws {
        let fixture = PronunciationFixture()
        fixture.recorderCanStart = false
        let controller = PronunciationCheckController(dependencies: fixture.dependencies)
        controller.check(target: "hello there", lessonID: "lesson-fixture", language: "en",
                         store: try LearningStore(inMemory: true), onPass: { _ in XCTFail("recording failure must not score") })
        await settle()

        XCTAssertFalse(controller.state.isBusy)
        XCTAssertEqual(fixture.recorderStops, 1)
        XCTAssertEqual(fixture.removals.count, 1)
        XCTAssertEqual(fixture.audioReleases, 1)
    }

    func testTimeoutAndCancelCleanExactlyOnce() async throws {
        let fixture = PronunciationFixture()
        let controller = PronunciationCheckController(dependencies: fixture.dependencies)
        controller.check(target: "hello there", lessonID: "lesson-fixture", language: "en",
                         store: try LearningStore(inMemory: true), onPass: { _ in XCTFail("cancelled attempt must not score") })
        await settle()

        fixture.fireRecordingTimeout(attempt: 1)
        controller.cancel()
        controller.cancel()

        XCTAssertEqual(fixture.recorderStops, 1)
        XCTAssertEqual(fixture.removals, fixture.fileURLs)
        XCTAssertTrue(fixture.fileURLs.allSatisfy { !FileManager.default.fileExists(atPath: $0.path) })
        XCTAssertEqual(fixture.audioReleases, 1)
        XCTAssertEqual(fixture.recognitionTasks.first?.cancelCount, 1)
        XCTAssertNil(controller.listeningTarget)
        XCTAssertEqual(controller.state, .idle)
    }

    func testRecognitionTimeoutFailsWithoutScoringAndAllowsRetry() async throws {
        let fixture = PronunciationFixture()
        let store = try LearningStore(inMemory: true)
        let controller = PronunciationCheckController(dependencies: fixture.dependencies)
        var passed: [Int] = []

        controller.check(target: "hello there", lessonID: "lesson-fixture", language: "en", store: store) { passed.append($0) }
        await settle()
        fixture.fireRecordingTimeout(attempt: 1)
        XCTAssertEqual(controller.state, .recognizing)
        fixture.fireRecognitionTimeout(attempt: 1)

        XCTAssertEqual(controller.state, .failed("Pronunciation check timed out. Please try again."))
        XCTAssertTrue(passed.isEmpty)
        XCTAssertEqual(fixture.recorderStops, 1)
        XCTAssertEqual(fixture.recognitionTasks.first?.cancelCount, 1)
        XCTAssertEqual(fixture.removals.count, 1)
        XCTAssertTrue(fixture.fileURLs.allSatisfy { !FileManager.default.fileExists(atPath: $0.path) })
        XCTAssertEqual(fixture.audioReleases, 1)

        controller.check(target: "hello there", lessonID: "lesson-fixture", language: "en", store: store) { passed.append($0) }
        await settle()
        fixture.fireRecordingTimeout(attempt: 2)
        fixture.completeRecognition(1)
        await settle()
        XCTAssertEqual(passed.count, 1)
        XCTAssertEqual(controller.state, .result)
    }

    func testLateCallbackCannotScoreNextAttempt() async throws {
        let fixture = PronunciationFixture()
        let store = try LearningStore(inMemory: true)
        let controller = PronunciationCheckController(dependencies: fixture.dependencies)
        var passed: [Int] = []
        controller.check(target: "hello there", lessonID: "lesson-fixture", language: "en", store: store) { passed.append($0) }
        await settle()
        fixture.fireRecordingTimeout(attempt: 1)
        let oldCallbackIndex = 0
        controller.cancel()

        controller.check(target: "hello there", lessonID: "lesson-fixture", language: "en", store: store) { passed.append($0) }
        await settle()
        fixture.fireRecordingTimeout(attempt: 2)
        fixture.completeRecognition(oldCallbackIndex)
        await settle()
        XCTAssertEqual(controller.state, .recognizing)
        XCTAssertTrue(passed.isEmpty)

        fixture.completeRecognition(1)
        await settle()
        XCTAssertEqual(passed.count, 1)
        XCTAssertEqual(controller.state, .result)
        XCTAssertEqual(Set(fixture.fileURLs).count, 2)
        XCTAssertEqual(fixture.removals.count, 2)
        XCTAssertTrue(fixture.fileURLs.allSatisfy { !FileManager.default.fileExists(atPath: $0.path) })
        XCTAssertEqual(fixture.recognitionTasks.map(\.cancelCount), [1, 1])
    }

    func testOwnerSwitchFencesStalePronunciationAndAllowsCurrentOwnerRetry() async throws {
        let fixture = PronunciationFixture()
        let store = try LearningStore(inMemory: true)
        store.activateLearnerIdentity("account-a")
        let controller = PronunciationCheckController(dependencies: fixture.dependencies)
        var passed: [Int] = []

        controller.check(target: "hello there", lessonID: "lesson-fixture", language: "en", store: store) { passed.append($0) }
        await settle()
        fixture.fireRecordingTimeout(attempt: 1)
        store.activateLearnerIdentity("account-b")
        await settle()
        fixture.completeRecognition(0)
        await settle()

        XCTAssertEqual(controller.state, .idle)
        XCTAssertTrue(passed.isEmpty)
        XCTAssertTrue((store.archive.completions ?? []).isEmpty)

        controller.check(target: "hello there", lessonID: "lesson-fixture", language: "en", store: store) { passed.append($0) }
        await settle()
        fixture.fireRecordingTimeout(attempt: 2)
        fixture.completeRecognition(1)
        await settle()

        XCTAssertEqual(controller.state, .result)
        XCTAssertEqual(passed.count, 1)
        XCTAssertEqual(store.archive.completions?.filter { $0.kind == ProgressEngine.pronunciation }.count, 1)
    }

    func testReturningToSameOwnerDoesNotReviveOldPronunciationAttempt() async throws {
        let fixture = PronunciationFixture()
        let store = try LearningStore(inMemory: true)
        store.activateLearnerIdentity("account-a")
        let controller = PronunciationCheckController(dependencies: fixture.dependencies)
        var passed: [Int] = []

        controller.check(target: "hello there", lessonID: "lesson-fixture", language: "en", store: store) { passed.append($0) }
        await settle()
        fixture.fireRecordingTimeout(attempt: 1)
        store.activateLearnerIdentity("account-b")
        store.activateLearnerIdentity("account-a")
        await settle()
        fixture.completeRecognition(0)
        await settle()

        XCTAssertEqual(controller.state, .idle)
        XCTAssertTrue(passed.isEmpty)
        XCTAssertTrue((store.archive.completions ?? []).isEmpty)
    }

    func testDataDeletionFencesSuspendedPronunciationResult() async throws {
        let fixture = PronunciationFixture()
        let store = try LearningStore(inMemory: true)
        let controller = PronunciationCheckController(dependencies: fixture.dependencies)
        var passed: [Int] = []

        controller.check(target: "hello there", lessonID: "lesson-fixture", language: "en", store: store) { passed.append($0) }
        await settle()
        fixture.fireRecordingTimeout(attempt: 1)
        store.deleteAll()
        await settle()
        fixture.completeRecognition(0)
        await settle()

        XCTAssertEqual(controller.state, .idle)
        XCTAssertTrue(passed.isEmpty)
        XCTAssertTrue((store.archive.completions ?? []).isEmpty)
        XCTAssertEqual(fixture.recorderStops, 1)
        XCTAssertEqual(fixture.audioReleases, 1)
    }

    func testBackupImportFencesSuspendedPronunciationResult() async throws {
        let fixture = PronunciationFixture()
        let store = try LearningStore(inMemory: true)
        let controller = PronunciationCheckController(dependencies: fixture.dependencies)
        var passed: [Int] = []

        controller.check(target: "hello there", lessonID: "lesson-fixture", language: "en", store: store) { passed.append($0) }
        await settle()
        fixture.fireRecordingTimeout(attempt: 1)
        try store.importData(Archive().encoded())
        await settle()
        fixture.completeRecognition(0)
        await settle()

        XCTAssertEqual(controller.state, .idle)
        XCTAssertTrue(passed.isEmpty)
        XCTAssertTrue((store.archive.completions ?? []).isEmpty)
        XCTAssertEqual(fixture.recorderStops, 1)
        XCTAssertEqual(fixture.audioReleases, 1)
    }

    func testSharedAudioSessionLeaseIsExclusiveAndRequiresExactReleaseToken() {
        let ownership = AudioSessionLeaseOwnership()
        let conversation = ownership.acquire(owner: .conversation)

        XCTAssertNotNil(conversation)
        XCTAssertNil(ownership.acquire(owner: .pronunciation))
        XCTAssertFalse(ownership.release(UUID(), deactivatedSuccessfully: true))
        XCTAssertNil(ownership.acquire(owner: .pronunciation))

        XCTAssertTrue(ownership.release(conversation!, deactivatedSuccessfully: true))
        let pronunciation = ownership.acquire(owner: .pronunciation)
        XCTAssertNotNil(pronunciation)
        XCTAssertNil(ownership.acquire(owner: .conversation))
        XCTAssertTrue(ownership.release(pronunciation!, deactivatedSuccessfully: true))
        XCTAssertNotNil(ownership.acquire(owner: .conversation))
    }

    func testStaleAudioSessionReleaseCannotReleaseNewOwner() {
        let ownership = AudioSessionLeaseOwnership()
        let previous = ownership.acquire(owner: .conversation)!
        XCTAssertTrue(ownership.release(previous, deactivatedSuccessfully: true))
        let current = ownership.acquire(owner: .pronunciation)!

        XCTAssertFalse(ownership.release(previous, deactivatedSuccessfully: true))
        XCTAssertNil(ownership.acquire(owner: .conversation))
        XCTAssertTrue(ownership.release(current, deactivatedSuccessfully: true))
    }

    func testFailedAudioDeactivationKeepsLeaseUntilCleanupSucceeds() {
        let ownership = AudioSessionLeaseOwnership()
        let conversation = ownership.acquire(owner: .conversation)!

        XCTAssertFalse(ownership.release(conversation, deactivatedSuccessfully: false))
        XCTAssertNil(ownership.acquire(owner: .pronunciation))
        XCTAssertTrue(ownership.release(conversation, deactivatedSuccessfully: true))
        XCTAssertNotNil(ownership.acquire(owner: .pronunciation))
    }

    func testFailedCleanupCanBeRetriedAfterPronunciationOwnerIsGone() {
        let ownership = AudioSessionLeaseOwnership()
        let pronunciation = ownership.acquire(owner: .pronunciation)!
        var cleanupAttempts = 0
        ownership.retainCleanup(for: pronunciation) {
            cleanupAttempts += 1
            return cleanupAttempts == 2
        }

        XCTAssertNil(ownership.acquire(owner: .conversation), "A failed cleanup must keep audio exclusive")
        let conversation = ownership.acquire(owner: .conversation)

        XCTAssertNotNil(conversation, "A later owner should retry cleanup without the old controller")
        XCTAssertEqual(2, cleanupAttempts)
    }

    func testRecoveredLeaseCannotRunStaleCleanupAgainstNewOwner() {
        let ownership = AudioSessionLeaseOwnership()
        let previous = ownership.acquire(owner: .pronunciation)!
        ownership.retainCleanup(for: previous) { true }
        let current = ownership.acquire(owner: .conversation)!
        var touchedAudioSession = false

        let cleaned = ownership.cleanupIfCurrent(previous) {
            touchedAudioSession = true
            return true
        }

        XCTAssertFalse(cleaned)
        XCTAssertFalse(touchedAudioSession, "A stale pronunciation object must not deactivate the new owner's audio")
        XCTAssertTrue(ownership.owns(current))
    }

    func testUnsupportedLocalModelMakesNoRequest() async throws {
        let fixture = PronunciationFixture()
        fixture.supportsOnDeviceRecognition = false
        let controller = PronunciationCheckController(dependencies: fixture.dependencies)

        controller.check(target: "hello there", lessonID: "lesson-fixture", language: "en",
                         store: try LearningStore(inMemory: true), onPass: { _ in XCTFail("unsupported local model must not score") })

        XCTAssertEqual(controller.state, .unavailable("On-device speech recognition is unavailable for this language."))
        XCTAssertEqual(fixture.recognitionRequests, 0)
        XCTAssertEqual(fixture.recorderStarts, 0)
        XCTAssertEqual(fixture.audioActivations, 0)
    }

    func testRecognitionRequestRequiresOnDeviceProcessing() async throws {
        let fixture = PronunciationFixture()
        let controller = PronunciationCheckController(dependencies: fixture.dependencies)
        controller.check(target: "hello there", lessonID: "lesson-fixture", language: "en",
                         store: try LearningStore(inMemory: true), onPass: { _ in })
        await settle()
        fixture.fireRecordingTimeout(attempt: 1)

        XCTAssertEqual(fixture.requiresOnDeviceValues, [true])
        XCTAssertEqual(Set(fixture.fileURLs).count, 1)
    }

    func testBuiltBundleContainsLocalizedSpeechAndMicrophonePermissionDescriptions() {
        let appBundle = Bundle(for: PronunciationCheckController.self)
        XCTAssertEqual(appBundle.infoDictionary?["NSMicrophoneUsageDescription"] as? String,
                       "Palique uses your microphone while you practise speaking.")
        XCTAssertEqual(appBundle.infoDictionary?["NSSpeechRecognitionUsageDescription"] as? String,
                       "Palique checks pronunciation with on-device speech recognition. Your practice audio is not sent to a server.")

        let expectedByLanguage = [
            "en": (
                "Palique uses your microphone while you practise speaking.",
                "Palique checks pronunciation with on-device speech recognition. Your practice audio is not sent to a server."),
            "es": (
                "Palique usa el micrófono mientras practicas la pronunciación.",
                "Palique comprueba la pronunciación con reconocimiento de voz en el dispositivo. El audio de práctica no se envía a ningún servidor.")
        ]
        for (language, expected) in expectedByLanguage {
            guard let localization = appBundle.url(forResource: language, withExtension: "lproj"),
                  let bundle = Bundle(url: localization) else {
                return XCTFail("Missing built \(language) localization bundle")
            }
            XCTAssertEqual(bundle.localizedString(forKey: "NSMicrophoneUsageDescription", value: "", table: "InfoPlist"), expected.0)
            XCTAssertEqual(bundle.localizedString(forKey: "NSSpeechRecognitionUsageDescription", value: "", table: "InfoPlist"), expected.1)
        }
    }
}
