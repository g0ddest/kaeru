package app.kaeru.data.together

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import app.kaeru.ui.common.together.VoicePlayback
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A clip through the speaker, once, straight away.
 *
 * `MediaPlayer` rather than a second ExoPlayer: this is one short file with no track selection, no
 * buffering policy and no session, and a media3 player would bring all three plus a notification
 * the app does not want for a two-second remark.
 *
 * Focus is asked for as `TRANSIENT_MAY_DUCK`, which is what quietens music and podcasts in other
 * apps while somebody speaks. It will not quieten this app's own video — the system does not duck
 * a process against itself — so the episode is turned down by the screen that owns the player,
 * which is deterministic and needs no version checks. Both are wanted: one is about the phone, the
 * other about the picture.
 */
@Singleton
class VoicePlayer @Inject constructor(@ApplicationContext context: Context) : VoicePlayback {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cache = context.cacheDir
    private var player: MediaPlayer? = null
    private var file: File? = null
    private var focus: AudioFocusRequest? = null

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /**
     * Plays [bytes], calling [onFinished] when the clip is over however it got there.
     *
     * Anything already playing is stopped first: two clips at once is two people talking over each
     * other with no way to follow either.
     */
    override fun play(id: Long, bytes: ByteArray, onFinished: () -> Unit) {
        stop()
        val target = runCatching {
            File(cache, "clip-$id.audio").apply { writeBytes(bytes) }
        }.getOrNull() ?: return onFinished()
        val media = MediaPlayer()
        val done = {
            release()
            onFinished()
        }
        runCatching {
            media.setAudioAttributes(attributes)
            media.setDataSource(target.absolutePath)
            media.setOnCompletionListener { done() }
            media.setOnErrorListener { _, _, _ ->
                done()
                true
            }
            media.prepare()
            requestFocus()
            media.start()
            player = media
            file = target
        }.onFailure {
            runCatching { media.release() }
            target.delete()
            onFinished()
        }
    }

    override fun stop() {
        val media = player ?: return
        runCatching { media.stop() }
        release()
    }

    private fun release() {
        player?.let { runCatching { it.release() } }
        player = null
        file?.delete()
        file = null
        abandonFocus()
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .build()
            focus = request
            audio.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focus?.let { audio.abandonAudioFocusRequest(it) }
            focus = null
        } else {
            @Suppress("DEPRECATION")
            audio.abandonAudioFocus(null)
        }
    }
}
