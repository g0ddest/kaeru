import Foundation

/// Google Cast has no SDK for the Mac, and the Mac app casts nothing. `CastManager` stays — it is
/// pure, and the app model and the player talk to it on both systems — driven by a transport that
/// is never connected, so every cast asked of it ends as «not connected» before a stream is resolved.
extension CastManager {
    convenience init(service: any AnimeService) {
        self.init(resolve: { id, translation, episode in
            try await service.resolve(id, translation: translation, episode: episode)
        }, transport: NoCastTransport())
    }
}

@MainActor final class NoCastTransport: CastTransport {
    var onConnection: ((CastConnection) -> Void)?
    var onStatus: ((CastRemoteStatus) -> Void)?
    var onFailure: ((CastFailure) -> Void)?
    func start() {}
    func load(_ payload: CastLoadPayload, completion: @escaping (Result<Void, CastFailure>) -> Void) { completion(.failure(.notConnected)) }
    func play() {}
    func pause() {}
    func seek(to position: TimeInterval) {}
    func stop() {}
    func disconnect() {}
    func presentDevices() {}
    func presentExpandedControls() {}
}
