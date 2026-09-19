import XCTest
import AVFoundation
import KaeruShared
@testable import Kaeru

@MainActor final class LiveServiceTests: XCTestCase {
    func testSharedFrameworkIsLinked() {
        XCTAssertFalse(Platform.shared.name().isEmpty)
        XCTAssertTrue(PlaybackRules.shared.shouldMarkWatched(positionMs: 900, durationMs: 1000))
    }
    func testPublicCatalogAndKodikPlayback() async throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["KAERU_LIVE_TESTS"] == "1", "Use Kaeru-Live scheme for live network smoke checks")
        let service = SharedService(configuration: .bundled)
        let catalog = try await service.discover()
        XCTAssertFalse(catalog.isEmpty)
        let found = try await service.search("Sousou no Frieren")
        XCTAssertTrue(found.contains { $0.id == 52991 })
        let anime = try await service.details(52991)
        XCTAssertEqual(anime.episodes, 28)
        let model = AppModel(service: service, store: try LocalStore(inMemory: true), configuration: .bundled, saveSession: { _ in })
        let playback = PlaybackModel(anime: anime, episode: 1, model: model)
        defer { playback.close() }
        await playback.start()
        try await waitUntil { !playback.loading }
        XCTAssertNil(playback.error)
        XCTAssertEqual(playback.player.currentItem?.status, .readyToPlay)
        try await waitUntil { playback.player.currentTime().seconds > 1 }
        let seek = expectation(description: "Seek completes")
        playback.player.seek(to: CMTime(seconds: 300, preferredTimescale: 600)) { finished in
            XCTAssertTrue(finished); seek.fulfill()
        }
        await fulfillment(of: [seek], timeout: 20)
        playback.player.currentItem?.cancelPendingSeeks()
        if let quality = playback.qualities.first(where: { $0 != playback.quality }) {
            playback.selectQuality(quality)
            try await waitUntil { !playback.loading }
            XCTAssertNil(playback.error)
            XCTAssertGreaterThanOrEqual(playback.player.currentTime().seconds, 299)
        }
        playback.suspend()
        XCTAssertGreaterThanOrEqual(model.progress[anime.id]?.position ?? 0, 299)
        playback.becameActive()
        if let quality = playback.qualities.first(where: { $0 != playback.quality }) {
            playback.selectQuality(quality)
            try await waitUntil { !playback.loading }
            XCTAssertEqual(playback.player.rate, 0, "Changing quality must preserve the paused state")
        }
    }
    private func waitUntil(_ condition: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(60)
        while !condition(), Date() < deadline { try await Task.sleep(for: .milliseconds(250)) }
        guard condition() else { throw AppError.message("Playback did not reach expected state within 60 seconds") }
    }
}
