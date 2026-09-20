import Foundation
import KaeruShared

@MainActor protocol AnimeService {
    func discover() async throws -> [Anime]
    func search(_ query: String) async throws -> [Anime]
    func details(_ id: Int) async throws -> Anime
    func library(_ userID: Int64, token: String) async throws -> [LibraryItem]
    func exchange(_ code: String) async throws -> Tokens
    func refresh(_ token: String) async throws -> Tokens
    func account(_ token: String) async throws -> Account
    func setRate(_ pending: PendingRate, userID: Int64, rateID: Int64, token: String) async throws -> LibraryItem
    func translations(_ id: Int) async throws -> [Translation]
    func resolve(_ id: Int, translation: Int, episode: Int) async throws -> Stream
    func seasonal(year: Int, season: String) async throws -> [Anime]
    func configureKodikToken(_ token: String)
}

extension AnimeService {
    func seasonal(year: Int, season: String) async throws -> [Anime] { try await discover() }
    func configureKodikToken(_ token: String) {}
}

/// A request the server answered with something other than success, carried as a number.
///
/// Whether a token has expired used to be decided by looking for «401» inside a message. The
/// message comes from the shared Kotlin module across the `NSError` bridge, so rewording one
/// sentence there — or localising it — would have stopped the token refresh happening at all, and
/// stopped it quietly: the viewer would simply be signed out at the next request.
struct ServiceFailure: LocalizedError {
    let status: Int
    /// The `error` field of an OAuth failure. `invalid_grant` is the one that means «sign in
    /// again» rather than «try again».
    let oauthError: String?
    let underlying: Error
    var errorDescription: String? { underlying.localizedDescription }

    /// The status behind an error, whichever side of the bridge it is on: already typed here, or
    /// still an `NSError` carrying the Kotlin exception that caused it.
    static func of(_ error: Error) -> Self? {
        if let typed = error as? Self { return typed }
        guard let failure = (error as NSError).userInfo["KotlinException"] as? ApiException else { return nil }
        return Self(status: Int(failure.status), oauthError: failure.oauthError, underlying: error)
    }
    /// Everything from the shared module, typed where it can be and passed through where it cannot.
    static func wrap(_ error: Error) -> Error { of(error) ?? error }
}

struct AppConfiguration {
    var clientID: String
    var proxyURL: String
    var togetherRelayURL: String = ""
    static var bundled: Self {
        let url = Bundle.main.url(forResource: "Configuration", withExtension: "plist")
        let values = url.flatMap { NSDictionary(contentsOf: $0) } ?? [:]
        return Self(clientID: values["SHIKIMORI_CLIENT_ID"] as? String ?? "", proxyURL: values["AUTH_PROXY_URL"] as? String ?? "", togetherRelayURL: values["TOGETHER_RELAY_URL"] as? String ?? "")
    }
    var canSignIn: Bool { !clientID.isEmpty && URL(string: proxyURL)?.scheme == "https" }
}

@MainActor final class SharedService: AnimeService {
    private let api: NativeApi
    init(configuration: AppConfiguration) { api = NativeApi(clientId: configuration.clientID, proxyUrl: configuration.proxyURL) }
    private func decode<T: Decodable>(_ type: T.Type, _ call: (@escaping @Sendable (String?, Error?) -> Void) -> Void) async throws -> T {
        let json: String = try await withCheckedThrowingContinuation { continuation in
            call { result, error in
                if let error { continuation.resume(throwing: ServiceFailure.wrap(error)) }
                else if let result { continuation.resume(returning: result) }
                else { continuation.resume(throwing: AppError.message("Сервер вернул пустой ответ.")) }
            }
        }
        try Task.checkCancellation()
        return try JSONDecoder().decode(type, from: Data(json.utf8))
    }
    func discover() async throws -> [Anime] { try await decode([Anime].self) { api.discover(completionHandler: $0) } }
    func seasonal(year: Int, season: String) async throws -> [Anime] { try await decode([Anime].self) { api.seasonal(year: Int32(year), season: season, completionHandler: $0) } }
    func configureKodikToken(_ token: String) { api.configureKodikToken(token: token) }
    func search(_ query: String) async throws -> [Anime] { try await decode([Anime].self) { api.search(query: query, completionHandler: $0) } }
    func details(_ id: Int) async throws -> Anime { try await decode(Anime.self) { api.details(animeId: Int32(id), completionHandler: $0) } }
    func library(_ userID: Int64, token: String) async throws -> [LibraryItem] { try await decode([LibraryItem].self) { api.library(userId: userID, accessToken: token, completionHandler: $0) } }
    func exchange(_ code: String) async throws -> Tokens { try await decode(Tokens.self) { api.exchange(code: code, completionHandler: $0) } }
    func refresh(_ token: String) async throws -> Tokens { try await decode(Tokens.self) { api.refresh(refreshToken: token, completionHandler: $0) } }
    func account(_ token: String) async throws -> Account { try await decode(Account.self) { api.account(accessToken: token, completionHandler: $0) } }
    func setRate(_ pending: PendingRate, userID: Int64, rateID: Int64, token: String) async throws -> LibraryItem {
        try await decode(LibraryItem.self) { api.setRate(animeId: Int32(pending.anime.id), userId: userID, rateId: rateID, status: pending.status, episodes: Int32(pending.episodes), accessToken: token, completionHandler: $0) }
    }
    func translations(_ id: Int) async throws -> [Translation] { try await decode([Translation].self) { api.translations(animeId: Int32(id), completionHandler: $0) } }
    func resolve(_ id: Int, translation: Int, episode: Int) async throws -> Stream { try await decode(Stream.self) { api.resolve(animeId: Int32(id), translationId: Int32(translation), episode: Int32(episode), completionHandler: $0) } }
}
