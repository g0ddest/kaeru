import Foundation
import Network

@MainActor func togetherTimeout<T: Sendable>(_ seconds: Double, operation: @escaping @MainActor @Sendable () async throws -> T) async throws -> T {
    try await withThrowingTaskGroup(of: T.self) { group in
        group.addTask { try await operation() }
        group.addTask { try await Task.sleep(for: .seconds(seconds)); throw TogetherError.timeout }
        defer { group.cancelAll() }
        guard let value = try await group.next() else { throw CancellationError() }
        return value
    }
}

/// A single bounded TCP connection, shared by LAN Together and the one-shot TV handoff.
/// Network callbacks are marshalled to MainActor; cancellation tears down pending I/O.
@MainActor final class KaeruLANConnection {
    private let connection: NWConnection
    private var started = false
    private var cancelled = false
    private var ready: CheckedContinuation<Void, Error>?
    init(host: String, port: UInt16) throws {
        guard TogetherLANAddress.isValid(host), port > 0, let endpointPort = NWEndpoint.Port(rawValue: port) else { throw TogetherError.invalidInvitation }
        connection = NWConnection(host: NWEndpoint.Host(host), port: endpointPort, using: .tcp)
    }
    init(accepted: NWConnection) { connection = accepted }
    func start() async throws {
        try Task.checkCancellation()
        guard !cancelled else { throw TogetherError.disconnected }
        guard !started else { return }
        started = true
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                ready = continuation
                connection.stateUpdateHandler = { [weak self] state in
                    Task { @MainActor in
                        guard let self else { return }
                        switch state {
                        case .ready: self.finishReady(nil)
                        case .failed: self.finishReady(TogetherError.disconnected)
                        case .cancelled: self.finishReady(CancellationError())
                        default: break
                        }
                    }
                }
                connection.start(queue: .global(qos: .userInitiated))
            }
        } onCancel: { Task { @MainActor in self.close() } }
    }
    private func finishReady(_ error: Error?) {
        let pending = ready; ready = nil
        if let error { pending?.resume(throwing: error) } else { pending?.resume() }
    }
    func send(_ data: Data) async throws {
        try Task.checkCancellation()
        guard !cancelled else { throw TogetherError.disconnected }
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                connection.send(content: data, completion: .contentProcessed { error in
                    if error != nil { continuation.resume(throwing: TogetherError.disconnected) } else { continuation.resume() }
                })
            }
        } onCancel: { Task { @MainActor in self.close() } }
    }
    func read(maximum: Int) async throws -> Data {
        try Task.checkCancellation()
        guard !cancelled, maximum > 0 else { throw TogetherError.disconnected }
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                connection.receive(minimumIncompleteLength: 1, maximumLength: maximum) { data, _, complete, error in
                    if let data, !data.isEmpty { continuation.resume(returning: data) }
                    else if complete || error != nil { continuation.resume(throwing: TogetherError.disconnected) }
                    else { continuation.resume(throwing: TogetherError.disconnected) }
                }
            }
        } onCancel: { Task { @MainActor in self.close() } }
    }
    func readExactly(_ count: Int) async throws -> Data {
        guard count > 0, count <= TogetherCodec.maximumFrameBytes else { throw TogetherError.frameTooLarge }
        var data = Data()
        while data.count < count { data.append(try await read(maximum: count - data.count)) }
        return data
    }
    func readFrame() async throws -> Data {
        let header = try await readExactly(4)
        let count = header.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        guard count > 28, count <= TogetherCodec.maximumFrameBytes else { throw TogetherError.frameTooLarge }
        return try await readExactly(Int(count))
    }
    func sendFrame(_ data: Data) async throws {
        guard data.count > 28, data.count <= TogetherCodec.maximumFrameBytes else { throw TogetherError.frameTooLarge }
        let count = UInt32(data.count)
        let header = Data([UInt8((count >> 24) & 255), UInt8((count >> 16) & 255), UInt8((count >> 8) & 255), UInt8(count & 255)])
        try await send(header + data)
    }
    func close() {
        guard !cancelled else { return }
        cancelled = true; finishReady(CancellationError()); connection.cancel()
    }
}
