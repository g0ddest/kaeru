import XCTest
@testable import Kaeru

final class CastCoreTests: XCTestCase {
    private let anime = Anime(id: 42, title: "Example", poster: "https://example.com/poster.jpg")

    func testLoadFallsBackToHighestQualityAndNeverSerializesLocalHeaders() throws {
        let stream = Stream(urls: [.init(quality: 1080, url: "https://example.com/high"),
                                   .init(quality: 480, url: "https://example.com/low")],
                            headers: ["Authorization": "private", "Referer": "https://private.example"],
                            translation: .init(id: 7, title: "Voice", episodes: 12), episode: 3)
        let payload = try CastLoadPayload(anime: anime, stream: stream, quality: 720, position: 81, autoplay: false)
        XCTAssertEqual(payload.url.absoluteString, "https://example.com/high")
        XCTAssertEqual(payload.selection.quality, 1080)
        XCTAssertEqual(payload.title, "Example")
        XCTAssertEqual(payload.subtitle, "3 серия   Voice")
        XCTAssertEqual(payload.contentType, "application/x-mpegURL")
        XCTAssertEqual(payload.position, 81)
        XCTAssertFalse(payload.autoplay)
        let json = String(decoding: try JSONEncoder().encode(payload), as: UTF8.self)
        XCTAssertFalse(json.contains("private"))
        XCTAssertFalse(json.contains("headers"))
    }

    func testAutoChoosesHighestAndExplicitAvailableQualityIsRespected() throws {
        let stream = Stream(urls: [.init(quality: 480, url: "https://example.com/480"),
                                   .init(quality: 1080, url: "https://example.com/1080"),
                                   .init(quality: 720, url: "https://example.com/720")],
                            headers: [:], translation: .init(id: 7, title: "Voice", episodes: 12), episode: 3)
        for (requested, expected) in [(0, 1080), (480, 480), (720, 720), (2160, 1080), (360, 1080)] {
            let payload = try CastLoadPayload(anime: anime, stream: stream, quality: requested, position: 0, autoplay: true)
            XCTAssertEqual(payload.selection.quality, expected)
        }
    }

    func testLocalFilesAndCredentialURLsCannotBeSentToReceiver() {
        for url in ["file:///tmp/movie.m3u8", "https://user:secret@example.com/video", "https:///missing-host"] {
            let stream = Stream(urls: [.init(quality: 720, url: url)], headers: [:],
                                translation: .init(id: 7, title: "Voice", episodes: 12), episode: 3)
            XCTAssertThrowsError(try CastLoadPayload(anime: anime, stream: stream, quality: 720, position: 0, autoplay: true))
        }
    }

    func testHandoffKeepsRequestedPositionUntilMatchingReceiverReports() {
        var state = CastPlaybackState()
        let handoff = CastHandoff(selection: .init(anime: anime, episode: 3, translation: 7, quality: 720),
                                  position: 120, shouldPlay: false)
        state.begin(handoff)
        state.contentID = "new"
        XCTAssertFalse(state.update(.init(contentID: "old", position: 900, duration: 1000, phase: .playing)))
        XCTAssertEqual(state.handoff?.position, 120)
        XCTAssertFalse(state.handoff!.shouldPlay)
        XCTAssertTrue(state.update(.init(contentID: "new", position: 124, duration: 1440, phase: .paused)))
        XCTAssertEqual(state.handoff?.position, 124)
        XCTAssertFalse(state.handoff!.shouldPlay)
    }

    func testFinishedIsDistinctFromStoppedAndUnknownDurationIsSafe() {
        var state = CastPlaybackState()
        state.begin(.init(selection: .init(anime: anime, episode: 3, translation: 7, quality: 720), position: 12, shouldPlay: true))
        state.contentID = "video"
        _ = state.update(.init(contentID: "video", position: .nan, duration: .infinity, phase: .buffering))
        XCTAssertEqual(state.position, 12)
        XCTAssertEqual(state.duration, 0)
        XCTAssertTrue(state.handoff!.shouldPlay)
        _ = state.update(.init(contentID: "video", position: 20, duration: 30, phase: .stopped))
        XCTAssertFalse(state.ended)
        _ = state.update(.init(contentID: "video", position: 30, duration: 30, phase: .finished))
        XCTAssertTrue(state.ended)
        XCTAssertFalse(state.handoff!.shouldPlay)
    }

    func testNewEpisodeResetsDurationAndHandoffDoesNotLeakPreviousEpisode() {
        var state = CastPlaybackState()
        state.begin(.init(selection: .init(anime: anime, episode: 2, translation: 7, quality: 720), position: 100, shouldPlay: true))
        state.contentID = "old"
        _ = state.update(.init(contentID: "old", position: 100, duration: 1440, phase: .playing))
        state.begin(.init(selection: .init(anime: anime, episode: 3, translation: 8, quality: 480), position: 0, shouldPlay: true))
        XCTAssertEqual(state.position, 0)
        XCTAssertEqual(state.duration, 0)
        XCTAssertNil(state.contentID)
        XCTAssertEqual(state.handoff?.selection.episode, 3)
        XCTAssertEqual(state.handoff?.selection.translation, 8)
    }
}
