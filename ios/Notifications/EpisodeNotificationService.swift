import Foundation
import Observation
import UserNotifications

@MainActor @Observable final class EpisodeNotificationService {
    private(set) var isEnabled = false
    private(set) var authorizationStatus: UNAuthorizationStatus = .notDetermined
    private(set) var errorMessage: String?
    @ObservationIgnored private let center: UNUserNotificationCenter
    @ObservationIgnored private let fileURL: URL
    @ObservationIgnored private var state = NotificationCatalog()
    @ObservationIgnored private var revision = UUID()
    @ObservationIgnored private var readable = true
    @ObservationIgnored private var currentAccount: String?
    static let categoryIdentifier = "KAERU_NEW_EPISODE"
    static let watchActionIdentifier = "KAERU_WATCH_EPISODE"
    private static let releasePrefix = "kaeru.release."

    init(center: UNUserNotificationCenter = .current()) {
        self.center = center
        fileURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Notifications/state.json")
        do {
            if FileManager.default.fileExists(atPath: fileURL.path) { state = try JSONDecoder().decode(NotificationCatalog.self, from: Data(contentsOf: fileURL)) }
            isEnabled = state.enabled
        } catch { readable = false; errorMessage = error.localizedDescription }
    }

    /// Only call from an explicit user action. No init/process/schedule path prompts.
    @discardableResult func requestAuthorization() async -> Bool {
        do {
            let granted = try await center.requestAuthorization(options: [.alert, .sound, .badge])
            await refreshAuthorization()
            return granted
        } catch { errorMessage = error.localizedDescription; return false }
    }
    func refreshAuthorization() async {
        authorizationStatus = await center.notificationSettings().authorizationStatus
    }
    /// Enabling requires already-granted permission; UI calls requestAuthorization first.
    @discardableResult func setEnabled(_ enabled: Bool) async -> Bool {
        guard readable else { return false }
        revision = UUID()
        let requestRevision = revision
        if enabled {
            await refreshAuthorization()
            guard requestRevision == revision, canPost else { return false }
        }
        if enabled && !isEnabled { state.accounts = [:] }
        state.enabled = enabled
        guard save() else { return false }
        isEnabled = enabled
        if !enabled { await removeOurNotifications() }
        else { await registerActions() }
        return true
    }
    /// Call synchronously on account changes/sign-out to fence any suspended process operation.
    func setAccount(_ account: String?) {
        guard currentAccount != account else { return }
        currentAccount = account; revision = UUID()
        // Clear pending and delivered cards immediately; they belong to the previous account.
        let pending = state.requestIDs
        center.removePendingNotificationRequests(withIdentifiers: pending)
        center.removeDeliveredNotifications(withIdentifiers: pending)
        state.requestIDs = []
        save()
    }

    /// Call after a SUCCESSFUL fresh library refresh, on foreground and any OS-granted background refresh.
    /// A failed fetch must not be represented as an empty library. No permission prompt here.
    func process(library: [LibraryItem], progress: [EpisodeProgress], account: String) async {
        guard readable, isEnabled, !account.isEmpty else { return }
        if currentAccount == nil { setAccount(account) }
        guard currentAccount == account else { return }
        let generation = revision
        await refreshAuthorization()
        guard generation == revision, currentAccount == account, isEnabled, canPost else { return }
        var baseline = state.accounts[account] ?? EpisodeReleaseState()
        let releases = baseline.process(library: library, progress: progress)
        state.accounts[account] = baseline
        let ids = releases.map { Self.releasePrefix + String($0.animeID) }
        state.requestIDs = Array(Set(state.requestIDs + ids))
        guard save() else { return } // At-most-once: persist even if posting later fails.
        for release in releases {
            guard generation == revision, currentAccount == account, isEnabled else { return }
            let content = UNMutableNotificationContent()
            content.title = release.title
            content.body = "Вышла серия \(release.aired)." + (release.episode != release.aired ? " Смотреть с серии \(release.episode)." : "")
            content.sound = .default
            content.categoryIdentifier = Self.categoryIdentifier
            content.threadIdentifier = "kaeru.new-episodes"
            content.userInfo = ["url": release.titleURL.absoluteString, "watchURL": release.watchURL.absoluteString]
            do {
                let id = Self.releasePrefix + String(release.animeID)
                try await center.add(UNNotificationRequest(identifier: id, content: content, trigger: nil))
                if generation != revision || currentAccount != account || !isEnabled {
                    center.removePendingNotificationRequests(withIdentifiers: [id]); center.removeDeliveredNotifications(withIdentifiers: [id])
                }
            } catch { errorMessage = error.localizedDescription }
        }
    }

    func clearForPlayback(animeID: Int) {
        let ids = [Self.releasePrefix + String(animeID), EpisodeNotificationPlan.prefix + String(animeID)]
        center.removePendingNotificationRequests(withIdentifiers: ids)
        center.removeDeliveredNotifications(withIdentifiers: ids)
        state.requestIDs.removeAll { ids.contains($0) }
        save()
    }

    /// Forward to the app's existing URL router from UNUserNotificationCenterDelegate.
    static func url(for response: UNNotificationResponse) -> URL? {
        guard response.actionIdentifier != UNNotificationDismissActionIdentifier else { return nil }
        let key = response.actionIdentifier == watchActionIdentifier ? "watchURL" : "url"
        guard let raw = response.notification.request.content.userInfo[key] as? String,
              let url = URL(string: raw), url.scheme == "kaeru", url.host == "anime" else { return nil }
        return url
    }
    func registerActions() async {
        let watch = UNNotificationAction(identifier: Self.watchActionIdentifier, title: "Смотреть", options: [.foreground])
        let category = UNNotificationCategory(identifier: Self.categoryIdentifier, actions: [watch], intentIdentifiers: [], options: [])
        var categories = await center.notificationCategories()
        categories = Set(categories.filter { $0.identifier != Self.categoryIdentifier })
        categories.insert(category)
        center.setNotificationCategories(categories)
    }
    private var canPost: Bool { authorizationStatus.allowsPosting }
    private func removeOurNotifications() async {
        let pending = await center.pendingNotificationRequests()
        let delivered = await center.deliveredNotifications()
        let ours: (String) -> Bool = { $0.hasPrefix(Self.releasePrefix) || $0.hasPrefix(EpisodeNotificationPlan.prefix) }
        center.removePendingNotificationRequests(withIdentifiers: pending.map(\.identifier).filter(ours))
        center.removeDeliveredNotifications(withIdentifiers: delivered.map { $0.request.identifier }.filter(ours))
    }
    @discardableResult private func save() -> Bool {
        guard readable else { return false }
        do {
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try JSONEncoder().encode(state).write(to: fileURL, options: .atomic)
            return true
        } catch { errorMessage = "Не удалось сохранить настройки уведомлений: \(error.localizedDescription)"; return false }
    }
}

private struct NotificationCatalog: Codable {
    var enabled = false
    var accounts: [String: EpisodeReleaseState] = [:]
    var requestIDs: [String] = []
}

extension UNAuthorizationStatus {
    /// The system has said yes, outright or quietly. `.ephemeral` — an App Clip's — exists only on
    /// iOS, and the background check reads the same rule, so it is written once.
    var allowsPosting: Bool {
        #if os(iOS)
        if self == .ephemeral { return true }
        #endif
        return self == .authorized || self == .provisional
    }
}
