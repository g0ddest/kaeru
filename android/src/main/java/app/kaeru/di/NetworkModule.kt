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
import app.kaeru.data.network.UserAgentInterceptor
import app.kaeru.data.shikimori.SessionShikimoriApi
import app.kaeru.data.shikimori.ShikimoriApi
import app.kaeru.domain.repository.AuthRepository
import app.kaeru.shared.data.network.HttpTransport
import app.kaeru.shared.data.shikimori.ShikimoriClient
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.time.Clock
import javax.inject.Named
import javax.inject.Qualifier
import javax.inject.Singleton

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

    /** The app's own wire, the television hand-off for now: defaults left out, unknown keys ignored. */
    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = false
    }

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
     * Shikimori, from the shared module, with this app's name on the wire.
     *
     * The token exchange is the one call that does not go to Shikimori: Kaeru's worker holds the
     * client secret the exchange needs — it used to be compiled into the APK, where anyone could
     * read it — and adds it on the way through. The client knows whether it has an address for
     * that, and a build assembled without one refuses to sign anyone in rather than sending a
     * code somewhere it cannot be redeemed.
     */
    @Provides
    @Singleton
    fun shikimoriClient(
        transport: HttpTransport,
        @Named("shikimoriClientId") clientId: String,
        @Named("authProxyUrl") proxyUrl: String,
    ): ShikimoriClient = ShikimoriClient(transport, clientId, proxyUrl, userAgent = USER_AGENT)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthBindings {
    @Binds
    abstract fun tokenStore(impl: DataStoreTokenStore): TokenStore

    @Binds
    abstract fun authRepository(impl: ShikimoriAuthRepository): AuthRepository

    @Binds
    abstract fun shikimoriApi(impl: SessionShikimoriApi): ShikimoriApi
}
