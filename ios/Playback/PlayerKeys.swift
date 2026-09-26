import Foundation

/// What a key does to the player: the web player's keys (web/src/player/keys.ts), and Esc and ⌃⌘F
/// for the full screen a Mac window can be in.
enum PlayerKeyAction: Equatable { case togglePlaying, back, forward, fullScreen, mute, next, exitFullScreen }

/// The modifiers a key arrived with, as the few this decides by — not AppKit's flags, so the rule
/// is tested the same on every platform.
struct PlayerKeyModifiers: OptionSet, Hashable {
    let rawValue: Int
    static let command = Self(rawValue: 1 << 0)
    static let control = Self(rawValue: 1 << 1)
    static let option = Self(rawValue: 1 << 2)
    static let shift = Self(rawValue: 1 << 3)
}

/// One key going down: which key by where it is on the keyboard, and how.
struct PlayerKeyPress: Equatable {
    /// The hardware key code (Carbon's `kVK_…`). A position, not a letter: in the Russian layout
    /// F, M and N type «а», «ь», «т», and the key is still the player's.
    var keyCode: UInt16
    var modifiers: PlayerKeyModifiers = []
    /// Held down and sent again by the keyboard.
    var isRepeat = false
}

/// Where the keyboard is when the key goes down.
struct PlayerKeyState: Equatable {
    /// A text field has it — the together composer. Everything typed there is text.
    var typing = false
    /// The player's window fills the screen, which is the only time Esc is the player's.
    var fullScreen = false
}

/// What becomes of a key that goes down in the player's window.
enum PlayerKeyRoute: Equatable {
    /// The player's, and this is what it does.
    case perform(PlayerKeyAction)
    /// The player's, held down: it does nothing, and nothing else gets it either. Passed on, a
    /// held Space reached the bare-Space item in «Воспроизведение» — or AVKit, which plays and
    /// pauses behind the model's back — and the episode flipped several times a second.
    case swallow
    /// Not the player's: the menus, the text field, the sheet have it.
    case pass
}

/// Where a key goes.
///
/// Pure, so the rules that are easy to get wrong are the ones that are tested: a key typed into
/// the chat is never a pause, a combination is the menus' (all but ⌃⌘F, the system's own full
/// screen), and a held Space does not flip the picture back and forth.
enum PlayerKeys {
    private static let byCode: [UInt16: PlayerKeyAction] = [
        49: .togglePlaying, // Space
        123: .back,         // ←
        124: .forward,      // →
        3: .fullScreen,     // F («а»)
        46: .mute,          // M («ь»)
        45: .next,          // N («т»)
        53: .exitFullScreen // Esc
    ]
    /// A held arrow keeps seeking; anything else held would undo itself.
    private static let repeats: Set<PlayerKeyAction> = [.back, .forward]

    static func route(_ press: PlayerKeyPress, state: PlayerKeyState) -> PlayerKeyRoute {
        // ⌃⌘F: the system's full screen, and a window's command rather than a key typed — so the
        // composer does not keep it either.
        let chord = press.modifiers.subtracting(.shift)
        if press.keyCode == 3, chord == [.control, .command] { return press.isRepeat ? .swallow : .perform(.fullScreen) }
        guard !state.typing, press.modifiers.isDisjoint(with: [.command, .control, .option]),
              let action = byCode[press.keyCode] else { return .pass }
        // Before the repeat: in a window Esc is nobody's, held or not — a menu or a sheet may want it.
        if action == .exitFullScreen, !state.fullScreen { return .pass }
        if press.isRepeat, !repeats.contains(action) { return .swallow }
        return .perform(action)
    }
}
