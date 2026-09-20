package app.kaeru.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

/** Where the releases of this app are published. */
const val GITHUB_API_BASE_URL = "https://api.github.com/"

/**
 * How many releases are read.
 *
 * More than one, because the newest is not always the first thing in the list — a patch published
 * to an older line lands on top of it — and few, because everything past the first handful is
 * history nobody is going to be offered.
 */
private const val RELEASES_PER_PAGE = 5

fun githubJson(): Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
}

/**
 * The releases of `g0ddest/kaeru`, unauthenticated.
 *
 * The repository is public, so no token is sent and none is needed; what that costs is the rate
 * limit — sixty requests an hour per address — which is why `UpdatePolicy` exists. The `Accept`
 * header is GitHub's own versioned media type: without it the API answers with whatever its
 * default version is that month, and the field names are what this file parses.
 */
interface GitHubReleasesApi {

    @Headers("Accept: application/vnd.github+json")
    @GET("repos/g0ddest/kaeru/releases")
    suspend fun releases(@Query("per_page") perPage: Int = RELEASES_PER_PAGE): List<GitHubReleaseDto>
}

@Serializable
data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val name: String? = null,
    val body: String? = null,
    /** An unpublished draft, visible only to the maintainer and never offered. */
    val draft: Boolean = false,
    /** Every release of this app so far is one, so it is read and deliberately not filtered on. */
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
data class GitHubAssetDto(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)
