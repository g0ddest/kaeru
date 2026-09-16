package app.kaeru.ui.common.together

/** What a held button produced: playable audio and how long it runs. */
class RecordedClip(val bytes: ByteArray, val durationMs: Int)

/**
 * The microphone, as the button that holds it down sees one.
 *
 * An interface because the button lives in `ui.mobile` and a `MediaRecorder` lives in `data`, and
 * a screen that reached across for one would be the only place in the app where a composable knows
 * what an Android media API is. It is also what lets the overlay be previewed and the gesture be
 * reasoned about without a microphone anywhere near it.
 */
interface VoiceCapture {
    /** Whether the microphone is open right now. */
    val recording: Boolean

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
    override val recording: Boolean get() = false
    override val maxDurationMs: Int get() = 30_000
    override fun start(): Boolean = false
    override fun level(): Float = 0f
    override fun elapsedMs(): Int = 0
    override fun stop(): RecordedClip? = null
    override fun cancel() = Unit
}
