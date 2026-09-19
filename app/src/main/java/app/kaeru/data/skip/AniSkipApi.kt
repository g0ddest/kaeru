package app.kaeru.data.skip

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** Where the community's marks live. A free public service, so nothing here may be depended on. */
const val ANISKIP_BASE_URL = "https://api.aniskip.com/"

/**
 * The four kinds worth asking for: an opening and an ending, and the two «mixed» kinds, which is
 * what the same thing is called when it runs over the episode rather than standing apart from it.
 */
val ANISKIP_TYPES = listOf("op", "ed", "mixed-op", "mixed-ed")

/** Lenient on purpose: this is somebody else's service and it may grow fields at any time. */
fun aniSkipJson(): Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
data class AniSkipResponse(
    val found: Boolean = false,
    val results: List<AniSkipResult> = emptyList(),
)

@Serializable
data class AniSkipResult(
    val interval: AniSkipInterval,
    @SerialName("skipType") val skipType: String = "",
    /** The length of the file these marks were made against, in seconds. */
    @SerialName("episodeLength") val episodeLength: Double = 0.0,
)

/** Seconds, with a fraction, from the start of the episode. */
@Serializable
data class AniSkipInterval(val startTime: Double = 0.0, val endTime: Double = 0.0)

/**
 * One call, and the id it takes is MyAnimeList's — which for everything this app can play is the
 * Shikimori id as well. A title Shikimori carries and MyAnimeList does not simply has no marks.
 */
interface AniSkipApi {
    @GET("v2/skip-times/{malId}/{episode}")
    suspend fun skipTimes(
        @Path("malId") malId: Int,
        @Path("episode") episode: Int,
        @Query("types[]") types: List<String>,
        @Query("episodeLength") episodeLength: Int,
    ): AniSkipResponse
}
