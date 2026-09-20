import SwiftUI
import AVKit

/// AVKit's player, and everything this app draws on top of it.
///
/// The overlay is hosted inside `contentOverlayView` rather than stacked over this view in SwiftUI,
/// and that is the whole point of the generic parameter: AVKit's expand button presents a window of
/// its own, over everything of this app's, and anything sitting in a SwiftUI stack behind it simply
/// disappears at the moment people actually start watching. What is in the content overlay belongs
/// to the player view controller and goes full screen with it — the conversation, the skip button
/// and the next episode included.
struct NativePlayer<Overlay: View>: UIViewControllerRepresentable {
    let playback: PlaybackModel
    /// What a double tap near an edge did, so the screen can say so for a moment.
    var onDoubleTap: (PlayerTapZone) -> Void = { _ in }
    @ViewBuilder var overlay: () -> Overlay
    func makeCoordinator() -> Coordinator { Coordinator(playback: playback, onDoubleTap: onDoubleTap) }
    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = playback.player
        controller.delegate = context.coordinator
        controller.allowsPictureInPicturePlayback = true
        controller.canStartPictureInPictureAutomaticallyFromInline = playback.pipOnLeave
        controller.updatesNowPlayingInfoCenter = false // PlaybackMediaControls owns its session.
        // Forces the view to load, because everything below hangs off the content overlay.
        _ = controller.view
        // On `contentOverlayView` — the layer AVKit puts between the picture and its own controls —
        // and deliberately not cancelling the touches it sees: the system's single tap, which is
        // what brings the transport bar back, has to go on working. The first tap of a double tap
        // therefore also raises the bar, which is what every player on this phone does.
        let double = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.doubleTapped))
        double.numberOfTapsRequired = 2
        double.cancelsTouchesInView = false
        controller.contentOverlayView?.addGestureRecognizer(double)
        controller.contentOverlayView?.isUserInteractionEnabled = true
        if let container = controller.contentOverlayView {
            let host = UIHostingController(rootView: overlay())
            host.view.backgroundColor = .clear
            // Only what the overlay actually draws answers a touch; the space between its chips
            // belongs to AVKit, which is what keeps the single tap that raises the transport bar.
            host.view.translatesAutoresizingMaskIntoConstraints = false
            controller.addChild(host)
            container.addSubview(host.view)
            NSLayoutConstraint.activate([
                host.view.leadingAnchor.constraint(equalTo: container.leadingAnchor),
                host.view.trailingAnchor.constraint(equalTo: container.trailingAnchor),
                host.view.topAnchor.constraint(equalTo: container.topAnchor),
                host.view.bottomAnchor.constraint(equalTo: container.bottomAnchor)
            ])
            host.didMove(toParent: controller)
            context.coordinator.host = host
        }
        return controller
    }
    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        controller.player = playback.player
        controller.canStartPictureInPictureAutomaticallyFromInline = playback.pipOnLeave
        (context.coordinator.host as? UIHostingController<Overlay>)?.rootView = overlay()
    }
    @MainActor final class Coordinator: NSObject, @preconcurrency AVPlayerViewControllerDelegate {
        private let playback: PlaybackModel
        private let onDoubleTap: (PlayerTapZone) -> Void
        var host: UIViewController?
        init(playback: PlaybackModel, onDoubleTap: @escaping (PlayerTapZone) -> Void) {
            self.playback = playback; self.onDoubleTap = onDoubleTap
        }
        /// Ten seconds either way, by which third of the picture the finger landed on — the same
        /// rule as Android's `GestureMath.doubleTapZone`.
        @objc func doubleTapped(_ recognizer: UITapGestureRecognizer) {
            guard let view = recognizer.view else { return }
            let zone = PlayerGestures.zone(x: recognizer.location(in: view).x, width: view.bounds.width)
            guard zone != .middle else { return }
            let step = Double(playback.skipSeconds)
            playback.seek(by: zone == .back ? -step : step)
            onDoubleTap(zone)
        }
        func playerViewControllerWillStartPictureInPicture(_ playerViewController: AVPlayerViewController) {
            playback.setPictureInPicture(true)
        }
        func playerViewController(_ playerViewController: AVPlayerViewController, failedToStartPictureInPictureWithError error: Error) {
            playback.setPictureInPicture(false)
        }
        func playerViewControllerDidStopPictureInPicture(_ playerViewController: AVPlayerViewController) {
            playback.setPictureInPicture(false)
        }
        func playerViewControllerShouldAutomaticallyDismissAtPictureInPictureStart(_ playerViewController: AVPlayerViewController) -> Bool { false }
        func playerViewController(_ playerViewController: AVPlayerViewController, restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
            // This container stays presented when PiP starts; only report restoration if attached.
            completionHandler(playerViewController.viewIfLoaded?.window != nil)
        }
    }
}
