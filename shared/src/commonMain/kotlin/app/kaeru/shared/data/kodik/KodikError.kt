package app.kaeru.shared.data.kodik

/**
 * What the Kodik chain can fail with, short of the transport itself.
 *
 * A host that answered with a non-2xx is not here: that is an [app.kaeru.shared.ApiException]
 * carrying the status, and a request that never got an answer is a
 * [app.kaeru.shared.data.network.NetworkException]. Both come straight out of the transport.
 */
sealed class KodikError(message: String) : Exception(message) {
    class ParserBroken(val step: String) : KodikError("Kodik parser broke at $step")
    class NoToken : KodikError("Kodik public token unavailable")

    /**
     * Kodik has nothing for this ask, and [missing] says how much of nothing.
     *
     * The two read very differently to a viewer, and only one of them leaves another voice to
     * try: a title Kodik never had is a dead end, but a track without this episode is the
     * ordinary case of a studio that has not caught up, and the way past it is a different track.
     */
    class NotFound(val missing: Missing = Missing.EPISODE) :
        KodikError("Kodik title, translation or episode is unavailable")

    enum class Missing {
        /** `get-player` found no player for this anime at all. */
        TITLE,

        /** The title is there; the track asked for is not offered, or does not carry this episode. */
        EPISODE,
    }
}
