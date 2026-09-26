package app.kaeru.domain.viewsync

import app.kaeru.domain.model.EpisodeProgress
import app.kaeru.domain.model.ListStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** The worker, as far as a client can tell: it merges what it is sent and answers with the document. */
class FakeSyncApi(private val account: () -> Long?) : ViewingSyncApi {
    /** The document per account, as the worker keeps it. */
    val documents = mutableMapOf<Long, MutableMap<String, SyncTitle>>()
    var gets = 0
        private set

    /** Every batch that reached the worker, with the account whose bearer carried it. */
    val posts = mutableListOf<Pair<Long?, SyncTitles>>()
    var attempts = 0
        private set
    var failure: Exception? = null

    val requests: Int get() = gets + attempts

    fun document(account: Long): MutableMap<String, SyncTitle> = documents.getOrPut(account) { mutableMapOf() }

    override suspend fun get(): SyncTitles {
        gets++
        failure?.let { throw it }
        return document(account() ?: throw SyncFailure(SyncFailure.Kind.SIGNED_OUT)).toMap()
    }

    override suspend fun post(titles: SyncTitles): SyncTitles {
        attempts++
        failure?.let { throw it }
        val who = account() ?: throw SyncFailure(SyncFailure.Kind.SIGNED_OUT)
        posts += who to titles
        val document = document(who)
        for ((id, title) in titles) document[id] = SyncMerge.merge(document[id], title)
        return document.toMap()
    }
}

/** This device's tables, in memory: positions by (anime, episode), dubs by anime, the list's statuses. */
class FakeLocalViewing : LocalViewing {
    val rows = mutableMapOf<Pair<Int, Int>, EpisodeProgress>()
    val remembered = mutableMapOf<Int, RememberedDub>()
    val listed = MutableStateFlow<Map<Int, ListStatus>>(emptyMap())
    val applied = mutableListOf<SyncedViewing>()

    /** The account the tables belong to; a write for another is turned down, as the account lock does. */
    var owner: Long? = null

    fun put(vararg progress: EpisodeProgress) {
        for (row in progress) rows[row.animeId to row.episode] = row
    }

    override suspend fun positions(): List<EpisodeProgress> = rows.values.toList()

    override suspend fun dubs(): Map<Int, RememberedDub> = remembered.toMap()

    override fun statuses(): Flow<Map<Int, ListStatus>> = listed

    override suspend fun apply(account: Long, change: SyncedViewing): Boolean {
        if (owner != null && owner != account) return false
        applied += change
        for ((animeId, gone) in change.tombstones) {
            rows.entries.removeAll { (key, row) -> key.first == animeId && !row.updatedAt.isAfter(gone) }
        }
        for (row in change.positions) {
            val here = rows[row.animeId to row.episode]
            if (here == null || here.updatedAt.isBefore(row.updatedAt)) rows[row.animeId to row.episode] = row
        }
        remembered.putAll(change.dubs)
        return true
    }
}

/** The preferences sync keeps, in memory; one instance outliving two services is a restart. */
class FakeSyncState : SyncStateStore {
    private var outboxAccount: Long? = null
    private var outbox: SyncTitles = emptyMap()
    private val seededAccounts = mutableSetOf<Long>()
    private var stampsAccount: Long? = null
    private var stamps: Map<Int, Long> = emptyMap()

    override suspend fun outbox(account: Long): SyncTitles = if (outboxAccount == account) outbox else emptyMap()

    override suspend fun setOutbox(account: Long, titles: SyncTitles) {
        outboxAccount = account
        outbox = titles
    }

    override suspend fun seeded(): Set<Long> = seededAccounts.toSet()

    override suspend fun markSeeded(account: Long) {
        seededAccounts += account
    }

    override suspend fun forgetSeeded(account: Long) {
        seededAccounts -= account
    }

    override suspend fun dubStamps(account: Long): Map<Int, Long> = if (stampsAccount == account) stamps else emptyMap()

    override suspend fun setDubStamps(account: Long, stamps: Map<Int, Long>) {
        stampsAccount = account
        this.stamps = stamps
    }
}
