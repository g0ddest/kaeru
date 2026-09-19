@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package app.kaeru.shared.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault

@Serializable
internal data class Anime(
    val id: Int,
    val title: String = "",
    val originalTitle: String = "",
    val poster: String = "",
    val description: String = "",
    val episodes: Int = 0,
    val episodesAired: Int = 0,
    val status: String = "",
    val score: String = "",
    val year: String = "",
    val nextEpisodeAt: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) val kind: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val studios: List<String>? = null,
)

@Serializable
internal data class LibraryItem(
    val id: Long,
    val anime: Anime,
    val status: String,
    val episodes: Int,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val updatedAt: String? = null,
)

@Serializable
internal data class Account(val id: Long, val nickname: String = "", val avatar: String = "")

@Serializable
internal data class Translation(
    val id: Int,
    val title: String,
    val episodes: Int = 0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val kind: String? = null,
)

@Serializable
internal data class StreamUrl(val quality: Int, val url: String)

@Serializable
internal data class Stream(
    val urls: List<StreamUrl>,
    val headers: Map<String, String>,
    val translation: Translation,
    val episode: Int,
)
