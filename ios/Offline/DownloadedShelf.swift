import Foundation

/// «Скачано»: what is on the device and still worth watching.
///
/// A port of `HomeFeedBuilder`'s `FeedKind.DOWNLOADED` row. Three rules, and each of them is about
/// somebody on a train:
///
/// - Every list status, not only «Смотрю». A show marked «Завершено» whose finale is downloaded is
///   still a finale somebody can watch without a network.
/// - Newest download first. That is the order the viewer put them there in, and these episodes have
///   no other relationship to each other.
/// - An episode already behind the viewer is left out, either way it can be behind: counted by
///   Shikimori, or finished on this device. An episode with no position at all is behind nobody —
///   that is exactly what this row is for.
///
/// Android drops a download whose title is in no list, because there it has no artwork and no name
/// to draw a card with. Here a download carries its own title, so it is shown: the point of the
/// shelf is that it works with nothing else available.
enum DownloadedShelf {
    struct Item: Identifiable, Hashable {
        var anime: Anime
        var episode: Int
        var id: String { "\(anime.id):\(episode)" }
    }

    /// - Parameter counted: how many episodes of a title the list says are watched.
    /// - Parameter progress: where this device left a given episode, if anywhere.
    static func build(entries: [DownloadEntry],
                      counted: (Int) -> Int,
                      progress: (Int, Int) -> EpisodeProgress?,
                      threshold: Double) -> [Item] {
        var seen = Set<String>()
        return entries.filter { $0.state == .completed }
            .sorted { $0.updatedAt > $1.updatedAt }
            .compactMap { entry -> Item? in
                let item = Item(anime: entry.anime, episode: entry.episode)
                // One card per episode: the same episode downloaded in two dubs is one thing to
                // watch, and the newer of the two is the one the viewer last asked for.
                guard seen.insert(item.id).inserted else { return nil }
                guard entry.episode > counted(entry.anime.id) else { return nil }
                if let row = progress(entry.anime.id, entry.episode), row.isWatched(threshold: threshold) { return nil }
                return item
            }
    }
}
