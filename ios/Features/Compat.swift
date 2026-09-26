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
    /// thing and, until the player has a window of its own, gets a sheet the size of a small one.
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

private struct PlayerPresentation: ViewModifier {
    @Environment(AppModel.self) private var model
    @Binding var route: PlaybackRoute?
    func body(content: Content) -> some View {
        #if os(iOS)
        content.fullScreenCover(item: $route) { PlayerScreen(anime: $0.anime, episode: $0.episode, model: model) }
        #else
        content.sheet(item: $route) { route in
            PlayerScreen(anime: route.anime, episode: route.episode, model: model)
                .frame(minWidth: 800, idealWidth: 1120, minHeight: 450, idealHeight: 630)
        }
        #endif
    }
}

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
