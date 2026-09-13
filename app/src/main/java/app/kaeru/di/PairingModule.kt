package app.kaeru.di

import app.kaeru.data.pairing.LanAddresses
import app.kaeru.data.pairing.NetworkLanAddresses
import app.kaeru.data.pairing.OkHttpPairingClient
import app.kaeru.data.pairing.SocketPairingServer
import app.kaeru.domain.pairing.PairingClient
import app.kaeru.domain.pairing.TvPairingServer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** The one client in the app allowed to speak in the clear, and only to a television on the LAN. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PairingHttp

@Module
@InstallIn(SingletonComponent::class)
object PairingModule {

    /**
     * Deliberately not derived from the shared plain client. It carries no interceptors, no
     * `User-Agent` and no TLS connection spec at all, so this client is incapable of being reused
     * for anything that talks to the internet: the only thing it can reach is a cleartext address,
     * and [OkHttpPairingClient] refuses every address that is not a private one.
     *
     * The read timeout is four times the others because the wait on this call is not the network —
     * it is the television's own round trip to Shikimori, which happens while the phone holds the
     * connection open. Five seconds there would report a failure for a sign-in that succeeded and
     * send the viewer to press «Повторить» with a code that has already been spent.
     */
    @Provides
    @Singleton
    @PairingHttp
    fun pairingHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
        // A code is good for one exchange. Nothing but the person tapping «Повторить» resends it.
        .retryOnConnectionFailure(false)
        .build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PairingBindings {
    // Unscoped on purpose: both implementations are already @Singleton, so these hand out the
    // one instance each. The server in particular has to be one instance — it owns a port.
    @Binds
    abstract fun lanAddresses(impl: NetworkLanAddresses): LanAddresses

    @Binds
    abstract fun tvPairingServer(impl: SocketPairingServer): TvPairingServer

    @Binds
    abstract fun pairingClient(impl: OkHttpPairingClient): PairingClient
}
