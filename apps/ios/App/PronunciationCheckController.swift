import AVFoundation
import Combine
import Foundation
import Speech
import LearningCore

enum PronunciationCheckState: Equatable {
    case idle
    case requestingPermissions
    case recording
    case recognizing
    case result
    case failed(String)
    case unavailable(String)

    var isBusy: Bool {
        switch self {
        case .requestingPermissions, .recording, .recognizing: true
        default: false
        }
    }
}

@MainActor protocol PronunciationRecording: AnyObject {
    func record() -> Bool
    func stop()
}

@MainActor protocol PronunciationCancellation: AnyObject {
    func cancel()
}

@MainActor protocol PronunciationRecognitionTask: PronunciationCancellation {}

@MainActor protocol PronunciationRecognizing: AnyObject {
    var isAvailable: Bool { get }
    var supportsOnDeviceRecognition: Bool { get }
    func recognize(url: URL, requiresOnDevice: Bool,
                   completion: @escaping (String?, Bool, Error?) -> Void) -> PronunciationRecognitionTask?
}

@MainActor struct PronunciationAudioSession {
    let activate: () throws -> Void
    let release: () -> Void
}

@MainActor struct PronunciationCheckDependencies {
    let requestSpeechAuthorization: (@escaping (SFSpeechRecognizerAuthorizationStatus) -> Void) -> Void
    let requestRecordPermission: (@escaping (Bool) -> Void) -> Void
    let makeRecorder: (URL) -> PronunciationRecording?
    let makeRecognizer: (String) -> PronunciationRecognizing?
    let audioSession: PronunciationAudioSession
    let schedule: (TimeInterval, @escaping @MainActor () -> Void) -> PronunciationCancellation
    let temporaryDirectory: URL
    let removeFile: (URL) -> Void

    static func live() -> PronunciationCheckDependencies {
        let session = AVAudioSession.sharedInstance()
        let audioLease = AVAudioSessionPronunciationLease(session)
        return PronunciationCheckDependencies(
            requestSpeechAuthorization: { SFSpeechRecognizer.requestAuthorization($0) },
            requestRecordPermission: { session.requestRecordPermission($0) },
            makeRecorder: { url in
                let settings: [String: Any] = [
                    AVFormatIDKey: kAudioFormatMPEG4AAC,
                    AVSampleRateKey: 16_000,
                    AVNumberOfChannelsKey: 1
                ]
                guard let recorder = try? AVAudioRecorder(url: url, settings: settings) else { return nil }
                return ApplePronunciationRecorder(recorder)
            },
            makeRecognizer: { language in
                guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: language)) else { return nil }
                return ApplePronunciationRecognizer(recognizer)
            },
            audioSession: PronunciationAudioSession(
                activate: { try audioLease.activate() },
                release: { audioLease.release() }),
            schedule: { delay, action in
                let cancellation = DispatchWorkItem { Task { @MainActor in action() } }
                DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: cancellation)
                return DispatchWorkItemCancellation(cancellation)
            },
            temporaryDirectory: FileManager.default.temporaryDirectory,
            removeFile: { try? FileManager.default.removeItem(at: $0) })
    }

#if DEBUG
    static func uiTestFixture() -> PronunciationCheckDependencies {
        PronunciationCheckDependencies(
            requestSpeechAuthorization: { $0(.authorized) },
            requestRecordPermission: { $0(true) },
            makeRecorder: { url in
                _ = FileManager.default.createFile(atPath: url.path, contents: Data())
                return PronunciationUITestRecorder()
            },
            makeRecognizer: { _ in PronunciationUITestRecognizer() },
            audioSession: PronunciationAudioSession(activate: {}, release: {}),
            schedule: { delay, action in
                let item = DispatchWorkItem { Task { @MainActor in action() } }
                if delay == 4 {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.2, execute: item)
                }
                return DispatchWorkItemCancellation(item)
            },
            temporaryDirectory: FileManager.default.temporaryDirectory,
            removeFile: {
                try? FileManager.default.removeItem(at: $0)
                PronunciationUITestProbe.shared.recordCleanup()
            })
    }
#endif
}

@MainActor private final class AVAudioSessionPronunciationLease {
    private let session: AVAudioSession
    private var previousConfiguration: (AVAudioSession.Category, AVAudioSession.Mode, AVAudioSession.CategoryOptions)?

    init(_ session: AVAudioSession) { self.session = session }

    func activate() throws {
        let previous = (session.category, session.mode, session.categoryOptions)
        do {
            try session.setCategory(.playAndRecord, mode: .measurement, options: [.duckOthers])
            try session.setActive(true)
            previousConfiguration = previous
        } catch {
            try? session.setCategory(previous.0, mode: previous.1, options: previous.2)
            throw error
        }
    }

    func release() {
        guard let previousConfiguration else { return }
        self.previousConfiguration = nil
        try? session.setCategory(previousConfiguration.0, mode: previousConfiguration.1,
                                 options: previousConfiguration.2)
    }
}

/// Captures speech to a request-unique temporary file and scores only when the
/// recognizer guarantees on-device processing. Each attempt has one terminal
/// cleanup path so stale permission and recognition callbacks cannot affect a retry.
@MainActor
final class PronunciationCheckController: ObservableObject {
    @Published private(set) var state: PronunciationCheckState = .idle
    @Published private(set) var result: (target: String, score: Int)?

    var listeningTarget: String? { activeAttempt?.target }
    var statusMessage: String? {
        switch state {
        case .failed(let message), .unavailable(let message): message
        default: nil
        }
    }

    private struct Attempt {
        let id: UUID
        let target: String
        let lessonID: String
        let language: String
        let store: LearningStore
        let onPass: (Int) -> Void
        let recognizer: PronunciationRecognizing
        var recorder: PronunciationRecording?
        var recognitionTask: PronunciationRecognitionTask?
        var timeout: PronunciationCancellation?
        var fileURL: URL?
        var audioActivated = false
    }

    private enum Terminal {
        case cancelled
        case failed(String)
        case result(Int)
    }

    private let dependencies: PronunciationCheckDependencies
    private var activeAttempt: Attempt?

    init(dependencies: PronunciationCheckDependencies? = nil) {
#if DEBUG
        if let dependencies {
            self.dependencies = dependencies
        } else if ProcessInfo.processInfo.arguments.contains("--pronunciation-lifecycle-ui-test") {
            self.dependencies = .uiTestFixture()
        } else {
            self.dependencies = .live()
        }
#else
        self.dependencies = dependencies ?? .live()
#endif
    }

    func check(target: String, lessonID: String, language: String,
               store: LearningStore, onPass: @escaping (Int) -> Void) {
        guard activeAttempt == nil else { return }
        result = nil
        guard let recognizer = dependencies.makeRecognizer(language), recognizer.isAvailable,
              recognizer.supportsOnDeviceRecognition else {
            state = .unavailable("On-device speech recognition is unavailable for this language.")
            return
        }

        let attempt = Attempt(id: UUID(), target: target, lessonID: lessonID, language: language,
                              store: store, onPass: onPass, recognizer: recognizer)
        activeAttempt = attempt
        state = .requestingPermissions
        dependencies.requestSpeechAuthorization { [weak self] status in
            Task { @MainActor in self?.speechPermissionResolved(status, attemptID: attempt.id) }
        }
    }

    func cancel() {
        guard activeAttempt != nil else {
            if state.isBusy { state = .idle }
            result = nil
            return
        }
        terminate(.cancelled)
    }

    private func speechPermissionResolved(_ status: SFSpeechRecognizerAuthorizationStatus, attemptID: UUID) {
        guard activeAttempt?.id == attemptID else { return }
        guard status == .authorized else {
            terminate(.failed("Speech recognition permission was denied."))
            return
        }
        dependencies.requestRecordPermission { [weak self] granted in
            Task { @MainActor in self?.recordPermissionResolved(granted, attemptID: attemptID) }
        }
    }

    private func recordPermissionResolved(_ granted: Bool, attemptID: UUID) {
        guard activeAttempt?.id == attemptID else { return }
        guard granted else {
            terminate(.failed("Microphone permission was denied."))
            return
        }
        beginRecording(attemptID: attemptID)
    }

    private func beginRecording(attemptID: UUID) {
        guard var attempt = activeAttempt, attempt.id == attemptID else { return }
        let url = dependencies.temporaryDirectory.appendingPathComponent("pronunciation-\(attemptID.uuidString).m4a")
        attempt.fileURL = url
        activeAttempt = attempt
        do {
            try dependencies.audioSession.activate()
            attempt.audioActivated = true
            activeAttempt = attempt
            guard let recorder = dependencies.makeRecorder(url) else {
                terminate(.failed("Pronunciation recording could not start. Please try again."))
                return
            }
            attempt.recorder = recorder
            activeAttempt = attempt
            guard recorder.record() else {
                terminate(.failed("Pronunciation recording could not start. Please try again."))
                return
            }
            state = .recording
            attempt.timeout = dependencies.schedule(4) { [weak self] in self?.beginRecognition(attemptID: attemptID) }
            activeAttempt = attempt
        } catch {
            terminate(.failed("Pronunciation recording could not start. Please try again."))
        }
    }

    private func beginRecognition(attemptID: UUID) {
        guard var attempt = activeAttempt, attempt.id == attemptID, state == .recording,
              let fileURL = attempt.fileURL else { return }
        attempt.timeout?.cancel()
        attempt.timeout = nil
        attempt.recorder?.stop()
        attempt.recorder = nil
        state = .recognizing
        attempt.timeout = dependencies.schedule(10) { [weak self] in
            guard self?.activeAttempt?.id == attemptID else { return }
            self?.terminate(.failed("Pronunciation check timed out. Please try again."))
        }
        activeAttempt = attempt
        let recognitionTask = attempt.recognizer.recognize(url: fileURL, requiresOnDevice: true) { [weak self] transcript, isFinal, error in
            Task { @MainActor in
                guard let self, self.activeAttempt?.id == attemptID, self.state == .recognizing else { return }
                if error != nil || transcript == nil {
                    self.terminate(.failed("Pronunciation could not be checked. Please try again."))
                    return
                }
                guard isFinal, let transcript else { return }
                let score = PronunciationEngine.score(target: attempt.target, transcript: transcript)
                self.terminate(.result(score))
            }
        }
        guard activeAttempt?.id == attemptID else {
            recognitionTask?.cancel()
            return
        }
        attempt.recognitionTask = recognitionTask
        activeAttempt = attempt
    }

    private func terminate(_ terminal: Terminal) {
        guard var attempt = activeAttempt else { return }
        activeAttempt = nil

        attempt.timeout?.cancel()
        attempt.recognitionTask?.cancel()
        attempt.recorder?.stop()
        if let fileURL = attempt.fileURL { dependencies.removeFile(fileURL) }
        if attempt.audioActivated { dependencies.audioSession.release() }

        switch terminal {
        case .cancelled:
            result = nil
            state = .idle
        case .failed(let message):
            result = nil
            state = .failed(message)
        case .result(let score):
            result = (attempt.target, score)
            state = .result
            if PronunciationEngine.passed(score) {
                attempt.store.recordCompletion(kind: ProgressEngine.pronunciation,
                                               itemID: attempt.lessonID, bestScore: score)
                attempt.onPass(score)
            }
        }
    }
}

@MainActor private final class ApplePronunciationRecorder: PronunciationRecording {
    private let recorder: AVAudioRecorder
    init(_ recorder: AVAudioRecorder) { self.recorder = recorder }
    func record() -> Bool { recorder.record() }
    func stop() { recorder.stop() }
}

@MainActor private final class ApplePronunciationRecognizer: PronunciationRecognizing {
    private let recognizer: SFSpeechRecognizer
    init(_ recognizer: SFSpeechRecognizer) { self.recognizer = recognizer }
    var isAvailable: Bool { recognizer.isAvailable }
    var supportsOnDeviceRecognition: Bool { recognizer.supportsOnDeviceRecognition }

    func recognize(url: URL, requiresOnDevice: Bool,
                   completion: @escaping (String?, Bool, Error?) -> Void) -> PronunciationRecognitionTask? {
        let request = SFSpeechURLRecognitionRequest(url: url)
        request.shouldReportPartialResults = false
        request.requiresOnDeviceRecognition = requiresOnDevice
        let task = recognizer.recognitionTask(with: request) { result, error in
            completion(result?.bestTranscription.formattedString, result?.isFinal ?? false, error)
        }
        return ApplePronunciationRecognitionTask(task)
    }
}

@MainActor private final class ApplePronunciationRecognitionTask: PronunciationRecognitionTask {
    private let task: SFSpeechRecognitionTask
    init(_ task: SFSpeechRecognitionTask) { self.task = task }
    func cancel() { task.cancel() }
}

@MainActor private final class DispatchWorkItemCancellation: PronunciationCancellation {
    private let workItem: DispatchWorkItem
    init(_ workItem: DispatchWorkItem) { self.workItem = workItem }
    func cancel() { workItem.cancel() }
}

#if DEBUG
@MainActor final class PronunciationUITestProbe: ObservableObject {
    static let shared = PronunciationUITestProbe()
    @Published private(set) var cleanupCount = 0

    func recordCleanup() { cleanupCount += 1 }
}

@MainActor private final class PronunciationUITestRecorder: PronunciationRecording {
    func record() -> Bool { true }
    func stop() {}
}

@MainActor private final class PronunciationUITestRecognizer: PronunciationRecognizing {
    var isAvailable = true
    var supportsOnDeviceRecognition = true
    func recognize(url: URL, requiresOnDevice: Bool,
                   completion: @escaping (String?, Bool, Error?) -> Void) -> PronunciationRecognitionTask? {
        PronunciationUITestRecognitionTask()
    }
}

@MainActor private final class PronunciationUITestRecognitionTask: PronunciationRecognitionTask {
    func cancel() {}
}
#endif
