import SwiftUI
import AVKit

/// AVKit's player on the Mac, and everything this app draws on top of it.
///
/// Not the iPad's arrangement (../NativePlayer.swift). There the overlay has to live inside AVKit's
/// content overlay, because AVKit's expand button takes the picture into a window of its own and
/// anything stacked beside it in SwiftUI is left behind. Here that button is off: what goes full
/// screen is the window, and a plain SwiftUI overlay goes with it — the conversation, the skip
/// button and the next episode included.
struct NativePlayer<Overlay: View>: View {
    let playback: PlaybackModel
    /// The iPad's double tap by thirds. A Mac player has no taps; the call reads the same on both.
    var onDoubleTap: (PlayerTapZone) -> Void = { _ in }
    @ViewBuilder var overlay: () -> Overlay
    var body: some View {
        PlayerSurface(playback: playback)
            .overlay { overlay() }
            // No tap brings the controls back on a Mac. The pointer does, as in every player here.
            .onContinuousHover { phase in if case .active = phase { playback.showChrome() } }
    }
}

private struct PlayerSurface: NSViewRepresentable {
    let playback: PlaybackModel
    func makeCoordinator() -> Coordinator { Coordinator(playback: playback) }
    func makeNSView(context: Context) -> AVPlayerView {
        let view = AVPlayerView()
        view.player = playback.player
        // The bar along the bottom, as on the iPad, rather than the floating panel a viewer can
        // drag over the conversation.
        view.controlsStyle = .inline
        // AVKit's own full screen is a window of AVKit's, which the overlay would not follow.
        view.showsFullScreenToggleButton = false
        view.allowsPictureInPicturePlayback = true
        view.pictureInPictureDelegate = context.coordinator
        view.updatesNowPlayingInfoCenter = false // PlaybackMediaControls owns Now Playing.
        // A signed Kodik address is nothing to share, and a film is not stepped frame by frame.
        view.showsSharingServiceButton = false
        view.showsFrameSteppingButtons = false
        return view
    }
    func updateNSView(_ view: AVPlayerView, context: Context) {
        if view.player !== playback.player { view.player = playback.player }
    }
    static func dismantleNSView(_ view: AVPlayerView, coordinator: Coordinator) { view.player = nil }

    /// The picture-in-picture callbacks, as on the iPad. Their Swift names are the importer's
    /// («…Picture(inPicture:)»); a near miss would compile as a plain method nobody calls.
    @MainActor final class Coordinator: NSObject, @preconcurrency AVPlayerViewPictureInPictureDelegate {
        private let playback: PlaybackModel
        init(playback: PlaybackModel) { self.playback = playback }
        func playerViewWillStartPicture(inPicture playerView: AVPlayerView) { playback.setPictureInPicture(true) }
        func playerView(_ playerView: AVPlayerView, failedToStartPictureInPictureWithError error: Error) {
            playback.setPictureInPicture(false)
        }
        func playerViewDidStopPicture(inPicture playerView: AVPlayerView) { playback.setPictureInPicture(false) }
        func playerViewShouldAutomaticallyDismissAtPicture(inPictureStart playerView: AVPlayerView) -> Bool { false }
        func playerView(_ playerView: AVPlayerView, restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
            completionHandler(playerView.window != nil)
        }
    }
}
