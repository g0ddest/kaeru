package app.kaeru.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import app.kaeru.data.shikimori.UnconfiguredOAuthApi
import app.kaeru.data.shikimori.UserAgentInterceptor
import app.kaeru.data.shikimori.shikimoriJson
import app.kaeru.domain.repository.AuthRepository
import app.kaeru.shared.data.network.HttpTransport
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Dispatcher
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

private val Context.prefsDataStore: DataStore<Preferences> by preferencesDataStore("prefs")

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    private val USER_AGENT = "Kaeru/${BuildConfig.VERSION_NAME}"

    @Provides
    @Named("shikimoriClientId")
    fun clientId(): String = BuildConfig.SHIKIMORI_CLIENT_ID

    @Provides
    @Named("authProxyUrl")
    fun authProxyUrl(): String = BuildConfig.AUTH_PROXY_URL

    @Provides
    @Singleton
    @Named("auth")
    fun authDataStore(@ApplicationContext ctx: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            // Excluded from all Android backup/transfer modes, independently of manifest flags.
            ctx.noBackupFilesDir.resolve("auth.preferences_pb")
        }

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

    /**
     * What the shared module's clients talk through: Ktor over OkHttp, on the same logging as
     * the rest of this app's traffic.
     *
     * Not the plain client. That one forces the app's `User-Agent` onto every request, and the
     * shared clients set their own per request — `Kaeru/<version>` for Shikimori, a desktop
     * browser's for Kodik, which serves its pages to nothing else.
     */
    @Provides
    @Singleton
    fun sharedTransport(): HttpTransport = HttpTransport(HttpClient(OkHttp) {
        engine { preconfigured = OkHttpClient.Builder().addInterceptor(logging()).build() }
    })

    /**
     * The one call that does not go to Shikimori.
     *
     * Kaeru's worker holds the client secret the exchange needs — it used to be compiled into the
     * APK, where anyone could read it — and adds it on the way through. Everything else in
     * `data/shikimori` keeps the Shikimori base URL, the authorization page the viewer opens
     * included: only the token endpoint moved.
     *
     * An address is required, and it has to be one an http client can use. Without either there is
     * nothing to ask, and [UnconfiguredOAuthApi] says so on the first call instead of spending a
     * code on a request that cannot be answered — a build with `wss://` copied down from
     * `TOGETHER_RELAY_URL` would otherwise take Retrofit's `IllegalArgumentException` out through
     * this `@Provides` and kill the app at the first injection.
     */
    @Provides
    @Singleton
    fun oauthApi(
        @PlainClient client: OkHttpClient,
        json: Json,
        @Named("authProxyUrl") proxyUrl: String,
    ): ShikimoriOAuthApi {
        val base = proxyUrl.trim()
        if (base.isEmpty()) return UnconfiguredOAuthApi
        return runCatching {
            Retrofit.Builder()
                // Retrofit resolves a relative path only against a base that ends in one.
                .baseUrl(if (base.endsWith("/")) base else "$base/")
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(ShikimoriOAuthApi::class.java)
        }.getOrDefault(UnconfiguredOAuthApi)
    }

    @Provides
    @Singleton
    @ShikimoriClient
    fun shikimoriClient(
        @PlainClient plain: OkHttpClient,
        store: TokenStore,
        oauthApi: ShikimoriOAuthApi,
        @Named("shikimoriClientId") clientId: String,
        clock: Clock,
    ): OkHttpClient = plain.newBuilder()
        // Authenticators block API workers while Retrofit enqueues refresh on the plain client.
        // Those calls must never compete for the same dispatcher or per-host slots.
        .dispatcher(Dispatcher())
        .addInterceptor(RateLimitInterceptor())
        .addInterceptor(AuthInterceptor(store))
        .authenticator(TokenAuthenticator(store, oauthApi, clientId, clock))
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
