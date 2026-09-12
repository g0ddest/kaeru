package app.kaeru.data.kodik

import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

/**
 * The single Kodik API call this app is allowed to make.
 *
 * `search` would be the natural endpoint, but it rejects the public token from
 * Kodik's embed script with 401; `get-player` — the call the embed script makes
 * itself — accepts it.
 */
interface KodikApi {
    @FormUrlEncoded
    @POST("get-player")
    suspend fun getPlayer(
        @Field("token") token: String,
        @Field("shikimoriID") shikimoriId: Int,
        @Field("types") types: String = "anime,anime-serial",
        @Field("translations") translations: String? = null,
    ): KodikGetPlayerDto
}
