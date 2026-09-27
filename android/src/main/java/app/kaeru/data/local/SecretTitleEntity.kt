package app.kaeru.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.kaeru.domain.model.SecretTitle
import java.time.Instant

/** «Смотреть украдкой» for one title; account-owned, like the rates it stands in for. */
@Entity(tableName = "secret_title")
data class SecretTitleEntity(
    @PrimaryKey val animeId: Int,
    val isOn: Boolean,
    val watched: Int,
    val at: Instant,
) {
    fun toDomain() = SecretTitle(animeId, isOn, watched, at)
}

fun SecretTitle.toEntity() = SecretTitleEntity(animeId, on, watched, at)
