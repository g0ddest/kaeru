import Foundation
import KaeruShared

enum SyncError: Error, Equatable {
    case offline, throttled, unavailable, parameters, parser, unknown
}

extension Date {
    /// This moment as the worker counts it.
    var syncMilliseconds: Int64 { Int64((timeIntervalSince1970 * 1000).rounded()) }
    init(syncMilliseconds value: Int64) { self.init(timeIntervalSince1970: Double(value) / 1000) }
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

    /// Sends a batch, written by the shared `SyncWire`; the answer is the document as it now
    /// stands for those titles.
    func post(_ titles: SyncTitles, token: String) async throws -> SyncTitles {
        try await call(token: token, body: Data(SyncWire.shared.body(titles: titles).utf8))
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
        // Read as far as it can be by the shared `SyncWire`. A 200 with no `titles` at all means
        // the worker and the app disagree on the shape.
        guard let text = String(data: data, encoding: .utf8), let titles = SyncWire.shared.titles(text: text) else {
            throw SyncError.parser
        }
        return titles
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
