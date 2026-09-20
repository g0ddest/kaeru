import Foundation
import GoogleCast

extension CastManager {
    convenience init(service: any AnimeService) {
        self.init(resolve: { id, translation, episode in
            try await service.resolve(id, translation: translation, episode: episode)
        }, transport: GoogleCastTransport())
    }
}

extension CastLoadPayload {
    /// Standard Styled Media Receiver request, matching Android MediaItemFactory.castMediaItem.
    @MainActor func makeSDKRequest() -> GCKMediaLoadRequestData {
        let metadata = GCKMediaMetadata(metadataType: .tvShow)
        metadata.setString(title, forKey: kGCKMetadataKeyTitle)
        metadata.setString(subtitle, forKey: kGCKMetadataKeySubtitle)
        metadata.setString(title, forKey: kGCKMetadataKeySeriesTitle)
        metadata.setInteger(selection.episode, forKey: kGCKMetadataKeyEpisodeNumber)
        if let artwork { metadata.addImage(GCKImage(url: artwork, width: 0, height: 0)) }
        let media = GCKMediaInformationBuilder(contentURL: url)
        media.contentID = url.absoluteString
        media.contentType = contentType
        media.streamType = .buffered
        media.metadata = metadata
        // No credentials or customData: Styled Media Receiver has no header interceptor.
        let request = GCKMediaLoadRequestDataBuilder()
        request.mediaInformation = media.build()
        request.startTime = position
        request.autoplay = NSNumber(value: autoplay)
        return request.build()
    }
}

@MainActor final class GoogleCastTransport: NSObject, CastTransport, @preconcurrency GCKSessionManagerListener,
                                             @preconcurrency GCKRemoteMediaClientListener, @preconcurrency GCKRequestDelegate {
    static let receiverID = "0EEA38FE"
    var onConnection: ((CastConnection) -> Void)?
    var onStatus: ((CastRemoteStatus) -> Void)?
    var onFailure: ((CastFailure) -> Void)?

    private var started = false
    private var client: GCKRemoteMediaClient?
    private var pollTask: Task<Void, Never>?
    private var requests: [Int: (GCKRequest, (Result<Void, CastFailure>) -> Void)] = [:]
    private var context: GCKCastContext { GCKCastContext.sharedInstance() }

    static func configure() {
        guard !GCKCastContext.isSharedInstanceInitialized() else { return }
        let options = GCKCastOptions(discoveryCriteria: GCKDiscoveryCriteria(applicationID: receiverID))
        options.stopReceiverApplicationWhenEndingSession = true
        options.startDiscoveryAfterFirstTapOnCastButton = true
        options.suspendSessionsWhenBackgrounded = true
        GCKCastContext.setSharedInstanceWith(options)
        GCKCastContext.sharedInstance().useDefaultExpandedMediaControls = true
    }

    func start() {
        guard !started else { return }
        Self.configure(); started = true
        context.sessionManager.add(self)
        if let session = context.sessionManager.currentCastSession, session.connectionState == .connected {
            attach(session)
        }
    }

    func load(_ payload: CastLoadPayload, completion: @escaping (Result<Void, CastFailure>) -> Void) {
        guard let client else { completion(.failure(.notConnected)); return }
        track(client.loadMedia(with: payload.makeSDKRequest()), failure: .loadFailed, completion: completion)
    }

    func play() { if let client { command(client.play()) } }
    func pause() { if let client, client.mediaStatus != nil { command(client.pause()) } }
    func stop() { if let client { command(client.stop()) } }
    func seek(to position: TimeInterval) {
        guard let client else { return }
        let options = GCKMediaSeekOptions()
        options.interval = CastLoadPayload.validTime(position)
        options.relative = false
        options.resumeState = .unchanged
        command(client.seek(with: options))
    }

    func disconnect() {
        // true terminates the receiver application, including when the SDK dialog initiated it.
        if !context.sessionManager.endSessionAndStopCasting(true) { onFailure?(.commandFailed) }
    }
    /// Discovery is started here rather than left to the SDK.
    ///
    /// `startDiscoveryAfterFirstTapOnCastButton` keeps the local-network prompt away from launch,
    /// and the SDK lifts it when somebody taps a `GCKUICastButton` — which this app no longer has.
    /// Without this line the picker opens on an empty list for ever.
    func presentDevices() {
        start()
        context.discoveryManager.startDiscovery()
        context.presentCastDialog()
    }
    func presentExpandedControls() { context.presentDefaultExpandedMediaControls() }

    private func command(_ request: GCKRequest) {
        track(request, failure: .commandFailed) { [weak self] result in
            if case .failure(let error) = result { self?.onFailure?(error) }
        }
    }

    private func track(_ request: GCKRequest, failure: CastFailure,
                       completion: @escaping (Result<Void, CastFailure>) -> Void) {
        if !request.inProgress {
            completion(request.error == nil ? .success(()) : .failure(failure))
            return
        }
        requests[request.requestID] = (request, { result in
            switch result { case .success: completion(.success(())); case .failure: completion(.failure(failure)) }
        })
        request.delegate = self
    }

    func requestDidComplete(_ request: GCKRequest) {
        requests.removeValue(forKey: request.requestID)?.1(.success(()))
        emitStatus()
    }
    func request(_ request: GCKRequest, didFailWithError error: GCKError) {
        // Do not surface receiver errors verbatim: they can contain short-lived signed URLs.
        requests.removeValue(forKey: request.requestID)?.1(.failure(.commandFailed))
    }
    func request(_ request: GCKRequest, didAbortWith abortReason: GCKRequestAbortReason) {
        let completion = requests.removeValue(forKey: request.requestID)?.1
        if abortReason != .replaced { completion?(.failure(.commandFailed)) }
    }

    private func attach(_ session: GCKCastSession) {
        client?.remove(self)
        client = session.remoteMediaClient
        client?.add(self)
        onConnection?(.connected(session.device.friendlyName ?? "Chromecast"))
        emitStatus()
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            while !Task.isCancelled {
                do { try await Task.sleep(for: .milliseconds(500)) } catch { return }
                guard let self else { return }
                self.emitStatus()
            }
        }
    }

    private func detach() {
        pollTask?.cancel(); pollTask = nil
        client?.remove(self); client = nil
        let pending = requests.values.map(\.0)
        requests.removeAll()
        for request in pending { request.delegate = nil; request.cancel() }
    }

    private func emitStatus() {
        guard let client, let status = client.mediaStatus else { return }
        let phase: CastRemotePhase
        switch status.playerState {
        case .playing: phase = .playing
        case .paused: phase = .paused
        case .buffering: phase = .buffering
        case .loading: phase = .loading
        case .idle:
            switch status.idleReason {
            case .finished: phase = .finished
            case .error: phase = .failed
            case .cancelled, .interrupted: phase = .stopped
            default: phase = .unknown
            }
        default: phase = .unknown
        }
        onStatus?(.init(contentID: status.mediaInformation?.contentID ?? status.mediaInformation?.contentURL?.absoluteString,
                        position: client.approximateStreamPosition(),
                        duration: status.mediaInformation?.streamDuration ?? 0, phase: phase))
    }

    func remoteMediaClient(_ client: GCKRemoteMediaClient, didUpdate mediaStatus: GCKMediaStatus?) {
        guard self.client === client else { return }
        emitStatus()
    }
    func sessionManager(_ sessionManager: GCKSessionManager, willStart session: GCKCastSession) {
        onConnection?(.connecting)
    }
    func sessionManager(_ sessionManager: GCKSessionManager, didStart session: GCKCastSession) { attach(session) }
    func sessionManager(_ sessionManager: GCKSessionManager, didResumeCastSession session: GCKCastSession) { attach(session) }
    func sessionManager(_ sessionManager: GCKSessionManager, willEnd session: GCKCastSession) { emitStatus() }
    func sessionManager(_ sessionManager: GCKSessionManager, didEnd session: GCKCastSession, withError error: Error?) {
        detach(); onConnection?(.disconnected)
        if error != nil { onFailure?(.connectionFailed) }
    }
    func sessionManager(_ sessionManager: GCKSessionManager, didFailToStart session: GCKCastSession, withError error: Error) {
        detach(); onConnection?(.disconnected); onFailure?(.connectionFailed)
    }
    func sessionManager(_ sessionManager: GCKSessionManager, didSuspend session: GCKCastSession, with reason: GCKConnectionSuspendReason) {
        emitStatus(); detach()
        onConnection?(.suspended(session.device.friendlyName ?? "Chromecast"))
    }
}
