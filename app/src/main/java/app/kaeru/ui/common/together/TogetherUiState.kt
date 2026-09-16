package app.kaeru.ui.common.together

import app.kaeru.domain.together.ReactionKind

/** Where a shared viewing is, in the one word a screen needs to decide what to draw. */
enum class TogetherPhase { IDLE, HOSTING, JOINING, LIVE, LOST, ENDED }

/** The two things a wait can offer, and there is never a third. */
enum class WaitExit { KEEP_WATCHING, WATCH_ALONE }

/**
 * A wait, or the end of one, with the button that gets the evening back.
 *
 * Never built without an [exit]: this type exists so that the compiler, and not a reviewer, is
 * what stops a state where the video has stopped for somebody else's reason and nothing on screen
 * gives control back.
 */
data class WaitLine(val text: String, val exit: WaitExit)

/** One line at the top of the screen about what the other phone did. Replaced, never queued. */
data class NoticeLine(val id: Long, val text: String)

/** A recorded clip as the overlay holds it: bytes to play, and how long they run. */
data class VoiceClipItem(val bytes: ByteArray, val durationMs: Int) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VoiceClipItem) return false
        return durationMs == other.durationMs && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + durationMs
}

/**
 * Something somebody said: a line of text or a clip, never both.
 *
 * The same value stands in the corner for seven seconds and in the history sheet for the whole
 * session, so it carries [at] — the corner has no use for a timestamp, and the sheet cannot be
 * read without one.
 */
data class ConversationItem(
    val id: Long,
    val mine: Boolean,
    val author: String,
    val text: String? = null,
    val clip: VoiceClipItem? = null,
    val at: Long = 0,
)

/** An emoji on its way up the right-hand side of the picture. */
data class FlyingReaction(val id: Long, val kind: ReactionKind, val mine: Boolean)

/** A clip to put through the speaker right now, once. */
data class PlayingClip(val id: Long, val bytes: ByteArray, val durationMs: Int) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlayingClip) return false
        return id == other.id && durationMs == other.durationMs && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + durationMs
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/** The invitation, ready for the system share sheet. */
data class ShareRequest(val text: String, val link: String)

/**
 * The screen a link opens, before anybody has agreed to anything.
 *
 * [loading] is the honest state of an invitation whose sender has not answered yet: the room is
 * known, and nothing else is. Everything below it arrives together with the hello, which is why
 * they are all nullable and not defaulted to something plausible.
 */
data class JoinUiState(
    val loading: Boolean = true,
    val peerName: String? = null,
    val title: String? = null,
    val posterUrl: String? = null,
    val animeId: Int = 0,
    val episode: Int = 0,
    val positionMs: Long = 0,
    /** «Вася смотрит «…», 7 серия, 12:04», once there is something to say. */
    val line: String? = null,
    val error: String? = null,
    /**
     * Whether [error] is worth another attempt.
     *
     * False for a link that was never a room: there is nothing to knock on again, and a
     * «Повторить» that cannot do anything is worse than no button at all.
     */
    val retryable: Boolean = true,
)

/**
 * A shared viewing as two screens see it.
 *
 * One state for both because they are the same session: the player overlay reads [stack],
 * [reactions] and [notice]; the join screen reads [join]; both read [wait], which is the part
 * neither of them is allowed to draw without a way out.
 */
data class TogetherUiState(
    val phase: TogetherPhase = TogetherPhase.IDLE,
    val peerName: String? = null,
    val wait: WaitLine? = null,
    val notice: NoticeLine? = null,
    /** What is in the bottom-left corner right now: at most three, each on its own timer. */
    val stack: List<ConversationItem> = emptyList(),
    /** Everything said this session, oldest first. In memory, and gone when the session is. */
    val history: List<ConversationItem> = emptyList(),
    val historyOpen: Boolean = false,
    val reactions: List<FlyingReaction> = emptyList(),
    val playing: PlayingClip? = null,
    val share: ShareRequest? = null,
    val join: JoinUiState? = null,
    /** One line at the bottom, said once and forgotten. */
    val message: String? = null,
) {
    /** Whether the two round buttons and the input row belong on the player at all. */
    val live: Boolean get() = phase == TogetherPhase.LIVE

    /** Whether the player's own action says «поделиться» or «выйти». */
    val active: Boolean get() = phase != TogetherPhase.IDLE
}
