import Foundation

/// The worker's `/sync` document (infra/relay/src/sync.ts; spec 2026-09-26-kaeru-sync-design.md §2):
/// per anime id, where each episode stopped, the chosen dub and a tombstone for a finished title.
/// Every `at` is the device's own clock in milliseconds, and the newer one wins, field by field and
/// episode by episode. `secret` («Смотреть украдкой») is not read here yet.
struct SyncPosition: Codable, Equatable {
    /// Where the episode stopped and how long it is, in milliseconds.
    var p: Int64
    var d: Int64
    var at: Int64
}

struct SyncDub: Codable, Equatable {
    var id: Int
    var title: String
    var at: Int64
}

struct SyncTitle: Codable, Equatable {
    var dub: SyncDub?
    var eps: [String: SyncPosition]?
    var gone: Int64?
    init(dub: SyncDub? = nil, eps: [String: SyncPosition]? = nil, gone: Int64? = nil) {
        self.dub = dub; self.eps = eps; self.gone = gone
    }
    var isEmpty: Bool { dub == nil && gone == nil && (eps?.isEmpty ?? true) }
}

typealias SyncTitles = [String: SyncTitle]

enum SyncError: Error, Equatable {
    case offline, throttled, unavailable, parameters, parser, unknown
}

extension Date {
    /// This moment as the worker counts it.
    var syncMilliseconds: Int64 { Int64((timeIntervalSince1970 * 1000).rounded()) }
    init(syncMilliseconds value: Int64) { self.init(timeIntervalSince1970: Double(value) / 1000) }
}

enum SyncWire {
    /// What a POST carries. Absent fields are left out rather than sent as null: the worker takes a
    /// null `dub` for a malformed one and refuses the whole batch.
    static func body(_ titles: SyncTitles) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        return try encoder.encode(["titles": titles])
    }

    /// Whatever of the document this build can read; a title or field it cannot is skipped, as the
    /// web client does. A 200 without `titles` means the worker and the app disagree on the shape.
    static func titles(_ data: Data) throws -> SyncTitles {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let titles = root["titles"] as? [String: Any] else { throw SyncError.parser }
        var read: SyncTitles = [:]
        for (id, value) in titles {
            guard id.range(of: #"^\d{1,9}$"#, options: .regularExpression) != nil,
                  let source = value as? [String: Any] else { continue }
            var title = SyncTitle()
            if let dub = source["dub"] as? [String: Any], let identifier = integer(dub["id"]),
               let name = dub["title"] as? String, let at = integer(dub["at"]) {
                title.dub = SyncDub(id: Int(identifier), title: name, at: at)
            }
            if let eps = source["eps"] as? [String: Any] {
                var positions: [String: SyncPosition] = [:]
                for (episode, raw) in eps {
                    guard episode.range(of: #"^\d{1,5}$"#, options: .regularExpression) != nil,
                          let position = raw as? [String: Any], let p = integer(position["p"]),
                          let d = integer(position["d"]), let at = integer(position["at"]) else { continue }
                    positions[episode] = SyncPosition(p: p, d: d, at: at)
                }
                if !positions.isEmpty { title.eps = positions }
            }
            if let gone = integer(source["gone"]) { title.gone = gone }
            read[id] = title
        }
        return read
    }

    /// A JSON number, whole or not; a boolean is not one even though Foundation bridges it so.
    private static func integer(_ value: Any?) -> Int64? {
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
        let double = number.doubleValue
        guard double.isFinite, abs(double) < 9e15 else { return nil }
        return Int64(double.rounded())
    }
}

/// One request out, its status and body back. URLSession in the app; a script in the tests.
@MainActor protocol SyncTransport: AnyObject {
    func send(_ request: URLRequest) async throws -> (Int, Data)
}

@MainActor final class URLSessionSyncTransport: SyncTransport {
    private let session: URLSession
    init(session: URLSession = .shared) { self.session = session }
    func send(_ request: URLRequest) async throws -> (Int, Data) {
        let (data, response) = try await session.data(for: request)
        return ((response as? HTTPURLResponse)?.statusCode ?? 0, data)
    }
}

/// `GET /sync` and `POST /sync` on the relay with the Shikimori bearer. The relay is configured as
/// the `wss://` address rooms use; the same worker answers `https://` on the same host.
@MainActor struct SyncClient {
    let endpoint: URL
    let transport: any SyncTransport

    init?(relayURL: String, transport: any SyncTransport) {
        guard var parts = URLComponents(string: relayURL), let scheme = parts.scheme, parts.host?.isEmpty == false else { return nil }
        switch scheme {
        case "wss", "https": parts.scheme = "https"
        case "ws", "http": parts.scheme = "http"
        default: return nil
        }
        parts.path = "/sync"; parts.query = nil; parts.fragment = nil
        guard let url = parts.url else { return nil }
        endpoint = url; self.transport = transport
    }

    func get(token: String) async throws -> SyncTitles { try await call(token: token, body: nil) }

    /// Sends a batch; the answer is the whole merged document.
    func post(_ titles: SyncTitles, token: String) async throws -> SyncTitles {
        try await call(token: token, body: SyncWire.body(titles))
    }

    private func call(token: String, body: Data?) async throws -> SyncTitles {
        var request = URLRequest(url: endpoint, timeoutInterval: 20)
        request.httpMethod = body == nil ? "GET" : "POST"
        // The bearer and the content type and nothing else: the worker's preflight allows only those.
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = body
        }
        let status: Int, data: Data
        do { (status, data) = try await transport.send(request) }
        catch is CancellationError { throw CancellationError() }
        catch { throw SyncError.offline }
        guard (200...299).contains(status) else { throw Self.refusal(status, data) }
        return try SyncWire.titles(data)
    }

    /// A non-2xx answer. The 401 goes out as the `ServiceFailure` the app's token refresh looks for.
    private static func refusal(_ status: Int, _ data: Data) -> Error {
        let code = ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["error"] as? String
        if status == 401 || code == "sign_in" {
            return ServiceFailure(status: 401, oauthError: nil, underlying: AppError.signedOut)
        }
        if status == 429 { return SyncError.throttled }
        if code == "unavailable" { return SyncError.unavailable }
        if code == "parameters" { return SyncError.parameters }
        return SyncError.unknown
    }
}
