import Foundation
import Observation

@MainActor @Observable final class ApplicationRuntime {
    static let shared = ApplicationRuntime()
    private(set) var model: AppModel?
    var pendingURL: URL?

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
