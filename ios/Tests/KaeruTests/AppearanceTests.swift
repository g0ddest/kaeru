import XCTest
@testable import Kaeru

final class AppearanceTests: XCTestCase {

    func testTheDefaultIsWhateverTheSystemSays() {
        XCTAssertEqual(AppAppearance(stored: PlaybackPreferences().appearance), .system)
        XCTAssertEqual(AppAppearance(stored: nil), .system)
    }

    func testAValueNobodyRecognisesFallsBackToTheSystem() {
        // An older build's value, a hand-edited file, a half-written snapshot: none of them is a
        // reason to show a viewer a screen they did not ask for.
        XCTAssertEqual(AppAppearance(stored: "amoled"), .system)
        XCTAssertEqual(AppAppearance(stored: ""), .system)
        XCTAssertEqual(AppAppearance(stored: "Dark"), .system)
    }

    func testAChoiceSurvivesNormalisationAndNonsenseDoesNot() {
        var preferences = PlaybackPreferences()
        preferences.appearance = "light"
        preferences.normalize()
        XCTAssertEqual(preferences.appearance, "light")
        XCTAssertEqual(AppAppearance(stored: preferences.appearance), .light)

        preferences.appearance = "полночь"
        preferences.normalize()
        XCTAssertEqual(preferences.appearance, "system")
    }

    func testEveryChoiceHasSomethingToShowInTheSettingsList() {
        XCTAssertEqual(AppAppearance.allCases, [.system, .light, .dark])
        for value in AppAppearance.allCases { XCTAssertFalse(value.title.isEmpty) }
    }
}
