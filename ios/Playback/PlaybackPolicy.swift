import Foundation
import KaeruShared

struct NextEpisodeState: Equatable, Sendable {
    var offered = false
    var countdown: Int?
    var advance = false
}
enum AutomaticSkip: Equatable { case seek(Double), finishEnding }

/// The player's memory of one episode — whether autoplay was cancelled, whether it already moved
/// on, whether the ending already stepped aside, where the last tick was and when the last seek
/// settles — around the shared rules (Playback/PlayerRules.swift), as Android's PlaybackController
/// keeps it. Measured in seconds of media. State survives quality/translation changes; reset only
/// when opening another episode.
struct PlaybackPolicy {
    private var autoplayCancelled = false
    private var advanced = false
    private var endingSkipped = false
    private var previousPosition: Double?
    private var settleAt = 0.0

    mutating func resetEpisode() { self = Self() }
    mutating func cancelAutoplay() { autoplayCancelled = true }
    mutating func didSeek(to position: Double) {
        previousPosition = nil
        settleAt = position + SkipRules.shared.SEEK_SETTLE_MS.mediaSeconds
    }
    mutating func next(position: Double, duration: Double, hasNext: Bool, autoNext: Bool, ended: Bool = false) -> NextEpisodeState {
        guard position.isFinite, duration.isFinite, duration > 0, hasNext else { return NextEpisodeState() }
        let offered = EpisodeQueue.nextDue(position: position, duration: duration, ended: ended)
        guard autoNext, !autoplayCancelled,
              let countdown = EpisodeQueue.countdown(position: position, duration: duration, ended: ended) else {
            return NextEpisodeState(offered: offered)
        }
        let advance = countdown <= 0 && !advanced
        if advance { advanced = true }
        return NextEpisodeState(offered: offered, countdown: countdown, advance: advance)
    }
    /// «Пропускать эндинг»: once per episode, ten seconds into the ending — and only when playback
    /// carried the viewer there: the tick before was already inside the ending, and a second has
    /// played since the last seek. A position the bar was dragged to is not the viewer asking to
    /// be moved on.
    mutating func automaticSkip(marks: SkipMarks, position: Double, duration: Double, ending: Bool) -> AutomaticSkip? {
        defer { previousPosition = position }
        guard ending, !endingSkipped,
              marks.endingSkipDue(position: position, duration: duration),
              position >= settleAt, let previousPosition,
              marks.insideEnding(position: previousPosition, duration: duration) else { return nil }
        endingSkipped = true
        return .finishEnding
    }
    static func clampSeek(_ position: Double, duration: Double) -> Double {
        guard position.isFinite else { return 0 }
        return duration.isFinite && duration > 0 ? min(duration, max(0, position)) : max(0, position)
    }
    static func quality(preferred: Int, available: [Int]) -> Int? {
        let available = available.filter { $0 > 0 }
        if preferred <= 0 { return available.max() }
        return available.contains(preferred) ? preferred : available.max()
    }
}

/// Transient system pauses must not become user intent, or foreground resume breaks.
struct PlaybackIntent {
    private(set) var wantsPlayback = true
    private(set) var suspended = false
    var shouldPlay: Bool { wantsPlayback && !suspended }
    mutating func userSetPlaying(_ playing: Bool) { wantsPlayback = playing }
    mutating func suspend(backgroundAllowed: Bool, pictureInPicture: Bool) { suspended = !backgroundAllowed && !pictureInPicture }
    mutating func activate() { suspended = false }
}
