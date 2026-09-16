package app.kaeru.domain.together

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What a held button produced: playable audio and how long it runs. */
class RecordedClip(val bytes: ByteArray, val durationMs: Int)

/**
 * The microphone, as the button that holds it down sees one.
 *
 * In `domain` so that both sides depend downward: the button lives in `ui.mobile`, the
 * `MediaRecorder` in `data`, and neither has any business importing the other. Nothing here is
 * Android — a `ByteArray`, some numbers and a flag — which is also what lets the overlay be
 * previewed and the gesture reasoned about with no microphone anywhere near it.
 */
interface VoiceCapture {
    /**
     * Whether the microphone is open right now.
     *
     * A flow rather than a value because it can close without anybody asking it to: the framework
     * stops at the thirty-second ceiling, and the screen has to notice and send what was said.
     */
    val recording: StateFlow<Boolean>

    /** The ceiling, in milliseconds. Reaching it sends the clip rather than discarding it. */
    val maxDurationMs: Int

    /** Opens the microphone. False when the phone would not give it up. */
    fun start(): Boolean

    /** How loud it is right now, from nothing to as loud as this microphone goes. */
    fun level(): Float

    fun elapsedMs(): Int

    /** Closes the microphone and hands over what was said, or null if it was not worth sending. */
    fun stop(): RecordedClip?

    /** Closes the microphone and keeps nothing. */
    fun cancel()
}

/** A clip through the speaker, once. */
interface VoicePlayback {
    fun play(id: Long, bytes: ByteArray, onFinished: () -> Unit)

    fun stop()
}

/** A microphone that is not there: for previews, and for a screen with nothing to record with. */
object NoVoiceCapture : VoiceCapture {
    override val recording: StateFlow<Boolean> = MutableStateFlow(false)
    override val maxDurationMs: Int get() = 30_000
    override fun start(): Boolean = false
    override fun level(): Float = 0f
    override fun elapsedMs(): Int = 0
    override fun stop(): RecordedClip? = null
    override fun cancel() = Unit
}
