import SwiftUI
import AVKit

struct NativePlayer: UIViewControllerRepresentable {
    let playback: PlaybackModel
    /// What a double tap near an edge did, so the screen can say so for a moment.
    var onDoubleTap: (PlayerTapZone) -> Void = { _ in }
    func makeCoordinator() -> Coordinator { Coordinator(playback: playback, onDoubleTap: onDoubleTap) }
    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = playback.player
        controller.delegate = context.coordinator
        controller.allowsPictureInPicturePlayback = true
        controller.canStartPictureInPictureAutomaticallyFromInline = playback.pipOnLeave
        controller.updatesNowPlayingInfoCenter = false // PlaybackMediaControls owns its session.
        // On `contentOverlayView` — the layer AVKit puts between the picture and its own controls —
        // and deliberately not cancelling the touches it sees: the system's single tap, which is
        // what brings the transport bar back, has to go on working. The first tap of a double tap
        // therefore also raises the bar, which is what every player on this phone does.
        let double = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.doubleTapped))
        double.numberOfTapsRequired = 2
        double.cancelsTouchesInView = false
        controller.contentOverlayView?.addGestureRecognizer(double)
        controller.contentOverlayView?.isUserInteractionEnabled = true
        return controller
    }
    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        controller.player = playback.player
        controller.canStartPictureInPictureAutomaticallyFromInline = playback.pipOnLeave
    }
    @MainActor final class Coordinator: NSObject, AVPlayerViewControllerDelegate {
        private let playback: PlaybackModel
        private let onDoubleTap: (PlayerTapZone) -> Void
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
