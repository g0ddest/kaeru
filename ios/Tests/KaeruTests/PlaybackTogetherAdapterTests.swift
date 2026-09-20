import XCTest
import AVFoundation
@testable import Kaeru
private typealias LibraryItem = Kaeru.LibraryItem
private typealias Stream = Kaeru.Stream

@MainActor private final class SilentService: AnimeService {
    func discover() async throws -> [Anime] { [] }
    func search(_ query: String) async throws -> [Anime] { [] }
    func details(_ id: Int) async throws -> Anime { Anime(id: id, title: "Test") }
    func library(_ userID: Int64, token: String) async throws -> [LibraryItem] { [] }
    func exchange(_ code: String) async throws -> Tokens { throw AppError.message("Нет сети") }
    func refresh(_ token: String) async throws -> Tokens { throw AppError.message("Нет сети") }
    func account(_ token: String) async throws -> Account { throw AppError.message("Нет сети") }
    func setRate(_ pending: PendingRate, userID: Int64, rateID: Int64, token: String) async throws -> LibraryItem { throw AppError.message("Нет сети") }
    func translations(_ id: Int) async throws -> [Translation] { [] }
    func resolve(_ id: Int, translation: Int, episode: Int) async throws -> Stream { throw AppError.message("Нет сети") }
    func seasonal(year: Int, season: String) async throws -> [Anime] { [] }
    func configureKodikToken(_ token: String) {}
}

/// The seam a shared session drives AVPlayer through, and the two things about AVPlayer that the
/// first version of it did not know: a rate is a play command, and a rate of 1.03 is played as 1.0
/// unless the item is told to keep pitch with an algorithm that takes any rate.
@MainActor final class PlaybackTogetherAdapterTests: XCTestCase {
    private func bench() throws -> (PlaybackModel, PlaybackTogetherAdapter) {
        let model = AppModel(service: SilentService(), store: try LocalStore(inMemory: true),
                             configuration: AppConfiguration(clientID: "test", proxyURL: "https://example.com"))
        let playback = PlaybackModel(anime: Anime(id: 7, title: "Test", episodes: 12, episodesAired: 12, status: "released"),
                                     episode: 1, model: model)
        let manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Гость")
        return (playback, PlaybackTogetherAdapter(playback: playback, manager: manager))
    }

    /// Normal speed handed to a paused picture used to start it: a pause a beat after a nudge
    /// ended was undone, and announced to the friend as this viewer pressing play.
    /// The first seconds of an episode opened for the room: no player yet, no rate, only intent.
    func testAResolvingPictureThatMeansToPlayReportsPlayingAndBuffering() throws {
        let (playback, adapter) = try bench()
        let report = adapter.togetherSnapshot
        XCTAssertTrue(report.buffering, "поток ещё разрешается — это буферизация")
        XCTAssertTrue(report.playing, "и намерение играть, а не пауза с пустым буфером")
        XCTAssertTrue(playback.wantsPlayback)
    }
    func testACorrectionOnAPausedPictureDoesNotStartIt() throws {
        let (playback, adapter) = try bench()
        defer { playback.close() }
        playback.player.replaceCurrentItem(with: AVPlayerItem(url: URL(fileURLWithPath: "/dev/null")))
        playback.player.pause()
        adapter.togetherSetRate(1)
        XCTAssertEqual(playback.player.rate, 0)
        adapter.togetherSetRate(1.03)
        XCTAssertEqual(playback.player.rate, 0)
    }

    /// The pitch algorithm an item comes with snaps the rate to a handful of values, and three
    /// percent either way snaps to 1.0 — which is a correction that corrects nothing, for the
    /// whole of an evening.
    func testACorrectionAsksForARateThePlayerWillActuallyMake() throws {
        let (playback, adapter) = try bench()
        defer { playback.close() }
        let item = AVPlayerItem(url: URL(fileURLWithPath: "/dev/null"))
        playback.player.replaceCurrentItem(with: item)
        playback.player.rate = 1
        adapter.togetherSetRate(1.03)
        XCTAssertEqual(item.audioTimePitchAlgorithm, .timeDomain)
        XCTAssertEqual(playback.player.rate, 1.03, accuracy: 0.001)
        adapter.togetherSetRate(1)
        XCTAssertEqual(playback.player.rate, 1, accuracy: 0.001)
    }

    /// A picture waiting for a segment is on its way to playing, and the friend is told so — the
    /// whole of «ждём друга» hangs on a stall being told apart from a pause.
    func testAPictureWaitingForTheNetworkIsReportedAsPlayingAndBuffering() throws {
        let (playback, adapter) = try bench()
        defer { playback.close() }
        playback.player.replaceCurrentItem(with: AVPlayerItem(url: URL(fileURLWithPath: "/dev/null")))
        XCTAssertTrue(adapter.togetherSnapshot.buffering)
        // Before the engine has a rate at all, the answer is the intent — and a fresh player means
        // to play. It used to read as a pause, and the friend ran ahead through every opening.
        XCTAssertTrue(adapter.togetherSnapshot.playing, "намерение играть — уже при загрузке")
        playback.player.rate = 1
        XCTAssertTrue(adapter.togetherSnapshot.buffering)
        XCTAssertTrue(adapter.togetherSnapshot.playing, "стоп на пути к воспроизведению — не пауза")
    }
}
