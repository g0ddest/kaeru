package app.kaeru.data.together

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import app.kaeru.ui.common.together.RecordedClip
import app.kaeru.ui.common.together.VoiceCapture
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The microphone, for as long as a finger is on the button and not one millisecond longer.
 *
 * Opus at 16 kHz mono, which is about 3 KB a second: thirty seconds of speech is under 100 KB, so
 * a clip crosses the channel in one breath rather than in a visible transfer. Ogg and Opus arrived
 * in `MediaRecorder` at API 29, so older phones get AAC in an MP4 container — same sample rate,
 * same mono, a little larger, and both play back through the same decoder on the other side.
 *
 * It records to a file rather than to memory because `MediaRecorder` has no other mode. The file
 * lives in the cache and is deleted as soon as its bytes have been read, so a recording exists on
 * disk for the length of one clip and is never a recording of anything the viewer did not mean.
 */
@Singleton
class VoiceRecorder @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : VoiceCapture {

    /** Thirty seconds, and the last one sends rather than throws the speech away. */
    override val maxDurationMs: Int get() = MAX_DURATION_MS

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    override val recording: Boolean get() = recorder != null

    /**
     * Opens the microphone. False when the phone would not give it up, which is the answer to a
     * permission that was granted and then taken away as much as to a microphone in use elsewhere.
     */
    override fun start(): Boolean {
        if (recorder != null) return true
        val target = File(context.cacheDir, "voice-${System.currentTimeMillis()}.clip")
        val media = newRecorder()
        return runCatching {
            media.setAudioSource(MediaRecorder.AudioSource.MIC)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                media.setOutputFormat(MediaRecorder.OutputFormat.OGG)
                media.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            } else {
                media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            }
            media.setAudioChannels(1)
            media.setAudioSamplingRate(SAMPLE_RATE)
            media.setAudioEncodingBitRate(BIT_RATE)
            media.setOutputFile(target.absolutePath)
            media.prepare()
            media.start()
            recorder = media
            file = target
            startedAt = SystemClock.elapsedRealtime()
        }.onFailure {
            runCatching { media.release() }
            target.delete()
        }.isSuccess
    }

    /** How loud it is right now, from nothing to as loud as this microphone goes. */
    override fun level(): Float {
        val amplitude = runCatching { recorder?.maxAmplitude }.getOrNull() ?: return 0f
        return (amplitude / MAX_AMPLITUDE).coerceIn(0f, 1f)
    }

    override fun elapsedMs(): Int =
        if (recorder == null) 0 else (SystemClock.elapsedRealtime() - startedAt).toInt()

    /**
     * Closes the microphone and hands over what was said.
     *
     * Null for a clip too short to be one: a tap that was meant as a tap produces 80 milliseconds
     * of nothing, and sending it would put an unplayable smudge in the other person's corner.
     */
    override fun stop(): RecordedClip? {
        val media = recorder ?: return null
        val target = file
        recorder = null
        file = null
        val elapsed = (SystemClock.elapsedRealtime() - startedAt).toInt()
        val stopped = runCatching { media.stop() }.isSuccess
        runCatching { media.release() }
        val bytes = target?.takeIf { stopped && elapsed >= MIN_DURATION_MS }?.takeIf { it.exists() }
            ?.runCatching { readBytes() }?.getOrNull()
        target?.delete()
        if (bytes == null || bytes.isEmpty()) return null
        return RecordedClip(bytes, elapsed.coerceAtMost(MAX_DURATION_MS))
    }

    /** A swipe to the left: the microphone closes and nothing is kept. */
    override fun cancel() {
        val media = recorder ?: return
        recorder = null
        runCatching { media.stop() }
        runCatching { media.release() }
        file?.delete()
        file = null
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val BIT_RATE = 24_000
        const val MAX_DURATION_MS = 30_000
        const val MIN_DURATION_MS = 400

        /** `getMaxAmplitude` is a 16-bit sample, so this is as loud as the number goes. */
        const val MAX_AMPLITUDE = 32_767f
    }
}
