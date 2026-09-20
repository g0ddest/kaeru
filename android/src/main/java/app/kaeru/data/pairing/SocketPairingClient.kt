package app.kaeru.data.pairing

import app.kaeru.di.IoDispatcher
import app.kaeru.domain.error.PairingFailed
import app.kaeru.domain.error.PairingFailureReason
import app.kaeru.domain.pairing.PairingClient
import app.kaeru.domain.pairing.PairingRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Where a pairing request is actually dialled — the address in the link, everywhere but a test. */
fun interface PairingAddresses {
    fun resolve(request: PairingRequest): InetSocketAddress
}

@Singleton
class LanPairingAddresses @Inject constructor() : PairingAddresses {
    /** The host is a validated IPv4 literal by the time it gets here, so nothing is resolved. */
    override fun resolve(request: PairingRequest) = InetSocketAddress(request.host, request.port)
}

/**
 * One POST, to a television on the same Wi-Fi, carrying a code that is worthless a second later.
 *
 * Written on a bare socket rather than on the app's HTTP client, and that is the point rather than
 * an economy. A television's address is one a router made up this morning; no certificate exists
 * for it, so the request has to go out in the clear — and the app's cleartext policy stays
 * deny-by-default for everything else, which is the only thing that catches a plain-`http` URL
 * arriving as data rather than as a constant. `NetworkSecurityPolicy` is consulted by the
 * libraries that choose to; a socket never asks, so the narrowing is done here instead: the
 * address is checked against the private ranges before anything is opened, exactly one request is
 * written, no redirect is followed, and the answer is read under a byte budget and a deadline.
 */
@Singleton
class SocketPairingClient @Inject constructor(
    private val json: Json,
    private val timeouts: PairingTimeouts,
    private val addresses: PairingAddresses,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : PairingClient {

    override suspend fun send(request: PairingRequest, code: String, redirectUri: String): Result<Unit> {
        if (!PairingRequest.isLanAddress(request.host)) {
            return Result.failure(PairingFailed(PairingFailureReason.BAD_LINK))
        }
        val body = json.encodeToString(PairingPayload(request.nonce, code, redirectUri))
        return withContext(dispatcher) {
            try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.connect(addresses.resolve(request), timeouts.connectMs)
                    // The deadline starts once there is somebody to talk to, and covers writing
                    // the request and reading every byte of the answer. The per-read timeout is
                    // the same value, so a television that accepts and then says nothing fails
                    // here rather than holding the phone on a spinner.
                    socket.soTimeout = timeouts.requestMs
                    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeouts.requestMs.toLong())
                    write(socket, request, body)
                    accepted(HttpWire(socket.getInputStream(), deadline))
                }
            } catch (error: IOException) {
                Result.failure(PairingFailed(PairingFailureReason.UNREACHABLE, error))
            }
        }
    }

    private fun write(socket: Socket, request: PairingRequest, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write(
                httpHead(
                    "POST $PAIR_PATH HTTP/1.1",
                    "Host: ${request.host}:${request.port}",
                    "Content-Type: application/json; charset=utf-8",
                    "Content-Length: ${bytes.size}",
                    "Connection: close",
                ),
            )
            write(bytes)
            flush()
        }
    }

    /**
     * Success is a 2xx whose body is this protocol's own «yes», and nothing else.
     *
     * A redirect, an answer too large to be a pairing, and a 200 from something that is not a
     * Kaeru television all land in the same place: the code was not taken, and the viewer is told
     * to try again rather than told they are signed in somewhere they are not.
     */
    private fun accepted(wire: HttpWire): Result<Unit> {
        val status = wire.line() ?: return refused()
        val parts = status.split(' ')
        if (parts.size < 2 || !parts[0].startsWith("HTTP/1.")) return refused()
        val code = parts[1].toIntOrNull() ?: return refused()
        val length = wire.contentLength() ?: return refused()
        if (code !in 200..299 || length > MAX_BODY_BYTES) return refused()
        val body = if (length <= 0) "" else wire.body(length) ?: return refused()
        val answer = runCatching { json.decodeFromString<PairingAccepted>(body) }.getOrNull()
        return if (answer?.ok == true) Result.success(Unit) else refused()
    }

    private fun refused(): Result<Unit> = Result.failure(PairingFailed(PairingFailureReason.REFUSED))

    private companion object {
        const val PAIR_PATH = "/pair"
    }
}
