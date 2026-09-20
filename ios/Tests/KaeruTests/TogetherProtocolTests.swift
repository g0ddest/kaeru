import XCTest
@testable import Kaeru

final class TogetherProtocolTests: XCTestCase {
    let room = "AAAAAAAAAAA"
    let key = Data(repeating: 0, count: 16)
    func invitation() throws -> TogetherInvitation { try TogetherInvitation(roomID: room, key: key) }

    func testCanonicalLinksAndFragmentPrecedence() throws {
        let link = try invitation()
        XCTAssertEqual(try TogetherInvitation.parse(link.shareURL), link)
        XCTAssertEqual(try TogetherInvitation.parse(URL(string: "kaeru://watch?r=AAAAAAAAAAA&k=AAAAAAAAAAAAAAAAAAAAAA")!), link)
        for raw in [
            "https://evil.test/w/AAAAAAAAAAA#AAAAAAAAAAAAAAAAAAAAAA",
            "https://kaeru.vitaliy.velikodniy.name/w/AAAAAAAAAAA?k=AAAAAAAAAAAAAAAAAAAAAA",
            "kaeru://watch?r=AAAAAAAAAAB#AAAAAAAAAAAAAAAAAAAAAA",
            "kaeru://watch?r=AAAAAAAAAAA&k=AAAAAAAAAAAAAAAAAAAAAA#bad",
            "kaeru://watch?r=AAAAAAAAAAA&r=AAAAAAAAAAA#AAAAAAAAAAAAAAAAAAAAAA",
            "kaeru://watch?h=127.0.0.1&p=80&r=AAAAAAAAAAA#AAAAAAAAAAAAAAAAAAAAAA",
            "kaeru://watch?h=192.168.1.1&r=AAAAAAAAAAA#AAAAAAAAAAAAAAAAAAAAAA",
            "kaeru://watch?h=192.168.1.1&p=65536&r=AAAAAAAAAAA#AAAAAAAAAAAAAAAAAAAAAA",
            "kaeru://watch/other?r=AAAAAAAAAAA#AAAAAAAAAAAAAAAAAAAAAA",
            "https://u@kaeru.vitaliy.velikodniy.name/w/AAAAAAAAAAA#AAAAAAAAAAAAAAAAAAAAAA"
        ] { XCTAssertThrowsError(try TogetherInvitation.parse(URL(string: raw)!), raw) }
    }

    func testLANLiteralValidation() {
        for host in ["10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.2", "169.254.1.2"] { XCTAssertTrue(TogetherLANAddress.isValid(host)) }
        for host in ["localhost", "127.0.0.1", "8.8.8.8", "172.32.0.1", "192.168.001.1", "192.168.+1.1", "::1", "192.168.1.256"] { XCTAssertFalse(TogetherLANAddress.isValid(host), host) }
    }

    func testCodecAuthenticatesSideRoomNonceAndTampering() throws {
        let link = try invitation()
        let message = TogetherMessage(t: .seek, seq: 7, positionMs: 42_000)
        let frame = try TogetherCodec.encode(message, invitation: link, from: .host, nonce: Data(repeating: 1, count: 12))
        XCTAssertEqual(try TogetherCodec.decode(frame, invitation: link, from: .host), message)
        XCTAssertThrowsError(try TogetherCodec.decode(frame, invitation: link, from: .guest))
        let other = try TogetherInvitation(roomID: "AQAAAAAAAAA", key: key)
        XCTAssertThrowsError(try TogetherCodec.decode(frame, invitation: other, from: .host))
        var flipped = frame; flipped[20] ^= 1
        XCTAssertThrowsError(try TogetherCodec.decode(flipped, invitation: link, from: .host))
        XCTAssertThrowsError(try TogetherCodec.decode(Data(repeating: 0, count: 28), invitation: link, from: .host))
        XCTAssertThrowsError(try TogetherCodec.decode(Data(repeating: 0, count: 65_537), invitation: link, from: .host))
    }

    func testMalformedMessagesAndForwardCompatibleFields() throws {
        let valid = Data(#"{"t":"play","seq":1,"positionMs":123,"future":true}"#.utf8)
        XCTAssertEqual(try TogetherCodec.decodeJSON(valid).positionMs, 123)
        let greeting = Data(#"{"t":"hello","seq":1,"name":"a","animeId":1,"episode":1,"positionMs":0,"playing":true}"#.utf8)
        XCTAssertNil(try TogetherCodec.decodeJSON(greeting).epoch, "a build older than the epoch still greets")
        for raw in [
            #"{"t":"peer-left","seq":0}"#, #"{"t":"unknown","seq":1}"#,
            #"{"t":"play","seq":1}"#, #"{"t":"seek","seq":-1,"positionMs":0}"#,
            #"{"t":"seek","seq":1,"positionMs":-1}"#,
            #"{"t":"reaction","seq":1,"kind":"bad"}"#,
            #"{"t":"hello","seq":1,"name":"a","animeId":1,"episode":1,"positionMs":0,"playing":true,"epoch":0}"#,
            #"{"t":"voice","seq":1,"chunk":0,"total":9,"bytes":"AA","durationMs":1000}"#,
            #"{"t":"voice","seq":1,"chunk":1,"total":1,"bytes":"AA","durationMs":1000}"#
        ] { XCTAssertThrowsError(try TogetherCodec.decodeJSON(Data(raw.utf8)), raw) }
    }

    func testSymmetricOrderingAndRejoinReset() throws {
        var guest = TogetherOrdering(isHost: false)
        let mine = try guest.next(control: true)
        XCTAssertTrue(guest.accept(seq: mine, control: true)) // equal host control wins
        XCTAssertFalse(guest.accept(seq: mine, control: true)) // replay
        XCTAssertEqual(try guest.next(control: false), mine + 1)
        var host = TogetherOrdering(isHost: true)
        _ = try host.next(control: true)
        XCTAssertFalse(host.accept(seq: 1, control: true))
        XCTAssertTrue(host.accept(seq: 5, control: false))
        XCTAssertFalse(host.accept(seq: 4, control: false))
        host.allowRejoin()
        XCTAssertTrue(host.accept(seq: 1, control: false, hello: true))
        XCTAssertGreaterThan(try host.next(control: true), 5)
    }

    /// A relay keeps every frame it carries, and it decides when a socket drops. Handing a kept
    /// greeting back into the half-minute window used to reopen the count, and with it every
    /// frame of the evening, in order. A greeting says which session it belongs to now, and one
    /// that was heard before is judged by its count like any other frame.
    func testAGreetingTheRelayKeptCannotReopenTheCount() {
        var host = TogetherOrdering(isHost: true)
        XCTAssertTrue(host.accept(seq: 1, control: false, hello: true, epoch: 77))
        XCTAssertTrue(host.accept(seq: 40, control: true))
        host.allowRejoin()
        // The same greeting, handed back: same epoch, and forty is the mark.
        XCTAssertFalse(host.accept(seq: 1, control: false, hello: true, epoch: 77))
        XCTAssertFalse(host.accept(seq: 40, control: true), "the seek that followed it must not play again")
        // The friend's session started over: a greeting with an epoch never heard, and it alone
        // comes below the mark.
        XCTAssertTrue(host.accept(seq: 1, control: false, hello: true, epoch: 78))
        XCTAssertFalse(host.accept(seq: 40, control: true), "what was said before the restart stays refused")
        // Counted above what they heard from this side, their next word is theirs.
        XCTAssertTrue(host.accept(seq: 41, control: true))
        // Neither greeting can be played again, window or no window.
        host.allowRejoin()
        XCTAssertFalse(host.accept(seq: 1, control: false, hello: true, epoch: 78))
        XCTAssertFalse(host.accept(seq: 1, control: false, hello: true, epoch: 77))
        XCTAssertEqual(host.peerEpochs, [77, 78])
    }

    /// A friend on a build older than the epoch gets the older rule, for as long as they stay on it.
    func testAGreetingWithNoEpochKeepsTheOldRule() {
        var host = TogetherOrdering(isHost: true)
        XCTAssertTrue(host.accept(seq: 1, control: false, hello: true))
        XCTAssertTrue(host.accept(seq: 40, control: true))
        // No window open: below the mark is below the mark.
        XCTAssertFalse(host.accept(seq: 1, control: false, hello: true))
        host.allowRejoin()
        XCTAssertTrue(host.accept(seq: 1, control: false, hello: true))
        XCTAssertTrue(host.accept(seq: 2, control: true), "they count from one again, and are followed")
        // Once, per window.
        XCTAssertFalse(host.accept(seq: 1, control: false, hello: true))
        // A friend who has ever greeted with an epoch is on the new rule: a bare greeting inside
        // a window is a frame like any other.
        var guest = TogetherOrdering(isHost: false)
        XCTAssertTrue(guest.accept(seq: 1, control: false, hello: true, epoch: 9))
        XCTAssertTrue(guest.accept(seq: 30, control: false))
        guest.allowRejoin()
        XCTAssertFalse(guest.accept(seq: 1, control: false, hello: true))
    }

    func testClockProjectionAndDriftBoundaries() {
        var clock = TogetherClock()
        clock.record(sent: 1000, peerReceived: 1550, peerSent: 1550, received: 1100)
        XCTAssertEqual(clock.offsetMs, 500)
        XCTAssertEqual(clock.project(position: 10_000, sentAt: 2000, playing: true, now: 1600), 10_100)
        XCTAssertEqual(TogetherSync.decide(local: 10_499, remote: 10_000, offsetMs: 0, bothPlaying: true, correcting: false, supportsRate: true), .none)
        XCTAssertEqual(TogetherSync.decide(local: 10_500, remote: 10_000, offsetMs: 0, bothPlaying: true, correcting: false, supportsRate: true), .rate(0.97))
        XCTAssertEqual(TogetherSync.decide(local: 12_000, remote: 10_000, offsetMs: 0, bothPlaying: true, correcting: false, supportsRate: true), .seek(10_000, notify: false))
        XCTAssertEqual(TogetherSync.decide(local: 20_001, remote: 10_000, offsetMs: 0, bothPlaying: true, correcting: false, supportsRate: true), .seek(10_000, notify: true))
        XCTAssertEqual(TogetherSync.decide(local: 10_199, remote: 10_000, offsetMs: 0, bothPlaying: true, correcting: true, supportsRate: true), .rate(1))
        XCTAssertEqual(TogetherSync.decide(local: 11_001, remote: 10_000, offsetMs: 0, bothPlaying: true, correcting: false, supportsRate: false), .seek(10_000, notify: false))
        XCTAssertEqual(TogetherSync.decide(local: 20_000, remote: 10_000, offsetMs: 0, bothPlaying: false, correcting: false, supportsRate: true), .none)
    }

    func testVoiceAssemblyBoundsAndExpiry() throws {
        var voice = TogetherVoiceAssembly()
        let first = TogetherMessage(t: .voice, seq: 1, chunk: 0, total: 2, bytes: Data([1]).togetherBase64, durationMs: 1000)
        let second = TogetherMessage(t: .voice, seq: 2, chunk: 1, total: 2, bytes: Data([2]).togetherBase64, durationMs: 1000)
        XCTAssertNil(try voice.append(first, now: 0))
        XCTAssertEqual(try voice.append(second, now: 100)?.data, Data([1,2]))
        XCTAssertNil(try voice.append(first, now: 0))
        XCTAssertNil(try voice.append(second, now: 30_001))
        XCTAssertNil(try voice.append(second, now: 30_002))
    }
}
