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
///
/// A reference rather than a value, because a flush is written one frame at a time across as many
/// suspensions, and what the session says meanwhile has to land in the same buffer, behind it.
@MainActor final class TogetherSendBuffer {
    /// Enough for everything a viewer can do in half a minute of scrubbing. Android's number.
    static let maximum = 64

    private(set) var frames: [Data] = []

    func append(_ frame: Data) {
        frames.append(frame)
        if frames.count > Self.maximum { frames.removeFirst(frames.count - Self.maximum) }
    }

    /// Writes what is held, oldest first, taking each frame off only once it has been written.
    /// A write that fails is a socket on its way out: that frame and everything after it stay, in
    /// order, for the next socket. Whether the buffer was emptied.
    ///
    /// Used to hand everything over in one go and forget it, so a socket that died the moment it
    /// opened took half a minute of a viewer's scrubbing with it, silently.
    func flush(_ write: (Data) async throws -> Void) async -> Bool {
        while let frame = frames.first {
            do { try await write(frame) } catch { return false }
            // Still at the front: appends go to the back, and nothing else takes from the front
            // but `clear`, after which the transport is closed and appends nothing.
            if frames.first == frame { frames.removeFirst() }
        }
        return true
    }

    func clear() { frames = [] }
}

/// A protocol-level ping on a schedule, and a socket called dead when its pong does not come back.
///
/// Both clients take the read deadline off the relay socket, because a room with one person in it
/// is silent for as long as somebody is copying the link. The cost is that a socket which stops
/// carrying anything without ever closing — a cell handover, a NAT that forgot the connection — is
/// noticed by nobody: writes are buffered by the kernel and the read simply never returns. Android
/// gets this from OkHttp's `pingInterval`; here it has to be done by hand. Separate from the
/// application's own ping, which measures the clocks and is not answered by a socket that is
/// merely alive.
struct TogetherSocketWatchdog {
    let intervalSeconds: TimeInterval

    /// Waits the interval, asks `ping` for a pong, and goes round again for as long as one comes.
    /// The first time none does, `dead` — once — and done. A cancelled watchdog calls nothing.
    @MainActor func run(ping: @MainActor () async -> Bool, dead: @MainActor () -> Void) async {
        while true {
            do { try await Task.sleep(for: .seconds(intervalSeconds)) } catch { return }
            if Task.isCancelled { return }
            if await ping() { continue }
            if Task.isCancelled { return }
            dead()
            return
        }
    }
}

/// Polls `done` every `stepMs` until it says so or the time is up. Whether it was the former.
@MainActor func togetherWaitUntil(_ done: @MainActor () -> Bool, seconds: TimeInterval, stepMs: Int = 50) async -> Bool {
    let deadline = Date().addingTimeInterval(seconds)
    while !done() {
        guard Date() < deadline else { return false }
        try? await Task.sleep(for: .milliseconds(stepMs))
    }
    return true
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
