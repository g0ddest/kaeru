import XCTest
@testable import Kaeru

/// The part of Watch Together that has to agree with a phone on the other side of the country:
/// whose clock is whose, how far apart the two pictures are, and what is worth doing about it.
@MainActor final class TogetherSyncTests: XCTestCase {

    // MARK: - the clock

    func testOffsetIsTheMedianSoOneSlowPacketCannotMoveIt() {
        var clock = TogetherClock()
        // Four honest round trips: 100 ms there, 100 ms back, the friend's clock 500 ms ahead.
        for step in 0..<4 {
            let sent = Int64(step) * 1000
            clock.record(sent: sent, peerReceived: sent + 600, peerSent: sent + 600, received: sent + 200)
        }
        XCTAssertEqual(clock.offsetMs, 500)
        // One packet that sat in a buffer for nine seconds on the way back. An average would put
        // the offset near −4 s and order a seek; a median cannot be moved by one sample at all.
        clock.record(sent: 9000, peerReceived: 9600, peerSent: 9600, received: 18_000)
        XCTAssertEqual(clock.offsetMs, 500)
        XCTAssertEqual(clock.rttMs, 200)
    }

    func testOnlyTheLastFiveSamplesCount() {
        var clock = TogetherClock()
        for _ in 0..<5 { clock.record(sent: 0, peerReceived: 100, peerSent: 100, received: 0) }
        XCTAssertEqual(clock.offsetMs, 100)
        // A phone moving from Wi-Fi to mobile data changes the path; the window has to forget.
        for _ in 0..<5 { clock.record(sent: 0, peerReceived: 900, peerSent: 900, received: 0) }
        XCTAssertEqual(clock.offsetMs, 900)
    }

    /// A phone that went to the background answers every ping it finds waiting when it comes
    /// back, and each answer's trip is the whole of the freeze; half of that would land on the
    /// offset, and three of them would carry the median. Android's `ClockOffset` refuses the same.
    func testAnAnswerThatTookSecondsToComeBackIsNotAMeasurementOfTheClocks() {
        var clock = TogetherClock()
        for step in 0..<4 {
            let sent = Int64(step) * 5_000
            clock.record(sent: sent, peerReceived: sent + 800, peerSent: sent + 800, received: sent + 200)
        }
        XCTAssertEqual(clock.offsetMs, 700)
        // Fifteen seconds asleep: three pings answered at once.
        clock.record(sent: 20_000, peerReceived: 35_700, peerSent: 35_700, received: 35_000)
        clock.record(sent: 25_000, peerReceived: 35_700, peerSent: 35_700, received: 35_000)
        clock.record(sent: 30_000, peerReceived: 35_700, peerSent: 35_700, received: 35_000)
        XCTAssertEqual(clock.offsetMs, 700)
        XCTAssertEqual(clock.rttMs, 200)
    }

    // MARK: - the policy

    func testDriftIsJudgedAgainstTheFriendsClockNotTheirNumber() {
        // The friend's clock reads 3 s ahead, so their 10 s is this device's 13 s: the same
        // moment, not a three-second gap, and nothing at all should happen.
        XCTAssertEqual(
            TogetherSync.decide(local: 13_000, remote: 10_000, offsetMs: 3_000, bothPlaying: true, correcting: false, supportsRate: true),
            .none)
        // Ignoring the offset, that very gap would have ordered a seek.
        XCTAssertEqual(
            TogetherSync.decide(local: 13_000, remote: 10_000, offsetMs: 0, bothPlaying: true, correcting: false, supportsRate: true),
            .seek(10_000, notify: false))
    }

    func testASeekAimsAtTheFriendsPositionOnThisDevicesClock() {
        XCTAssertEqual(
            TogetherSync.decide(local: 18_000, remote: 10_000, offsetMs: -1_000, bothPlaying: true, correcting: false, supportsRate: true),
            .seek(9_000, notify: false))
        XCTAssertEqual(
            TogetherSync.decide(local: 30_000, remote: 10_000, offsetMs: -1_000, bothPlaying: true, correcting: false, supportsRate: true),
            .seek(9_000, notify: true))
    }

    func testTheSideThatIsBehindSpeedsUp() {
        XCTAssertEqual(
            TogetherSync.decide(local: 9_000, remote: 10_000, offsetMs: 0, bothPlaying: true, correcting: false, supportsRate: true),
            .rate(1.03))
        XCTAssertEqual(
            TogetherSync.decide(local: 11_000, remote: 10_000, offsetMs: 0, bothPlaying: true, correcting: false, supportsRate: true),
            .rate(0.97))
    }

    // MARK: - the session

    func testASessionAnswersAPingWithTheThreeTimestampsThatMakeAnOffset() async throws {
        let bench = try await Bench.live()
        bench.clock.value = 5_000
        try bench.deliver(.init(t: .ping, seq: 10, sentAt: 4_000))
        await bench.settle()
        let pong = try XCTUnwrap(bench.sent().last { $0.t == .pong })
        XCTAssertEqual(pong.pingSentAt, 4_000)
        XCTAssertEqual(pong.receivedAt, 5_000)
        XCTAssertEqual(pong.sentAt, 5_000)
    }

    func testAPongTeachesTheSessionHowFarApartTheClocksAre() async throws {
        let bench = try await Bench.live()
        // Ping left at 1000 here, reached them at 1600 by their clock, answered at once, back at
        // 1200 here: 100 ms each way and a friend's clock 500 ms ahead.
        bench.clock.value = 1_200
        try bench.deliver(.init(t: .pong, seq: 10, sentAt: 1_600, pingSentAt: 1_000, receivedAt: 1_600))
        await bench.settle()
        XCTAssertEqual(bench.manager.clockOffsetMs, 500)
    }

    func testEverySecondSaysWhereThisSideIs() async throws {
        let bench = try await Bench.live()
        bench.player.togetherSnapshot.positionMs = 12_345
        bench.clock.value = 7_000
        bench.manager.beat()
        await bench.settle()
        let state = try XCTUnwrap(bench.sent().last { $0.t == .state })
        XCTAssertEqual(state.positionMs, 12_345)
        XCTAssertEqual(state.playing, true)
        XCTAssertEqual(state.sentAt, 7_000)
        // Five beats to a ping — the cadence Android keeps. The room opened with one of its own,
        // so that the clocks have something to work from before the first beat.
        XCTAssertEqual(bench.pings(), 1)
        for _ in 0..<3 { bench.manager.beat() }
        await bench.settle()
        XCTAssertEqual(bench.pings(), 1)
        bench.manager.beat()
        await bench.settle()
        XCTAssertEqual(bench.pings(), 2)
    }

    /// A stall is said the moment it starts and the moment it ends, not on the next beat: the
    /// friend's wait is then a trip through the relay late, the same on both ends, and cancels.
    func testAStallIsReportedTheMomentItStartsAndTheMomentItEnds() async throws {
        let bench = try await Bench.live()
        bench.manager.beat()
        await bench.settle()
        let reports = bench.sent().filter { $0.t == .state }.count
        bench.player.togetherSnapshot.buffering = true
        bench.manager.reportStallIfChanged()
        await bench.settle()
        XCTAssertEqual(bench.sent().filter { $0.t == .state }.count, reports + 1)
        XCTAssertEqual(bench.sent().last { $0.t == .state }?.buffering, true)
        // Nothing changed: nothing more to say until the beat.
        bench.manager.reportStallIfChanged()
        await bench.settle()
        XCTAssertEqual(bench.sent().filter { $0.t == .state }.count, reports + 1)
        bench.player.togetherSnapshot.buffering = false
        bench.manager.reportStallIfChanged()
        await bench.settle()
        XCTAssertEqual(bench.sent().filter { $0.t == .state }.count, reports + 2)
        XCTAssertEqual(bench.sent().last { $0.t == .state }?.buffering, false)
    }

    func testTheFriendsReportIsCarriedForwardToNowBeforeItIsJudged() async throws {
        let bench = try await Bench.live()
        bench.player.togetherSnapshot.positionMs = 10_000
        bench.clock.value = 10_000
        // They were at 10 s two seconds ago and playing, so they are at 12 s now — this side is
        // two seconds behind and has to jump.
        try bench.deliver(.init(t: .state, seq: 10, positionMs: 10_000, playing: true, buffering: false, sentAt: 8_000))
        await bench.settle()
        XCTAssertTrue(bench.player.seeks.isEmpty, "a report is recorded, not acted on the instant it lands")
        bench.manager.beat(); bench.manager.beat()
        await bench.settle()
        XCTAssertEqual(bench.player.seeks, [12_000])
    }

    func testASmallGapIsNudgedAndThenReleased() async throws {
        let bench = try await Bench.live()
        bench.player.togetherSnapshot.positionMs = 10_800
        bench.clock.value = 10_000
        try bench.deliver(.init(t: .state, seq: 10, positionMs: 10_000, playing: true, buffering: false, sentAt: 10_000))
        await bench.settle()
        bench.manager.beat(); bench.manager.beat()
        await bench.settle()
        XCTAssertEqual(bench.player.rate, 0.97)
        // The nudge worked; normal speed comes back rather than the session staying slow for ever.
        bench.player.togetherSnapshot.positionMs = 10_100
        try bench.deliver(.init(t: .state, seq: 11, positionMs: 10_000, playing: true, buffering: false, sentAt: 10_000))
        await bench.settle()
        bench.manager.beat(); bench.manager.beat()
        await bench.settle()
        XCTAssertEqual(bench.player.rate, 1)
        XCTAssertTrue(bench.player.seeks.isEmpty)
    }

    func testAReportTooOldToTrustIsLeftAlone() async throws {
        let bench = try await Bench.live()
        bench.player.togetherSnapshot.positionMs = 10_000
        bench.clock.value = 10_000
        try bench.deliver(.init(t: .state, seq: 10, positionMs: 60_000, playing: true, buffering: false, sentAt: 10_000))
        await bench.settle()
        // Six seconds of silence later, where they said they were says nothing about where they are.
        bench.clock.value = 16_001
        bench.manager.beat(); bench.manager.beat()
        await bench.settle()
        XCTAssertTrue(bench.player.seeks.isEmpty)
    }

    // MARK: - what a friend's word costs

    /// A friend's pause used to be a seek to where they paused, however close this side already
    /// was — and on HLS a seek is a stall, the stall is reported, and the friend then waits for it.
    /// Every pause and every play cost the room a hiccup.
    func testAFriendsPauseWithinHalfASecondIsAppliedWithoutAJump() async throws {
        let bench = try await Bench.live()
        bench.player.togetherSnapshot.positionMs = 10_000
        try bench.deliver(.init(t: .pause, seq: 10, positionMs: 10_300))
        await bench.settle()
        XCTAssertEqual(bench.player.pauses, 1)
        XCTAssertTrue(bench.player.seeks.isEmpty, "триста миллисекунд — не повод перематывать")
        // Half a second and more is a jump worth making, as it is on Android.
        try bench.deliver(.init(t: .play, seq: 11, positionMs: 12_000))
        await bench.settle()
        XCTAssertEqual(bench.player.seeks, [12_000])
        XCTAssertTrue(bench.player.togetherSnapshot.playing)
    }

    /// Opening an episode starts it on both phones, so an episode the friend opened plays here too
    /// rather than sitting on its first frame while their reports say they are a minute in.
    func testAnEpisodeTheFriendOpenedPlaysHereToo() async throws {
        let bench = try await Bench.live()
        try bench.deliver(.init(t: .episode, seq: 10, episode: 2))
        await bench.settle()
        XCTAssertEqual(bench.player.opened.last?.episode, 2)
        XCTAssertTrue(bench.player.togetherSnapshot.playing)
    }

    /// A friend who pressed «выйти» is gone, and the room is over here and now — not half a
    /// minute of «Восстанавливаем связь» and then «Связь прервалась».
    func testAFriendsGoodbyeEndsTheRoomHereAndNow() async throws {
        let bench = try await Bench.live()
        try bench.deliver(.init(t: .bye, seq: 10))
        await bench.settle()
        XCTAssertEqual(bench.manager.phase, .ended)
        XCTAssertNil(bench.manager.invitation)
        // Whatever the relay hands over after that belongs to a room that has ended.
        bench.transport.continuation.yield(.peerLeft)
        await bench.settle()
        XCTAssertEqual(bench.manager.phase, .ended)
    }

    func testAPausedFriendIsNotBehind() async throws {
        let bench = try await Bench.live()
        bench.player.togetherSnapshot.positionMs = 10_000
        bench.clock.value = 10_000
        try bench.deliver(.init(t: .state, seq: 10, positionMs: 600_000, playing: false, buffering: false, sentAt: 10_000))
        await bench.settle()
        bench.manager.beat(); bench.manager.beat()
        await bench.settle()
        XCTAssertTrue(bench.player.seeks.isEmpty)
        XCTAssertEqual(bench.player.rate, 1)
    }

    /// The greeting is stale by however long the invitation sat on screen. Opening at it meant a
    /// visible jump, with a sentence next to it, the moment the session caught up.
    func testAGuestLandsWhereTheFriendIsNowNotWhereTheyWereWhenTheySaidHello() async throws {
        let bench = try await Bench.joining()
        bench.clock.value = 1_000
        try bench.deliver(.init(t: .hello, seq: 1, name: "Хозяин", animeId: 7, episode: 1, positionMs: 5_000, playing: true))
        await bench.settle()
        XCTAssertEqual(bench.manager.joining?.episode?.positionMs, 5_000)
        // Twelve seconds of reading the invitation, with nothing said since.
        bench.clock.value = 13_000
        bench.manager.acceptJoin()
        bench.manager.attach(bench.player)
        await bench.settle()
        XCTAssertEqual(bench.player.seeks.last, 17_000)
    }

    func testAGuestLandsWhereTheFriendsLastReportPutsThemNow() async throws {
        let bench = try await Bench.joining()
        bench.clock.value = 1_000
        try bench.deliver(.init(t: .hello, seq: 1, name: "Хозяин", animeId: 7, episode: 1, positionMs: 5_000, playing: true))
        // A report from the join screen's own minute: they were at 30 s two seconds ago.
        bench.clock.value = 11_000
        try bench.deliver(.init(t: .state, seq: 2, positionMs: 30_000, playing: true, buffering: false, sentAt: 11_000))
        await bench.settle()
        bench.clock.value = 13_000
        bench.manager.acceptJoin()
        bench.manager.attach(bench.player)
        await bench.settle()
        XCTAssertEqual(bench.player.seeks.last, 32_000)
    }

    // MARK: - bench

    /// A live room with a fake transport, a fake player and a clock the test winds by hand.
    ///
    /// The session is the guest of the room, which is the side that follows — the side a drift
    /// correction actually happens on.
    @MainActor final class Bench {
        final class Clock: @unchecked Sendable { var value: Int64 = 0 }
        let clock: Clock
        let transport: TogetherManagerTests.Transport
        let player = TogetherManagerTests.Playback()
        let manager: TogetherManager
        let invitation: TogetherInvitation

        private init() throws {
            let clock = Clock()
            let transport = TogetherManagerTests.Transport()
            self.clock = clock
            self.transport = transport
            manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Гость",
                                      transportFactory: { _, _ in transport },
                                      now: { clock.value })
            invitation = try TogetherInvitation(roomID: "AAAAAAAAAAA", key: Data(repeating: 3, count: 16))
        }

        static func live() async throws -> Bench {
            let bench = try Bench()
            bench.manager.attach(bench.player)
            await bench.manager.join(bench.invitation)
            await bench.settle()
            return bench
        }

        /// The same room from the join screen: the link has been followed, nothing has been agreed
        /// to and no player is attached yet.
        static func joining() async throws -> Bench {
            let bench = try Bench()
            await bench.manager.join(bench.invitation)
            await bench.settle()
            return bench
        }

        func deliver(_ message: TogetherMessage) throws {
            try transport.deliver(message, link: invitation, side: .host)
        }

        func pings() -> Int { sent().filter { $0.t == .ping }.count }

        func sent() -> [TogetherMessage] {
            transport.outgoing.compactMap { try? TogetherCodec.decode($0, invitation: invitation, from: .guest) }
        }

        func settle() async { for _ in 0..<30 { await Task.yield() } }
    }
}

/// Waiting for a friend whose player is still loading.
///
/// Both sides always reported `buffering` and neither ever read it: one phone stalled on a
/// segment, the other played on, the gap passed ten seconds, and the rule said seek — so the
/// stalled phone was dragged forward, stalled again on the segment it did not have, and was
/// dragged again. «Перемотал на» every few seconds for as long as the network was slow.
@MainActor final class TogetherBufferingHoldTests: XCTestCase {
    /// A room this side made, with the player attached the way one opens after somebody joins.
    /// The invitation is the room's own: a host seals under the key it generated, and a frame
    /// sealed under any other is dropped without a word — as it should be.
    private func room() async throws -> (TogetherManagerTests.Transport, TogetherManagerTests.Playback, TogetherManager, TogetherInvitation) {
        let transport = TogetherManagerTests.Transport()
        let player = TogetherManagerTests.Playback()
        let manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Host", transportFactory: { _, _ in transport })
        await manager.create()
        manager.attach(player)
        try await Task.sleep(for: .milliseconds(20))
        return (transport, player, manager, try XCTUnwrap(manager.invitation))
    }
    func testAPlayerThatIsLoadingHoldsTheOtherOne() async throws {
        let (transport, player, manager, link) = try await room()
        try transport.deliver(TogetherMessage(t: .hello, seq: 1, name: "Guest", animeId: 7, episode: 1, positionMs: 0, playing: true),
                              link: link, side: .guest)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(manager.peerName, "Guest")
        XCTAssertTrue(player.togetherSnapshot.playing)
        try transport.deliver(TogetherMessage(t: .state, seq: 2, positionMs: 1000, playing: true, buffering: true, sentAt: 1),
                              link: link, side: .guest)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(player.pauses, 1, "пока друг грузится, эта сторона ждёт")
        try transport.deliver(TogetherMessage(t: .state, seq: 3, positionMs: 1200, playing: true, buffering: false, sentAt: 2),
                              link: link, side: .guest)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertTrue(player.togetherSnapshot.playing, "и продолжает, когда друг готов")
        await manager.leave()
    }
    /// A hold is a wait for a friend who wants to play, and the friend's own word ends it. Their
    /// pause used to be applied and then undone: the pause paused this side, their next report —
    /// paused, so no longer «loading» — let the hold go, and letting go meant play.
    func testAFriendWhoPausesWhileThisSideWaitsForThemStaysPaused() async throws {
        let (transport, player, manager, link) = try await room()
        try transport.deliver(TogetherMessage(t: .hello, seq: 1, name: "Guest", animeId: 7, episode: 1, positionMs: 1000, playing: true),
                              link: link, side: .guest)
        try transport.deliver(TogetherMessage(t: .state, seq: 2, positionMs: 1000, playing: true, buffering: true, sentAt: 1),
                              link: link, side: .guest)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(player.pauses, 1)
        try transport.deliver(TogetherMessage(t: .pause, seq: 3, positionMs: 1000), link: link, side: .guest)
        try transport.deliver(TogetherMessage(t: .state, seq: 4, positionMs: 1000, playing: false, buffering: false, sentAt: 2),
                              link: link, side: .guest)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(player.plays, 0, "после их паузы никто не жмёт play")
        XCTAssertFalse(player.togetherSnapshot.playing)
        await manager.leave()
    }

    /// Two phones stalling at once each stop for the other, and a friend who stopped for this
    /// side reports «paused» without ever sending a pause. The first report to say «not loading»
    /// has to start this side again, or both sit paused for good.
    func testTwoPicturesThatStoppedForEachOtherBothStartAgain() async throws {
        let (transport, player, manager, link) = try await room()
        try transport.deliver(TogetherMessage(t: .hello, seq: 1, name: "Guest", animeId: 7, episode: 1, positionMs: 1000, playing: true),
                              link: link, side: .guest)
        try transport.deliver(TogetherMessage(t: .state, seq: 2, positionMs: 1000, playing: true, buffering: true, sentAt: 1),
                              link: link, side: .guest)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(player.pauses, 1)
        try transport.deliver(TogetherMessage(t: .state, seq: 3, positionMs: 1000, playing: false, buffering: false, sentAt: 2),
                              link: link, side: .guest)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(player.plays, 1)
        await manager.leave()
    }

    /// This viewer's own pause during a wait is a pause, not a wait that ends in play.
    func testThisViewerPausingDuringAWaitIsNotOverruledWhenTheFriendIsReady() async throws {
        let (transport, player, manager, link) = try await room()
        try transport.deliver(TogetherMessage(t: .hello, seq: 1, name: "Guest", animeId: 7, episode: 1, positionMs: 1000, playing: true),
                              link: link, side: .guest)
        try transport.deliver(TogetherMessage(t: .state, seq: 2, positionMs: 1000, playing: true, buffering: true, sentAt: 1),
                              link: link, side: .guest)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(player.pauses, 1)
        manager.sendPause()
        try transport.deliver(TogetherMessage(t: .state, seq: 3, positionMs: 1500, playing: true, buffering: false, sentAt: 2),
                              link: link, side: .guest)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(player.plays, 0)
        await manager.leave()
    }

    func testNothingIsCorrectedWhileTheFriendLoads() async throws {
        let (transport, player, manager, link) = try await room()
        try transport.deliver(TogetherMessage(t: .hello, seq: 1, name: "Guest", animeId: 7, episode: 1, positionMs: 0, playing: true),
                              link: link, side: .guest)
        try transport.deliver(TogetherMessage(t: .state, seq: 2, positionMs: 0, playing: true, buffering: true, sentAt: 1),
                              link: link, side: .guest)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(manager.peerName, "Guest")
        player.seeks.removeAll()
        // A gap that would normally be a seek and a sentence about it.
        manager.correct()
        XCTAssertTrue(player.seeks.isEmpty, "никаких перемоток, пока друг подгружает")
        await manager.leave()
    }
}
