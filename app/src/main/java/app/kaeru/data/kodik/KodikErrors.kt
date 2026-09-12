package app.kaeru.data.kodik

sealed class KodikError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NoToken(cause: Throwable? = null) : KodikError("Kodik public token unavailable", cause)
    class NotFound(val shikimoriId: Int) : KodikError("Kodik has no player for shikimori $shikimoriId")
    class ParserBroken(val step: String, cause: Throwable? = null) :
        KodikError("Kodik parser broke at $step", cause)
    class Network(cause: Throwable) : KodikError("Kodik network failure", cause)
}
