import SwiftUI
import AVKit

struct NativePlayer: UIViewControllerRepresentable {
    let playback: PlaybackModel
    func makeCoordinator() -> Coordinator { Coordinator(playback: playback) }
    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = playback.player
        controller.delegate = context.coordinator
        controller.allowsPictureInPicturePlayback = true
        controller.canStartPictureInPictureAutomaticallyFromInline = playback.pipOnLeave
        controller.updatesNowPlayingInfoCenter = false // PlaybackMediaControls owns its session.
        return controller
    }
    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        controller.player = playback.player
        controller.canStartPictureInPictureAutomaticallyFromInline = playback.pipOnLeave
    }
    @MainActor final class Coordinator: NSObject, AVPlayerViewControllerDelegate {
        private let playback: PlaybackModel
        init(playback: PlaybackModel) { self.playback = playback }
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
