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
        // `buffering` included, at last: without it this phone never once told the friend it was
        // waiting for the network, so the friend never waited back — and dragged it forward.
        // Read off the player this instant, not off the model's cached position: the cache moves on
        // the tick, and a report that says where the picture was up to a quarter of a second ago
        // is a report the friend will settle a quarter of a second — or a second — away from.
        let live = value.player.currentTime().seconds
        let position = value.snapshot.ready && live.isFinite && live >= 0 ? live : value.snapshot.position
        // `playing` on the wire is what the viewer asked for, not what the engine is managing.
        // `isPlaying` follows `timeControlStatus`, which is `.waitingToPlayAtSpecifiedRate` for as
        // long as a segment is on its way — so a stall went out as «paused, buffering», which is
        // exactly what the friend takes for a pause with an empty buffer and never waits for.
        // AVPlayer's `rate` is the rate that was asked for and stays non-zero through the wait;
        // it is the intent, and a picture stalling on its way to playing is playing, and buffering.
        let playing = value.snapshot.isPlaying || (value.snapshot.buffering && value.player.rate > 0)
        return .init(animeID: value.snapshot.animeID, episode: value.snapshot.episode,
                     translationID: value.snapshot.translation, positionMs: Int64(max(0, position) * 1000),
                     playing: playing, buffering: value.snapshot.buffering, ready: value.snapshot.ready)
    }
    var togetherSupportsRate: Bool { true }
    func togetherPlay() { playback?.setPlaying(true, notify: false) }
    func togetherPause() { playback?.setPlaying(false, notify: false) }
    func togetherSeek(toMilliseconds position: Int64) { playback?.seek(to: Double(max(0, position)) / 1000, notify: false) }
    /// A nudge of three percent, on a player that will actually make one.
    ///
    /// Two things about AVPlayer that the first version of this did not know. The audio pitch
    /// algorithm an item comes with snaps the rate to a handful of values — 0.5, 0.8, 1.0, 1.25,
    /// 1.5 and so on — so 0.97 and 1.03 were both played as 1.0 and a gap under two seconds was
    /// never closed at all: that is the second or two the guest sat behind the host for the whole
    /// evening. `timeDomain` takes any rate and keeps the pitch, and is the one meant for voice.
    /// And a non-zero rate is a play command: normal speed handed to a paused picture starts it,
    /// which used to undo a pause a beat after a correction ended. The rate is only ever touched
    /// while the viewer wants the picture moving — a paused player takes its speed from
    /// `defaultRate` when it is next played, which is the viewer's own.
    func togetherSetRate(_ factor: Float) {
        guard let playback else { return }
        let player = playback.player
        if let item = player.currentItem, item.audioTimePitchAlgorithm != .timeDomain {
            item.audioTimePitchAlgorithm = .timeDomain
        }
        guard player.rate > 0 else { return }
        player.rate = Float(playback.speed) * factor
    }
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
