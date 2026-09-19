import Foundation

struct TogetherOrdering {
    let isHost: Bool
    private(set) var sequence: Int64 = 0
    private var peerSequence: Int64 = 0
    private var lastControl: (Int64, Bool) = (0, false)
    private var rejoining = false
    mutating func next(control: Bool) throws -> Int64 {
        guard sequence < Int64.max - 1 else { throw TogetherError.invalidMessage }
        sequence += 1
        if control { lastControl = (sequence, isHost) }
        return sequence
    }
    mutating func allowRejoin() { rejoining = true }
    mutating func accept(seq: Int64, control: Bool, hello: Bool = false) -> Bool {
        guard seq > 0, seq < Int64.max else { return false }
        if rejoining && hello { peerSequence = 0; lastControl = (0, false); rejoining = false }
        guard seq > peerSequence else { return false }
        peerSequence = seq; sequence = max(sequence, seq)
        guard control else { return true }
        let wins = seq > lastControl.0 || (seq == lastControl.0 && !isHost && !lastControl.1)
        if wins { lastControl = (seq, !isHost) }
        return wins
    }
}

struct TogetherClock {
    private var samples: [(Int64, Int64)] = []
    var offsetMs: Int64 { median(samples.map(\.0)) }
    var rttMs: Int64 { median(samples.map(\.1)) }
    mutating func record(sent: Int64, peerReceived: Int64, peerSent: Int64, received: Int64) {
        guard received >= sent, peerSent >= peerReceived else { return }
        let rtt = (received - sent) - (peerSent - peerReceived)
        guard (0...60_000).contains(rtt), abs(peerReceived - sent) <= 86_400_000 else { return }
        samples.append((((peerReceived - sent) + (peerSent - received)) / 2, rtt))
        if samples.count > 5 { samples.removeFirst() }
    }
    func project(position: Int64, sentAt: Int64, playing: Bool, now: Int64) -> Int64 {
        // peerClock = localClock + offset. Thus elapsed = localNow + offset - peerSentAt.
        max(0, position + (playing ? min(60_000, max(0, now + offsetMs - sentAt)) : 0))
    }
    private func median(_ values: [Int64]) -> Int64 {
        let sorted = values.sorted(); guard !sorted.isEmpty else { return 0 }
        return (sorted[(sorted.count - 1) / 2] + sorted[sorted.count / 2]) / 2
    }
}

enum TogetherSyncAction: Equatable { case none, rate(Float), seek(Int64, notify: Bool) }

/// How often the session speaks, in milliseconds.
///
/// These are Android's `TogetherSession` constants and they have to stay the same on both phones:
/// a device that says where it is once every three seconds cannot be corrected against by one that
/// expects a report every second, and a side that never pings leaves the other measuring an offset
/// of zero for the whole evening.
enum TogetherTiming {
    /// Where this side is, often enough for the other to measure drift against.
    static let stateMs: Int64 = 1_000
    /// How often the gap is looked at. Twice the report interval, so it is never acting blind.
    static let syncMs: Int64 = 2_000
    /// One round trip for the clocks, and what keeps a quiet socket from idling out.
    static let pingMs: Int64 = 5_000
    /// Past this, the friend's last report is too old to carry forward from.
    static let staleStateMs: Int64 = 5_000
    /// How long a friend whose socket went away has to walk back into the room.
    static let rejoinWindowMs: Int64 = 30_000
}

enum TogetherSync {
    /// - Parameter offsetMs: how far the friend's clock is from this one, so `remote + offsetMs`
    ///   is where they are on this device's clock. Without it the two sides chase each other's
    ///   clock error instead of the drift, which is a steady seek every couple of seconds between
    ///   phones whose clocks are a second apart.
    static func decide(local: Int64, remote: Int64, offsetMs: Int64, bothPlaying: Bool, correcting: Bool, supportsRate: Bool) -> TogetherSyncAction {
        let settled: TogetherSyncAction = correcting ? .rate(1) : .none
        guard bothPlaying else { return settled }
        let target = remote + offsetMs
        let drift = local - target
        let gap = abs(drift)
        if gap < 200 { return settled }
        if gap < 500 && !correcting { return .none }
        if gap < 2000 {
            if !supportsRate { return gap > 1000 ? .seek(target, notify: false) : settled }
            return .rate(drift > 0 ? 0.97 : 1.03)
        }
        return .seek(target, notify: gap > 10_000)
    }
}

struct TogetherVoiceClip: Equatable, Sendable { let data: Data; let durationMs: Int64 }
struct TogetherVoiceAssembly {
    private var parts: [Data] = []
    private var total = 0
    private var duration: Int64 = 0
    private var started: Int64 = 0
    mutating func append(_ message: TogetherMessage, now: Int64) throws -> TogetherVoiceClip? {
        try message.validate()
        guard message.t == .voice, let index = message.chunk, let count = message.total, let encoded = message.bytes, let bytes = Data(togetherBase64: encoded), let duration = message.durationMs else { throw TogetherError.invalidMessage }
        if now - started > 30_000 { parts = []; total = 0 }
        if index == 0 { parts = []; total = count; self.duration = duration; started = now }
        guard count == total, index == parts.count, duration == self.duration else { parts = []; total = 0; return nil }
        guard parts.reduce(0, { $0 + $1.count }) + bytes.count <= 262_144 else { parts = []; throw TogetherError.frameTooLarge }
        parts.append(bytes)
        guard parts.count == total else { return nil }
        defer { parts = []; total = 0 }
        return TogetherVoiceClip(data: parts.reduce(into: Data(), { $0.append($1) }), durationMs: duration)
    }
}
