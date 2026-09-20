package app.kaeru.shared.data.kodik

internal sealed class KodikError(message: String) : Exception(message) {
    class ParserBroken(val step: String) : KodikError("Kodik parser broke at $step")
    class NoToken : KodikError("Kodik public token unavailable")
    class NotFound : KodikError("Kodik title, translation or episode is unavailable")
}
