import AppKit
import UserNotifications

// The Mac side of the application: AppKit's delegate and the check for new episodes. The iPhone
// and iPad bring their own (../KaeruAppDelegate.swift); generate_project.rb gives this folder to
// the Mac app only.
@MainActor final class KaeruAppDelegate: NSObject, NSApplicationDelegate, @preconcurrency UNUserNotificationCenterDelegate {
    // Will rather than did: a click on a notification can be what launched the app, and it is
    // handed over before launching finishes — to whatever delegate the centre has by then.
    // There is no `application(_:open:)` here on purpose: with SwiftUI's lifecycle it would take
    // `kaeru://` links away from `onOpenURL`, and nothing would open them.
    func applicationWillFinishLaunching(_ notification: Notification) {
        // First, so a crash anywhere after this is one that gets reported.
        Reporting.install()
        UNUserNotificationCenter.current().delegate = self
    }

    /// Closing the window is not quitting a Mac app: the check for new episodes and the downloads
    /// go on, and the Dock brings the window back.
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        if let url = EpisodeNotificationService.url(for: response) { ApplicationRuntime.shared.pendingURL = url }
        completionHandler()
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound, .list])
    }
}

/// The check for new episodes, every six hours or so while Kaeru is running.
///
/// A Mac has no `BGTaskScheduler` and does not launch an app to refresh it; what it has is a
/// scheduler inside the process, which the system runs when the machine can spare it. So the
/// check happens only while the app is open — with or without a window — and the settings footer
/// says as much. The rule for whether to check at all is the iPad's.
@MainActor enum EpisodeBackgroundRefresh {
    static let identifier = "app.kaeru.mac.new-episodes"
    private static var scheduler: NSBackgroundActivityScheduler?

    static func schedule(enabled: Bool) {
        scheduler?.invalidate()
        scheduler = nil
        guard enabled else { return }
        let activity = NSBackgroundActivityScheduler(identifier: identifier)
        activity.repeats = true
        activity.interval = 6 * 60 * 60
        activity.tolerance = 60 * 60
        activity.qualityOfService = .utility
        // Called on a queue of the scheduler's own; the work belongs to the main actor, and the
        // completion is what lets the scheduler plan the next run.
        activity.schedule { completion in
            Task { @MainActor in
                await refresh()
                completion(.finished)
            }
        }
        scheduler = activity
    }

    private static func refresh() async {
        guard let model = try? ApplicationRuntime.shared.loadModel() else { return }
        await model.notifications.refreshAuthorization()
        guard model.session != nil, model.notifications.isEnabled,
              model.notifications.authorizationStatus.allowsPosting else { return }
        await model.reloadLibrary()
    }
}
