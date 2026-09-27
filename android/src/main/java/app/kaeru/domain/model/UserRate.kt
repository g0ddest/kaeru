package app.kaeru.domain.model

import java.time.Instant

enum class ListStatus(val apiValue: String) {
    WATCHING("watching"), PLANNED("planned"), COMPLETED("completed"),
    ON_HOLD("on_hold"), DROPPED("dropped"), REWATCHING("rewatching"),

    /**
     * «Смотреть украдкой»: a title kept on this device (and in sync, when it is on) and never
     * written to Shikimori. Not a Shikimori status — [apiValue] is never sent and never read back —
     * so it is last, after every status the server knows.
     */
    SECRET("secret");

    /** Whether Shikimori knows this status at all. */
    val onShikimori: Boolean get() = this != SECRET

    companion object {
        fun fromApi(value: String): ListStatus =
            entries.firstOrNull { it.onShikimori && it.apiValue == value } ?: PLANNED
    }
}

data class UserRate(
    val id: Long,
    val animeId: Int,
    val status: ListStatus,
    val episodes: Int,
    val updatedAt: Instant,
)
