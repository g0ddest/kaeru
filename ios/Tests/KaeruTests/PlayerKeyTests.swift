import XCTest
@testable import Kaeru

/// The player's keyboard, the web player's (web/src/player/keys.ts): «пробел — пауза, ←/→ — 10 с,
/// F — полный экран, M — звук, N — следующая серия». The audience types in the Russian layout,
/// where F, M and N type «а», «ь», «т»: only the physical key counts, which is what a key code is.
final class PlayerKeyTests: XCTestCase {
    private enum Code {
        static let space: UInt16 = 49, left: UInt16 = 123, right: UInt16 = 124
        static let f: UInt16 = 3, m: UInt16 = 46, n: UInt16 = 45, escape: UInt16 = 53
        /// Where QWERTY has A and Y: the letters «ф» and «н» in the Russian layout.
        static let a: UInt16 = 0, y: UInt16 = 16, returnKey: UInt16 = 36
    }
    private func route(_ code: UInt16, _ modifiers: PlayerKeyModifiers = [], repeating: Bool = false,
                       typing: Bool = false, fullScreen: Bool = false) -> PlayerKeyRoute {
        PlayerKeys.route(PlayerKeyPress(keyCode: code, modifiers: modifiers, isRepeat: repeating),
                         state: PlayerKeyState(typing: typing, fullScreen: fullScreen))
    }
    /// What the key does to the player, if anything.
    private func action(_ code: UInt16, _ modifiers: PlayerKeyModifiers = [], repeating: Bool = false,
                        typing: Bool = false, fullScreen: Bool = false) -> PlayerKeyAction? {
        guard case .perform(let action) = route(code, modifiers, repeating: repeating, typing: typing, fullScreen: fullScreen)
        else { return nil }
        return action
    }

    func testEachKeyDoesWhatTheWebPlayersDoes() {
        XCTAssertEqual(action(Code.space), .togglePlaying)
        XCTAssertEqual(action(Code.left), .back)
        XCTAssertEqual(action(Code.right), .forward)
        XCTAssertEqual(action(Code.f), .fullScreen)
        XCTAssertEqual(action(Code.m), .mute)
        XCTAssertEqual(action(Code.n), .next)
    }

    /// By the key, not by the letter: Dvorak's F sits where QWERTY has Y, and the letter it types
    /// is nothing to the player. Return is the composer's, not the picture's.
    func testOnlyThePlayersKeysAreTaken() {
        XCTAssertNil(action(Code.a))
        XCTAssertNil(action(Code.y))
        XCTAssertNil(action(Code.returnKey))
    }

    /// ⌘F, ⌃→, ⌥Space belong to the menus and to the system. Shift changes nothing, as on the web.
    func testCombinationsAreLeftToTheSystem() {
        for modifier in [PlayerKeyModifiers.command, .control, .option] {
            XCTAssertNil(action(Code.f, modifier))
            XCTAssertNil(action(Code.right, modifier))
            XCTAssertNil(action(Code.space, modifier))
        }
        XCTAssertEqual(action(Code.f, .shift), .fullScreen)
    }

    /// A held arrow keeps seeking; a held Space or letter must not flip the state back and forth.
    func testOnlyTheArrowsRepeat() {
        XCTAssertEqual(action(Code.left, repeating: true), .back)
        XCTAssertEqual(action(Code.right, repeating: true), .forward)
        for code in [Code.space, Code.f, Code.m, Code.n] {
            XCTAssertNil(action(code, repeating: true))
        }
    }

    /// …and it goes nowhere else either. Let through, a held Space reached the bare-Space item in
    /// «Воспроизведение» and paused and played the episode — and the friend's — several times a
    /// second; a held N skipped episode after episode.
    func testAHeldPlayerKeyIsKeptFromTheMenus() {
        for code in [Code.space, Code.f, Code.m, Code.n] {
            XCTAssertEqual(route(code, repeating: true), .swallow)
            XCTAssertEqual(route(code, .shift, repeating: true), .swallow)
        }
        XCTAssertEqual(route(Code.f, [.control, .command], repeating: true), .swallow)
        XCTAssertEqual(route(Code.escape, repeating: true, fullScreen: true), .swallow)
    }

    /// What is not the player's key is not the player's when held either: a letter held in the
    /// composer, a menu's combination, Esc in a window.
    func testAHeldKeyThatIsNotThePlayersPassesOn() {
        XCTAssertEqual(route(Code.a, repeating: true), .pass)
        XCTAssertEqual(route(Code.space, repeating: true, typing: true), .pass)
        XCTAssertEqual(route(Code.n, .command, repeating: true), .pass)
        XCTAssertEqual(route(Code.escape, repeating: true), .pass)
        XCTAssertEqual(route(Code.escape), .pass)
        XCTAssertEqual(route(Code.space, typing: true), .pass)
    }

    /// The together composer: a space, an «а» or an arrow typed there is text and caret, never a
    /// pause, a full screen or a seek.
    func testNothingIsTakenFromATextField() {
        for code in [Code.space, Code.left, Code.right, Code.f, Code.m, Code.n, Code.escape] {
            XCTAssertNil(action(code, typing: true))
            XCTAssertNil(action(code, typing: true, fullScreen: true))
        }
    }

    /// ⌃⌘F, the system's own full screen, is the player's too — the one combination it takes, and
    /// from the composer as well, since it types nothing there.
    func testControlCommandFIsTheSystemsFullScreen() {
        XCTAssertEqual(action(Code.f, [.control, .command]), .fullScreen)
        XCTAssertEqual(action(Code.f, [.control, .command], typing: true), .fullScreen)
        XCTAssertEqual(action(Code.f, [.control, .command, .shift]), .fullScreen)
        XCTAssertNil(action(Code.f, [.control, .command, .option]))
        XCTAssertNil(action(Code.m, [.control, .command]))
        XCTAssertNil(action(Code.f, [.control, .command], repeating: true))
    }

    /// Esc leaves full screen, and is nobody's in a window: a menu or a sheet may want it.
    func testEscapeLeavesFullScreenOnly() {
        XCTAssertEqual(action(Code.escape, fullScreen: true), .exitFullScreen)
        XCTAssertNil(action(Code.escape))
        XCTAssertNil(action(Code.escape, repeating: true, fullScreen: true))
        XCTAssertEqual(action(Code.f, fullScreen: true), .fullScreen)
    }
}
