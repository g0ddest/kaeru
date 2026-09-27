package app.kaeru.shared.domain.sync

/** The dub a title remembers on this device: the track id, and its name once something has named it. */
data class RememberedDub(val id: Int, val title: String?)

/** One episode's position on this device, or from another one: all times epoch milliseconds. */
data class EpisodePosition(
    val animeId: Int,
    val episode: Int,
    val positionMs: Long,
    val durationMs: Long,
    val at: Long,
)

/** «Украдкой» for one title as another device left it: all times epoch milliseconds. */
data class TitleSecret(val animeId: Int, val on: Boolean, val watched: Int, val at: Long)

/** What this device holds, as far as deciding what from the server is newer needs it. */
data class LocalSyncState(
    /** Every saved position; only which episode and when count here. */
    val positions: List<EpisodePosition>,
    /** The dub each title remembers. */
    val dubs: Map<Int, RememberedDub>,
    /** When each title's dub was chosen here; a dub remembered before stamps existed has none. */
    val dubStamps: Map<Int, Long>,
    /** «Украдкой» as this device has it, on or off, by anime id. */
    val secrets: Map<Int, SyncSecret>,
)

/** What the server holds that is newer than this device's, ready to be written here. */
data class SyncNewer(
    val positions: List<EpisodePosition>,
    /** By anime id: positions saved at or before the moment are dead. */
    val tombstones: Map<Int, Long>,
    /** Only where the remembered dub actually changes. */
    val dubs: Map<Int, RememberedDub>,
    /** New dub stamps, once the write went in — also where the dub itself is already the same. */
    val dubStamps: Map<Int, Long>,
    /** «Украдкой» as another device left it, in the document's order. */
    val secrets: List<TitleSecret>,
) {
    /** Nothing to write; [dubStamps] alone does not count, as it is kept apart from the viewing data. */
    val isEmpty: Boolean get() = positions.isEmpty() && tombstones.isEmpty() && dubs.isEmpty() && secrets.isEmpty()
}

/**
 * What a client does with the `/sync` document beyond merging it: taking what the server has that
 * is newer ([newer]), and the one full send of what a device kept before sync ([seed]).
 */
object SyncRules {

    /** The worker keeps the 30 latest episodes of a title; older ones would only be trimmed again. */
    const val EPISODES_PER_TITLE: Int = 30

    /** The worker refuses a longer dub name, and with it the whole batch. */
    const val MAX_DUB_TITLE: Int = 200

    /**
     * What of [remote] is newer than [local].
     *
     * - A title id or an episode key that is not a number is skipped; so is an episode at or below
     *   zero, and a position with no length (it cannot be resumed from).
     * - A position goes in unless this device has the same episode stamped at or after it; its
     *   `p` is clamped into `0..d`.
     * - Every tombstone is passed on as it is; the write drops what it covers.
     * - A secret goes in over none, or over an older one; `watched` is at least zero.
     * - A dub with id zero is no dub. Otherwise it wins over none, or over an older stamp — a dub
     *   remembered here without a stamp counts as stamped at zero. The stamp is taken either way;
     *   the dub itself only where it differs from the remembered one.
     */
    fun newer(remote: SyncTitles, local: LocalSyncState): SyncNewer {
        if (remote.isEmpty()) return SyncNewer(emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyList())
        val known = HashMap<Long, Long>()
        for (position in local.positions) known[key(position.animeId, position.episode)] = position.at
        val positions = mutableListOf<EpisodePosition>()
        val tombstones = LinkedHashMap<Int, Long>()
        val dubs = LinkedHashMap<Int, RememberedDub>()
        val stamped = LinkedHashMap<Int, Long>()
        val secrets = mutableListOf<TitleSecret>()
        for ((id, title) in remote) {
            val animeId = id.toIntOrNull() ?: continue
            title.gone?.let { tombstones[animeId] = it }
            for ((episodeKey, position) in title.eps.orEmpty()) {
                val episode = episodeKey.toIntOrNull() ?: continue
                if (episode <= 0 || position.d <= 0) continue
                val here = known[key(animeId, episode)]
                if (here != null && here >= position.at) continue
                positions += EpisodePosition(animeId, episode, position.p.coerceIn(0, position.d), position.d, position.at)
            }
            val secret = title.secret
            if (secret != null) {
                val here = local.secrets[animeId]
                if (here == null || here.at < secret.at) {
                    secrets += TitleSecret(animeId, secret.on, secret.watched.coerceAtLeast(0), secret.at)
                }
            }
            val dub = title.dub
            if (dub != null && dub.id != 0) {
                val remembered = local.dubs[animeId]
                val mine = local.dubStamps[animeId] ?: remembered?.let { 0L }
                if (mine == null || mine < dub.at) {
                    if (remembered?.let { it.id == dub.id && it.title == dub.title } != true) {
                        dubs[animeId] = RememberedDub(dub.id, dub.title)
                    }
                    stamped[animeId] = dub.at
                }
            }
        }
        return SyncNewer(positions, tombstones, dubs, stamped, secrets)
    }

    /**
     * The first full send for an account: what [local] kept before sync, less what [remote] already
     * covers ([SyncMerge.without]). Titles in [finished] are left out.
     *
     * Per title, the [EPISODES_PER_TITLE] latest positions with a length; a named dub, its name cut
     * to [MAX_DUB_TITLE], stamped as chosen here or at zero; the secret as it stands. Only titles
     * with something left are returned, to be merged into the outbox.
     */
    fun seed(local: LocalSyncState, finished: Set<Int>, remote: SyncTitles): SyncTitles {
        val batch = LinkedHashMap<String, SyncTitle>()
        for ((animeId, rows) in local.positions.groupBy { it.animeId }) {
            if (animeId in finished) continue
            val eps = rows.filter { it.durationMs > 0 }
                .sortedByDescending { it.at }
                .take(EPISODES_PER_TITLE)
                .associate { it.episode.toString() to wire(it) }
            if (eps.isNotEmpty()) batch[animeId.toString()] = SyncTitle(eps = eps)
        }
        for ((animeId, dub) in local.dubs) {
            val title = dub.title ?: continue
            if (animeId in finished) continue
            val id = animeId.toString()
            batch[id] = (batch[id] ?: SyncTitle()).copy(
                dub = SyncDub(dub.id, title.take(MAX_DUB_TITLE), local.dubStamps[animeId] ?: 0L),
            )
        }
        for ((animeId, secret) in local.secrets) {
            if (animeId in finished) continue
            val id = animeId.toString()
            batch[id] = (batch[id] ?: SyncTitle()).copy(secret = secret.copy(watched = secret.watched.coerceAtLeast(0)))
        }
        val left = LinkedHashMap<String, SyncTitle>()
        for ((id, title) in batch) {
            val rest = SyncMerge.without(title, remote[id] ?: SyncTitle())
            if (!rest.isEmpty) left[id] = rest
        }
        return left
    }

    /** A position as it goes out: never below zero. */
    fun wire(position: EpisodePosition): SyncPosition =
        SyncPosition(p = position.positionMs.coerceAtLeast(0), d = position.durationMs.coerceAtLeast(0), at = position.at)

    private fun key(animeId: Int, episode: Int): Long = (animeId.toLong() shl 32) or (episode.toLong() and 0xffffffffL)
}
