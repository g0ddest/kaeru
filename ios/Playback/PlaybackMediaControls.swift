import AVFoundation
import MediaPlayer

/// Session-scoped commands avoid taking ownership of another player's global handlers.
///
/// The Mac has no now-playing session: its media keys, the menu bar's Now Playing and the
/// headphones all read the process's one command centre and one info centre. There is only ever
/// one player at a time, so that is the same thing — plus `playbackState`, which the Mac reads
/// where iOS reads the session's activity.
@MainActor final class PlaybackMediaControls: NSObject {
    private weak var playback: PlaybackModel?
    #if os(iOS)
    private let session: MPNowPlayingSession
    private var activeRequested = false
    private var commands: MPRemoteCommandCenter { session.remoteCommandCenter }
    private var nowPlaying: MPNowPlayingInfoCenter { session.nowPlayingInfoCenter }
    #else
    private var commands: MPRemoteCommandCenter { .shared() }
    private var nowPlaying: MPNowPlayingInfoCenter { .default() }
    #endif
    init(playback: PlaybackModel) {
        self.playback = playback
        #if os(iOS)
        session = MPNowPlayingSession(players: [playback.player])
        #endif
        super.init()
        #if os(iOS)
        session.automaticallyPublishesNowPlayingInfo = false
        #endif
        commands.playCommand.addTarget(self, action: #selector(play(_:)))
        commands.pauseCommand.addTarget(self, action: #selector(pause(_:)))
        commands.togglePlayPauseCommand.addTarget(self, action: #selector(toggle(_:)))
        commands.changePlaybackPositionCommand.addTarget(self, action: #selector(seek(_:)))
        commands.skipForwardCommand.addTarget(self, action: #selector(forward(_:)))
        commands.skipBackwardCommand.addTarget(self, action: #selector(backward(_:)))
        commands.nextTrackCommand.addTarget(self, action: #selector(next(_:)))
        commands.changePlaybackRateCommand.addTarget(self, action: #selector(rate(_:)))
        commands.changePlaybackRateCommand.supportedPlaybackRates = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2]
    }
    func update(snapshot: PlaybackSnapshot, title: String, skipSeconds: Int, hasNext: Bool) {
        commands.playCommand.isEnabled = snapshot.ready
        commands.pauseCommand.isEnabled = snapshot.ready
        commands.togglePlayPauseCommand.isEnabled = snapshot.ready
        commands.changePlaybackPositionCommand.isEnabled = snapshot.ready && snapshot.duration > 0
        commands.nextTrackCommand.isEnabled = snapshot.ready && hasNext
        commands.skipForwardCommand.isEnabled = snapshot.ready
        commands.skipBackwardCommand.isEnabled = snapshot.ready
        commands.changePlaybackRateCommand.isEnabled = snapshot.ready
        commands.skipForwardCommand.preferredIntervals = [NSNumber(value: skipSeconds)]
        commands.skipBackwardCommand.preferredIntervals = [NSNumber(value: skipSeconds)]
        nowPlaying.nowPlayingInfo = [
            MPMediaItemPropertyTitle: title,
            MPMediaItemPropertyAlbumTitle: "Серия \(snapshot.episode)",
            MPMediaItemPropertyPlaybackDuration: snapshot.duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: snapshot.position,
            MPNowPlayingInfoPropertyPlaybackRate: snapshot.isPlaying ? snapshot.speed : 0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: snapshot.speed,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.video.rawValue
        ]
        #if os(iOS)
        if snapshot.isPlaying, !activeRequested {
            activeRequested = true
            session.becomeActiveIfPossible { [weak self] active in
                Task { @MainActor in if !active { self?.activeRequested = false } }
            }
        }
        #else
        nowPlaying.playbackState = snapshot.isPlaying ? .playing : snapshot.ready ? .paused : .stopped
        #endif
    }
    func close() {
        [commands.playCommand, commands.pauseCommand, commands.togglePlayPauseCommand,
         commands.changePlaybackPositionCommand, commands.skipForwardCommand, commands.skipBackwardCommand,
         commands.nextTrackCommand, commands.changePlaybackRateCommand].forEach { $0.removeTarget(self) }
        nowPlaying.nowPlayingInfo = nil
        #if os(macOS)
        nowPlaying.playbackState = .stopped
        #endif
        playback = nil
    }
    @objc private func play(_ event: MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus {
        guard let playback, playback.snapshot.ready else { return .noSuchContent }
        playback.setPlaying(true); return .success
    }
    @objc private func pause(_ event: MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus {
        guard let playback else { return .noSuchContent }
        playback.setPlaying(false); return .success
    }
    @objc private func toggle(_ event: MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus {
        guard let playback, playback.snapshot.ready else { return .noSuchContent }
        playback.setPlaying(!playback.isPlaying); return .success
    }
    @objc private func seek(_ event: MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus {
        guard let playback, playback.snapshot.ready, let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
        playback.seek(to: event.positionTime); return .success
    }
    @objc private func forward(_ event: MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus {
        guard let playback, playback.snapshot.ready else { return .noSuchContent }
        playback.seek(by: Double(playback.skipSeconds)); return .success
    }
    @objc private func backward(_ event: MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus {
        guard let playback, playback.snapshot.ready else { return .noSuchContent }
        playback.seek(by: -Double(playback.skipSeconds)); return .success
    }
    @objc private func next(_ event: MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus {
        guard let playback, playback.snapshot.ready, playback.hasNext else { return .noSuchContent }
        playback.nextNow(); return .success
    }
    @objc private func rate(_ event: MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus {
        guard let playback, let event = event as? MPChangePlaybackRateCommandEvent else { return .commandFailed }
        playback.setSpeed(Double(event.playbackRate)); return .success
    }
}
