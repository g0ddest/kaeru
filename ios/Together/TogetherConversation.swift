import Foundation
import Observation

/// What the other phone did. One line at the top of the picture, replaced rather than queued.
enum TogetherNoticeKind: Equatable { case paused, played, seeked, episode, joined, left, catchingUp }

/// The two ways out of a wait, and there is never a third.
enum TogetherWaitExit: Equatable {
    /// The friend may still walk in. The line goes; the room stays.
    case keepWatching
    /// Nothing is left to wait for. The line goes and so does the session.
    case watchAlone
}

/// A wait, or the end of one, with the button that gets the evening back.
///
/// Never built without an `exit`: this type exists so that the compiler, and not a reviewer, is
/// what stops a state where the video has stopped for somebody else's reason and nothing on screen
/// gives control back.
struct TogetherWait: Equatable {
    var text: String
    var exit: TogetherWaitExit
}

struct TogetherNotice: Equatable, Identifiable {
    var id: Int64
    var text: String
}

/// A clip as the overlay holds it: bytes to play, and how long they run.
struct TogetherClip: Equatable {
    var data: Data
    var durationMs: Int64
}

/// Something somebody said: a line of text or a clip, never both.
///
/// The same value stands in the corner for seven seconds and in the history for the whole session,
/// so it carries `at` — the corner has no use for a timestamp and the history cannot be read
/// without one.
struct TogetherSaid: Equatable, Identifiable {
    var id: Int64
    var mine: Bool
    var author: String
    var text: String?
    var clip: TogetherClip?
    var at: Date
    /// Its seven seconds are up and it is on its way out. The corner draws that; it is not gone.
    var leaving = false
}

/// An emoji on its way up the right-hand side of the picture.
struct TogetherFlyingReaction: Equatable, Identifiable {
    var id: Int64
    var reaction: TogetherReaction
    var mine: Bool
    var startedAt: Date
}

/// How long the parts of a shared viewing that end by themselves last. Android's numbers, to the
/// millisecond: two phones in one room should forget the same line at the same moment.
enum TogetherConversationTiming {
    /// Long enough to look away from the video and read it.
    static let itemLifeMs: Double = 7_000
    /// The fade at the end of those seven seconds, which is part of them rather than added to them.
    static let itemFadeMs: Double = 300
    /// At most three, because the corner of a phone in landscape is about 147 points tall.
    static let stackMax = 3
    /// Long enough to read, short enough that it is gone before it is in the way.
    static let noticeLifeMs: Double = 3_000
    static let reactionLifeMs: Double = 2_200
    /// Three at once. Beyond that a tapped-out friend produces rain rather than a reaction.
    static let reactionMax = 3
    /// Nothing waits longer than this for anybody.
    static let waitTimeoutMs: Double = 30_000
    /// How long «Сессия закончилась» stays up. It is a receipt for something the viewer just did.
    static let endedLineMs: Double = 3_000
}

/// People talking over a video, and everything about that which ends by itself.
///
/// A port of the half of Android's `TogetherViewModel` that owns clocks: the seven seconds a line
/// lives in the corner, the second a reaction flies for, the three seconds of a notice, and the
/// thirty after which a wait stops being a wait. The session underneath — the socket, the player,
/// the drift — is `TogetherManager`'s and none of it is here.
///
/// Time is passed in rather than read: every deadline is judged against a `Date` the caller hands
/// over, so a test can run an evening through this without sleeping through it. In the app a timer
/// calls `sweep` ten times a second, which is what Android's per-item coroutines do between them.
///
/// What this viewer says is put on screen here rather than waited for as an echo from the network.
/// A message that appears only once the network has confirmed it is a message that does not appear
/// when the network is the thing that is wrong.
@MainActor @Observable final class TogetherConversation {
    private(set) var notice: TogetherNotice?
    private(set) var wait: TogetherWait?
    /// What is in the bottom-left corner right now: at most three, each on its own clock.
    private(set) var stack: [TogetherSaid] = []
    /// Everything said this session, oldest first. In memory, and gone when the session is.
    private(set) var history: [TogetherSaid] = []
    private(set) var reactions: [TogetherFlyingReaction] = []
    var historyOpen = false
    /// A clip to put through the speaker right now, once.
    private(set) var playing: TogetherSaid?
    /// One line said once and then forgotten — «Нужен доступ к микрофону», a goodbye.
    var message: String?

    /// Whether things in the corner may disappear on a clock.
    ///
    /// Turned off with a screen reader running: text that vanishes on a timer is the one thing
    /// WCAG 2.2.1 will not have, and somebody listening to the screen cannot be made to race it.
    var autoHide = true {
        didSet { if !autoHide { expiry = [:] } }
    }

    /// Ids for what this viewer says. Negative, so they can never collide with the session's.
    private var localID: Int64 = 0
    private var expiry: [Int64: Date] = [:]
    private var noticeUntil: Date?
    private var waitUntil: Date?
    /// What happens when a timed wait runs out, since the three of them end differently.
    private var timedWait: TimedWait?
    /// Whether «<имя> догоняет…» is up. It has no clock: it ends when the gap does.
    private(set) var catchingUp = false

    private enum TimedWait { case hosting, joining, ended }

    // MARK: - what somebody said

    func said(_ item: TogetherSaid, now: Date = Date()) {
        stack = Array((stack + [item]).suffix(TogetherConversationTiming.stackMax))
        history.append(item)
        guard autoHide else { return }
        expiry[item.id] = now.addingTimeInterval(TogetherConversationTiming.itemLifeMs / 1000)
    }

    func chat(_ text: String, mine: Bool, author: String, now: Date = Date()) {
        let trimmed = String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(TogetherCopy.maxChars))
        guard !trimmed.isEmpty else { return }
        said(TogetherSaid(id: nextID(), mine: mine, author: author, text: trimmed, at: now), now: now)
    }

    func clip(_ clip: TogetherClip, mine: Bool, author: String, now: Date = Date()) {
        let item = TogetherSaid(id: nextID(), mine: mine, author: author, clip: clip, at: now)
        said(item, now: now)
        // Straight through the speaker: a remark about what is on screen right now is not worth
        // anything twenty seconds later, behind a tap nobody made.
        if !mine { playing = item }
    }

    /// «Послушать ещё раз», from the corner or from the history.
    func replay(_ id: Int64) {
        guard let item = history.first(where: { $0.id == id }), item.clip != nil else { return }
        playing = item
    }
    /// The clip that was playing has finished, or was never started.
    func clipPlayed() { playing = nil }

    func fly(_ reaction: TogetherReaction, mine: Bool, now: Date = Date()) {
        guard reactions.count < TogetherConversationTiming.reactionMax else { return }
        reactions.append(TogetherFlyingReaction(id: nextID(), reaction: reaction, mine: mine, startedAt: now))
    }

    // MARK: - what the other phone did

    /// «Догоняет» is a wait, not a remark: this viewer's own video is fine and the friend is
    /// behind, so what the screen owes them is the state and a way to stop caring about it.
    /// Anything else the other phone does is a remark — and a later one is proof that the catching
    /// up finished.
    func notice(_ kind: TogetherNoticeKind, peerName: String?, positionMs: Int64 = 0, episode: Int = 0, now: Date = Date()) {
        let text = TogetherCopy.notice(kind, peerName: peerName, positionMs: positionMs, episode: episode)
        guard kind != .catchingUp else {
            catchingUp = true
            waitUntil = nil; timedWait = nil
            wait = TogetherWait(text: text, exit: .keepWatching)
            return
        }
        if catchingUp { catchingUp = false; wait = nil }
        localID -= 1
        notice = TogetherNotice(id: localID, text: text)
        noticeUntil = now.addingTimeInterval(TogetherConversationTiming.noticeLifeMs / 1000)
    }

    /// The gap the friend was closing has closed. The same two seconds the sync policy uses as the
    /// line between «pull with playback speed» and «seek»: under it there is nothing left to tell
    /// anybody.
    func caughtUp() {
        guard catchingUp else { return }
        catchingUp = false
        wait = nil
    }

    /// Where the session is, as the screen has to draw it.
    ///
    /// - Parameter peerPresent: whether somebody else is actually in the room. A host that has
    ///   opened one and is waiting is «live» to the transport and is waiting to the viewer.
    func phaseChanged(_ phase: TogetherPhase, peerPresent: Bool, error: TogetherError?, now: Date = Date()) {
        switch phase {
        case .idle:
            forget()
        case .connecting:
            arm(.joining, now: now)
            wait = TogetherWait(text: TogetherCopy.connecting, exit: .watchAlone)
        case .live:
            if peerPresent {
                arm(nil, now: now)
                // A wait is kept only while somebody is catching up; anything else is over.
                if !catchingUp { wait = nil }
            } else {
                arm(.hosting, now: now)
                wait = TogetherWait(text: TogetherCopy.waitingFriend, exit: .keepWatching)
            }
        case .reconnecting:
            // The seat is being held for a friend who stepped into a tunnel. Nothing has ended, so
            // the way out is the one that keeps the room.
            arm(nil, now: now)
            catchingUp = false
            wait = TogetherWait(text: TogetherCopy.reconnecting, exit: .keepWatching)
        case .failed:
            arm(nil, now: now)
            catchingUp = false
            wait = TogetherWait(text: TogetherCopy.lost(error), exit: .watchAlone)
        case .ended:
            arm(.ended, now: now)
            catchingUp = false
            wait = TogetherWait(text: TogetherCopy.ended, exit: .watchAlone)
        }
    }

    /// «Смотреть дальше» / «Смотреть одному» — the wait is over either way, and which of the two it
    /// was is what the caller acts on.
    @discardableResult func leaveWait() -> TogetherWaitExit? {
        let exit = wait?.exit
        arm(nil, now: Date())
        catchingUp = false
        wait = nil
        return exit
    }

    /// A session that is over takes its conversation with it. Nothing here is written down.
    func forget() {
        stack = []; history = []; reactions = []; notice = nil; wait = nil
        playing = nil; historyOpen = false; catchingUp = false
        expiry = [:]; noticeUntil = nil; waitUntil = nil; timedWait = nil
    }

    // MARK: - the clock

    /// Takes away everything whose time is up. Called ten times a second while a session is live.
    func sweep(now: Date = Date()) {
        if let until = noticeUntil, now >= until { notice = nil; noticeUntil = nil }
        let fade = TogetherConversationTiming.itemFadeMs / 1000
        for index in stack.indices {
            guard let until = expiry[stack[index].id] else { continue }
            // Marked first and removed a fade later, so the corner has something to fade rather
            // than a line that vanishes between two frames.
            if now >= until.addingTimeInterval(-fade) && !stack[index].leaving { stack[index].leaving = true }
        }
        let gone = stack.filter { expiry[$0.id].map { now >= $0 } ?? false }.map(\.id)
        if !gone.isEmpty {
            stack.removeAll { gone.contains($0.id) }
            for id in gone { expiry[id] = nil }
        }
        let flight = TogetherConversationTiming.reactionLifeMs / 1000
        reactions.removeAll { now.timeIntervalSince($0.startedAt) >= flight }
        if let until = waitUntil, now >= until { waitRanOut() }
    }

    // MARK: -

    /// Negative, so what this viewer says can never collide with an id the session hands out.
    private func nextID() -> Int64 {
        localID -= 1
        return localID
    }

    /// Starts, restarts or cancels the clock on a wait. Thirty seconds and then something else
    /// happens, whatever that something is: neither of them leaves a person looking at a spinner.
    private func arm(_ kind: TimedWait?, now: Date) {
        guard kind != timedWait else { return }
        timedWait = kind
        switch kind {
        case nil: waitUntil = nil
        case .ended: waitUntil = now.addingTimeInterval(TogetherConversationTiming.endedLineMs / 1000)
        case .hosting, .joining: waitUntil = now.addingTimeInterval(TogetherConversationTiming.waitTimeoutMs / 1000)
        }
    }

    private func waitRanOut() {
        let kind = timedWait
        timedWait = nil; waitUntil = nil
        switch kind {
        // The friend may still be reading the message. The line goes; the room stays.
        case .hosting, .ended, nil: wait = nil
        case .joining: wait = TogetherWait(text: TogetherCopy.unreachable, exit: .watchAlone)
        }
    }
}
