package app.kaeru.di

import app.kaeru.BuildConfig
import app.kaeru.data.together.LanSocketTransport
import app.kaeru.data.together.LanTogetherEndpoints
import app.kaeru.data.together.RelayTransport
import app.kaeru.data.together.TogetherEndpoints
import app.kaeru.data.together.TogetherTimeouts
import app.kaeru.domain.pairing.PairingRequest
import app.kaeru.domain.together.TransportFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TogetherClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TogetherRelayUrl

@Module
@InstallIn(SingletonComponent::class)
object TogetherModule {

    @Provides
    @Singleton
    fun togetherTimeouts(): TogetherTimeouts = TogetherTimeouts()

    /**
     * Empty in a build assembled without a relay, and the transport says so rather than dialling
     * nowhere.
     */
    @Provides
    @TogetherRelayUrl
    fun relayUrl(): String = BuildConfig.TOGETHER_RELAY_URL

    /**
     * A WebSocket lives for the length of an episode and says nothing for minutes at a time, so
     * the read timeout that suits a request would close it. The ping keeps the NAT binding that
     * carries it from being collected instead.
     */
    @Provides
    @Singleton
    @TogetherClient
    fun togetherClient(@PlainClient plain: OkHttpClient): OkHttpClient = plain.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /**
     * Sockets when the link carries an address on this network, the relay otherwise.
     *
     * The address is checked again here and not merely trusted from the link, because this is the
     * last place before a socket is opened.
     */
    @Provides
    @Singleton
    fun transportFactory(lan: LanSocketTransport, relay: RelayTransport): TransportFactory =
        TransportFactory { link ->
            val endpoint = link.lan
            if (endpoint != null && PairingRequest.isLanAddress(endpoint.host)) lan else relay
        }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TogetherBindings {
    @Binds
    abstract fun togetherEndpoints(impl: LanTogetherEndpoints): TogetherEndpoints
}
