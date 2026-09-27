import XCTest
import KaeruShared
@testable import Kaeru
private typealias LibraryItem = Kaeru.LibraryItem
private typealias Stream = Kaeru.Stream

/// The worker, as far as these tests need one: every request written down, and an answer that is
/// either scripted or — for a POST — the batch itself, as a worker that took all of it would say.
@MainActor private final class FakeSyncTransport: SyncTransport {
    var requests: [URLRequest] = []
    var remote: [String: Any] = [:]
    var failing = false
    var script: [(Int, String)] = []
    func send(_ request: URLRequest) async throws -> (Int, Data) {
        requests.append(request)
        await Task.yield()
        if failing { throw URLError(.notConnectedToInternet) }
        if !script.isEmpty { let (status, body) = script.removeFirst(); return (status, Data(body.utf8)) }
        if request.httpMethod == "POST", let body = request.httpBody,
           let sent = (try JSONSerialization.jsonObject(with: body) as? [String: Any])?["titles"] {
            return (200, try JSONSerialization.data(withJSONObject: ["titles": sent]))
        }
        return (200, try JSONSerialization.data(withJSONObject: ["titles": remote]))
    }
    var posts: [[String: Any]] {
        requests.filter { $0.httpMethod == "POST" }.compactMap { request in
            request.httpBody.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }?["titles"] as? [String: Any]
        }
    }
    var gets: Int { requests.filter { $0.httpMethod == "GET" }.count }
}

/// A clock that moves only when told, and the timers that go off when it passes them.
@MainActor private final class ManualClock {
    @MainActor final class Timer: SyncTimer {
        let fireAt: Date
        let action: @MainActor () -> Void
        var cancelled = false
        init(fireAt: Date, action: @escaping @MainActor () -> Void) { self.fireAt = fireAt; self.action = action }
        func cancel() { cancelled = true }
    }
    var now = Date(timeIntervalSince1970: 1_790_000_000)
    var timers: [Timer] = []
    func schedule(_ delay: TimeInterval, _ action: @escaping @MainActor () -> Void) -> any SyncTimer {
        let timer = Timer(fireAt: now.addingTimeInterval(delay), action: action)
        timers.append(timer)
        return timer
    }
    func advance(_ seconds: TimeInterval) {
        now = now.addingTimeInterval(seconds)
        let due = timers.filter { !$0.cancelled && $0.fireAt <= now }
        timers.removeAll { $0.cancelled || $0.fireAt <= now }
        due.forEach { $0.action() }
    }
}

/// The outbox as builds before the shared rules kept it: their own Codable copies of the `/sync`
/// document, verbatim — the same names, types and optionals — so what they stored can be written
/// here exactly as they wrote it.
private enum EarlierBuild {
    struct Position: Codable, Equatable { var p: Int64; var d: Int64; var at: Int64 }
    struct Dub: Codable, Equatable { var id: Int; var title: String; var at: Int64 }
    struct Secret: Codable, Equatable { var on: Bool; var watched: Int; var at: Int64 }
    struct Title: Codable, Equatable {
        var dub: Dub?
        var eps: [String: Position]?
        var secret: Secret?
        var gone: Int64?
        init(dub: Dub? = nil, eps: [String: Position]? = nil, secret: Secret? = nil, gone: Int64? = nil) {
            self.dub = dub; self.eps = eps; self.secret = secret; self.gone = gone
        }
    }
}

@MainActor private final class SyncStubService: AnimeService {
    var rates: [LibraryItem] = []
    var refreshCount = 0
    func discover() async throws -> [Anime] { [] }
    func search(_ query: String) async throws -> [Anime] { [] }
    func details(_ id: Int) async throws -> Anime { Anime(id: id, title: "Remote \(id)") }
    func library(_ userID: Int64, token: String) async throws -> [LibraryItem] { rates }
    func exchange(_ code: String) async throws -> Tokens { tokens }
    func refresh(_ token: String) async throws -> Tokens { refreshCount += 1; return tokens }
    var tokens: Tokens { Tokens(access_token: "fresh", refresh_token: "refresh", expires_in: 3600, created_at: Date().timeIntervalSince1970) }
    func account(_ token: String) async throws -> Account { Account(id: 1, nickname: "Tester", avatar: "") }
    func setRate(_ pending: PendingRate, userID: Int64, rateID: Int64, token: String) async throws -> LibraryItem {
        LibraryItem(id: 55, anime: pending.anime, status: pending.status, episodes: pending.episodes)
    }
    func translations(_ id: Int) async throws -> [Kaeru.Translation] { [] }
    func resolve(_ id: Int, translation: Int, episode: Int) async throws -> Stream { throw AppError.message("No fixture") }
}

@MainActor final class ViewingSyncTests: XCTestCase {
    private let base = Date(timeIntervalSince1970: 1_790_000_000)
    private func ms(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }
    private func anime(_ id: Int = 7) -> Anime { Anime(id: id, title: "Test \(id)", episodes: 12, episodesAired: 12, status: "released") }
    private func session(_ account: Int64) -> Session {
        Session(account: Account(id: account, nickname: "A", avatar: ""),
                tokens: Tokens(access_token: "token-\(account)", refresh_token: "refresh", expires_in: 3600, created_at: Date().timeIntervalSince1970))
    }
    private func model(store: any LocalStorage, account: Int64? = 1, transport: FakeSyncTransport, clock: ManualClock,
                       service: SyncStubService = SyncStubService(), syncOn: Bool = true) -> AppModel {
        // Sync is opt-in; the tests here are about it switched on, unless they say otherwise.
        var preferences = (try? store.read(PlaybackPreferences.self, key: "playbackPreferences")) ?? PlaybackPreferences()
        preferences.syncOn = syncOn
        try? store.write(preferences, key: "playbackPreferences")
        return AppModel(service: service, store: store,
                 configuration: AppConfiguration(clientID: "test", proxyURL: "https://example.com", togetherRelayURL: "wss://relay.test"),
                 session: account.map(session), saveSession: { _ in },
                 sync: SyncEnvironment(transport: transport, now: { clock.now }, schedule: clock.schedule, keepAlive: { {} }))
    }
    private func position(_ anime: Int = 7, episode: Int, at seconds: Double, of length: Double = 1200, updated: Date) -> EpisodeProgress {
        EpisodeProgress(animeID: anime, episode: episode, position: seconds, duration: length, updatedAt: updated)
    }

    // MARK: - the switch

    func testOffByDefaultSendsAndReadsNothing() async throws {
        XCTAssertFalse(PlaybackPreferences().syncOn)
        let store = try LocalStore(inMemory: true), transport = FakeSyncTransport(), clock = ManualClock()
        let app = model(store: store, transport: transport, clock: clock, syncOn: false)
        app.saveProgress(position(episode: 1, at: 300, updated: clock.now), anime: Anime(id: 7, title: "T"), account: app.accountKey)
        app.pushSync(.leaving)
        await app.viewingSync?.settle()
        clock.advance(120)
        await app.viewingSync?.settle()
        XCTAssertTrue(transport.requests.isEmpty)
    }

    func testSwitchedOnReadsThenOffSendsNothingMore() async throws {
        let store = try LocalStore(inMemory: true), transport = FakeSyncTransport(), clock = ManualClock()
        let app = model(store: store, transport: transport, clock: clock, syncOn: false)
        app.setSyncEnabled(true)
        await app.viewingSync?.settle()
        XCTAssertEqual(transport.gets, 1)
        app.setSyncEnabled(false)
        let sent = transport.requests.count
        app.saveProgress(position(episode: 2, at: 300, updated: clock.now), anime: Anime(id: 7, title: "T"), account: app.accountKey)
        app.pushSync(.leaving)
        clock.advance(120)
        await app.viewingSync?.settle()
        XCTAssertEqual(transport.requests.count, sent)
        XCTAssertFalse(app.preferences.syncOn)
    }

    // MARK: - the wire

    /// The document and its wire format are shared/'s (SyncWire, tested there against
    /// sync-vectors.json); this is the client around it: what goes out, and what comes back.
    func testClientSendsTheBearerToTheRelayOverHTTPSAndReadsRefusals() async throws {
        let transport = FakeSyncTransport()
        let client = try XCTUnwrap(SyncClient(relayURL: "wss://relay.test", transport: transport))
        _ = try await client.get(token: "abc")
        let batch: SyncTitles = ["7": .of(eps: ["3": SyncPosition(p: 861_000, d: 1_440_000, at: 1_790_000_000_500)], gone: 1)]
        let answer = try await client.post(batch, token: "abc")
        XCTAssertEqual(transport.requests.map { $0.url?.absoluteString }, ["https://relay.test/sync", "https://relay.test/sync"])
        XCTAssertEqual(transport.requests.map { $0.value(forHTTPHeaderField: "Authorization") }, ["Bearer abc", "Bearer abc"])
        XCTAssertEqual(transport.requests[1].value(forHTTPHeaderField: "Content-Type"), "application/json")
        XCTAssertNil(transport.requests[0].httpBody)
        XCTAssertEqual(transport.requests[1].httpBody.flatMap { String(data: $0, encoding: .utf8) },
                       #"{"titles":{"7":{"eps":{"3":{"p":861000,"d":1440000,"at":1790000000500}},"gone":1}}}"#)
        XCTAssertEqual(answer, batch, "The answer is read back into the shared document")

        // A 200 that is not the document at all: the two sides disagree on the shape.
        transport.script = [(200, "{\"nope\":1}"), (200, "Bad gateway")]
        do { _ = try await client.get(token: "abc"); XCTFail() } catch { XCTAssertEqual(error as? SyncError, .parser) }
        do { _ = try await client.get(token: "abc"); XCTFail() } catch { XCTAssertEqual(error as? SyncError, .parser) }

        transport.script = [(401, "{\"error\":\"sign_in\"}"), (429, "Too many requests"), (502, "{\"error\":\"unavailable\"}"), (400, "{\"error\":\"parameters\"}")]
        do { _ = try await client.get(token: "abc"); XCTFail() } catch { XCTAssertEqual(ServiceFailure.of(error)?.status, 401) }
        do { _ = try await client.get(token: "abc"); XCTFail() } catch { XCTAssertEqual(error as? SyncError, .throttled) }
        do { _ = try await client.get(token: "abc"); XCTFail() } catch { XCTAssertEqual(error as? SyncError, .unavailable) }
        do { _ = try await client.get(token: "abc"); XCTFail() } catch { XCTAssertEqual(error as? SyncError, .parameters) }
        transport.failing = true
        do { _ = try await client.get(token: "abc"); XCTFail() } catch { XCTAssertEqual(error as? SyncError, .offline) }
    }

    func testAnExpiredTokenIsRefreshedOnceAndTheRequestRepeated() async throws {
        let transport = FakeSyncTransport(), clock = ManualClock(), service = SyncStubService()
        transport.script = [(401, "{\"error\":\"sign_in\"}")]
        let model = model(store: try LocalStore(inMemory: true), transport: transport, clock: clock, service: service)
        await model.viewingSync?.pull()
        XCTAssertEqual(service.refreshCount, 1)
        XCTAssertEqual(transport.requests.map { $0.value(forHTTPHeaderField: "Authorization") }, ["Bearer token-1", "Bearer fresh"])
    }

    // MARK: - reading

    func testPullTakesOnlyWhatIsNewerThanThisDevice() async throws {
        let transport = FakeSyncTransport(), clock = ManualClock()
        let model = model(store: try LocalStore(inMemory: true), transport: transport, clock: clock)
        model.saveProgress(position(episode: 3, at: 100, updated: base), anime: anime(), account: model.accountKey)
        model.saveProgress(position(episode: 4, at: 50, updated: base), anime: anime(), account: model.accountKey)
        model.rememberTranslation(610, title: "AniLibria.TV", for: 7)
        transport.remote = [
            "7": ["eps": ["3": ["p": 900_000, "d": 1_200_000, "at": ms(base) - 1000],   // older: this device's stays
                          "4": ["p": 600_000, "d": 1_300_000, "at": ms(base) + 1000],   // newer: taken
                          "5": ["p": 10_000, "d": 0, "at": ms(base) + 1000],            // no length: nothing to resume
                          "6": ["p": 2_000_000, "d": 1_300_000, "at": ms(base) + 1000]], // past its end: at the end
                  "dub": ["id": 1, "title": "Older", "at": ms(clock.now) - 1]],
            "8": ["eps": ["1": ["p": 30_000, "d": 1_400_000, "at": ms(base)]], "dub": ["id": 2, "title": "Studio", "at": ms(base)]],
        ]
        await model.viewingSync?.pull()
        XCTAssertEqual(model.progressFor(animeID: 7, episode: 3)?.position, 100)
        XCTAssertEqual(model.progressFor(animeID: 7, episode: 4)?.position, 600)
        XCTAssertEqual(model.progressFor(animeID: 7, episode: 4)?.duration, 1300)
        XCTAssertNil(model.progressFor(animeID: 7, episode: 5))
        XCTAssertEqual(model.progressFor(animeID: 7, episode: 6)?.position, 1300, "As on Android: a position is clamped into its length")
        XCTAssertEqual(model.progressFor(animeID: 8, episode: 1)?.position, 30)
        XCTAssertEqual(model.titleTranslations[7], 610)
        XCTAssertEqual(model.titleTranslations[8], 2)

        // What was taken is this device's now, and survives a restart; and it is not sent back.
        let restarted = self.model(store: model.store, transport: transport, clock: clock)
        XCTAssertEqual(restarted.progressFor(animeID: 7, episode: 4)?.position, 600)
        XCTAssertEqual(restarted.titleTranslations[8], 2)
        XCTAssertEqual(restarted.dubStamps[8]?.title, "Studio")
    }

    func testRemoteTombstoneRemovesPositionsSavedAtOrBeforeIt() async throws {
        let transport = FakeSyncTransport(), clock = ManualClock()
        let model = model(store: try LocalStore(inMemory: true), transport: transport, clock: clock)
        model.saveProgress(position(episode: 1, at: 100, updated: base), anime: anime(), account: model.accountKey)
        model.saveProgress(position(episode: 2, at: 200, updated: base.addingTimeInterval(20)), anime: anime(), account: model.accountKey)
        transport.remote = ["7": ["gone": ms(base) + 10_000]]
        await model.viewingSync?.pull()
        XCTAssertNil(model.progressFor(animeID: 7, episode: 1))
        XCTAssertEqual(model.progressFor(animeID: 7, episode: 2)?.position, 200)
        XCTAssertEqual(model.progress[7]?.episode, 2)
    }

    // MARK: - writing

    func testPositionsGoInBatchesAtMostOnceAMinute() async throws {
        let transport = FakeSyncTransport(), clock = ManualClock()
        let model = model(store: try LocalStore(inMemory: true), transport: transport, clock: clock)
        await model.viewingSync?.pull()
        model.saveProgress(position(episode: 1, at: 10, updated: clock.now), anime: anime(), account: model.accountKey)
        clock.advance(0)
        await model.viewingSync?.settle()
        XCTAssertEqual(transport.posts.count, 1)
        let first = try XCTUnwrap((transport.posts[0]["7"] as? [String: Any])?["eps"] as? [String: Any])
        XCTAssertEqual((first["1"] as? [String: Any])?["p"] as? Int64, 10_000)

        clock.advance(10)
        model.saveProgress(position(episode: 1, at: 20, updated: clock.now), anime: anime(), account: model.accountKey)
        clock.advance(5)
        model.saveProgress(position(episode: 1, at: 25, updated: clock.now), anime: anime(), account: model.accountKey)
        clock.advance(40)
        await model.viewingSync?.settle()
        XCTAssertEqual(transport.posts.count, 1, "Not before a minute has passed since the last batch")
        clock.advance(5)
        await model.viewingSync?.settle()
        XCTAssertEqual(transport.posts.count, 2)
        let second = try XCTUnwrap((transport.posts[1]["7"] as? [String: Any])?["eps"] as? [String: Any])
        XCTAssertEqual((second["1"] as? [String: Any])?["p"] as? Int64, 25_000, "One batch, with the latest of it")
        clock.advance(120)
        await model.viewingSync?.settle()
        XCTAssertEqual(transport.posts.count, 2, "Nothing left to send")
    }

    func testPauseLeavingAndAnotherEpisodeSendAtOnce() async throws {
        let transport = FakeSyncTransport(), clock = ManualClock()
        let model = model(store: try LocalStore(inMemory: true), transport: transport, clock: clock)
        await model.viewingSync?.pull()
        model.saveProgress(position(episode: 1, at: 10, updated: clock.now), anime: anime(), account: model.accountKey)
        clock.advance(0)
        await model.viewingSync?.settle()
        XCTAssertEqual(transport.posts.count, 1)
        for (step, reason) in [SyncReason.pause, .episodeChange, .leaving].enumerated() {
            clock.advance(2)
            model.saveProgress(position(episode: 1, at: 20 + Double(step), updated: clock.now), anime: anime(), account: model.accountKey)
            model.pushSync(reason)
            await model.viewingSync?.settle()
            XCTAssertEqual(transport.posts.count, 2 + step, "\(reason) goes without waiting for the minute")
        }
    }

    func testAFailedBatchStaysAndIsTriedAgain() async throws {
        let transport = FakeSyncTransport(), clock = ManualClock()
        let model = model(store: try LocalStore(inMemory: true), transport: transport, clock: clock)
        await model.viewingSync?.pull()
        transport.failing = true
        model.saveProgress(position(episode: 1, at: 10, updated: clock.now), anime: anime(), account: model.accountKey)
        model.pushSync(.pause)
        await model.viewingSync?.settle()
        XCTAssertEqual(transport.requests.count, 2)
        transport.failing = false
        clock.advance(60)
        await model.viewingSync?.settle()
        XCTAssertEqual(transport.posts.count, 2)
        XCTAssertNotNil(transport.posts[1]["7"])
        clock.advance(120)
        await model.viewingSync?.settle()
        XCTAssertEqual(transport.posts.count, 2)
    }

    func testTheQueueSurvivesARestart() async throws {
        let transport = FakeSyncTransport(), clock = ManualClock(), store = try LocalStore(inMemory: true)
        let first = model(store: store, transport: transport, clock: clock)
        await first.viewingSync?.pull()
        transport.failing = true
        first.saveProgress(position(episode: 2, at: 42, updated: clock.now), anime: anime(), account: first.accountKey)
        first.rememberTranslation(610, title: "AniLibria.TV", for: 7)
        first.pushSync(.leaving)
        await first.viewingSync?.settle()

        transport.failing = false
        let second = model(store: store, transport: transport, clock: clock)
        second.pushSync(.pause)
        await second.viewingSync?.settle()
        let title = try XCTUnwrap(transport.posts.last?["7"] as? [String: Any])
        XCTAssertEqual(((title["eps"] as? [String: Any])?["2"] as? [String: Any])?["p"] as? Int64, 42_000)
        XCTAssertEqual((title["dub"] as? [String: Any])?["id"] as? Int, 610)
        XCTAssertEqual((title["dub"] as? [String: Any])?["title"] as? String, "AniLibria.TV")
        XCTAssertEqual((title["dub"] as? [String: Any])?["at"] as? Int64, ms(clock.now))
    }

    /// Updating the app must not lose what was waiting to go: the outbox an earlier build stored is
    /// read, sent, and kept in the same form meanwhile.
    func testAnOutboxAnEarlierBuildLeftBehindStillGoesOut() async throws {
        let transport = FakeSyncTransport(), clock = ManualClock(), store = try LocalStore(inMemory: true)
        let left: [String: EarlierBuild.Title] = [
            "7": .init(dub: .init(id: 610, title: "AniLibria.TV", at: 1_790_000_000_000),
                       eps: ["2": .init(p: 42_000, d: 1_200_000, at: 1_790_000_000_001)],
                       secret: .init(on: true, watched: 3, at: 1_790_000_000_002)),
            "9": .init(gone: 1_790_000_000_003),
        ]
        try store.write(left, key: "user-1.sync.outbox")

        let model = model(store: store, transport: transport, clock: clock)
        model.saveProgress(position(8, episode: 1, at: 10, updated: clock.now), anime: anime(8), account: model.accountKey)
        let kept = try XCTUnwrap(store.read([String: EarlierBuild.Title].self, key: "user-1.sync.outbox"))
        XCTAssertEqual(kept["7"], left["7"])
        XCTAssertEqual(kept["9"], left["9"])
        XCTAssertEqual(kept["8"], .init(eps: ["1": .init(p: 10_000, d: 1_200_000, at: ms(clock.now))]), "Written back in the same form")

        model.pushSync(.pause)
        await model.viewingSync?.settle()
        let sent = try XCTUnwrap(transport.posts.last)
        let seven = try XCTUnwrap(sent["7"] as? [String: Any])
        let dub = try XCTUnwrap(seven["dub"] as? [String: Any])
        XCTAssertEqual(dub["id"] as? Int, 610)
        XCTAssertEqual(dub["title"] as? String, "AniLibria.TV")
        XCTAssertEqual(dub["at"] as? Int64, 1_790_000_000_000)
        let episode = try XCTUnwrap((seven["eps"] as? [String: Any])?["2"] as? [String: Any])
        XCTAssertEqual(episode["p"] as? Int64, 42_000)
        XCTAssertEqual(episode["d"] as? Int64, 1_200_000)
        XCTAssertEqual(episode["at"] as? Int64, 1_790_000_000_001)
        let secret = try XCTUnwrap(seven["secret"] as? [String: Any])
        XCTAssertEqual(secret["on"] as? Bool, true)
        XCTAssertEqual(secret["watched"] as? Int, 3)
        XCTAssertEqual(secret["at"] as? Int64, 1_790_000_000_002)
        XCTAssertEqual((sent["9"] as? [String: Any])?["gone"] as? Int64, 1_790_000_000_003)
        XCTAssertNotNil(sent["8"])
        XCTAssertNil(try store.read([String: EarlierBuild.Title].self, key: "user-1.sync.outbox"), "All of it taken")
    }

    /// As on Android: a dub goes out only once something has named it, and no longer than the
    /// worker takes — a longer name had the whole batch refused. A position with no length stays.
    func testADubGoesOutNamedAndCutToWhatTheWorkerTakes() async throws {
        let transport = FakeSyncTransport(), clock = ManualClock()
        let model = model(store: try LocalStore(inMemory: true), transport: transport, clock: clock)
        await model.viewingSync?.pull()
        model.rememberTranslation(4, for: 8)
        let long = String(repeating: "Я", count: 250)
        model.rememberTranslation(610, title: long, for: 7)
        model.saveProgress(position(9, episode: 1, at: 10, of: 0, updated: clock.now), anime: anime(9), account: model.accountKey)
        clock.advance(0)
        await model.viewingSync?.settle()
        let sent = try XCTUnwrap(transport.posts.last)
        XCTAssertEqual(Set(sent.keys), ["7"])
        let dub = try XCTUnwrap((sent["7"] as? [String: Any])?["dub"] as? [String: Any])
        XCTAssertEqual(dub["title"] as? String, String(repeating: "Я", count: 200))
        XCTAssertEqual(model.dubStamps[7]?.title, long, "Kept whole on the device")
    }

    /// As on Android: turned off, nothing done meanwhile is queued — so turned back on, what the
    /// device holds by then goes once in full again.
    func testTurnedBackOnSendsWhatTheDeviceHoldsByThen() async throws {
        let transport = FakeSyncTransport(), clock = ManualClock()
        let model = model(store: try LocalStore(inMemory: true), transport: transport, clock: clock)
        await model.viewingSync?.pull()
        model.setSyncEnabled(false)
        model.saveProgress(position(episode: 4, at: 300, updated: clock.now), anime: anime(), account: model.accountKey)
        clock.advance(120)
        await model.viewingSync?.settle()
        XCTAssertTrue(transport.posts.isEmpty)

        model.setSyncEnabled(true)
        await model.viewingSync?.settle()
        clock.advance(0)
        await model.viewingSync?.settle()
        let seven = try XCTUnwrap(transport.posts.last?["7"] as? [String: Any])
        XCTAssertEqual(((seven["eps"] as? [String: Any])?["4"] as? [String: Any])?["p"] as? Int64, 300_000)
    }

    func testPositionsKeptBeforeSyncGoOnceForEachAccount() async throws {
        let transport = FakeSyncTransport(), clock = ManualClock(), store = try LocalStore(inMemory: true), service = SyncStubService()
        service.rates = [LibraryItem(id: 1, anime: anime(10), status: "completed", episodes: 12)]
        let offline = AppModel(service: service, store: store, configuration: AppConfiguration(clientID: "test", proxyURL: "https://example.com"),
                               session: session(1), saveSession: { _ in })
        await offline.reloadLibrary()
        offline.saveProgress(position(episode: 1, at: 300, updated: base), anime: anime(), account: offline.accountKey)
        offline.saveProgress(position(episode: 2, at: 0, of: 0, updated: base), anime: anime(), account: offline.accountKey)
        offline.saveProgress(position(8, episode: 2, at: 60, updated: base), anime: anime(8), account: offline.accountKey)
        offline.saveProgress(position(10, episode: 3, at: 60, updated: base), anime: anime(10), account: offline.accountKey)
        offline.rememberTranslation(610, title: "AniLibria.TV", for: 7)
        offline.rememberTranslation(4, for: 9)
        offline.queueRate(anime: anime(11), status: WatchStatus.secret.rawValue, episodes: 0)
        transport.remote = ["8": ["eps": ["2": ["p": 90_000, "d": 1_200_000, "at": ms(base) + 1]]]]

        let model = model(store: store, transport: transport, clock: clock, service: service)
        await model.viewingSync?.pull()
        clock.advance(0)
        await model.viewingSync?.settle()
        XCTAssertEqual(transport.posts.count, 1)
        let seven = try XCTUnwrap(transport.posts[0]["7"] as? [String: Any])
        XCTAssertEqual(((seven["eps"] as? [String: Any])?["1"] as? [String: Any])?["p"] as? Int64, 300_000)
        XCTAssertNil((seven["eps"] as? [String: Any])?["2"], "No length, nothing to resume from")
        XCTAssertEqual((seven["dub"] as? [String: Any])?["id"] as? Int, 610)
        XCTAssertEqual((seven["dub"] as? [String: Any])?["title"] as? String, "AniLibria.TV")
        XCTAssertNil(transport.posts[0]["8"], "The server already has something newer")
        XCTAssertNil(transport.posts[0]["9"], "As on Android: a dub nobody has named stays on the device")
        XCTAssertNil(transport.posts[0]["10"], "A completed title is not sent at all")
        let secret = try XCTUnwrap((transport.posts[0]["11"] as? [String: Any])?["secret"] as? [String: Any])
        XCTAssertEqual(secret["on"] as? Bool, true)

        let again = self.model(store: store, transport: transport, clock: clock)
        await again.viewingSync?.pull()
        clock.advance(120)
        await again.viewingSync?.settle()
        XCTAssertEqual(transport.posts.count, 1, "Once per account")
    }

    func testAnotherAccountNeverCarriesThePreviousOnesData() async throws {
        let transport = FakeSyncTransport(), clock = ManualClock(), store = try LocalStore(inMemory: true)
        let first = model(store: store, transport: transport, clock: clock)
        await first.viewingSync?.pull()
        transport.failing = true
        first.saveProgress(position(episode: 1, at: 300, updated: clock.now), anime: anime(), account: first.accountKey)
        first.rememberTranslation(610, title: "A", for: 7)
        first.pushSync(.leaving)
        await first.viewingSync?.settle()
        first.signOut()

        transport.failing = false
        transport.requests = []
        let second = model(store: store, account: 2, transport: transport, clock: clock)
        XCTAssertNil(second.progressFor(animeID: 7, episode: 1))
        XCTAssertNil(second.titleTranslations[7])
        await second.viewingSync?.pull()
        second.saveProgress(position(9, episode: 1, at: 5, updated: clock.now), anime: anime(9), account: second.accountKey)
        second.pushSync(.leaving)
        await second.viewingSync?.settle()
        XCTAssertEqual(transport.posts.count, 1)
        XCTAssertEqual(Set(transport.posts[0].keys), ["9"])
        XCTAssertTrue(transport.requests.allSatisfy { $0.value(forHTTPHeaderField: "Authorization") == "Bearer token-2" })
    }

    func testNothingIsSentOrReadWithoutAnAccount() async throws {
        let transport = FakeSyncTransport(), clock = ManualClock()
        let model = model(store: try LocalStore(inMemory: true), account: nil, transport: transport, clock: clock)
        await model.viewingSync?.pull()
        model.saveProgress(position(episode: 1, at: 10, updated: clock.now), anime: anime(), account: model.accountKey)
        model.rememberTranslation(610, title: "A", for: 7)
        model.pushSync(.leaving)
        model.appWentToBackground()
        clock.advance(600)
        model.appBecameActive()
        await model.viewingSync?.settle()
        XCTAssertTrue(transport.requests.isEmpty)
    }

    func testSignOutStopsAndDropsNothingOfTheAccount() async throws {
        let transport = FakeSyncTransport(), clock = ManualClock(), store = try LocalStore(inMemory: true)
        let model = model(store: store, transport: transport, clock: clock)
        await model.viewingSync?.pull()
        transport.failing = true
        model.saveProgress(position(episode: 1, at: 10, updated: clock.now), anime: anime(), account: model.accountKey)
        model.pushSync(.pause)
        await model.viewingSync?.settle()
        model.signOut()
        transport.failing = false
        let count = transport.requests.count
        clock.advance(600)
        await model.viewingSync?.settle()
        XCTAssertEqual(transport.requests.count, count, "Signed out: the retry does not go")
        let back = self.model(store: store, transport: transport, clock: clock)
        back.pushSync(.pause)
        await back.viewingSync?.settle()
        XCTAssertNotNil(transport.posts.last?["7"], "The same account signing back in sends what was waiting")
    }

    // MARK: - finished titles

    func testATitleTurningCompletedLeavesATombstoneButOnesAlreadyCompletedDoNot() async throws {
        let transport = FakeSyncTransport(), clock = ManualClock(), service = SyncStubService()
        service.rates = [LibraryItem(id: 1, anime: anime(7), status: "completed", episodes: 12),
                         LibraryItem(id: 2, anime: anime(8), status: "watching", episodes: 3)]
        let model = model(store: try LocalStore(inMemory: true), transport: transport, clock: clock, service: service)
        await model.viewingSync?.pull()
        await model.reloadLibrary()
        model.saveProgress(position(8, episode: 4, at: 100, updated: clock.now), anime: anime(8), account: model.accountKey)
        clock.advance(3)
        model.queueRate(anime: anime(8), status: "completed", episodes: 12)
        await model.flush()
        clock.advance(60)
        await model.viewingSync?.settle()
        let posted = transport.posts
        XCTAssertNil(posted.first(where: { $0["7"] != nil }), "Completed before this launch saw the list")
        let eight = try XCTUnwrap(posted.last?["8"] as? [String: Any])
        XCTAssertEqual(eight["gone"] as? Int64, ms(clock.now.addingTimeInterval(-60)))
        XCTAssertNil(eight["eps"], "Older than the tombstone: the worker would refuse it anyway")

        // A finished title's positions are not sent at all.
        clock.advance(60)
        model.saveProgress(position(8, episode: 5, at: 10, updated: clock.now), anime: anime(8), account: model.accountKey)
        model.pushSync(.leaving)
        await model.viewingSync?.settle()
        XCTAssertEqual(transport.posts.count, posted.count)
    }

    /// As on Android: turned on, the list as it stands is where things stand — a title completed
    /// right after leaves its tombstone.
    func testATitleCompletedRightAfterTurningSyncOnLeavesATombstone() async throws {
        let transport = FakeSyncTransport(), clock = ManualClock(), service = SyncStubService()
        service.rates = [LibraryItem(id: 2, anime: anime(8), status: "watching", episodes: 3)]
        let model = model(store: try LocalStore(inMemory: true), transport: transport, clock: clock, service: service, syncOn: false)
        await model.reloadLibrary()
        model.setSyncEnabled(true)
        await model.viewingSync?.settle()
        model.queueRate(anime: anime(8), status: "completed", episodes: 12)
        await model.flush()
        clock.advance(0)
        await model.viewingSync?.settle()
        let eight = try XCTUnwrap(transport.posts.last?["8"] as? [String: Any])
        XCTAssertEqual(eight["gone"] as? Int64, ms(clock.now))
    }

    // MARK: - the foreground

    func testComingBackAfterFiveMinutesReadsAgain() async throws {
        let transport = FakeSyncTransport(), clock = ManualClock()
        let model = model(store: try LocalStore(inMemory: true), transport: transport, clock: clock)
        await model.viewingSync?.pull()
        XCTAssertEqual(transport.gets, 1)
        model.appWentToBackground()
        clock.advance(120)
        model.appBecameActive()
        await model.viewingSync?.settle()
        XCTAssertEqual(transport.gets, 1, "Two minutes is not long enough for anything to have changed")
        model.appWentToBackground()
        clock.advance(300)
        model.appBecameActive()
        await model.viewingSync?.settle()
        XCTAssertEqual(transport.gets, 2)
    }

    func testGoingToTheBackgroundSendsWhatIsWaiting() async throws {
        let transport = FakeSyncTransport(), clock = ManualClock()
        let model = model(store: try LocalStore(inMemory: true), transport: transport, clock: clock)
        await model.viewingSync?.pull()
        model.saveProgress(position(episode: 1, at: 10, updated: clock.now), anime: anime(), account: model.accountKey)
        clock.advance(0)
        await model.viewingSync?.settle()
        clock.advance(5)
        model.saveProgress(position(episode: 1, at: 15, updated: clock.now), anime: anime(), account: model.accountKey)
        model.appWentToBackground()
        await model.viewingSync?.settle()
        XCTAssertEqual(transport.posts.count, 2)
    }
}
