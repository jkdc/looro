import AVFoundation
import Speech
import Foundation

/// Captures ~4 seconds of microphone audio to a file, transcribes it with the
/// on-device speech recognizer, and hands the transcript to the scoring
/// engine. Degraded mode: any authorization or engine failure surfaces as
/// `unavailable` and the UI falls back to plain listen-and-repeat.
@MainActor
final class PronunciationCheckController: ObservableObject {
    @Published var listeningTarget: String?
    @Published var result: (target: String, score: Int)?

    private var recorder: AVAudioRecorder?
    private var workItem: DispatchWorkItem?

    func check(target: String, lessonID: String, language: String,
               store: LearningStore, onPass: @escaping (Int) -> Void) {
        guard listeningTarget == nil else { return }
        listeningTarget = target
        result = nil
        SFSpeechRecognizer.requestAuthorization { [weak self] _ in
            Task { @MainActor in self?.begin(target: target, lessonID: lessonID, language: language, store: store, onPass: onPass) }
        }
    }

    private func begin(target: String, lessonID: String, language: String,
                       store: LearningStore, onPass: @escaping (Int) -> Void) {
        let session = AVAudioSession.sharedInstance()
        session.requestRecordPermission { [weak self] granted in
            Task { @MainActor in
                guard granted else { self?.fail(); return }
                self?.record(target: target, lessonID: lessonID, language: language, store: store, onPass: onPass)
            }
        }
    }

    private func record(target: String, lessonID: String, language: String,
                        store: LearningStore, onPass: @escaping (Int) -> Void) {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("pronunciation-check.m4a")
        try? FileManager.default.removeItem(at: url)
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playAndRecord, mode: .measurement, options: [.duckOthers])
            try session.setActive(true)
            let settings: [String: Any] = [AVFormatIDKey: kAudioFormatMPEG4AAC,
                                           AVSampleRateKey: 16_000, AVNumberOfChannelsKey: 1]
            let recorder = try AVAudioRecorder(url: url, settings: settings)
            guard recorder.record() else { fail(); return }
            self.recorder = recorder
            let stop = DispatchWorkItem { [weak self] in
                self?.finalize(url: url, target: target, lessonID: lessonID,
                               language: language, store: store, onPass: onPass)
            }
            workItem = stop
            DispatchQueue.main.asyncAfter(deadline: .now() + 4, execute: stop)
        } catch {
            fail()
        }
    }

    private func finalize(url: URL, target: String, lessonID: String,
                          language: String, store: LearningStore, onPass: @escaping (Int) -> Void) {
        recorder?.stop()
        recorder = nil
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: language)),
              recognizer.isAvailable else { fail(); return }
        let request = SFSpeechURLRecognitionRequest(url: url)
        request.shouldReportPartialResults = false
        if let localeHint = (Locale(identifier: language) as NSLocale)?.languageCode {
            request.taskHint = .dictation
            _ = localeHint
        }
        recognizer.recognitionTask(with: request) { [weak self] recognized, _ in
            guard let self, let recognized, recognized.isFinal else { return }
            Task { @MainActor in
                self.listeningTarget = nil
                let score = PronunciationEngine.score(target: target,
                                                      transcript: recognized.bestTranscription.formattedString)
                self.result = (target, score)
                if PronunciationEngine.passed(score) {
                    store.recordCompletion(kind: ProgressEngine.pronunciation, itemID: lessonID, bestScore: score)
                    onPass(score)
                }
            }
        }
    }

    private func fail() {
        listeningTarget = nil
        result = nil
    }
}
