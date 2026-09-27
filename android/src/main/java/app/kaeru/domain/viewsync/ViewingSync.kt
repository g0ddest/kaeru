package app.kaeru.domain.viewsync

import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.SecretTitle
import app.kaeru.shared.domain.sync.EpisodePosition
import app.kaeru.shared.domain.sync.LocalSyncState
import app.kaeru.shared.domain.sync.RememberedDub
import app.kaeru.shared.domain.sync.SyncDub
import app.kaeru.shared.domain.sync.SyncMerge
import app.kaeru.shared.domain.sync.SyncPosition
import app.kaeru.shared.domain.sync.SyncRules
import app.kaeru.shared.domain.sync.SyncSecret
import app.kaeru.shared.domain.sync.SyncTitle
import app.kaeru.shared.domain.sync.SyncTitles
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
 * this device's positions, dubs and «украдкой» states in batches: at most one a minute, at once on a pause, on another
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
            is ViewingSyncEvents.Event.Secret -> secretChanged(event.secret)
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
        enqueue(acc, animeId, SyncTitle(dub = SyncDub(dub.id, title.take(SyncRules.MAX_DUB_TITLE), at)))
    }

    /**
     * «Украдкой» turned on or off, or another episode counted under it. A finished one still goes:
     * the tombstone the list's turn to «completed» leaves is newer, and the worker drops it then.
     */
    private suspend fun secretChanged(secret: SecretTitle) {
        val acc = account ?: return
        enqueue(acc, secret.animeId, SyncTitle(secret = wire(secret)))
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

    /** What the server holds, taken where it is newer than this device's ([SyncRules.newer]). */
    private suspend fun apply(acc: Long, titles: SyncTitles) {
        if (titles.isEmpty()) return
        val newer = SyncRules.newer(titles, localState(acc))
        val change = SyncedViewing(
            positions = newer.positions.map { position ->
                EpisodeProgress(
                    animeId = position.animeId,
                    episode = position.episode,
                    positionMs = position.positionMs,
                    durationMs = position.durationMs,
                    updatedAt = Instant.ofEpochMilli(position.at),
                )
            },
            tombstones = newer.tombstones.mapValues { (_, at) -> Instant.ofEpochMilli(at) },
            dubs = newer.dubs,
            secrets = newer.secrets.map { secret ->
                SecretTitle(secret.animeId, secret.on, secret.watched, Instant.ofEpochMilli(secret.at))
            },
        )
        val written = change.isEmpty || local.apply(acc, change)
        if (account != acc || !written) return
        if (newer.dubStamps.isNotEmpty()) store.setDubStamps(acc, store.dubStamps(acc) + newer.dubStamps)
        editOutbox(acc) { outbox ->
            for ((id, title) in titles) {
                val waiting = outbox[id] ?: continue
                val left = SyncMerge.without(waiting, title)
                if (left.isEmpty) outbox.remove(id) else outbox[id] = left
            }
        }
    }

    /** Once per account: what this device kept before sync, where it is newer than the server's ([SyncRules.seed]). */
    private suspend fun seed(acc: Long, remote: SyncTitles) {
        if (acc in store.seeded()) return
        val finished = latest.filterValues { it == ListStatus.COMPLETED }.keys
        val batch = SyncRules.seed(localState(acc), finished, remote)
        if (account != acc) return
        editOutbox(acc) { outbox ->
            for ((id, left) in batch) outbox[id] = SyncMerge.merge(outbox[id], left)
        }
        store.markSeeded(acc)
    }

    /** This device's positions, dubs, dub stamps and secrets, as the shared rules read them. */
    private suspend fun localState(acc: Long) = LocalSyncState(
        positions = local.positions().map(::episodePosition),
        dubs = local.dubs(),
        dubStamps = store.dubStamps(acc),
        secrets = local.secrets().mapValues { (_, secret) -> wire(secret) },
    )

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

        /** Titles per POST, well under the worker's 256 KB body with 30 episodes each. */
        const val TITLES_PER_POST = 100

        fun wire(secret: SecretTitle) = SyncSecret(
            on = secret.on,
            watched = secret.watched.coerceAtLeast(0),
            at = secret.at.toEpochMilli(),
        )

        fun position(progress: EpisodeProgress): SyncPosition = SyncRules.wire(episodePosition(progress))

        private fun episodePosition(progress: EpisodeProgress) = EpisodePosition(
            animeId = progress.animeId,
            episode = progress.episode,
            positionMs = progress.positionMs,
            durationMs = progress.durationMs,
            at = progress.updatedAt.toEpochMilli(),
        )
    }
}
