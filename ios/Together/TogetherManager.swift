import Foundation
import Observation

enum TogetherPhase: Equatable {
    /// `reconnecting` covers both ways a room can be half there: this phone's own socket is being
    /// dialled again, or the friend's went away and the seat is being held for them.
    case idle, connecting, live, reconnecting, ended, failed
}

/// What a link in a messenger turns into before there is a player.
///
/// The invited side knows a room id and nothing else until the other phone answers, so this starts
/// as «подключаемся» with no title on it and fills in when the greeting lands. The screen that
/// draws it is the one place a person agrees to join something — the player opens after that, not
/// before, which is also why a failure here is a state and not a dismissal.
struct TogetherJoinTarget: Equatable {
    var peerName: String?
    var episode: TogetherEpisode?
    var failure: String?
    /// False for a link that was never a room: nothing to knock on again.
    var retryable = true
    var isWaiting: Bool { episode == nil && failure == nil }
}

/// Owns one two-person Together room. Transport and playback are deliberately injected so the
/// relay/LAN paths and the native AVPlayer can be tested without a network or an AVAudioSession.
@MainActor @Observable final class TogetherManager {
    private(set) var phase: TogetherPhase = .idle
    private(set) var invitation: TogetherInvitation?
    private(set) var peerName: String?
    /// Set from the moment an invitation is accepted until the viewer has agreed to what it turned
    /// out to be — or walked away from it. Nil for the side that made the room.
    private(set) var joining: TogetherJoinTarget?
    private(set) var error: TogetherError?
    private(set) var messages: [TogetherMessage] = []
    /// What is on screen over the video, and everything about it that ends by itself.
    let conversation = TogetherConversation()

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
    /// Which connection this is, for the greeting to say: drawn afresh each time this side
    /// connects, so a friend can tell a session that started over from a greeting the relay kept.
    @ObservationIgnored private var epoch: Int64 = 0
    /// The invitation this phone was given, kept past a failure so «Повторить» has something to
    /// knock on.
    @ObservationIgnored private var lastJoin: TogetherInvitation?
    /// What the room was last told this side is watching — by a greeting, an `episode` this side
    /// sent, or one it followed. A player that attaches on something else is a change of episode.
    @ObservationIgnored private var announcedEpisode: TogetherEpisode?
    /// A greeting that arrived before a player did, replayed the moment one attaches.
    @ObservationIgnored private var pendingGreeting: TogetherMessage?
    /// Frames in a row that would not open. A key that does not match never starts matching.
    @ObservationIgnored private var refused = 0
    /// When this side last answered a greeting with its own, for the guest's once-in-three-seconds.
    @ObservationIgnored private var greetingAnsweredAt: Int64?
    private var ordering = TogetherOrdering(isHost: false)
    private var clock = TogetherClock()
    /// Where the friend said they were, and when that arrived here.
    @ObservationIgnored private var report: PeerReport?
    /// Whether a rate correction other than normal speed is in force right now.
    @ObservationIgnored private var correcting = false
    /// The friend's player said it is filling its buffer.
    @ObservationIgnored private var peerLoading = false
    /// This side paused itself to wait for them, and will start again when they are ready.
    @ObservationIgnored private var heldForPeer = false
    /// When waiting stops being kindness and starts being a frozen picture.
    @ObservationIgnored private var holdUntil: Int64 = 0
    /// What the last report said about the buffer, so a change can be said before the next beat.
    @ObservationIgnored private var reportedBuffering: Bool?
    /// When the greeting being read on the join screen arrived, for carrying its position forward.
    @ObservationIgnored private var pendingGreetingAt: Int64 = 0
    /// Nothing is corrected before this instant — the quiet after a jump.
    @ObservationIgnored private var correctionSettledAt: Int64 = 0
    @ObservationIgnored private var heartbeat: Task<Void, Never>?
    /// Ten times a second, which is what takes the lines out of the corner. Separate from the
    /// session's own beat: the conversation's clocks are tenths of a second and the room's are
    /// whole ones, and running the room ten times as often to keep up would be a waste of a radio.
    @ObservationIgnored private var sweeper: Task<Void, Never>?
    @ObservationIgnored private var beats: Int64 = 0
    /// A clip arriving in pieces. One at a time: two people talking at once is two people nobody
    /// can follow, and the protocol sends a clip's chunks back to back.
    @ObservationIgnored private var voice = TogetherVoiceAssembly()
    /// Set when the friend's socket went away; the room is over if nobody walks back in by then.
    @ObservationIgnored private var rejoinBy: Int64?

    /// How far the friend's clock reads from this one. Zero until a pong has been answered.
    var clockOffsetMs: Int64 { clock.offsetMs }
    /// The round trip, for showing a connection as good or poor.
    var roundTripMs: Int64 { clock.rttMs }

    private struct PeerReport { let positionMs: Int64; let playing: Bool; let sentAt: Int64; let at: Int64 }

    /// The one place the phase moves, so the overlay can never be drawing a session that has
    /// already ended. Everything the screen shows about waiting follows from here.
    private func enter(_ value: TogetherPhase) {
        phase = value
        conversation.phaseChanged(value, peerPresent: peerName != nil, error: error)
        if value == .idle || value == .ended { stopSweeping() }
        else { startSweeping() }
    }
    private func startSweeping() {
        guard sweeper == nil else { return }
        sweeper = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(100))
                guard !Task.isCancelled, let self else { return }
                self.conversation.sweep()
                self.reportStallIfChanged()
            }
        }
    }
    private func stopSweeping() { sweeper?.cancel(); sweeper = nil }

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
        // The buffer as it stands, before the greeting below moves the picture: the stall that
        // seek costs is then a change, and is said at once rather than on the next beat.
        reportedBuffering = playback.togetherSnapshot.buffering
        if let greeting = pendingGreeting {
            pendingGreeting = nil
            apply(greeting)
        }
        guard phase == .live, let invitation else { return }
        // A player that opened on something else — another episode, another title picked from
        // the catalogue while the room was live — is a change of episode for the friend, exactly
        // as if it had been picked from the player's own menu. A greeting alone does not say so:
        // a friend already in the room changes episode on `episode` and on nothing else, and
        // Android's player announces its opening as one. Without this the two phones sat in one
        // room on two different titles.
        // Not «ready»: a player attaches the moment its stream is resolved, a beat before the
        // first frame, and waiting for the frame meant the change was never said at all.
        let here = playback.togetherSnapshot
        if let animeID = here.animeID, let episode = here.episode, animeID > 0, episode > 0,
           let known = announcedEpisode, known.animeID != animeID || known.episode != episode {
            TogetherLog.write("player opened \(animeID)/\(episode) over a live room; telling the friend")
            sendEpisode(.init(animeID: animeID, episode: episode, translationID: here.translationID, positionMs: here.positionMs))
        }
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
        // The room this phone is already keeping. Opening one's own invitation — from the share
        // sheet, from a chat with oneself — used to dial back in as the guest, which left the
        // room with two guests in it and no host at all.
        if let current = self.invitation, current.roomID == invitation.roomID, side == .host,
           phase == .live || phase == .connecting || phase == .reconnecting {
            TogetherLog.write("ignored an invitation to the room this phone is hosting")
            return
        }
        joining = TogetherJoinTarget()
        lastJoin = invitation
        do { try await connect(invitation, asHost: false) }
        catch is CancellationError { joining = nil }
        catch let error as TogetherError { fail(error) }
        catch { fail(.disconnected) }
    }
    /// «Смотреть вместе» on the join screen: the room stays, the screen goes, and the player the
    /// caller opens next is the one this session attaches to.
    ///
    /// A player that is already open never attaches again, so the greeting that was held back
    /// while the invitation was being read is applied here instead — otherwise agreeing to join
    /// would leave this phone on whatever it happened to be watching.
    func acceptJoin() {
        joining = nil
        guard playback != nil, let greeting = pendingGreeting else { return }
        pendingGreeting = nil
        apply(greeting)
    }
    /// «Повторить» after a room that would not open. The invitation is kept for exactly this: it
    /// is cleared when a room fails, because a dead link is not one to share.
    func retryJoin() async {
        guard let lastJoin else { return }
        await join(lastJoin)
    }

    func leave() async {
        guard phase != .ended else { return }
        let fence = generation
        receiveTask?.cancel(); receiveTask = nil
        // «Не сейчас» on the join screen is not leaving a session: nothing was joined. No goodbye
        // goes out — the socket simply closes, the relay tells the host «peer-left», and the host
        // holds the seat for half a minute the way it does for a dropped connection. Android's
        // `dismissJoin()` has always been this quiet; a goodbye from here ended the host's room
        // for anybody who tapped the link out of curiosity.
        let declining = joining != nil
        if !declining, let transport, let invitation {
            // Goodbye waits for the wire: everything after this line tears the session down, and a
            // frame handed to a cancelled transport is a friend left staring at a paused picture.
            var bye = TogetherMessage(t: .bye, seq: 1)
            TogetherLog.write("bye out")
            if let seq = try? ordering.next(control: true) {
                bye.seq = seq
                if let frame = try? TogetherCodec.encode(bye, invitation: invitation, from: side) {
                    await deliver(frame, transport: transport, fence: fence)
                }
            }
        }
        generation = UUID()
        heartbeat?.cancel(); heartbeat = nil
        let closing = transport
        transport = nil; playback?.togetherSetRate(1); playback?.togetherDuck(false)
        correcting = false; report = nil; rejoinBy = nil
        error = nil; peerName = nil
        // The room goes with it. Leaving used to keep the invitation, so «Завершить комнату» left
        // the link, the share button and itself on screen — a room that had ended and would not
        // go away.
        invitation = nil
        joining = nil
        if declining { closing?.close(); enter(.idle); return }
        conversation.message = TogetherCopy.leftSession
        enter(.ended)
        // Last, and after the screen has been told: the goodbye is on the wire or nearly, and
        // what is left is the socket's own closing handshake, waited for up to a second so the
        // frame is not cut off behind it. Nothing above waits for this.
        await closing?.closeAfterGoodbye()
    }

    /// Said out loud by this viewer, and on screen here rather than waited for as an echo from the
    /// network: a message that appears only once the network has confirmed it is a message that
    /// does not appear when the network is the thing that is wrong.
    func send(chat text: String) {
        let value = String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(TogetherCopy.maxChars))
        guard !value.isEmpty else { return }
        sendChat(value)
        conversation.chat(value, mine: true, author: TogetherCopy.you)
    }
    func send(reaction: TogetherReaction) {
        sendReaction(reaction)
        conversation.fly(reaction, mine: true)
    }

    /// «Смотреть дальше» keeps the room; «Смотреть одному» ends it. Which of the two a wait offers
    /// is decided once, in `TogetherConversation`, and never by the screen.
    func leaveWait() {
        guard conversation.leaveWait() == .watchAlone else { return }
        Task { [weak self] in await self?.leave() }
    }

    func sendPlay() { sendAction(.play(position: currentPosition)) }
    func sendPause() { sendAction(.pause(position: currentPosition)) }
    func sendSeek(_ positionMs: Int64) { sendAction(.seek(position: max(0, positionMs))) }
    func sendEpisode(_ episode: TogetherEpisode) {
        announcedEpisode = episode
        // The friend's last report is about the episode this side is leaving, and judged against
        // the start of the next one it is a twenty-minute gap and a seek to close it.
        report = nil
        sendAction(.episode(episode))
    }
    func sendChat(_ text: String) {
        let value = String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(4096))
        guard !value.isEmpty else { return }
        sendMessage(.init(t: .chat, seq: 1, name: displayName, text: value))
    }
    func sendReaction(_ reaction: TogetherReaction) { sendMessage(.init(t: .reaction, seq: 1, name: displayName, kind: reaction)) }
    /// A clip on its way out, cut into the pieces the protocol carries — the same cut as Android's
    /// `TogetherSession.sendVoice`, so the far side reassembles it without knowing which phone
    /// recorded it. A ceiling on the whole clip and not only on each frame: past this is a caller
    /// with a bug rather than somebody with a lot to say, and it is better dropped here than cut
    /// into frames and sent.
    func send(voice clip: RecordedClip) {
        guard !clip.data.isEmpty, clip.data.count <= Self.maximumVoiceBytes else { return }
        let cut = Self.voiceChunkBytes
        let total = (clip.data.count + cut - 1) / cut
        guard (1...8).contains(total) else { return }
        for index in 0..<total {
            let slice = clip.data[(index * cut)..<min((index + 1) * cut, clip.data.count)]
            sendMessage(.init(t: .voice, seq: 1, chunk: index, total: total,
                              bytes: Data(slice).togetherBase64, durationMs: clip.durationMs))
        }
        conversation.clip(TogetherClip(data: clip.data, durationMs: clip.durationMs),
                          mine: true, author: TogetherCopy.you)
    }
    /// Turns the picture down while somebody is talking over it, and back up after. Explicit
    /// rather than left to the system: a phone will not duck an app against itself.
    func duck(_ on: Bool) { playback?.togetherDuck(on) }
    static let voiceChunkBytes = 32_768
    static let maximumVoiceBytes = 262_144

    private func connect(_ invitation: TogetherInvitation, asHost: Bool) async throws {
        receiveTask?.cancel(); receiveTask = nil; transport?.close()
        let value = transportFactory(invitation, asHost)
        self.invitation = invitation; self.transport = value; self.side = asHost ? .host : .guest
        self.ordering = TogetherOrdering(isHost: asHost); self.clock = TogetherClock()
        epoch = Int64.random(in: 1...Int64.max)
        error = nil; peerName = nil; conversation.forget(); enter(.connecting); generation = UUID()
        self.report = nil; self.correcting = false; self.beats = 0; self.rejoinBy = nil
        // A wait belongs to the room it was made in: a hold left by a friend who was loading when
        // the last room died would otherwise let go with `play` on the next friend's first report.
        peerLoading = false; dropHold(); correctionSettledAt = 0
        reportedBuffering = nil; pendingGreetingAt = 0
        refused = 0; greetingAnsweredAt = nil
        try await value.connect(invitation, asHost: asHost)
        let fence = generation
        enter(.live)
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
        // A guest nobody has answered in half a minute is not going to be answered: the link was
        // stale, or somebody who cannot read the room's frames is sitting in the other seat. The
        // same half minute as Android's `WAIT_TIMEOUT_MS`, and the same ending.
        if side == .guest, peerName == nil, beats >= TogetherTiming.waitTimeoutMs / TogetherTiming.stateMs {
            TogetherLog.write("no greeting in \(TogetherTiming.waitTimeoutMs / 1000)s; giving up on this room")
            fail(.timeout)
            return
        }
        // A report is only worth sending while there is something to report about — and only
        // once this viewer has said yes, or the friend would be measuring drift against whatever
        // this phone happened to be watching when the link arrived. A ping is worth sending
        // either way, because it is what keeps the offset current and the socket from idling out.
        if phase == .live, joining == nil { sendState() }
        if beats % (TogetherTiming.pingMs / TogetherTiming.stateMs) == 0 { sendPing() }
        // Said again until somebody answers it.
        //
        // A greeting is one frame, sent once, through a relay that keeps nothing: whoever is in
        // the room first says hello to an empty room, and the one who arrives second never hears
        // it. The host answers a guest's hello, so the usual order works — but a host whose socket
        // blinked and redialled has already spent its guest's only greeting, and then both sides
        // sit in the same room saying nothing at all. This is what «Подключаемся…» for ever was.
        if peerName == nil, beats % (TogetherTiming.helloRetryMs / TogetherTiming.stateMs) == 0,
           let invitation {
            Task { [weak self] in await self?.sendHello(invitation: invitation) }
        }
        if beats % (TogetherTiming.syncMs / TogetherTiming.stateMs) == 0 { correct() }
        if let deadline = rejoinBy, now() >= deadline { rejoinBy = nil; fail(.disconnected) }
        if heldForPeer, holdUntil > 0, now() >= holdUntil {
            TogetherLog.write("waited \(TogetherTiming.peerLoadingHoldMs / 1000)s for the friend to load; going on")
            releaseHold()
        }
    }

    private func sendPing() { sendMessage(.init(t: .ping, seq: 1, sentAt: now())) }

    private func sendState() {
        guard let snapshot = playback?.togetherSnapshot else { return }
        reportedBuffering = snapshot.buffering
        // With the episode on it. A report used to be a position and nothing else, and a guest
        // followed it whatever it was a position *in* — so a friend who had moved on to the next
        // episode, or stayed on the last one, dragged this side's picture through the wrong one.
        sendMessage(.init(t: .state, seq: 1, animeId: snapshot.animeID, episode: snapshot.episode,
                          positionMs: snapshot.positionMs, playing: snapshot.playing,
                          buffering: snapshot.buffering, sentAt: now()))
    }

    /// A stall is said the moment it starts and the moment it ends, not on the next beat.
    ///
    /// The friend stops for a picture that is loading and starts again when it is not, and both
    /// happen when the report saying so lands. Off the beat alone that is up to a second late each
    /// way, and the two delays are different — so after every stall the two pictures stood a
    /// random fraction of a second apart, which the corrector then spent half a minute nudging out.
    /// Said at once, both delays shrink to a trip through the relay, and cancel. Ten times a
    /// second, off the sweeper: the same clock the corner's lines fade on.
    func reportStallIfChanged() {
        guard phase == .live, joining == nil, let reported = reportedBuffering,
              let buffering = playback?.togetherSnapshot.buffering, buffering != reported else { return }
        sendState()
    }

    /// One look at the gap, and usually nothing to do about it.
    ///
    /// The friend's last report is carried forward to now before it is judged: a report that is a
    /// second and a half old says where they were, and half a second is the whole width of the
    /// band that means «leave it alone».
    /// The friend's player is filling its buffer, so this one waits instead of running ahead.
    ///
    /// Both sides have always reported it and neither has ever read it. What that looked like: one
    /// phone stalls on a segment, the other plays on, the gap passes ten seconds, and the rule
    /// says seek — so the stalled phone is dragged forward, stalls again on the segment it has not
    /// got, and is dragged again. «Перемотал на» every few seconds, for as long as the network
    /// was slow. Waiting is what a person would do.
    private func peerIsLoading(_ loading: Bool) {
        guard loading != peerLoading else { return }
        peerLoading = loading
        guard joining == nil, let playback else { return }
        if loading {
            guard playback.togetherSnapshot.playing else { return }
            heldForPeer = true
            holdUntil = now() + TogetherTiming.peerLoadingHoldMs
            if correcting { playback.togetherSetRate(1); correcting = false }
            TogetherLog.write("holding: the friend is loading")
            playback.togetherPause()
            conversation.notice(.catchingUp, peerName: peerName)
        } else if heldForPeer {
            // Whether their loading ended in a picture that moves or in one they stopped for this
            // side's sake — two phones stalling at once each wait for the other, and the first
            // report to say «not loading» is what breaks that. A pause the friend chose is not
            // this case: it arrives as a `pause`, ahead of any report, and takes the hold off.
            releaseHold()
        }
    }
    /// Let go of a hold — because the friend is ready, or because they have taken too long and a
    /// held picture with nothing on screen explaining it is worse than being out of step.
    private func releaseHold() {
        TogetherLog.write("hold released: the friend is ready")
        dropHold()
        // Nothing is corrected against a report taken while the picture was standing still.
        report = nil
        playback?.togetherPlay()
    }
    /// The wait is over without anybody being started: somebody said what they wanted instead.
    private func dropHold() {
        heldForPeer = false
        holdUntil = 0
    }
    /// Ends a correction that is running. Nothing to do when none is.
    private func normalSpeed() {
        guard correcting else { return }
        playback?.togetherSetRate(1)
        correcting = false
    }
    /// A jump small enough to be read as the video stuttering is not worth making — and on HLS it
    /// is not a stutter but a stall: the segment is fetched again, the picture stops, the friend
    /// is told this side is loading and waits. Every pause and play used to cost one. Android's
    /// `catchUpTo`, with the same half second.
    private func catchUp(to positionMs: Int64, on playback: any TogetherPlayback) {
        guard abs(playback.togetherSnapshot.positionMs - positionMs) >= TogetherSync.ignoreMs else { return }
        playback.togetherSeek(toMilliseconds: positionMs)
    }
    /// Where the friend is at this moment, as a position in the episode: their last report carried
    /// forward on this device's clock, or the greeting carried forward until they have reported.
    /// The greeting is stale by however long the invitation sat on screen, and a picture opened at
    /// it jumps visibly — with a sentence about it — the moment the session catches up.
    private func peerPositionNow(fallback greeting: TogetherMessage) -> Int64 {
        let now = self.now()
        if let report {
            let moved = report.playing ? max(0, now - report.sentAt + clock.offsetMs) : 0
            return max(0, report.positionMs + moved)
        }
        let said = greeting.positionMs ?? 0
        guard greeting.playing == true, pendingGreetingAt > 0 else { return said }
        return said + min(max(0, now - pendingGreetingAt), TogetherTiming.helloCarryMaxMs)
    }
    func correct() {
        // Nothing is corrected on the join screen: the player behind it, if there is one, is
        // showing what this viewer was watching when the link arrived, and is nobody's to move.
        guard phase == .live, joining == nil, let playback else { return }
        // One side follows and the other is the reference — the way every watch-together that
        // works does it. Two phones each correcting towards the other, by two different estimates
        // of the clock offset, settle a second apart and take turns jumping; the side that made
        // the room is the one the picture is measured against.
        guard side == .guest else {
            if correcting { playback.togetherSetRate(1); correcting = false }
            return
        }
        // A correction the player can no longer honour is one nothing will ever take off again.
        if correcting && !playback.togetherSupportsRate { playback.togetherSetRate(1); correcting = false }
        // Nobody is corrected while either player is filling its buffer, and nothing is corrected
        // in the seconds right after a seek: a jump costs an HLS player a stall, and a rule that
        // judges the stall it caused will order another jump.
        guard !peerLoading, !heldForPeer, !playback.togetherSnapshot.buffering,
              now() >= correctionSettledAt else { return }
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
        case .seek(let position, let notify):
            // Normal speed first: a rate correction left running across a jump is one the viewer
            // would carry into an episode it was never about.
            if correcting { playback.togetherSetRate(1); correcting = false }
            playback.togetherSeek(toMilliseconds: position)
            // The stall this jump is about to cause is not evidence of anything.
            correctionSettledAt = now + TogetherTiming.correctionQuietMs
            self.report = nil
            // Ten seconds or more apart is a jump the viewer can see, and a jump they did not ask
            // for needs a reason on screen. «Догоняет» has no clock: it ends when the gap does.
            if notify { conversation.notice(.catchingUp, peerName: peerName) }
        }
        // The same two seconds the policy uses as the line between pulling with playback speed and
        // jumping: under it the gap is being closed silently and there is nothing left to say.
        if abs(here.positionMs - (there + clock.offsetMs)) < TogetherSync.seekMs { conversation.caughtUp() }
    }

    private func sendHello(invitation: TogetherInvitation) async {
        if let snapshot = playback?.togetherSnapshot, let animeID = snapshot.animeID, let episode = snapshot.episode, animeID > 0 {
            announcedEpisode = TogetherEpisode(animeID: animeID, episode: episode, translationID: snapshot.translationID, positionMs: snapshot.positionMs)
        }
        TogetherLog.write("hello out anime=\(playback?.togetherSnapshot.animeID ?? 0) episode=\(playback?.togetherSnapshot.episode ?? 0)")
        guard let transport else { return }
        let snapshot = playback?.togetherSnapshot ?? TogetherPlaybackSnapshot()
        let message = TogetherMessage(t: .hello, seq: 1, name: displayName,
                                      animeId: snapshot.animeID ?? 0, episode: snapshot.episode ?? 0,
                                      translationId: snapshot.translationID, positionMs: snapshot.positionMs,
                                      playing: snapshot.playing, epoch: epoch)
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
                case .reconnecting: if phase == .live { enter(.reconnecting) }
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
        // Both ways of dropping a frame are silent by design — a relay is untrusted and a bad
        // frame is simply not a message. Silent is not the same as unexplainable, though: a room
        // that never comes alive looks identical from the screen whether the greeting never
        // arrived, would not authenticate, or was refused as a replay.
        guard let message = try? TogetherCodec.decode(frame, invitation: invitation, from: remoteSide) else {
            // One refusal has an answer, and it is the only one worth telling somebody about: a
            // frame that opens under this side's own key was sent by another guest. Both phones
            // opened the link, nobody is keeping the room, and the two of them will sit there
            // exchanging bytes neither can read until one of them gives up.
            if (try? TogetherCodec.decode(frame, invitation: invitation, from: side)) != nil {
                TogetherLog.write("frame refused: the other phone also joined as \(side)")
                fail(.sameSide)
                return
            }
            refused += 1
            TogetherLog.write("frame refused: did not authenticate as \(remoteSide) (\(refused) in a row)")
            // One is a packet and two is bad luck. Three in a row from a peer that is sitting
            // there is a key that does not match — a link truncated by a chat application, most
            // often — and the alternative to saying so is waiting for a friend who is already
            // connected and shouting through the wrong door, in silence, for ever. Android's
            // `GARBLED_LIMIT`, and the same ending.
            if refused >= TogetherTiming.garbledLimit { fail(.disconnected) }
            return
        }
        refused = 0
        if message.t == .hello, message.epoch == nil {
            // A build older than the epoch. Its greeting after a drop is let in the old way — any
            // one greeting inside the window the relay's «peer-left» opens resets the count.
            TogetherLog.write("hello without an epoch: the other phone runs an older build")
        }
        guard ordering.accept(seq: message.seq, control: message.isControl, hello: message.t == .hello, epoch: message.epoch) else {
            TogetherLog.write("frame refused: \(message.t) seq=\(message.seq) out of order")
            return
        }
        TogetherLog.write("frame in \(message.t) seq=\(message.seq)")
        if messages.count >= 100 { messages.removeFirst(messages.count - 99) }
        messages.append(message)
        if message.t == .hello {
            // Somebody is in the room — either for the first time or walking back into the seat
            // the half-minute window was holding for them.
            let already = peerName != nil
            peerName = message.name; rejoinBy = nil
            enter(.live)
            if !already { conversation.notice(.joined, peerName: peerName) }
            answerGreeting(invitation)
        }
        apply(message)
    }

    /// Either side answers a greeting with its own.
    ///
    /// The host every time, the way Android's host does: its own was said when the room opened
    /// and repeated only while nobody had answered, and a guest who walks in a second after the
    /// last repeat would otherwise wait for a greeting that is not coming.
    ///
    /// The guest too, because a handshake can be lost one way round. The host's socket dies
    /// while the host is in the background and is dialled again; the guest's only greeting
    /// landed in the gap. The guest hears the host — who repeats until answered — and so stops
    /// repeating; the host never hears the guest, keeps greeting an empty room for the rest of
    /// the evening, and applies the guest's reports all the same. A one-sided acquaintance.
    /// At most once in `helloRetryMs`, or the two would greet each other for ever: an answer is
    /// itself a greeting, and the host answers every one.
    private func answerGreeting(_ invitation: TogetherInvitation) {
        if side == .guest {
            if let last = greetingAnsweredAt, now() - last < TogetherTiming.helloRetryMs { return }
            greetingAnsweredAt = now()
        }
        Task { [weak self] in await self?.sendHello(invitation: invitation); self?.sendPing() }
    }

    /// The friend's socket went away, and it is not the end: a room keeps the seat for half a
    /// minute, which is about how long a train takes to leave a tunnel. A friend who walks back in
    /// with a session that started over greets with a new epoch, and that greeting alone is let in
    /// below the replay guard's mark. The guard is told about the drop all the same, for a friend
    /// on a build too old to carry an epoch: theirs is the one greeting inside this window.
    private func peerLeft() {
        TogetherLog.write("peer left, holding the seat for \(TogetherTiming.rejoinWindowMs / 1000)s")
        guard phase == .live || phase == .reconnecting else { return }
        ordering.allowRejoin()
        report = nil
        if correcting { playback?.togetherSetRate(1); correcting = false }
        rejoinBy = now() + TogetherTiming.rejoinWindowMs
        conversation.notice(.left, peerName: peerName)
        enter(.reconnecting)
    }

    /// This phone's own socket is back. The greeting goes out again because a friend whose room
    /// carried on meanwhile has no other way of learning where this side got to, and the ping
    /// because the path may well be a different one now and the old offset was measured on the
    /// old one.
    private func reconnected(invitation: TogetherInvitation) async {
        if phase == .reconnecting && rejoinBy == nil { enter(.live) }
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
            // A report from another episode is not a position this picture can be measured
            // against, and not a stall this picture should wait for. An older build sends no
            // episode at all; that report is taken as it always was.
            if let theirs = message.episode, let here = playback?.togetherSnapshot,
               theirs != here.episode || (message.animeId != nil && message.animeId != here.animeID) {
                TogetherLog.write("state in from another episode (\(theirs)); ignored")
                report = nil
                peerIsLoading(false)
                return
            }
            report = PeerReport(positionMs: positionMs, playing: playing, sentAt: sentAt, at: now())
            TogetherLog.write("state in pos=\(positionMs) playing=\(playing) buffering=\(message.buffering == true) here=\(playback?.togetherSnapshot.positionMs ?? -1)/\(playback?.togetherSnapshot.playing ?? false)")
            // Only a friend who means to be playing. One who is paused and buffering is simply
            // paused — and that reaches this side as a `pause`, never as a report.
            peerIsLoading(message.buffering == true && playing)
            return
        // What was said belongs to the room rather than to whatever is on screen: a guest still on
        // its join screen has a conversation to keep too, and the corner of the player picks it up
        // the moment one opens.
        case .chat:
            guard let text = message.text else { return }
            conversation.chat(text, mine: false, author: TogetherCopy.name(peerName))
            return
        case .reaction:
            guard let kind = message.kind else { return }
            conversation.fly(kind, mine: false)
            return
        case .voice:
            guard let clip = try? voice.append(message, now: now()) else { return }
            conversation.clip(TogetherClip(data: clip.data, durationMs: clip.durationMs),
                              mine: false, author: TogetherCopy.name(peerName))
            return
        case .bye:
            peerSaidGoodbye()
            return
        default: break
        }
        // An invitation being read is not an invitation accepted. Whatever the friend does
        // meanwhile is folded into the greeting the screen is about, for the moment this viewer
        // says yes — the way Android keeps its `pendingEpisode` — and the player that may be open
        // behind the screen, showing something else entirely, is not touched. Android gates every
        // control on the session being live; this is the same rule, read from the join screen.
        if joining != nil, message.isControl {
            stash(message)
            return
        }
        guard let playback else {
            // The join screen asks what the friend is watching before any player exists — that is
            // the whole content of the screen the viewer decides on. Tell the screen now and keep
            // the greeting, so attaching a player a moment later lands on the right episode.
            if side == .guest, message.t == .hello, let animeID = message.animeId, let episode = message.episode {
                let item = TogetherEpisode(animeID: animeID, episode: episode,
                                           translationID: message.translationId, positionMs: message.positionMs ?? 0)
                pendingGreeting = message; pendingGreetingAt = now()
                joining = TogetherJoinTarget(peerName: peerName, episode: item)
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
            // An invitation being read is not an invitation accepted, and this phone may well
            // have a player of its own open behind the screen — somebody watching something else
            // when the link arrived. The greeting fills the screen in and stops there; the player
            // hears about it when they press «Присоединиться», out of `attach`.
            if joining != nil, let animeID = message.animeId, let episode = message.episode {
                pendingGreeting = message; pendingGreetingAt = now()
                joining = TogetherJoinTarget(peerName: peerName,
                                             episode: TogetherEpisode(animeID: animeID, episode: episode,
                                                                      translationID: message.translationId,
                                                                      positionMs: message.positionMs ?? 0))
                return
            }
            guard let animeID = message.animeId, let episode = message.episode, let playing = message.playing else { return }
            // Where they are now, not where they were when they said hello: a viewer reading the
            // invitation for ten seconds is ten seconds behind by the time they say yes.
            let item = TogetherEpisode(animeID: animeID, episode: episode, translationID: message.translationId,
                                       positionMs: peerPositionNow(fallback: message))
            dropHold()
            if playback.togetherSnapshot.animeID != animeID || playback.togetherSnapshot.episode != episode {
                normalSpeed()
                onOpenPlayback?(item)
                Task { try? await playback.togetherOpen(item); if playing { playback.togetherPlay() } else { playback.togetherPause() } }
            } else {
                // A greeting can come mid-session too — the host answers every one of this
                // side's, and this side greets again whenever a player attaches or a socket
                // comes back. Close enough is left alone, as it is for a pause: the jump would
                // be a stall for nothing.
                catchUp(to: item.positionMs, on: playback)
                playing ? playback.togetherPlay() : playback.togetherPause()
            }
        // Whatever the friend says next outranks a picture this side stopped for them: a pause
        // pressed while this side was waiting is a pause, and a hold that later let go with `play`
        // would overrule it — while the friend's own reports, being paused, said nothing.
        case .play:
            dropHold()
            if let positionMs = message.positionMs { catchUp(to: positionMs, on: playback) }
            playback.togetherPlay()
            conversation.notice(.played, peerName: peerName, positionMs: message.positionMs ?? 0)
        case .pause:
            dropHold()
            if let positionMs = message.positionMs { catchUp(to: positionMs, on: playback) }
            playback.togetherPause()
            conversation.notice(.paused, peerName: peerName, positionMs: message.positionMs ?? 0)
        case .seek:
            // The hold stays. A seek says where the friend is, not whether they are ready: they
            // have just jumped and are loading the new place, and this side is paused for exactly
            // that reason. Taking the hold off here without starting the picture left this side
            // standing until somebody pressed play — the friend's «buffering=false» a moment later
            // found nothing to release. `play` and `pause` end the hold; a seek only moves it.
            if let positionMs = message.positionMs { playback.togetherSeek(toMilliseconds: positionMs) }
            conversation.notice(.seeked, peerName: peerName, positionMs: message.positionMs ?? 0)
        case .episode:
            guard let episode = message.episode else { return }
            let item = TogetherEpisode(animeID: message.animeId ?? playback.togetherSnapshot.animeID ?? 0,
                                       episode: episode, translationID: message.translationId, positionMs: message.positionMs ?? 0)
            // Followed, so the room and this side now agree on what is playing.
            announcedEpisode = item
            dropHold()
            // Their last report is about the episode they have just left. Judged against the start
            // of the new one, it is a twenty-minute gap and a seek to close it — the same reason a
            // correcting seek forgets the report it acted on.
            report = nil
            // A correction running when the episode changes would be inherited by one it was
            // never about; and an episode a friend opened is one they are playing — on both
            // phones opening one starts it — so this side plays it too rather than sitting on
            // its first frame while the friend's reports say they are a minute in.
            normalSpeed()
            onOpenPlayback?(item)
            conversation.notice(.episode, peerName: peerName, episode: episode)
            Task { try? await playback.togetherOpen(item); playback.togetherPlay() }
        case .state, .ping, .pong, .chat, .reaction, .voice, .bye: break
        }
    }

    /// What the friend did while the invitation was being read, folded into the greeting that
    /// will be applied when it is accepted.
    ///
    /// A state, not a queue: a pause and then a jump is a paused picture at the new place, and
    /// replaying each in turn would move a player the viewer never agreed to lend. Their last
    /// report is forgotten with it — it describes where they were before this, and the next one
    /// describes where they are after. Nothing said before the greeting itself changes it: the
    /// host greets again on every greeting of this side's, and a fresh greeting already says
    /// what an older pause did.
    private func stash(_ message: TogetherMessage) {
        report = nil
        guard var greeting = pendingGreeting, message.seq > greeting.seq else {
            TogetherLog.write("\(message.t) seq=\(message.seq) on the join screen, before a greeting; dropped")
            return
        }
        switch message.t {
        case .play:
            greeting.playing = true
            greeting.positionMs = message.positionMs ?? greeting.positionMs
        case .pause:
            greeting.playing = false
            greeting.positionMs = message.positionMs ?? greeting.positionMs
        case .seek:
            greeting.positionMs = message.positionMs ?? greeting.positionMs
        case .episode:
            // An episode a friend opened is one they are playing, from its start.
            greeting.animeId = message.animeId ?? greeting.animeId
            greeting.episode = message.episode
            greeting.translationId = message.translationId
            greeting.positionMs = message.positionMs ?? 0
            greeting.playing = true
        default: return
        }
        greeting.seq = message.seq
        pendingGreeting = greeting
        pendingGreetingAt = now()
        // The screen names the episode it is about, and that has just changed.
        if message.t == .episode, let episode = greeting.episode, let animeID = greeting.animeId, var target = joining, target.episode != nil {
            target.episode = TogetherEpisode(animeID: animeID, episode: episode, translationID: greeting.translationId, positionMs: greeting.positionMs ?? 0)
            joining = target
        }
        TogetherLog.write("\(message.t) seq=\(message.seq) folded into the greeting on the join screen")
    }

    /// The friend pressed «выйти». Nobody to hold a seat for.
    ///
    /// Used to be ignored: the relay's `peer-left` followed a moment later and was the only thing
    /// heard, so a friend who left on purpose was shown as a connection that dropped — half a
    /// minute of «Восстанавливаем связь» and then «Связь прервалась», an account of what happened
    /// that is simply untrue. Android's `departed(deliberate = true)`, and the same ending.
    private func peerSaidGoodbye() {
        TogetherLog.write("peer said goodbye")
        conversation.notice(.left, peerName: peerName)
        generation = UUID()
        receiveTask?.cancel(); receiveTask = nil
        heartbeat?.cancel(); heartbeat = nil
        transport?.close(); transport = nil
        normalSpeed()
        playback?.togetherDuck(false)
        report = nil; rejoinBy = nil; dropHold()
        invitation = nil
        // Whoever is still on the join screen is told there: the invitation has been withdrawn,
        // and knocking on the room again would only find it empty.
        if joining != nil {
            joining = TogetherJoinTarget(peerName: peerName, failure: TogetherCopy.ended, retryable: false)
        }
        enter(.ended)
    }

    private enum Action { case play(position: Int64), pause(position: Int64), seek(position: Int64), episode(TogetherEpisode) }
    private var currentPosition: Int64 { playback?.togetherSnapshot.positionMs ?? 0 }
    private func sendAction(_ action: Action) {
        // This viewer said what they want the picture doing, and a wait this side had put on it
        // for the friend's sake must not say otherwise when the friend is ready.
        switch action {
        case .play(let position): dropHold(); sendMessage(.init(t: .play, seq: 1, positionMs: position, playing: true))
        case .pause(let position): dropHold(); sendMessage(.init(t: .pause, seq: 1, positionMs: position, playing: false))
        case .seek(let position): sendMessage(.init(t: .seek, seq: 1, positionMs: position))
        case .episode(let item): dropHold(); sendMessage(.init(t: .episode, seq: 1, animeId: item.animeID, episode: item.episode, translationId: item.translationID, positionMs: item.positionMs))
        }
    }
    private func sendMessage(_ message: TogetherMessage, invitation: TogetherInvitation? = nil, transport: TogetherTransport? = nil) {
        guard let invitation = invitation ?? self.invitation, let transport = transport ?? self.transport else { return }
        // The number and the bytes are settled here, on this actor, in the order the caller wrote
        // them. Doing it inside the task would let the language schedule two sends in either order,
        // and a voice clip is up to eight frames whose reassembly depends on that order.
        var value = message
        guard let seq = try? ordering.next(control: message.isControl) else { return }
        value.seq = seq
        guard let frame = try? TogetherCodec.encode(value, invitation: invitation, from: side) else { return }
        let fence = generation
        Task { [weak self] in await self?.deliver(frame, transport: transport, fence: fence) }
    }
    private func deliver(_ frame: Data, transport: TogetherTransport, fence: UUID) async {
        guard generation == fence else { return }
        try? await transport.send(frame)
    }
    private func fail(_ value: TogetherError) {
        TogetherLog.write("failed \(value) phase=\(phase) peer=\(peerName ?? "-")")
        error = value
        // Fenced like the other two endings. Closing the transport wakes the receive loop with a
        // throw, and without a new fence that throw came back here as a second failure — so a
        // room given up on for want of a greeting was shown as a connection that dropped.
        generation = UUID()
        receiveTask?.cancel(); receiveTask = nil
        heartbeat?.cancel(); heartbeat = nil
        transport?.close(); transport = nil
        if correcting { playback?.togetherSetRate(1); correcting = false }
        playback?.togetherDuck(false)
        report = nil; rejoinBy = nil
        // Nothing here dials again, so the link this invitation carries is a link to a room that
        // never opened. Keeping it on screen only offers people a way to share a dead one.
        invitation = nil
        // Whoever is still on the join screen is told there, where they can try again: a screen
        // that dismissed itself would leave the tap on the invitation looking like nothing at all.
        if joining != nil {
            joining = TogetherJoinTarget(peerName: peerName,
                                         failure: value.errorDescription,
                                         retryable: value != .invalidInvitation)
        }
        enter(.failed)
    }
}
