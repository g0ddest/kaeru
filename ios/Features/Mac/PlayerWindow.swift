import SwiftUI
import AppKit

// The player on the Mac is a window of its own, as it is in every video app there: the picture
// goes full screen by itself and leaves the catalogue on the desktop, and the next title can be
// found while this one plays. The iPad's full-screen cover has no counterpart to become.

/// What the player's window shows: the episode `ApplicationRuntime.nowPlaying` names.
///
/// Closing the window is the iPad's «Готово»: the screen inside goes, and with it the playback,
/// stopped and its place saved (PlayerScreen's onDisappear). The route stays behind on purpose.
/// The window only ever opens with a fresh one set just before, and clearing the old one as the
/// window closed raced the next opening: SwiftUI brought the window back with its last contents
/// first, and those contents took the new route away again.
struct PlayerWindow: View {
    static let id = "player"
    var body: some View {
        let runtime = ApplicationRuntime.shared
        Group {
            if let model = runtime.model, let route = runtime.nowPlaying {
                // Another episode asked for while this one plays is another screen: the old one
                // disappears — closed, its place saved — and the new one starts from nothing.
                PlayerScreen(anime: route.anime, episode: route.episode, model: model)
                    .id(route.id)
                    .environment(model)
            } else { Color.black }
        }
        .frame(minWidth: 640, minHeight: 360)
    }
}

/// The player window's keyboard and its menu, in one place: the window, the model, and whether the
/// viewer is typing — read alike by the key monitor and by «Воспроизведение».
@MainActor @Observable final class PlayerControls {
    let playback: PlaybackModel
    /// The together composer is open, and every key is its.
    var typing = false
    /// A sheet is over the player — the conversation's history, a dialog — with keys of its own.
    private(set) var covered = false
    /// For the menu's wording. The keys read the window itself: this follows the end of the
    /// animation, and Esc pressed during it has to count too.
    private(set) var fullScreen = false
    @ObservationIgnored weak var window: NSWindow?
    /// Says on the picture how far ← or → went.
    @ObservationIgnored var hint: (PlayerTapZone) -> Void = { _ in }
    @ObservationIgnored private var monitor: Any?

    init(playback: PlaybackModel) { self.playback = playback }

    func perform(_ action: PlayerKeyAction) {
        switch action {
        case .togglePlaying: playback.setPlaying(!playback.wantsPlayback)
        case .back: playback.seek(by: -Double(playback.skipSeconds)); hint(.back)
        case .forward: playback.seek(by: Double(playback.skipSeconds)); hint(.forward)
        case .fullScreen: window?.toggleFullScreen(nil)
        case .exitFullScreen: if let window, window.styleMask.contains(.fullScreen) { window.toggleFullScreen(nil) }
        case .mute: playback.setMuted(!playback.muted)
        case .next: playback.nextNow()
        }
    }

    func fullScreenChanged(_ window: NSWindow, _ value: Bool) {
        guard window === self.window else { return }
        fullScreen = value
    }
    func sheetChanged(_ window: NSWindow, _ value: Bool) {
        guard window === self.window else { return }
        covered = value
    }

    /// AppKit offers a key to the menus before the window's first responder hears it, so Space in
    /// «Воспроизведение» would be a pause even typed into the composer. This hears every key
    /// first: the player's — by where it is on the keyboard, so the Russian layout's «а» is still
    /// F — when the player's window has the keyboard and no text field in it does. A player's key
    /// held down is kept here too, doing nothing (`PlayerKeyRoute.swallow`).
    func startListening() {
        guard monitor == nil else { return }
        monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            let taken = MainActor.assumeIsolated { self?.take(event) ?? false }
            return taken ? nil : event
        }
    }
    func stopListening() {
        if let monitor { NSEvent.removeMonitor(monitor) }
        monitor = nil
    }

    private func take(_ event: NSEvent) -> Bool {
        // A sheet over the player — the history, a dialog — is a window of its own, with its own keys.
        guard let window, event.window === window, window.attachedSheet == nil else { return false }
        // The flag covers the composer; the field editor covers any other field that ever appears.
        let state = PlayerKeyState(typing: typing || window.firstResponder is NSText,
                                   fullScreen: window.styleMask.contains(.fullScreen))
        switch PlayerKeys.route(PlayerKeyPress(event), state: state) {
        case .perform(let action): perform(action); return true
        case .swallow: return true
        case .pass: return false
        }
    }
}

private extension PlayerKeyPress {
    init(_ event: NSEvent) {
        let flags = event.modifierFlags
        var modifiers: PlayerKeyModifiers = []
        if flags.contains(.command) { modifiers.insert(.command) }
        if flags.contains(.control) { modifiers.insert(.control) }
        if flags.contains(.option) { modifiers.insert(.option) }
        if flags.contains(.shift) { modifiers.insert(.shift) }
        self.init(keyCode: event.keyCode, modifiers: modifiers, isRepeat: event.isARepeat)
    }
}

extension View {
    /// The player's window around a player: its keys, its menu, its dark title bar.
    func playerWindowControls(playback: PlaybackModel, typing: Bool,
                              hint: @escaping (PlayerTapZone) -> Void) -> some View {
        modifier(PlayerWindowControls(playback: playback, typing: typing, hint: hint))
    }
}

private struct PlayerWindowControls: ViewModifier {
    @State private var controls: PlayerControls
    let typing: Bool
    let hint: (PlayerTapZone) -> Void
    init(playback: PlaybackModel, typing: Bool, hint: @escaping (PlayerTapZone) -> Void) {
        _controls = State(initialValue: PlayerControls(playback: playback))
        self.typing = typing
        self.hint = hint
    }
    func body(content: Content) -> some View {
        content
            .background(WindowReader { window in
                controls.window = window
                controls.fullScreenChanged(window, window.styleMask.contains(.fullScreen))
            })
            .focusedSceneValue(controls)
            .onChange(of: typing, initial: true) { _, value in controls.typing = value }
            .onAppear { controls.hint = hint; controls.startListening() }
            .onDisappear { controls.stopListening() }
            .onReceive(NotificationCenter.default.publisher(for: NSWindow.didEnterFullScreenNotification)) { note in
                if let window = note.object as? NSWindow { controls.fullScreenChanged(window, true) }
            }
            .onReceive(NotificationCenter.default.publisher(for: NSWindow.didExitFullScreenNotification)) { note in
                if let window = note.object as? NSWindow { controls.fullScreenChanged(window, false) }
            }
            .onReceive(NotificationCenter.default.publisher(for: NSWindow.willBeginSheetNotification)) { note in
                if let window = note.object as? NSWindow { controls.sheetChanged(window, true) }
            }
            .onReceive(NotificationCenter.default.publisher(for: NSWindow.didEndSheetNotification)) { note in
                if let window = note.object as? NSWindow { controls.sheetChanged(window, false) }
            }
            // In full screen there is no title bar to hide the toolbar in; it slides down when the
            // pointer goes to the top, as the system's own players do.
            .windowToolbarFullScreenVisibility(.onHover)
    }
}

/// Hands over the window a view is in, once it is in one.
struct WindowReader: NSViewRepresentable {
    let found: (NSWindow) -> Void
    func makeNSView(context: Context) -> NSView { Probe(found: found) }
    func updateNSView(_ view: NSView, context: Context) {}
    private final class Probe: NSView {
        let found: (NSWindow) -> Void
        init(found: @escaping (NSWindow) -> Void) {
            self.found = found
            super.init(frame: .zero)
        }
        required init?(coder: NSCoder) { nil }
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            if let window { found(window) }
        }
    }
}

/// «Воспроизведение» in the menu bar: the player's keys where they can be read, and the player's
/// «…» menu where a Mac user looks for it. Live while the player's window has the keyboard — and
/// never while the composer or a sheet over the picture does: a disabled item leaves its key to
/// the field, and a bare Space in the history is not a pause.
struct PlayerCommands: Commands {
    @FocusedValue(PlayerControls.self) private var controls
    var body: some Commands {
        CommandMenu("Воспроизведение") { PlayerMenu(controls: controls) }
    }
}

private struct PlayerMenu: View {
    let controls: PlayerControls?
    private var off: Bool { controls.map { $0.typing || $0.covered } ?? true }
    var body: some View {
        let playback = controls?.playback
        let skip = playback?.skipSeconds ?? 10
        Group {
            Button(playback?.wantsPlayback == true ? "Пауза" : "Воспроизвести") { controls?.perform(.togglePlaying) }
                .keyboardShortcut(.space, modifiers: [])
            Button("Назад на \(skip) с") { controls?.perform(.back) }
                .keyboardShortcut(.leftArrow, modifiers: [])
            Button("Вперёд на \(skip) с") { controls?.perform(.forward) }
                .keyboardShortcut(.rightArrow, modifiers: [])
            Divider()
            // F goes both ways; in full screen the item says Esc, the key every Mac app leaves it by.
            let fullScreen = controls?.fullScreen == true
            Button(fullScreen ? "Выйти из полноэкранного режима" : "Во весь экран") {
                controls?.perform(.fullScreen)
            }
            .keyboardShortcut(fullScreen ? .escape : "f", modifiers: [])
            Toggle("Без звука", isOn: Binding(get: { playback?.muted ?? false }, set: { playback?.setMuted($0) }))
                .keyboardShortcut("m", modifiers: [])
            Button("Следующая серия") { controls?.perform(.next) }
                .keyboardShortcut("n", modifiers: [])
                .disabled(playback?.hasNext != true)
            if let offer = playback?.skipOffer {
                Button(offer.kind == .opening ? "Пропустить опенинг" : "Пропустить эндинг") { playback?.skipCurrent() }
            }
        }
        .disabled(off)
        Divider()
        if let playback {
            PlayerOptionsMenu(playback: playback, offersNext: false).disabled(off || playback.loading)
        }
    }
}
