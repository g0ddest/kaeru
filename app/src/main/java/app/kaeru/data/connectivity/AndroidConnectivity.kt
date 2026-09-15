package app.kaeru.data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.kaeru.domain.connectivity.Connectivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The device's own answer to «is there a network».
 *
 * Validated, not merely connected: a hotel portal and a router with no uplink both hand out an
 * interface that carries no traffic, and treating either as online would send the write queue out
 * to be refused rather than leaving it to wait. The default network is the one asked about, since
 * it is the one every request in this app goes over.
 */
@Singleton
class AndroidConnectivity @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : Connectivity {

    private val manager: ConnectivityManager? get() = context.getSystemService(ConnectivityManager::class.java)

    override val online: Flow<Boolean> = callbackFlow {
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

    private fun ConnectivityManager.isOnline(): Boolean =
        getNetworkCapabilities(activeNetwork ?: return false).reachesInternet
}

/** Both capabilities, not either: `INTERNET` is what the interface is for, `VALIDATED` is whether it works. */
private val NetworkCapabilities?.reachesInternet: Boolean
    get() = this != null &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
