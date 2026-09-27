import Foundation
import KaeruShared

// Thin Swift faces for the player rules in shared/ (shared/src/commonMain/.../domain/playback/),
// the same functions Android calls. The rules and their tests live there; this file only turns
// this app's seconds and optionals into the milliseconds and boxed numbers Kotlin speaks, so a
// screen never sees a `KotlinInt`.

// MARK: - Units

extension Double {
    /// Seconds of media as the shared rules count them: whole milliseconds. Anything that is not a
    /// finite number is nothing, which every rule reads as «not known».
    var mediaMilliseconds: Int64 {
        guard isFinite else { return 0 }
        let value = (self * 1000).rounded()
        return value >= Double(Int64.max) ? .max : value <= Double(Int64.min) ? .min : Int64(value)
    }
}

extension Int64 {
    /// Back from the shared rules' milliseconds to this app's seconds.
    var mediaSeconds: Double { Double(self) / 1000 }
}

extension Int {
    /// This number boxed for Kotlin, clamped into its 32 bits.
    var kotlin: KotlinInt { KotlinInt(int: Int32(clamping: self)) }
}

/// Numbers Kotlin hands back inside a list or a map. They arrive as plain `NSNumber`s rather than
/// the `KotlinInt` the header promises, and Swift's bridge traps on reading one as the other — so
/// they are read through Foundation's untyped collections instead. The sync faces (Core/SharedSync.swift)
/// read theirs the same way.
enum KotlinNumbers {
    static func ints(_ list: [KotlinInt]) -> [Int] {
        (list as NSArray).map { ($0 as! NSNumber).intValue }
    }
    static func ints(_ map: [KotlinInt: KotlinInt]) -> [Int: Int] {
        var result: [Int: Int] = [:]
        for (key, value) in map as NSDictionary {
            result[(key as! NSNumber).intValue] = (value as! NSNumber).intValue
        }
        return result
    }
}

// MARK: - Opening and ending (SkipRules)

// Kotlin data classes and enum entries, immutable once made: safe to hand from one actor to another.
extension SkipInterval: @retroactive @unchecked Sendable {}
extension SkipMarks: @retroactive @unchecked Sendable {}
extension SkipOffer: @retroactive @unchecked Sendable {}
extension SkipKind: @retroactive @unchecked Sendable {}

extension SkipInterval {
    convenience init(start: Double, end: Double) {
        self.init(startMs: start.mediaMilliseconds, endMs: end.mediaMilliseconds)
    }
    var start: Double { startMs.mediaSeconds }
    var end: Double { endMs.mediaSeconds }
    func contains(_ position: Double) -> Bool {
        let at = position.mediaMilliseconds
        return at >= startMs && at < endMs
    }
}

extension SkipMarks {
    /// Nothing known about this episode: no button, and nothing skips itself.
    static var empty: SkipMarks { SkipMarks.companion.NONE }

    /// Both halves through the shared sieve; what is implausible for this length is simply gone.
    func accepted(duration: Double) -> SkipMarks {
        SkipRules.shared.accept(marks: self, durationMs: duration.mediaMilliseconds)
    }
    /// The button to show at `position`, for its ten seconds of played video.
    func offer(position: Double, duration: Double) -> SkipOffer? {
        SkipRules.shared.offer(marks: self, positionMs: position.mediaMilliseconds, durationMs: duration.mediaMilliseconds)
    }
    /// Ten seconds into the ending and still inside it.
    func endingSkipDue(position: Double, duration: Double) -> Bool {
        SkipRules.shared.endingSkipDue(marks: self, positionMs: position.mediaMilliseconds, durationMs: duration.mediaMilliseconds)
    }
    /// Anywhere inside the ending.
    func insideEnding(position: Double, duration: Double) -> Bool {
        SkipRules.shared.insideEnding(marks: self, positionMs: position.mediaMilliseconds, durationMs: duration.mediaMilliseconds)
    }
}

// MARK: - One episode's progress (EpisodeProgressRules)

enum EpisodeRules {
    /// A minute in, or a fiftieth of the episode: watching rather than a mis-tap.
    static func started(position: Double, duration: Double) -> Bool {
        EpisodeProgressRules.shared.started(positionMs: position.mediaMilliseconds, durationMs: duration.mediaMilliseconds)
    }
    /// Enough behind the viewer to count the episode as watched; never while the length is unknown.
    static func watched(position: Double, duration: Double, threshold: Double) -> Bool {
        EpisodeProgressRules.shared.watched(positionMs: position.mediaMilliseconds, durationMs: duration.mediaMilliseconds,
                                            threshold: Float(threshold))
    }
    /// Where opening the episode again starts: from the top unless it was started and is not yet watched.
    static func resumePosition(position: Double, duration: Double, threshold: Double) -> Double {
        EpisodeProgressRules.shared.resumePosition(positionMs: position.mediaMilliseconds, durationMs: duration.mediaMilliseconds,
                                                   threshold: Float(threshold)).mediaSeconds
    }
}

extension EpisodeProgress {
    var started: Bool { EpisodeRules.started(position: position, duration: duration) }
    func isWatched(threshold: Double) -> Bool { EpisodeRules.watched(position: position, duration: duration, threshold: threshold) }
}

// MARK: - What comes next (NextEpisodeRules, CompletionRules)

enum EpisodeQueue {
    /// Shikimori's status word, read as Android reads it: anything unknown is a finished show.
    static func airing(_ status: String) -> AiringStatus {
        switch status {
        case "ongoing": .ongoing
        case "anons": .anons
        default: .released
        }
    }
    static func availableEpisodes(status: String, episodes: Int, episodesAired: Int) -> Int {
        Int(NextEpisodeRules.shared.availableEpisodes(status: airing(status), episodes: Int32(clamping: episodes),
                                                      episodesAired: Int32(clamping: episodesAired)))
    }
    static func hasNext(episode: Int, available: Int) -> Bool {
        NextEpisodeRules.shared.hasNextEpisode(episode: Int32(clamping: episode), availableEpisodes: Int32(clamping: available))
    }
    /// The last half minute, or the end itself: time to offer the next episode.
    static func nextDue(position: Double, duration: Double, ended: Bool) -> Bool {
        NextEpisodeRules.shared.nextEpisodeDue(positionMs: position.mediaMilliseconds, durationMs: duration.mediaMilliseconds, ended: ended)
    }
    /// Seconds left on the autoplay countdown, or nil while it is not due.
    static func countdown(position: Double, duration: Double, ended: Bool) -> Int? {
        NextEpisodeRules.shared.countdownSeconds(positionMs: position.mediaMilliseconds, durationMs: duration.mediaMilliseconds,
                                                 ended: ended)?.intValue
    }
    /// The announced count reached and nothing more on the schedule: worth offering «завершить».
    static func offerCompletion(episode: Int, announced: Int, nextEpisodeAt: Date?, now: Date) -> Bool {
        CompletionRules.shared.offerCompletion(episode: Int32(clamping: episode), announcedEpisodes: Int32(clamping: announced),
                                               nextEpisodeAtMs: nextEpisodeAt.map { KotlinLong(value: $0.syncMilliseconds) },
                                               nowMs: now.syncMilliseconds)
    }
}

// MARK: - Which voice (TranslationRanker, TranslationUsage)

enum TranslationPreference {
    /// The studios the app ships with, as the opening guess for a viewer with no history.
    static var defaultStudios: [String] { TranslationRanker.shared.DEFAULT_STUDIOS }

    /// Track id → how many anime remember it, out of each anime's remembered track.
    static func usage(_ remembered: [Int: Int]) -> [Int: Int] {
        let rows = remembered.map { RememberedTrack(animeId: Int32(clamping: $0.key), translationId: $0.value.kotlin) }
        return KotlinNumbers.ints(TranslationUsage.shared.of(remembered: rows))
    }

    /// The tracks, best first — the order the chooser shows and the player picks from.
    static func ranked(_ available: [Translation], remembered: Int?, studios: [String], usage: [Int: Int]) -> [Translation] {
        order(available, remembered: remembered, studios: studios, usage: usage).map { available[$0] }
    }

    /// Known not to carry the episode. The catalogue's count is all this app reads of a track, and
    /// only against the first season's numbering, which is the only one it plays.
    static func lacks(_ track: Translation, episode: Int) -> Bool {
        TranslationRanker.shared.lacksEpisode(episode: Int32(clamping: episode), listedEpisodes: nil, season: 1,
                                              episodesCount: track.episodes > 0 ? track.episodes.kotlin : nil)
    }

    /// The track to play: the head of the ranking, or — when it is known to lack the episode — the
    /// next one down that does not, as Android stands one in. Zero when none can.
    static func pick(_ available: [Translation], episode: Int, remembered: Int?, studios: [String], usage: [Int: Int]) -> Int {
        order(available, remembered: remembered, studios: studios, usage: usage)
            .lazy.map { available[$0] }.first { !lacks($0, episode: episode) }?.id ?? 0
    }

    private static func order(_ available: [Translation], remembered: Int?, studios: [String], usage: [Int: Int]) -> [Int] {
        let candidates = available.map { track in
            TranslationCandidate(id: Int32(clamping: track.id), title: track.title,
                                 kind: track.kind == "subtitles" ? .subtitles : .voice,
                                 episodesCount: track.episodes > 0 ? track.episodes.kotlin : nil)
        }
        let counts = Dictionary(uniqueKeysWithValues: usage.map { ($0.key.kotlin, $0.value.kotlin) })
        return KotlinNumbers.ints(TranslationRanker.shared.order(tracks: candidates, preferred: studios,
                                                                 rememberedId: remembered?.kotlin, usage: counts))
    }
}
