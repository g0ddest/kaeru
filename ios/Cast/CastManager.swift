import Foundation
import Observation

/// Retain one instance at application scope and initialize it at launch for SDK session resumption.
@MainActor @Observable final class CastManager {
    private(set) var connection: CastConnection = .disconnected
    private(set) var state = CastPlaybackState()
    private(set) var qualities: [Int] = []
    private(set) var isResolving = false
    var isConnected: Bool { connection.isConnected }
    var receiverName: String? { connection.receiverName }
    var error: String? { state.error?.localizedDescription }

    /// Capture and flush the local player synchronously. Return nil when there is no local episode.
    @ObservationIgnored var localPlayback: (() -> CastHandoff?)?
    @ObservationIgnored var onPauseLocal: (() -> Void)?
    /// Called once after a session ends, never on background suspension. Parent resolves local media.
    @ObservationIgnored var onReturnToLocal: ((CastHandoff) -> Void)?
    /// Parent owns account fencing, persistence throttling, watched threshold, and next-episode policy.
    @ObservationIgnored var onProgress: ((EpisodeProgress, Anime) -> Void)?
    @ObservationIgnored var onEnded: ((CastSelection) -> Void)?

    @ObservationIgnored private let resolve: (Int, Int, Int) async throws -> Stream
    @ObservationIgnored private let transport: any CastTransport
    @ObservationIgnored private let sleep: (Duration) async throws -> Void
    @ObservationIgnored private var generation = UUID()
    @ObservationIgnored private var loadTask: Task<Void, Never>?
    @ObservationIgnored private var timeoutTask: Task<Void, Never>?
    @ObservationIgnored private var retriesRemaining = 0
    @ObservationIgnored private var retryScheduled = false
    @ObservationIgnored private var notifiedEnd = false

    init(resolve: @escaping (Int, Int, Int) async throws -> Stream,
         transport: any CastTransport,
         sleep: @escaping (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.resolve = resolve; self.transport = transport; self.sleep = sleep
        transport.onConnection = { [weak self] in self?.connectionChanged($0) }
        transport.onStatus = { [weak self] in self?.receive($0) }
        transport.onFailure = { [weak self] in self?.state.fail($0) }
        transport.start()
    }

    func start() { transport.start() }
    func presentDevices() { transport.presentDevices() }
    func presentExpandedControls() { transport.presentExpandedControls() }

    /// Always resolves a fresh network stream; a downloaded asset is never sent to the receiver.
    func load(anime: Anime, episode: Int, translation: Int, quality: Int,
              position: TimeInterval = 0, autoplay: Bool = true) async {
        let handoff = CastHandoff(selection: .init(anime: anime, episode: episode, translation: translation, quality: quality),
                                  position: position, shouldPlay: autoplay)
        guard isConnected else { state.fail(.notConnected); return }
        publishProgress()
        onPauseLocal?()
        await performLoad(handoff, retries: 1)
    }

    func selectQuality(_ quality: Int) async {
        guard var handoff = state.handoff else { return }
        handoff.selection.quality = quality
        await replace(handoff)
    }

    func selectTranslation(_ translation: Int) async {
        guard var handoff = state.handoff else { return }
        handoff.selection.translation = translation
        await replace(handoff)
    }

    func selectEpisode(_ episode: Int) async {
        guard var handoff = state.handoff, episode > 0 else { return }
        handoff.selection.episode = episode; handoff.position = 0; handoff.shouldPlay = true
        await replace(handoff)
    }

    func retry() async { if let handoff = state.handoff { await replace(handoff) } }

    func play() {
        guard isConnected, !isResolving, !state.isBuffering else { return }
        state.setIntent(playing: true); transport.play()
    }
    func pause() {
        guard isConnected, !isResolving, !state.isBuffering else { return }
        state.setIntent(playing: false); transport.pause(); publishProgress()
    }
    func seek(to position: TimeInterval) {
        guard isConnected, !isResolving, !state.isBuffering, position.isFinite else { return }
        let target = state.duration > 0 ? min(max(0, position), state.duration) : max(0, position)
        transport.seek(to: target)
    }

    /// Stops the receiver application; the session-end callback performs the local handoff.
    func stopCasting() { publishProgress(); transport.disconnect() }

    /// Use on account changes or explicit episode close; prevents an unwanted local resume.
    func clearPlayback() {
        publishProgress(); invalidate()
        state = CastPlaybackState(); qualities = []
        transport.stop()
    }

    private func replace(_ handoff: CastHandoff) async {
        guard isConnected else { state.fail(.notConnected); return }
        publishProgress()
        await performLoad(handoff, retries: 1)
    }

    private func performLoad(_ handoff: CastHandoff, retries: Int) async {
        generation = UUID(); let fence = generation
        timeoutTask?.cancel(); retryScheduled = false; retriesRemaining = retries; notifiedEnd = false
        state.begin(handoff); isResolving = true; qualities = []
        // Silence the old source while a new signed URL is being resolved.
        transport.pause()
        do {
            let selection = handoff.selection
            let stream = try await resolve(selection.anime.id, selection.translation, selection.episode)
            guard fence == generation, isConnected, !Task.isCancelled else { return }
            let payload = try CastLoadPayload(anime: selection.anime, stream: stream, quality: selection.quality,
                                              position: handoff.position, autoplay: handoff.shouldPlay)
            state.begin(.init(selection: payload.selection, position: payload.position, shouldPlay: payload.autoplay))
            state.contentID = payload.url.absoluteString
            qualities = Array(Set(stream.urls.filter { CastLoadPayload.remoteURL($0.url) != nil }.map(\.quality))).sorted(by: >)
            isResolving = false
            armTimeout(fence)
            transport.load(payload) { [weak self] result in
                guard let self, self.generation == fence else { return }
                if case .failure(let failure) = result { self.failedLoad(failure) }
            }
        } catch {
            guard fence == generation else { return }
            isResolving = false
            state.fail((error as? CastFailure) ?? .unavailableSource)
        }
    }

    private func armTimeout(_ fence: UUID) {
        let sleep = sleep
        timeoutTask = Task { [weak self] in
            do { try await sleep(.seconds(20)) } catch { return }
            guard let self, self.generation == fence else { return }
            // Pause a late-starting receiver too; a timed-out spinner must not turn into hidden audio.
            self.transport.pause()
            self.state.fail(.timedOut)
        }
    }

    private func failedLoad(_ failure: CastFailure) {
        guard !retryScheduled else { return }
        timeoutTask?.cancel()
        guard retriesRemaining > 0, isConnected, let handoff = state.handoff else {
            state.fail(failure); return
        }
        retriesRemaining -= 1; retryScheduled = true
        let retries = retriesRemaining
        loadTask = Task { [weak self] in
            guard let self, self.isConnected, !Task.isCancelled else { return }
            await self.performLoad(handoff, retries: retries)
        }
    }

    private func receive(_ status: CastRemoteStatus) {
        guard isConnected, !isResolving, !retryScheduled else { return }
        // A resumed session from another process is controllable, but cannot be attributed to an
        // anime without a local selection. Never infer account progress from receiver titles.
        if state.selection == nil { state.contentID = status.contentID }
        guard state.update(status) else { return }
        if status.phase == .playing || status.phase == .paused || status.phase == .finished {
            timeoutTask?.cancel()
        }
        if status.phase == .failed { failedLoad(.loadFailed); return }
        publishProgress()
        if state.ended, !notifiedEnd, let selection = state.selection {
            notifiedEnd = true; onEnded?(selection)
        }
    }

    private func connectionChanged(_ value: CastConnection) {
        let previous = connection
        connection = value
        switch value {
        case .connected:
            if case .suspended = previous { return }
            guard !previous.isConnected, state.selection == nil, let handoff = localPlayback?() else { return }
            loadTask = Task { [weak self] in
                guard let self else { return }
                await self.load(anime: handoff.selection.anime, episode: handoff.selection.episode,
                                translation: handoff.selection.translation, quality: handoff.selection.quality,
                                position: handoff.position, autoplay: handoff.shouldPlay)
            }
        case .disconnected:
            publishProgress()
            let handoff = state.handoff
            invalidate(); state = CastPlaybackState(); qualities = []
            if let handoff { onReturnToLocal?(handoff) }
        case .suspended:
            // Keep ownership while the SDK reconnects, avoiding simultaneous local/remote audio.
            timeoutTask?.cancel()
            publishProgress()
        case .connecting: break
        }
    }

    private func publishProgress() {
        guard let selection = state.selection, state.duration > 0 else { return }
        onProgress?(.init(animeID: selection.anime.id, episode: selection.episode, position: state.position, duration: state.duration), selection.anime)
    }

    private func invalidate() {
        generation = UUID(); loadTask?.cancel(); timeoutTask?.cancel()
        loadTask = nil; timeoutTask = nil; isResolving = false; retryScheduled = false
    }
}
