import XCTest
import KaeruShared
@testable import Kaeru
private typealias LibraryItem = Kaeru.LibraryItem
private typealias Stream = Kaeru.Stream

/// Shikimori as far as «украдкой» cares: every write written down.
@MainActor private final class RecordingService: AnimeService {
    var rates: [LibraryItem] = []
    var writes: [PendingRate] = []
    var detailsAsked: [Int] = []
    func discover() async throws -> [Anime] { [] }
    func search(_ query: String) async throws -> [Anime] { [] }
    func details(_ id: Int) async throws -> Anime {
        detailsAsked.append(id)
        return Anime(id: id, title: "Remote \(id)", episodes: 12, episodesAired: 12, status: "released")
    }
    func library(_ userID: Int64, token: String) async throws -> [LibraryItem] { rates }
    func exchange(_ code: String) async throws -> Tokens { tokens }
    func refresh(_ token: String) async throws -> Tokens { tokens }
    var tokens: Tokens { Tokens(access_token: "fresh", refresh_token: "refresh", expires_in: 3600, created_at: Date().timeIntervalSince1970) }
    func account(_ token: String) async throws -> Account { Account(id: 1, nickname: "Tester", avatar: "") }
    func setRate(_ pending: PendingRate, userID: Int64, rateID: Int64, token: String) async throws -> LibraryItem {
        writes.append(pending)
        return LibraryItem(id: rateID == 0 ? 55 : rateID, anime: pending.anime, status: pending.status, episodes: pending.episodes)
    }
    func translations(_ id: Int) async throws -> [Kaeru.Translation] { [] }
    func resolve(_ id: Int, translation: Int, episode: Int) async throws -> Stream { throw AppError.message("No fixture") }
}

@MainActor private final class SecretTransport: SyncTransport {
    var requests: [URLRequest] = []
    var remote: [String: Any] = [:]
    func send(_ request: URLRequest) async throws -> (Int, Data) {
        requests.append(request)
        await Task.yield()
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
}

@MainActor private final class SecretClock: SyncTimer {
    var now = Date(timeIntervalSince1970: 1_790_000_000)
    var pending: [(Date, @MainActor () -> Void)] = []
    func cancel() {}
    func schedule(_ delay: TimeInterval, _ action: @escaping @MainActor () -> Void) -> any SyncTimer {
        pending.append((now.addingTimeInterval(delay), action))
        return self
    }
    func advance(_ seconds: TimeInterval) {
        now = now.addingTimeInterval(seconds)
        let due = pending.filter { $0.0 <= now }
        pending.removeAll { $0.0 <= now }
        due.forEach { $0.1() }
    }
}

@MainActor final class SecretWatchTests: XCTestCase {
    private var anime: Anime { Anime(id: 7, title: "Test", episodes: 12, episodesAired: 12, status: "released") }
    private func session() -> Session {
        Session(account: Account(id: 1, nickname: "A", avatar: ""),
                tokens: Tokens(access_token: "token", refresh_token: "refresh", expires_in: 3600, created_at: Date().timeIntervalSince1970))
    }
    private func model(_ service: RecordingService, store: (any LocalStorage)? = nil) throws -> AppModel {
        let model = AppModel(service: service, store: try store ?? LocalStore(inMemory: true),
                             configuration: AppConfiguration(clientID: "test", proxyURL: "https://example.com"),
                             session: session(), saveSession: { _ in })
        model.preferences.watchedThreshold = 0.8
        return model
    }
    private func watched(_ model: AppModel, _ episode: Int, anime: Anime? = nil) {
        let title = anime ?? self.anime
        model.saveProgress(EpisodeProgress(animeID: title.id, episode: episode, position: 900, duration: 1000), anime: title, account: model.accountKey)
    }

    // MARK: - nothing goes to Shikimori

    func testSwitchingToSecretKeepsTheShikimoriRecordAndWritesNothing() async throws {
        let service = RecordingService()
        service.rates = [LibraryItem(id: 3, anime: anime, status: "watching", episodes: 3)]
        let model = try model(service)
        await model.reloadLibrary()
        model.queueRate(anime: anime, status: WatchStatus.secret.rawValue, episodes: 3)
        await model.flush()
        XCTAssertTrue(service.writes.isEmpty, "The Shikimori record is left as it is")
        XCTAssertTrue(model.pending.isEmpty)
        XCTAssertEqual(model.secrets[7]?.on, true)
        XCTAssertEqual(model.secrets[7]?.watched, 3, "Starts from what Shikimori counted")
        XCTAssertEqual(model.rate(for: 7)?.status, "secret")
        XCTAssertEqual(model.rate(for: 7)?.episodes, 3)
        XCTAssertEqual(model.rate(for: 7)?.id, 3, "The Shikimori record's id stays for switching back")

        // Watched past the threshold, marked by hand, unmarked, counted with the stepper: all local.
        watched(model, 4)
        XCTAssertEqual(model.secrets[7]?.watched, 4)
        watched(model, 4)
        XCTAssertEqual(model.secrets[7]?.watched, 4)
        model.saveProgress(EpisodeProgress(animeID: 7, episode: 5, position: 100, duration: 1000), anime: anime, account: model.accountKey)
        XCTAssertEqual(model.secrets[7]?.watched, 4, "Below the threshold nothing is counted")
        model.markEpisode(anime: anime, episode: 8, watched: true)
        XCTAssertEqual(model.secrets[7]?.watched, 8)
        model.markEpisode(anime: anime, episode: 6, watched: false)
        XCTAssertEqual(model.secrets[7]?.watched, 5)
        XCTAssertEqual(model.rate(for: 7)?.episodes, 5)
        model.setEpisodes(anime: anime, count: 9)
        XCTAssertEqual(model.secrets[7]?.watched, 9)
        model.setEpisodes(anime: anime, count: 2)
        XCTAssertEqual(model.secrets[7]?.watched, 2)
        model.undoEpisodeChange()
        XCTAssertEqual(model.secrets[7]?.watched, 9)
        await model.flush()
        XCTAssertTrue(service.writes.isEmpty)
        XCTAssertTrue(model.pending.isEmpty)
        XCTAssertEqual(model.library.first?.episodes, 3, "Shikimori's own count is untouched")
    }

    func testStartingASecretTitleDoesNotAddItToTheList() async throws {
        let service = RecordingService()
        let model = try model(service)
        model.queueRate(anime: anime, status: "secret", episodes: 0)
        XCTAssertEqual(model.secrets[7]?.watched, 0)
        model.beginPlayback(anime: anime)
        await model.flush()
        XCTAssertTrue(service.writes.isEmpty)
        XCTAssertTrue(model.library.isEmpty)
        XCTAssertEqual(model.rate(for: 7)?.status, "secret")
    }

    func testTheFinaleOffersNothingAndTheTitleIsFinishedLocally() async throws {
        let service = RecordingService()
        let model = try model(service)
        model.queueRate(anime: anime, status: "secret", episodes: 0)
        model.setEpisodes(anime: anime, count: 11)
        XCTAssertFalse(model.isFinished(7))
        watched(model, 12)
        XCTAssertNil(model.completionSuggestion)
        model.markEpisode(anime: anime, episode: 12, watched: false)
        model.markEpisode(anime: anime, episode: 12, watched: true)
        XCTAssertNil(model.completionSuggestion)
        XCTAssertEqual(model.secrets[7]?.watched, 12)
        XCTAssertTrue(model.isFinished(7))
        XCTAssertEqual(model.rate(for: 7)?.status, "secret", "Kept under «Украдкой»")
        XCTAssertFalse(model.continueWatching.contains { $0.id == 7 })
        await model.flush()
        XCTAssertTrue(service.writes.isEmpty)
    }

    func testAnOngoingSecretTitleIsNotFinishedAtItsAnnouncedCount() throws {
        let model = try model(RecordingService())
        let airing = Anime(id: 7, title: "Test", episodes: 12, episodesAired: 12, status: "ongoing",
                           nextEpisodeAt: ISO8601DateFormatter().string(from: Date().addingTimeInterval(4 * 24 * 3600)))
        model.queueRate(anime: airing, status: "secret", episodes: 0)
        watched(model, 12, anime: airing)
        XCTAssertEqual(model.secrets[7]?.watched, 12)
        XCTAssertFalse(model.isFinished(7))
    }

    // MARK: - switching back

    func testSwitchingBackSetsTheStatusAndSendsTheCountOnce() async throws {
        let service = RecordingService()
        service.rates = [LibraryItem(id: 3, anime: anime, status: "planned", episodes: 3)]
        let model = try model(service)
        await model.reloadLibrary()
        model.queueRate(anime: anime, status: "secret", episodes: 3)
        watched(model, 6)
        // The status menu passes what the title shows, which is the secret count.
        model.queueRate(anime: anime, status: "watching", episodes: model.rate(for: 7)?.episodes ?? 0)
        await model.flush()
        XCTAssertEqual(service.writes.count, 1)
        XCTAssertEqual(service.writes.first?.status, "watching")
        XCTAssertEqual(service.writes.first?.episodes, 6)
        XCTAssertEqual(model.secrets[7]?.on, false)
        XCTAssertEqual(model.rate(for: 7)?.status, "watching")
        XCTAssertEqual(model.rate(for: 7)?.episodes, 6)
        watched(model, 7)
        await model.flush()
        XCTAssertEqual(service.writes.last?.episodes, 7, "An ordinary title again")
    }

    func testSwitchingBackNeverLowersShikimorisCount() async throws {
        let service = RecordingService()
        service.rates = [LibraryItem(id: 3, anime: anime, status: "watching", episodes: 5)]
        let model = try model(service)
        await model.reloadLibrary()
        model.queueRate(anime: anime, status: "secret", episodes: 5)
        model.setEpisodes(anime: anime, count: 2)
        model.queueRate(anime: anime, status: "on_hold", episodes: model.rate(for: 7)?.episodes ?? 0)
        await model.flush()
        XCTAssertEqual(service.writes.map(\.status), ["on_hold"])
        XCTAssertEqual(service.writes.first?.episodes, 5)
    }

    // MARK: - «Мой список» and «Продолжить»

    func testSecretTitlesHaveTheirOwnTabAndLeaveTheirShikimoriStatus() async throws {
        let service = RecordingService()
        let other = Anime(id: 8, title: "Other", episodes: 12, episodesAired: 12, status: "released")
        service.rates = [LibraryItem(id: 3, anime: anime, status: "watching", episodes: 3),
                         LibraryItem(id: 4, anime: other, status: "watching", episodes: 1)]
        let model = try model(service)
        await model.reloadLibrary()
        let loose = Anime(id: 9, title: "Loose", episodes: 24, episodesAired: 24, status: "released")
        model.queueRate(anime: anime, status: "secret", episodes: 3)
        model.queueRate(anime: loose, status: "secret", episodes: 0)
        XCTAssertEqual(Set(model.myList.map(\.anime.id)), [7, 8, 9])
        let tab = LibraryListing.build(model.myList, LibraryQuery(recent: false, status: "secret", text: "", byTitle: true))
        XCTAssertEqual(tab.items.map(\.anime.id), [9, 7])
        XCTAssertEqual(tab.counts["secret"], 2)
        XCTAssertEqual(tab.counts["watching"], 1)
        let watching = LibraryListing.build(model.myList, LibraryQuery(recent: false, status: "watching", text: "", byTitle: true))
        XCTAssertEqual(watching.items.map(\.anime.id), [8])
        XCTAssertEqual(WatchStatus.allCases.last, .secret)
        XCTAssertEqual(WatchStatus.secret.title, "Украдкой")

        // Continuing: a secret title takes part like a list title.
        XCTAssertTrue(model.continueWatching.contains { $0.id == 9 })
    }

    func testTheSecretAndItsCardSurviveARestartWithoutTheNetwork() async throws {
        let store = try LocalStore(inMemory: true)
        let model = try model(RecordingService(), store: store)
        model.queueRate(anime: anime, status: "secret", episodes: 0)
        watched(model, 2)
        let restarted = try self.model(RecordingService(), store: store)
        XCTAssertEqual(restarted.secrets[7]?.watched, 2)
        XCTAssertEqual(restarted.myList.first?.anime.title, "Test")
        XCTAssertEqual(restarted.rate(for: 7)?.status, "secret")
    }

    // MARK: - sync

    private func synced(_ service: RecordingService, transport: SecretTransport, clock: SecretClock, syncOn: Bool,
                        store: (any LocalStorage)? = nil) throws -> AppModel {
        let store = try store ?? LocalStore(inMemory: true)
        var preferences = PlaybackPreferences()
        preferences.syncOn = syncOn
        preferences.watchedThreshold = 0.8
        try store.write(preferences, key: "playbackPreferences")
        return AppModel(service: service, store: store,
                        configuration: AppConfiguration(clientID: "test", proxyURL: "https://example.com", togetherRelayURL: "wss://relay.test"),
                        session: session(), saveSession: { _ in },
                        sync: SyncEnvironment(transport: transport, now: { clock.now }, schedule: clock.schedule, keepAlive: { {} }))
    }
    private func ms(_ date: Date) -> Int64 { Int64((date.timeIntervalSince1970 * 1000).rounded()) }

    func testWithSyncOffTheSecretStaysOnTheDevice() async throws {
        let transport = SecretTransport(), clock = SecretClock()
        let model = try synced(RecordingService(), transport: transport, clock: clock, syncOn: false)
        model.queueRate(anime: anime, status: "secret", episodes: 0)
        model.markEpisode(anime: anime, episode: 2, watched: true)
        model.pushSync(.leaving)
        clock.advance(120)
        await model.viewingSync?.settle()
        XCTAssertTrue(transport.requests.isEmpty)
        XCTAssertEqual(model.secrets[7]?.watched, 2)
    }

    func testWithSyncOnTheSecretTravelsAndSwitchingBackToo() async throws {
        let transport = SecretTransport(), clock = SecretClock(), service = RecordingService()
        let model = try synced(service, transport: transport, clock: clock, syncOn: true)
        await model.viewingSync?.pull()
        model.queueRate(anime: anime, status: "secret", episodes: 0)
        clock.advance(1)
        model.markEpisode(anime: anime, episode: 3, watched: true)
        model.pushSync(.leaving)
        await model.viewingSync?.settle()
        let secret = try XCTUnwrap((transport.posts.last?["7"] as? [String: Any])?["secret"] as? [String: Any])
        XCTAssertEqual(secret["on"] as? Bool, true)
        XCTAssertEqual(secret["watched"] as? Int, 3)
        XCTAssertEqual(secret["at"] as? Int64, ms(clock.now))

        clock.advance(61)
        model.queueRate(anime: anime, status: "watching", episodes: 3)
        await model.flush()
        model.pushSync(.leaving)
        await model.viewingSync?.settle()
        let back = try XCTUnwrap((transport.posts.last?["7"] as? [String: Any])?["secret"] as? [String: Any])
        XCTAssertEqual(back["on"] as? Bool, false)
    }

    func testANewerRemoteSecretIsTakenAndAnOlderOneIsNot() async throws {
        let transport = SecretTransport(), clock = SecretClock(), service = RecordingService()
        let model = try synced(service, transport: transport, clock: clock, syncOn: true)
        model.queueRate(anime: Anime(id: 8, title: "Mine"), status: "secret", episodes: 0)
        transport.remote = [
            "9": ["secret": ["on": true, "watched": 4, "at": ms(clock.now) + 1000]],
            "8": ["secret": ["on": false, "watched": 1, "at": ms(clock.now) - 1000]],
        ]
        await model.viewingSync?.pull()
        await model.viewingSync?.settle()
        XCTAssertEqual(model.secrets[9]?.on, true)
        XCTAssertEqual(model.secrets[9]?.watched, 4)
        XCTAssertEqual(model.secrets[8]?.on, true, "Older than this device's: ignored")
        // A title known only from the other device is fetched once so the tab has its card.
        for _ in 0..<20 where model.secrets[9]?.anime == nil { await Task.yield() }
        XCTAssertEqual(service.detailsAsked, [9])
        XCTAssertEqual(model.myList.first { $0.anime.id == 9 }?.anime.title, "Remote 9")
        XCTAssertTrue(service.writes.isEmpty, "Nothing from another device is written to Shikimori")

        // Switched off elsewhere, later: the title goes back to its Shikimori status here.
        transport.remote = ["9": ["secret": ["on": false, "watched": 4, "at": ms(clock.now) + 2000]]]
        await model.viewingSync?.pull()
        XCTAssertEqual(model.secrets[9]?.on, false)
        XCTAssertNil(model.rate(for: 9))
    }

    /// As on Android (and the spec: a finished title keeps nothing but its tombstone), a secret
    /// title watched through leaves a tombstone its secret does not outlive: whatever goes with it
    /// is no newer, and the worker drops it. Here it stays finished «украдкой».
    func testAFinishedSecretTitleLeavesATombstoneItsSecretDoesNotOutlive() async throws {
        let transport = SecretTransport(), clock = SecretClock()
        let model = try synced(RecordingService(), transport: transport, clock: clock, syncOn: true)
        await model.viewingSync?.pull()
        model.queueRate(anime: anime, status: "secret", episodes: 0)
        model.setEpisodes(anime: anime, count: 11)
        model.pushSync(.leaving)
        await model.viewingSync?.settle()
        clock.advance(61)
        watched(model, 12)
        model.pushSync(.leaving)
        await model.viewingSync?.settle()
        let seven = try XCTUnwrap(transport.posts.last?["7"] as? [String: Any])
        let gone = try XCTUnwrap(seven["gone"] as? Int64)
        XCTAssertEqual(gone, ms(clock.now))
        if let secret = seven["secret"] as? [String: Any] {
            XCTAssertLessThanOrEqual(try XCTUnwrap(secret["at"] as? Int64), gone)
        }
        XCTAssertNil(seven["eps"])
        XCTAssertEqual(model.secrets[7]?.watched, 12)
        XCTAssertTrue(model.isFinished(7))
    }

    /// The announced length the shared rules get is the secret's own card's: none without a card,
    /// or with a length not known.
    func testSyncReadsTheAnnouncedLengthFromTheSecretsCard() {
        let state = LocalSyncState.of(positions: [], translations: [:], stamps: [:], secrets: [
            7: SecretTitle(on: true, watched: 3, at: 1, anime: anime),
            8: SecretTitle(on: true, watched: 3, at: 1, anime: nil),
            9: SecretTitle(on: true, watched: 3, at: 1, anime: Anime(id: 9, title: "Announced", episodes: 0, status: "anons")),
        ])
        XCTAssertEqual(KotlinNumbers.ints(state.announcedEpisodes), [7: 12])
        XCTAssertEqual(KotlinNumbers.keyed(state.secrets).count, 3)
    }

    /// A title another device finished keeps nothing on the server but its tombstone. Watched
    /// «украдкой» here and stamped before it, it reads as watched through (`SyncRules.newer`, for
    /// every platform): the count goes up to the announced episodes, stamped as it was here, and
    /// nothing of it goes out. Finished here too, it leaves one tombstone of this device's own —
    /// and no more: by the next read the count is at the announced one already.
    func testAnotherDevicesTombstoneOverASecretTitleReadsAsWatchedThroughAndThenSettles() async throws {
        let transport = SecretTransport(), clock = SecretClock()
        let model = try synced(RecordingService(), transport: transport, clock: clock, syncOn: true)
        await model.viewingSync?.pull()
        model.queueRate(anime: anime, status: "secret", episodes: 0)
        model.setEpisodes(anime: anime, count: 9)
        let stamped = try XCTUnwrap(model.secrets[7]?.at)
        model.pushSync(.leaving)
        await model.viewingSync?.settle()
        let sent = transport.posts.count
        // Finished on another device half a minute later, and read here half a minute after that.
        clock.advance(30)
        let gone = ms(clock.now)
        transport.remote = ["7": ["gone": gone]]
        clock.advance(30)

        await model.viewingSync?.pull()

        XCTAssertEqual(model.secrets[7]?.on, true)
        XCTAssertEqual(model.secrets[7]?.watched, 12)
        XCTAssertEqual(model.secrets[7]?.at, stamped, "Stamped as it was here: nothing new to send")
        XCTAssertEqual(model.secrets[7]?.anime?.title, "Test", "The card stays")
        XCTAssertTrue(model.isFinished(7))

        clock.advance(1)
        await model.viewingSync?.settle()
        XCTAssertEqual(transport.posts.count, sent + 1)
        let seven = try XCTUnwrap(transport.posts.last?["7"] as? [String: Any])
        XCTAssertEqual(seven.keys.sorted(), ["gone"], "Only this device's own tombstone goes")
        XCTAssertGreaterThan(try XCTUnwrap(seven["gone"] as? Int64), gone)

        // The server holds that tombstone now: read again later, nothing changes and nothing goes.
        transport.remote = ["7": ["gone": try XCTUnwrap(seven["gone"] as? Int64)]]
        clock.advance(600)
        await model.viewingSync?.pull()
        clock.advance(120)
        await model.viewingSync?.settle()
        XCTAssertEqual(transport.posts.count, sent + 1)
        XCTAssertEqual(model.secrets[7]?.watched, 12)
        XCTAssertEqual(model.secrets[7]?.at, stamped)
    }

    /// As on Android: the count is the episode the mark leaves, as `SecretRules.watchedAfterMark`
    /// has it — not held at the announced number. Switching back still writes Shikimori no more
    /// than the title has.
    func testTheCountFollowsTheMarkPastTheAnnouncedNumber() async throws {
        let service = RecordingService()
        let model = try model(service)
        model.queueRate(anime: anime, status: "secret", episodes: 0)
        watched(model, 13)
        XCTAssertEqual(model.secrets[7]?.watched, 13)
        XCTAssertTrue(model.isFinished(7))
        model.queueRate(anime: anime, status: "completed", episodes: 13)
        await model.flush()
        XCTAssertEqual(service.writes.map(\.episodes), [12])
    }
}
