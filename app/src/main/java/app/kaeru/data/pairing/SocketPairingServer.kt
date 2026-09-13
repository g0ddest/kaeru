package app.kaeru.data.pairing

import android.util.Log
import app.kaeru.di.IoDispatcher
import app.kaeru.domain.error.PairingFailed
import app.kaeru.domain.error.PairingFailureReason
import app.kaeru.domain.pairing.PairingSession
import app.kaeru.domain.pairing.TvPairingServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Clock
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The smallest HTTP server that can finish a sign-in: one method, one path, one useful answer.
 *
 * It exists because the alternative is asking somebody to type a code off a television with a
 * remote control. It is open only while the login screen is, listens on a port the operating system
 * picks, and the only thing it will do with what arrives is hand it straight to the token exchange.
 *
 * Written by hand rather than with a server library because the whole protocol is a request line,
 * a `Content-Length` and one small JSON object; every read is bounded in bytes and in time, so a
 * caller on the local network can neither make this allocate more than a few kilobytes nor keep it
 * to itself for more than a few seconds.
 */
@Singleton
class SocketPairingServer @Inject constructor(
    private val json: Json,
    private val clock: Clock,
    private val addresses: LanAddresses,
    private val timeouts: PairingTimeouts,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : TvPairingServer {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * Everything about which offer is the current one, and it is never read outside [lock].
     *
     * [generation] is what makes a `stop()` that lands in the middle of a `start()` mean something:
     * the start reads its own number on the way in, and installs its socket only if the number is
     * still current on the way out. Without it a stop can close the previous socket, watch the
     * start install a new one behind it, and leave a television listening for a code nobody can see
     * a QR for — with the expiry timer already cancelled, so nothing would ever close it.
     */
    private val lock = Any()
    private var generation = 0
    private var socket: ServerSocket? = null
    private var worker: Job? = null

    /**
     * Set the moment a code is accepted and cleared again only if its exchange failed. A phone
     * whose first attempt died on a flaky network gets to retry with a fresh code; a second phone
     * arriving after a completed sign-in is told the offer is gone.
     */
    private val paired = AtomicBoolean(false)

    override suspend fun start(
        session: PairingSession,
        onCode: suspend (code: String, redirectUri: String) -> Result<Unit>,
    ): Result<TvPairingServer.Endpoint> = withContext(dispatcher) {
        val mine = synchronized(lock) {
            closeLocked()
            ++generation
        }
        val host = addresses.siteLocalIpv4()
            ?: return@withContext Result.failure(PairingFailed(PairingFailureReason.NO_LOCAL_ADDRESS))
        val opened = runCatching { openPort() }.getOrElse { error ->
            return@withContext Result.failure(PairingFailed(PairingFailureReason.NO_LOCAL_ADDRESS, error))
        }
        val installed = synchronized(lock) {
            if (generation != mine) {
                false
            } else {
                socket = opened
                paired.set(false)
                worker = scope.launch { serve(mine, opened, session, onCode) }
                true
            }
        }
        if (!installed) {
            // Somebody stopped this offer, or replaced it, while the port was being opened. The
            // socket that was about to become the current one never does.
            runCatching { opened.close() }
            return@withContext Result.failure(PairingFailed(PairingFailureReason.SUPERSEDED))
        }
        Result.success(TvPairingServer.Endpoint(host, opened.localPort))
    }

    override fun stop() {
        synchronized(lock) {
            ++generation
            closeLocked()
        }
    }

    /** Caller holds [lock]. */
    private fun closeLocked() {
        worker?.cancel()
        worker = null
        // Closing is what unblocks `accept()`; the worker is otherwise parked in it forever.
        runCatching { socket?.close() }
        socket = null
    }

    private fun current(generationAtStart: Int): Boolean =
        synchronized(lock) { generation == generationAtStart }

    /**
     * A port of our own, on every interface this television has.
     *
     * `SO_REUSEADDR` is turned off, which is not the default: with it on, a wildcard bind happily
     * shares a port that something else already holds on a single address, and then a connection
     * to that address reaches the other listener rather than this one. On a television that means
     * a phone's code being handed to whatever else is listening, which is worth failing to start
     * over.
     */
    private fun openPort(): ServerSocket = ServerSocket().apply {
        reuseAddress = false
        bind(InetSocketAddress(ANY_FREE_PORT), BACKLOG)
    }

    private suspend fun serve(
        generationAtStart: Int,
        server: ServerSocket,
        session: PairingSession,
        onCode: suspend (String, String) -> Result<Unit>,
    ) {
        while (currentCoroutineContext().isActive && !server.isClosed && current(generationAtStart)) {
            val client = runCatching { server.accept() }.getOrNull() ?: return
            try {
                handle(generationAtStart, client, session, onCode)
            } catch (cancelled: CancellationException) {
                // The offer itself is being taken down. That is the one throw that belongs to the
                // loop rather than to the connection, and it has to reach the coroutine machinery.
                throw cancelled
            } catch (failure: Throwable) {
                // Everything else is one connection's problem and is kept to that connection.
                //
                // An IOException is the ordinary case and says nothing worth hearing: a phone that
                // hung up mid-request, or a caller the watchdog closed the socket on. Anything else
                // is a bug — in parsing, in the token exchange, in a callback — and used to end the
                // `while` below, leaving the port bound with nobody accepting on it: a QR still on
                // screen pointing at a socket that would never answer again, until the viewer left
                // the login screen and came back. So the loop survives it, and says so in the log.
                if (failure !is IOException) {
                    Log.w(TAG, "A pairing connection failed; still listening for the next one", failure)
                }
            } finally {
                runCatching { client.close() }
            }
        }
    }

    private suspend fun handle(
        generationAtStart: Int,
        client: Socket,
        session: PairingSession,
        onCode: suspend (String, String) -> Result<Unit>,
    ) {
        client.soTimeout = timeouts.readMs
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeouts.acceptMs)
        // The read loops check the deadline themselves, but only between bytes: a caller that
        // sends nothing at all is parked inside `read` and notices nothing. Closing the socket
        // from the outside is what actually ends that, so the deadline gets a watchdog as well as
        // a check. It is cancelled the instant the request is complete, because what follows is
        // the television's own round trip to Shikimori and that is allowed to take its time.
        val watchdog = scope.launch {
            delay(timeouts.acceptMs)
            runCatching { client.close() }
        }
        val request = try {
            readRequest(HttpWire(client.getInputStream(), deadline))
        } finally {
            watchdog.cancel()
        }
        // Once a code has been read off the wire the rest must finish. Cancelling here would lose
        // the profile write that follows the token write inside the exchange, and would leave the
        // phone holding a connection that closes with no answer on it — and the sign-in this
        // answers may itself be what takes the login screen, and this server, down.
        withContext(NonCancellable) {
            write(client, decide(generationAtStart, request, session, onCode))
        }
    }

    private suspend fun decide(
        generationAtStart: Int,
        request: RawRequest?,
        session: PairingSession,
        onCode: suspend (String, String) -> Result<Unit>,
    ): Answer {
        if (request == null || request.method != "POST" || request.path != PAIR_PATH) return badRequest()
        val payload = runCatching { json.decodeFromString<PairingPayload>(request.body) }.getOrNull()
            ?: return badRequest()
        // The nonce comes first, before anything that would describe the state of this offer: a
        // caller that has not read the code off the television screen learns only that it guessed
        // wrong, never whether a pairing is live, spent or stale.
        if (!session.matches(payload.nonce)) return refused(409, PairingErrors.NONCE_MISMATCH)
        if (payload.code.isBlank()) return badRequest()
        if (paired.get()) return refused(410, PairingErrors.ALREADY_PAIRED)
        if (session.isExpired(clock.instant())) return refused(410, PairingErrors.EXPIRED)
        if (!current(generationAtStart)) return refused(410, PairingErrors.EXPIRED)
        // Claimed before the exchange rather than after it, so two phones racing the same nonce
        // cannot both get as far as spending a code.
        if (!paired.compareAndSet(false, true)) return refused(410, PairingErrors.ALREADY_PAIRED)
        val exchanged = try {
            onCode(payload.code, payload.redirectUri)
        } catch (failure: Throwable) {
            // A claim is only worth holding while something is being done with it. An exchange that
            // threw spent nothing, so the nonce goes back on offer and the throw goes up to the
            // accept loop, which logs it and keeps listening. Without this the first unexpected
            // failure would leave a code on screen that no phone could ever use.
            paired.set(false)
            throw failure
        }
        if (exchanged.isFailure) {
            paired.set(false)
            return refused(409, PairingErrors.EXCHANGE_FAILED)
        }
        return Answer(200, json.encodeToString(PairingAccepted(ok = true)))
    }

    private fun badRequest() = refused(400, PairingErrors.BAD_REQUEST)

    private fun refused(status: Int, error: String) = Answer(status, json.encodeToString(PairingRefused(error)))

    private fun readRequest(wire: HttpWire): RawRequest? {
        val line = wire.line() ?: return null
        val parts = line.split(' ')
        if (parts.size != 3) return null
        val length = wire.contentLength() ?: return null
        if (length < 1 || length > MAX_BODY_BYTES) return null
        val body = wire.body(length) ?: return null
        return RawRequest(
            method = parts[0].uppercase(),
            path = parts[1].substringBefore('?'),
            body = body,
        )
    }

    private fun write(client: Socket, answer: Answer) {
        val body = answer.body.toByteArray(Charsets.UTF_8)
        client.getOutputStream().apply {
            write(
                httpHead(
                    "HTTP/1.1 ${answer.status} ${reason(answer.status)}",
                    "Content-Type: application/json; charset=utf-8",
                    "Content-Length: ${body.size}",
                    "Connection: close",
                ),
            )
            write(body)
            flush()
        }
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        409 -> "Conflict"
        410 -> "Gone"
        else -> "Error"
    }

    private data class RawRequest(val method: String, val path: String, val body: String)

    private data class Answer(val status: Int, val body: String)

    private companion object {
        const val TAG = "KaeruPairing"
        const val ANY_FREE_PORT = 0
        const val BACKLOG = 4
        const val PAIR_PATH = "/pair"
    }
}
