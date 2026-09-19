import XCTest
@testable import Kaeru

final class PlaybackParityTests: XCTestCase {
    private let marks = SkipMarks(opening: SkipInterval(start: 20, end: 110), ending: SkipInterval(start: 1100, end: 1190))

    func testAniSkipUsesMALIDRepeatedTypesAndRoundedActualDuration() throws {
        let request = try XCTUnwrap(AniSkipClient.request(animeID: 17, episode: 3, duration: 1200.6))
        let url = try XCTUnwrap(request.url)
        XCTAssertEqual(url.host, "api.aniskip.com")
        XCTAssertEqual(url.path, "/v2/skip-times/17/3")
        let query = try XCTUnwrap(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems)
        XCTAssertEqual(query.filter { $0.name == "types[]" }.compactMap(\.value), ["op", "ed", "mixed-op", "mixed-ed"])
        XCTAssertEqual(query.first { $0.name == "episodeLength" }?.value, "1201")
        for duration in [0.0, -1, .nan, .infinity] {
            XCTAssertNil(AniSkipClient.request(animeID: 17, episode: 3, duration: duration))
        }
    }

    func testAniSkipFiltersImplausibleMarksAndAcceptsFirstValidMixedType() throws {
        let data = Data(#"{"found":true,"results":[{"skipType":"op","interval":{"startTime":400,"endTime":490}},{"skipType":"mixed-op","interval":{"startTime":20,"endTime":110},"episodeLength":1200,"future":1},{"skipType":"op","interval":{"startTime":0,"endTime":90}},{"skipType":"ed","interval":{"startTime":100,"endTime":190}},{"skipType":"mixed-ed","interval":{"startTime":1100,"endTime":1190}}]}"#.utf8)
        XCTAssertEqual(try AniSkipClient.decode(data, duration: 1200), marks)
        XCTAssertEqual(try AniSkipClient.decode(Data(#"{"found":false}"#.utf8), duration: 1200), SkipMarks())
        XCTAssertEqual(try AniSkipClient.decode(Data(#"{}"#.utf8), duration: 1200), SkipMarks())
    }

    func testOnlyPlausibleDurationMatchedMarksAreEligible() {
        for interval in [SkipInterval(start: -1, end: 89), .init(start: 0, end: 59.999), .init(start: 0, end: 150.001), .init(start: .nan, end: 90), .init(start: 1190, end: 1280)] {
            XCTAssertNil(SkipMarks(opening: interval, ending: interval).accepted(duration: 1200).opening)
            XCTAssertNil(SkipMarks(opening: interval, ending: interval).accepted(duration: 1200).ending)
        }
        XCTAssertEqual(marks.accepted(duration: .nan), SkipMarks())
        XCTAssertEqual(marks.accepted(duration: 0), SkipMarks())
    }

    func testManualSkipOfferExistsOnlyDuringFirstTenMediaSeconds() {
        XCTAssertNil(marks.offer(position: 19.99, duration: 1200))
        XCTAssertEqual(marks.offer(position: 20, duration: 1200)?.kind, .opening)
        XCTAssertEqual(marks.offer(position: 29.99, duration: 1200)?.interval.end, 110)
        XCTAssertNil(marks.offer(position: 30, duration: 1200))
        XCTAssertEqual(marks.offer(position: 1100, duration: 1200)?.kind, .ending)
        XCTAssertNil(marks.offer(position: 1110, duration: 1200))
    }

    func testNextOfferAtThirtyCountdownAtTenUsesMediaTimeAndCeiling() {
        var policy = PlaybackPolicy()
        XCTAssertFalse(policy.next(position: 1169.9, duration: 1200, hasNext: true, autoNext: true).offered)
        XCTAssertEqual(policy.next(position: 1170, duration: 1200, hasNext: true, autoNext: true), NextEpisodeState(offered: true))
        XCTAssertEqual(policy.next(position: 1190, duration: 1200, hasNext: true, autoNext: true).countdown, 10)
        XCTAssertEqual(policy.next(position: 1195.1, duration: 1200, hasNext: true, autoNext: true).countdown, 5)
        XCTAssertEqual(policy.next(position: 1195.1, duration: 1200, hasNext: true, autoNext: true).countdown, 5)
        XCTAssertTrue(policy.next(position: 1200, duration: 1200, hasNext: true, autoNext: true).advance)
        XCTAssertFalse(policy.next(position: 1200, duration: 1200, hasNext: true, autoNext: true).advance)
    }

    func testCancelledCountdownLeavesOfferAndCannotAdvanceAtEndUntilNewEpisode() {
        var policy = PlaybackPolicy()
        policy.cancelAutoplay()
        XCTAssertEqual(policy.next(position: 1195, duration: 1200, hasNext: true, autoNext: true), NextEpisodeState(offered: true))
        XCTAssertFalse(policy.next(position: 1200, duration: 1200, hasNext: true, autoNext: true).advance)
        policy.resetEpisode()
        XCTAssertTrue(policy.next(position: 1200, duration: 1200, hasNext: true, autoNext: true).advance)
    }

    func testSeekingAwayHidesCountdownAndReenteringRecomputesRemainingTime() {
        var policy = PlaybackPolicy()
        _ = policy.next(position: 1195, duration: 1200, hasNext: true, autoNext: true)
        XCTAssertEqual(policy.next(position: 200, duration: 1200, hasNext: true, autoNext: true), NextEpisodeState())
        XCTAssertEqual(policy.next(position: 1198, duration: 1200, hasNext: true, autoNext: true).countdown, 2)
    }

    func testUnknownDurationLastEpisodeAndDisabledAutoplayNeverAdvance() {
        var policy = PlaybackPolicy()
        for duration in [0.0, -1, .nan, .infinity] {
            XCTAssertEqual(policy.next(position: 1200, duration: duration, hasNext: true, autoNext: true, ended: true), NextEpisodeState())
        }
        XCTAssertEqual(policy.next(position: 1200, duration: 1200, hasNext: false, autoNext: true, ended: true), NextEpisodeState())
        XCTAssertEqual(policy.next(position: 1200, duration: 1200, hasNext: true, autoNext: false, ended: true), NextEpisodeState(offered: true))
    }

    func testOpeningNeverAutomaticallySkips() {
        var policy = PlaybackPolicy()
        XCTAssertNil(policy.automaticSkip(marks: marks, position: 21, duration: 1200, playing: true, ending: true))
    }

    func testAutoEndingWaitsTenSecondsAndDoesNotFireImmediatelyAfterScrub() {
        var policy = PlaybackPolicy()
        XCTAssertNil(policy.automaticSkip(marks: marks, position: 1109, duration: 1200, playing: true, ending: true))
        XCTAssertEqual(policy.automaticSkip(marks: marks, position: 1110, duration: 1200, playing: true, ending: true), .finishEnding)
        XCTAssertNil(policy.automaticSkip(marks: marks, position: 1111, duration: 1200, playing: true, ending: true))
        policy.resetEpisode()
        policy.didSeek(to: 1150)
        XCTAssertNil(policy.automaticSkip(marks: marks, position: 1150, duration: 1200, playing: true, ending: true))
        XCTAssertNil(policy.automaticSkip(marks: marks, position: 1150.5, duration: 1200, playing: true, ending: true))
        XCTAssertEqual(policy.automaticSkip(marks: marks, position: 1151, duration: 1200, playing: true, ending: true), .finishEnding)
    }

    func testEndingNeverAutoSkipsWhenPausedOrOutsideInterval() {
        var policy = PlaybackPolicy()
        for position in [1000.0, 1190, 1200] {
            XCTAssertNil(policy.automaticSkip(marks: marks, position: position, duration: 1200, playing: true, ending: true))
        }
        XCTAssertNil(policy.automaticSkip(marks: marks, position: 1115, duration: 1200, playing: false, ending: true))
    }

    func testAutoQualityUsesBestAndUnavailableQualityFallsBackToBest() {
        XCTAssertEqual(PlaybackPolicy.quality(preferred: 0, available: [360, 1080, 720]), 1080)
        XCTAssertEqual(PlaybackPolicy.quality(preferred: 720, available: [360, 1080, 720]), 720)
        XCTAssertEqual(PlaybackPolicy.quality(preferred: 720, available: [360, 1080]), 1080)
        XCTAssertEqual(PlaybackPolicy.quality(preferred: 240, available: [360, 1080]), 1080)
        XCTAssertNil(PlaybackPolicy.quality(preferred: 0, available: []))
    }

    func testResumeUsesConfiguredThresholdAndRejectsInvalidSamples() {
        XCTAssertEqual(PlaybackPolicy.resume(position: 850, duration: 1000, threshold: 0.9), 850)
        XCTAssertEqual(PlaybackPolicy.resume(position: 850, duration: 1000, threshold: 0.8), 0)
        XCTAssertEqual(PlaybackPolicy.resume(position: .nan, duration: 1000, threshold: 0.9), 0)
        XCTAssertEqual(PlaybackPolicy.resume(position: 50, duration: 0, threshold: 0.9), 0)
        XCTAssertEqual(PlaybackPolicy.clampSeek(-10, duration: 100), 0)
        XCTAssertEqual(PlaybackPolicy.clampSeek(110, duration: 100), 100)
        XCTAssertEqual(PlaybackPolicy.clampSeek(110, duration: .nan), 110)
    }

    func testSystemSuspensionPreservesIntentButUserPauseCancelsResume() {
        var intent = PlaybackIntent()
        intent.suspend(backgroundAllowed: false, pictureInPicture: false)
        XCTAssertFalse(intent.shouldPlay)
        intent.activate()
        XCTAssertTrue(intent.shouldPlay)
        intent.userSetPlaying(false)
        intent.suspend(backgroundAllowed: false, pictureInPicture: false)
        intent.activate()
        XCTAssertFalse(intent.shouldPlay)
    }

    func testPiPAndBackgroundKeepPlaybackAndNeverStartPausedVideo() {
        for pip in [false, true] {
            var intent = PlaybackIntent()
            intent.suspend(backgroundAllowed: !pip, pictureInPicture: pip)
            XCTAssertTrue(intent.shouldPlay)
            intent.userSetPlaying(false)
            XCTAssertFalse(intent.shouldPlay)
            intent.activate()
            XCTAssertFalse(intent.shouldPlay)
        }
    }
}
