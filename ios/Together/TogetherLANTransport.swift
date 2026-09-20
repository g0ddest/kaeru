import Foundation
import Network
import Darwin

@MainActor final class TogetherLANTransport: TogetherTransport {
    private var listener: NWListener?
    private var socket: KaeruLANConnection?
    private var firstFrame: Data?
    private var candidates: [UUID: KaeruLANConnection] = [:]
    private var candidateTasks: [UUID: Task<Void, Never>] = [:]
    private var ready: CheckedContinuation<Void, Error>?
    private var accepted: CheckedContinuation<Void, Error>?
    private var invitation: TogetherInvitation?
    private var closed = false
    private var generation = UUID()

    func prepareHost() async throws -> TogetherLANEndpoint {
        guard let host = Self.localAddress() else { throw TogetherError.disconnected }
        let listener = try NWListener(using: .tcp, on: .any)
        self.listener = listener
        let fence = generation
        listener.stateUpdateHandler = { [weak self] state in
            Task { @MainActor in
                guard let self, self.generation == fence else { return }
                switch state {
                case .ready: self.finishReady(nil)
                case .failed, .cancelled: self.finishReady(TogetherError.disconnected); self.finishAccept(TogetherError.disconnected)
                default: break
                }
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            Task { @MainActor in
                guard let self, self.generation == fence else { connection.cancel(); return }
                self.vet(connection)
            }
        }
        try await togetherTimeout(5) {
            try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { continuation in
                    self.ready = continuation
                    listener.start(queue: .global(qos: .userInitiated))
                }
            } onCancel: { Task { @MainActor in self.close() } }
        }
        guard let port = listener.port else { throw TogetherError.disconnected }
        return TogetherLANEndpoint(host: host, port: port.rawValue)
    }
    func connect(_ invitation: TogetherInvitation, asHost: Bool) async throws {
        guard !closed, let endpoint = invitation.lan else { throw TogetherError.invalidInvitation }
        self.invitation = invitation
        if asHost {
            guard listener != nil else { throw TogetherError.disconnected }
            try await togetherTimeout(10) {
                try await withTaskCancellationHandler {
                    try await withCheckedThrowingContinuation { self.accepted = $0 }
                } onCancel: { Task { @MainActor in self.close() } }
            }
        } else {
            let socket = try KaeruLANConnection(host: endpoint.host, port: endpoint.port)
            self.socket = socket
            try await togetherTimeout(5) { try await socket.start() }
            // Usable for encrypted writes immediately: the guest hello authenticates to the host.
        }
    }
    private func vet(_ connection: NWConnection) {
        guard !closed, socket == nil, candidates.count < 4, let invitation else { connection.cancel(); return }
        let id = UUID(), fence = generation
        let candidate = KaeruLANConnection(accepted: connection)
        candidates[id] = candidate
        candidateTasks[id] = Task { [weak self] in
            guard let self else { candidate.close(); return }
            defer { self.candidates[id] = nil; self.candidateTasks[id] = nil }
            do {
                let frame = try await togetherTimeout(2) {
                    try await candidate.start()
                    let frame = try await candidate.readFrame()
                    _ = try TogetherCodec.decode(frame, invitation: invitation, from: .guest)
                    return frame
                }
                guard self.generation == fence, self.socket == nil, !Task.isCancelled else { candidate.close(); return }
                self.socket = candidate; self.firstFrame = frame
                // Listener cancellation after a winner must not turn success into a failure.
                self.listener?.stateUpdateHandler = nil
                self.listener?.cancel(); self.listener = nil
                for (other, task) in self.candidateTasks where other != id { task.cancel(); self.candidates[other]?.close() }
                self.finishAccept(nil)
            } catch { candidate.close() }
        }
    }
    func receive() async throws -> TogetherTransportEvent {
        if let firstFrame { self.firstFrame = nil; return .frame(firstFrame) }
        guard let socket else { throw TogetherError.disconnected }
        return try await togetherTimeout(60) { .frame(try await socket.readFrame()) }
    }
    func send(_ frame: Data) async throws {
        guard let socket else { throw TogetherError.disconnected }
        try await togetherTimeout(5) { try await socket.sendFrame(frame) }
    }
    private func finishReady(_ error: Error?) { let pending = ready; ready = nil; if let error { pending?.resume(throwing: error) } else { pending?.resume() } }
    private func finishAccept(_ error: Error?) { let pending = accepted; accepted = nil; if let error { pending?.resume(throwing: error) } else { pending?.resume() } }
    func close() {
        guard !closed else { return }
        closed = true; generation = UUID()
        finishReady(CancellationError()); finishAccept(CancellationError())
        listener?.cancel(); listener = nil; socket?.close(); socket = nil
        for task in candidateTasks.values { task.cancel() }
        for connection in candidates.values { connection.close() }
        candidateTasks = [:]; candidates = [:]; firstFrame = nil; invitation = nil
    }
    private static func localAddress() -> String? {
        var interfaces: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&interfaces) == 0 else { return nil }
        defer { freeifaddrs(interfaces) }
        var current = interfaces
        var fallback: String?
        while let entry = current {
            defer { current = entry.pointee.ifa_next }
            guard let address = entry.pointee.ifa_addr, address.pointee.sa_family == UInt8(AF_INET), entry.pointee.ifa_flags & UInt32(IFF_UP) != 0 else { continue }
            var buffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(address, socklen_t(address.pointee.sa_len), &buffer, socklen_t(buffer.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let host = String(cString: buffer)
            guard TogetherLANAddress.isValid(host) else { continue }
            if String(cString: entry.pointee.ifa_name) == "en0" { return host }
            fallback = fallback ?? host
        }
        return fallback
    }
}
