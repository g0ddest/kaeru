package app.kaeru.domain.viewsync

/**
 * The worker's `/sync` document (infra/relay/src/sync.ts; spec 2026-09-26-kaeru-sync-design.md §2):
 * per anime id, where each episode stopped, the chosen dub and a tombstone for a finished title.
 *
 * Every `at` is the device's own clock in milliseconds, and the newer one wins, field by field and
 * episode by episode — on the worker and, with the same rules, here ([merge], [without]). `secret`
 * («Смотреть украдкой») is not read here yet: an answer carrying one is read without it.
 */
data class SyncPosition(
    /** Where the episode stopped, in milliseconds. */
    val p: Long,
    /** How long the episode is, in milliseconds. */
    val d: Long,
    val at: Long,
)

data class SyncDub(val id: Int, val title: String, val at: Long)

data class SyncTitle(
    val dub: SyncDub? = null,
    /** By episode number, as a string: the key the worker uses. */
    val eps: Map<String, SyncPosition>? = null,
    /** The moment the title was finished; anything stamped at or before it is dead. */
    val gone: Long? = null,
) {
    val isEmpty: Boolean get() = dub == null && gone == null && eps.isNullOrEmpty()
}

/** By anime id, as a string: the key the worker uses. */
typealias SyncTitles = Map<String, SyncTitle>

/** The merge rules the worker applies, so the client can tell what it still owes and what is spent. */
object SyncMerge {

    /** [patch] over [base], the newer `at` winning per field and per episode. */
    fun merge(base: SyncTitle?, patch: SyncTitle): SyncTitle {
        var out = base ?: SyncTitle()
        val dub = patch.dub
        if (dub != null && (out.dub?.at ?: Long.MIN_VALUE) <= dub.at) out = out.copy(dub = dub)
        val gone = patch.gone
        if (gone != null && (out.gone ?: Long.MIN_VALUE) <= gone) out = out.copy(gone = gone)
        val eps = patch.eps
        if (eps != null) {
            val merged = out.eps.orEmpty().toMutableMap()
            for ((episode, position) in eps) {
                if ((merged[episode]?.at ?: Long.MIN_VALUE) <= position.at) merged[episode] = position
            }
            out = out.copy(eps = merged)
        }
        return out
    }

    /** [title] without what [covered] (a sent batch, or the server) already holds: anything no newer. */
    fun without(title: SyncTitle, covered: SyncTitle): SyncTitle {
        val floor = covered.gone ?: Long.MIN_VALUE
        val dub = title.dub?.takeUnless { dub ->
            dub.at <= floor || covered.dub?.let { dub.at <= it.at } == true
        }
        val gone = title.gone?.takeUnless { it <= floor }
        val eps = title.eps?.filter { (episode, position) ->
            position.at > floor && covered.eps?.get(episode)?.let { position.at <= it.at } != true
        }?.takeIf { it.isNotEmpty() }
        return SyncTitle(dub = dub, eps = eps, gone = gone)
    }
}
