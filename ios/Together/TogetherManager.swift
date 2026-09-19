import Foundation
import Observation

enum TogetherPhase: Equatable {
    /// `reconnecting` covers both ways a room can be half there: this phone's own socket is being
    /// dialled again, or the friend's went away and the seat is being held for them.
    case idle, connecting, live, reconnecting, ended, failed
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
    /// Wall-clock milliseconds. Injected so the beats below can be driven by a test rather than
    /// waited out, and because the clock offset is arithmetic on two devices' wall clocks.
    private let now: @Sendable () -> Int64
    @ObservationIgnored private var transport: TogetherTransport?
    @ObservationIgnored private weak var playback: (any TogetherPlayback)?
    @ObservationIgnored private var receiveTask: Task<Void, Never>?
    @ObservationIgnored private var generation = UUID()
    @ObservationIgnored private var side: TogetherSide = .guest
    /// A greeting that arrived before a player did, replayed the moment one attaches.
    @ObservationIgnored private var pendingGreeting: TogetherMessage?
    private var ordering = TogetherOrdering(isHost: false)
    private var clock = TogetherClock()
    /// Where the friend said they were, and when that arrived here.
    @ObservationIgnored private var report: PeerReport?
    /// Whether a rate correction other than normal speed is in force right now.
    @ObservationIgnored private var correcting = false
    @ObservationIgnored private var heartbeat: Task<Void, Never>?
    @ObservationIgnored private var beats: Int64 = 0
    /// Set when the friend's socket went away; the room is over if nobody walks back in by then.
    @ObservationIgnored private var rejoinBy: Int64?

    /// How far the friend's clock reads from this one. Zero until a pong has been answered.
    var clockOffsetMs: Int64 { clock.offsetMs }
    /// The round trip, for showing a connection as good or poor.
    var roundTripMs: Int64 { clock.rttMs }

    private struct PeerReport { let positionMs: Int64; let playing: Bool; let sentAt: Int64; let at: Int64 }

    var onOpenPlayback: ((TogetherEpisode) -> Void)?

    init(relayURL: String,
         displayName: String,
         transportFactory: ((TogetherInvitation, Bool) -> TogetherTransport)? = nil,
         now: (@Sendable () -> Int64)? = nil) {
        self.relayURL = relayURL
        self.now = now ?? { Int64(Date().timeIntervalSince1970 * 1000) }
        self.displayName = String(displayName.trimmingCharacters(in: .whitespacesAndNewlines).prefix(32))
        self.transportFactory = transportFactory ?? { invitation, _ in
            if invitation.lan != nil { return TogetherLANTransport() }
            return TogetherRelayTransport(baseURL: relayURL)
        }
    }

    func attach(_ playback: any TogetherPlayback) {
        self.playback = playback
        if let greeting = pendingGreeting {
            pendingGreeting = nil
            apply(greeting)
        }
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
        heartbeat?.cancel(); heartbeat = nil
        transport?.close(); transport = nil; playback?.togetherSetRate(1)
        correcting = false; report = nil; rejoinBy = nil
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
        self.report = nil; self.correcting = false; self.beats = 0; self.rejoinBy = nil
        try await value.connect(invitation, asHost: asHost)
        let fence = generation
        phase = .live
        receiveTask = Task { [weak self] in await self?.receiveLoop(fence: fence, invitation: invitation, transport: value) }
        await sendHello(invitation: invitation)
        // Before the first beat, so the clocks have a sample to work from while the greeting is
        // still being answered rather than five seconds into the episode.
        sendPing()
        startHeartbeat(fence: fence)
    }

    // MARK: - the clock the session runs on

    private func startHeartbeat(fence: UUID) {
        heartbeat?.cancel()
        heartbeat = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(TogetherTiming.stateMs))
                guard !Task.isCancelled, let self, self.generation == fence else { return }
                self.beat()
            }
        }
    }

    /// One tick of the session's own clock, once a second.
    ///
    /// The three cadences are counted in beats rather than run on three timers, so a test can
    /// drive a whole evening's worth of them without sleeping through it.
    func beat() {
        guard phase == .live || phase == .reconnecting else { return }
        beats += 1
        // A report is only worth sending while there is something to report about; a ping is
        // worth sending either way, because it is what keeps the offset current and the socket
        // from idling out.
        if phase == .live { sendState() }
        if beats % (TogetherTiming.pingMs / TogetherTiming.stateMs) == 0 { sendPing() }
        if beats % (TogetherTiming.syncMs / TogetherTiming.stateMs) == 0 { correct() }
        if let deadline = rejoinBy, now() >= deadline { rejoinBy = nil; fail(.disconnected) }
    }

    private func sendPing() { sendMessage(.init(t: .ping, seq: 1, sentAt: now())) }

    private func sendState() {
        guard let snapshot = playback?.togetherSnapshot else { return }
        sendMessage(.init(t: .state, seq: 1, positionMs: snapshot.positionMs, playing: snapshot.playing,
                          buffering: snapshot.buffering, sentAt: now()))
    }

    /// One look at the gap, and usually nothing to do about it.
    ///
    /// The friend's last report is carried forward to now before it is judged: a report that is a
    /// second and a half old says where they were, and half a second is the whole width of the
    /// band that means «leave it alone».
    func correct() {
        guard phase == .live, let playback else { return }
        // A correction the player can no longer honour is one nothing will ever take off again.
        if correcting && !playback.togetherSupportsRate { playback.togetherSetRate(1); correcting = false }
        guard let report else { return }
        let now = self.now()
        guard now - report.at <= TogetherTiming.staleStateMs else { return }
        // Their clock is in `sentAt` and ours is in `now`, so the difference already carries the
        // clock error; the policy's offset is what puts it back. The two mix here on purpose.
        let there = report.playing ? report.positionMs + (now - report.sentAt) : report.positionMs
        let here = playback.togetherSnapshot
        let action = TogetherSync.decide(local: here.positionMs, remote: there, offsetMs: clock.offsetMs,
                                         bothPlaying: here.playing && report.playing,
                                         correcting: correcting, supportsRate: playback.togetherSupportsRate)
        switch action {
        case .none: break
        case .rate(let factor):
            playback.togetherSetRate(factor)
            correcting = factor != 1
        case .seek(let position, _):
            // Normal speed first: a rate correction left running across a jump is one the viewer
            // would carry into an episode it was never about.
            if correcting { playback.togetherSetRate(1); correcting = false }
            playback.togetherSeek(toMilliseconds: position)
        }
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
                case .peerLeft: peerLeft()
                case .reconnecting: if phase == .live { phase = .reconnecting }
                case .reconnected: await reconnected(invitation: invitation)
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
        if message.t == .hello {
            // Somebody is in the room — either for the first time or walking back into the seat
            // the half-minute window was holding for them.
            peerName = message.name; rejoinBy = nil; phase = .live
        }
        apply(message)
    }

    /// The friend's socket went away, and it is not the end: a room keeps the seat for half a
    /// minute, which is about how long a train takes to leave a tunnel. A friend who walks back in
    /// has restarted their count from one, so the replay guard is told to expect exactly one
    /// greeting below the mark it holds — and only a greeting, or the window would be thirty
    /// seconds in which any captured frame plays again.
    private func peerLeft() {
        guard phase == .live || phase == .reconnecting else { return }
        ordering.allowRejoin()
        report = nil
        if correcting { playback?.togetherSetRate(1); correcting = false }
        rejoinBy = now() + TogetherTiming.rejoinWindowMs
        phase = .reconnecting
    }

    /// This phone's own socket is back. The greeting goes out again because a friend whose room
    /// carried on meanwhile has no other way of learning where this side got to, and the ping
    /// because the path may well be a different one now and the old offset was measured on the
    /// old one.
    private func reconnected(invitation: TogetherInvitation) async {
        if phase == .reconnecting && rejoinBy == nil { phase = .live }
        await sendHello(invitation: invitation)
        sendPing()
    }

    private func apply(_ message: TogetherMessage) {
        // The three that are answered wherever the session is, player or no player: the clocks
        // and the drift are a property of the room rather than of what happens to be on screen,
        // and a guest still on its join screen has to answer a ping or the friend measures
        // nothing all evening.
        switch message.t {
        case .ping:
            let at = now()
            sendMessage(.init(t: .pong, seq: 1, sentAt: at, pingSentAt: message.sentAt ?? at, receivedAt: at))
            return
        case .pong:
            guard let pingSentAt = message.pingSentAt, let receivedAt = message.receivedAt, let sentAt = message.sentAt else { return }
            clock.record(sent: pingSentAt, peerReceived: receivedAt, peerSent: sentAt, received: now())
            return
        case .state:
            // Recorded, not acted on: the gap is judged on its own beat, against a report that has
            // been carried forward to the moment of judging.
            guard let positionMs = message.positionMs, let playing = message.playing, let sentAt = message.sentAt else { return }
            report = PeerReport(positionMs: positionMs, playing: playing, sentAt: sentAt, at: now())
            return
        default: break
        }
        guard let playback else {
            // The join screen asks what the friend is watching before any player exists — that is
            // the whole content of the screen the viewer decides on. Tell the screen now and keep
            // the greeting, so attaching a player a moment later lands on the right episode.
            if side == .guest, message.t == .hello, let animeID = message.animeId, let episode = message.episode {
                let item = TogetherEpisode(animeID: animeID, episode: episode,
                                           translationID: message.translationId, positionMs: message.positionMs ?? 0)
                pendingGreeting = message
                onOpenPlayback?(item)
            }
            return
        }
        switch message.t {
        case .hello:
            // Only the side that joined follows the other. A host that adopted its guest's hello
            // would abandon the episode it invited them to — and the first hello of a guest that
            // has not opened anything yet names episode zero.
            guard side == .guest else { return }
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
        case .state, .ping, .pong, .chat, .reaction, .voice, .bye: break
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
        error = value; phase = .failed
        receiveTask?.cancel(); heartbeat?.cancel(); heartbeat = nil
        transport?.close(); transport = nil
        if correcting { playback?.togetherSetRate(1); correcting = false }
        report = nil; rejoinBy = nil
    }
}
