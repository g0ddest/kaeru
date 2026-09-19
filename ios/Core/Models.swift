import Foundation

struct Anime: Codable, Identifiable, Hashable {
    var id: Int
    var title: String
    var originalTitle = ""
    var poster = ""
    var description = ""
    var episodes = 0
    var episodesAired = 0
    var status = ""
    var score = ""
    var year = ""
    var nextEpisodeAt = ""
    var kind: String? = nil
    var studios: [String]? = nil
    var availableEpisodes: Int { status == "released" ? max(episodes, episodesAired) : episodesAired }
    var nextAirDate: Date? { ISO8601DateFormatter().date(from: nextEpisodeAt) }
    var subtitle: String { [year, episodes > 0 ? "\(episodes) эп." : nil, score.isEmpty ? nil : "★ \(score)"].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ") }
    var plainDescription: String {
        description.replacingOccurrences(of: "\\[/?[^\\]]+\\]", with: "", options: .regularExpression)
            .replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
            .replacingOccurrences(of: "&quot;", with: "\"").replacingOccurrences(of: "&amp;", with: "&")
    }
}

struct LibraryItem: Codable, Identifiable, Hashable {
    var id: Int64
    var anime: Anime
    var status: String
    var episodes: Int
    var updatedAt: String? = nil
}

enum WatchStatus: String, CaseIterable, Identifiable {
    case watching, planned, completed, onHold = "on_hold", dropped, rewatching
    var id: String { rawValue }
    var title: String {
        switch self {
        case .watching: "Смотрю"
        case .planned: "В планах"
        case .completed: "Просмотрено"
        case .onHold: "Отложено"
        case .dropped: "Брошено"
        case .rewatching: "Пересматриваю"
        }
    }
}

struct Translation: Codable, Identifiable, Hashable { var id: Int; var title: String; var episodes: Int; var kind: String? = nil }
struct StreamURL: Codable, Hashable { var quality: Int; var url: String }
struct Stream: Codable { var urls: [StreamURL]; var headers: [String: String]; var translation: Translation; var episode: Int }
struct Account: Codable, Equatable { var id: Int64; var nickname: String; var avatar: String }
struct Tokens: Codable {
    var access_token: String
    var refresh_token: String
    var expires_in: Double
    var created_at: Double?
    var expiresAt: Date { Date(timeIntervalSince1970: created_at ?? 0).addingTimeInterval(expires_in) }
}
struct Session: Codable { var account: Account; var tokens: Tokens }

struct EpisodeProgress: Codable, Equatable {
    var animeID: Int
    var episode: Int
    var position: Double
    var duration: Double
    var updatedAt = Date()
    var watched: Bool { duration.isFinite && duration > 0 && position.isFinite && position >= duration * 0.9 }
}

struct PendingRate: Codable, Identifiable, Equatable {
    var id: Int { anime.id }
    var anime: Anime
    var status: String
    var episodes: Int
    var revision = UUID()
}

/// What one account's list looks like, as one record.
///
/// Three of its six fields are read but never written any more: positions, the episodes they belong
/// to and the titles behind them each live in a record of their own, so five seconds of playback
/// costs one small write instead of re-encoding the whole library. They are still decoded, because
/// a phone that was upgraded rather than installed has all of it in here — see
/// `AppModel.restoreAccount`, which takes such a snapshot apart once and never writes it whole again.
struct AccountSnapshot: Codable {
    var library: [LibraryItem] = []
    var pending: [PendingRate] = []
    var progress: [Int: EpisodeProgress] = [:]
    var recent: [Int: Anime] = [:]
    var episodeHistory: [String: EpisodeProgress] = [:]
    var translations: [Int: Int] = [:]

    init(library: [LibraryItem] = [], pending: [PendingRate] = [], progress: [Int: EpisodeProgress] = [:], recent: [Int: Anime] = [:], episodeHistory: [String: EpisodeProgress] = [:], translations: [Int: Int] = [:]) {
        self.library = library; self.pending = pending; self.progress = progress; self.recent = recent
        self.episodeHistory = episodeHistory; self.translations = translations
    }
    private enum CodingKeys: String, CodingKey { case library, pending, progress, recent, episodeHistory, translations }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        library = try values.decodeIfPresent([LibraryItem].self, forKey: .library) ?? []
        pending = try values.decodeIfPresent([PendingRate].self, forKey: .pending) ?? []
        progress = try values.decodeIfPresent([Int: EpisodeProgress].self, forKey: .progress) ?? [:]
        recent = try values.decodeIfPresent([Int: Anime].self, forKey: .recent) ?? [:]
        episodeHistory = try values.decodeIfPresent([String: EpisodeProgress].self, forKey: .episodeHistory) ?? [:]
        translations = try values.decodeIfPresent([Int: Int].self, forKey: .translations) ?? [:]
        for value in progress.values where episodeHistory["\(value.animeID):\(value.episode)"] == nil {
            episodeHistory["\(value.animeID):\(value.episode)"] = value
        }
    }
    /// Only the three fields that are still this record's own. What playback writes every few
    /// seconds is not among them, which is the whole reason this method is written out by hand.
    func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(library, forKey: .library)
        try values.encode(pending, forKey: .pending)
        try values.encode(translations, forKey: .translations)
    }
}

enum AppError: LocalizedError {
    case message(String), invalidCallback, signedOut, missingConfiguration
    var errorDescription: String? {
        switch self {
        case .message(let message): message
        case .invalidCallback: "Не удалось проверить вход. Попробуйте войти ещё раз."
        case .signedOut: "Сессия завершена. Войдите снова."
        case .missingConfiguration: "В этой сборке не настроен вход в Shikimori."
        }
    }
}

struct OAuthAttempt {
    private var pendingState: String?
    mutating func begin(state: String) { pendingState = state }
    mutating func cancel() { pendingState = nil }
    mutating func consume(_ url: URL) throws -> String {
        let expected = pendingState
        pendingState = nil
        guard let expected, let parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              parts.scheme == "kaeru", parts.host == "oauth", parts.path.isEmpty,
              parts.user == nil, parts.password == nil, parts.port == nil, parts.fragment == nil else { throw AppError.invalidCallback }
        let codes = parts.queryItems?.filter { $0.name == "code" } ?? []
        let states = parts.queryItems?.filter { $0.name == "state" } ?? []
        guard codes.count == 1, states.count == 1, states[0].value == expected,
              let code = codes[0].value, !code.isEmpty else { throw AppError.invalidCallback }
        return code
    }
}
