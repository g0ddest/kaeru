import XCTest
@testable import Kaeru

/// An evening in a few milliseconds: this phone as the guest, an Android phone as the host, and
/// everything that pulls two pictures apart — clocks 700 ms apart, a relay with jitter, a host
/// whose reports are read up to a quarter of a second late, and HLS stalls of three seconds on
/// either side. What the owner saw across the room was «сходится с разницей в секунду» and a seek
/// every few minutes; this is where that has to show up before a build does.
///
/// The host is modelled, not run: what Android's `TogetherSession` puts on the wire and does about
/// what it hears — a report a second and a ping every five, a hold for a guest that is loading, a
/// stall said the moment it starts — and nothing else, since the host is the reference and never
/// corrects itself.
@MainActor final class TogetherSimulationTests: XCTestCase {
    final class Clock: @unchecked Sendable { var value: Int64 = 0 }

    /// A player whose picture moves with the clock at the rate it was last asked for, and stands
    /// still while it stalls. A seek costs the stall HLS charges for one.
    final class MovingPlayer: TogetherPlayback {
        var position: Double = 0
        var playing = true
        var stalledFor: Double = 0
        var rate: Float = 1
        var seeks: [Int64] = []
        var seekStallMs: Double = 800
        var togetherSnapshot: TogetherPlaybackSnapshot {
            .init(animeID: 7, episode: 1, positionMs: Int64(position), playing: playing, buffering: stalledFor > 0, ready: true)
        }
        var togetherSupportsRate: Bool { true }
        func togetherPlay() { playing = true }
        func togetherPause() { playing = false }
        func togetherSeek(toMilliseconds value: Int64) { seeks.append(value); position = Double(value); stalledFor = seekStallMs }
        func togetherSetRate(_ factor: Float) { rate = factor }
        func togetherDuck(_ on: Bool) {}
        func togetherOpen(_ episode: TogetherEpisode) async throws {}
        func advance(_ ms: Double) {
            if stalledFor > 0 { stalledFor = max(0, stalledFor - ms); return }
            guard playing else { return }
            position += ms * Double(rate)
        }
    }

    /// The Android host, as far as the wire can tell.
    struct Host {
        var position: Double = 60_000
        var playing = true
        var stalledFor: Double = 0
        var held = false
        var guestLoading = false
        var seq: Int64 = 1
        // Far enough back that the first beat is due at once, and far enough from the edge that
        // subtracting it from a clock does not trap.
        var lastReportAt: Int64 = -1_000_000
        var lastPingAt: Int64 = -1_000_000
        var reportedBuffering = false
        var buffering: Bool { stalledFor > 0 }
        mutating func advance(_ ms: Double) {
            if stalledFor > 0 { stalledFor = max(0, stalledFor - ms); return }
            if playing { position += ms }
        }
    }

    struct Bound { let at: Int64; let message: TogetherMessage }

    func settle() async { for _ in 0..<30 { await Task.yield() } }

    func testAnEveningOfSkewedClocksAndStallsSettlesWithinHalfASecondAndWithoutSeeks() async throws {
        let clock = Clock()
        let transport = TogetherManagerTests.Transport()
        let player = MovingPlayer()
        let manager = TogetherManager(relayURL: "wss://relay.test", displayName: "Гость",
                                      transportFactory: { _, _ in transport }, now: { clock.value })
        let invitation = try TogetherInvitation(roomID: "AAAAAAAAAAA", key: Data(repeating: 3, count: 16))
        // The order the app has: the link is followed first, the viewer says yes, the player opens after.
        await manager.join(invitation)
        await settle()
        manager.acceptJoin()
        manager.attach(player)
        await settle()

        let skew: Int64 = 700
        var host = Host()
        var toGuest: [Bound] = []
        var toHost: [Bound] = []
        var handled = 0
        var trips = 0
        var lastToGuest: Int64 = 0
        var lastToHost: Int64 = 0
        // Sixty to a hundred and fifty milliseconds each way, never the same twice running: the
        // asymmetry is what the offset estimate has to live with. Never overtaking, though: a
        // socket delivers in order, and the replay guard counts on it.
        func latency() -> Int64 { trips += 1; return 60 + Int64((trips * 37) % 90) }
        func hostNow() -> Int64 { clock.value + skew }
        func post(_ message: TogetherMessage) {
            var sealed = message
            sealed.seq = host.seq; host.seq += 1
            lastToGuest = max(lastToGuest, clock.value + latency())
            toGuest.append(Bound(at: lastToGuest, message: sealed))
        }
        func hostReport() {
            // What the last poll saw, up to a quarter of a second ago: Media3 does not announce
            // the position, and Android reads it four times a second.
            let poll = Double((trips * 53) % 250)
            host.reportedBuffering = host.buffering
            post(.init(t: .state, seq: 1, positionMs: Int64(max(0, host.position - poll)), playing: host.playing,
                       buffering: host.buffering, sentAt: hostNow()))
        }
        post(.init(t: .hello, seq: 1, name: "Хозяин", animeId: 7, episode: 1, positionMs: Int64(host.position), playing: true))

        let step: Int64 = 100
        let end: Int64 = 240_000
        let guestStalls: Set<Int64> = [80_000, 150_000]
        let hostStalls: Set<Int64> = [40_000, 120_000, 200_000]
        var seeksAtSteadyState = 0
        var samples: [(at: Int64, driftMs: Int64)] = []

        while clock.value < end {
            clock.value += step
            player.advance(Double(step)); host.advance(Double(step))
            if guestStalls.contains(clock.value) { player.stalledFor = 3_000 }
            if hostStalls.contains(clock.value) { host.stalledFor = 3_000 }

            let arriving = toGuest.filter { $0.at <= clock.value }.sorted { $0.at < $1.at }
            toGuest.removeAll { $0.at <= clock.value }
            for bound in arriving { try transport.deliver(bound.message, link: invitation, side: .host) }
            await settle()

            if clock.value % 1_000 == 0 { manager.beat() }
            manager.reportStallIfChanged()
            await settle()

            for frame in transport.outgoing[handled...] {
                guard let message = try? TogetherCodec.decode(frame, invitation: invitation, from: .guest) else { continue }
                lastToHost = max(lastToHost, clock.value + latency())
                toHost.append(Bound(at: lastToHost, message: message))
            }
            handled = transport.outgoing.count

            let landed = toHost.filter { $0.at <= clock.value }.sorted { $0.at < $1.at }
            toHost.removeAll { $0.at <= clock.value }
            for bound in landed {
                switch bound.message.t {
                case .ping:
                    let at = hostNow()
                    post(.init(t: .pong, seq: 1, sentAt: at, pingSentAt: bound.message.sentAt, receivedAt: at))
                case .hello:
                    // Android's `arrived`: every greeting is answered with one, and a ping.
                    post(.init(t: .hello, seq: 1, name: "Хозяин", animeId: 7, episode: 1, positionMs: Int64(host.position), playing: host.playing))
                    post(.init(t: .ping, seq: 1, sentAt: hostNow()))
                case .state:
                    // Android's `peerIsLoading`: a guest that means to play and is loading is
                    // waited for; the first report to say otherwise starts the picture again.
                    let loading = (bound.message.buffering ?? false) && (bound.message.playing ?? false)
                    guard loading != host.guestLoading else { break }
                    host.guestLoading = loading
                    if loading {
                        if host.playing { host.held = true; host.playing = false }
                    } else if host.held {
                        host.held = false; host.playing = true
                    }
                default: break
                }
            }

            if hostNow() - host.lastReportAt >= 1_000 {
                host.lastReportAt = hostNow(); hostReport()
            } else if host.buffering != host.reportedBuffering {
                hostReport()
            }
            if hostNow() - host.lastPingAt >= 5_000 {
                host.lastPingAt = hostNow()
                post(.init(t: .ping, seq: 1, sentAt: hostNow()))
            }

            if clock.value == 60_000 { seeksAtSteadyState = player.seeks.count }
            // Sampled between stalls, once both pictures are moving again.
            if clock.value > 60_000, clock.value % 10_000 == 0, player.stalledFor == 0, host.stalledFor == 0,
               player.playing, host.playing {
                samples.append((clock.value, Int64(player.position - host.position)))
            }
        }

        let settled = samples.map { "\($0.at / 1000)s: \($0.driftMs)ms" }.joined(separator: ", ")
        XCTAssertGreaterThanOrEqual(samples.count, 12, "the evening should have had a quiet minute or two in it")
        for sample in samples {
            XCTAssertLessThan(abs(sample.driftMs), TogetherSync.ignoreMs,
                              "the two pictures should settle inside the band the policy leaves alone; drift was \(settled)")
        }
        XCTAssertLessThanOrEqual(player.seeks.count - seeksAtSteadyState, 1,
                                 "at most one seek in three minutes of stalls; seeks were \(player.seeks)")
        XCTAssertEqual(manager.clockOffsetMs, skew, accuracy: 60, "the clocks should be read as 700 ms apart")
        await manager.leave()
    }
}
