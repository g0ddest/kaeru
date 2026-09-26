import SwiftUI

@main
struct KaeruApp: App {
    #if os(iOS)
    @UIApplicationDelegateAdaptor(KaeruAppDelegate.self) private var appDelegate
    #else
    @NSApplicationDelegateAdaptor(KaeruAppDelegate.self) private var appDelegate
    #endif
    @State private var model: AppModel?
    @State private var startupError: String?
    var body: some Scene {
        WindowGroup {
            Group {
                if let model { RootView().environment(model) }
                else if let startupError {
                    ContentUnavailableView {
                        Label("Не удалось открыть Kaeru", systemImage: "externaldrive.badge.exclamationmark")
                    } description: {
                        Text(startupError)
                    } actions: {
                        // The only thing standing between the viewer and a working application is a
                        // cache of what the servers can say again, so there is always a way out.
                        Button("Очистить кэш и открыть") { retry() }.buttonStyle(.borderedProminent)
                    }
                } else { ProgressView().task { initialize() } }
            }
            .tint(Palette.accent)
        }
    }
    @MainActor private func initialize() {
        do {
            model = try ApplicationRuntime.shared.loadModel()
        } catch { startupError = error.localizedDescription }
    }

    /// Second attempt, with the local cache thrown away. Sessions and downloads survive it.
    @MainActor private func retry() {
        startupError = nil
        do {
            model = try ApplicationRuntime.shared.loadModel(discardingCache: true)
        } catch { startupError = error.localizedDescription }
    }
}
