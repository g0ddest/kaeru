import XCTest
@testable import Kaeru

/// The one question about a version that matters: is that one newer than this one.
final class UpdateVersionTests: XCTestCase {
    func testAVersionIsReadAsNumbersRatherThanAsText() {
        // The bug this whole type exists to prevent: as strings, `0.10.0` sorts before `0.9.0`,
        // and the app stops offering updates the first time a component reaches ten.
        XCTAssertTrue(isNewerVersion("0.10.0", than: "0.9.0"))
        XCTAssertFalse(isNewerVersion("0.9.0", than: "0.10.0"))
    }

    func testTheLeadingVeeIsNotPartOfTheNumber() {
        XCTAssertEqual(Version.parse("v0.4.0"), Version.parse("0.4.0"))
        XCTAssertEqual(Version.parse("V1.2"), Version.parse("1.2"))
    }

    func testAMissingComponentIsZero() {
        XCTAssertEqual(Version.parse("1.0"), Version.parse("1.0.0"))
        XCTAssertFalse(isNewerVersion("1.0", than: "1.0.0"))
    }

    /// A pre-release suffix is dropped, so `0.4.0-rc1` and `0.4.0` are one version — which is the
    /// honest answer for an app that cannot tell the two builds apart anyway.
    func testASuffixIsNotPartOfTheOrdering() {
        XCTAssertEqual(Version.parse("0.4.0-rc.1"), Version.parse("0.4.0"))
        XCTAssertEqual(Version.parse("0.4.0+ci7"), Version.parse("0.4.0"))
    }

    func testAComponentWithNoDigitsEndsTheVersion() {
        XCTAssertEqual(Version.parse("1.x.3"), Version.parse("1"))
    }

    /// Null rather than zero: «this is not a version» and «this is version zero» lead to opposite
    /// decisions, and a tag nobody can read must never look older than what is installed.
    func testATagWithNoNumberInItIsNotAVersion() {
        XCTAssertNil(Version.parse("latest"))
        XCTAssertNil(Version.parse(""))
        XCTAssertFalse(isNewerVersion("latest", than: "0.1.0"))
        XCTAssertFalse(isNewerVersion("9.9.9", than: "unreadable"))
    }

    func testOrdering() {
        XCTAssertTrue(isNewerVersion("0.6.0", than: "0.5.1"))
        XCTAssertTrue(isNewerVersion("1.0.0", than: "0.99.99"))
        XCTAssertFalse(isNewerVersion("0.5.1", than: "0.5.1"))
        XCTAssertFalse(isNewerVersion("0.5.0", than: "0.5.1"))
    }
}

/// When the quiet check is allowed to run: once a day, so the hourly budget GitHub gives one
/// address — shared by everybody behind one router — is not spent on launches.
final class UpdatePolicyTests: XCTestCase {
    private let epoch = Date(timeIntervalSince1970: 1_758_326_400)
    private let policy = UpdatePolicy()

    func testADeviceThatHasNeverCheckedIsDue() {
        XCTAssertTrue(policy.due(lastCheckedAt: nil, now: epoch))
    }

    func testTheDayIsCountedFromTheLastCheck() {
        XCTAssertFalse(policy.due(lastCheckedAt: epoch, now: epoch))
        XCTAssertFalse(policy.due(lastCheckedAt: epoch, now: epoch.addingTimeInterval(23 * 3600)))
        XCTAssertTrue(policy.due(lastCheckedAt: epoch, now: epoch.addingTimeInterval(24 * 3600)))
    }

    /// The only way to get a check dated in the future is a clock that was wrong and has since
    /// been corrected. A phone that fell into that hole would otherwise stop checking for as long
    /// as the wrong reading stayed ahead of now.
    func testACheckDatedInTheFutureIsDueRatherThanNever() {
        XCTAssertTrue(policy.due(lastCheckedAt: epoch.addingTimeInterval(3600), now: epoch))
    }
}

/// The date inside a Russian sentence. It was following the device's locale, which on a phone set
/// to English printed «19 September 2026» in the middle of «Проверено …».
final class UpdateDateTests: XCTestCase {
    private var calendar: Calendar {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(identifier: "Europe/Chisinau") ?? .gmt
        return value
    }
    private func date(_ year: Int, _ month: Int, _ day: Int) -> Date {
        calendar.date(from: DateComponents(timeZone: calendar.timeZone, year: year, month: month, day: day, hour: 12))!
    }

    func testTheMonthIsSpelledOutInRussian() {
        XCTAssertEqual(UpdateCopy.date(date(2026, 9, 19), calendar: calendar), "19 сентября 2026")
        XCTAssertEqual(UpdateCopy.date(date(2026, 1, 1), calendar: calendar), "1 января 2026")
        XCTAssertEqual(UpdateCopy.date(date(2025, 12, 31), calendar: calendar), "31 декабря 2025")
    }
}
