import XCTest
@testable import Kaeru

/// What happens to a room when the network goes away for a minute: the waits, what is kept to be
/// said afterwards, which of the relay's answers are final, and the seat a friend can walk back into.
@MainActor final class TogetherReconnectTests: XCTestCase {

    func testTheScheduleIsOneTwoFourEightAndThenWhatIsLeftOfTheHalfMinute() {
        var backoff = TogetherRelayBackoff()
        var waits: [Int64] = []
        while let wait = backoff.next() { waits.append(wait) }
        XCTAssertEqual(waits, [1_000, 2_000, 4_000, 8_000, 15_000])
        // Exactly the budget, not a millisecond more, and nothing after it.
        XCTAssertEqual(waits.reduce(0, +), TogetherRelayBackoff.budgetMs)
        XCTAssertNil(backoff.next())
    }

    func testASocketThatCameBackGetsTheWholeHalfMinuteAgain() {
        var backoff = TogetherRelayBackoff()
        _ = backoff.next(); _ = backoff.next()
        XCTAssertEqual(backoff.waitedMs, 3_000)
        // A long evening would run out of the budget if it were spent once for the whole session.
        backoff.reset()
        XCTAssertEqual(backoff.waitedMs, 0)
        XCTAssertEqual(backoff.next(), 1_000)
    }

    func testWhatCouldNotBeSentIsKeptNewestFirstToSurvive() {
        let buffer = TogetherSendBuffer()
        for value in 0..<(TogetherSendBuffer.maximum + 3) { buffer.append(Data([UInt8(value % 251)])) }
        XCTAssertEqual(buffer.frames.count, TogetherSendBuffer.maximum)
        // The last action is the one that counts, so it is the oldest that go.
        XCTAssertEqual(buffer.frames.first, Data([3]))
        XCTAssertEqual(buffer.frames.last, Data([UInt8((TogetherSendBuffer.maximum + 2) % 251)]))
    }

    /// A socket that died the moment it opened used to take the whole backlog with it: everything
    /// was handed over at once and forgotten, whether or not the socket took it.
    func testAFlushTheSocketWouldNotTakeKeepsTheRestInOrderForTheNextOne() async {
        let buffer = TogetherSendBuffer()
        for value in 1...3 { buffer.append(Data([UInt8(value)])) }
        var written: [UInt8] = []
        let emptied = await buffer.flush { frame in
            if frame == Data([2]) { throw TogetherError.disconnected }
            written.append(frame[0])
        }
        XCTAssertFalse(emptied)
        XCTAssertEqual(written, [1])
        XCTAssertEqual(buffer.frames, [Data([2]), Data([3])], "the frame that failed and everything after it, in order")
        let again = await buffer.flush { frame in written.append(frame[0]) }
        XCTAssertTrue(again)
        XCTAssertEqual(written, [1, 2, 3])
        XCTAssertTrue(buffer.frames.isEmpty)
    }

    func testWhatIsSaidDuringAFlushQueuesBehindIt() async {
        let buffer = TogetherSendBuffer()
        buffer.append(Data([1])); buffer.append(Data([2]))
        var written: [UInt8] = []
        let emptied = await buffer.flush { frame in
            written.append(frame[0])
            // A pause pressed while the backlog is being written.
            if frame == Data([1]) { buffer.append(Data([3])) }
        }
        XCTAssertTrue(emptied)
        XCTAssertEqual(written, [1, 2, 3])
    }

    // MARK: - the watchdog

    /// Android gets a socket watchdog for free from OkHttp's `pingInterval(20 s)`; this side has to
    /// keep one by hand, and it has to be the same twenty seconds.
    func testTheSocketPingIsAndroidsTwentySeconds() {
        XCTAssertEqual(TogetherTiming.socketPingSeconds, 20)
    }

    func testTheWatchdogPingsOnItsIntervalAndCallsASocketDeadWhenAPongDoesNotComeBack() async {
        let watchdog = TogetherSocketWatchdog(intervalSeconds: 0.005)
        var pings = 0
        var dead = 0
        await watchdog.run(ping: { pings += 1; return pings < 3 }, dead: { dead += 1 })
        XCTAssertEqual(pings, 3, "two pongs came back; the third did not")
        XCTAssertEqual(dead, 1)
    }

    func testACancelledWatchdogCallsNothingDead() async {
        let watchdog = TogetherSocketWatchdog(intervalSeconds: 10)
        var dead = 0
        var pings = 0
        let running = Task { await watchdog.run(ping: { pings += 1; return false }, dead: { dead += 1 }) }
        try? await Task.sleep(for: .milliseconds(20))
        running.cancel()
        await running.value
        XCTAssertEqual(pings, 0)
        XCTAssertEqual(dead, 0)
    }

    // MARK: - the polite close

    func testTheGoodbyeGraceIsAndroidsSecond() {
        XCTAssertEqual(TogetherTiming.goodbyeGraceSeconds, 1)
    }

    func testWaitingUntilSomethingIsTrueStopsEarlyAndGivesUpOnTime() async {
        var polls = 0
        let early = await togetherWaitUntil({ polls += 1; return polls >= 3 }, seconds: 5, stepMs: 1)
        XCTAssertTrue(early)
        XCTAssertEqual(polls, 3)
        let started = Date()
        let late = await togetherWaitUntil({ false }, seconds: 0.05, stepMs: 5)
        XCTAssertFalse(late)
        XCTAssertGreaterThanOrEqual(Date().timeIntervalSince(started), 0.05)
    }

    func testThreeOfTheRelaysCloseCodesAreAnswersRatherThanAccidents() {
        XCTAssertEqual(TogetherRelayClose.refusal(for: 4409), .roomFull)
        XCTAssertEqual(TogetherRelayClose.refusal(for: 4408), .expired)
        XCTAssertEqual(TogetherRelayClose.refusal(for: 4413), .frameTooLarge)
        // An ordinary close is an accident, and an accident is worth another attempt.
        XCTAssertNil(TogetherRelayClose.refusal(for: 1000))
        XCTAssertNil(TogetherRelayClose.refusal(for: 1006))
        XCTAssertTrue(TogetherRelayClose.refuses(.roomFull))
        XCTAssertFalse(TogetherRelayClose.refuses(.disconnected))
    }

    func testADroppedSocketIsSaidOutLoudAndThenTakenBack() async throws {
        let bench = try await TogetherSyncTests.Bench.live()
        bench.transport.continuation.yield(.reconnecting)
        await bench.settle()
        XCTAssertEqual(bench.manager.phase, .reconnecting)
        bench.transport.continuation.yield(.reconnected)
        await bench.settle()
        XCTAssertEqual(bench.manager.phase, .live)
        // The friend's room carried on meanwhile and has no other way of learning where this side
        // got to, so the greeting goes out again — and a ping, because the path may be a new one.
        XCTAssertEqual(bench.sent().filter { $0.t == .hello }.count, 2)
        XCTAssertEqual(bench.pings(), 2)
    }

    func testTheSeatIsHeldForHalfAMinuteAndAReturningFriendMayCountFromOneAgain() async throws {
        let bench = try await TogetherSyncTests.Bench.live()
        bench.clock.value = 1_000
        try bench.deliver(.init(t: .seek, seq: 40, positionMs: 4_000))
        await bench.settle()
        XCTAssertEqual(bench.player.seeks, [4_000])

        bench.transport.continuation.yield(.peerLeft)
        await bench.settle()
        XCTAssertEqual(bench.manager.phase, .reconnecting, "the room is not over — the seat is being held")

        // Anything but a greeting stays gated by the old mark, or the window would be thirty
        // seconds in which a captured frame plays again.
        try bench.deliver(.init(t: .seek, seq: 2, positionMs: 9_000))
        await bench.settle()
        XCTAssertEqual(bench.player.seeks, [4_000])

        // The friend walks back in, counting from one as a fresh session does.
        try bench.deliver(.init(t: .hello, seq: 1, name: "Хозяин", animeId: 7, episode: 1, positionMs: 4_000, playing: false))
        await bench.settle()
        XCTAssertEqual(bench.manager.phase, .live)
        XCTAssertEqual(bench.manager.peerName, "Хозяин")
    }

    /// The relay sees every frame and decides when a socket drops. It used to be able to hand a
    /// kept greeting into the window that follows and then every frame after it, in order — an
    /// old seek, an old line, an old clip, each taken for the friend saying it now.
    func testAFrameTheRelayKeptIsNotPlayedAgainAfterAReconnect() async throws {
        let bench = try await TogetherSyncTests.Bench.live()
        try bench.deliver(.init(t: .hello, seq: 1, name: "Хозяин", animeId: 7, episode: 1, positionMs: 1_000, playing: true, epoch: 5))
        try bench.deliver(.init(t: .seek, seq: 40, positionMs: 4_000))
        await bench.settle()
        XCTAssertEqual(bench.player.seeks, [4_000])

        bench.transport.continuation.yield(.peerLeft)
        await bench.settle()
        XCTAssertEqual(bench.manager.phase, .reconnecting)

        // The greeting again, and the seek after it, exactly as they were first carried.
        try bench.deliver(.init(t: .hello, seq: 1, name: "Хозяин", animeId: 7, episode: 1, positionMs: 1_000, playing: true, epoch: 5))
        try bench.deliver(.init(t: .seek, seq: 40, positionMs: 4_000))
        await bench.settle()
        XCTAssertEqual(bench.manager.phase, .reconnecting, "a kept greeting is not the friend walking back in")
        XCTAssertEqual(bench.player.seeks, [4_000])

        // The friend's session started over: a new epoch, and the seat is theirs again.
        try bench.deliver(.init(t: .hello, seq: 1, name: "Хозяин", animeId: 7, episode: 1, positionMs: 4_000, playing: true, epoch: 6))
        await bench.settle()
        XCTAssertEqual(bench.manager.phase, .live)
        // Still nothing from before the drop plays again — and what they say next does.
        try bench.deliver(.init(t: .seek, seq: 40, positionMs: 4_000))
        try bench.deliver(.init(t: .seek, seq: 41, positionMs: 9_000))
        await bench.settle()
        XCTAssertEqual(bench.player.seeks, [4_000, 9_000])
    }

    func testNobodyWalksBackInAndTheEveningIsCalledOver() async throws {
        let bench = try await TogetherSyncTests.Bench.live()
        bench.clock.value = 1_000
        bench.transport.continuation.yield(.peerLeft)
        await bench.settle()
        XCTAssertEqual(bench.manager.phase, .reconnecting)
        bench.manager.beat()
        XCTAssertEqual(bench.manager.phase, .reconnecting, "half a minute, not one beat")
        bench.clock.value = 1_000 + TogetherTiming.rejoinWindowMs
        bench.manager.beat()
        await bench.settle()
        XCTAssertEqual(bench.manager.phase, .failed)
        XCTAssertEqual(bench.manager.error, .disconnected)
    }
}

/// A room with one person in it is silent, and silence is not a failure.
///
/// The relay socket used to carry a ten-second request deadline. Nothing arrives in a room nobody
/// has joined, so the task failed on that silence, the session said «Восстанавливаем связь»,
/// dialled again and failed ten seconds later — for as long as somebody was copying the link.
final class TogetherSocketDeadlineTests: XCTestCase {
    func testTheSocketOutlivesASilentRoom() {
        XCTAssertGreaterThanOrEqual(TogetherTiming.socketLifetimeSeconds, 3600)
        // The first dial still has to answer for itself, and quickly.
        XCTAssertLessThanOrEqual(TogetherTiming.dialSeconds, 15)
        XCTAssertLessThan(TogetherTiming.dialSeconds, TogetherTiming.socketLifetimeSeconds)
    }
}
