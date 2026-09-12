package app.kaeru.data.kodik

sealed class KodikError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NoToken(cause: Throwable? = null) : KodikError("Kodik public token unavailable", cause)
    class NotFound(val shikimoriId: Int) : KodikError("Kodik has no player for shikimori $shikimoriId")
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
