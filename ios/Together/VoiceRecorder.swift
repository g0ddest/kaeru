import AVFoundation
import Observation

/// A clip as it comes off the microphone.
struct RecordedClip: Equatable, Sendable {
    var data: Data
    var durationMs: Int64
}

/// How long the microphone may be open, and how short a press is not speech.
enum VoiceLimits {
    /// Thirty seconds, and reaching it **sends** rather than throws the speech away. Discarding
    /// half a minute of somebody's talking because they did not watch a counter is the worst thing
    /// this button could do.
    static let maxDurationMs: Int64 = 30_000
    /// A tap that was meant as a tap produces eighty milliseconds of nothing, and sending it would
    /// put an unplayable smudge in the other person's corner.
    static let minDurationMs: Int64 = 400
    /// Twelve levels a second reads as a voice.
    static let levelTick = Duration.milliseconds(80)
    static let bars = 14
    /// When the counter turns amber: five seconds left to say the rest of it.
    static let warningMs: Int64 = 25_000
}

/// The microphone, for as long as a finger is on the button and not one millisecond longer.
///
/// A port of Android's `VoiceRecorder`, and deliberately the same shape: `start` opens it, `stop`
/// closes it and hands over what was said, `cancel` closes it and keeps nothing.
///
/// AAC at 16 kHz mono in an MP4 container — about 3 KB a second, so thirty seconds crosses the
/// channel in one breath. It is also the format Android's own pre-Opus path used and the one its
/// `MediaPlayer` reads, which is what makes a clip recorded here playable there. (The other
/// direction is the gap: Android records Ogg/Opus from API 29, and no Apple decoder reads an Ogg
/// container. Such a clip arrives whole and is refused by the player with a sentence saying so
/// rather than silence.)
///
/// It records to a file because `AVAudioRecorder` has no other mode. The file lives in the
/// caches directory and is deleted as soon as its bytes have been read.
@MainActor @Observable final class VoiceRecorder {
    private(set) var isRecording = false
    /// How loud it is right now, from nothing to as loud as this microphone goes.
    private(set) var level: Float = 0
    private(set) var elapsedMs: Int64 = 0

    @ObservationIgnored private var recorder: AVAudioRecorder?
    @ObservationIgnored private var file: URL?
    @ObservationIgnored private var startedAt = Date.distantPast
    @ObservationIgnored private var meter: Task<Void, Never>?
    /// What the audio session was doing before the microphone took it over.
    @ObservationIgnored private var restoreSession = false

    /// Whether the phone will give the microphone up, asking once if it has never been asked.
    ///
    /// Asked at the first hold rather than on the way into a session: this is the moment a person
    /// can see what it is for.
    func permitted() async -> Bool {
        switch AVAudioApplication.shared.recordPermission {
        case .granted: return true
        case .denied: return false
        default: return await AVAudioApplication.requestRecordPermission()
        }
    }

    /// Opens the microphone. False when the phone would not give it up — which is the answer to a
    /// permission that was granted and then taken away as much as to a microphone in use elsewhere.
    @discardableResult func start() -> Bool {
        guard recorder == nil else { return true }
        sweep()
        let target = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(Self.prefix)\(Int(Date().timeIntervalSince1970 * 1000)).m4a")
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
            try session.setActive(true)
            restoreSession = true
            let value = try AVAudioRecorder(url: target, settings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: 16_000,
                AVNumberOfChannelsKey: 1,
                AVEncoderBitRateKey: 24_000
            ])
            value.isMeteringEnabled = true
            // The ceiling belongs to the recorder rather than to a timer in the view: a view that
            // is no longer being drawn stops counting, and the microphone would not.
            guard value.record(forDuration: Double(VoiceLimits.maxDurationMs) / 1000) else { throw CancellationError() }
            recorder = value; file = target; startedAt = Date()
            isRecording = true; level = 0; elapsedMs = 0
            startMetering()
            return true
        } catch {
            try? FileManager.default.removeItem(at: target)
            releaseSession()
            return false
        }
    }

    /// Closes the microphone and hands over what was said, or nil if it was not worth sending.
    func stop() -> RecordedClip? {
        guard let value = recorder, let target = file else { return nil }
        recorder = nil; file = nil; isRecording = false
        meter?.cancel(); meter = nil
        let elapsed = Int64(Date().timeIntervalSince(startedAt) * 1000)
        value.stop()
        defer { try? FileManager.default.removeItem(at: target); releaseSession() }
        guard elapsed >= VoiceLimits.minDurationMs, let data = try? Data(contentsOf: target), !data.isEmpty else { return nil }
        return RecordedClip(data: data, durationMs: min(elapsed, VoiceLimits.maxDurationMs))
    }

    /// A swipe to the left, or the app leaving the screen: the microphone closes and nothing is kept.
    func cancel() {
        guard let value = recorder else { return }
        recorder = nil; isRecording = false
        meter?.cancel(); meter = nil
        value.stop()
        if let target = file { try? FileManager.default.removeItem(at: target) }
        file = nil
        releaseSession()
    }

    private func startMetering() {
        meter = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: VoiceLimits.levelTick)
                guard !Task.isCancelled, let self, let value = self.recorder else { return }
                value.updateMeters()
                // −50 dB is about the noise floor of a phone in a quiet room; everything below it
                // would draw as a bar anyway and say nothing.
                let decibels = value.averagePower(forChannel: 0)
                self.level = max(0, min(1, (decibels + 50) / 50))
                self.elapsedMs = Int64(Date().timeIntervalSince(self.startedAt) * 1000)
                // The recorder stopped itself at the ceiling. Noticing that is what sends the clip.
                if !value.isRecording { self.isRecording = false; return }
            }
        }
    }

    /// Gives the session back to the episode. Recording needs `.playAndRecord`, which routes to
    /// the earpiece on some phones and is the wrong category to leave a film playing under.
    private func releaseSession() {
        guard restoreSession else { return }
        restoreSession = false
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    /// Clears clips nothing is going to come back for: one is read and deleted the moment
    /// recording stops, so the only ones left are from a process that died with the microphone
    /// open. Done on the way in, because the way out is what failed.
    private func sweep() {
        let directory = FileManager.default.temporaryDirectory
        let names = (try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []
        for name in names where name.hasPrefix(Self.prefix) {
            try? FileManager.default.removeItem(at: directory.appendingPathComponent(name))
        }
    }

    private static let prefix = "kaeru-voice-"
}

/// A clip through the speaker, once, straight away.
///
/// The episode is turned down explicitly rather than left to audio focus: the system will not duck
/// an app against itself, and a clip about what is on screen right now has to be audible over it.
@MainActor final class VoicePlayer: NSObject, AVAudioPlayerDelegate {
    private var player: AVAudioPlayer?
    private var finished: (() -> Void)?

    /// Plays `data`, calling `onFinished` when the clip is over however it got there. Anything
    /// already playing is stopped first: two clips at once is two people talking over each other.
    ///
    /// - Returns: false when nothing on this device can decode the clip — an Ogg/Opus recording
    ///   from an Android phone, which no Apple decoder reads.
    @discardableResult func play(_ data: Data, onFinished: @escaping () -> Void) -> Bool {
        stop()
        guard let value = try? AVAudioPlayer(data: data) else { onFinished(); return false }
        value.delegate = self
        finished = onFinished
        guard value.play() else { finished = nil; onFinished(); return false }
        player = value
        return true
    }

    func stop() {
        guard let value = player else { return }
        value.stop()
        release()
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor [weak self] in self?.release() }
    }
    nonisolated func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        Task { @MainActor [weak self] in self?.release() }
    }

    /// Everything this clip held, given back — including the promise that it would end.
    private func release() {
        let callback = finished
        finished = nil
        player = nil
        callback?()
    }
}
