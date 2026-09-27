import Foundation
import KaeruShared

// Thin Swift faces for the sync and «украдкой» rules in shared/ (shared/src/commonMain/.../domain/sync/),
// the same ones Android calls. The `/sync` document is the shared `SyncTitle`, `SyncDub`,
// `SyncPosition` and `SyncSecret`; merging, the wire format, what of the server's is newer, the
// first full send and every «украдкой» decision are shared/'s too, and tested there. This file only
// turns this app's seconds, dates and Ints into the milliseconds and boxed numbers Kotlin speaks.
// Keeping the outbox, when it goes out, the network and Shikimori stay Swift (SyncService, AppModel).

/// By anime id, as a string: the key the worker uses.
typealias SyncTitles = [String: SyncTitle]

extension SyncTitle {
    /// A title with only what is given. Kotlin's defaults do not reach Swift, nor does a bare `init()`.
    static func of(dub: SyncDub? = nil, eps: [String: SyncPosition]? = nil, secret: SyncSecret? = nil, gone: Int64? = nil) -> SyncTitle {
        SyncTitle(dub: dub, eps: eps, gone: gone.map { KotlinLong(value: $0) }, secret: secret)
    }

    /// The tombstone as a plain number.
    var goneAt: Int64? { gone?.int64Value }

    /// The same title with another tombstone, or with none.
    func with(gone: Int64?) -> SyncTitle {
        doCopy(dub: dub, eps: eps, gone: gone.map { KotlinLong(value: $0) }, secret: secret)
    }
}

extension SyncDub {
    /// A chosen dub as it goes out: the name cut to what the worker takes, counted in UTF-16 units
    /// as Kotlin's `take` counts them — a longer one would have the whole batch refused.
    static func named(id: Int, title: String, at: Int64) -> SyncDub {
        let name = title as NSString
        let limit = Int(SyncRules.shared.MAX_DUB_TITLE)
        return SyncDub(id: Int32(clamping: id), title: name.length > limit ? name.substring(to: limit) : title, at: at)
    }
}

extension EpisodeProgress {
    /// This position as the shared rules count it: whole milliseconds, saved at an epoch millisecond.
    var episodePosition: EpisodePosition {
        EpisodePosition(animeId: Int32(clamping: animeID), episode: Int32(clamping: episode),
                        positionMs: position.mediaMilliseconds, durationMs: duration.mediaMilliseconds,
                        at: updatedAt.syncMilliseconds)
    }

    /// A position another device saved, back in this app's seconds.
    init(_ position: EpisodePosition) {
        self.init(animeID: Int(position.animeId), episode: Int(position.episode), position: position.positionMs.mediaSeconds,
                  duration: position.durationMs.mediaSeconds, updatedAt: Date(syncMilliseconds: position.at))
    }
}

extension SecretTitle {
    /// «Украдкой» as it goes out, and as the shared rules compare it: never below zero.
    var wire: SyncSecret { SyncSecret(on: on, watched: Int32(clamping: max(0, watched)), at: at) }

    /// Every episode of a released show watched and nothing more on the schedule (`SecretRules.finished`),
    /// with Shikimori's status read as Android reads it. Never while it is off, or its card is not here.
    func finished(now: Date = Date()) -> Bool {
        guard on, let anime else { return false }
        return SecretRules.shared.finished(released: EpisodeQueue.airing(anime.status) == .released,
                                           announcedEpisodes: Int32(clamping: anime.episodes),
                                           watched: Int32(clamping: watched),
                                           nextEpisodeAtMs: anime.nextAirDate.map { KotlinLong(value: $0.syncMilliseconds) },
                                           nowMs: now.syncMilliseconds)
    }
}

/// The «украдкой» count (`SecretRules`), over plain Ints. «Counted» is what Shikimori's rate holds;
/// nil when there is no rate.
enum SecretCount {
    /// Turned on: the count starts from Shikimori's, whose record is left as it is.
    static func whenTurnedOn(counted: Int?) -> Int {
        Int(SecretRules.shared.watchedWhenTurnedOn(counted: counted?.kotlin))
    }
    /// Turned off: what goes to Shikimori once, or nil when the rate already says as much.
    static func toSendWhenTurnedOff(watched: Int, counted: Int?) -> Int? {
        SecretRules.shared.episodesToSendWhenTurnedOff(watched: Int32(clamping: watched), counted: counted?.kotlin)?.intValue
    }
    /// An episode marked or unmarked: the new count, or nil when it is that already and nothing changes.
    static func afterMark(watched: Int, episodes: Int) -> Int? {
        SecretRules.shared.watchedAfterMark(watched: Int32(clamping: watched), episodes: Int32(clamping: episodes))?.intValue
    }
}

extension LocalSyncState {
    /// What this device holds, as the shared rules read it: its positions, the dub each title
    /// remembers — named only once something named it — and when it was chosen, and «украдкой» on or off.
    static func of(positions: [EpisodeProgress], translations: [Int: Int], stamps: [Int: DubStamp],
                   secrets: [Int: SecretTitle]) -> LocalSyncState {
        var dubs: [KotlinInt: RememberedDub] = [:]
        for (animeID, id) in translations {
            let name = stamps[animeID]?.title ?? ""
            dubs[animeID.kotlin] = RememberedDub(id: Int32(clamping: id), title: name.isEmpty ? nil : name)
        }
        var chosen: [KotlinInt: KotlinLong] = [:]
        for (animeID, stamp) in stamps { chosen[animeID.kotlin] = KotlinLong(value: stamp.at) }
        var states: [KotlinInt: SyncSecret] = [:]
        for (animeID, secret) in secrets { states[animeID.kotlin] = secret.wire }
        return LocalSyncState(positions: positions.map(\.episodePosition), dubs: dubs, dubStamps: chosen, secrets: states)
    }
}

extension SyncNewer {
    /// By anime id: positions saved at or before the moment are dead.
    var tombstoneMoments: [Int: Int64] { KotlinNumbers.longs(tombstones) }
    /// Only where the remembered dub actually changes.
    var dubChanges: [Int: RememberedDub] { KotlinNumbers.keyed(dubs) }
    /// When each taken dub was chosen — also where the dub itself is already the same.
    var dubMoments: [Int: Int64] { KotlinNumbers.longs(dubStamps) }
}

extension KotlinNumbers {
    /// A set of anime ids for Kotlin to look numbers up in.
    static func set(_ values: Set<Int>) -> Set<KotlinInt> { Set(values.map(\.kotlin)) }

    static func longs(_ map: [KotlinInt: KotlinLong]) -> [Int: Int64] {
        var result: [Int: Int64] = [:]
        for (key, value) in map as NSDictionary {
            result[(key as! NSNumber).intValue] = (value as! NSNumber).int64Value
        }
        return result
    }

    /// Kotlin objects keyed by a number: only the keys need reading through Foundation.
    static func keyed<Value>(_ map: [KotlinInt: Value]) -> [Int: Value] {
        var result: [Int: Value] = [:]
        for (key, value) in map as NSDictionary {
            result[(key as! NSNumber).intValue] = (value as! Value)
        }
        return result
    }
}
