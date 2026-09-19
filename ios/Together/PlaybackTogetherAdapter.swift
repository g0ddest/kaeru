import Foundation

/// Bridges the local AVPlayer model to the transport without making remote commands emit local
/// actions again. One adapter is retained by the player for the duration of its full-screen route.
@MainActor final class PlaybackTogetherAdapter: TogetherPlayback {
    private weak var playback: PlaybackModel?
    private weak var manager: TogetherManager?

    init(playback: PlaybackModel, manager: TogetherManager) {
        self.playback = playback; self.manager = manager
        manager.attach(self)
        playback.onLocalAction = { [weak self] action in
            guard let self, let manager = self.manager else { return }
            switch action {
            case .playing(let value): value ? manager.sendPlay() : manager.sendPause()
            case .seek(let value): manager.sendSeek(Int64(max(0, value) * 1000))
            case .episode(let value):
                let snapshot = self.togetherSnapshot
                guard let animeID = snapshot.animeID else { return }
                manager.sendEpisode(.init(animeID: animeID, episode: value,
                                          translationID: snapshot.translationID, positionMs: snapshot.positionMs))
            case .speed: break
            }
        }
    }

    var togetherSnapshot: TogetherPlaybackSnapshot {
        guard let value = playback else { return .init() }
        return .init(animeID: value.snapshot.animeID, episode: value.snapshot.episode,
                     translationID: value.snapshot.translation, positionMs: Int64(max(0, value.snapshot.position) * 1000),
                     playing: value.snapshot.isPlaying, ready: value.snapshot.ready)
    }
    var togetherSupportsRate: Bool { true }
    func togetherPlay() { playback?.setPlaying(true, notify: false) }
    func togetherPause() { playback?.setPlaying(false, notify: false) }
    func togetherSeek(toMilliseconds position: Int64) { playback?.seek(to: Double(max(0, position)) / 1000, notify: false) }
    func togetherSetRate(_ factor: Float) { playback?.player.rate = factor }
    func togetherDuck(_ on: Bool) { playback?.setDucked(on) }
    func togetherOpen(_ episode: TogetherEpisode) async throws {
        guard let playback else { throw TogetherError.playbackUnavailable }
        playback.selectEpisode(episode.episode, position: Double(episode.positionMs) / 1000, play: false, notify: false)
    }
    func close() {
        if let playback, let manager { manager.detach(self); playback.onLocalAction = nil }
        self.playback = nil; self.manager = nil
    }
}
