import XCTest
@testable import Kaeru

/// The stream's iterator, in a box of its own so it can be advanced in place.
///
/// It used to be copied out of a stored property, advanced, and put back — which across an
/// `await` is a race, and the compiler says so. What makes one consumer safe is the manager's
/// receive loop: one call at a time, and nothing in this stub used to say so. At file scope
/// rather than nested, because a type inside a `@MainActor` test case is `@MainActor` too, and an
/// iterator cannot be advanced through an actor-isolated property at all.
private final class TransportEvents: @unchecked Sendable {
    private var iterator: AsyncThrowingStream<TogetherTransportEvent, Error>.Iterator
    init(_ stream: AsyncThrowingStream<TogetherTransportEvent, Error>) { iterator = stream.makeAsyncIterator() }
    func next() async throws -> TogetherTransportEvent? { try await iterator.next() }
}

@MainActor final class TogetherManagerTests: XCTestCase {
    final class Playback: TogetherPlayback {
        var togetherSnapshot = TogetherPlaybackSnapshot(animeID: 7, episode: 1, positionMs: 1000, playing: true, ready: true)
        var togetherSupportsRate = true
        var seeks: [Int64] = []
        var pauses = 0
        var plays = 0
        var opened: [TogetherEpisode] = []
        var rate: Float = 1
        func togetherPlay() { plays += 1; togetherSnapshot.playing = true }
        func togetherPause() { pauses += 1; togetherSnapshot.playing = false }
        func togetherSeek(toMilliseconds position: Int64) { seeks.append(position); togetherSnapshot.positionMs = position }
        func togetherSetRate(_ factor: Float) { rate = factor }
        func togetherDuck(_ on: Bool) {}
        func togetherOpen(_ episode: TogetherEpisode) async throws {
            opened.append(episode)
            // Opened paused, the way `PlaybackTogetherAdapter` opens one: whether it then plays is the session's word.
            togetherSnapshot = TogetherPlaybackSnapshot(animeID: episode.animeID, episode: episode.episode, translationID: episode.translationID, positionMs: episode.positionMs, playing: false, ready: true)
        }
    }
    final class Transport: TogetherTransport {
        var outgoing: [Data] = []
        var continuation: AsyncThrowingStream<TogetherTransportEvent, Error>.Continuation!
        var stream: AsyncThrowingStream<TogetherTransportEvent, Error>!
        private var events: TransportEvents!
        /// A relay that is not there — an empty address in the build, no network, a room refused.
        var refuse = false
        init() { stream = AsyncThrowingStream { continuation = $0 }; events = TransportEvents(stream) }
        func connect(_ invitation: TogetherInvitation, asHost: Bool) async throws {
            if refuse { throw TogetherError.notConfigured }
        }
        func receive() async throws -> TogetherTransportEvent {
            guard let event = try await events.next() else { throw TogetherError.disconnected }
            return event
        }
        var closes = 0
        var goodbyes = 0
        /// What happened to this transport, in order: every frame written, the polite close, the cut.
        var order: [String] = []
        func send(_ frame: Data) async throws { outgoing.append(frame); order.append("send") }
        func close() { closes += 1; order.append("close"); continuation.finish() }
        /// A real socket takes a moment to see its close frame answered; this one takes 60 ms.
        func closeAfterGoodbye() async {
            goodbyes += 1; order.append("goodbye")
            try? await Task.sleep(for: .milliseconds(60))
            close()
        }
        func deliver(_ message: TogetherMessage, link: TogetherInvitation, side: TogetherSide) throws {
            continuation.yield(.frame(try TogetherCodec.encode(message, invitation: link, from: side)))
        }
    }
    func settle() async { for _ in 0..<30 { await Task.yield() } }
    func testHostHandshakeRemotePauseNoEchoAndReplay() async throws {
        let transport = Transport(); let player = Playback()
        let manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Host", transportFactory: { _, _ in transport })
        manager.attach(player)
        await manager.create(); await settle()
        let link = try XCTUnwrap(manager.invitation)
        try transport.deliver(TogetherMessage(t: .hello, seq: 1, name: "Guest", animeId: 0, episode: 0, positionMs: 0, playing: false), link: link, side: .guest)
        await settle()
        XCTAssertEqual(manager.phase, .live)
        let before = transport.outgoing.count
        try transport.deliver(TogetherMessage(t: .pause, seq: 100, positionMs: 5000), link: link, side: .guest)
        await settle()
        XCTAssertEqual(player.pauses, 1); XCTAssertEqual(player.seeks, [5000])
        XCTAssertEqual(transport.outgoing.count, before)
        try transport.deliver(TogetherMessage(t: .pause, seq: 99, positionMs: 0), link: link, side: .guest)
        await settle(); XCTAssertEqual(player.pauses, 1)
        await manager.leave(); XCTAssertEqual(manager.phase, .ended); XCTAssertEqual(player.rate, 1)
    }
    /// `send` completing means the socket task took the goodbye, not that the wire did, and cutting
    /// the session straight after could take the frame with it — the friend then saw a bare drop
    /// and waited half a minute to be told the connection was lost. Android waits a second in
    /// `RelayTransport.close()`; this side now does the same, and the screen does not wait with it.
    func testLeavingWaitsForTheGoodbyeToLeaveButTheScreenDoesNot() async throws {
        let transport = Transport(); let player = Playback()
        let manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Host", transportFactory: { _, _ in transport })
        manager.attach(player); await manager.create(); await settle()
        let link = try XCTUnwrap(manager.invitation)
        let leaving = Task { await manager.leave() }
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(manager.phase, .ended, "the screen is told at once")
        XCTAssertEqual(transport.goodbyes, 1)
        XCTAssertEqual(transport.closes, 0, "the socket is still finishing its handshake")
        await leaving.value
        XCTAssertEqual(transport.closes, 1)
        let said = transport.outgoing.compactMap { try? TogetherCodec.decode($0, invitation: link, from: .host) }
        XCTAssertEqual(said.last?.t, .bye)
        XCTAssertLessThan(try XCTUnwrap(transport.order.lastIndex(of: "send")), try XCTUnwrap(transport.order.firstIndex(of: "goodbye")),
                          "the goodbye is written before the close that waits for it")
    }
    func testLeaveFencesLateFrames() async throws {
        let transport = Transport(); let player = Playback()
        let manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Host", transportFactory: { _, _ in transport })
        manager.attach(player); await manager.create(); await settle()
        let link = try XCTUnwrap(manager.invitation)
        await manager.leave()
        try transport.deliver(TogetherMessage(t: .pause, seq: 100, positionMs: 5000), link: link, side: .guest)
        await settle(); XCTAssertEqual(player.pauses, 0); XCTAssertEqual(manager.phase, .ended)
    }
    /// «Завершить комнату» used to leave the room on screen: the phase said «Завершено» while the
    /// link, the share button and the button itself stayed exactly where they were.
    func testLeavingTakesTheRoomWithIt() async throws {
        let transport = Transport()
        let manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Host", transportFactory: { _, _ in transport })
        await manager.create(); await settle()
        XCTAssertNotNil(manager.invitation)
        await manager.leave(); await settle()
        XCTAssertEqual(manager.phase, .ended)
        XCTAssertNil(manager.invitation)
    }
    /// And a room that never opened is not a room to share either.
    func testAFailedRoomIsNotLeftOnScreen() async throws {
        let transport = Transport(); transport.refuse = true
        let manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Host", transportFactory: { _, _ in transport })
        await manager.create(); await settle()
        XCTAssertEqual(manager.phase, .failed)
        XCTAssertNil(manager.invitation)
    }
    func testGuestRoutesThenAttachesReadyPlayback() async throws {
        let transport = Transport(); let player = Playback()
        let manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Guest", transportFactory: { _, _ in transport })
        var opened: TogetherEpisode?
        manager.onOpenPlayback = { opened = $0 }
        let link = try TogetherInvitation(roomID: "AAAAAAAAAAA", key: Data(repeating: 0, count: 16))
        await manager.join(link); await settle()
        try transport.deliver(TogetherMessage(t: .hello, seq: 1, name: "Host", animeId: 7, episode: 3, translationId: 4, positionMs: 5000, playing: false), link: link, side: .host)
        await settle(); XCTAssertEqual(opened?.episode, 3)
        // What the join screen is for: the greeting is an offer until somebody takes it, and the
        // player is opened by the screen that took it.
        XCTAssertEqual(manager.joining?.episode?.episode, 3)
        manager.acceptJoin()
        manager.attach(player); await settle()
        XCTAssertEqual(manager.phase, .live); XCTAssertEqual(player.togetherSnapshot.episode, 3)
        XCTAssertFalse(player.togetherSnapshot.playing)
        await manager.leave()
    }
}

/// A clip leaving one phone and arriving on another, through the same codec both of them use.
@MainActor final class TogetherVoiceWireTests: XCTestCase {
    private func settle() async { for _ in 0..<30 { await Task.yield() } }

    /// Half a minute of speech is about ninety kilobytes, which is three frames of the protocol's
    /// thirty-two. Cut here exactly as `TogetherSession.sendVoice` cuts it, so the far side
    /// reassembles without knowing which phone recorded it.
    func testAClipIsCutIntoFramesTheOtherSideReassembles() async throws {
        let transport = TogetherManagerTests.Transport()
        let manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Host",
                                      transportFactory: { _, _ in transport })
        await manager.create(); await settle()
        let link = try XCTUnwrap(manager.invitation)
        let spoken = Data((0..<90_000).map { UInt8($0 % 251) })
        manager.send(voice: RecordedClip(data: spoken, durationMs: 29_500))
        await settle()

        var assembly = TogetherVoiceAssembly()
        var clip: TogetherVoiceClip?
        var frames = 0
        for frame in transport.outgoing {
            let message = try TogetherCodec.decode(frame, invitation: link, from: .host)
            guard message.t == .voice else { continue }
            frames += 1
            if let finished = try assembly.append(message, now: 0) { clip = finished }
        }
        XCTAssertEqual(frames, 3)
        XCTAssertEqual(clip?.data, spoken)
        XCTAssertEqual(clip?.durationMs, 29_500)
    }

    /// The corner shows what this viewer said as they say it, rather than waiting for an echo the
    /// network may never bring back.
    func testMyOwnClipIsInTheCornerStraightAwayAndDoesNotPlayItselfBack() async throws {
        let transport = TogetherManagerTests.Transport()
        let manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Host",
                                      transportFactory: { _, _ in transport })
        await manager.create(); await settle()
        manager.send(voice: RecordedClip(data: Data([1, 2, 3, 4]), durationMs: 900))
        XCTAssertEqual(manager.conversation.history.last?.clip?.durationMs, 900)
        XCTAssertTrue(manager.conversation.history.last?.mine == true)
        XCTAssertNil(manager.conversation.playing)
    }

    /// A caller with a bug rather than somebody with a lot to say: dropped before it is cut.
    func testNothingIsSentForAnEmptyOrImpossibleClip() async throws {
        let transport = TogetherManagerTests.Transport()
        let manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Host",
                                      transportFactory: { _, _ in transport })
        await manager.create(); await settle()
        let before = transport.outgoing.count
        manager.send(voice: RecordedClip(data: Data(), durationMs: 1_000))
        manager.send(voice: RecordedClip(data: Data(count: 300_000), durationMs: 1_000))
        await settle()
        XCTAssertEqual(transport.outgoing.count, before)
        XCTAssertTrue(manager.conversation.history.isEmpty)
    }
}

/// An invitation that arrives while this phone is already watching something.
///
/// The greeting used to go straight to the player that happened to be open, and the join screen —
/// which is what the viewer is looking at — was never told what the room turned out to be. It said
/// «Подключаемся…» for as long as they cared to wait.
@MainActor final class TogetherJoinScreenTests: XCTestCase {
    func testAGreetingFillsTheJoinScreenEvenWithAPlayerOpen() async throws {
        let transport = TogetherManagerTests.Transport()
        let player = TogetherManagerTests.Playback()
        let manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Guest", transportFactory: { _, _ in transport })
        manager.attach(player)
        let link = try TogetherInvitation(roomID: "AAAAAAAAAAA", key: Data(repeating: 0, count: 16))
        await manager.join(link)
        try await Task.sleep(for: .milliseconds(20))
        try transport.deliver(TogetherMessage(t: .hello, seq: 1, name: "Host", animeId: 7, episode: 3,
                                              translationId: 4, positionMs: 5000, playing: true),
                              link: link, side: .host)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(manager.joining?.episode?.episode, 3)
        XCTAssertEqual(manager.joining?.episode?.animeID, 7)
        // Nothing has been agreed to yet, so the player is left exactly where it was.
        XCTAssertEqual(player.togetherSnapshot.episode, 1)
        manager.acceptJoin()
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertNil(manager.joining)
        XCTAssertEqual(player.togetherSnapshot.episode, 3)
        await manager.leave()
    }

    /// A pause, a jump or another episode from the host used to reach the player behind the join
    /// screen — somebody else's video, paused and scrubbed by a friend nobody had said yes to.
    /// Android gates every control on the session being live; this is the same rule here.
    func testTheFriendsCommandsWaitOnTheJoinScreenAndAreAppliedAsOneStateOnYes() async throws {
        let transport = TogetherManagerTests.Transport()
        let player = TogetherManagerTests.Playback()
        let manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Guest", transportFactory: { _, _ in transport })
        manager.attach(player)
        let link = try TogetherInvitation(roomID: "AAAAAAAAAAA", key: Data(repeating: 0, count: 16))
        await manager.join(link)
        try await Task.sleep(for: .milliseconds(20))
        try transport.deliver(TogetherMessage(t: .hello, seq: 1, name: "Host", animeId: 7, episode: 3, positionMs: 5_000, playing: true),
                              link: link, side: .host)
        try transport.deliver(TogetherMessage(t: .pause, seq: 2, positionMs: 6_000), link: link, side: .host)
        try transport.deliver(TogetherMessage(t: .seek, seq: 3, positionMs: 9_000), link: link, side: .host)
        try transport.deliver(TogetherMessage(t: .state, seq: 4, positionMs: 9_000, playing: false, buffering: false, sentAt: 1), link: link, side: .host)
        try await Task.sleep(for: .milliseconds(20))
        // Nothing has been agreed to, so the player is exactly where it was.
        XCTAssertEqual(player.pauses, 0)
        XCTAssertEqual(player.seeks, [])
        XCTAssertEqual(player.togetherSnapshot.episode, 1)
        XCTAssertTrue(player.togetherSnapshot.playing)
        manager.acceptJoin()
        try await Task.sleep(for: .milliseconds(20))
        // One state, not three actions: the friend's episode, paused, at the place they jumped to.
        XCTAssertEqual(player.opened.map(\.episode), [3])
        XCTAssertEqual(player.opened.last?.positionMs, 9_000)
        XCTAssertFalse(player.togetherSnapshot.playing)
        XCTAssertEqual(player.seeks, [], "the position is where the episode is opened, not a jump after")
        await manager.leave()
    }

    /// The friend's autoplay ran into the next episode while the invitation sat on screen. The
    /// screen names the new one, and saying yes opens it — Android's `pendingEpisode`.
    func testAnEpisodeTheFriendMovedToWhileTheInvitationWasReadIsTheOneOpened() async throws {
        let transport = TogetherManagerTests.Transport()
        let player = TogetherManagerTests.Playback()
        let manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Guest", transportFactory: { _, _ in transport })
        manager.attach(player)
        let link = try TogetherInvitation(roomID: "AAAAAAAAAAA", key: Data(repeating: 0, count: 16))
        await manager.join(link)
        try await Task.sleep(for: .milliseconds(20))
        try transport.deliver(TogetherMessage(t: .hello, seq: 1, name: "Host", animeId: 7, episode: 3, translationId: 4, positionMs: 1_300_000, playing: true),
                              link: link, side: .host)
        try transport.deliver(TogetherMessage(t: .episode, seq: 2, animeId: 7, episode: 4, translationId: 4, positionMs: 0), link: link, side: .host)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(manager.joining?.episode?.episode, 4)
        XCTAssertEqual(player.togetherSnapshot.episode, 1)
        manager.acceptJoin()
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(player.opened.map(\.episode), [4])
        XCTAssertTrue(player.togetherSnapshot.playing, "an episode a friend opened is one they are playing")
        await manager.leave()
    }

    /// «Не сейчас» is not leaving a session — nothing was joined. No goodbye goes out: the socket
    /// closes, the relay says «peer-left», and the host keeps the seat for half a minute the way
    /// it does for a dropped connection. A goodbye from here ended the host's room for anybody
    /// who tapped the link out of curiosity; Android's `dismissJoin()` has always been this quiet.
    func testDecliningTheInvitationSaysNoGoodbye() async throws {
        let transport = TogetherManagerTests.Transport()
        let player = TogetherManagerTests.Playback()
        let manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Guest", transportFactory: { _, _ in transport })
        manager.attach(player)
        let link = try TogetherInvitation(roomID: "AAAAAAAAAAA", key: Data(repeating: 0, count: 16))
        await manager.join(link)
        try await Task.sleep(for: .milliseconds(20))
        try transport.deliver(TogetherMessage(t: .hello, seq: 1, name: "Host", animeId: 7, episode: 3, positionMs: 5_000, playing: true),
                              link: link, side: .host)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(manager.joining?.episode?.episode, 3)
        await manager.leave()
        try await Task.sleep(for: .milliseconds(20))
        let said = transport.outgoing.compactMap { try? TogetherCodec.decode($0, invitation: link, from: .guest) }
        XCTAssertFalse(said.contains { $0.t == .bye }, "a viewer who never joined has nothing to say goodbye to")
        XCTAssertEqual(transport.closes, 1, "the socket simply closes")
        XCTAssertNil(manager.joining)
        XCTAssertEqual(manager.phase, .idle, "nothing was joined, so nothing ended")
        XCTAssertEqual(player.togetherSnapshot.episode, 1, "and the viewer's own video is untouched")
    }

    /// Whereas a viewer who did join, and leaves, says so — the host must not wait half a minute.
    func testLeavingAJoinedRoomStillSaysGoodbye() async throws {
        let transport = TogetherManagerTests.Transport()
        let player = TogetherManagerTests.Playback()
        let manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Guest", transportFactory: { _, _ in transport })
        manager.attach(player)
        let link = try TogetherInvitation(roomID: "AAAAAAAAAAA", key: Data(repeating: 0, count: 16))
        await manager.join(link)
        try await Task.sleep(for: .milliseconds(20))
        try transport.deliver(TogetherMessage(t: .hello, seq: 1, name: "Host", animeId: 7, episode: 1, positionMs: 1_000, playing: true),
                              link: link, side: .host)
        try await Task.sleep(for: .milliseconds(20))
        manager.acceptJoin()
        await manager.leave()
        try await Task.sleep(for: .milliseconds(20))
        let said = transport.outgoing.compactMap { try? TogetherCodec.decode($0, invitation: link, from: .guest) }
        XCTAssertTrue(said.contains { $0.t == .bye })
        XCTAssertEqual(manager.phase, .ended)
    }
}

/// A guest with nobody answering, or with somebody in the other seat whose frames will not open.
///
/// Android has always ended both after a while — `WAIT_TIMEOUT_MS` and `GARBLED_LIMIT`. Here the
/// screen said «Подключаемся…» for as long as anybody cared to look at it: a stale link, or a
/// stranger who knocked on the room first, was a spinner with no way out but the home button.
@MainActor final class TogetherGuestPatienceTests: XCTestCase {
    func testAGuestNobodyGreetsInHalfAMinuteGivesUp() async throws {
        let bench = try await TogetherSyncTests.Bench.joining()
        for _ in 0..<(Int(TogetherTiming.waitTimeoutMs / TogetherTiming.stateMs) - 1) { bench.manager.beat() }
        XCTAssertEqual(bench.manager.phase, .live, "twenty-nine seconds is still waiting")
        bench.manager.beat()
        await bench.settle()
        XCTAssertEqual(bench.manager.phase, .failed)
        XCTAssertEqual(bench.manager.error, .timeout)
        XCTAssertEqual(bench.manager.joining?.failure, TogetherError.timeout.errorDescription)
        XCTAssertEqual(bench.manager.joining?.retryable, true)
    }

    func testAHostWaitsForItsGuestAsLongAsItLikes() async throws {
        let transport = TogetherManagerTests.Transport()
        let manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Host", transportFactory: { _, _ in transport })
        await manager.create()
        try await Task.sleep(for: .milliseconds(20))
        for _ in 0..<100 { manager.beat() }
        XCTAssertEqual(manager.phase, .live, "a room is open until somebody walks into it or the host says otherwise")
        await manager.leave()
    }

    func testThreeFramesThatWillNotOpenInARowEndTheRoom() async throws {
        let bench = try await TogetherSyncTests.Bench.joining()
        for _ in 0..<(TogetherTiming.garbledLimit - 1) { bench.transport.continuation.yield(.frame(Data(repeating: 9, count: 64))) }
        await bench.settle()
        XCTAssertEqual(bench.manager.phase, .live, "one is a packet and two is bad luck")
        // A frame that opens starts the count again.
        try bench.deliver(.init(t: .ping, seq: 1, sentAt: 1))
        for _ in 0..<(TogetherTiming.garbledLimit - 1) { bench.transport.continuation.yield(.frame(Data(repeating: 9, count: 64))) }
        await bench.settle()
        XCTAssertEqual(bench.manager.phase, .live)
        bench.transport.continuation.yield(.frame(Data(repeating: 9, count: 64)))
        await bench.settle()
        XCTAssertEqual(bench.manager.phase, .failed)
        XCTAssertEqual(bench.manager.error, .disconnected)
    }
}

/// A greeting nobody heard is said again.
///
/// The relay keeps nothing: whoever is in the room first greets an empty room, and a host whose
/// socket blinked has already spent its guest's only hello. Both sides then sit there silently.
@MainActor final class TogetherGreetingRetryTests: XCTestCase {
    func testAnUnansweredGreetingIsRepeated() async throws {
        let transport = TogetherManagerTests.Transport()
        let manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Host", transportFactory: { _, _ in transport })
        await manager.create()
        try await Task.sleep(for: .milliseconds(30))
        let afterJoining = transport.outgoing.count
        // Three seconds of beats with nobody answering.
        for _ in 0..<3 { manager.beat() }
        try await Task.sleep(for: .milliseconds(30))
        XCTAssertGreaterThan(transport.outgoing.count, afterJoining)
        await manager.leave()
    }
    func testOnceSomebodyAnsweredTheGreetingStops() async throws {
        let transport = TogetherManagerTests.Transport()
        let manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Host", transportFactory: { _, _ in transport })
        await manager.create()
        try await Task.sleep(for: .milliseconds(30))
        let link = try XCTUnwrap(manager.invitation)
        try transport.deliver(TogetherMessage(t: .hello, seq: 1, name: "Guest", animeId: 0, episode: 0, positionMs: 0, playing: false),
                              link: link, side: .guest)
        try await Task.sleep(for: .milliseconds(30))
        let settled = transport.outgoing.count
        for _ in 0..<3 { manager.beat() }
        try await Task.sleep(for: .milliseconds(30))
        // State and pings still go; a greeting does not — the whole point is that it is answered.
        XCTAssertEqual(manager.peerName, "Guest")
        XCTAssertLessThanOrEqual(transport.outgoing.count - settled, 3)
        await manager.leave()
    }
}

/// Both phones opened the link.
///
/// A frame is sealed against the side that sent it, so two guests exchange bytes neither can read.
/// From the relay the room is alive; from inside it is silent, and the screen used to say
/// «Подключаемся…» until the half-minute ran out.
@MainActor final class TogetherSameSideTests: XCTestCase {
    func testTwoGuestsAreToldWhatIsWrong() async throws {
        let transport = TogetherManagerTests.Transport()
        let manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Guest", transportFactory: { _, _ in transport })
        let link = try TogetherInvitation(roomID: "AAAAAAAAAAA", key: Data(repeating: 0, count: 16))
        await manager.join(link)
        try await Task.sleep(for: .milliseconds(20))
        // The other phone joined as a guest too, so its frames carry the guest's own seal.
        try transport.deliver(TogetherMessage(t: .hello, seq: 1, name: "Тоже гость", animeId: 7, episode: 1, positionMs: 0, playing: false),
                              link: link, side: .guest)
        try await Task.sleep(for: .milliseconds(20))
        XCTAssertEqual(manager.error, .sameSide)
        XCTAssertEqual(manager.joining?.failure, TogetherError.sameSide.errorDescription)
    }
    func testAHostIgnoresAnInvitationToItsOwnRoom() async throws {
        let transport = TogetherManagerTests.Transport()
        let manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Host", transportFactory: { _, _ in transport })
        await manager.create()
        try await Task.sleep(for: .milliseconds(20))
        let own = try XCTUnwrap(manager.invitation)
        await manager.join(own)
        try await Task.sleep(for: .milliseconds(20))
        // Still the host of the room it made, and no join screen over it.
        XCTAssertNil(manager.joining)
        XCTAssertEqual(manager.phase, .live)
        await manager.leave()
    }
}

/// The host answers a greeting with its own.
///
/// Android's host does, and Android's guest waits for it: a room made on an iPhone was one that
/// an Android phone could not join — its greeting was heard, the room went live on the iPhone,
/// and nothing came back. «Не удалось подключиться», every time, in that one direction.
@MainActor final class TogetherHostAnswersTests: XCTestCase {
    func testTheHostAnswersAGuestsGreeting() async throws {
        let transport = TogetherManagerTests.Transport()
        let manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Host", transportFactory: { _, _ in transport })
        await manager.create()
        try await Task.sleep(for: .milliseconds(30))
        let link = try XCTUnwrap(manager.invitation)
        let before = transport.outgoing.count
        try transport.deliver(TogetherMessage(t: .hello, seq: 1, name: "Guest", animeId: 0, episode: 0, positionMs: 0, playing: false),
                              link: link, side: .guest)
        try await Task.sleep(for: .milliseconds(50))
        let answered = transport.outgoing.dropFirst(before).compactMap { try? TogetherCodec.decode($0, invitation: link, from: .host) }
        XCTAssertTrue(answered.contains { $0.t == .hello }, "гость должен услышать приветствие хозяина в ответ на своё")
        await manager.leave()
    }
}
