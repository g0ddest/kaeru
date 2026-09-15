package app.kaeru.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.kaeru.domain.sync.RateOp
import app.kaeru.domain.sync.RateOpKind
import java.time.Instant

/**
 * A user-rate write that is waiting for a network.
 *
 * The row id is the queue: it is handed out in the order the viewer acted, and replay follows it.
 * [kind] and [value] are stored as text rather than as a typed column pair because a queue that
 * survives an app upgrade has to survive it for both halves of a rate at once.
 */
@Entity(tableName = "rate_outbox", indices = [Index("animeId")])
data class RateOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val animeId: Int,
    val kind: String,
    val value: String,
    val createdAt: Instant,
) {
    fun toDomain() = RateOp(
        id = id,
        animeId = animeId,
        kind = RateOpKind.valueOf(kind),
        value = value,
        createdAt = createdAt,
    )
}

fun RateOp.toEntity() = RateOutboxEntity(
    id = id,
    animeId = animeId,
    kind = kind.name,
    value = value,
    createdAt = createdAt,
)
