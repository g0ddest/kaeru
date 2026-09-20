import Foundation

/// Two phones on different networks, talking through a stranger's machine.
///
/// The room is the path — `wss://<relay>/w/<roomId>` — and there is no join frame: the URL already
/// said which room this is, and a protocol with one has a state before the connection is usable,
/// which is one more thing to get wrong on every reconnect.
///
/// A dropped socket is not the end of an evening. It is [TogetherRelayBackoff] and a handful of
/// attempts over half a minute, with what could not be sent meanwhile kept and written when the
/// socket comes back. The one thing that is the end is the relay saying so: three of its close
/// codes are answers rather than accidents, and dialling again would only get the same one.
@MainActor final class TogetherRelayTransport: TogetherTransport {
    private let baseURL: String
    private var endpoint: URL?
    private var session: URLSession?
    private var socket: URLSessionWebSocketTask?
    private var backoff = TogetherRelayBackoff()
    private let buffer = TogetherSendBuffer()
    private var reconnecting = false
    private var closed = false

    init(baseURL: String) { self.baseURL = baseURL }

    func connect(_ invitation: TogetherInvitation, asHost: Bool) async throws {
        guard var url = URLComponents(string: baseURL), url.scheme == "wss", url.host != nil, url.user == nil, url.password == nil, url.query == nil, url.fragment == nil else { throw TogetherError.notConfigured }
        url.path = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        url.path = (url.path.isEmpty ? "" : "/" + url.path) + "/w/" + invitation.roomID
        guard let endpoint = url.url else { throw TogetherError.notConfigured }
        self.endpoint = endpoint
        closed = false; reconnecting = false; backoff.reset(); buffer.clear()
        TogetherLog.write("connect room=\(invitation.roomID) as=\(asHost ? "host" : "guest") host=\(url.host ?? "?")")
        // The first dial answers for itself rather than going into the backoff: somebody who has
        // just pressed «создать комнату» with no network deserves to be told so now, not after
        // half a minute of a spinner. Everything after this one is an outage, and outages wait.
        try await dial(endpoint)
    }

    private func dial(_ endpoint: URL) async throws {
        dropSocket()
        let configuration = URLSessionConfiguration.ephemeral
        // No read deadline on the socket itself, exactly as Android's client says of its own:
        // a room with one person in it has no incoming traffic at all, and ten seconds of that
        // is normal — it is somebody who has just pressed «Смотреть вместе» and is copying the
        // link. With a request timeout on it the task failed on that silence, the session went to
        // «Восстанавливаем связь», dialled, and failed again ten seconds later, forever. The
        // first dial still answers within ten seconds, because that deadline is put on the
        // handshake below rather than on the life of the socket.
        configuration.timeoutIntervalForRequest = TogetherTiming.socketLifetimeSeconds
        configuration.timeoutIntervalForResource = TogetherTiming.socketLifetimeSeconds
        configuration.httpShouldSetCookies = false
        let session = URLSession(configuration: configuration)
        let socket = session.webSocketTask(with: endpoint)
        socket.maximumMessageSize = TogetherCodec.maximumFrameBytes
        self.session = session; self.socket = socket
        socket.resume()
        do {
            // A WebSocket control ping confirms the upgrade without sending an application frame.
            try await togetherTimeout(TogetherTiming.dialSeconds) {
                try await withTaskCancellationHandler {
                    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                        socket.sendPing { error in
                            if let error { continuation.resume(throwing: error) } else { continuation.resume() }
                        }
                    }
                } onCancel: { socket.cancel(with: .goingAway, reason: nil) }
            }
        } catch {
            // A relay that refuses the room says so in the close code, not in the error.
            TogetherLog.write("dial failed close=\(socket.closeCode.rawValue) error=\(String(describing: error))")
            if let refusal = TogetherRelayClose.refusal(for: socket.closeCode.rawValue) { throw refusal }
            throw error
        }
        TogetherLog.write("dial ok")
    }

    func receive() async throws -> TogetherTransportEvent {
        if reconnecting { return try await redial() }
        guard let socket else { throw TogetherError.disconnected }
        do { return try await listen(socket) }
        catch is CancellationError { throw CancellationError() }
        catch {
            if let refusal = TogetherRelayClose.refusal(for: socket.closeCode.rawValue) { throw refusal }
            if let value = error as? TogetherError, value == .frameTooLarge { throw value }
            guard !closed else { throw TogetherError.disconnected }
            // Said before the waiting starts, so the screen can stop claiming the friend is there
            // while this side dials. The wait itself happens in the next call.
            TogetherLog.write("socket lost close=\(socket.closeCode.rawValue) error=\(String(describing: error)); reconnecting")
            reconnecting = true
            return .reconnecting
        }
    }

    private func listen(_ socket: URLSessionWebSocketTask) async throws -> TogetherTransportEvent {
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
                // Binary is the friend, text is the relay, and the relay has one thing to say.
                if (try? JSONDecoder().decode(Control.self, from: Data(value.utf8)).type) == "peer-left" {
                    TogetherLog.write("relay says peer-left")
                    return .peerLeft
                }
                TogetherLog.write("relay text \(value.prefix(120))")
            @unknown default: break
            }
        }
    }

    private func redial() async throws -> TogetherTransportEvent {
        guard let endpoint, !closed else { throw TogetherError.disconnected }
        while let wait = backoff.next() {
            try await Task.sleep(for: .milliseconds(wait))
            guard !closed else { throw TogetherError.disconnected }
            do {
                try await dial(endpoint)
                // A socket that worked is a fresh start: the half minute is per outage, not per
                // evening, or a long one would run out of it.
                backoff.reset()
                // What was said while the socket was away goes out first, oldest first, and
                // `reconnecting` stays set until it has — anything said meanwhile queues behind
                // it rather than in front of actions the viewer took first. A socket that dies
                // under the backlog keeps the rest, in order, and is dialled again rather than
                // reported as back.
                guard let socket else { continue }
                let emptied = await buffer.flush { try await socket.send(.data($0)) }
                guard emptied else {
                    TogetherLog.write("the new socket died under the backlog; \(buffer.frames.count) frames kept for the next")
                    continue
                }
                reconnecting = false
                return .reconnected
            } catch is CancellationError {
                throw CancellationError()
            } catch let error as TogetherError where TogetherRelayClose.refuses(error) {
                throw error
            } catch {
                continue
            }
        }
        throw TogetherError.disconnected
    }

    func send(_ frame: Data) async throws {
        guard !closed, endpoint != nil else { throw TogetherError.disconnected }
        guard !reconnecting, let socket else { buffer.append(frame); return }
        do { try await socket.send(.data(frame)) }
        catch {
            // Kept rather than thrown: a pause pressed as the train enters a tunnel is a pause the
            // viewer meant, and noticing the dead socket is the receiving side's job.
            buffer.append(frame)
        }
    }

    func close() {
        closed = true
        buffer.clear()
        endpoint = nil
        dropSocket()
    }

    private func dropSocket() {
        socket?.cancel(with: .normalClosure, reason: nil)
        session?.invalidateAndCancel()
        socket = nil; session = nil
    }
}
