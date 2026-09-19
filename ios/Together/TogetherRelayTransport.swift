import Foundation

@MainActor final class TogetherRelayTransport: TogetherTransport {
    private let baseURL: String
    private var session: URLSession?
    private var socket: URLSessionWebSocketTask?
    init(baseURL: String) { self.baseURL = baseURL }
    func connect(_ invitation: TogetherInvitation, asHost: Bool) async throws {
        guard var url = URLComponents(string: baseURL), url.scheme == "wss", url.host != nil, url.user == nil, url.password == nil, url.query == nil, url.fragment == nil else { throw TogetherError.notConfigured }
        url.path = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        url.path = (url.path.isEmpty ? "" : "/" + url.path) + "/w/" + invitation.roomID
        guard let endpoint = url.url else { throw TogetherError.notConfigured }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 10
        configuration.timeoutIntervalForResource = 24 * 60 * 60
        configuration.httpShouldSetCookies = false
        let session = URLSession(configuration: configuration)
        let socket = session.webSocketTask(with: endpoint)
        socket.maximumMessageSize = TogetherCodec.maximumFrameBytes
        self.session = session; self.socket = socket
        socket.resume()
        // A WebSocket control ping confirms the upgrade without sending an application frame.
        try await togetherTimeout(10) {
            try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                    socket.sendPing { error in
                        if let error { continuation.resume(throwing: error) } else { continuation.resume() }
                    }
                }
            } onCancel: { socket.cancel(with: .goingAway, reason: nil) }
        }
    }
    func receive() async throws -> TogetherTransportEvent {
        guard let socket else { throw TogetherError.disconnected }
        do {
            while true {
                try Task.checkCancellation()
                let message = try await socket.receive()
                switch message {
                case .data(let bytes):
                    guard bytes.count <= TogetherCodec.maximumFrameBytes else { throw TogetherError.frameTooLarge }
                    return .frame(bytes)
                case .string(let value):
                    guard value.utf8.count <= 1024 else { throw TogetherError.frameTooLarge }
                    struct Control: Decodable { let type: String }
                    if (try? JSONDecoder().decode(Control.self, from: Data(value.utf8)).type) == "peer-left" { return .peerLeft }
                @unknown default: break
                }
            }
        } catch {
            switch socket.closeCode.rawValue {
            case 4409: throw TogetherError.roomFull
            case 4408: throw TogetherError.expired
            case 4413: throw TogetherError.frameTooLarge
            default: throw error
            }
        }
    }
    func send(_ frame: Data) async throws {
        guard let socket else { throw TogetherError.disconnected }
        try await socket.send(.data(frame))
    }
    func close() {
        socket?.cancel(with: .normalClosure, reason: nil)
        session?.invalidateAndCancel(); socket = nil; session = nil
    }
}
