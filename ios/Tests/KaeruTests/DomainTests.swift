import XCTest
@testable import Kaeru

final class DomainTests: XCTestCase {
    func testOAuthRejectsUnsolicitedMismatchedAndReplayedCallbacks() throws {
        var attempt = OAuthAttempt()
        let callback = URL(string: "kaeru://oauth?code=abc&state=expected")!
        XCTAssertThrowsError(try attempt.consume(callback))
        attempt.begin(state: "expected")
        XCTAssertThrowsError(try attempt.consume(URL(string: "kaeru://oauth?code=abc&state=wrong")!))
        attempt.begin(state: "expected")
        XCTAssertEqual(try attempt.consume(callback), "abc")
        XCTAssertThrowsError(try attempt.consume(callback))
    }

    func testOAuthRejectsWrongPathAndDuplicateCode() {
        var attempt = OAuthAttempt()
        for raw in ["kaeru://oauth/other?code=a&state=s", "kaeru://oauth?code=a&code=b&state=s"] {
            attempt.begin(state: "s")
            XCTAssertThrowsError(try attempt.consume(URL(string: raw)!))
        }
    }

    func testZeroDurationDoesNotMarkWatched() {
        XCTAssertFalse(EpisodeProgress(animeID: 1, episode: 1, position: 20, duration: 0).watched)
        XCTAssertTrue(EpisodeProgress(animeID: 1, episode: 1, position: 900, duration: 1000).watched)
    }
}
