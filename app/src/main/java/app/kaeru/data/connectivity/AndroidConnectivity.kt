package app.kaeru.data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.kaeru.domain.connectivity.Connectivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/** Long enough to survive a rotation, short enough that nothing watches a network nobody asked about. */
private const val STOP_TIMEOUT_MS = 5_000L

/**
 * The device's own answer to «is there a network».
 *
 * Validated, not merely connected: a hotel portal and a router with no uplink both hand out an
 * interface that carries no traffic, and treating either as online would send the write queue out
 * to be refused rather than leaving it to wait. The default network is the one asked about, since
 * it is the one every request in this app goes over.
 *
 * Shared, so the home screen, the player and the television banner are one registration rather
 * than three. The replay cache is dropped the moment the last collector leaves, so a collector
 * that arrives after a gap is answered by a fresh read rather than by a remembered one.
 */
@Singleton
class AndroidConnectivity(
    context: Context,
    scope: CoroutineScope,
    stopTimeoutMs: Long,
) : Connectivity {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context,
        // Lives as long as the process. Registering a network callback is not work worth a
        // lifecycle, and the sharing itself stops whenever nothing is collecting.
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        STOP_TIMEOUT_MS,
    )

    private val manager: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

    private val state: Flow<Boolean> = callbackFlow {
        val manager = manager
        if (manager == null) {
            // No connectivity service to ask — a stripped image, or a context without one. Saying
            // «offline» forever would hide the library behind a banner nothing could ever lift.
            send(true)
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.reachesInternet)
            }

            override fun onLost(network: Network) = Unit.also { trySend(false) }

            override fun onUnavailable() = Unit.also { trySend(false) }
        }
        // Registered before the first read, so a change that lands between the two is reported
        // rather than lost; `distinctUntilChanged` absorbs the duplicate that ordering can cost.
        manager.registerDefaultNetworkCallback(callback)
        trySend(manager.isOnline())
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    override val online: Flow<Boolean> = state
        .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMs, replayExpirationMillis = 0), replay = 1)
        // Per collector, because the shared flow replays its last value to each new one.
        .distinctUntilChanged()

    private fun ConnectivityManager.isOnline(): Boolean =
        getNetworkCapabilities(activeNetwork ?: return false).reachesInternet
}

/** Both capabilities, not either: `INTERNET` is what the interface is for, `VALIDATED` is whether it works. */
private val NetworkCapabilities?.reachesInternet: Boolean
    get() = this != null &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
