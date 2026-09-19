import SwiftUI

@main
struct KaeruApp: App {
    @State private var model: AppModel?
    @State private var startupError: String?
    var body: some Scene {
        WindowGroup {
            Group {
                if let model { RootView().environment(model) }
                else if let startupError {
                    ContentUnavailableView("Не удалось открыть Kaeru", systemImage: "externaldrive.badge.exclamationmark", description: Text(startupError))
                } else { ProgressView().task { initialize() } }
            }
            .tint(.indigo)
        }
    }
    @MainActor private func initialize() {
        do {
            let store = try LocalStore()
            let configuration = AppConfiguration.bundled
            model = AppModel(service: SharedService(configuration: configuration), store: store, configuration: configuration, session: try KeychainSession.read())
        } catch { startupError = error.localizedDescription }
    }
}
