import Foundation

struct DownloadPolicies: Codable, Equatable {
    var wifiOnly = true
    var storageLimitBytes: Int64? = 5 * 1024 * 1024 * 1024
    var deleteWatched = false
    /// Zero selects the highest available quality; explicit enqueue quality takes precedence.
    var quality = 720
    static let fallbackEstimate: Int64 = 400 * 1024 * 1024

    func fits(used: Int64, additional: Int64) -> Bool {
        guard let limit = storageLimitBytes else { return true }
        return used >= 0 && additional >= 0 && used <= limit && additional <= limit - used
    }

    init() {}
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        wifiOnly = try values.decodeIfPresent(Bool.self, forKey: .wifiOnly) ?? true
        storageLimitBytes = values.contains(.storageLimitBytes) ? try values.decodeIfPresent(Int64.self, forKey: .storageLimitBytes) : 5 * 1024 * 1024 * 1024
        deleteWatched = try values.decodeIfPresent(Bool.self, forKey: .deleteWatched) ?? false
        quality = try values.decodeIfPresent(Int.self, forKey: .quality) ?? 720
    }
    // Encode nil explicitly so “unlimited” survives a round trip.
    func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(wifiOnly, forKey: .wifiOnly)
        try values.encode(storageLimitBytes, forKey: .storageLimitBytes)
        try values.encode(deleteWatched, forKey: .deleteWatched)
        try values.encode(quality, forKey: .quality)
    }
    private enum CodingKeys: String, CodingKey { case wifiOnly, storageLimitBytes, deleteWatched, quality }
}

enum DownloadState: String, Codable {
    case queued, resolving, downloading, paused, waitingForWiFi, waitingForNetwork, failed, completed, cancelled, removing
    var title: String {
        switch self {
        case .queued: "В очереди"
        case .resolving: "Получение ссылки"
        case .downloading: "Загрузка"
        case .paused: "Приостановлено"
        case .waitingForWiFi: "Ожидание Wi-Fi"
        case .waitingForNetwork: "Ожидание сети"
        case .failed: "Не удалось скачать"
        case .completed: "Загружено"
        case .cancelled: "Отменено"
        case .removing: "Удаление"
        }
    }
    var isPending: Bool { [.queued, .resolving, .downloading, .waitingForWiFi, .waitingForNetwork].contains(self) }
}

enum DownloadFailure: String, Codable {
    case expiredLink, network, noSpace, storageLimit, missingFile, unavailable, unknown
    var message: String {
        switch self {
        case .expiredLink: "Ссылка устарела. Попробуйте позже."
        case .network: "Нет связи. Загрузка продолжится после подключения."
        case .noSpace: "На устройстве недостаточно места."
        case .storageLimit: "Достигнут лимит загрузок. Удалите файлы или увеличьте лимит."
        case .missingFile: "Файл удалён с устройства. Скачайте эпизод снова."
        case .unavailable: "Выбранная озвучка или качество недоступны."
        case .unknown: "Не удалось скачать. Попробуйте ещё раз."
        }
    }
}

struct OfflineEpisode: Codable, Hashable {
    var animeID: Int
    var episode: Int
}

struct DownloadEntry: Codable, Identifiable {
    var anime: Anime
    var episode: Int
    var translation: Int
    var quality: Int
    var id: String { "\(anime.id):\(episode):\(translation):\(quality)" }
    var state: DownloadState = .queued
    var bytes: Int64 = 0
    var expectedBytes: Int64 = 0
    var estimatedBytes: Int64 = DownloadPolicies.fallbackEstimate
    var progress: Double = 0
    var relativePath: String?
    var isHLS = false
    /// Unique per attempt; stale callbacks cannot resurrect removed/replaced entries.
    var taskToken: String?
    var refreshAttempts: [Date] = []
    var failure: DownloadFailure?
    var deleteWhenReleased = false
    var updatedAt = Date()
    var episodeKey: OfflineEpisode { OfflineEpisode(animeID: anime.id, episode: episode) }
    var reservedBytes: Int64 {
        state == .completed || state == .cancelled || state == .failed ? bytes : max(bytes, max(expectedBytes, estimatedBytes))
    }
}

struct OfflineCatalog: Codable {
    var version = 1
    var entries: [DownloadEntry] = []
    var policies = DownloadPolicies()
    var transfers: [OfflineTransfer] = []
    init() {}
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        version = try values.decodeIfPresent(Int.self, forKey: .version) ?? 1
        guard version == 1 else { throw CocoaError(.coderReadCorrupt) }
        entries = try values.decodeIfPresent([DownloadEntry].self, forKey: .entries) ?? []
        policies = try values.decodeIfPresent(DownloadPolicies.self, forKey: .policies) ?? DownloadPolicies()
        transfers = try values.decodeIfPresent([OfflineTransfer].self, forKey: .transfers) ?? []
    }
}

struct OfflineCatalogStore {
    let directory: URL
    var fileURL: URL { directory.appendingPathComponent("catalog.json") }
    func load() throws -> OfflineCatalog {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return OfflineCatalog() }
        return try JSONDecoder().decode(OfflineCatalog.self, from: Data(contentsOf: fileURL))
    }
    func save(_ catalog: OfflineCatalog) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try JSONEncoder().encode(catalog).write(to: fileURL, options: .atomic)
        var directory = directory
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
        #if os(iOS)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: directory.path)
        try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: fileURL.path)
        #endif
    }
}

enum OfflineRules {
    static func quality(wanted: Int, available: [Int]) -> Int? {
        let valid = available.filter { $0 > 0 }
        guard wanted > 0 else { return valid.max() }
        return valid.filter { $0 <= wanted }.max() ?? valid.min()
    }

    static func reconcile(_ entries: [DownloadEntry], liveTokens: Set<String>, playableIDs: Set<String>) -> [DownloadEntry] {
        entries.map { original in
            var row = original
            let live = row.taskToken.map(liveTokens.contains) ?? false
            if row.state == .removing || row.state == .cancelled { return row }
            if !live && playableIDs.contains(row.id) {
                row.state = .completed; row.progress = 1; row.taskToken = nil; row.failure = nil
            } else if row.state == .completed && !playableIDs.contains(row.id) {
                row.state = .failed; row.failure = .missingFile; row.relativePath = nil; row.bytes = 0
            } else if !live {
                row.taskToken = nil
                if row.state.isPending { row.state = .queued }
            }
            return row
        }
    }

    /// Which pending rows get the transfer slots, in the order they should be started.
    ///
    /// Two at a time, because a phone downloading an episode is also playing one, and a third
    /// transfer buys nothing but contention. Work already in flight keeps its slot: a row whose
    /// token the transfer layer still holds is cheaper to resume than a queued one is to start,
    /// and dropping it would throw away bytes already on disk. Everything else follows in the
    /// order the viewer queued it.
    ///
    /// A row that failed for want of a network is not offered a slot even while it is pending:
    /// it waits for connectivity to come back and clear the failure, so a tunnel cannot spin the
    /// queue against a network that is not there.
    static func admittedIDs(_ entries: [DownloadEntry], occupiedTokens: Set<String>, slots: Int = 2) -> [String] {
        let candidates = entries.filter { $0.state.isPending && $0.failure != .network }
        let live = candidates.filter { $0.taskToken.map(occupiedTokens.contains) ?? false }
        let waiting = candidates.filter { !($0.taskToken.map(occupiedTokens.contains) ?? false) }
        return (live + waiting).prefix(slots).map(\.id)
    }

    static func shouldRefresh(status: Int?, bytes: Int64) -> Bool { status == 403 || status == 410 || bytes > 0 }
    static func claimRefresh(_ entry: inout DownloadEntry, now: Date = Date()) -> Bool {
        entry.refreshAttempts.removeAll { now.timeIntervalSince($0) > 3600 }
        guard entry.refreshAttempts.count < 3 else { return false }
        entry.refreshAttempts.append(now)
        return true
    }
    static func markWatched(_ entry: inout DownloadEntry, enabled: Bool) {
        if enabled && entry.state == .completed { entry.deleteWhenReleased = true }
    }
    static func canDeleteWatched(_ entry: DownloadEntry, playing: OfflineEpisode?) -> Bool {
        entry.deleteWhenReleased && entry.episodeKey != playing
    }
}

struct OfflineTransfer: Codable {
    var token: String
    var isHLS: Bool
    var wifiOnly: Bool
    var headers: [String: String]
    var identifier: String { Self.prefix + (isHLS ? "hls." : "file.") + token }
    static var prefix: String { (Bundle.main.bundleIdentifier ?? "app.kaeru") + ".offline.v1." }
}

