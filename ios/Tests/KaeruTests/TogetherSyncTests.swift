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
