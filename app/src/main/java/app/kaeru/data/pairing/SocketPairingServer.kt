package app.kaeru.data.pairing

import app.kaeru.di.IoDispatcher
import app.kaeru.domain.error.PairingFailed
import app.kaeru.domain.error.PairingFailureReason
import app.kaeru.domain.pairing.PairingSession
import app.kaeru.domain.pairing.TvPairingServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.time.Clock
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
 * a `Content-Length` and one small JSON object; every read is bounded, so a caller on the local
 * network cannot make this allocate more than a few kilobytes however it misbehaves.
 */
@Singleton
class SocketPairingServer @Inject constructor(
    private val json: Json,
    private val clock: Clock,
    private val addresses: LanAddresses,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : TvPairingServer {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile private var socket: ServerSocket? = null
    private var worker: Job? = null

    /**
     * Set the moment a code is accepted and cleared again only if its exchange failed. A phone
     * whose first attempt died on a flaky network gets to retry with a fresh code; a second phone
     * arriving after a completed sign-in is told the offer is gone.
     */
    private val paired = AtomicBoolean(false)

    override fun start(
        session: PairingSession,
        onCode: suspend (code: String, redirectUri: String) -> Result<Unit>,
    ): Result<TvPairingServer.Endpoint> {
        stop()
        val host = addresses.siteLocalIpv4()
            ?: return Result.failure(PairingFailed(PairingFailureReason.NO_LOCAL_ADDRESS))
        val opened = runCatching { ServerSocket(ANY_FREE_PORT, BACKLOG) }.getOrElse { error ->
            return Result.failure(PairingFailed(PairingFailureReason.NO_LOCAL_ADDRESS, error))
        }
        socket = opened
        paired.set(false)
        worker = scope.launch { serve(opened, session, onCode) }
        return Result.success(TvPairingServer.Endpoint(host, opened.localPort))
    }

    override fun stop() {
        worker?.cancel()
        worker = null
        // Closing is what unblocks `accept()`; the worker is otherwise parked in it forever.
        runCatching { socket?.close() }
        socket = null
    }

    private suspend fun serve(
        server: ServerSocket,
        session: PairingSession,
        onCode: suspend (String, String) -> Result<Unit>,
    ) {
        while (currentCoroutineContext().isActive && !server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: return
            try {
                client.soTimeout = READ_TIMEOUT_MS
                val answer = decide(readRequest(client.getInputStream()), session, onCode)
                // The sign-in this answer reports may already have swapped the screen underneath
                // us, which takes the login screen's view model and this server down with it. The
                // phone still has to hear how it went, so the write is not the thing that gets
                // cancelled.
                withContext(NonCancellable) { write(client, answer) }
            } catch (_: IOException) {
                // A phone that hung up mid-request is not a failure anyone needs to hear about.
            } finally {
                runCatching { client.close() }
            }
        }
    }

    private suspend fun decide(
        request: RawRequest?,
        session: PairingSession,
        onCode: suspend (String, String) -> Result<Unit>,
    ): Answer {
        if (request == null || request.method != "POST" || request.path != PAIR_PATH) return badRequest()
        val payload = runCatching { json.decodeFromString<PairingPayload>(request.body) }.getOrNull()
            ?: return badRequest()
        if (payload.code.isBlank()) return badRequest()
        if (paired.get()) return refused(410, PairingErrors.ALREADY_PAIRED)
        val now = clock.instant()
        if (session.isExpired(now)) return refused(410, PairingErrors.EXPIRED)
        if (!session.isValid(now, payload.nonce)) return refused(409, PairingErrors.NONCE_MISMATCH)
        // Claimed before the exchange rather than after it, so two phones racing the same nonce
        // cannot both get as far as spending a code.
        if (!paired.compareAndSet(false, true)) return refused(410, PairingErrors.ALREADY_PAIRED)
        val exchanged = onCode(payload.code, payload.redirectUri)
        if (exchanged.isFailure) {
            paired.set(false)
            return refused(409, PairingErrors.EXCHANGE_FAILED)
        }
        return Answer(200, json.encodeToString(PairingAccepted(ok = true)))
    }

    private fun badRequest() = refused(400, PairingErrors.BAD_REQUEST)

    private fun refused(status: Int, error: String) = Answer(status, json.encodeToString(PairingRefused(error)))

    /**
     * Reads one request, or nothing at all when it is malformed or larger than a pairing could
     * possibly be. Every loop here is bounded, which is the whole reason this is not a
     * `BufferedReader`: that would also read past the headers into the body it was not asked for.
     */
    private fun readRequest(input: InputStream): RawRequest? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size != 3) return null
        var length = -1
        var budget = MAX_HEADER_BYTES
        while (true) {
            val header = readLine(input) ?: return null
            if (header.isEmpty()) break
            budget -= header.length
            if (budget < 0) return null
            val colon = header.indexOf(':')
            if (colon <= 0) return null
            if (header.take(colon).trim().equals("content-length", ignoreCase = true)) {
                length = header.substring(colon + 1).trim().toIntOrNull() ?: return null
            }
        }
        if (length < 1 || length > MAX_BODY_BYTES) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val chunk = input.read(body, read, length - read)
            if (chunk < 0) return null
            read += chunk
        }
        return RawRequest(
            method = parts[0].uppercase(),
            path = parts[1].substringBefore('?'),
            body = String(body, Charsets.UTF_8),
        )
    }

    /** One CRLF-terminated line, capped, with the terminator stripped. Null on EOF or overrun. */
    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (buffer.length <= MAX_LINE_BYTES) {
            when (val byte = input.read()) {
                -1 -> return null
                '\n'.code -> return buffer.removeSuffix("\r")
                else -> buffer.append(byte.toChar())
            }
        }
        return null
    }

    private fun StringBuilder.removeSuffix(suffix: String): String =
        toString().let { if (it.endsWith(suffix)) it.dropLast(suffix.length) else it }

    private fun write(client: Socket, answer: Answer) {
        val body = answer.body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(answer.status).append(' ').append(reason(answer.status)).append(CRLF)
            append("Content-Type: application/json; charset=utf-8").append(CRLF)
            append("Content-Length: ").append(body.size).append(CRLF)
            append("Connection: close").append(CRLF).append(CRLF)
        }
        client.getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
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
        const val ANY_FREE_PORT = 0
        const val BACKLOG = 4
        const val PAIR_PATH = "/pair"
        const val CRLF = "\r\n"

        /** A phone on the same Wi-Fi answers in milliseconds; anything slower is not the phone. */
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_LINE_BYTES = 1_024
        const val MAX_HEADER_BYTES = 8_192

        /** A nonce, a code and a redirect. Four kilobytes is already generous. */
        const val MAX_BODY_BYTES = 4_096
    }
}
