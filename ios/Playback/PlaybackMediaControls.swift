import AVFoundation
import MediaPlayer

/// Session-scoped commands avoid taking ownership of another player's global handlers.
@MainActor final class PlaybackMediaControls: NSObject {
    private weak var playback: PlaybackModel?
    private let session: MPNowPlayingSession
    private var activeRequested = false
    init(playback: PlaybackModel) {
        self.playback = playback
        session = MPNowPlayingSession(players: [playback.player])
        super.init()
        session.automaticallyPublishesNowPlayingInfo = false
        let commands = session.remoteCommandCenter
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
        let commands = session.remoteCommandCenter
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
        session.nowPlayingInfoCenter.nowPlayingInfo = [
            MPMediaItemPropertyTitle: title,
            MPMediaItemPropertyAlbumTitle: "Серия \(snapshot.episode)",
            MPMediaItemPropertyPlaybackDuration: snapshot.duration,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: snapshot.position,
            MPNowPlayingInfoPropertyPlaybackRate: snapshot.isPlaying ? snapshot.speed : 0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: snapshot.speed,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.video.rawValue
        ]
        if snapshot.isPlaying, !activeRequested {
            activeRequested = true
            session.becomeActiveIfPossible { [weak self] active in
                Task { @MainActor in if !active { self?.activeRequested = false } }
            }
        }
    }
    func close() {
        let commands = session.remoteCommandCenter
        [commands.playCommand, commands.pauseCommand, commands.togglePlayPauseCommand,
         commands.changePlaybackPositionCommand, commands.skipForwardCommand, commands.skipBackwardCommand,
         commands.nextTrackCommand, commands.changePlaybackRateCommand].forEach { $0.removeTarget(self) }
        session.nowPlayingInfoCenter.nowPlayingInfo = nil
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
