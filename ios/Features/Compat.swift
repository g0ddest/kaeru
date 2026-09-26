import SwiftUI
#if os(iOS)
import UIKit
#else
import AppKit
#endif

// The seams between the iPad and the Mac, each in one place. Every screen was written against
// UIKit's navigation bar, full-screen covers and pasteboard, and SwiftUI on the Mac has none of
// them; a call site names what it wants here, iOS gets exactly the call it always made, and the
// Mac gets the nearest thing it has.

/// How a navigation title sits. A Mac window has one title, in its title bar, and no modes.
enum KaeruTitleDisplay { case inline, large }

extension View {
    @ViewBuilder func kaeruTitleDisplay(_ mode: KaeruTitleDisplay) -> some View {
        #if os(iOS)
        navigationBarTitleDisplayMode(mode == .inline ? .inline : .large)
        #else
        self
        #endif
    }

    /// A field for links, tokens and codes: never capitalised, never corrected. The Mac does not
    /// capitalise a text field to begin with.
    @ViewBuilder func kaeruPlainTextInput() -> some View {
        #if os(iOS)
        textInputAutocapitalization(.never).autocorrectionDisabled()
        #else
        autocorrectionDisabled()
        #endif
    }

    /// Whether the bar over a screen paints its background: the navigation bar on iOS, the
    /// window's toolbar on the Mac.
    @ViewBuilder func kaeruBarBackground(_ visibility: Visibility) -> some View {
        #if os(iOS)
        toolbarBackground(visibility, for: .navigationBar)
        #else
        toolbarBackground(visibility, for: .windowToolbar)
        #endif
    }

    /// Whether that bar is there at all.
    @ViewBuilder func kaeruBar(_ visibility: Visibility) -> some View {
        #if os(iOS)
        toolbar(visibility, for: .navigationBar)
        #else
        toolbar(visibility, for: .windowToolbar)
        #endif
    }

    /// A form laid out the way iOS lays one out. The Mac's own default is two columns — labels
    /// right-aligned on the left — which turns a settings page of sections and footers into a
    /// dialog box.
    @ViewBuilder func kaeruGroupedForm() -> some View {
        #if os(iOS)
        self
        #else
        formStyle(.grouped)
        #endif
    }

    /// The player, over whatever asked for it: a full-screen cover on iOS. The Mac has no such
    /// thing; there the episode goes to the player's own window, and the screen that asked stays
    /// where it was, to be browsed while the episode plays.
    func playerPresentation(item route: Binding<PlaybackRoute?>) -> some View {
        modifier(PlayerPresentation(route: route))
    }

    /// Something that has to be answered before anything else: a full-screen cover on iOS, a sheet
    /// over the window on the Mac.
    @ViewBuilder func kaeruCover<Content: View>(isPresented: Binding<Bool>, onDismiss: (() -> Void)? = nil,
                                               @ViewBuilder content: @escaping () -> Content) -> some View {
        #if os(iOS)
        fullScreenCover(isPresented: isPresented, onDismiss: onDismiss, content: content)
        #else
        sheet(isPresented: isPresented, onDismiss: onDismiss) { content().frame(minWidth: 480, minHeight: 560) }
        #endif
    }

    /// The smallest a sheet may be. A sheet on the Mac is as big as what is in it, and a list or a
    /// form in one has no size of its own — it collapses to a strip. iOS gives a sheet the screen.
    @ViewBuilder func kaeruSheetSize(minWidth: CGFloat, minHeight: CGFloat) -> some View {
        #if os(iOS)
        self
        #else
        frame(minWidth: minWidth, minHeight: minHeight)
        #endif
    }

    /// What a pointer resting on something says about it: a tooltip on the Mac. The text is the
    /// control's accessibility label, so the two never disagree; iOS has no pointer to rest.
    @ViewBuilder func kaeruHelp(_ text: String) -> some View {
        #if os(iOS)
        self
        #else
        help(text)
        #endif
    }

    /// What a right click on it offers. Where iOS wanted a long press it already has its own
    /// `contextMenu`; this is for the places a Mac user right-clicks and a phone user never would.
    @ViewBuilder func kaeruContextMenu<Items: View>(@ViewBuilder _ items: () -> Items) -> some View {
        #if os(iOS)
        self
        #else
        contextMenu(menuItems: items)
        #endif
    }

    /// How it answers the pointer passing over it. A finger has nothing to show before it lands;
    /// a pointer does, and a Mac where cards do not react to it feels like a picture of an app.
    @ViewBuilder func kaeruHover(_ style: KaeruHoverStyle) -> some View {
        #if os(iOS)
        self
        #else
        modifier(HoverHighlight(style: style))
        #endif
    }

    /// Pull to refresh on iOS. A Mac has no pull, so it gets what a Mac app has instead: a button
    /// in the toolbar, and ⌘R while the screen is the one on show.
    @ViewBuilder func kaeruRefreshable(_ action: @escaping @MainActor @Sendable () async -> Void) -> some View {
        #if os(iOS)
        refreshable(action: action)
        #else
        modifier(RefreshButton(action: action))
        #endif
    }

    /// Esc, said to a field: «never mind». iOS has no key for it.
    @ViewBuilder func kaeruExitCommand(_ action: @escaping () -> Void) -> some View {
        #if os(iOS)
        self
        #else
        onExitCommand(perform: action)
        #endif
    }
}

/// What reacts, and how, when the pointer passes over it.
enum KaeruHoverStyle {
    /// Artwork: lifted towards the viewer — a touch larger, brighter, with a shadow under it.
    case lift
    /// A row in a list of rows: its background lights up.
    case row
    /// A control drawn over the picture: a shade brighter.
    case chip
}

extension ToolbarItemPlacement {
    /// The trailing end of the bar. A Mac toolbar has no «top bar»; its primary actions sit there.
    static var kaeruTrailing: Self {
        #if os(iOS)
        .topBarTrailing
        #else
        .primaryAction
        #endif
    }
}

#if os(iOS)
private struct PlayerPresentation: ViewModifier {
    @Environment(AppModel.self) private var model
    @Binding var route: PlaybackRoute?
    func body(content: Content) -> some View {
        content.fullScreenCover(item: $route) { PlayerScreen(anime: $0.anime, episode: $0.episode, model: model) }
    }
}
#else
/// The route is handed to the player's window and let go of at once: the screen that asked keeps
/// nothing presented, so a later tap on another episode asks again and the window changes episode.
private struct PlayerPresentation: ViewModifier {
    @Environment(\.openWindow) private var openWindow
    @Binding var route: PlaybackRoute?
    func body(content: Content) -> some View {
        content.onChange(of: route?.id) { _, id in
            guard id != nil, let asked = route else { return }
            ApplicationRuntime.shared.nowPlaying = asked
            openWindow(id: PlayerWindow.id)
            route = nil
        }
    }
}

private struct HoverHighlight: ViewModifier {
    let style: KaeruHoverStyle
    @State private var hovering = false
    func body(content: Content) -> some View {
        styled(content)
            .onHover { inside in withAnimation(.easeOut(duration: 0.15)) { hovering = inside } }
    }
    @ViewBuilder private func styled(_ content: Content) -> some View {
        switch style {
        case .lift:
            content
                .brightness(hovering ? 0.06 : 0)
                .scaleEffect(hovering ? 1.03 : 1)
                .shadow(color: .black.opacity(hovering ? 0.28 : 0), radius: hovering ? 12 : 0, y: hovering ? 6 : 0)
                .zIndex(hovering ? 1 : 0)
        case .row:
            content.background {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(Palette.elevated.opacity(hovering ? 1 : 0))
                    .padding(.horizontal, -8)
            }
        case .chip:
            content.brightness(hovering ? 0.12 : 0)
        }
    }
}

/// «Обновить» in the toolbar of a screen that iOS refreshes by pulling it. Disabled while it runs,
/// so a held ⌘R is one reload and not a queue of them.
private struct RefreshButton: ViewModifier {
    let action: @MainActor @Sendable () async -> Void
    @State private var running = false
    func body(content: Content) -> some View {
        content
            .refreshable(action: action)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        running = true
                        Task { await action(); running = false }
                    } label: { Label("Обновить", systemImage: "arrow.clockwise") }
                    .keyboardShortcut("r", modifiers: .command)
                    .disabled(running)
                    .help("Обновить (⌘R)")
                }
            }
    }
}
#endif

/// What the app puts on the pasteboard. Writing never asks the viewer anything, on either system.
enum Pasteboard {
    static func copy(_ text: String) {
        #if os(iOS)
        UIPasteboard.general.string = text
        #else
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        #endif
    }
}

/// The system the app runs on, where it matters to a screen.
enum OperatingSystem {
    /// Where a viewer who said no to notifications goes to change their mind: the app's page in
    /// Settings on iOS, Notifications in System Settings on the Mac.
    static var notificationSettings: URL? {
        #if os(iOS)
        URL(string: UIApplication.openSettingsURLString)
        #else
        URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension")
        #endif
    }

    /// Hands a URL to the system — a release to install, a page to read. True when something took it.
    @MainActor static func open(_ url: URL) async -> Bool {
        #if os(iOS)
        await UIApplication.shared.open(url)
        #else
        NSWorkspace.shared.open(url)
        #endif
    }

    /// Whether the system stops an app that leaves the screen. A phone does, and the player saves
    /// and settles for it; a Mac never does, and a window behind another is still being watched.
    static var suspendsAppsInBackground: Bool {
        #if os(iOS)
        true
        #else
        false
        #endif
    }
}
