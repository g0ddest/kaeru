package app.kaeru.data.shikimori

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ShikimoriApi {
    @GET("api/users/whoami")
    suspend fun whoami(@Header("Authorization") authorization: String? = null): UserDto

    @GET("api/v2/user_rates?target_type=Anime")
    suspend fun userRates(
        @Query("user_id") userId: Long,
        @Query("status") status: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 1000,
    ): List<UserRateDto>

    @GET("api/animes")
    suspend fun animesByIds(
        @Query("ids") ids: String,
        @Query("limit") limit: Int = 50,
    ): List<AnimeShortDto>

    @GET("api/animes/{id}")
    suspend fun anime(@Path("id") id: Int): AnimeDetailsDto

    @GET("api/animes/{id}/screenshots")
    suspend fun screenshots(@Path("id") id: Int): List<ScreenshotDto>

    @GET("api/animes")
    suspend fun search(
        @Query("search") query: String,
        @Query("limit") limit: Int = 30,
    ): List<AnimeShortDto>

    @POST("api/v2/user_rates")
    suspend fun createUserRate(@Body body: UserRateRequest): UserRateDto

    @PATCH("api/v2/user_rates/{id}")
    suspend fun updateUserRate(
        @Path("id") id: Long,
        @Body body: UserRateRequest,
    ): UserRateDto
}
