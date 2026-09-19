import XCTest
@testable import Kaeru
private typealias LibraryItem = Kaeru.LibraryItem
private typealias Stream = Kaeru.Stream

@MainActor private final class RecordingStore: LocalStorage {
    var values: [String: Data] = [:]
    var snapshots: [AccountSnapshot] = []
    func read<T: Decodable>(_ type: T.Type, key: String) throws -> T? { try values[key].map { try JSONDecoder().decode(type, from: $0) } }
    func write<T: Encodable>(_ value: T, key: String) throws {
        let data = try JSONEncoder().encode(value)
        values[key] = data
        if key.hasSuffix(".snapshot") { snapshots.append(try JSONDecoder().decode(AccountSnapshot.self, from: data)) }
    }
}

@MainActor private final class StubService: AnimeService {
    var rates: [LibraryItem] = []
    var writeFailure = false
    var writes: [(PendingRate, Int64)] = []
    var refreshCount = 0
    var refreshError: Error?
    var libraryTokens: [String] = []
    var libraryHandler: (() async throws -> [LibraryItem])?
    func discover() async throws -> [Anime] { [] }
    func search(_ query: String) async throws -> [Anime] { [] }
    func details(_ id: Int) async throws -> Anime { Anime(id: id, title: "Test") }
    func library(_ userID: Int64, token: String) async throws -> [LibraryItem] {
        libraryTokens.append(token)
        if let libraryHandler { return try await libraryHandler() }
        return rates
    }
    func exchange(_ code: String) async throws -> Tokens { tokens }
    func refresh(_ token: String) async throws -> Tokens { refreshCount += 1; await Task.yield(); if let refreshError { throw refreshError }; return tokens }
    var tokens: Tokens { Tokens(access_token: "fresh", refresh_token: "refresh", expires_in: 3600, created_at: Date().timeIntervalSince1970) }
    func account(_ token: String) async throws -> Account { Account(id: 1, nickname: "Tester", avatar: "") }
    func setRate(_ pending: PendingRate, userID: Int64, rateID: Int64, token: String) async throws -> LibraryItem {
        writes.append((pending, rateID))
        if writeFailure { throw AppError.message("Offline") }
        return LibraryItem(id: 55, anime: pending.anime, status: pending.status, episodes: pending.episodes)
    }
    func translations(_ id: Int) async throws -> [Translation] { [] }
    func resolve(_ id: Int, translation: Int, episode: Int) async throws -> Stream { throw AppError.message("No fixture") }
}

@MainActor final class AppModelTests: XCTestCase {
    private let configuration = AppConfiguration(clientID: "test", proxyURL: "https://example.com")
    private var anime: Anime { Anime(id: 7, title: "Test", episodes: 12, episodesAired: 12, status: "released") }
    private func session(expired: Bool = false) -> Session {
        Session(account: Account(id: 1, nickname: "A", avatar: ""), tokens: Tokens(access_token: "old", refresh_token: "refresh", expires_in: expired ? 0 : 3600, created_at: Date().timeIntervalSince1970))
    }
    func testProgressPersistsAcrossModelRecreationAndIsAccountScoped() throws {
        let store = try LocalStore(inMemory: true), service = StubService()
        let first = AppModel(service: service, store: store, configuration: configuration, session: session(), saveSession: { _ in })
        first.saveProgress(EpisodeProgress(animeID: 7, episode: 3, position: 150, duration: 1200), anime: anime, account: first.accountKey)
        let second = AppModel(service: service, store: store, configuration: configuration, session: session(), saveSession: { _ in })
        XCTAssertEqual(second.progress[7]?.position, 150)
        second.signOut()
        XCTAssertNil(second.progress[7])
        second.saveProgress(EpisodeProgress(animeID: 7, episode: 3, position: 200, duration: 1200), anime: anime, account: "user-1")
        XCTAssertNil(second.progress[7], "Old player cannot write into guest account")
    }
    func testEveryEpisodePositionSurvivesSwitchingEpisodes() throws {
        let store = RecordingStore()
        let model = AppModel(service: StubService(), store: store, configuration: configuration)
        model.saveProgress(EpisodeProgress(animeID: 7, episode: 1, position: 120, duration: 1200), anime: anime, account: model.accountKey)
        model.saveProgress(EpisodeProgress(animeID: 7, episode: 2, position: 30, duration: 1200), anime: anime, account: model.accountKey)
        let data = try XCTUnwrap(store.values["guest.snapshot"])
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let history = try XCTUnwrap(object["episodeHistory"] as? [String: Any], "Each episode must be retained in the same atomic snapshot")
        XCTAssertEqual((history["7:1"] as? [String: Any])?["position"] as? Double, 120)
        XCTAssertEqual((history["7:2"] as? [String: Any])?["position"] as? Double, 30)
        let restored = AppModel(service: StubService(), store: store, configuration: configuration)
        XCTAssertEqual(restored.progressFor(animeID: 7, episode: 1)?.position, 120)
        XCTAssertEqual(restored.progressFor(animeID: 7, episode: 2)?.position, 30)
    }
    func testManualUnwatchClearsLaterProgressAndUndoRestoresCountAndPositions() async throws {
        let service = StubService(); service.writeFailure = true
        let model = AppModel(service: service, store: try LocalStore(inMemory: true), configuration: configuration, session: session(), saveSession: { _ in })
        model.queueRate(anime: anime, status: "watching", episodes: 7)
        model.saveProgress(EpisodeProgress(animeID: 7, episode: 5, position: 950, duration: 1000), anime: anime, account: model.accountKey)
        model.saveProgress(EpisodeProgress(animeID: 7, episode: 7, position: 800, duration: 1000), anime: anime, account: model.accountKey)
        model.markEpisode(anime: anime, episode: 5, watched: false)
        XCTAssertEqual(model.rate(for: 7)?.episodes, 4)
        XCTAssertNil(model.progressFor(animeID: 7, episode: 7))
        // A PiP/cast player still running the manually unmarked episode must not undo the user's action.
        model.saveProgress(EpisodeProgress(animeID: 7, episode: 5, position: 990, duration: 1000), anime: anime, account: model.accountKey)
        XCTAssertEqual(model.rate(for: 7)?.episodes, 4)
        model.undoEpisodeChange()
        XCTAssertEqual(model.rate(for: 7)?.episodes, 7)
        XCTAssertEqual(model.progressFor(animeID: 7, episode: 7)?.position, 800)
        await model.flush()
    }
    func testFinaleSuggestsCompletionWithoutOverwritingRewatchStatus() async throws {
        let service = StubService(); service.writeFailure = true
        let model = AppModel(service: service, store: try LocalStore(inMemory: true), configuration: configuration, session: session(), saveSession: { _ in })
        model.queueRate(anime: anime, status: "rewatching", episodes: 11)
        model.preferences.watchedThreshold = 0.8
        model.saveProgress(EpisodeProgress(animeID: 7, episode: 12, position: 810, duration: 1000), anime: anime, account: model.accountKey)
        XCTAssertEqual(model.rate(for: 7)?.status, "rewatching")
        XCTAssertEqual(model.rate(for: 7)?.episodes, 12)
        XCTAssertEqual(model.completionSuggestion?.id, 7)
        await model.flush()
    }
    func testStartingTitlePreservesChosenStatusAndRemembersTrackPerAccount() async throws {
        let service = StubService(); service.writeFailure = true
        let model = AppModel(service: service, store: try LocalStore(inMemory: true), configuration: configuration, session: session(), saveSession: { _ in })
        model.queueRate(anime: anime, status: "planned", episodes: 0)
        model.beginPlayback(anime: anime)
        XCTAssertEqual(model.rate(for: 7)?.status, "planned")
        model.rememberTranslation(2, for: 7)
        let tracks = [Translation(id: 1, title: "AniLibria", episodes: 12), Translation(id: 2, title: "Other", episodes: 12)]
        XCTAssertEqual(model.preferredTranslation(for: 7, available: tracks, episode: 1), 2)
        model.signOut()
        XCTAssertEqual(model.preferredTranslation(for: 7, available: tracks, episode: 1), 1)
        XCTAssertFalse(model.canUndoEpisodeChange)
    }
    func testOfflineOutboxSurvivesRestartAndRetriesOnceWithServerRateID() async throws {
        let store = try LocalStore(inMemory: true), service = StubService()
        service.writeFailure = true
        let first = AppModel(service: service, store: store, configuration: configuration, session: session(), saveSession: { _ in })
        first.queueRate(anime: anime, status: "watching", episodes: 3)
        await first.flush()
        XCTAssertEqual(first.pending.count, 1)
        let restored = AppModel(service: service, store: store, configuration: configuration, session: session(), saveSession: { _ in })
        XCTAssertEqual(restored.pending.first?.episodes, 3)
        service.writeFailure = false
        await restored.flush()
        XCTAssertTrue(restored.pending.isEmpty)
        XCTAssertEqual(restored.rate(for: 7)?.id, 55)
        restored.queueRate(anime: anime, status: "watching", episodes: 4)
        await restored.flush()
        XCTAssertEqual(service.writes.last?.1, 55)
    }
    func testConcurrentExpiredRequestsRefreshOnce() async throws {
        let service = StubService()
        let model = AppModel(service: service, store: try LocalStore(inMemory: true), configuration: configuration, session: session(expired: true), saveSession: { _ in })
        async let first: Void = model.reloadLibrary()
        async let second: Void = model.reloadLibrary()
        _ = await (first, second)
        XCTAssertEqual(service.refreshCount, 1)
        XCTAssertEqual(service.libraryTokens, ["fresh", "fresh"])
    }
    func testLateLibraryResponseCannotRestoreSignedOutAccount() async throws {
        let service = StubService()
        var continuation: CheckedContinuation<[LibraryItem], Error>?
        service.libraryHandler = { try await withCheckedThrowingContinuation { continuation = $0 } }
        let model = AppModel(service: service, store: try LocalStore(inMemory: true), configuration: configuration, session: session(), saveSession: { _ in })
        let task = Task { await model.reloadLibrary() }
        while continuation == nil { await Task.yield() }
        model.signOut()
        continuation?.resume(returning: [LibraryItem(id: 4, anime: anime, status: "watching", episodes: 1)])
        await task.value
        XCTAssertTrue(model.library.isEmpty)
        XCTAssertNil(model.session)
    }
    func testWatchedThresholdDoesNotQueueSameEpisodeRepeatedly() async throws {
        let service = StubService(); service.writeFailure = true
        let model = AppModel(service: service, store: try LocalStore(inMemory: true), configuration: configuration, session: session(), saveSession: { _ in })
        for position in [899.0, 900, 950, 999] {
            model.saveProgress(EpisodeProgress(animeID: 7, episode: 3, position: position, duration: 1000), anime: anime, account: model.accountKey)
        }
        XCTAssertEqual(model.pending.count, 1)
        XCTAssertEqual(model.pending.first?.episodes, 3)
        await model.flush()
    }
    func testLibraryReadStartedBeforeMutationCannotOverwriteAcknowledgedProgress() async throws {
        let service = StubService()
        var response: CheckedContinuation<[LibraryItem], Error>?
        service.libraryHandler = { try await withCheckedThrowingContinuation { response = $0 } }
        let model = AppModel(service: service, store: try LocalStore(inMemory: true), configuration: configuration, session: session(), saveSession: { _ in })
        let read = Task { await model.reloadLibrary() }
        while response == nil { await Task.yield() }
        model.queueRate(anime: anime, status: "watching", episodes: 5)
        await model.flush()
        response?.resume(returning: [LibraryItem(id: 55, anime: anime, status: "watching", episodes: 2)])
        await read.value
        XCTAssertEqual(model.rate(for: 7)?.episodes, 5)
    }
    func testRefreshPersistenceFailureBlocksEveryWaitingRequest() async throws {
        let service = StubService()
        let model = AppModel(service: service, store: try LocalStore(inMemory: true), configuration: configuration, session: session(expired: true), saveSession: { _ in throw AppError.message("Keychain unavailable") })
        async let first: Void = model.reloadLibrary()
        async let second: Void = model.reloadLibrary()
        _ = await (first, second)
        XCTAssertTrue(service.libraryTokens.isEmpty)
    }
    func testRejectedRefreshEndsSessionButProxyConfigurationFailurePreservesIt() async throws {
        for (message, shouldEnd) in [("OAuth refresh rejected (invalid_grant)", true), ("Request failed (HTTP 400)", false)] {
            let service = StubService(); service.refreshError = AppError.message(message)
            let model = AppModel(service: service, store: try LocalStore(inMemory: true), configuration: configuration, session: session(expired: true), saveSession: { _ in })
            await model.reloadLibrary()
            XCTAssertEqual(model.session == nil, shouldEnd)
            XCTAssertTrue(service.libraryTokens.isEmpty)
        }
    }
    func testWatchedProgressAndOutboxAreOneCommit() {
        let service = StubService(); service.writeFailure = true
        let store = RecordingStore()
        let model = AppModel(service: service, store: store, configuration: configuration, session: session(), saveSession: { _ in })
        model.saveProgress(EpisodeProgress(animeID: 7, episode: 3, position: 950, duration: 1000), anime: anime, account: model.accountKey)
        XCTAssertEqual(store.snapshots.count, 1)
        XCTAssertTrue(store.snapshots.first?.progress[7]?.watched == true)
        XCTAssertEqual(store.snapshots.first?.pending.first?.episodes, 3)
    }
}
