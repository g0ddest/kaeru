import Foundation

struct EpisodeRelease: Equatable {
    var animeID: Int
    var title: String
    var episode: Int
    var aired: Int
    var titleURL: URL { URL(string: "kaeru://anime/\(animeID)")! }
    var watchURL: URL { URL(string: "kaeru://anime/\(animeID)?episode=\(episode)")! }
}

/// Android NewEpisodeRule parity: the highest seen episode is also a remembered pair.
/// Processing mutates the durable baseline BEFORE the caller posts any notifications.
struct EpisodeReleaseState: Codable {
    var known: [Int: Set<Int>] = [:]
    mutating func process(library: [LibraryItem], progress: [EpisodeProgress]) -> [EpisodeRelease] {
        var releases: [EpisodeRelease] = []
        for item in library where item.status == "watching" || item.status == "rewatching" {
            let available = item.anime.availableEpisodes
            guard available > 0 else { continue }
            let id = item.anime.id
            let pairs = known[id] ?? []
            guard let seen = pairs.max() else { known[id] = [available]; continue }
            guard available > seen else { continue }
            known[id, default: []].insert(available)
            guard available > item.episodes else { continue }
            let next = item.episodes + 1
            guard !pairs.contains(next) else { continue }
            known[id, default: []].insert(next)
            guard !progress.contains(where: { $0.animeID == id && $0.episode == next && $0.position.isFinite && ($0.position >= 60 || ($0.duration.isFinite && $0.duration > 0 && $0.position / $0.duration >= 0.02)) }) else { continue }
            releases.append(EpisodeRelease(animeID: id, title: item.anime.title, episode: next, aired: available))
        }
        return releases
    }
}
