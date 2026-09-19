import XCTest
@testable import Kaeru

final class OfflineCoreTests: XCTestCase {
    private func entry(_ episode: Int = 1) -> DownloadEntry {
        DownloadEntry(anime: Anime(id: 7, title: "Test"), episode: episode, translation: 3, quality: 720)
    }

    func testPolicyBoundaryReservationsAndUnlimited() {
        var policy = DownloadPolicies()
        policy.storageLimitBytes = 800
        XCTAssertTrue(policy.fits(used: 400, additional: 400))
        XCTAssertFalse(policy.fits(used: 401, additional: 400))
        XCTAssertFalse(policy.fits(used: Int64.max, additional: 400))
        policy.storageLimitBytes = nil
        XCTAssertTrue(policy.fits(used: Int64.max, additional: 400))
        XCTAssertEqual(DownloadPolicies().quality, 720)
        XCTAssertTrue(DownloadPolicies().wifiOnly)
    }

    func testIdentityIncludesTranslationAndQuality() {
        let row = entry()
        XCTAssertEqual(row.id, "7:1:3:720")
        var other = row
        other.quality = 1080
        XCTAssertNotEqual(row.id, other.id)
        other = row
        other.translation = 4
        XCTAssertNotEqual(row.id, other.id)
    }

    func testQualityChoiceNeverSilentlyIncreasesUnlessAllChoicesAreHigher() {
        XCTAssertEqual(OfflineRules.quality(wanted: 720, available: [360, 1080]), 360)
        XCTAssertEqual(OfflineRules.quality(wanted: 240, available: [360, 720]), 360)
        XCTAssertEqual(OfflineRules.quality(wanted: 0, available: [360, 1080]), 1080)
        XCTAssertNil(OfflineRules.quality(wanted: 720, available: []))
    }

    func testReconciliationRecoversLostTasksButPreservesUserPauseAndCancel() {
        var running = entry(); running.state = .downloading; running.taskToken = "old"
        var paused = entry(2); paused.state = .paused
        var cancelled = entry(3); cancelled.state = .cancelled
        var complete = entry(4); complete.state = .completed; complete.relativePath = "missing"
        let rows = OfflineRules.reconcile([running, paused, cancelled, complete], liveTokens: [], playableIDs: [])
        XCTAssertEqual(rows.map(\.state), [.queued, .paused, .cancelled, .failed])
        XCTAssertNil(rows[0].taskToken)
        XCTAssertEqual(rows[3].failure, .missingFile)
    }

    func testReconciliationKeepsLiveTaskAndRecoversCompletedFile() {
        var live = entry(); live.state = .downloading; live.taskToken = "live"
        var finished = entry(2); finished.taskToken = "finished"; finished.relativePath = "movie.mp4"
        let rows = OfflineRules.reconcile([live, finished], liveTokens: ["live"], playableIDs: [finished.id])
        XCTAssertEqual(rows[0].taskToken, "live")
        XCTAssertEqual(rows[1].state, .completed)
        XCTAssertEqual(rows[1].progress, 1)
    }

    func testExpiredLinkBudgetIsDurableAndSliding() throws {
        var row = entry()
        let now = Date(timeIntervalSince1970: 10_000)
        for _ in 0..<3 { XCTAssertTrue(OfflineRules.claimRefresh(&row, now: now)) }
        row = try JSONDecoder().decode(DownloadEntry.self, from: JSONEncoder().encode(row))
        XCTAssertFalse(OfflineRules.claimRefresh(&row, now: now.addingTimeInterval(3599)))
        XCTAssertTrue(OfflineRules.claimRefresh(&row, now: now.addingTimeInterval(3601)))
        XCTAssertTrue(OfflineRules.shouldRefresh(status: 403, bytes: 0))
        XCTAssertTrue(OfflineRules.shouldRefresh(status: 410, bytes: 0))
        XCTAssertTrue(OfflineRules.shouldRefresh(status: 500, bytes: 1))
        XCTAssertFalse(OfflineRules.shouldRefresh(status: 404, bytes: 0))
    }

    func testWatchedDeletionOnlyPromisesCompletedDownloadsAndWaitsForPlayback() {
        var row = entry(); row.state = .completed
        var queued = entry(2)
        OfflineRules.markWatched(&queued, enabled: true)
        XCTAssertFalse(queued.deleteWhenReleased)
        OfflineRules.markWatched(&row, enabled: true)
        XCTAssertFalse(OfflineRules.canDeleteWatched(row, playing: OfflineEpisode(animeID: 7, episode: 1)))
        XCTAssertTrue(OfflineRules.canDeleteWatched(row, playing: nil))
        row.deleteWhenReleased = false // Explicit un-watch revokes the promise.
        XCTAssertFalse(OfflineRules.canDeleteWatched(row, playing: nil))
    }

    func testCatalogRoundTripAtomicSaveAndCorruptionDoesNotSilentlyReset() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = OfflineCatalogStore(directory: directory)
        var catalog = OfflineCatalog(); catalog.entries = [entry()]; catalog.policies.wifiOnly = false
        try store.save(catalog)
        let loaded = try store.load()
        XCTAssertEqual(loaded.entries.first?.id, entry().id)
        XCTAssertFalse(loaded.policies.wifiOnly)
        try Data("broken".utf8).write(to: store.fileURL)
        XCTAssertThrowsError(try store.load())
    }

    func testOldEmptyCatalogUsesPolicyDefaults() throws {
        let old = try JSONDecoder().decode(OfflineCatalog.self, from: Data("{}".utf8))
        XCTAssertTrue(old.entries.isEmpty)
        XCTAssertEqual(old.policies.storageLimitBytes, 5 * 1024 * 1024 * 1024)
    }

}

final class OfflineReleaseTests: XCTestCase {
    private func item(aired: Int, watched: Int = 2, status: String = "watching") -> Kaeru.LibraryItem {
        Kaeru.LibraryItem(id: 1, anime: Anime(id: 7, title: "Show", episodesAired: aired), status: status, episodes: watched)
    }
    func testSilentBaselineThenOneAlertForNextUnwatchedEpisode() {
        var state = EpisodeReleaseState()
        XCTAssertTrue(state.process(library: [item(aired: 2)], progress: []).isEmpty)
        let alerts = state.process(library: [item(aired: 4)], progress: [])
        XCTAssertEqual(alerts.map(\.episode), [3])
        XCTAssertTrue(state.process(library: [item(aired: 5)], progress: []).isEmpty)
    }
    func testStartedEpisodeCompletedLibraryAndNoIncreaseAreSilent() {
        var state = EpisodeReleaseState()
        _ = state.process(library: [item(aired: 2)], progress: [])
        let started = EpisodeProgress(animeID: 7, episode: 3, position: 2, duration: 100)
        XCTAssertTrue(state.process(library: [item(aired: 3)], progress: [started]).isEmpty)
        XCTAssertTrue(state.process(library: [item(aired: 4, status: "completed")], progress: []).isEmpty)
    }
    func testNewlyAddedTitleIsSilentAndDedupSurvivesRestart() throws {
        var state = EpisodeReleaseState()
        _ = state.process(library: [], progress: [])
        XCTAssertTrue(state.process(library: [item(aired: 2, status: "rewatching")], progress: []).isEmpty)
        XCTAssertEqual(state.process(library: [item(aired: 4, status: "rewatching")], progress: []).count, 1)
        state = try JSONDecoder().decode(EpisodeReleaseState.self, from: JSONEncoder().encode(state))
        XCTAssertTrue(state.process(library: [item(aired: 5, status: "rewatching")], progress: []).isEmpty)
    }
}

final class OfflineQueueTests: XCTestCase {
    func testTwoSlotsPreserveExistingTransfersBeforeNewQueuedWork() {
        var queued = DownloadEntry(anime: Anime(id: 1, title: "A"), episode: 1, translation: 1, quality: 720)
        queued.taskToken = "queued"
        var live = queued; live.episode = 2; live.taskToken = "live"; live.state = .downloading
        var resolving = queued; resolving.episode = 3; resolving.taskToken = "resolving"; resolving.state = .resolving
        XCTAssertEqual(OfflineRules.admittedIDs([queued, live, resolving], occupiedTokens: ["live", "resolving"]), [live.id, resolving.id])
        live.state = .paused
        XCTAssertEqual(OfflineRules.admittedIDs([queued, live, resolving], occupiedTokens: ["resolving"]), [resolving.id, queued.id])
    }
    func testNetworkFailureWaitsForExplicitNetworkRecovery() {
        var row = DownloadEntry(anime: Anime(id: 1, title: "A"), episode: 1, translation: 1, quality: 720)
        row.state = .waitingForNetwork; row.failure = .network
        XCTAssertTrue(OfflineRules.admittedIDs([row], occupiedTokens: []).isEmpty)
        row.failure = nil
        XCTAssertEqual(OfflineRules.admittedIDs([row], occupiedTokens: []), [row.id])
    }
    func testUnlimitedPolicyPersistsAsUnlimited() throws {
        var policy = DownloadPolicies(); policy.storageLimitBytes = nil
        let restored = try JSONDecoder().decode(DownloadPolicies.self, from: JSONEncoder().encode(policy))
        XCTAssertNil(restored.storageLimitBytes)
    }
    func testBaselinePairIsNotAnnouncedAndAccidentalOpenDoesNotSuppressNews() {
        var state = EpisodeReleaseState()
        var item = Kaeru.LibraryItem(id: 1, anime: Anime(id: 7, title: "Show", episodesAired: 2), status: "watching", episodes: 1)
        _ = state.process(library: [item], progress: [])
        item.anime.episodesAired = 3
        XCTAssertTrue(state.process(library: [item], progress: []).isEmpty) // Episode 2 was the baseline pair.
        item.episodes = 3; item.anime.episodesAired = 5
        let accidental = EpisodeProgress(animeID: 7, episode: 4, position: 1, duration: 1400)
        XCTAssertEqual(state.process(library: [item], progress: [accidental]).map(\.episode), [4])
    }
}
