import Foundation

struct EpisodeNotificationPlan: Equatable {
    static let prefix = "kaeru.episode."
    var animeID: Int
    var episode: Int
    var title: String
    var date: Date
    var id: String { "\(Self.prefix)\(animeID)" }
    var url: URL { URL(string: "kaeru://anime/\(animeID)?episode=\(episode)")! }

    static func make(anime: [Anime], now: Date = Date(), limit: Int = 60) -> [Self] {
        var seen = Set<Int>()
        return anime.compactMap { item -> Self? in
            guard let date = item.nextAirDate, date > now, item.id > 0 else { return nil }
            return Self(animeID: item.id, episode: max(1, item.episodesAired + 1), title: item.title, date: date)
        }.sorted { $0.date == $1.date ? $0.animeID < $1.animeID : $0.date < $1.date }
            .filter { seen.insert($0.animeID).inserted }.prefix(max(0, limit)).map { $0 }
    }
}
