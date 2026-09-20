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

/// The memo in front of the continuation rule. Everything here is about how often the answer is
/// worked out, never about what it is — that is `ContinuationTests` above.
final class ContinueTargetCacheTests: XCTestCase {
    private let anime = Anime(id: 7, title: "Test", episodes: 12, episodesAired: 8, status: "ongoing")
    private func resolve(_ anime: Anime) -> ContinueTarget {
        ContinueTarget.resolve(anime: anime, counted: 0, rewatching: false, progress: [], threshold: 0.9)
    }
    func testTheSameTitleIsWorkedOutOnce() {
        var cache = ContinueTargetCache()
        for _ in 0..<50 { _ = cache.target(for: anime, threshold: 0.9, resolve: resolve) }
        XCTAssertEqual(cache.computed, 1)
    }
    func testAChangedListWorksItOutAgain() {
        var cache = ContinueTargetCache()
        _ = cache.target(for: anime, threshold: 0.9, resolve: resolve)
        cache.invalidate()
        _ = cache.target(for: anime, threshold: 0.9, resolve: resolve)
        XCTAssertEqual(cache.computed, 2)
    }
    /// The setting the rule reads, changed while the screen is up: every answer on it is stale.
    func testMovingTheThresholdWorksEverythingOutAgain() {
        var cache = ContinueTargetCache()
        _ = cache.target(for: anime, threshold: 0.9, resolve: resolve)
        _ = cache.target(for: anime, threshold: 0.8, resolve: resolve)
        _ = cache.target(for: anime, threshold: 0.8, resolve: resolve)
        XCTAssertEqual(cache.computed, 2)
    }
    /// One id, two counts of what has aired: the catalogue's copy and the list's. A cache keyed on
    /// the id alone would hand one of them the other's episode.
    func testTheSameIdWithADifferentCountIsADifferentQuestion() {
        var cache = ContinueTargetCache()
        let aired = cache.target(for: anime, threshold: 0.9, resolve: resolve)
        var more = anime; more.episodesAired = 12
        let later = cache.target(for: more, threshold: 0.9, resolve: resolve)
        XCTAssertEqual(cache.computed, 2)
        XCTAssertTrue(aired.canPlay && later.canPlay)
    }
}
