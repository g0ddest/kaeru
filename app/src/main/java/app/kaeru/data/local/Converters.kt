package app.kaeru.data.local

import androidx.room.TypeConverter
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.ListStatus
import java.time.Instant

class Converters {
    @TypeConverter
    fun instantToLong(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun longToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun animeStatusToString(value: AnimeStatus): String = value.name

    @TypeConverter
    fun stringToAnimeStatus(value: String): AnimeStatus = AnimeStatus.valueOf(value)

    @TypeConverter
    fun listStatusToString(value: ListStatus): String = value.name

    @TypeConverter
    fun stringToListStatus(value: String): ListStatus = ListStatus.valueOf(value)

    @TypeConverter
    fun listToString(value: List<String>): String = value.joinToString("\n")

    @TypeConverter
    fun stringToList(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split("\n")
}
