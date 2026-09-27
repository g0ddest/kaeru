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
    /// Episodes there to watch: aired so far while it airs, the announced total once it is out,
    /// nothing for an announcement (the shared rule, as on Android).
    var availableEpisodes: Int { EpisodeQueue.availableEpisodes(status: status, episodes: episodes, episodesAired: episodesAired) }
    var nextAirDate: Date? { ISODate.parse(nextEpisodeAt, fractional: false) }
    /// Whether `episode` is the last one: the announced count reached, and no next episode on the
    /// schedule. Shikimori's announced count lags behind a show that got longer — 12 announced, a
    /// 13th in four days — and offering «завершить» there was wrong.
    func endsWith(_ episode: Int, now: Date = Date()) -> Bool {
        EpisodeQueue.offerCompletion(episode: episode, announced: episodes, nextEpisodeAt: nextAirDate, now: now)
    }
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
    /// «Смотреть украдкой»: not a Shikimori status. The title is watched on this device (and, with
    /// sync, the viewer's others) and nothing about it is written to Shikimori. Last on purpose.
    case secret
    var id: String { rawValue }
    var title: String {
        switch self {
        case .watching: "Смотрю"
        case .planned: "В планах"
        case .completed: "Просмотрено"
        case .onHold: "Отложено"
        case .dropped: "Брошено"
        case .rewatching: "Пересматриваю"
        case .secret: "Украдкой"
        }
    }
    /// The line under the title where a menu has room for one.
    var hint: String? { self == .secret ? "Не отмечать на Shikimori" : nil }
}

/// A title watched «украдкой» (spec 2026-09-26-kaeru-sync-design.md §4): whether it is, how many
/// episodes were watched meanwhile, when that last changed (ms, for sync), and the title's card, so
/// «Мой список» can show it without Shikimori and without the network. Kept when switched off, so
/// the time of the switch can win over an older state from another device. Whether it is finished
/// is the shared rule's (Core/SharedSync.swift).
struct SecretTitle: Codable, Equatable {
    var on: Bool
    var watched: Int
    var at: Int64
    var anime: Anime?
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
    var watched: Bool { isWatched(threshold: 0.9) }
}

/// When a title's dub was chosen, and what it is called: what sync needs beside the id, which is
/// all the player ever used. At 0 for a choice made before sync existed, as on the web.
struct DubStamp: Codable, Equatable {
    var title: String
    var at: Int64
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
/// Three of its eight fields are read but never written any more: positions, the episodes they belong
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
    var dubs: [Int: DubStamp] = [:]
    var secrets: [Int: SecretTitle] = [:]

    init(library: [LibraryItem] = [], pending: [PendingRate] = [], progress: [Int: EpisodeProgress] = [:], recent: [Int: Anime] = [:], episodeHistory: [String: EpisodeProgress] = [:], translations: [Int: Int] = [:], dubs: [Int: DubStamp] = [:], secrets: [Int: SecretTitle] = [:]) {
        self.library = library; self.pending = pending; self.progress = progress; self.recent = recent
        self.episodeHistory = episodeHistory; self.translations = translations; self.dubs = dubs; self.secrets = secrets
    }
    private enum CodingKeys: String, CodingKey { case library, pending, progress, recent, episodeHistory, translations, dubs, secrets }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        library = try values.decodeIfPresent([LibraryItem].self, forKey: .library) ?? []
        pending = try values.decodeIfPresent([PendingRate].self, forKey: .pending) ?? []
        progress = try values.decodeIfPresent([Int: EpisodeProgress].self, forKey: .progress) ?? [:]
        recent = try values.decodeIfPresent([Int: Anime].self, forKey: .recent) ?? [:]
        episodeHistory = try values.decodeIfPresent([String: EpisodeProgress].self, forKey: .episodeHistory) ?? [:]
        translations = try values.decodeIfPresent([Int: Int].self, forKey: .translations) ?? [:]
        dubs = try values.decodeIfPresent([Int: DubStamp].self, forKey: .dubs) ?? [:]
        secrets = try values.decodeIfPresent([Int: SecretTitle].self, forKey: .secrets) ?? [:]
        for value in progress.values where episodeHistory["\(value.animeID):\(value.episode)"] == nil {
            episodeHistory["\(value.animeID):\(value.episode)"] = value
        }
    }
    /// Only the five fields that are still this record's own. What playback writes every few
    /// seconds is not among them, which is the whole reason this method is written out by hand.
    func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(library, forKey: .library)
        try values.encode(pending, forKey: .pending)
        try values.encode(translations, forKey: .translations)
        try values.encode(dubs, forKey: .dubs)
        try values.encode(secrets, forKey: .secrets)
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

/// Shikimori's timestamps, parsed by two formatters made once. A formatter is expensive to make, and
/// the home screen sorts its shelves by these dates on every redraw — a new one per comparison held
/// the main thread long enough for the Mac to call the app not responding while an episode played.
/// `ISO8601DateFormatter` is safe to share between threads (Apple documents it as thread-safe).
enum ISODate {
    nonisolated(unsafe) private static let plain = ISO8601DateFormatter()
    nonisolated(unsafe) private static let fractional: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions.insert(.withFractionalSeconds)
        return formatter
    }()
    static func parse(_ value: String, fractional allowFraction: Bool = true) -> Date? {
        if let date = plain.date(from: value) { return date }
        return allowFraction ? fractional.date(from: value) : nil
    }
}
