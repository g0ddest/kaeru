package app.kaeru.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import java.time.Instant

@Entity(tableName = "user_rate", indices = [Index("animeId", unique = true)])
data class UserRateEntity(
    @PrimaryKey val id: Long,
    val animeId: Int,
    val status: ListStatus,
    val episodes: Int,
    val updatedAt: Instant,
) {
    fun toDomain() = UserRate(
        id = id,
        animeId = animeId,
        status = status,
        episodes = episodes,
        updatedAt = updatedAt,
    )
}

fun UserRate.toEntity() = UserRateEntity(
    id = id,
    animeId = animeId,
    status = status,
    episodes = episodes,
    updatedAt = updatedAt,
)
