import Foundation

struct ContinueTarget: Equatable {
    var episode: Int
    var position: Double
    var rewatch = false
    var canPlay: Bool

    static func resolve(anime: Anime, counted: Int, rewatching: Bool, progress: [EpisodeProgress], threshold: Double) -> Self {
        let started = progress.filter { $0.animeID == anime.id && $0.started }
        if let resume = started.filter({ $0.episode > counted && $0.episode <= anime.availableEpisodes && !$0.isWatched(threshold: threshold) }).max(by: { $0.episode < $1.episode }) {
            return Self(episode: resume.episode, position: resume.position, canPlay: true)
        }
        let finished = rewatching ? Set<Int>() : Set(started.filter { $0.isWatched(threshold: threshold) }.map(\.episode))
        var next = max(0, counted) + 1
        while finished.contains(next) { next += 1 }
        let ended = (anime.episodes > 0 && anime.availableEpisodes >= anime.episodes) || (anime.status == "released" && anime.availableEpisodes > 0)
        if ended && next > max(anime.episodes, anime.availableEpisodes) {
            return Self(episode: 1, position: 0, rewatch: true, canPlay: true)
        }
        return Self(episode: next, position: 0, canPlay: next <= anime.availableEpisodes)
    }
}

/// What `ContinueTarget.resolve` last said about a title, until something it was based on moves.
///
/// The home screen asks this question once for the shelf a card belongs in, once more for the
/// caption on it and once again for the hero — and it asks it of every card on screen, on every
/// frame of a scroll. Each answer walked the whole episode history, so a large library made
/// scrolling quadratic in it.
///
/// Keyed on more than the title's id: the same show arrives from the catalogue and from the list
/// with different counts of what has aired, and two answers to one id would hand a card the other
/// one's episode. Everything the rule reads about the title is in the key; everything it reads
/// about the viewer — the list, the history, the threshold — invalidates the whole cache, because
/// all three move rarely and together.
struct ContinueTargetCache {
    private struct Key: Hashable { let id: Int; let episodes: Int; let aired: Int; let status: String }
    private var values: [Key: ContinueTarget] = [:]
    private var threshold = Double.nan
    /// How many answers had to actually be worked out. Read by the tests, and by nothing else.
    private(set) var computed = 0

    mutating func invalidate() { values.removeAll(keepingCapacity: true) }

    mutating func target(for anime: Anime, threshold: Double, resolve: (Anime) -> ContinueTarget) -> ContinueTarget {
        if threshold != self.threshold { self.threshold = threshold; invalidate() }
        let key = Key(id: anime.id, episodes: anime.episodes, aired: anime.episodesAired, status: anime.status)
        if let known = values[key] { return known }
        let value = resolve(anime)
        computed += 1
        values[key] = value
        return value
    }
}

extension EpisodeProgress {
    var started: Bool { position >= 60 || (duration > 0 && position / duration >= 0.02) }
    func isWatched(threshold: Double) -> Bool {
        duration.isFinite && duration > 0 && position.isFinite && position >= duration * threshold
    }
}
