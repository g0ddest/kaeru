package app.kaeru.domain.together

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Base64

/**
 * Everything two phones say to each other while watching one episode.
 *
 * Every message carries [seq], the sender's own count, and that count is meant to be the whole of
 * the conflict resolution: the two sides are equals, both may pause and seek, and when their
 * commands cross on the wire the later one should win.
 *
 * Acting on that is the session's job and nothing else's. The codec sees one frame at a time and
 * has nothing to compare it against; a transport delivers whatever authenticates. So it is the
 * session above that has to keep the highest [seq] it has applied from each peer and drop anything
 * that does not exceed it — which is also what stops a relay replaying an old `Seek` back down the
 * room it carried it through. Until it does, this counter is a number in a frame and not a
 * guarantee.
 *
 * The serial names are short because every one of them is encrypted, framed and sent once a
 * second for the length of an episode.
 */
@Serializable
sealed interface TogetherMessage {
    val seq: Long

    /**
     * The first thing either side sends: who this is, and what they are already watching.
     *
     * [epoch] says which connection of the sender's this greeting belongs to: drawn at random each
     * time a side connects, never the same twice. A friend whose session started over counts from
     * one again, and the epoch is what tells that greeting apart from one the relay kept and handed
     * back. Null from a build older than the field, and read as such.
     */
    @Serializable
    @SerialName("hello")
    data class Hello(
        val name: String,
        val animeId: Int,
        val episode: Int,
        val translationId: Int? = null,
        val positionMs: Long,
        val playing: Boolean,
        override val seq: Long,
        val epoch: Long? = null,
    ) : TogetherMessage

    @Serializable
    @SerialName("play")
    data class Play(val positionMs: Long, override val seq: Long) : TogetherMessage

    @Serializable
    @SerialName("pause")
    data class Pause(val positionMs: Long, override val seq: Long) : TogetherMessage

    @Serializable
    @SerialName("seek")
    data class Seek(val positionMs: Long, override val seq: Long) : TogetherMessage

    /** One side changed episode or voice track; the other follows, resolving its own source. */
    @Serializable
    @SerialName("episode")
    data class Episode(
        val episode: Int,
        val translationId: Int? = null,
        override val seq: Long,
    ) : TogetherMessage

    /**
     * Where this side is, once a second.
     *
     * Never a command: a peer that reports `playing = false` has not asked anybody to pause, and
     * nobody pauses because of it. Pausing is [Pause] and nothing else. This one exists so the
     * other side can measure drift, and so a stall can be shown as a stall rather than as a
     * friend who wandered off.
     */
    @Serializable
    @SerialName("state")
    data class State(
        val positionMs: Long,
        val playing: Boolean,
        val buffering: Boolean,
        val sentAt: Long,
        override val seq: Long,
        /**
         * What the position is a position *in*. Optional, because a report used to be a position
         * and nothing else — and a guest followed it whatever episode it came from, so a friend
         * who had moved on dragged this side's picture through the wrong one. A build without
         * these fields is followed as it always was.
         */
        val animeId: Int? = null,
        val episode: Int? = null,
    ) : TogetherMessage

    @Serializable
    @SerialName("chat")
    data class Chat(val text: String, override val seq: Long) : TogetherMessage

    @Serializable
    @SerialName("reaction")
    data class Reaction(val kind: ReactionKind, override val seq: Long) : TogetherMessage

    /**
     * A slice of one voice clip. [chunk] counts from zero up to [total], and the last slice of a
     * clip is the one where `chunk == total - 1`.
     *
     * Clips are cut up because a frame is capped at 64 KB and thirty seconds of Opus is more than
     * that. Both transports deliver in order, so a receiver needs nothing but the counter to put
     * a clip back together.
     */
    @Serializable
    @SerialName("voice")
    data class Voice(
        val chunk: Int,
        val total: Int,
        @Serializable(with = Base64Bytes::class) val bytes: ByteArray,
        val durationMs: Long,
        override val seq: Long,
    ) : TogetherMessage {
        init {
            require(bytes.size <= MAX_VOICE_CHUNK_BYTES) {
                "A voice slice is at most $MAX_VOICE_CHUNK_BYTES bytes, not ${bytes.size}"
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Voice) return false
            return chunk == other.chunk && total == other.total &&
                bytes.contentEquals(other.bytes) && durationMs == other.durationMs && seq == other.seq
        }

        override fun hashCode(): Int {
            var result = chunk
            result = 31 * result + total
            result = 31 * result + bytes.contentHashCode()
            result = 31 * result + durationMs.hashCode()
            result = 31 * result + seq.hashCode()
            return result
        }
    }

    /** [sentAt] is this device's clock, echoed back in [Pong] so neither side has to trust it. */
    @Serializable
    @SerialName("ping")
    data class Ping(val sentAt: Long, override val seq: Long) : TogetherMessage

    /**
     * The three timestamps that turn a round trip into a clock offset: when the [Ping] said it
     * left, when it arrived here, and when this answer left.
     */
    @Serializable
    @SerialName("pong")
    data class Pong(
        val pingSentAt: Long,
        val receivedAt: Long,
        val sentAt: Long,
        override val seq: Long,
    ) : TogetherMessage

    @Serializable
    @SerialName("bye")
    data class Bye(override val seq: Long) : TogetherMessage

    /**
     * The friend's socket went away, but the channel did not.
     *
     * Nothing in this app ever sends one: it is what a transport says when it learns the other side
     * has gone while the way to reach them is still open — the relay's own doing, since a room keeps
     * a freed slot and the same friend may come back into it within the session's rejoin window.
     * That is what separates it from [Bye], which is somebody deciding to leave, and from the
     * channel closing, which is the session being over.
     *
     * [seq] is always zero. It is not a peer's message and takes no part in deciding whose action
     * came last.
     */
    @Serializable
    @SerialName("peer-left")
    data class PeerLeft(override val seq: Long = 0) : TogetherMessage

    companion object {
        /** A line over a video, not a conversation. The sender clamps; the receiver clamps to show. */
        const val MAX_CHAT_CHARS = 200

        /** Half a frame, so the encrypted slice and its framing still fit with room to spare. */
        const val MAX_VOICE_CHUNK_BYTES = 32 * 1024
    }
}

/** The six the overlay offers. Which glyph each one is drawn as belongs to the UI, not here. */
@Serializable
enum class ReactionKind {
    @SerialName("heart")
    HEART,

    @SerialName("laugh")
    LAUGH,

    @SerialName("wow")
    WOW,

    @SerialName("sad")
    SAD,

    @SerialName("fire")
    FIRE,

    @SerialName("clap")
    CLAP,
}

/**
 * Audio as base64url rather than as a JSON array of numbers.
 *
 * The default encoding of a `ByteArray` is `[104,105,...]` — four characters or so per byte, which
 * would put a third of every voice clip's budget into commas. Base64 costs a third on top of the
 * bytes and nothing else.
 */
object Base64Bytes : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.kaeru.together.Base64Bytes", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.getUrlEncoder().withoutPadding().encodeToString(value))
    }

    override fun deserialize(decoder: Decoder): ByteArray =
        Base64.getUrlDecoder().decode(decoder.decodeString())
}
