package app.kaeru.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import app.kaeru.BuildConfig
import app.kaeru.data.auth.DataStoreTokenStore
import app.kaeru.data.auth.ShikimoriAuthRepository
import app.kaeru.data.auth.TokenStore
import app.kaeru.data.shikimori.AuthInterceptor
import app.kaeru.data.shikimori.RateLimitInterceptor
import app.kaeru.data.shikimori.SHIKIMORI_BASE_URL
import app.kaeru.data.shikimori.ShikimoriApi
import app.kaeru.data.shikimori.ShikimoriOAuthApi
import app.kaeru.data.shikimori.TokenAuthenticator
import app.kaeru.data.shikimori.UserAgentInterceptor
import app.kaeru.data.shikimori.shikimoriJson
import app.kaeru.domain.repository.AuthRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.time.Clock
import javax.inject.Named
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ShikimoriClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlainClient

private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore("auth")
private val Context.prefsDataStore: DataStore<Preferences> by preferencesDataStore("prefs")

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    private val USER_AGENT = "Kaeru/${BuildConfig.VERSION_NAME}"

    @Provides
    @Named("shikimoriClientId")
    fun clientId(): String = BuildConfig.SHIKIMORI_CLIENT_ID

    @Provides
    @Named("shikimoriClientSecret")
    fun clientSecret(): String = BuildConfig.SHIKIMORI_CLIENT_SECRET

    @Provides
    @Singleton
    @Named("auth")
    fun authDataStore(@ApplicationContext ctx: Context): DataStore<Preferences> = ctx.authDataStore

    @Provides
    @Singleton
    @Named("prefs")
    fun prefsDataStore(@ApplicationContext ctx: Context): DataStore<Preferences> = ctx.prefsDataStore

    @Provides
    @Singleton
    fun json(): Json = shikimoriJson()

    @Provides
    @Singleton
    fun clock(): Clock = Clock.systemUTC()

    private fun logging() = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
    }

    /** Shared anonymous client for OAuth, Kodik and images. */
    @Provides
    @Singleton
    @PlainClient
    fun plainClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(UserAgentInterceptor(USER_AGENT))
        .addInterceptor(logging())
        .build()

    @Provides
    @Singleton
    fun oauthApi(@PlainClient client: OkHttpClient, json: Json): ShikimoriOAuthApi = Retrofit.Builder()
        .baseUrl(SHIKIMORI_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ShikimoriOAuthApi::class.java)

    @Provides
    @Singleton
    @ShikimoriClient
    fun shikimoriClient(
        @PlainClient plain: OkHttpClient,
        store: TokenStore,
        oauthApi: ShikimoriOAuthApi,
        @Named("shikimoriClientId") clientId: String,
        @Named("shikimoriClientSecret") clientSecret: String,
        clock: Clock,
    ): OkHttpClient = plain.newBuilder()
        .addInterceptor(RateLimitInterceptor())
        .addInterceptor(AuthInterceptor(store))
        .authenticator(TokenAuthenticator(store, oauthApi, clientId, clientSecret, clock))
        .build()

    @Provides
    @Singleton
    fun shikimoriApi(@ShikimoriClient client: OkHttpClient, json: Json): ShikimoriApi = Retrofit.Builder()
        .baseUrl(SHIKIMORI_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(ShikimoriApi::class.java)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthBindings {
    @Binds
    abstract fun tokenStore(impl: DataStoreTokenStore): TokenStore

    @Binds
    abstract fun authRepository(impl: ShikimoriAuthRepository): AuthRepository
}
