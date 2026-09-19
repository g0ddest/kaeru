import Foundation

struct SkipInterval: Codable, Equatable, Sendable {
    var start: Double
    var end: Double
    func contains(_ position: Double) -> Bool { position >= start && position < end }
    func plausible(duration: Double) -> Bool {
        duration.isFinite && duration > 0 && start.isFinite && end.isFinite && start >= 0 && end <= duration && (60...150).contains(end - start)
    }
}

enum SkipKind: String, Codable, Sendable { case opening, ending }
struct SkipOffer: Equatable, Sendable {
    var kind: SkipKind
    var interval: SkipInterval
}
struct SkipMarks: Codable, Equatable, Sendable {
    var opening: SkipInterval?
    var ending: SkipInterval?
    func accepted(duration: Double) -> Self {
        Self(opening: opening.flatMap { $0.plausible(duration: duration) && $0.start <= 300 ? $0 : nil },
             ending: ending.flatMap { $0.plausible(duration: duration) && $0.end >= duration - 180 ? $0 : nil })
    }
    func offer(position: Double, duration: Double) -> SkipOffer? {
        let valid = accepted(duration: duration)
        if let opening = valid.opening, position >= opening.start, position < opening.start + 10 {
            return SkipOffer(kind: .opening, interval: opening)
        }
        if let ending = valid.ending, position >= ending.start, position < ending.start + 10 {
            return SkipOffer(kind: .ending, interval: ending)
        }
        return nil
    }
}

struct NextEpisodeState: Equatable, Sendable {
    var offered = false
    var countdown: Int?
    var advance = false
}
enum AutomaticSkip: Equatable { case seek(Double), finishEnding }

/// Android 0.6 SkipRules / EpisodeQueue arithmetic, measured in seconds of media.
/// State survives quality/translation changes; reset only when opening another episode.
struct PlaybackPolicy {
    private var autoplayCancelled = false
    private var advanced = false
    private var endingSkipped = false
    private var previousPosition: Double?
    private var settleAt = 0.0

    mutating func resetEpisode() { self = Self() }
    mutating func cancelAutoplay() { autoplayCancelled = true }
    mutating func didSeek(to position: Double) { previousPosition = nil; settleAt = position + 1 }
    mutating func next(position: Double, duration: Double, hasNext: Bool, autoNext: Bool, ended: Bool = false) -> NextEpisodeState {
        guard position.isFinite, duration.isFinite, duration > 0, hasNext else { return NextEpisodeState() }
        let remaining = max(0, duration - position)
        let offered = ended || remaining <= 30
        guard autoNext, !autoplayCancelled, ended || remaining <= 10 else { return NextEpisodeState(offered: offered) }
        let countdown = ended ? 0 : Int(ceil(remaining))
        let advance = countdown == 0 && !advanced
        if advance { advanced = true }
        return NextEpisodeState(offered: offered, countdown: countdown, advance: advance)
    }
    mutating func automaticSkip(marks: SkipMarks, position: Double, duration: Double, playing: Bool, ending: Bool) -> AutomaticSkip? {
        defer { previousPosition = position }
        guard playing, ending, !endingSkipped,
              let interval = marks.accepted(duration: duration).ending,
              position >= interval.start + 10, interval.contains(position),
              position >= settleAt, let previousPosition, interval.contains(previousPosition) else { return nil }
        endingSkipped = true
        return .finishEnding
    }
    static func resume(position: Double, duration: Double, threshold: Double) -> Double {
        guard position.isFinite, position > 0, duration.isFinite, duration > 0 else { return 0 }
        let threshold = threshold.isFinite ? min(1, max(0.5, threshold)) : 0.9
        return position < duration * threshold ? position : 0
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
