#if os(macOS)
import XCTest
@testable import Kaeru

@MainActor private final class QuietService: AnimeService {
    func discover() async throws -> [Anime] { [] }
    func search(_ query: String) async throws -> [Anime] { [] }
    func details(_ id: Int) async throws -> Anime { Anime(id: id, title: "Test") }
    func library(_ userID: Int64, token: String) async throws -> [Kaeru.LibraryItem] { [] }
    func exchange(_ code: String) async throws -> Tokens { throw AppError.message("Нет сети") }
    func refresh(_ token: String) async throws -> Tokens { throw AppError.message("Нет сети") }
    func account(_ token: String) async throws -> Account { throw AppError.message("Нет сети") }
    func setRate(_ pending: PendingRate, userID: Int64, rateID: Int64, token: String) async throws -> Kaeru.LibraryItem { throw AppError.message("Нет сети") }
    func translations(_ id: Int) async throws -> [Kaeru.Translation] { [] }
    func resolve(_ id: Int, translation: Int, episode: Int) async throws -> Kaeru.Stream { throw AppError.message("Нет сети") }
    func seasonal(year: Int, season: String) async throws -> [Anime] { [] }
    func configureKodikToken(_ token: String) {}
}

/// What the bar reads from the model: the volume — the viewer's, with a friend's voice on top — and whether there is a next episode.
@MainActor final class PlayerBarModelTests: XCTestCase {
    private func playback() throws -> PlaybackModel {
        let model = AppModel(service: QuietService(), store: try LocalStore(inMemory: true),
                             configuration: AppConfiguration(clientID: "test", proxyURL: "https://example.com"))
        return PlaybackModel(anime: Anime(id: 7, title: "Test", episodes: 12, episodesAired: 12, status: "released"),
                             episode: 1, model: model)
    }

    func testDuckingTurnsDownTheViewersOwnVolume() throws {
        let playback = try playback()
        playback.setVolume(0.5)
        XCTAssertEqual(playback.player.volume, 0.5, accuracy: 0.0001)
        playback.setDucked(true)
        XCTAssertEqual(playback.player.volume, 0.1, accuracy: 0.0001)
        playback.setVolume(1)
        XCTAssertEqual(playback.player.volume, 0.2, accuracy: 0.0001, "громкость, выбранная во время разговора, тоже приглушена")
        XCTAssertEqual(playback.volume, 1)
        playback.setDucked(false)
        XCTAssertEqual(playback.player.volume, 1, accuracy: 0.0001)
    }

    /// «Следующая серия» in the bar is there exactly when `hasNext` is.
    private func playback(_ anime: Anime, episode: Int) throws -> PlaybackModel {
        let model = AppModel(service: QuietService(), store: try LocalStore(inMemory: true),
                             configuration: AppConfiguration(clientID: "test", proxyURL: "https://example.com"))
        return PlaybackModel(anime: anime, episode: episode, model: model)
    }

    func testNoNextEpisodeOnTheLastOneOfAFinishedTitle() throws {
        let finished = Anime(id: 7, title: "Test", episodes: 12, episodesAired: 12, status: "released")
        XCTAssertFalse(try playback(finished, episode: 12).hasNext)
        XCTAssertTrue(try playback(finished, episode: 11).hasNext)
    }

    /// A title still airing: 24 announced, 7 out. The eighth is not there to go to yet.
    func testNoNextEpisodeOnTheLatestAiredOfAnOngoingTitle() throws {
        let ongoing = Anime(id: 8, title: "Test", episodes: 24, episodesAired: 7, status: "ongoing")
        XCTAssertFalse(try playback(ongoing, episode: 7).hasNext)
        XCTAssertTrue(try playback(ongoing, episode: 3).hasNext)
    }

    func testNextEpisodeFromAnEarlierOne() throws {
        let finished = Anime(id: 9, title: "Test", episodes: 64, episodesAired: 64, status: "released")
        XCTAssertTrue(try playback(finished, episode: 1).hasNext)
    }

    /// Moving the slider up is asking to hear: the sound comes back on, as in every Mac player.
    func testRaisingTheVolumeUnmutes() throws {
        let playback = try playback()
        playback.setMuted(true)
        playback.setVolume(0.6)
        XCTAssertFalse(playback.muted)
        XCTAssertFalse(playback.player.isMuted)
    }

    /// The next episode's window starts where the viewer left the slider.
    func testTheVolumeCarriesOverToTheNextPlayer() throws {
        try playback().setVolume(0.3)
        XCTAssertEqual(try playback().volume, 0.3, accuracy: 0.0001)
        try playback().setVolume(1)
    }
}

/// The Mac player's own control bar: the arithmetic behind its clock, its timeline and its volume.
final class PlayerBarTests: XCTestCase {
    func testClockReadsMinutesAndSecondsUnderAnHour() {
        XCTAssertEqual(PlayerBarRules.clock(0), "0:00")
        XCTAssertEqual(PlayerBarRules.clock(7.9), "0:07")
        XCTAssertEqual(PlayerBarRules.clock(65), "1:05")
        XCTAssertEqual(PlayerBarRules.clock(23 * 60 + 40), "23:40")
    }

    func testClockReadsHoursPastAnHour() {
        XCTAssertEqual(PlayerBarRules.clock(3600), "1:00:00")
        XCTAssertEqual(PlayerBarRules.clock(3600 + 5 * 60 + 3), "1:05:03")
    }

    /// Both ends of a film over an hour long say the hours, so the numbers do not change width
    /// when the playhead crosses the hour.
    func testClockKeepsTheHoursOfItsReference() {
        XCTAssertEqual(PlayerBarRules.clock(303, reference: 5400), "0:05:03")
        XCTAssertEqual(PlayerBarRules.clock(303, reference: 1400), "5:03")
    }

    func testClockTreatsNonsenseAsZero() {
        XCTAssertEqual(PlayerBarRules.clock(-4), "0:00")
        XCTAssertEqual(PlayerBarRules.clock(.nan), "0:00")
        XCTAssertEqual(PlayerBarRules.clock(.infinity), "0:00")
    }

    func testRemainingCountsDownWithAMinus() {
        XCTAssertEqual(PlayerBarRules.remaining(position: 60, duration: 1440), "−23:00")
        XCTAssertEqual(PlayerBarRules.remaining(position: 1500, duration: 1440), "−0:00")
        XCTAssertEqual(PlayerBarRules.remaining(position: 10, duration: 0), "−0:00")
    }

    func testProgressIsAFractionOfTheEpisode() {
        XCTAssertEqual(PlayerBarRules.progress(position: 360, duration: 1440), 0.25)
        XCTAssertEqual(PlayerBarRules.progress(position: 2000, duration: 1440), 1)
        XCTAssertEqual(PlayerBarRules.progress(position: -3, duration: 1440), 0)
        XCTAssertEqual(PlayerBarRules.progress(position: 30, duration: 0), 0)
        XCTAssertEqual(PlayerBarRules.progress(position: 30, duration: .nan), 0)
    }

    func testFractionFollowsThePointerAlongTheTrack() {
        XCTAssertEqual(PlayerBarRules.fraction(at: 50, width: 200), 0.25)
        XCTAssertEqual(PlayerBarRules.fraction(at: -20, width: 200), 0)
        XCTAssertEqual(PlayerBarRules.fraction(at: 260, width: 200), 1)
        XCTAssertEqual(PlayerBarRules.fraction(at: 10, width: 0), 0)
    }

    func testScrubTargetIsClampedToTheEpisode() {
        XCTAssertEqual(PlayerBarRules.seekTarget(fraction: 0.5, duration: 1440), 720)
        XCTAssertEqual(PlayerBarRules.seekTarget(fraction: -0.2, duration: 1440), 0)
        XCTAssertEqual(PlayerBarRules.seekTarget(fraction: 1.4, duration: 1440), 1440)
    }

    /// Before the length of the episode is known there is nowhere to seek to.
    func testNoScrubTargetWithoutADuration() {
        XCTAssertNil(PlayerBarRules.seekTarget(fraction: 0.5, duration: 0))
        XCTAssertNil(PlayerBarRules.seekTarget(fraction: 0.5, duration: .nan))
        XCTAssertNil(PlayerBarRules.seekTarget(fraction: .nan, duration: 1440))
    }

    /// The friend's voice turns the episode down to a fifth of whatever the viewer chose.
    func testEffectiveVolumeComposesWithDucking() {
        XCTAssertEqual(PlayerBarRules.effectiveVolume(user: 1, ducked: false), 1)
        XCTAssertEqual(PlayerBarRules.effectiveVolume(user: 1, ducked: true), 0.2, accuracy: 0.0001)
        XCTAssertEqual(PlayerBarRules.effectiveVolume(user: 0.5, ducked: false), 0.5)
        XCTAssertEqual(PlayerBarRules.effectiveVolume(user: 0.5, ducked: true), 0.1, accuracy: 0.0001)
        XCTAssertEqual(PlayerBarRules.effectiveVolume(user: 1.7, ducked: false), 1)
        XCTAssertEqual(PlayerBarRules.effectiveVolume(user: -1, ducked: true), 0)
        XCTAssertEqual(PlayerBarRules.effectiveVolume(user: .nan, ducked: false), 1)
    }

    func testVolumeSliderIsTuckedAwayUntilReachedFor() {
        XCTAssertFalse(PlayerBarRules.volumeExpanded(hovering: false, dragging: false, focused: false))
        XCTAssertTrue(PlayerBarRules.volumeExpanded(hovering: true, dragging: false, focused: false))
        XCTAssertTrue(PlayerBarRules.volumeExpanded(hovering: false, dragging: true, focused: false),
                      "тянут ползунок — указатель мог уйти с тонкой дорожки")
        XCTAssertTrue(PlayerBarRules.volumeExpanded(hovering: false, dragging: false, focused: true),
                      "VoiceOver на ползунке")
    }

    func testVolumeSymbolFollowsTheLevel() {
        XCTAssertEqual(PlayerBarRules.volumeSymbol(volume: 0.8, muted: true), "speaker.slash.fill")
        XCTAssertEqual(PlayerBarRules.volumeSymbol(volume: 0, muted: false), "speaker.slash.fill")
        XCTAssertEqual(PlayerBarRules.volumeSymbol(volume: 0.2, muted: false), "speaker.wave.1.fill")
        XCTAssertEqual(PlayerBarRules.volumeSymbol(volume: 0.5, muted: false), "speaker.wave.2.fill")
        XCTAssertEqual(PlayerBarRules.volumeSymbol(volume: 1, muted: false), "speaker.wave.3.fill")
    }
}
#endif
