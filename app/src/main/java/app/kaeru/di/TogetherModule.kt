package app.kaeru.di

import app.kaeru.BuildConfig
import app.kaeru.data.together.LanSocketTransport
import app.kaeru.data.together.LanTogetherEndpoints
import app.kaeru.data.together.RelayTransport
import app.kaeru.data.together.TogetherEndpoints
import app.kaeru.data.together.TogetherTimeouts
import app.kaeru.data.together.HostChannel
import app.kaeru.data.together.HostTransports
import app.kaeru.data.together.TogetherSession
import app.kaeru.domain.pairing.PairingRequest
import app.kaeru.domain.together.PlaybackPort
import app.kaeru.domain.together.TogetherSessionApi
import app.kaeru.domain.together.TransportFactory
import app.kaeru.player.TogetherPlaybackPort
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Provider
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
     *
     * A fresh transport every time, because one of them carries one session: a port, a socket, a
     * backlog and a state are all instance state. A host has to hold on to the one it was given —
     * the port in its link came out of that object — which is what asking once and keeping the
     * answer means here.
     */
    /**
     * How this phone offers a room, which is the one case there is no link to route by yet.
     *
     * Sockets when there is a network to find a friend on and no relay in this build; the relay
     * whenever there is one, because a link that only works in one room is a link that mostly
     * does not work — a friend on mobile data can follow the other kind from anywhere.
     *
     * Reading the endpoint is what opens the port, so it is read here, before the link that
     * advertises it exists.
     */
    @Provides
    @Singleton
    fun hostTransports(
        lan: LanSocketTransport,
        relay: RelayTransport,
        @TogetherRelayUrl relayUrl: String,
    ): HostTransports = HostTransports {
        val endpoint = if (relayUrl.isBlank()) lan.hostEndpoint() else null
        if (endpoint != null) HostChannel(lan, endpoint) else HostChannel(relay, null)
    }

    /**
     * One for the process, like the playback it drives. Two sessions would mean two rooms, two
     * ping loops and two phones' worth of corrections applied to one picture.
     *
     * Its dependencies are named here rather than on its constructor because it is a use case
     * with no injection annotations of its own, exactly as `resolveEpisodeStream` and
     * `watchProgress` are put together.
     */
    @Provides
    @Singleton
    fun togetherSession(
        transports: TransportFactory,
        hosting: HostTransports,
        port: PlaybackPort,
        clock: Clock,
        @PlaybackScope scope: CoroutineScope,
    ): TogetherSessionApi = TogetherSession(transports, hosting, port, clock, scope)

    @Provides
    @Singleton
    fun transportFactory(
        lan: Provider<LanSocketTransport>,
        relay: Provider<RelayTransport>,
    ): TransportFactory = TransportFactory { link ->
        val endpoint = link.lan
        if (endpoint != null && PairingRequest.isLanAddress(endpoint.host)) lan.get() else relay.get()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TogetherBindings {
    @Binds
    abstract fun togetherEndpoints(impl: LanTogetherEndpoints): TogetherEndpoints

    @Binds
    abstract fun playbackPort(impl: TogetherPlaybackPort): PlaybackPort
}
