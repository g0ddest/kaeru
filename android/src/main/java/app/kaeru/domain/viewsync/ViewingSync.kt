package app.kaeru.domain.viewsync

import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.ListStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Viewing sync through the worker (spec 2026-09-26-kaeru-sync-design.md §4), after the web client
 * (web/src/sync/service.ts) and the iOS one (ios/Core/SyncService.swift).
 *
 * Reads the document when an account is signed in — at launch or just now — and when the app comes
 * back after five minutes in the background, and takes whatever is newer than this device's. Sends
 * this device's positions and dubs in batches: at most one a minute, at once on a pause, on another
 * episode, on leaving the player, on going to the background and on the network coming back; and a
 * tombstone for a title the list turns «completed». A batch that fails stays in the outbox, which
 * outlives the process, for the next try.
 *
 * Only while the viewer has turned it on in the settings: off, nothing is read, sent or queued.
 *
 * Local positions and dubs belong to the signed-in account already: a sign-out or a sign-in as
 * somebody else empties them (`KaeruDatabase.clearAccountData`). The outbox is tagged with its
 * account, and read as empty under any other, so one account's changes can never go out with
 * another's token. Without an account nothing is read or sent.
 *
 * Nothing here throws at a caller or makes one wait. Every entry point hands its work to [scope],
 * which has to run one coroutine at a time: the fields below are touched only from it.
 */
class ViewingSync(
    private val api: ViewingSyncApi,
    private val local: LocalViewing,
    private val store: SyncStateStore,
    private val accounts: Flow<Long?>,
    /** The viewer's switch: while it is off, nothing is read, sent or queued. */
    private val enabled: Flow<Boolean>,
    private val online: Flow<Boolean>,
    private val events: ViewingSyncEvents,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    private var started = false
    private var account: Long? = null
    private var timer: Job? = null
    private var lastPushAt: Long? = null
    private var inflight = false
    private var again = false
    private var pulling: Job? = null
    private var backgroundedAt: Long? = null

    /** Each listed title's status as last seen for this account; null until a list is first known. */
    private var statuses: Map<Int, ListStatus>? = null

    /** The list as it stands, for [finished]; kept even before a baseline is taken. */
    private var latest: Map<Int, ListStatus> = emptyMap()

    private val outboxLock = Mutex()

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(accounts.distinctUntilChanged(), enabled.distinctUntilChanged()) { id, on -> id to on }
                .distinctUntilChanged()
                .catch { }
                .collect { (id, on) -> guarded { onSettings(id, on) } }
        }
        scope.launch {
            events.events.collect { event -> guarded { onEvent(event) } }
        }
        scope.launch {
            local.statuses().catch { }.collect { current -> guarded { onStatuses(current) } }
        }
        scope.launch {
            // The first value is where things stand; only a return of the network is news.
            online.distinctUntilChanged().drop(1).filter { it }.catch { }.collect { sendSoon() }
        }
    }

    /** The app went out of sight: whatever is waiting goes now, and the clock for a re-read starts. */
    fun wentToBackground() {
        scope.launch {
            if (backgroundedAt == null) backgroundedAt = now()
            guarded { send() }
        }
    }

    /** The app is in front again: after five minutes away, another device may have played meanwhile. */
    fun becameActive() {
        scope.launch {
            val at = backgroundedAt ?: return@launch
            backgroundedAt = null
            if (now() - at >= PULL_AFTER_BACKGROUND_MS) pull()
        }
    }

    // --- what the app says --------------------------------------------------------------------

    /**
     * Sync runs for the signed-in account while the switch is on. Turning it off drops what was
     * waiting and forgets that this device was ever seeded, so turning it on again sends what the
     * device holds by then — nothing it did meanwhile was queued.
     */
    private suspend fun onSettings(id: Long?, on: Boolean) {
        val previous = account
        onAccount(if (on) id else null)
        if (!on && previous != null) {
            editOutbox(previous) { it.clear() }
            store.forgetSeeded(previous)
        }
    }

    private fun onAccount(id: Long?) {
        // Whatever was scheduled was the previous account's, and its list is not this one's.
        timer?.cancel()
        timer = null
        pulling?.cancel()
        pulling = null
        lastPushAt = null
        again = false
        backgroundedAt = null
        // The list already known is this account's own at launch; after a switch it is empty by
        // the time the new account is, which leaves the first list to arrive as the baseline.
        statuses = latest.takeIf { id != null && it.isNotEmpty() }
        account = id
        if (id != null) pull()
    }

    private suspend fun onEvent(event: ViewingSyncEvents.Event) {
        when (event) {
            is ViewingSyncEvents.Event.Position -> positionSaved(event.progress)
            is ViewingSyncEvents.Event.Dub -> dubChosen(event.animeId, event.dub)
            is ViewingSyncEvents.Event.Push -> sendSoon()
        }
    }

    private suspend fun positionSaved(progress: EpisodeProgress) {
        val acc = account ?: return
        if (finished(progress.animeId) || progress.durationMs <= 0) return
        enqueue(acc, progress.animeId, SyncTitle(eps = mapOf(progress.episode.toString() to position(progress))))
    }

    private suspend fun dubChosen(animeId: Int, dub: RememberedDub) {
        val acc = account ?: return
        val title = dub.title ?: return
        if (finished(animeId)) return
        val at = now()
        store.setDubStamps(acc, store.dubStamps(acc) + (animeId to at))
        enqueue(acc, animeId, SyncTitle(dub = SyncDub(dub.id, title.take(MAX_DUB_TITLE), at)))
    }

    /** A title turning «completed» leaves a tombstone; turning back before it went out takes it back. */
    private suspend fun onStatuses(current: Map<Int, ListStatus>) {
        latest = current
        val acc = account ?: return
        val before = statuses
        if (before.isNullOrEmpty()) {
            // The first list seen is where things stand, not a change. An empty one says nothing
            // yet: it is what a fresh sign-in has before Shikimori answers, and what a sign-out
            // leaves for a moment before the next account's list arrives whole.
            statuses = current.takeIf { it.isNotEmpty() }
            return
        }
        statuses = current
        for ((animeId, status) in current) {
            val was = before[animeId]
            if (status == ListStatus.COMPLETED && was != ListStatus.COMPLETED) {
                markGone(acc, animeId)
            } else if (status != ListStatus.COMPLETED && was == ListStatus.COMPLETED) {
                unmarkGone(acc, animeId)
            }
        }
    }

    private fun finished(animeId: Int): Boolean = latest[animeId] == ListStatus.COMPLETED

    // --- reading ------------------------------------------------------------------------------

    /** Reads the document and takes what is newer; one read at a time. */
    private fun pull() {
        if (pulling?.isActive == true) return
        pulling = scope.launch { guarded { read() } }
    }

    private suspend fun read() {
        val acc = account ?: return
        val titles = try {
            api.get()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Offline or refused: the next sign-in or return from the background reads again. What
            // is waiting still goes on its own schedule.
            schedule()
            return
        }
        if (account != acc) return
        apply(acc, titles)
        seed(acc, titles)
        schedule()
    }

    /** What the server holds, taken where it is newer than this device's. */
    private suspend fun apply(acc: Long, titles: SyncTitles) {
        if (titles.isEmpty()) return
        val known = local.positions().associate { (it.animeId to it.episode) to it.updatedAt.toEpochMilli() }
        val dubsHere = local.dubs()
        val stamps = store.dubStamps(acc)
        val positions = mutableListOf<EpisodeProgress>()
        val tombstones = mutableMapOf<Int, Instant>()
        val dubs = mutableMapOf<Int, RememberedDub>()
        val stamped = mutableMapOf<Int, Long>()
        for ((id, title) in titles) {
            val animeId = id.toIntOrNull() ?: continue
            title.gone?.let { tombstones[animeId] = Instant.ofEpochMilli(it) }
            for ((key, remote) in title.eps.orEmpty()) {
                val episode = key.toIntOrNull() ?: continue
                // A position with no length cannot be resumed from; the players never write one.
                if (episode <= 0 || remote.d <= 0) continue
                val here = known[animeId to episode]
                if (here != null && here >= remote.at) continue
                positions += EpisodeProgress(
                    animeId = animeId,
                    episode = episode,
                    positionMs = remote.p.coerceIn(0, remote.d),
                    durationMs = remote.d,
                    updatedAt = Instant.ofEpochMilli(remote.at),
                )
            }
            val dub = title.dub
            if (dub != null && dub.id != 0) {
                // A dub remembered before stamps existed counts as the oldest there is.
                val mine = stamps[animeId] ?: dubsHere[animeId]?.let { 0L }
                if (mine == null || mine < dub.at) {
                    if (dubsHere[animeId]?.let { it.id == dub.id && it.title == dub.title } != true) {
                        dubs[animeId] = RememberedDub(dub.id, dub.title)
                    }
                    stamped[animeId] = dub.at
                }
            }
        }
        val change = SyncedViewing(positions, tombstones, dubs)
        val written = change.isEmpty || local.apply(acc, change)
        if (account != acc || !written) return
        if (stamped.isNotEmpty()) store.setDubStamps(acc, store.dubStamps(acc) + stamped)
        editOutbox(acc) { outbox ->
            for ((id, title) in titles) {
                val waiting = outbox[id] ?: continue
                val left = SyncMerge.without(waiting, title)
                if (left.isEmpty) outbox.remove(id) else outbox[id] = left
            }
        }
    }

    /** Once per account: what this device kept before sync, where it is newer than the server's. */
    private suspend fun seed(acc: Long, remote: SyncTitles) {
        if (acc in store.seeded()) return
        val batch = mutableMapOf<String, SyncTitle>()
        for ((animeId, rows) in local.positions().groupBy { it.animeId }) {
            if (finished(animeId)) continue
            val eps = rows.filter { it.durationMs > 0 }
                .sortedByDescending { it.updatedAt }
                .take(EPISODES_PER_TITLE)
                .associate { it.episode.toString() to position(it) }
            if (eps.isNotEmpty()) batch[animeId.toString()] = SyncTitle(eps = eps)
        }
        val stamps = store.dubStamps(acc)
        for ((animeId, dub) in local.dubs()) {
            val title = dub.title ?: continue
            if (finished(animeId)) continue
            val id = animeId.toString()
            batch[id] = (batch[id] ?: SyncTitle()).copy(
                dub = SyncDub(dub.id, title.take(MAX_DUB_TITLE), stamps[animeId] ?: 0L),
            )
        }
        if (account != acc) return
        editOutbox(acc) { outbox ->
            for ((id, title) in batch) {
                val left = SyncMerge.without(title, remote[id] ?: SyncTitle())
                if (!left.isEmpty) outbox[id] = SyncMerge.merge(outbox[id], left)
            }
        }
        store.markSeeded(acc)
    }

    // --- writing ------------------------------------------------------------------------------

    private suspend fun markGone(acc: Long, animeId: Int) {
        val at = now()
        val id = animeId.toString()
        editOutbox(acc) { outbox ->
            // Whatever was waiting for this title is older than the tombstone and would be refused anyway.
            val kept = SyncMerge.without(outbox[id] ?: SyncTitle(), SyncTitle(gone = at))
            outbox[id] = kept.copy(gone = at)
        }
        schedule()
    }

    private suspend fun unmarkGone(acc: Long, animeId: Int) {
        val id = animeId.toString()
        editOutbox(acc) { outbox ->
            val title = outbox[id]
            if (title?.gone != null) {
                val left = title.copy(gone = null)
                if (left.isEmpty) outbox.remove(id) else outbox[id] = left
            }
        }
    }

    private suspend fun enqueue(acc: Long, animeId: Int, change: SyncTitle) {
        val id = animeId.toString()
        editOutbox(acc) { outbox -> outbox[id] = SyncMerge.merge(outbox[id], change) }
        schedule()
    }

    /** At once, whatever the minute says: a pause, a new episode, the player going away, the network back. */
    private fun sendSoon() {
        scope.launch { guarded { send() } }
    }

    /** The next batch, no sooner than a minute after the last one. */
    private suspend fun schedule() {
        val acc = account ?: return
        val empty = store.outbox(acc).isEmpty()
        // Decided after the read and without suspending again, so two callers cannot both start a timer.
        if (empty || timer?.isActive == true || inflight || account != acc) return
        val wait = lastPushAt?.let { (it + PUSH_EVERY_MS - now()).coerceAtLeast(0) } ?: 0L
        timer = scope.launch {
            delay(wait)
            timer = null
            guarded { send() }
        }
    }

    private suspend fun send() {
        val acc = account ?: return
        // A batch on its way goes on; this one follows it at once.
        if (inflight) {
            again = true
            return
        }
        inflight = true
        var failed = false
        try {
            val outbox = store.outbox(acc)
            if (outbox.isEmpty() || account != acc) return
            timer?.cancel()
            timer = null
            lastPushAt = now()
            for (ids in outbox.keys.sorted().chunked(TITLES_PER_POST)) {
                val batch = ids.associateWith { outbox.getValue(it) }
                val answer = api.post(batch)
                if (account != acc) return
                accepted(acc, batch)
                apply(acc, answer)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Kept in the outbox; the next batch carries it a minute later.
            failed = true
        } finally {
            inflight = false
        }
        if (account != acc) {
            again = false
            return
        }
        if (again && !failed) {
            again = false
            send()
            return
        }
        again = false
        schedule()
    }

    /** Out of the outbox: what the worker took, unless it changed again meanwhile. */
    private suspend fun accepted(acc: Long, batch: SyncTitles) {
        editOutbox(acc) { outbox ->
            for ((id, sent) in batch) {
                val waiting = outbox[id] ?: continue
                val left = SyncMerge.without(waiting, sent)
                if (left.isEmpty) outbox.remove(id) else outbox[id] = left
            }
        }
    }

    private suspend fun editOutbox(acc: Long, change: (MutableMap<String, SyncTitle>) -> Unit) {
        outboxLock.withLock {
            val outbox = store.outbox(acc).toMutableMap()
            change(outbox)
            store.setOutbox(acc, outbox)
        }
    }

    /** Sync is never worth a crash: storage or network trouble here is simply tried again later. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Deliberately silent; see above.
        }
    }

    companion object {
        /** At most one batch a minute while watching (spec §3: D1's free tier writes). */
        const val PUSH_EVERY_MS = 60_000L

        /** Back from the background after this long: another device may have played meanwhile. */
        const val PULL_AFTER_BACKGROUND_MS = 5 * 60_000L

        /** The worker keeps the 30 latest episodes of a title; older ones would only be trimmed again. */
        const val EPISODES_PER_TITLE = 30

        /** Titles per POST, well under the worker's 256 KB body with 30 episodes each. */
        const val TITLES_PER_POST = 100

        /** The worker refuses a longer dub name, and with it the whole batch. */
        const val MAX_DUB_TITLE = 200

        fun position(progress: EpisodeProgress) = SyncPosition(
            p = progress.positionMs.coerceAtLeast(0),
            d = progress.durationMs.coerceAtLeast(0),
            at = progress.updatedAt.toEpochMilli(),
        )
    }
}
