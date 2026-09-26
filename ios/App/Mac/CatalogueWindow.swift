import SwiftUI
import AppKit

// The main window — the catalogue — and the ways back to it. There is only ever one (KaeruApp: no
// New Window), and closing it leaves the app running, the player's window perhaps still playing
// in front of nothing. «Каталог» in the Window menu (⌘0) and a click on the Dock icon bring it
// back; the Window menu does not list a closed window, and the Dock alone does nothing while
// another window is up.

/// The catalogue's window while it is open, and how to open it when it is not.
@MainActor final class CatalogueWindow {
    static let id = "main"
    static let shared = CatalogueWindow()
    private init() {}

    /// Set by the window's own content, cleared as the window closes. In the Dock or hidden with
    /// the app it is still there, and is brought back rather than opened a second time.
    private(set) weak var window: NSWindow?
    /// SwiftUI opens a scene only through an action from some view's environment, and a click on
    /// the Dock reaches the app delegate, which has none: the catalogue's content leaves its own.
    private var openWindow: OpenWindowAction?

    /// Brings the catalogue to the front, opening it if it is closed.
    func show(_ open: OpenWindowAction? = nil) {
        if let window {
            if window.isMiniaturized { window.deminiaturize(nil) }
            window.makeKeyAndOrderFront(nil)
        } else {
            (open ?? openWindow)?(id: Self.id)
        }
    }

    fileprivate func appeared(in window: NSWindow, openWindow: OpenWindowAction) {
        self.window = window
        self.openWindow = openWindow
    }
    fileprivate func closing(_ window: NSWindow) {
        if window === self.window { self.window = nil }
    }
}

extension View {
    /// This is the catalogue's window.
    func catalogueWindow() -> some View { modifier(CatalogueWindowContent()) }
}

private struct CatalogueWindowContent: ViewModifier {
    @Environment(\.openWindow) private var openWindow
    func body(content: Content) -> some View {
        content
            .background(WindowReader { CatalogueWindow.shared.appeared(in: $0, openWindow: openWindow) })
            .onReceive(NotificationCenter.default.publisher(for: NSWindow.willCloseNotification)) { note in
                if let window = note.object as? NSWindow { CatalogueWindow.shared.closing(window) }
            }
    }
}

/// «Каталог» in the Window menu, where Mail keeps its viewer and Messages its conversations: the
/// way back to the main window once it has been closed, and to the front when it is behind.
struct CatalogueCommands: Commands {
    @Environment(\.openWindow) private var openWindow
    var body: some Commands {
        CommandGroup(before: .windowList) {
            Button("Каталог") { CatalogueWindow.shared.show(openWindow) }
                .keyboardShortcut("0")
            Divider()
        }
    }
}
