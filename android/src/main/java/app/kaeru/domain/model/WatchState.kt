package app.kaeru.domain.model

import java.time.Instant

data class WatchState(
    val animeId: Int,
    val episode: Int,
    val positionMs: Long,
    val durationMs: Long,
    val translationId: Int?,
    val kodikSeason: Int?,
    val updatedAt: Instant,
    /**
     * What the track named by [translationId] calls itself.
     *
     * The id alone names nothing a viewer would recognise, and the studio behind it is only known
     * once Kodik has been asked — a request the title screen has no other reason to make. So the
     * name travels with the choice, and the dub control can say «Озвучка: AniLibria.TV» the moment
     * the screen opens instead of after a round trip.
     *
     * Last and defaulted so the rows built all over the app and its tests keep reading as
     * positions rather than as records. Null for a row written before version 2 of the database,
     * and for one written by a position sample, which never sees the catalogue.
     */
    val translationTitle: String? = null,
) {
    val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}
