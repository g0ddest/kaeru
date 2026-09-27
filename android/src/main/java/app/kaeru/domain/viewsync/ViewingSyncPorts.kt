package app.kaeru.domain.viewsync

import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.SecretTitle
import app.kaeru.shared.domain.sync.RememberedDub
import app.kaeru.shared.domain.sync.SyncTitles
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.time.Instant

/** Why the batch should go now rather than within the minute. */
enum class SyncReason { PAUSE, EPISODE_CHANGE, LEAVING }

/** Why a `/sync` request failed, other than the 401 that goes out as the session's own failure. */
class SyncFailure(val kind: Kind) : Exception("Sync ${kind.name.lowercase()}") {
    enum class Kind { SIGNED_OUT, OFFLINE, THROTTLED, UNAVAILABLE, PARAMETERS, PARSER, UNKNOWN }
}

/** `GET /sync` and `POST /sync` with the signed-in account's bearer. */
interface ViewingSyncApi {
    suspend fun get(): SyncTitles

    /** Sends a batch; the answer is the whole merged document. */
    suspend fun post(titles: SyncTitles): SyncTitles
}

/** What another device did, already found to be newer than this one's. */
data class SyncedViewing(
    val positions: List<EpisodeProgress> = emptyList(),
    /** Positions saved at or before these moments are dropped. */
    val tombstones: Map<Int, Instant> = emptyMap(),
    val dubs: Map<Int, RememberedDub> = emptyMap(),
    /** «Украдкой» as another device left it; each written only over an older one. */
    val secrets: List<SecretTitle> = emptyList(),
) {
    val isEmpty: Boolean get() = positions.isEmpty() && tombstones.isEmpty() && dubs.isEmpty() && secrets.isEmpty()
}

/**
 * This device's positions and dubs, as sync reads and writes them.
 *
 * Writes go straight to storage, around the playback path: nothing written here is marked on
 * Shikimori, and nothing comes back to sync as a change of this device's own.
 */
interface LocalViewing {
    suspend fun positions(): List<EpisodeProgress>

    suspend fun dubs(): Map<Int, RememberedDub>

    /** «Украдкой» as this device has it, on or off, by anime id. */
    suspend fun secrets(): Map<Int, SecretTitle>

    /**
     * Every listed title's status, as the local copy of the list has it. A title watched
     * «украдкой» is [ListStatus.SECRET] — or [ListStatus.COMPLETED] once every episode of a
     * finished show is behind the viewer, which is when it leaves a tombstone.
     */
    fun statuses(): Flow<Map<Int, ListStatus>>

    /**
     * Writes [change] while [account] is still the one signed in. A position is written only over
     * an older one, checked again inside the write. Returns whether anything was written.
     */
    suspend fun apply(account: Long, change: SyncedViewing): Boolean
}

/** What sync keeps across launches: the unsent changes, who has been seeded, when dubs were chosen. */
interface SyncStateStore {
    /** Changes not yet accepted by the worker, for [account] only — another account's read as none. */
    suspend fun outbox(account: Long): SyncTitles

    suspend fun setOutbox(account: Long, titles: SyncTitles)

    /** Accounts whose positions this device has already sent once in full. */
    suspend fun seeded(): Set<Long>

    suspend fun markSeeded(account: Long)

    /** Sync was turned off: the next time it is on, this device's positions go once in full again. */
    suspend fun forgetSeeded(account: Long)

    /** When each title's dub was chosen here, for [account] only. */
    suspend fun dubStamps(account: Long): Map<Int, Long>

    suspend fun setDubStamps(account: Long, stamps: Map<Int, Long>)
}

/**
 * What the rest of the app tells sync, without knowing it: a position written, a dub chosen, the
 * player asking for the batch to go now.
 *
 * A channel rather than a call into sync, so the repositories and the player depend on nothing but
 * this — sync itself depends on half the app. Never blocks: a sender on the playback path must not
 * wait on anything, and a position dropped from a full buffer is followed by another within
 * seconds.
 */
class ViewingSyncEvents {
    sealed interface Event {
        data class Position(val progress: EpisodeProgress) : Event
        data class Dub(val animeId: Int, val dub: RememberedDub) : Event
        data class Secret(val secret: SecretTitle) : Event
        data class Push(val reason: SyncReason) : Event
    }

    private val channel = Channel<Event>(capacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val events: Flow<Event> = channel.receiveAsFlow()

    fun positionSaved(progress: EpisodeProgress) {
        channel.trySend(Event.Position(progress))
    }

    fun dubChosen(animeId: Int, dub: RememberedDub) {
        channel.trySend(Event.Dub(animeId, dub))
    }

    fun secretChanged(secret: SecretTitle) {
        channel.trySend(Event.Secret(secret))
    }

    fun push(reason: SyncReason) {
        channel.trySend(Event.Push(reason))
    }
}
