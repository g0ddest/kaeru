import Foundation
import Observation

@MainActor @Observable final class ApplicationRuntime {
    static let shared = ApplicationRuntime()
    private(set) var model: AppModel?
    var pendingURL: URL?
    #if os(macOS)
    /// The episode in the player's window. The Mac plays in a window of its own rather than over
    /// the screen that asked, and a window is opened by id — so what it is to show waits here.
    var nowPlaying: PlaybackRoute?
    #endif

    func loadModel(discardingCache: Bool = false) throws -> AppModel {
        if let model, !discardingCache { return model }
        let configuration = AppConfiguration.bundled
        let store = discardingCache ? try LocalStore.discardingCache() : try LocalStore()
        let value = AppModel(service: SharedService(configuration: configuration), store: store, configuration: configuration, session: try KeychainSession.read())
        model = value
        _ = value.downloads
        _ = value.cast
        value.notifications.setAccount(value.session == nil ? nil : value.accountKey)
        return value
    }
}
