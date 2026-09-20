import XCTest
@testable import Kaeru

final class DownloadedShelfTests: XCTestCase {
    private func anime(_ id: Int) -> Anime { Anime(id: id, title: "Тайтл \(id)", episodes: 12, episodesAired: 12, status: "released") }
    private func entry(_ id: Int, _ episode: Int, state: DownloadState = .completed, at seconds: Double = 0, translation: Int = 1) -> DownloadEntry {
        var row = DownloadEntry(anime: anime(id), episode: episode, translation: translation, quality: 720)
        row.state = state
        row.updatedAt = Date(timeIntervalSince1970: seconds)
        return row
    }
    private func shelf(_ entries: [DownloadEntry], counted: [Int: Int] = [:], progress: [String: EpisodeProgress] = [:]) -> [DownloadedShelf.Item] {
        DownloadedShelf.build(entries: entries, counted: { counted[$0] ?? 0 },
                              progress: { progress["\($0):\($1)"] }, threshold: 0.9)
    }

    func testOnlyFinishedDownloadsAppearNewestFirst() {
        let rows = shelf([entry(1, 5, at: 100), entry(2, 1, state: .downloading, at: 300), entry(3, 2, at: 200)])
        XCTAssertEqual(rows.map(\.id), ["3:2", "1:5"])
    }

    /// Every status, not only «Смотрю»: a finale on the device is a finale somebody can watch.
    func testATitleTheViewerCalledFinishedStillOffersTheEpisodeOnTheDevice() {
        XCTAssertEqual(shelf([entry(1, 12)], counted: [1: 11]).map(\.id), ["1:12"])
    }

    func testAnEpisodeTheListHasAlreadyCountedIsLeftOut() {
        XCTAssertTrue(shelf([entry(1, 3)], counted: [1: 3]).isEmpty)
        XCTAssertTrue(shelf([entry(1, 3)], counted: [1: 8]).isEmpty)
    }

    /// Finished here but not on the server yet — a mark still in the outbox, or an evening offline.
    func testAnEpisodeFinishedOnThisDeviceIsLeftOut() {
        let watched = EpisodeProgress(animeID: 1, episode: 3, position: 1150, duration: 1200)
        XCTAssertTrue(shelf([entry(1, 3)], progress: ["1:3": watched]).isEmpty)
    }

    /// The one this row exists for: stopped half way through, with no network to resume over.
    func testAnEpisodeLeftHalfWayThroughStays() {
        let half = EpisodeProgress(animeID: 1, episode: 3, position: 600, duration: 1200)
        XCTAssertEqual(shelf([entry(1, 3)], progress: ["1:3": half]).map(\.id), ["1:3"])
    }

    /// Two dubs of one episode are one thing to watch, and two cards with one key would crash a list.
    func testTheSameEpisodeInTwoDubsIsOneCard() {
        let rows = shelf([entry(1, 4, at: 10, translation: 2), entry(1, 4, at: 20, translation: 5)])
        XCTAssertEqual(rows.map(\.id), ["1:4"])
    }

    func testTwoEpisodesOfOneTitleAreTwoCards() {
        XCTAssertEqual(shelf([entry(1, 4, at: 20), entry(1, 5, at: 10)]).map(\.id), ["1:4", "1:5"])
    }
}
