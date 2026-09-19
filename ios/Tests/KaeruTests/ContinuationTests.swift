import XCTest
@testable import Kaeru

final class ContinuationTests: XCTestCase {
    private let anime = Anime(id: 7, title: "Test", episodes: 12, episodesAired: 8, status: "ongoing")
    func testWaitsForUnairedEpisodeInsteadOfReplayingLast() {
        let result = ContinueTarget.resolve(anime: anime, counted: 8, rewatching: false, progress: [], threshold: 0.9)
        XCTAssertEqual(result.episode, 9)
        XCTAssertFalse(result.canPlay)
    }
    func testAccidentalStartDoesNotDisplaceMeaningfulResume() {
        let values = [EpisodeProgress(animeID: 7, episode: 4, position: 600, duration: 1200), EpisodeProgress(animeID: 7, episode: 5, position: 10, duration: 1200)]
        let result = ContinueTarget.resolve(anime: anime, counted: 3, rewatching: false, progress: values, threshold: 0.9)
        XCTAssertEqual(result.episode, 4)
        XCTAssertEqual(result.position, 600)
    }
    func testFinishedFinaleDoesNotSkipUnwatchedGap() {
        let result = ContinueTarget.resolve(anime: anime, counted: 2, rewatching: false, progress: [EpisodeProgress(animeID: 7, episode: 8, position: 1190, duration: 1200)], threshold: 0.9)
        XCTAssertEqual(result.episode, 3)
    }
    func testCompletedRunOffersRewatchFromBeginning() {
        let finished = Anime(id: 7, title: "Test", episodes: 12, episodesAired: 12, status: "released")
        let result = ContinueTarget.resolve(anime: finished, counted: 12, rewatching: false, progress: [], threshold: 0.9)
        XCTAssertEqual(result.episode, 1)
        XCTAssertTrue(result.rewatch)
    }
    func testOldSnapshotMigratesLatestPositionWithoutLosingPending() throws {
        let old = #"{"library":[],"pending":[],"progress":{"7":{"animeID":7,"episode":3,"position":240,"duration":1200,"updatedAt":1}},"recent":{}}"#
        let decoded = try JSONDecoder().decode(AccountSnapshot.self, from: Data(old.utf8))
        XCTAssertEqual(decoded.episodeHistory["7:3"]?.position, 240)
    }
    func testRememberedTranslationMustActuallyHaveEpisode() {
        let tracks = [Translation(id: 1, title: "AniDUB", episodes: 2), Translation(id: 2, title: "AniLibria", episodes: 12)]
        XCTAssertEqual(TranslationPreference.pick(tracks, episode: 3, remembered: 1, studios: [], usage: [:]), 2)
        XCTAssertEqual(TranslationPreference.pick(tracks, episode: 2, remembered: 1, studios: [], usage: [:]), 1)
    }
}
