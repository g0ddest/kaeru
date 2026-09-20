package app.kaeru.data.pairing

import app.kaeru.domain.pairing.PairingRequest
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/** This device's own address on the local network, as it should appear in a QR code. */
interface LanAddresses {
    fun siteLocalIpv4(): String?
}

/**
 * Reads the address off the interfaces that are actually up.
 *
 * A television usually has one Ethernet or Wi-Fi address and nothing else, but a set-top box can
 * also be holding a `169.254` address it gave itself while waiting for a router. That one works and
 * is worth offering, so it is kept — last, behind anything a router handed out.
 */
@Singleton
class NetworkLanAddresses @Inject constructor() : LanAddresses {
    override fun siteLocalIpv4(): String? = runCatching {
        val candidates = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .filter(PairingRequest::isLanAddress)
            .toList()
        candidates.firstOrNull { !it.startsWith(LINK_LOCAL) } ?: candidates.firstOrNull()
    }.getOrNull()

    private companion object {
        const val LINK_LOCAL = "169.254."
    }
}
