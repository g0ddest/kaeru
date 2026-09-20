package app.kaeru.domain.model

import java.time.Instant

enum class ListStatus(val apiValue: String) {
    WATCHING("watching"), PLANNED("planned"), COMPLETED("completed"),
    ON_HOLD("on_hold"), DROPPED("dropped"), REWATCHING("rewatching");

    companion object {
        fun fromApi(value: String): ListStatus = entries.firstOrNull { it.apiValue == value } ?: PLANNED
    }
}

data class UserRate(
    val id: Long,
    val animeId: Int,
    val status: ListStatus,
    val episodes: Int,
    val updatedAt: Instant,
)
