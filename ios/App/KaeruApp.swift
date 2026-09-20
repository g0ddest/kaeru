import SwiftUI

@main
struct KaeruApp: App {
    @UIApplicationDelegateAdaptor(KaeruAppDelegate.self) private var appDelegate
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
            .tint(Palette.accent)
        }
    }
    @MainActor private func initialize() {
        do {
            model = try ApplicationRuntime.shared.loadModel()
        } catch { startupError = error.localizedDescription }
    }
}
