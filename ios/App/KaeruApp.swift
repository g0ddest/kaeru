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
        #if os(iOS)
        WindowGroup { root }
        #else
        // One main window. RootView answers deep links, notifications and invitations, and a
        // second one would answer each of them twice — so no New Window: the group's own commands
        // are removed, which takes that item and leaves Close (⌘W) to the Window menu. Emptying
        // `.newItem` instead took the whole File menu with it, Close included. A group rather than
        // a single `Window` all the same: closing it does not quit an app whose player is still
        // playing. «Каталог» (⌘0) and the Dock bring it back (Mac/CatalogueWindow.swift).
        WindowGroup(id: CatalogueWindow.id) { root.frame(minWidth: 900, minHeight: 600).font(.kaeruBody).catalogueWindow() }
            .defaultSize(width: 1280, height: 820)
            .windowResizability(.contentMinSize)
            .commandsRemoved()
            .commands {
                SidebarCommands()
                CatalogueCommands()
                PlayerCommands()
            }
        // The player: one window, as there is one playback at a time (`AppModel.playersOpen`).
        // Never brought back at launch — an empty player is nothing to restore — and not in the
        // Window menu, which would open it with nothing to show.
        Window("Плеер", id: PlayerWindow.id) { PlayerWindow().font(.kaeruBody) }
            // A window of the first rank. A single `Window` is otherwise an associated one, which
            // only follows another window into full screen: the green button, F and a double
            // click did nothing at all.
            .windowManagerRole(.principal)
            .defaultSize(width: 1280, height: 720)
            .windowResizability(.contentMinSize)
            .windowToolbarStyle(.unified(showsTitle: true))
            .restorationBehavior(.disabled)
            .defaultLaunchBehavior(.suppressed)
            .commandsRemoved()
        Settings { SettingsWindow() }
            .windowResizability(.contentSize)
        #endif
    }
    private var root: some View {
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
