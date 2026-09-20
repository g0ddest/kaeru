package app.kaeru.data.pairing

import java.io.InputStream

internal const val CRLF = "\r\n"

/** A request line, a status line or one header. Anything longer is not this protocol. */
internal const val MAX_LINE_BYTES = 1_024

/** The whole header block, across all of its lines. */
internal const val MAX_HEADER_BYTES = 8_192

/** A nonce, a code and a redirect one way; `{"ok":true}` the other. Four kilobytes is generous. */
internal const val MAX_BODY_BYTES = 4_096

/** What [HttpWire.contentLength] answers when the message declared none. */
internal const val NO_BODY = -1

/**
 * One HTTP/1.1 message read off a socket, bounded in bytes and in wall-clock time.
 *
 * Both halves of the pairing hand-off speak the same handful of lines, and both are reading from
 * something they have no reason to trust — a television from anything on the Wi-Fi, a phone from
 * whatever actually answered at the address in a QR code. So every loop here has two ceilings: a
 * byte budget, and a deadline that does not reset when a byte arrives. A per-read timeout alone
 * bounds neither, because a caller that sends one byte just inside it holds the connection for as
 * long as the byte budget lasts.
 *
 * Null means «not a message I will read»: end of stream, over budget, past the deadline, or
 * malformed. The caller never learns which, because none of those want different handling.
 */
internal class HttpWire(stream: InputStream, private val deadlineNanos: Long) {
    private val input = stream.buffered()

    private fun expired() = System.nanoTime() - deadlineNanos > 0

    /** One CRLF-terminated line with the terminator stripped. */
    fun line(max: Int = MAX_LINE_BYTES): String? {
        val buffer = StringBuilder()
        while (buffer.length <= max) {
            if (expired()) return null
            when (val byte = input.read()) {
                -1 -> return null
                '\n'.code -> return buffer.toString().removeSuffix("\r")
                else -> buffer.append(byte.toChar())
            }
        }
        return null
    }

    /**
     * Reads header lines up to the blank one and answers the `Content-Length` it found, or
     * [NO_BODY] when the message declared none.
     */
    fun contentLength(budget: Int = MAX_HEADER_BYTES): Int? {
        var left = budget
        var length = NO_BODY
        while (true) {
            val header = line() ?: return null
            if (header.isEmpty()) return length
            left -= header.length
            if (left < 0) return null
            val colon = header.indexOf(':')
            if (colon <= 0) return null
            if (header.take(colon).trim().equals("content-length", ignoreCase = true)) {
                length = header.substring(colon + 1).trim().toIntOrNull() ?: return null
            }
        }
    }

    /** Exactly [length] bytes, as UTF-8. The array is sized from a length the caller has checked. */
    fun body(length: Int): String? {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            if (expired()) return null
            val chunk = input.read(bytes, read, length - read)
            if (chunk < 0) return null
            read += chunk
        }
        return String(bytes, Charsets.UTF_8)
    }
}

/** The head of a message this app writes: a first line, three headers, and a blank line. */
internal fun httpHead(first: String, vararg headers: String): ByteArray = buildString {
    append(first).append(CRLF)
    headers.forEach { append(it).append(CRLF) }
    append(CRLF)
}.toByteArray(Charsets.US_ASCII)
