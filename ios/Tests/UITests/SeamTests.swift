import XCTest

/// The seam between this app and the system, which is where every defect that reached the viewer
/// has been: a toolbar that would not host a `UIButton`, a URL scheme nobody read, a screen that
/// existed on one platform and not the other.
///
/// None of it is reachable from a unit test — the rules underneath are all covered there — so what
/// is exercised here is only what needs a running app and a real system: launching, the picker, the
/// keychain and the store surviving a relaunch, and a link arriving from outside the app.
final class SeamTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    /// A valid invitation that will not resolve to a room: an eight-byte id and a sixteen-byte key,
    /// which is all `TogetherInvitation.parse` asks of one. What is under test is the route, not
    /// the relay — this link is meant to land on the join screen, not to connect anywhere.
    private let invitation = "kaeru://watch?r=AAECAwQFBgc&k=AAECAwQFBgcICQoLDA0ODw"

    // MARK: - launching

    func testLaunchingLandsOnTheHomeShelves() {
        let app = launch()
        // A shelf, not a spinner: the home screen is built from what is already on the device, so
        // it has content before the catalogue answers.
        XCTAssertTrue(app.tabBars.buttons["Главная"].waitForExistence(timeout: 30), app.debugDescription)
        let shelves = ["Продолжить просмотр", "Новые серии", "Скачано", "Дальше по списку", "Популярно сейчас"]
        let anyShelf = shelves.contains { app.staticTexts[$0].waitForExistence(timeout: 30) }
        XCTAssertTrue(anyShelf, app.debugDescription)
        capture("home", app)
    }

    // MARK: - the store, across a relaunch

    /// The switches are the one part of Settings with nothing behind them but the store: nobody
    /// notices a preference that fails to save until the next launch, and by then the evidence is
    /// gone.
    func testASettingSurvivesTheAppBeingKilled() {
        var app = launch()
        openSettings(app)
        let toggle = app.switches["Пропускать эндинг автоматически"].firstMatch
        XCTAssertTrue(toggle.waitForExistence(timeout: 10), app.debugDescription)
        let before = toggle.value as? String
        // The trailing edge, not the centre: a SwiftUI `Toggle` in a `Form` is one row-wide
        // element whose middle is the label, and a tap there moves nothing.
        flip(toggle)
        let after = toggle.value as? String
        XCTAssertNotEqual(before, after, "the switch did not move")
        capture("settings", app)
        app.buttons["Готово"].firstMatch.tap()

        app.terminate()
        app = launch()
        openSettings(app)
        let again = app.switches["Пропускать эндинг автоматически"].firstMatch
        XCTAssertTrue(again.waitForExistence(timeout: 10))
        XCTAssertEqual(again.value as? String, after, "the setting did not survive the relaunch")
        // Left as it was found, so a second run of this suite tests the same thing.
        flip(again)
        app.buttons["Готово"].firstMatch.tap()
    }

    // MARK: - «Обновления»

    func testTheUpdatesScreenSaysWhatIsInstalled() {
        let app = launch()
        XCTAssertTrue(app.tabBars.buttons["Главная"].waitForExistence(timeout: 30))
        // The quiet row when the launch check has found something, and the settings row otherwise:
        // both lead to the same page, and which one is there depends on what GitHub has published.
        let strip = app.buttons["update-strip"].firstMatch
        if strip.waitForExistence(timeout: 20) {
            capture("home-update-row", app)
            strip.tap()
        } else {
            openSettings(app)
            let row = app.buttons["Обновления"].firstMatch
            XCTAssertTrue(row.waitForExistence(timeout: 10), app.debugDescription)
            row.tap()
        }
        XCTAssertTrue(app.staticTexts["УСТАНОВЛЕНО"].waitForExistence(timeout: 10)
                      || app.staticTexts["Установлено"].waitForExistence(timeout: 2), app.debugDescription)
        XCTAssertTrue(app.staticTexts["ПОСЛЕДНИЙ ВЫПУСК"].exists || app.staticTexts["Последний выпуск"].exists)
        // Whatever it found, it has stopped saying «Проверяем…» by now.
        let settled = NSPredicate(format: "exists == true")
        let check = app.buttons["update-check"].firstMatch
        expectation(for: settled, evaluatedWith: check)
        waitForExpectations(timeout: 30)
        capture("updates", app)
        // The offer is at the end of the page, under whatever the release notes ran to.
        for _ in 0..<6 where !app.buttons["update-install"].firstMatch.isHittable {
            app.scrollViews.firstMatch.swipeUp()
        }
        capture("updates-offer", app)
    }

    // MARK: - the cast control

    /// The control that was there, took the press and drew nothing. Hittable is the assertion that
    /// matters: a toolbar item handed zero width still «exists».
    func testThePlayerHasACastControlThatDrawsAndSurvivesRotation() {
        let app = launch()
        openFirstEpisode(app)
        let cast = app.buttons["cast-button"].firstMatch
        XCTAssertTrue(cast.waitForExistence(timeout: 15), app.debugDescription)
        XCTAssertTrue(cast.isHittable, "the cast control has no size a finger could reach")
        XCTAssertGreaterThan(cast.frame.width, 20)
        XCTAssertGreaterThan(cast.frame.height, 20)
        capture("player-cast", app)

        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        // Waiting for the window to be wider than it is tall does two jobs: it says the player
        // really followed the device over rather than only that the device turned, and it lets the
        // animation finish, without which the screenshot below is a picture of a half-turn.
        let landscape = XCTNSPredicateExpectation(predicate: NSPredicate { element, _ in
            guard let app = element as? XCUIApplication else { return false }
            return app.frame.width > app.frame.height
        }, object: app)
        XCTAssertEqual(XCTWaiter().wait(for: [landscape], timeout: 15), .completed,
                       "the player did not follow the device into landscape")
        XCTAssertTrue(cast.waitForExistence(timeout: 10), "the cast control did not come back after rotation")
        XCTAssertTrue(cast.isHittable)
        capture("player-cast-landscape", app)
    }

    // MARK: - a link from outside the app

    /// The link is handed to iOS, not to the app: nothing inside the process can prove the `kaeru`
    /// scheme is registered and routed, and the three defects that reached the viewer were all of
    /// exactly this kind.
    func testAWatchLinkLandsOnTheJoinScreen() throws {
        let app = launch()
        XCTAssertTrue(app.tabBars.buttons["Главная"].waitForExistence(timeout: 30))
        // Which of the two rules applies depends on whether this device has an account, so the
        // test asks before it knocks rather than accepting either answer afterwards.
        let signedOut = signedOut(app)
        try open(invitation)
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20), "the link did not reach the app")
        let room = app.staticTexts["Комната"].firstMatch
        if signedOut {
            // Held until somebody signs in, as Android's `PendingWatchLink` holds one: the room
            // must not be joined against nobody.
            XCTAssertFalse(room.waitForExistence(timeout: 10), app.debugDescription)
            capture("deep-link-held", app)
        } else {
            // The room opens on the tap, not when the relay answers — the handshake can take half
            // a minute of backoff, and the screen has a state for every part of it.
            XCTAssertTrue(room.waitForExistence(timeout: 10), app.debugDescription)
            capture("deep-link-join", app)
        }
    }

    // MARK: - walking the app

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launch()
        return app
    }

    /// Whether this device has an account, read off the row that says so in «Ещё».
    private func signedOut(_ app: XCUIApplication) -> Bool {
        app.tabBars.buttons["Ещё"].tap()
        XCTAssertTrue(app.buttons["Аккаунт и настройки"].firstMatch.waitForExistence(timeout: 10), app.debugDescription)
        let answer = app.staticTexts["Войдите в Shikimori"].exists
        app.tabBars.buttons["Главная"].tap()
        return answer
    }

    private func openSettings(_ app: XCUIApplication) {
        // Настройки живут в «Ещё» на телефоне и в строке зрителя под боковой панелью на iPad.
        let more = app.tabBars.buttons["Ещё"].firstMatch
        if more.waitForExistence(timeout: 20) { more.tap() }
        let account = app.buttons["Аккаунт и настройки"].firstMatch
        XCTAssertTrue(account.waitForExistence(timeout: 10), app.debugDescription)
        account.tap()
        XCTAssertTrue(app.navigationBars["Настройки"].waitForExistence(timeout: 10), app.debugDescription)
    }

    private func openFirstEpisode(_ app: XCUIApplication) {
        XCTAssertTrue(app.tabBars.buttons["Главная"].waitForExistence(timeout: 30))
        // Through search rather than through the hero: the carousel turns itself over every seven
        // seconds, so a button found there may not be under the finger by the time it lands.
        app.tabBars.buttons["Поиск"].tap()
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10), app.debugDescription)
        field.tap(); field.typeText("Sousou no Frieren")
        let result = app.buttons["anime-52991"].firstMatch
        XCTAssertTrue(result.waitForExistence(timeout: 30), app.debugDescription)
        result.tap()
        let open = app.buttons["play-anime"]
        XCTAssertTrue(open.waitForExistence(timeout: 20), app.debugDescription)
        open.tap()
        XCTAssertTrue(app.buttons["Готово"].firstMatch.waitForExistence(timeout: 30), app.debugDescription)
    }

    /// Hands a URL to iOS the way anything outside the app does — a messenger, a mail, a
    /// notification. The same door `xcrun simctl openurl` knocks on, and the only one that proves
    /// the `kaeru` scheme is registered and routed rather than merely parsed.
    private func open(_ url: String) throws {
        XCUIDevice.shared.system.open(try XCTUnwrap(URL(string: url)))
    }

    /// Moves a switch that lives inside a `Form` row.
    private func flip(_ toggle: XCUIElement) {
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
    }

    private func capture(_ name: String, _ app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
