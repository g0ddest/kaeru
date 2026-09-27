import Foundation
import KaeruShared
#if os(iOS)
import UIKit
#endif

/// Why the player asks for the batch to go now rather than within the minute.
enum SyncReason: Equatable {
    case pause, episodeChange, leaving
    /// The network is back after a spell without it.
    case reconnected
}

/// A scheduled send that can be called off.
@MainActor protocol SyncTimer: AnyObject {
    func cancel()
}

/// What sync needs from the outside world, injectable so the tests can own the network and time.
struct SyncEnvironment {
    var transport: any SyncTransport
    var now: @MainActor () -> Date
    var schedule: @MainActor (TimeInterval, @escaping @MainActor () -> Void) -> any SyncTimer
    /// Asks the system for time to finish a request the app is leaving behind; returns the release.
    var keepAlive: @MainActor () -> @MainActor () -> Void

    @MainActor static var live: SyncEnvironment {
        SyncEnvironment(transport: URLSessionSyncTransport(), now: Date.init, schedule: TaskTimer.after, keepAlive: backgroundTime)
    }

    /// On a phone, a request started as the app goes away would be frozen with it half sent; the
    /// system gives a few seconds to finish when asked. A Mac keeps running and needs no asking.
    @MainActor private static func backgroundTime() -> @MainActor () -> Void {
        #if os(iOS)
        var identifier = UIBackgroundTaskIdentifier.invalid
        identifier = UIApplication.shared.beginBackgroundTask(withName: "kaeru.sync") {
            UIApplication.shared.endBackgroundTask(identifier)
            identifier = .invalid
        }
        return {
            guard identifier != .invalid else { return }
            UIApplication.shared.endBackgroundTask(identifier)
            identifier = .invalid
        }
        #else
        return {}
        #endif
    }
}

@MainActor final class TaskTimer: SyncTimer {
    private var task: Task<Void, Never>?
    static func after(_ delay: TimeInterval, _ action: @escaping @MainActor () -> Void) -> any SyncTimer {
        let timer = TaskTimer()
        timer.task = Task { @MainActor in
            try? await Task.sleep(for: .seconds(max(0, delay)))
            guard !Task.isCancelled else { return }
            action()
        }
        return timer
    }
    func cancel() { task?.cancel(); task = nil }
}

/// Viewing sync through the worker (spec 2026-09-26-kaeru-sync-design.md §4), as Android's
/// `ViewingSync` does it: reads the document on start and after five minutes in the background and
/// takes whatever is newer than this device's; sends this device's positions and dubs — and titles
/// watched «украдкой» — in batches: at most one a minute, at once on pause, on another episode, on
/// leaving the player and on going to the background; and a tombstone for a title the list marks
/// «completed». A batch that fails stays in the outbox for the next try, across launches.
///
/// The rules are shared/'s (Core/SharedSync.swift): merging, the wire, what of the server's is
/// newer, the first full send. What is here is when things go, the outbox and the network.
///
/// Nothing here throws at a caller or makes one wait: the player only ever hands over a position.
/// Local data is per account already (every record is keyed by `AppModel.accountKey`), and so is
/// the outbox, so one account's positions and unsent changes can never go out with another's token.
@MainActor final class SyncService {
    /// At most one batch a minute while watching (spec §3: D1's free tier writes).
    static let pushEvery: TimeInterval = 60
    /// Back from the background after this long: another device may have played meanwhile.
    static let pullAfterBackground: TimeInterval = 5 * 60
    /// Titles per POST, well under the worker's 256 KB body with 30 episodes each.
    static let titlesPerPost = 100
    /// Accounts whose positions this device already sent once in full. Not per account on purpose:
    /// it is a list of them, and sign-out leaves it alone; turning sync off takes the account out.
    static let seededKey = "sync.seeded"

    private weak var model: AppModel?
    private let client: SyncClient
    private let environment: SyncEnvironment
    private var timer: (any SyncTimer)?
    private var lastPushAt: Date?
    private var inflight = false
    private var again = false
    private var pulling: Task<Void, Never>?
    private var backgroundedAt: Date?
    /// Each listed title's status as last seen; nil until a list is first known for this account.
    private var statuses: [Int: String]?
    /// Work started and not yet over, so a test — or anything else — can wait for quiet.
    private var running: [UUID: Task<Void, Never>] = [:]

    init(model: AppModel, client: SyncClient, environment: SyncEnvironment) {
        self.model = model; self.client = client; self.environment = environment
    }

    func now() -> Date { environment.now() }

    /// The setting. Off, nothing is read, queued or sent.
    private var enabled: Bool { model?.preferences.syncOn == true }

    /// «Синхронизация между устройствами» switched: on reads the document (and uploads what this
    /// device has, once); off stops everything, drops what was waiting to go and forgets that this
    /// device was ever seeded — so on again sends what it holds by then, as nothing done meanwhile
    /// was queued.
    func setEnabled(_ on: Bool) {
        if on { statuses = nil; start(); return }
        timer?.cancel(); timer = nil
        again = false
        guard let account = model?.accountID else { return }
        writeOutbox([:], account: account)
        forgetSeeded(account)
    }

    // MARK: - what the app tells sync

    /// Signed in at launch or just now: read the document.
    func start() { guard enabled else { return }; launch { await self.pull() } }

    /// The account is another one, or none: whatever was scheduled was the previous one's, and
    /// its list is not this one's.
    func accountChanged() {
        timer?.cancel(); timer = nil
        lastPushAt = nil; again = false; backgroundedAt = nil; statuses = nil
    }

    func positionSaved(_ value: EpisodeProgress) {
        guard enabled, let model, model.accountID != nil, !model.isFinished(value.animeID) else { return }
        let position = SyncRules.shared.wire(position: value.episodePosition)
        // One with no length cannot be resumed from, here or anywhere else.
        guard position.d > 0 else { return }
        enqueue(value.animeID, .of(eps: [String(value.episode): position]))
    }

    /// A dub chosen here, stamped when. One nobody has named yet stays here: the other devices
    /// could not show it.
    func dubChosen(_ animeID: Int, id: Int, title: String, at: Int64) {
        guard enabled, let model, model.accountID != nil, !title.isEmpty, !model.isFinished(animeID) else { return }
        enqueue(animeID, .of(dub: .named(id: id, title: title, at: at)))
    }

    /// A title switched to or from «украдкой», or its count of watched episodes moved. A finished
    /// one still goes: the tombstone its turn to «completed» leaves is newer, and the worker drops
    /// it then — a finished title keeps nothing but the tombstone.
    func secretChanged(_ animeID: Int, _ secret: SyncSecret) {
        guard enabled, model?.accountID != nil else { return }
        enqueue(animeID, .of(secret: secret))
    }

    /// The list as it now stands. A title turning «completed» leaves a tombstone; turning back
    /// before the tombstone went out takes it back. The first list seen is where things stand.
    func libraryChanged(_ library: [LibraryItem]) {
        guard enabled, model?.accountID != nil else { return }
        let current = Self.statuses(library)
        // The first list seen is where things stand, not a change. An empty one says nothing yet:
        // it is what a fresh install has before Shikimori answers — and after one, the next list
        // is where things stand again rather than a title list full of changes.
        guard let before = statuses, !before.isEmpty else {
            statuses = current.isEmpty ? nil : current
            return
        }
        statuses = current
        for (animeID, status) in current {
            let was = before[animeID]
            if status == "completed", was != "completed" { markGone(animeID) }
            else if status != "completed", was == "completed" { unmarkGone(animeID) }
        }
    }

    func push(_ reason: SyncReason) {
        guard enabled, model?.accountID != nil else { return }
        launch { await self.send(keepAlive: reason == .leaving) }
    }

    func wentToBackground() {
        if backgroundedAt == nil { backgroundedAt = now() }
        guard enabled, model?.accountID != nil else { return }
        launch { await self.send(keepAlive: true) }
    }

    func becameActive() {
        guard enabled, let at = backgroundedAt else { return }
        backgroundedAt = nil
        if now().timeIntervalSince(at) >= Self.pullAfterBackground { launch { await self.pull() } }
    }

    /// Waits until nothing started here is still running.
    func settle() async {
        while let task = running.values.first { await task.value }
    }

    // MARK: - reading

    /// Reads the document and takes what is newer; one read at a time.
    func pull() async {
        guard enabled else { return }
        if let pulling { await pulling.value; return }
        let task = Task { await self.read() }
        pulling = task
        await task.value
        pulling = nil
    }

    private func read() async {
        guard let model, let account = model.accountID else { return }
        do {
            let titles = try await model.syncAuthorized { token in try await self.client.get(token: token) }
            guard model.accountID == account else { return }
            apply(titles, account: account)
            seed(account: account, remote: titles)
        } catch {
            // Offline or refused: the next launch or return from the background reads again.
        }
        schedule()
    }

    /// What the server holds, taken where it is newer than this device's (`SyncRules.newer`); and
    /// out of the outbox, what the server already holds.
    private func apply(_ titles: SyncTitles, account: Int64) {
        guard let model, model.accountID == account, !titles.isEmpty else { return }
        // Only what the answer names is compared, so only its titles' positions are handed over.
        let named = Set(titles.keys.compactMap { Int($0) })
        let newer = SyncRules.shared.newer(remote: titles, local: model.syncState(of: named))
        guard model.applySynced(newer), model.accountID == account else { return }
        var outbox = readOutbox(account)
        var outboxChanged = false
        for (id, title) in titles {
            guard let waiting = outbox[id] else { continue }
            let left = SyncMerge.shared.without(title: waiting, covered: title)
            outbox[id] = left.isEmpty ? nil : left
            outboxChanged = true
        }
        if outboxChanged { writeOutbox(outbox, account: account) }
    }

    /// Once per account: what this device kept before sync, less what the server already holds
    /// (`SyncRules.seed`), finished titles left out.
    private func seed(account: Int64, remote: SyncTitles) {
        guard let model else { return }
        var seeded = (try? model.store.read([Int64].self, key: Self.seededKey)) ?? []
        guard !seeded.contains(account) else { return }
        let batch = SyncRules.shared.seed(local: model.syncState(), finished: KotlinNumbers.set(model.finishedTitles()), remote: remote)
        var outbox = readOutbox(account)
        for (id, left) in batch { outbox[id] = SyncMerge.shared.merge(base: outbox[id], patch: left) }
        writeOutbox(outbox, account: account)
        seeded.append(account)
        try? model.store.write(seeded, key: Self.seededKey)
    }

    private func forgetSeeded(_ account: Int64) {
        guard let store = model?.store, var seeded = try? store.read([Int64].self, key: Self.seededKey),
              seeded.contains(account) else { return }
        seeded.removeAll { $0 == account }
        try? store.write(seeded, key: Self.seededKey)
    }

    // MARK: - writing

    private func markGone(_ animeID: Int) {
        guard let account = model?.accountID else { return }
        let at = now().syncMilliseconds
        var outbox = readOutbox(account)
        let id = String(animeID)
        // Whatever was waiting for this title is older than the tombstone and would be refused anyway.
        let kept = SyncMerge.shared.without(title: outbox[id] ?? .of(), covered: .of(gone: at))
        outbox[id] = kept.with(gone: at)
        writeOutbox(outbox, account: account)
        schedule()
    }

    private func unmarkGone(_ animeID: Int) {
        guard let account = model?.accountID else { return }
        var outbox = readOutbox(account)
        let id = String(animeID)
        guard let title = outbox[id], title.gone != nil else { return }
        let left = title.with(gone: nil)
        outbox[id] = left.isEmpty ? nil : left
        writeOutbox(outbox, account: account)
    }

    private func enqueue(_ animeID: Int, _ change: SyncTitle) {
        guard let account = model?.accountID else { return }
        var outbox = readOutbox(account)
        outbox[String(animeID)] = SyncMerge.shared.merge(base: outbox[String(animeID)], patch: change)
        writeOutbox(outbox, account: account)
        schedule()
    }

    /// The next batch, no sooner than a minute after the last one.
    private func schedule() {
        guard timer == nil, !inflight, let account = model?.accountID, !readOutbox(account).isEmpty else { return }
        let delay = lastPushAt.map { max(0, $0.addingTimeInterval(Self.pushEvery).timeIntervalSince(now())) } ?? 0
        timer = environment.schedule(delay) { [weak self] in
            guard let self else { return }
            self.timer = nil
            self.launch { await self.send(keepAlive: false) }
        }
    }

    private func send(keepAlive: Bool) async {
        guard enabled, let model, let account = model.accountID else { return }
        // A batch on its way goes on; this one follows it at once.
        if inflight { again = true; return }
        let outbox = readOutbox(account)
        guard !outbox.isEmpty else { return }
        timer?.cancel(); timer = nil
        lastPushAt = now()
        inflight = true
        let release = keepAlive ? environment.keepAlive() : nil
        defer { release?() }
        var failed = false
        let ids = outbox.keys.sorted()
        for start in stride(from: 0, to: ids.count, by: Self.titlesPerPost) {
            let batch = SyncTitles(uniqueKeysWithValues: ids[start..<min(ids.count, start + Self.titlesPerPost)].map { ($0, outbox[$0]!) })
            do {
                let answer = try await model.syncAuthorized { token in try await self.client.post(batch, token: token) }
                guard model.accountID == account else { inflight = false; return }
                accepted(batch, account: account)
                apply(answer, account: account)
            } catch {
                // Kept in the outbox; the next batch carries it a minute later.
                failed = true
                break
            }
        }
        inflight = false
        guard model.accountID == account else { again = false; return }
        if again, !failed {
            again = false
            await send(keepAlive: keepAlive)
            return
        }
        again = false
        schedule()
    }

    /// Out of the outbox: what the worker took, unless it changed again meanwhile.
    private func accepted(_ batch: SyncTitles, account: Int64) {
        var outbox = readOutbox(account)
        for (id, sent) in batch {
            guard let waiting = outbox[id] else { continue }
            let left = SyncMerge.shared.without(title: waiting, covered: sent)
            outbox[id] = left.isEmpty ? nil : left
        }
        writeOutbox(outbox, account: account)
    }

    private func launch(_ work: @escaping @MainActor () async -> Void) {
        let id = UUID()
        running[id] = Task { @MainActor in
            await work()
            self.running[id] = nil
        }
    }

    // MARK: - the outbox, one per account

    private func outboxKey(_ account: Int64) -> String { "user-\(account).sync.outbox" }

    private func readOutbox(_ account: Int64) -> SyncTitles {
        guard let stored = try? model?.store.read([String: StoredSyncTitle].self, key: outboxKey(account)) else { return [:] }
        return stored.mapValues(\.title)
    }

    private func writeOutbox(_ titles: SyncTitles, account: Int64) {
        guard let store = model?.store else { return }
        // A store that will not write keeps nothing past this launch; the positions themselves are
        // still on the device, and nothing here is worth an error in front of the viewer.
        if titles.isEmpty { try? store.remove([outboxKey(account)]) }
        else { try? store.write(titles.mapValues(StoredSyncTitle.init), key: outboxKey(account)) }
    }

    private static func statuses(_ library: [LibraryItem]) -> [Int: String] {
        library.reduce(into: [:]) { $0[$1.anime.id] = $1.status }
    }
}

/// One title of the outbox as it is kept on the device: the `/sync` wire shape of a title, every
/// absent field left out — exactly what builds before the shared rules wrote with their own
/// Codable copies of the document, so an outbox one of them left behind still goes out after an
/// update. Only storage: nothing is decided on it, it is turned into the shared `SyncTitle` first.
private struct StoredSyncTitle: Codable {
    struct Position: Codable { var p: Int64; var d: Int64; var at: Int64 }
    struct Dub: Codable { var id: Int; var title: String; var at: Int64 }
    struct Secret: Codable { var on: Bool; var watched: Int; var at: Int64 }
    var dub: Dub?
    var eps: [String: Position]?
    var secret: Secret?
    var gone: Int64?

    init(_ title: SyncTitle) {
        dub = title.dub.map { Dub(id: Int($0.id), title: $0.title, at: $0.at) }
        eps = title.eps?.mapValues { Position(p: $0.p, d: $0.d, at: $0.at) }
        secret = title.secret.map { Secret(on: $0.on, watched: Int($0.watched), at: $0.at) }
        gone = title.goneAt
    }

    var title: SyncTitle {
        .of(dub: dub.map { SyncDub(id: Int32(clamping: $0.id), title: $0.title, at: $0.at) },
            eps: eps?.mapValues { SyncPosition(p: $0.p, d: $0.d, at: $0.at) },
            secret: secret.map { SyncSecret(on: $0.on, watched: Int32(clamping: $0.watched), at: $0.at) },
            gone: gone)
    }
}
