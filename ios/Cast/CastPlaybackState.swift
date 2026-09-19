import Foundation

struct CastSelection: Codable, Equatable {
    var anime: Anime
    var episode: Int
    var translation: Int
    var quality: Int
}

struct CastHandoff: Equatable {
    var selection: CastSelection
    var position: TimeInterval
    var shouldPlay: Bool
}

enum CastConnection: Equatable {
    case disconnected, connecting, connected(String), suspended(String)
    var isConnected: Bool { if case .connected = self { true } else { false } }
    var receiverName: String? {
        switch self { case .connected(let name), .suspended(let name): name; default: nil }
    }
}

enum CastFailure: Error, LocalizedError, Equatable {
    case notConnected, unavailableSource, loadFailed, timedOut, commandFailed, connectionFailed
    var errorDescription: String? {
        switch self {
        case .notConnected: "Подключитесь к устройству Chromecast."
        case .unavailableSource: "Для этой серии нет доступного видео на Chromecast."
        case .loadFailed: "Chromecast не смог открыть видео. Попробуйте ещё раз."
        case .timedOut: "Chromecast не ответил за 20 секунд. Проверьте соединение."
        case .commandFailed: "Не удалось выполнить команду на Chromecast."
        case .connectionFailed: "Соединение с Chromecast потеряно."
        }
    }
}

/// The receiver fetches this URL itself. Local playback headers must never be serialized here.
struct CastLoadPayload: Encodable {
    let selection: CastSelection
    let url: URL
    let title: String
    let subtitle: String
    let artwork: URL?
    let position: TimeInterval
    let autoplay: Bool
    let contentType = "application/x-mpegURL"

    init(anime: Anime, stream: Stream, quality: Int, position: TimeInterval, autoplay: Bool) throws {
        let playable = stream.urls.filter { Self.remoteURL($0.url) != nil }
        guard stream.episode > 0,
              let source = playable.first(where: { $0.quality == quality })
                ?? playable.max(by: { $0.quality < $1.quality }),
              let url = Self.remoteURL(source.url) else { throw CastFailure.unavailableSource }
        self.selection = CastSelection(anime: anime, episode: stream.episode, translation: stream.translation.id, quality: source.quality)
        self.url = url
        title = anime.title
        subtitle = "\(stream.episode) серия   \(stream.translation.title)"
        artwork = Self.remoteURL(anime.poster)
        self.position = Self.validTime(position)
        self.autoplay = autoplay
    }

    static func remoteURL(_ value: String) -> URL? {
        guard let url = URL(string: value), ["http", "https"].contains(url.scheme?.lowercased() ?? ""),
              let host = url.host, !host.isEmpty, url.user == nil, url.password == nil else { return nil }
        return url
    }

    static func validTime(_ value: TimeInterval) -> TimeInterval { value.isFinite ? max(0, value) : 0 }
}

enum CastRemotePhase { case unknown, loading, buffering, playing, paused, finished, stopped, failed }

struct CastRemoteStatus {
    var contentID: String?
    var position: TimeInterval
    var duration: TimeInterval
    var phase: CastRemotePhase
}

struct CastPlaybackState {
    private(set) var selection: CastSelection?
    var contentID: String?
    private(set) var position: TimeInterval = 0
    private(set) var duration: TimeInterval = 0
    private(set) var isPlaying = false
    private(set) var isBuffering = false
    private(set) var ended = false
    private(set) var shouldPlay = false
    var error: CastFailure?

    var handoff: CastHandoff? {
        selection.map { CastHandoff(selection: $0, position: position, shouldPlay: shouldPlay && !ended) }
    }

    mutating func begin(_ handoff: CastHandoff) {
        self = CastPlaybackState()
        selection = handoff.selection
        position = CastLoadPayload.validTime(handoff.position)
        shouldPlay = handoff.shouldPlay
        isBuffering = true
    }

    mutating func setIntent(playing: Bool) { shouldPlay = playing }

    mutating func fail(_ failure: CastFailure) { error = failure; isBuffering = false; isPlaying = false }

    /// Ignore the preceding episode's asynchronous status during a source replacement.
    @discardableResult mutating func update(_ status: CastRemoteStatus) -> Bool {
        guard let contentID, contentID == status.contentID else { return false }
        if status.position.isFinite { position = max(0, status.position) }
        duration = CastLoadPayload.validTime(status.duration)
        if duration > 0 { position = min(position, duration) }
        isPlaying = status.phase == .playing
        isBuffering = status.phase == .buffering || status.phase == .loading
        ended = status.phase == .finished
        switch status.phase {
        case .playing: shouldPlay = true
        case .paused, .finished, .stopped: shouldPlay = false
        default: break
        }
        return true
    }
}

/// SDK-independent boundary; the only implementation in the app uses GoogleCast directly.
@MainActor protocol CastTransport: AnyObject {
    var onConnection: ((CastConnection) -> Void)? { get set }
    var onStatus: ((CastRemoteStatus) -> Void)? { get set }
    var onFailure: ((CastFailure) -> Void)? { get set }
    func start()
    func load(_ payload: CastLoadPayload, completion: @escaping (Result<Void, CastFailure>) -> Void)
    func play()
    func pause()
    func seek(to position: TimeInterval)
    func stop()
    func disconnect()
    func presentDevices()
    func presentExpandedControls()
}
