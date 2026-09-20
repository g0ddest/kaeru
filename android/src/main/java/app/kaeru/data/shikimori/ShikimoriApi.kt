package app.kaeru.data.shikimori

import app.kaeru.shared.data.shikimori.AnimeDto
import app.kaeru.shared.data.shikimori.ScreenshotDto
import app.kaeru.shared.data.shikimori.UserDto
import app.kaeru.shared.data.shikimori.UserRateDto

/**
 * What the repositories ask of Shikimori, with the signed-in session's token supplied on the way.
 *
 * The endpoints themselves — routes, paging, batching, the rate limit, the posters — are the
 * shared module's `ShikimoriClient`, which takes a token per call and holds no session. This is
 * the Android side of that bargain: [SessionShikimoriApi] reads the bearer from the token store,
 * refreshes it once after a 401 and retries, and the repositories never see a token at all.
 *
 * An interface rather than the client itself so the repositories' tests can stand a fake here,
 * as they always have.
 */
interface ShikimoriApi {
    /**
     * Who a token belongs to. [accessToken] names a candidate identity during sign-in and is used
     * exactly as given — never swapped for the session's, never refreshed. Null asks about the
     * session, and is answered 401 when there is none.
     */
    suspend fun whoami(accessToken: String? = null): UserDto

    /** Every row of the viewer's list, all six statuses. */
    suspend fun libraryRates(userId: Long): List<UserRateDto>

    /** The cards for [ids], however many: the client batches them in fifties. */
    suspend fun animesByIds(ids: List<Int>): List<AnimeDto>

    /** What is airing now (`status`), or what a season held (`season`); most popular first. */
    suspend fun catalogue(status: String? = null, season: String? = null): List<AnimeDto>

    suspend fun anime(id: Int): AnimeDto

    suspend fun screenshots(id: Int): List<ScreenshotDto>

    suspend fun search(query: String): List<AnimeDto>

    /** The real posters of [ids], by id; a title GraphQL has nothing for is simply absent. */
    suspend fun posters(ids: List<Int>): Map<Int, String>

    suspend fun createUserRate(userId: Long, animeId: Int, status: String): UserRateDto

    /** Names only the half it changes: Shikimori reads an absent field as «leave it». */
    suspend fun updateUserRate(id: Long, status: String? = null, episodes: Int? = null): UserRateDto
}
