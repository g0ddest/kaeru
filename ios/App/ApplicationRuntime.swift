import BackgroundTasks
import Observation
import UIKit
import UserNotifications

@MainActor @Observable final class ApplicationRuntime {
    static let shared = ApplicationRuntime()
    private(set) var model: AppModel?
    var pendingURL: URL?

    func loadModel() throws -> AppModel {
        if let model { return model }
        let configuration = AppConfiguration.bundled
        let value = AppModel(service: SharedService(configuration: configuration), store: try LocalStore(), configuration: configuration, session: try KeychainSession.read())
        model = value
        _ = value.downloads
        _ = value.cast
        value.notifications.setAccount(value.session == nil ? nil : value.accountKey)
        return value
    }
}

@MainActor final class KaeruAppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        EpisodeBackgroundRefresh.register()
        return true
    }

    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        do {
            let model = try ApplicationRuntime.shared.loadModel()
            if !model.downloads.handleBackgroundEvents(identifier: identifier, completionHandler: completionHandler) { completionHandler() }
        } catch { completionHandler() }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void) {
        if let url = EpisodeNotificationService.url(for: response) { ApplicationRuntime.shared.pendingURL = url }
        completionHandler()
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification, withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound, .list])
    }
}

@MainActor enum EpisodeBackgroundRefresh {
    static let identifier = "app.kaeru.ios.new-episodes"

    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            Task { @MainActor in
                guard let task = task as? BGAppRefreshTask else { task.setTaskCompleted(success: false); return }
                let operation = Task { @MainActor in
                    do {
                        let model = try ApplicationRuntime.shared.loadModel()
                        await model.notifications.refreshAuthorization()
                        guard model.session != nil, model.notifications.isEnabled,
                              [.authorized, .provisional, .ephemeral].contains(model.notifications.authorizationStatus) else {
                            task.setTaskCompleted(success: true); return
                        }
                        schedule(enabled: true)
                        await model.reloadLibrary()
                        task.setTaskCompleted(success: !Task.isCancelled && model.error == nil)
                    } catch { task.setTaskCompleted(success: false) }
                }
                task.expirationHandler = { operation.cancel() }
            }
        }
    }

    static func schedule(enabled: Bool) {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier)
        guard enabled else { return }
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 6 * 60 * 60)
        // The OS owns timing and may decline scheduling when background refresh is disabled.
        try? BGTaskScheduler.shared.submit(request)
    }
}
