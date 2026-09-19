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

    func testContinuationResumesUnfinishedEpisodeAndClampsToAired() {
        let anime = Anime(id: 7, title: "Фрирен", episodes: 28, episodesAired: 28)
        let progress = EpisodeProgress(animeID: 7, episode: 5, position: 100, duration: 1000)
        XCTAssertEqual(continueEpisode(anime: anime, watched: 4, progress: progress), 5)
        XCTAssertEqual(continueEpisode(anime: anime, watched: 28, progress: nil), 28)
        XCTAssertEqual(continueEpisode(anime: Anime(id: 1, title: "Анонс"), watched: 0, progress: nil), 0)
    }

    func testZeroDurationDoesNotMarkWatched() {
        XCTAssertFalse(EpisodeProgress(animeID: 1, episode: 1, position: 20, duration: 0).watched)
        XCTAssertTrue(EpisodeProgress(animeID: 1, episode: 1, position: 900, duration: 1000).watched)
    }
}
