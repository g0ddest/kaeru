import Foundation
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

/// Viewing sync through the worker (spec 2026-09-26-kaeru-sync-design.md §4), after the web
/// client (web/src/sync/service.ts): reads the document on start and after five minutes in the
/// background and takes whatever is newer than this device's; sends this device's positions and
/// dubs in batches — at most one a minute, at once on pause, on another episode, on leaving the
/// player and on going to the background — and a tombstone for a title the list marks «completed».
/// A batch that fails stays in the outbox for the next try, across launches.
///
/// Nothing here throws at a caller or makes one wait: the player only ever hands over a position.
/// Local data is per account already (every record is keyed by `AppModel.accountKey`), and so is
/// the outbox, so one account's positions and unsent changes can never go out with another's token.
@MainActor final class SyncService {
    /// At most one batch a minute while watching (spec §3: D1's free tier writes).
    static let pushEvery: TimeInterval = 60
    /// Back from the background after this long: another device may have played meanwhile.
    static let pullAfterBackground: TimeInterval = 5 * 60
    /// The worker keeps the 30 latest episodes of a title; older ones would only be trimmed again.
    static let episodesPerTitle = 30
    /// Titles per POST, well under the worker's 256 KB body with 30 episodes each.
    static let titlesPerPost = 100
    /// Accounts whose positions this device already sent once in full. Not per account on purpose:
    /// it is a list of them, and sign-out leaves it alone.
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
    /// device has, once); off stops everything and drops what was waiting to go.
    func setEnabled(_ on: Bool) {
        if on { statuses = nil; start(); return }
        timer?.cancel(); timer = nil
        again = false
        if let account = model?.accountID { writeOutbox([:], account: account) }
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
        enqueue(value.animeID, SyncTitle(eps: [String(value.episode): Self.position(value)]))
    }

    func dubChosen(_ animeID: Int, _ dub: SyncDub) {
        guard enabled, let model, model.accountID != nil, !model.isFinished(animeID) else { return }
        enqueue(animeID, SyncTitle(dub: dub))
    }

    /// The list as it now stands. A title turning «completed» leaves a tombstone; turning back
    /// before the tombstone went out takes it back. The first list seen is where things stand.
    func libraryChanged(_ library: [LibraryItem]) {
        guard enabled, model?.accountID != nil else { return }
        let current = Self.statuses(library)
        // The first list seen is where things stand, not a change. An empty one says nothing yet:
        // it is what a fresh install has before Shikimori answers.
        guard let before = statuses else {
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

    /// What the server holds, taken where it is newer than this device's.
    private func apply(_ titles: SyncTitles, account: Int64) {
        guard let model, model.accountID == account else { return }
        var outbox = readOutbox(account)
        var outboxChanged = false
        var positions: [EpisodeProgress] = []
        var tombstones: [Int: Date] = [:]
        var dubs: [Int: SyncDub] = [:]
        for (id, title) in titles {
            guard let animeID = Int(id) else { continue }
            if let gone = title.gone { tombstones[animeID] = Date(syncMilliseconds: gone) }
            for (episode, position) in title.eps ?? [:] {
                // A position with no length cannot be resumed from; the players never write one.
                guard let number = Int(episode), number > 0, position.d > 0 else { continue }
                if let known = model.progressFor(animeID: animeID, episode: number),
                   known.updatedAt.syncMilliseconds >= position.at { continue }
                positions.append(EpisodeProgress(animeID: animeID, episode: number, position: Double(max(0, position.p)) / 1000,
                                                 duration: Double(position.d) / 1000, updatedAt: Date(syncMilliseconds: position.at)))
            }
            if let dub = title.dub { dubs[animeID] = dub }
            if let waiting = outbox[id] {
                let left = Self.without(waiting, covered: title)
                outbox[id] = left.isEmpty ? nil : left
                outboxChanged = true
            }
        }
        model.applySynced(positions: positions, tombstones: tombstones, dubs: dubs)
        if outboxChanged { writeOutbox(outbox, account: account) }
    }

    /// Once per account: what this device kept before sync, where it is newer than the server's.
    private func seed(account: Int64, remote: SyncTitles) {
        guard let model else { return }
        var seeded = (try? model.store.read([Int64].self, key: Self.seededKey)) ?? []
        guard !seeded.contains(account) else { return }
        var batch: SyncTitles = [:]
        let byTitle = Dictionary(grouping: model.episodeHistory.values, by: \.animeID)
        for (animeID, rows) in byTitle where !model.isFinished(animeID) {
            let latest = rows.sorted { $0.updatedAt > $1.updatedAt }.prefix(Self.episodesPerTitle)
            let eps = Dictionary(latest.map { (String($0.episode), Self.position($0)) }) { first, _ in first }
            batch[String(animeID)] = SyncTitle(eps: eps)
        }
        for (animeID, dub) in model.stampedDubs() where !model.isFinished(animeID) {
            batch[String(animeID), default: SyncTitle()].dub = dub
        }
        var outbox = readOutbox(account)
        for (id, title) in batch {
            let left = Self.without(title, covered: remote[id] ?? SyncTitle())
            if !left.isEmpty { outbox[id] = Self.merge(outbox[id], left) }
        }
        writeOutbox(outbox, account: account)
        seeded.append(account)
        try? model.store.write(seeded, key: Self.seededKey)
    }

    // MARK: - writing

    private func markGone(_ animeID: Int) {
        guard let account = model?.accountID else { return }
        let at = now().syncMilliseconds
        var outbox = readOutbox(account)
        let id = String(animeID)
        // Whatever was waiting for this title is older than the tombstone and would be refused anyway.
        var kept = Self.without(outbox[id] ?? SyncTitle(), covered: SyncTitle(gone: at))
        kept.gone = at
        outbox[id] = kept
        writeOutbox(outbox, account: account)
        schedule()
    }

    private func unmarkGone(_ animeID: Int) {
        guard let account = model?.accountID else { return }
        var outbox = readOutbox(account)
        let id = String(animeID)
        guard var title = outbox[id], title.gone != nil else { return }
        title.gone = nil
        outbox[id] = title.isEmpty ? nil : title
        writeOutbox(outbox, account: account)
    }

    private func enqueue(_ animeID: Int, _ change: SyncTitle) {
        guard let account = model?.accountID else { return }
        var outbox = readOutbox(account)
        outbox[String(animeID)] = Self.merge(outbox[String(animeID)], change)
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
            let left = Self.without(waiting, covered: sent)
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
        (try? model?.store.read(SyncTitles.self, key: outboxKey(account))) ?? [:]
    }

    private func writeOutbox(_ titles: SyncTitles, account: Int64) {
        guard let store = model?.store else { return }
        // A store that will not write keeps nothing past this launch; the positions themselves are
        // still on the device, and nothing here is worth an error in front of the viewer.
        if titles.isEmpty { try? store.remove([outboxKey(account)]) }
        else { try? store.write(titles, key: outboxKey(account)) }
    }

    // MARK: - merging, as the worker does it

    static func position(_ value: EpisodeProgress) -> SyncPosition {
        SyncPosition(p: Int64((max(0, value.position) * 1000).rounded()), d: Int64((max(0, value.duration) * 1000).rounded()),
                     at: value.updatedAt.syncMilliseconds)
    }

    private static func statuses(_ library: [LibraryItem]) -> [Int: String] {
        library.reduce(into: [:]) { $0[$1.anime.id] = $1.status }
    }

    /// `patch` over `base`, the newer `at` winning per field and per episode.
    static func merge(_ base: SyncTitle?, _ patch: SyncTitle) -> SyncTitle {
        var out = base ?? SyncTitle()
        if let dub = patch.dub, (out.dub?.at ?? .min) <= dub.at { out.dub = dub }
        if let gone = patch.gone, (out.gone ?? .min) <= gone { out.gone = gone }
        if let eps = patch.eps {
            var merged = out.eps ?? [:]
            for (episode, position) in eps where (merged[episode]?.at ?? .min) <= position.at { merged[episode] = position }
            out.eps = merged
        }
        return out
    }

    /// `title` without what `covered` (a sent batch or the server) already holds: anything no newer.
    static func without(_ title: SyncTitle, covered: SyncTitle) -> SyncTitle {
        var out = title
        let floor = covered.gone ?? .min
        if let dub = out.dub, dub.at <= floor || (covered.dub.map { dub.at <= $0.at } ?? false) { out.dub = nil }
        if let gone = out.gone, gone <= floor { out.gone = nil }
        if let eps = out.eps {
            let left = eps.filter { episode, position in
                position.at > floor && !(covered.eps?[episode].map { position.at <= $0.at } ?? false)
            }
            out.eps = left.isEmpty ? nil : left
        }
        return out
    }
}
