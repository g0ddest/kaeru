package app.kaeru.data.kodik

import app.kaeru.domain.error.EpisodeUnavailableReason

sealed class KodikError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NoToken(cause: Throwable? = null) : KodikError("Kodik public token unavailable", cause)
    /** Kodik has nothing for this ask; [reason] says whether that is the title, the track or the episode. */
    class NotFound(val shikimoriId: Int, val reason: EpisodeUnavailableReason) :
        KodikError("Kodik has nothing for shikimori $shikimoriId: $reason")
    class ParserBroken(val step: String, cause: Throwable? = null) :
        KodikError("Kodik parser broke at $step", cause)
    class Network(cause: Throwable) : KodikError("Kodik network failure", cause)

    /**
     * Kodik answered, with a non-2xx status. Distinct from [Network] on purpose:
     * a 403 from the player host is not the user's connection being down, and
     * must not be explained to them as one.
     */
    class Rejected(val code: Int) : KodikError("Kodik answered HTTP $code")
}
