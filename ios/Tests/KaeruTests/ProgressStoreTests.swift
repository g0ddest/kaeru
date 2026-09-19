import XCTest
@testable import Kaeru
private typealias LibraryItem = Kaeru.LibraryItem
private typealias Stream = Kaeru.Stream

/// A store that remembers every write and every removal by key, so a test can say not only what
/// ended up on disk but how much was written to put it there.
@MainActor private final class CountingStore: LocalStorage {
    var values: [String: Data] = [:]
    private(set) var writes: [String] = []
    private(set) var removals: [String] = []
    func read<T: Decodable>(_ type: T.Type, key: String) throws -> T? {
        try values[key].map { try JSONDecoder().decode(type, from: $0) }
    }
    func write<T: Encodable>(_ value: T, key: String) throws {
        values[key] = try JSONEncoder().encode(value)
        writes.append(key)
    }
    func readAll<T: Decodable>(_ type: T.Type, prefix: String) throws -> [String: T] {
        try values.filter { $0.key.hasPrefix(prefix) }.mapValues { try JSONDecoder().decode(type, from: $0) }
    }
    func remove(_ keys: [String]) throws {
        for key in keys { values[key] = nil }
        removals.append(contentsOf: keys)
    }
    func reset() { writes = []; removals = [] }
}

@MainActor private final class SilentService: AnimeService {
    func discover() async throws -> [Anime] { [] }
    func search(_ query: String) async throws -> [Anime] { [] }
    func details(_ id: Int) async throws -> Anime { Anime(id: id, title: "Test") }
    func library(_ userID: Int64, token: String) async throws -> [LibraryItem] { [] }
    func exchange(_ code: String) async throws -> Tokens { throw AppError.signedOut }
    func refresh(_ token: String) async throws -> Tokens { throw AppError.signedOut }
    func account(_ token: String) async throws -> Account { Account(id: 1, nickname: "A", avatar: "") }
    func setRate(_ pending: PendingRate, userID: Int64, rateID: Int64, token: String) async throws -> LibraryItem {
        throw AppError.message("Offline")
    }
    func translations(_ id: Int) async throws -> [Translation] { [] }
    func resolve(_ id: Int, translation: Int, episode: Int) async throws -> Stream { throw AppError.message("No fixture") }
}

@MainActor final class ProgressStoreTests: XCTestCase {
    private let configuration = AppConfiguration(clientID: "test", proxyURL: "https://example.com")
    private var anime: Anime { Anime(id: 7, title: "Тест", description: String(repeating: "о", count: 400), episodes: 12, episodesAired: 12, status: "released") }
    private func session() -> Session {
        Session(account: Account(id: 1, nickname: "A", avatar: ""),
                tokens: Tokens(access_token: "t", refresh_token: "r", expires_in: 3600, created_at: Date().timeIntervalSince1970))
    }
    private func made(_ store: CountingStore) -> AppModel {
        AppModel(service: SilentService(), store: store, configuration: configuration, session: session(), saveSession: { _ in })
    }

    /// The point of the whole split: five seconds of playback is one small record, not the library.
    func testSavingAPositionWritesOneRecordAndNotTheLibrary() {
        let store = CountingStore()
        let model = made(store)
        model.queueRate(anime: anime, status: "watching", episodes: 0)
        store.reset()
        model.saveProgress(EpisodeProgress(animeID: 7, episode: 3, position: 150, duration: 1200), anime: anime, account: model.accountKey)
        XCTAssertEqual(store.writes, ["user-1.episode.7:3", "user-1.anime.7"])
        store.reset()
        model.saveProgress(EpisodeProgress(animeID: 7, episode: 3, position: 155, duration: 1200), anime: anime, account: model.accountKey)
        XCTAssertEqual(store.writes, ["user-1.episode.7:3"], "The title has not changed, so only the position is written")
        XCTAssertTrue(store.writes.allSatisfy { !$0.hasSuffix(".snapshot") })
    }

    /// The one position write that does touch the outbox, because it also marks the episode.
    func testAWatchedPositionAlsoWritesTheOutbox() {
        let store = CountingStore()
        let model = made(store)
        model.saveProgress(EpisodeProgress(animeID: 7, episode: 3, position: 1150, duration: 1200), anime: anime, account: model.accountKey)
        XCTAssertTrue(store.writes.contains("user-1.episode.7:3"))
        XCTAssertTrue(store.writes.contains("user-1.snapshot"))
        let snapshot = try? store.read(AccountSnapshot.self, key: "user-1.snapshot")
        XCTAssertEqual(snapshot?.pending.first?.episodes, 3)
    }

    /// What is written separately has to come back together.
    func testRowsAreReadBackIntoHistoryAndProgress() {
        let store = CountingStore()
        let first = made(store)
        first.saveProgress(EpisodeProgress(animeID: 7, episode: 3, position: 150, duration: 1200), anime: anime, account: first.accountKey)
        first.saveProgress(EpisodeProgress(animeID: 7, episode: 4, position: 20, duration: 1200), anime: anime, account: first.accountKey)
        let second = made(store)
        XCTAssertEqual(second.progressFor(animeID: 7, episode: 3)?.position, 150)
        XCTAssertEqual(second.progressFor(animeID: 7, episode: 4)?.position, 20)
        XCTAssertEqual(second.progress[7]?.episode, 4, "The latest of them is what the title screen shows")
        XCTAssertEqual(second.recentAnime[7]?.title, "Тест")
    }

    /// A snapshot written by an older build still carries everything; it is taken apart once.
    func testAnOldSnapshotIsMigratedIntoRowsAndLeftSmall() throws {
        let store = CountingStore()
        let legacy = #"""
        {"library":[],"pending":[],"translations":{},
         "progress":{"7":{"animeID":7,"episode":3,"position":240,"duration":1200,"updatedAt":1}},
         "recent":{"7":{"id":7,"title":"Тест","originalTitle":"","poster":"","description":"","episodes":12,"episodesAired":12,"status":"released","score":"","year":"","nextEpisodeAt":""}}}
        """#
        store.values["user-1.snapshot"] = Data(legacy.utf8)
        let model = made(store)
        XCTAssertEqual(model.progressFor(animeID: 7, episode: 3)?.position, 240)
        XCTAssertEqual(model.recentAnime[7]?.title, "Тест")
        XCTAssertTrue(store.writes.contains("user-1.episode.7:3"), "The history became rows")
        let rewritten = try XCTUnwrap(store.read(AccountSnapshot.self, key: "user-1.snapshot"))
        XCTAssertTrue(rewritten.episodeHistory.isEmpty, "And left the snapshot")
        XCTAssertTrue(rewritten.recent.isEmpty)
    }

    /// Unmarking an episode has to take its record away, or the next launch resumes what was undone.
    func testUnmarkingAnEpisodeRemovesItsRecordAndUndoPutsItBack() {
        let store = CountingStore()
        let model = made(store)
        model.saveProgress(EpisodeProgress(animeID: 7, episode: 3, position: 1150, duration: 1200), anime: anime, account: model.accountKey)
        store.reset()
        model.markEpisode(anime: anime, episode: 3, watched: false)
        XCTAssertEqual(store.removals, ["user-1.episode.7:3"])
        XCTAssertNil(store.values["user-1.episode.7:3"])
        model.undoEpisodeChange()
        XCTAssertNotNil(store.values["user-1.episode.7:3"], "Undo restores the record it removed")
        XCTAssertEqual(made(store).progressFor(animeID: 7, episode: 3)?.position, 1150)
    }

    /// Records are named by account, so signing out cannot show somebody else's evening.
    func testRecordsAreScopedToTheAccountThatWroteThem() {
        let store = CountingStore()
        let model = made(store)
        model.saveProgress(EpisodeProgress(animeID: 7, episode: 3, position: 150, duration: 1200), anime: anime, account: model.accountKey)
        model.signOut()
        XCTAssertNil(model.progressFor(animeID: 7, episode: 3))
        XCTAssertTrue(model.recentAnime.isEmpty)
    }
}

extension ProgressStoreTests {
    /// The memo is only as good as what invalidates it: a mark, a position and a status all move
    /// the answer, and a card that kept the old one would offer the episode just finished.
    func testTheRememberedTargetFollowsEveryChange() {
        let store = CountingStore()
        let model = made(store)
        XCTAssertEqual(model.continueTarget(for: anime).episode, 1)
        model.saveProgress(EpisodeProgress(animeID: 7, episode: 1, position: 600, duration: 1200), anime: anime, account: model.accountKey)
        XCTAssertEqual(model.continueTarget(for: anime).episode, 1, "Half way through the first is still the first")
        XCTAssertEqual(model.continueTarget(for: anime).position, 600)
        model.markEpisode(anime: anime, episode: 1, watched: true)
        XCTAssertEqual(model.continueTarget(for: anime).episode, 2)
        model.markEpisode(anime: anime, episode: 1, watched: false)
        XCTAssertEqual(model.continueTarget(for: anime).episode, 1)
        model.undoEpisodeChange()
        XCTAssertEqual(model.continueTarget(for: anime).episode, 2)
    }
}
