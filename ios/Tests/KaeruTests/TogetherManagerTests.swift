import XCTest
@testable import Kaeru

@MainActor final class TogetherManagerTests: XCTestCase {
    final class Playback: TogetherPlayback {
        var togetherSnapshot = TogetherPlaybackSnapshot(animeID: 7, episode: 1, positionMs: 1000, playing: true, ready: true)
        var togetherSupportsRate = true
        var seeks: [Int64] = []
        var pauses = 0
        var rate: Float = 1
        func togetherPlay() { togetherSnapshot.playing = true }
        func togetherPause() { pauses += 1; togetherSnapshot.playing = false }
        func togetherSeek(toMilliseconds position: Int64) { seeks.append(position); togetherSnapshot.positionMs = position }
        func togetherSetRate(_ factor: Float) { rate = factor }
        func togetherDuck(_ on: Bool) {}
        func togetherOpen(_ episode: TogetherEpisode) async throws {
            togetherSnapshot = TogetherPlaybackSnapshot(animeID: episode.animeID, episode: episode.episode, translationID: episode.translationID, positionMs: episode.positionMs, playing: true, ready: true)
        }
    }
    final class Transport: TogetherTransport {
        var outgoing: [Data] = []
        var continuation: AsyncThrowingStream<TogetherTransportEvent, Error>.Continuation!
        var stream: AsyncThrowingStream<TogetherTransportEvent, Error>!
        var iterator: AsyncThrowingStream<TogetherTransportEvent, Error>.Iterator!
        init() { stream = AsyncThrowingStream { continuation = $0 }; iterator = stream.makeAsyncIterator() }
        func connect(_ invitation: TogetherInvitation, asHost: Bool) async throws {}
        func receive() async throws -> TogetherTransportEvent {
            // Iteration is serialized by the manager receive loop.
            var current = iterator!; let event = try await current.next(); iterator = current
            guard let event else { throw TogetherError.disconnected }; return event
        }
        func send(_ frame: Data) async throws { outgoing.append(frame) }
        func close() { continuation.finish() }
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
    func testLeaveFencesLateFrames() async throws {
        let transport = Transport(); let player = Playback()
        let manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Host", transportFactory: { _, _ in transport })
        manager.attach(player); await manager.create(); await settle()
        let link = try XCTUnwrap(manager.invitation)
        await manager.leave()
        try transport.deliver(TogetherMessage(t: .pause, seq: 100, positionMs: 5000), link: link, side: .guest)
        await settle(); XCTAssertEqual(player.pauses, 0); XCTAssertEqual(manager.phase, .ended)
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
