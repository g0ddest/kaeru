import Foundation

/// The waits between attempts to get back to the relay, and the half minute they have to fit in.
///
/// Dropping is ordinary: a phone changes cell, a screen locks, a train goes into a tunnel. So a
/// dropped socket is not the end of an evening, it is a handful of attempts over half a minute.
///
/// The steps are Android's `TogetherTimeouts.backoffMs`, and so is the way the budget is spent: the
/// last wait is clipped to what is left rather than abandoned for overshooting it. With 1/2/4/8/16
/// the fifth would land at 31 s, and giving up there would give up at 15 — half the window, with
/// the last step of the schedule unreachable. Clipped, the schedule a viewer actually gets is
/// 1/2/4/8/15, and the budget is spent exactly.
struct TogetherRelayBackoff {
    static let stepsMs: [Int64] = [1_000, 2_000, 4_000, 8_000, 16_000]
    static let budgetMs: Int64 = 30_000

    private var attempt = 0
    /// Counted in the waits themselves rather than off the wall clock — on a phone they are the
    /// same thing, and only the former can be tested without sitting through it.
    private(set) var waitedMs: Int64 = 0

    /// How long to wait before the next attempt, or nil when the budget is gone and the session is
    /// over. A socket that came back resets it: the half minute is per outage, not per evening.
    mutating func next() -> Int64? {
        let remaining = Self.budgetMs - waitedMs
        guard remaining > 0 else { return nil }
        let step = Self.stepsMs[min(attempt, Self.stepsMs.count - 1)]
        attempt += 1
        let wait = min(step, remaining)
        waitedMs += wait
        return wait
    }

    mutating func reset() { attempt = 0; waitedMs = 0 }
}

/// What was said while there was nowhere to write it.
///
/// A pause pressed during a two-second reconnect is a pause the viewer meant, and throwing it away
/// leaves two phones disagreeing about whether the episode is running. Bounded, because a long
/// outage must not fill memory; past the bound the oldest go, since the protocol's own rule is that
/// the last action wins and replaying a stale seek on reconnect would undo what came after it.
struct TogetherSendBuffer {
    /// Enough for everything a viewer can do in half a minute of scrubbing. Android's number.
    static let maximum = 64

    private(set) var frames: [Data] = []

    mutating func append(_ frame: Data) {
        frames.append(frame)
        if frames.count > Self.maximum { frames.removeFirst(frames.count - Self.maximum) }
    }

    /// Hands over everything held and empties the buffer, so a failed flush cannot send twice.
    mutating func drain() -> [Data] {
        defer { frames = [] }
        return frames
    }

    mutating func clear() { frames = [] }
}

/// The relay's own close codes are its HTTP status plus 4000, and three of them are answers rather
/// than accidents: dialling again would only get the same one back.
enum TogetherRelayClose {
    static let idle = 4408
    static let roomFull = 4409
    static let frameTooLarge = 4413

    /// Whether an error is one of those answers, and therefore not worth another attempt.
    static func refuses(_ error: TogetherError) -> Bool {
        error == .roomFull || error == .expired || error == .frameTooLarge
    }

    /// What to tell the viewer about a close that ends things, and nil for one that does not.
    static func refusal(for code: Int) -> TogetherError? {
        switch code {
        case roomFull: .roomFull
        case idle: .expired
        case frameTooLarge: .frameTooLarge
        default: nil
        }
    }
}
