package app.kaeru.domain.together

import app.kaeru.domain.error.TogetherFailed
import app.kaeru.domain.error.TogetherFailureReason
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * What actually goes on the wire: one message, as JSON, sealed under the key from the link.
 *
 * A frame is `nonce ∥ ciphertext ∥ tag` and nothing else — no header, no room name, no length.
 * Length belongs to whoever is carrying the frame: the LAN transport writes four bytes in front of
 * it because a TCP stream has no message boundaries of its own, and the relay transport sends it
 * as one binary WebSocket message because that protocol already has them.
 *
 * The point of encrypting at all is the relay. It is a stranger's machine that has to read the room
 * name to route, and it is trusted with exactly that much: it never sees what episode two people
 * are on, what they said to each other, or the sound of either of them.
 *
 * A nonce is never reused under one key. GCM does not merely leak with a repeated nonce, it hands
 * over the authentication key, so [newNonce] draws fresh bytes for every frame and [encode] takes
 * the nonce as an argument rather than keeping a counter that a reconnect could reset.
 */
object TogetherCodec {

    /**
     * The largest frame either side will write or accept.
     *
     * It exists to stop a peer from making the other one allocate: the biggest honest message is a
     * voice slice, capped at half of this, and everything else is a few hundred bytes.
     */
    const val MAX_FRAME_BYTES = 64 * 1024

    /** 96 bits — the size GCM is specified for, and the only one that avoids an internal rehash. */
    const val NONCE_BYTES = 12

    /** 128-bit tag, the full-strength one. */
    const val TAG_BITS = 128

    const val TAG_BYTES = TAG_BITS / 8

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ALGORITHM = "AES"

    private val json = Json {
        // One letter, on every frame, for the length of an episode.
        classDiscriminator = "t"
        // A newer build may say more than this one understands; the parts it does understand still
        // work, which is what keeps a session between two different app versions alive.
        ignoreUnknownKeys = true
    }

    fun newNonce(random: SecureRandom): ByteArray = ByteArray(NONCE_BYTES).also(random::nextBytes)

    /**
     * Throws [TogetherFailed] with [TogetherFailureReason.FRAME_TOO_LARGE] rather than writing a
     * frame nobody will accept. Only a voice clip can get near the cap, and the sender cuts those
     * into slices before they reach here — so a throw means a bug on this side, not bad input.
     */
    fun encode(msg: TogetherMessage, key: ByteArray, nonce: ByteArray): ByteArray {
        require(key.size == RoomLink.KEY_BYTES) { "A room key is ${RoomLink.KEY_BYTES} bytes" }
        require(nonce.size == NONCE_BYTES) { "A GCM nonce is $NONCE_BYTES bytes" }
        val plaintext = json.encodeToString<TogetherMessage>(msg).toByteArray(Charsets.UTF_8)
        val sealed = cipher(Cipher.ENCRYPT_MODE, key, nonce).doFinal(plaintext)
        val frame = ByteArray(nonce.size + sealed.size)
        nonce.copyInto(frame)
        sealed.copyInto(frame, nonce.size)
        if (frame.size > MAX_FRAME_BYTES) {
            throw TogetherFailed(TogetherFailureReason.FRAME_TOO_LARGE)
        }
        return frame
    }

    /**
     * Never throws. Every way a frame can be wrong — too long, too short, the wrong key, a flipped
     * bit, JSON this build cannot read — comes back as a failure the transport can hand to its
     * collector and carry on, because one bad frame is not a reason to end a viewing.
     */
    fun decode(frame: ByteArray, key: ByteArray): Result<TogetherMessage> {
        if (frame.size > MAX_FRAME_BYTES) return failure(TogetherFailureReason.FRAME_TOO_LARGE)
        if (frame.size <= NONCE_BYTES + TAG_BYTES) return failure(TogetherFailureReason.TAMPERED)
        if (key.size != RoomLink.KEY_BYTES) return failure(TogetherFailureReason.TAMPERED)
        val nonce = frame.copyOfRange(0, NONCE_BYTES)
        val sealed = frame.copyOfRange(NONCE_BYTES, frame.size)
        val plaintext = runCatching { cipher(Cipher.DECRYPT_MODE, key, nonce).doFinal(sealed) }
            .getOrElse { return failure(TogetherFailureReason.TAMPERED, it) }
        return runCatching { json.decodeFromString<TogetherMessage>(plaintext.toString(Charsets.UTF_8)) }
            .fold({ Result.success(it) }, { failure(TogetherFailureReason.TAMPERED, it) })
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, SecretKeySpec(key, ALGORITHM), GCMParameterSpec(TAG_BITS, nonce))
        }

    private fun failure(reason: TogetherFailureReason, cause: Throwable? = null): Result<Nothing> =
        Result.failure(TogetherFailed(reason, cause))
}
