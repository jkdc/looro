import Foundation
import AVFoundation
@preconcurrency import WebRTC

enum ConnectionState: Equatable { case idle, connecting, active, closing, ended, failed }

@MainActor final class LiveTransport: NSObject {
    var onEvent: (([String: Any]) -> Void)?
    var onLevels: ((Double, Double) -> Void)?
    var onFailure: ((String) -> Void)?
    private var factory: RTCPeerConnectionFactory?
    private var peer: RTCPeerConnection?
    private var channel: RTCDataChannel?
    private var localTrack: RTCAudioTrack?
    private var meterTask: Task<Void, Never>?
    private var attempt = UUID()
    private(set) var started = false
    private(set) var isMuted = false
    private var closing = false
    private var audioLeaseID: UUID?
    private var previousAudioConfiguration: (AVAudioSession.Category, AVAudioSession.Mode, AVAudioSession.CategoryOptions)?
    private var lastInput = 0.0, lastOutput = 0.0

    /// The caller owns how the realtime session is created: personal OpenAI key
    /// or hosted minutes. The transport only needs the session descriptor and the
    /// answer SDP; audio always connects phone-to-provider directly.
    func connect(establish: @escaping (_ offerSDP: String) async throws -> (session: [String: Any], answer: String)) async throws {
        disconnect()
        closing = false
        let token = UUID(); attempt = token
        let granted = await AVAudioApplication.requestRecordPermission()
        guard granted else { throw TransportError.microphone }
        try Task.checkCancellation()
        guard attempt == token else { throw CancellationError() }
        guard let audioLeaseID = AudioSessionLeaseOwnership.shared.acquire(owner: .conversation) else {
            throw TransportError.audioBusy
        }
        self.audioLeaseID = audioLeaseID
        do {
        // WebRTC reapplies this configuration when its audio unit starts.
        // Setting AVAudioSession alone loses the speaker preference at that point.
        let audioConfiguration = RTCAudioSessionConfiguration()
        audioConfiguration.category = AVAudioSession.Category.playAndRecord.rawValue
        audioConfiguration.mode = AVAudioSession.Mode.voiceChat.rawValue
        audioConfiguration.categoryOptions = [.defaultToSpeaker, .allowBluetoothHFP]
        RTCAudioSessionConfiguration.setWebRTC(audioConfiguration)
        let audio = RTCAudioSession.sharedInstance()
        let session = AVAudioSession.sharedInstance()
        previousAudioConfiguration = (session.category, session.mode, session.categoryOptions)
        audio.lockForConfiguration()
        do {
            try audio.setCategory(.playAndRecord, mode: .voiceChat, options: audioConfiguration.categoryOptions)
            try audio.setActive(true)
            audio.unlockForConfiguration()
        } catch { audio.unlockForConfiguration(); throw error }
        RTCInitializeSSL()
        let factory = RTCPeerConnectionFactory(encoderFactory: RTCDefaultVideoEncoderFactory(), decoderFactory: RTCDefaultVideoDecoderFactory())
        self.factory = factory
        let config = RTCConfiguration(); config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherOnce
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: ["DtlsSrtpKeyAgreement": "true"])
        guard let peer = factory.peerConnection(with: config, constraints: constraints, delegate: self) else { throw TransportError.connection }
        self.peer = peer
        let source = factory.audioSource(with: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: ["googEchoCancellation": "true", "googNoiseSuppression": "true", "googAutoGainControl": "true"]))
        let track = factory.audioTrack(with: source, trackId: "app-microphone")
        localTrack = track; isMuted = false
        peer.add(track, streamIds: ["app-audio"])
        let dataConfig = RTCDataChannelConfiguration(); dataConfig.isOrdered = true
        guard let channel = peer.dataChannel(forLabel: "oai-events", configuration: dataConfig) else { throw TransportError.connection }
        self.channel = channel; channel.delegate = self
        let offer: RTCSessionDescription = try await withCheckedThrowingContinuation { c in
            peer.offer(for: RTCMediaConstraints(mandatoryConstraints: ["OfferToReceiveAudio": "true", "OfferToReceiveVideo": "false"], optionalConstraints: nil)) { sdp, error in
                if let error { c.resume(throwing: error) } else if let sdp { c.resume(returning: sdp) } else { c.resume(throwing: TransportError.connection) }
            }
        }
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            peer.setLocalDescription(offer) { error in if let error { c.resume(throwing: error) } else { c.resume() } }
        }
        let deadline = Date().addingTimeInterval(10)
        while peer.iceGatheringState != .complete {
            try await Task.sleep(for: .milliseconds(100))
            guard attempt == token else { throw CancellationError() }
            guard Date() < deadline else { throw TransportError.timeout }
        }
        guard let sdp = peer.localDescription?.sdp else { throw TransportError.connection }
        let established = try await establish(sdp)
        guard attempt == token else { throw CancellationError() }
        let answer = established.answer
        if !established.session.isEmpty { onEvent?(["type": "app.session.created", "session": established.session]) }
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            peer.setRemoteDescription(RTCSessionDescription(type: .answer, sdp: answer)) { error in
                if let error { c.resume(throwing: error) } else { c.resume() }
            }
        }
        let readyDeadline = Date().addingTimeInterval(20)
        while !started {
            try await Task.sleep(for: .milliseconds(100))
            guard attempt == token else { throw CancellationError() }
            guard Date() < readyDeadline else { throw TransportError.timeout }
        }
        startMetering()
        } catch {
            if attempt == token { disconnect() }
            throw error
        }
    }

    @discardableResult func send(_ event: [String: Any]) -> Bool {
        guard let channel, channel.readyState == .open, let data = try? JSONSerialization.data(withJSONObject: event) else { return false }
        return channel.sendData(RTCDataBuffer(data: data, isBinary: false))
    }
    func mute(_ muted: Bool) {
        isMuted = muted; localTrack?.isEnabled = !muted
        _ = send(["type": muted ? "session.input_audio.mute" : "session.input_audio.unmute", "event_id": UUID().uuidString])
    }
    func close() {
        closing = true; localTrack?.isEnabled = false; isMuted = true
        _ = send(["type": "session.close", "event_id": UUID().uuidString])
    }
    /// Synchronous lease cleanup: deactivate the shared audio session and
    /// restore the captured configuration under RTCAudioSession's lock. The
    /// lock is released on the exception path too, and success requires BOTH
    /// phases to finish.
    private static func deactivateAndRestoreSession(
        _ configuration: (AVAudioSession.Category, AVAudioSession.Mode, AVAudioSession.CategoryOptions)?) -> Bool {
        let audio = RTCAudioSession.sharedInstance()
        audio.lockForConfiguration()
        defer { audio.unlockForConfiguration() }
        do {
            try audio.setActive(false)
            if let configuration {
                try audio.setCategory(configuration.0, mode: configuration.1,
                                      options: configuration.2)
            }
            return true
        } catch {
            return false
        }
    }

    func disconnect() {
        attempt = UUID(); meterTask?.cancel(); meterTask = nil
        started = false; closing = true
        localTrack?.isEnabled = false; localTrack = nil
        channel?.delegate = nil; channel?.close(); channel = nil
        peer?.delegate = nil; peer?.close(); peer = nil; factory = nil
        if let audioLeaseID {
            // A late disconnect after the lease moved on (a recovery handed
            // the session to pronunciation) must not deactivate the NEW
            // owner's audio: ownership is checked before touching the shared
            // session.
            guard AudioSessionLeaseOwnership.shared.owns(audioLeaseID) else {
                self.audioLeaseID = nil
                previousAudioConfiguration = nil
                return
            }
            let configuration = previousAudioConfiguration
            if AudioSessionLeaseOwnership.shared.cleanupIfCurrent(audioLeaseID, cleanup: {
                Self.deactivateAndRestoreSession(configuration)
            }) {
                AudioSessionLeaseOwnership.shared.release(audioLeaseID, deactivatedSuccessfully: true)
                self.audioLeaseID = nil
                previousAudioConfiguration = nil
            } else {
                // The token stays held and the SAME synchronous cleanup is
                // retained process-wide, so the next acquire — conversation
                // or pronunciation — retries it even if this transport is
                // gone; the closure captures only values, never self.
                AudioSessionLeaseOwnership.shared.retainCleanup(for: audioLeaseID) {
                    Self.deactivateAndRestoreSession(configuration)
                }
            }
        }
        lastInput = 0; lastOutput = 0; onLevels?(0, 0)
    }
    private func startMetering() {
        meterTask?.cancel()
        meterTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self, let peer = self.peer else { return }
                peer.statistics { [weak self] report in
                    var input = 0.0, output = 0.0
                    for stat in report.statistics.values {
                        let level = (stat.values["audioLevel"] as? NSNumber)?.doubleValue ?? 0
                        if stat.type == "inbound-rtp" { output = max(output, level) }
                        if stat.type == "media-source" { input = max(input, level) }
                    }
                    Task { @MainActor [weak self] in
                        guard let self, self.started else { return }
                        self.lastInput = self.lastInput * 0.35 + min(1, input * 4) * 0.65
                        self.lastOutput = self.lastOutput * 0.35 + min(1, output * 4) * 0.65
                        self.onLevels?(self.isMuted ? 0 : self.lastInput, self.lastOutput)
                    }
                }
                try? await Task.sleep(for: .milliseconds(100))
            }
        }
    }
    enum TransportError: LocalizedError {
        case microphone, connection, timeout, audioBusy
        var errorDescription: String? {
            switch self {
            case .microphone: String(localized: "Allow microphone access in iPhone Settings → Palique to start a conversation.")
            case .connection: String(localized: "The voice connection couldn’t be established. Check your connection and try again.")
            case .timeout: String(localized: "The voice connection took too long. Please try again.")
            case .audioBusy: String(localized: "Audio is already in use. Finish the current audio activity and try again.")
            }
        }
    }
}

extension LiveTransport: RTCDataChannelDelegate, RTCPeerConnectionDelegate {
    nonisolated func dataChannelDidChangeState(_ dataChannel: RTCDataChannel) {
        let closed = dataChannel.readyState == .closed
        Task { @MainActor [weak self] in
            guard let self, dataChannel === self.channel, closed, !self.closing else { return }
            self.onFailure?("The voice connection ended unexpectedly. Your conversation has been saved.")
        }
    }
    nonisolated func dataChannel(_ dataChannel: RTCDataChannel, didReceiveMessageWith buffer: RTCDataBuffer) {
        let data = buffer.data
        Task { @MainActor [weak self] in
            guard let self, dataChannel === self.channel,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
            if json["type"] as? String == "session.started" { self.started = true }
            self.onEvent?(json)
        }
    }
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    nonisolated func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {
        Task { @MainActor [weak self] in
            guard let self, peerConnection === self.peer, !self.closing else { return }
            if newState == .failed { self.onFailure?("The network connection was lost. Tap to start a new conversation.") }
        }
    }
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}
