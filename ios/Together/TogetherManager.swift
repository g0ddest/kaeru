import Foundation
import Observation

enum TogetherPhase: Equatable {
    case idle, connecting, live, ended, failed
}

/// Owns one two-person Together room. Transport and playback are deliberately injected so the
/// relay/LAN paths and the native AVPlayer can be tested without a network or an AVAudioSession.
@MainActor @Observable final class TogetherManager {
    private(set) var phase: TogetherPhase = .idle
    private(set) var invitation: TogetherInvitation?
    private(set) var peerName: String?
    private(set) var error: TogetherError?
    private(set) var messages: [TogetherMessage] = []

    let displayName: String
    private let relayURL: String
    private let transportFactory: (TogetherInvitation, Bool) -> TogetherTransport
    @ObservationIgnored private var transport: TogetherTransport?
    @ObservationIgnored private weak var playback: (any TogetherPlayback)?
    @ObservationIgnored private var receiveTask: Task<Void, Never>?
    @ObservationIgnored private var generation = UUID()
    @ObservationIgnored private var side: TogetherSide = .guest
    private var ordering = TogetherOrdering(isHost: false)
    private var clock = TogetherClock()

    var onOpenPlayback: ((TogetherEpisode) -> Void)?

    init(relayURL: String,
         displayName: String,
         transportFactory: ((TogetherInvitation, Bool) -> TogetherTransport)? = nil) {
        self.relayURL = relayURL
        self.displayName = String(displayName.trimmingCharacters(in: .whitespacesAndNewlines).prefix(32))
        self.transportFactory = transportFactory ?? { invitation, _ in
            if invitation.lan != nil { return TogetherLANTransport() }
            return TogetherRelayTransport(baseURL: relayURL)
        }
    }

    func attach(_ playback: any TogetherPlayback) {
        self.playback = playback
        guard phase == .live, let invitation else { return }
        Task { [weak self] in await self?.sendHello(invitation: invitation) }
    }

    func detach(_ playback: any TogetherPlayback) {
        guard let current = self.playback,
              ObjectIdentifier(current as AnyObject) == ObjectIdentifier(playback as AnyObject) else { return }
        self.playback = nil
    }

    func create() async {
        do {
            let invitation = try TogetherInvitation.random()
            try await connect(invitation, asHost: true)
        } catch is CancellationError {
        } catch let error as TogetherError { fail(error) }
        catch { fail(.disconnected) }
    }

    func join(_ invitation: TogetherInvitation) async {
        do { try await connect(invitation, asHost: false) }
        catch is CancellationError {
        } catch let error as TogetherError { fail(error) }
        catch { fail(.disconnected) }
    }

    func leave() async {
        guard phase != .ended else { return }
        let fence = generation
        receiveTask?.cancel(); receiveTask = nil
        if let transport, let invitation {
            try? await send(.init(t: .bye, seq: 1), invitation: invitation, transport: transport, fence: fence)
        }
        generation = UUID()
        transport?.close(); transport = nil; playback?.togetherSetRate(1)
        phase = .ended; error = nil; peerName = nil
    }

    func sendPlay() { sendAction(.play(position: currentPosition)) }
    func sendPause() { sendAction(.pause(position: currentPosition)) }
    func sendSeek(_ positionMs: Int64) { sendAction(.seek(position: max(0, positionMs))) }
    func sendEpisode(_ episode: TogetherEpisode) { sendAction(.episode(episode)) }
    func sendChat(_ text: String) {
        let value = String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(4096))
        guard !value.isEmpty else { return }
        sendMessage(.init(t: .chat, seq: 1, name: displayName, text: value))
    }
    func sendReaction(_ reaction: TogetherReaction) { sendMessage(.init(t: .reaction, seq: 1, name: displayName, kind: reaction)) }

    private func connect(_ invitation: TogetherInvitation, asHost: Bool) async throws {
        receiveTask?.cancel(); receiveTask = nil; transport?.close()
        let value = transportFactory(invitation, asHost)
        self.invitation = invitation; self.transport = value; self.side = asHost ? .host : .guest
        self.ordering = TogetherOrdering(isHost: asHost); self.clock = TogetherClock()
        phase = .connecting; error = nil; generation = UUID()
        try await value.connect(invitation, asHost: asHost)
        let fence = generation
        phase = .live
        receiveTask = Task { [weak self] in await self?.receiveLoop(fence: fence, invitation: invitation, transport: value) }
        await sendHello(invitation: invitation)
    }

    private func sendHello(invitation: TogetherInvitation) async {
        guard let transport else { return }
        let snapshot = playback?.togetherSnapshot ?? TogetherPlaybackSnapshot()
        let message = TogetherMessage(t: .hello, seq: 1, name: displayName,
                                      animeId: snapshot.animeID ?? 0, episode: snapshot.episode ?? 0,
                                      translationId: snapshot.translationID, positionMs: snapshot.positionMs,
                                      playing: snapshot.playing)
        sendMessage(message, invitation: invitation, transport: transport)
    }

    private func receiveLoop(fence: UUID, invitation: TogetherInvitation, transport: TogetherTransport) async {
        do {
            while !Task.isCancelled {
                let event = try await transport.receive()
                guard generation == fence else { return }
                switch event {
                case .frame(let frame): await receive(frame, invitation: invitation, transport: transport, fence: fence)
                case .peerLeft: phase = .ended; return
                }
            }
        } catch is CancellationError {
        } catch {
            guard generation == fence else { return }
            fail(.disconnected)
        }
    }

    private func receive(_ frame: Data, invitation: TogetherInvitation, transport: TogetherTransport, fence: UUID) async {
        guard generation == fence else { return }
        let remoteSide = side.other
        guard let message = try? TogetherCodec.decode(frame, invitation: invitation, from: remoteSide),
              ordering.accept(seq: message.seq, control: message.isControl, hello: message.t == .hello) else { return }
        if messages.count >= 100 { messages.removeFirst(messages.count - 99) }
        messages.append(message)
        if message.t == .hello { peerName = message.name; phase = .live }
        apply(message)
    }

    private func apply(_ message: TogetherMessage) {
        guard let playback else { return }
        switch message.t {
        case .hello:
            guard let animeID = message.animeId, let episode = message.episode, let playing = message.playing else { return }
            let item = TogetherEpisode(animeID: animeID, episode: episode, translationID: message.translationId, positionMs: message.positionMs ?? 0)
            if playback.togetherSnapshot.animeID != animeID || playback.togetherSnapshot.episode != episode {
                onOpenPlayback?(item)
                Task { try? await playback.togetherOpen(item); playback.togetherSetRate(1); if playing { playback.togetherPlay() } else { playback.togetherPause() } }
            } else {
                playback.togetherSeek(toMilliseconds: item.positionMs)
                playing ? playback.togetherPlay() : playback.togetherPause()
            }
        case .play:
            if let positionMs = message.positionMs { playback.togetherSeek(toMilliseconds: positionMs) }
            playback.togetherPlay()
        case .pause:
            if let positionMs = message.positionMs { playback.togetherSeek(toMilliseconds: positionMs) }
            playback.togetherPause()
        case .seek:
            if let positionMs = message.positionMs { playback.togetherSeek(toMilliseconds: positionMs) }
        case .episode:
            guard let episode = message.episode else { return }
            let item = TogetherEpisode(animeID: message.animeId ?? playback.togetherSnapshot.animeID ?? 0,
                                       episode: episode, translationID: message.translationId, positionMs: message.positionMs ?? 0)
            onOpenPlayback?(item)
            Task { try? await playback.togetherOpen(item) }
        case .state:
            guard let positionMs = message.positionMs, let playing = message.playing else { return }
            let local = playback.togetherSnapshot.positionMs
            let action = TogetherSync.decide(local: local, remote: positionMs,
                                             bothPlaying: playback.togetherSnapshot.playing && playing,
                                             correcting: false, supportsRate: playback.togetherSupportsRate)
            switch action { case .none: break; case .rate(let factor): playback.togetherSetRate(factor); case .seek(let value, _): playback.togetherSeek(toMilliseconds: value) }
        case .chat, .reaction, .voice, .ping, .pong, .bye: break
        }
    }

    private enum Action { case play(position: Int64), pause(position: Int64), seek(position: Int64), episode(TogetherEpisode) }
    private var currentPosition: Int64 { playback?.togetherSnapshot.positionMs ?? 0 }
    private func sendAction(_ action: Action) {
        switch action {
        case .play(let position): sendMessage(.init(t: .play, seq: 1, positionMs: position, playing: true))
        case .pause(let position): sendMessage(.init(t: .pause, seq: 1, positionMs: position, playing: false))
        case .seek(let position): sendMessage(.init(t: .seek, seq: 1, positionMs: position))
        case .episode(let item): sendMessage(.init(t: .episode, seq: 1, animeId: item.animeID, episode: item.episode, translationId: item.translationID, positionMs: item.positionMs))
        }
    }
    private func sendMessage(_ message: TogetherMessage, invitation: TogetherInvitation? = nil, transport: TogetherTransport? = nil) {
        guard let invitation = invitation ?? self.invitation, let transport = transport ?? self.transport else { return }
        let fence = generation
        Task { [weak self] in await self?.send(message, invitation: invitation, transport: transport, fence: fence) }
    }
    private func send(_ message: TogetherMessage, invitation: TogetherInvitation, transport: TogetherTransport, fence: UUID) async {
        guard generation == fence else { return }
        var value = message
        guard let seq = try? ordering.next(control: message.isControl) else { return }
        value.seq = seq
        guard let frame = try? TogetherCodec.encode(value, invitation: invitation, from: side) else { return }
        try? await transport.send(frame)
    }
    private func fail(_ value: TogetherError) {
        error = value; phase = .failed; receiveTask?.cancel(); transport?.close(); transport = nil
    }
}
