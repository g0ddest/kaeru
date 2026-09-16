package app.kaeru.domain.together

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The first thing a peer says: who they are and exactly what they are watching.
 *
 * It is what the join screen is built from — before it arrives there is a room and nothing else
 * to say about it, which is why every field of it is known only together.
 */
data class PeerHello(
    val name: String,
    val animeId: Int,
    val episode: Int,
    val translationId: Int?,
    val positionMs: Long,
    val playing: Boolean,
)

/** Why a shared viewing stopped being one. Each of these is a different sentence to the viewer. */
enum class LostReason {
    /** The channel went and would not come back inside its reconnect window. */
    CONNECTION,

    /** A wait ran out its thirty seconds with nobody on the other end. */
    WAIT_TIMEOUT,

    /** Two people are already in this room, and a room holds two. */
    ROOM_FULL,

    /** This build carries no relay address, so only one Wi-Fi can be shared on. */
    NOT_CONFIGURED,
}

/** What the other phone just did, as something worth one line at the top of the screen. */
enum class NoticeKind { PAUSED, PLAYED, SEEKED, EPISODE, JOINED, LEFT, CATCHING_UP, OTHER_VOICE }

/**
 * Where a shared viewing is, from nothing through to over.
 *
 * Every state that stops the viewer getting on with the episode carries its own way out, and the
 * session is the thing that times them: [Hosting] with `waiting`, [Joining] before a hello, and
 * [Lost] are the three the screen has to put a button under.
 */
sealed interface SessionState {
    data object Idle : SessionState

    /** A room is open and the link has been made; `waiting` until somebody walks through it. */
    data class Hosting(val link: RoomLink, val waiting: Boolean) : SessionState

    /** Knocking on somebody else's room. `hello` is null until they answer. */
    data class Joining(val link: RoomLink, val hello: PeerHello?) : SessionState

    /** Two phones, one episode. [driftMs] is how far apart they are right now. */
    data class Live(val peerName: String, val offsetMs: Long, val driftMs: Long) : SessionState

    data class Lost(val reason: LostReason) : SessionState

    /** Somebody said goodbye. Nothing failed; there is simply nobody there any more. */
    data object Ended : SessionState
}

/** Something that happened once, as opposed to a state that is. */
sealed interface TogetherEvent {
    data class Notice(
        val kind: NoticeKind,
        val peerName: String,
        val positionMs: Long? = null,
        val episode: Int? = null,
    ) : TogetherEvent

    data class ChatItem(val id: Long, val fromPeer: Boolean, val text: String, val at: Long) : TogetherEvent

    data class ReactionEvent(val id: Long, val fromPeer: Boolean, val kind: ReactionKind) : TogetherEvent

    /** A recorded clip, already reassembled. The bytes are playable audio, not a frame. */
    data class VoiceClip(val id: Long, val fromPeer: Boolean, val bytes: ByteArray, val durationMs: Int) : TogetherEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is VoiceClip) return false
            return id == other.id && fromPeer == other.fromPeer &&
                durationMs == other.durationMs && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + fromPeer.hashCode()
            result = 31 * result + durationMs
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }
}

/**
 * A shared viewing, as everything above the transport sees it.
 *
 * An interface rather than the class itself so the screens can be built, previewed and tested
 * against a session that does nothing — and so the player's own engine is the only thing that has
 * to know what a socket is.
 */
interface TogetherSessionApi {
    val state: StateFlow<SessionState>

    val events: SharedFlow<TogetherEvent>

    /** Opens a room and returns the link to it. The room waits until somebody joins or leaves. */
    suspend fun host(name: String): RoomLink

    suspend fun join(link: RoomLink, name: String)

    suspend fun leave()

    suspend fun sendChat(text: String)

    suspend fun sendReaction(kind: ReactionKind)

    suspend fun sendVoice(bytes: ByteArray, durationMs: Int)

    /**
     * Leaves whatever wait the viewer is stuck in without leaving the session behind it.
     *
     * The one rule this feature is built around: no state where the video is stopped for
     * somebody else's reason may be without a button that gives it back.
     */
    fun watchAlone()
}
