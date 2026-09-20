import XCTest

final class NavigationTests: XCTestCase {
    @MainActor func testNativeBrowseSearchDetailPlayerAndSettings() throws {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.staticTexts["Популярно сейчас"].waitForExistence(timeout: 30), app.debugDescription)
        capture("home", app)
        let search = app.buttons["Поиск"].firstMatch
        if search.exists { search.tap() } else { app.staticTexts["Поиск"].firstMatch.tap() }
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap(); field.typeText("Sousou no Frieren")
        let result = app.buttons["anime-52991"].firstMatch
        XCTAssertTrue(result.waitForExistence(timeout: 30), app.debugDescription)
        capture("search", app)
        result.tap()
        let play = app.buttons["play-anime"]
        XCTAssertTrue(play.waitForExistence(timeout: 15), app.debugDescription)
        capture("detail", app)
        play.tap()
        // The player's one menu. It was «Серия, озвучка и качество» until the controls were
        // reworked into AVKit's own; the test kept the old name and had been failing ever since.
        let menu = app.buttons["Настройки воспроизведения"]
        XCTAssertTrue(menu.waitForExistence(timeout: 10))
        let ready = NSPredicate(format: "enabled == true")
        expectation(for: ready, evaluatedWith: menu)
        waitForExpectations(timeout: 60)
        XCTAssertFalse(app.staticTexts["Видео недоступно"].exists)
        capture("player", app)
        menu.tap()
        XCTAssertTrue(app.buttons["Качество"].waitForExistence(timeout: 5))
        capture("player-options", app)
        app.buttons["Качество"].tap()
        let quality = app.buttons["360p"]
        if quality.waitForExistence(timeout: 3) { quality.tap() }
        else { app.tap() }
        app.buttons["Готово"].firstMatch.tap()
        XCTAssertTrue(play.waitForExistence(timeout: 10))
        app.navigationBars.buttons.firstMatch.tap()
        // Настройки живут в «Ещё»: на телефоне пять вкладок, и учётная запись — первая строка там.
        // Именно вкладка, а не любая кнопка с этим словом: на главной у каждой карточки есть своё
        // меню «Ещё: <тайтл>», и раньше тап уходил туда. Вкладки дожидаемся: после закрытия
        // плеера панель возвращается не в том же кадре, и тап в пустоту раньше просто терялся.
        // Поиск закрывается до перехода. Пока строка поиска активна, система держит над экраном
        // свою затемняющую подложку, и тап по вкладке до неё просто не доходит — тест всё это
        // время нажимал в пустоту и искал «Ещё» на экране поиска.
        let cancel = app.navigationBars.buttons.matching(
            NSPredicate(format: "label == 'Cancel' OR label == 'Отменить'")).firstMatch
        if cancel.exists { cancel.tap() }
        let more = app.tabBars.buttons["Ещё"]
        XCTAssertTrue(more.waitForExistence(timeout: 15), app.debugDescription)
        more.tap()
        let settings = app.buttons["Аккаунт и настройки"].firstMatch
        XCTAssertTrue(settings.waitForExistence(timeout: 15), app.debugDescription)
        settings.tap()
        XCTAssertTrue(app.navigationBars["Настройки"].waitForExistence(timeout: 5))
        capture("settings", app)
        app.buttons["Готово"].firstMatch.tap()
    }
    @MainActor private func capture(_ name: String, _ app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name; attachment.lifetime = .keepAlways
        add(attachment)
    }
}
