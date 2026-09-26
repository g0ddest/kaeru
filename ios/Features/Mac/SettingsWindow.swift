import SwiftUI

/// Kaeru ▸ Настройки… (⌘,): the settings the iPad raises as a sheet, in the Mac's own window for
/// them — so they can stay open beside the catalogue, and sign-in attaches to them.
struct SettingsWindow: View {
    var body: some View {
        let runtime = ApplicationRuntime.shared
        Group {
            if let model = runtime.model {
                SettingsView()
                    .environment(model)
                    .preferredColorScheme(AppAppearance(stored: model.preferences.appearance).colorScheme)
            } else {
                // The main window opens the app's data; until it has, there is nothing to set.
                ProgressView()
            }
        }
        .tint(Palette.accent)
        // One size, as a settings window has, with room for what is pushed inside it — the
        // downloads, the room, the release notes — which scroll within it.
        .frame(width: 620, height: 680)
    }
}
