import SwiftUI
import AVKit

/// AVKit's player on the Mac, and everything this app draws on top of it.
///
/// Not the iPad's arrangement (../NativePlayer.swift). There the overlay has to live inside AVKit's
/// content overlay, because AVKit's expand button takes the picture into a window of its own and
/// anything stacked beside it in SwiftUI is left behind. Here that button is off: what goes full
/// screen is the window, and a plain SwiftUI overlay goes with it — the conversation, the skip
/// button and the next episode included.
///
/// The pointer is the Mac's finger: a click on the picture plays and pauses, a double click makes
/// the window full screen, and the pointer moving brings the controls back.
struct NativePlayer<Overlay: View>: View {
    let playback: PlaybackModel
    /// The iPad's double tap by thirds. A click on a Mac plays and pauses instead, and ← → say
    /// where they went through the player's window (Features/Mac/PlayerWindow.swift); the call
    /// reads the same on both.
    var onDoubleTap: (PlayerTapZone) -> Void = { _ in }
    @ViewBuilder var overlay: () -> Overlay
    /// How far down from the top the window's toolbar reaches over the picture.
    private let toolbarBand: CGFloat = 56
    var body: some View {
        PlayerSurface(playback: playback)
            // Under the title bar too. The toolbar comes and goes with the pointer, and a picture
            // laid out below it would jump every time it did.
            .ignoresSafeArea()
            .overlay { overlay() }
            .onContinuousHover { phase in
                switch phase {
                case .active(let point):
                    // A pointer resting on the toolbar is on its way to a button there.
                    if point.y < toolbarBand { playback.holdChrome() } else { playback.showChrome() }
                case .ended:
                    // Gone out of the window — through the toolbar, say, to the menu bar — and on
                    // its way to no button here: the controls go in their three seconds, rather
                    // than staying over the picture until the pointer comes back.
                    playback.showChrome()
                }
            }
            // The pointer goes with the controls, as in every player on this system.
            .pointerVisibility(playback.chromeVisible ? .automatic : .hidden)
            // Up when the episode starts or stops, gone three seconds into playing — without
            // anybody having to move the pointer first. The iPad waits for a tap instead.
            .onChange(of: playback.isPlaying, initial: true) { _, _ in playback.showChrome() }
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
        // No speed menu of AVKit's: it would set the player's rate behind the model's back, and the
        // friend and the saved preference would never hear of it. Скорость is in «…» and in the
        // «Воспроизведение» menu, through `PlaybackModel.setSpeed`.
        view.speeds = []
        // Clicks on the picture. In AVKit's content overlay — above the picture and below AVKit's
        // bar — so the bar keeps every click of its own.
        if let overlay = view.contentOverlayView {
            let clicks = ClickCatcher(frame: overlay.bounds)
            clicks.autoresizingMask = [.width, .height]
            clicks.clicked = { [weak coordinator = context.coordinator, weak clicks] count in
                coordinator?.clicked(count, window: clicks?.window)
            }
            overlay.addSubview(clicks)
        }
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
        /// One click plays or pauses, as in the web player and QuickTime. The second click of a
        /// double click puts that back — the picture carries on as it was — and fills the screen.
        func clicked(_ count: Int, window: NSWindow?) {
            switch count {
            case 1: playback.setPlaying(!playback.wantsPlayback)
            case 2:
                playback.setPlaying(!playback.wantsPlayback)
                window?.toggleFullScreen(nil)
            default: break
            }
        }
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

/// A transparent layer over the picture that hears clicks and nothing else.
private final class ClickCatcher: NSView {
    var clicked: (Int) -> Void = { _ in }
    /// A view that is not opaque offers its clicks to the window to be dragged by, and a press on
    /// the picture then moved the window instead of pausing the episode.
    override var mouseDownCanMoveWindow: Bool { false }
    /// Kept here. Passed on, as a view passes on what it does not handle, the release reached
    /// AVKit's own view underneath, which toggled the picture straight back.
    override func mouseUp(with event: NSEvent) {}
    override func mouseDragged(with event: NSEvent) {}
    override func mouseDown(with event: NSEvent) { clicked(event.clickCount) }
}
