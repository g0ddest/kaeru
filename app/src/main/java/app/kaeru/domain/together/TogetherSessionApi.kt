package app.kaeru.domain.together

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

// ReactionKind is the one type of this contract that already existed: it travels on the wire, so
// it is declared beside the messages in TogetherMessage.kt rather than a second time here.

/** What a friend was watching when they said hello, which is what a join screen draws itself from. */
data class PeerHello(
    val name: String,
    val animeId: Int,
    val episode: Int,
    val translationId: Int?,
    val positionMs: Long,
    val playing: Boolean,
)

/** Why a shared viewing stopped being one. Every one of them is something to say out loud. */
enum class LostReason {
    /** The channel went and did not come back, or the friend left and did not return. */
    CONNECTION,

    /** Half a minute of waiting for somebody who never arrived. */
    WAIT_TIMEOUT,

    /** Two people are already in this room. A forwarded link does not make a third seat. */
    ROOM_FULL,

    /** This build has no relay, and the friend is not on this Wi-Fi. */
    NOT_CONFIGURED,
}

/**
 * Where a shared viewing is, in the only terms a screen needs.
 *
 * Every waiting state carries what it is waiting for, and every ending carries why — an overlay
 * that cannot say which of the two is happening is how a viewer ends up staring at a spinner.
 */
sealed interface SessionState {
    /** Nobody is watching along. The player is a player. */
    data object Idle : SessionState

    /** A link exists. [waiting] is true until a friend walks through it. */
    data class Hosting(val link: RoomLink, val waiting: Boolean) : SessionState

    /** Following a link. [hello] is null until the other phone says what it is watching. */
    data class Joining(val link: RoomLink, val hello: PeerHello?) : SessionState

    /**
     * Two phones on one episode. [offsetMs] is how far the friend's clock reads from this one and
     * [driftMs] how far their picture is from this one — positive when this side is ahead.
     */
    data class Live(val peerName: String, val offsetMs: Long, val driftMs: Long) : SessionState

    /** It stopped, and not because anybody asked. */
    data class Lost(val reason: LostReason) : SessionState

    /** Somebody left on purpose. */
    data object Ended : SessionState
}

/** The kinds of one-line thing that happen to a session and are worth a sentence over the video. */
enum class NoticeKind { PAUSED, PLAYED, SEEKED, EPISODE, JOINED, LEFT, CATCHING_UP, OTHER_VOICE }

/**
 * Everything a session hands the overlay, as it happens.
 *
 * [Notice] is about the session; the other three are people talking. [ChatItem.fromPeer] and its
 * siblings are false for this viewer's own, which the overlay shows too — a message you sent and
 * cannot see is a message you send twice.
 */
sealed interface TogetherEvent {
    data class Notice(
        val kind: NoticeKind,
        val peerName: String,
        val positionMs: Long? = null,
        val episode: Int? = null,
    ) : TogetherEvent

    data class ChatItem(val id: Long, val fromPeer: Boolean, val text: String, val at: Long) : TogetherEvent

    data class ReactionEvent(val id: Long, val fromPeer: Boolean, val kind: ReactionKind) : TogetherEvent

    data class VoiceClip(
        val id: Long,
        val fromPeer: Boolean,
        val bytes: ByteArray,
        val durationMs: Int,
    ) : TogetherEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is VoiceClip) return false
            return id == other.id && fromPeer == other.fromPeer &&
                bytes.contentEquals(other.bytes) && durationMs == other.durationMs
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + fromPeer.hashCode()
            result = 31 * result + bytes.contentHashCode()
            result = 31 * result + durationMs
            return result
        }
    }
}

/**
 * Watching one episode with one friend.
 *
 * Symmetric: there is no host in the sense of somebody in charge, only somebody who made the link.
 * Both sides may pause, seek and change episode, and the later action wins.
 *
 * Nothing here throws. A room that will not open, a friend who never arrives and a channel that
 * dies all arrive as [SessionState.Lost], because every one of them is something the screen has
 * to draw rather than something a caller can handle.
 */
interface TogetherSessionApi {
    val state: StateFlow<SessionState>

    val events: SharedFlow<TogetherEvent>

    /** Opens a room and returns the link to send. Returns as soon as there is a link to share. */
    suspend fun host(name: String): RoomLink

    /**
     * Follows a link: connects, waits for the friend's hello, opens what they are watching from
     * where they are, and returns once this phone is playing alongside them.
     */
    suspend fun join(link: RoomLink, name: String)

    /** Says goodbye, closes the channel, leaves the video playing. */
    suspend fun leave()

    suspend fun sendChat(text: String)

    suspend fun sendReaction(kind: ReactionKind)

    suspend fun sendVoice(bytes: ByteArray, durationMs: Int)

    /** The exit every wait has to have: stop waiting, stop hoping, keep watching. */
    fun watchAlone()
}
