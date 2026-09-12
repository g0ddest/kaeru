package app.kaeru.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import app.kaeru.di.PlaybackScope
import app.kaeru.domain.error.NetworkUnavailable
import app.kaeru.domain.error.SourceUnavailable
import app.kaeru.domain.error.SourceUnavailableReason
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real engine: one [ExoPlayer] shared by the player screen and the media session, so a
 * rotation, a trip to the home screen or the notification never restarts the video.
 *
 * The player is built on first use and given back in [shutdownIfIdle] when the media service
 * that owns it goes away with nothing loaded, rather than holding a playback thread and an
 * audio-focus registration for the life of the process. Whoever needs it next builds another.
 *
 * It is pinned to the main looper, so every method here has to be called from the main thread —
 * which is where the controller's scope and every UI callback already run.
 */
@UnstableApi
@Singleton
class ExoPlaybackEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:PlaybackScope private val scope: CoroutineScope,
) : PlaybackEngine {

    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = push()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startPolling() else stopPolling()
            push()
        }

        override fun onPlayerError(error: PlaybackException) = push(translate(error))
    }

    private var instance: ExoPlayer? = null
    private val _videoPlayer = MutableStateFlow<Player?>(null)
    override val videoPlayer: StateFlow<Player?> = _videoPlayer.asStateFlow()

    private var poll: Job? = null

    /** The player, built if this is the first thing to ask for it. Main thread only. */
    fun acquirePlayer(): ExoPlayer = instance ?: build().also {
        instance = it
        _videoPlayer.value = it
    }

    private fun build(): ExoPlayer = ExoPlayer.Builder(context)
        // Set explicitly so construction is safe from whichever thread Hilt gets here first.
        .setLooper(Looper.getMainLooper())
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
        .apply { addListener(listener) }

    override fun prepare(url: String, headers: StreamHeaders, startPositionMs: Long, metadata: StreamMetadata?) {
        val player = acquirePlayer()
        player.setMediaSource(MediaItemFactory.mediaSource(MediaItemFactory.mediaItem(url, metadata), headers))
        player.seekTo(startPositionMs)
        player.prepare()
        // Stated rather than read back: until the manifest is parsed the player reports
        // neither a length nor an error, and the previous episode's numbers must not linger.
        _state.value = EngineState(isBuffering = true, positionMs = startPositionMs)
        startPolling()
    }

    override fun play() {
        instance?.play()
    }

    override fun pause() {
        instance?.pause()
    }

    override fun seekTo(positionMs: Long) {
        instance?.seekTo(positionMs)
        push()
    }

    override fun release() {
        stopPolling()
        instance?.stop()
        instance?.clearMediaItems()
        _state.value = EngineState()
    }

    /**
     * Gives the player itself back, not just its decoders, and only when nothing is loaded in
     * it — which is exactly what [release] leaves behind when the player screen finishes.
     * [KaeruPlaybackService] asks on its way out; the next [prepare] builds another player.
     *
     * A player that still holds an episode belongs to a screen, whether or not it is playing:
     * the service can be torn down while a paused video is on screen, and pulling the decoder
     * out from under it would leave a black rectangle nothing can start again.
     */
    fun shutdownIfIdle() {
        val player = instance ?: return
        if (player.mediaItemCount > 0) return
        stopPolling()
        player.removeListener(listener)
        player.release()
        instance = null
        _videoPlayer.value = null
        _state.value = EngineState()
    }

    /** Position is the one thing Media3 does not announce; four reads a second is smooth enough. */
    private fun startPolling() {
        if (poll?.isActive == true) return
        poll = scope.launch {
            while (isActive) {
                push()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        poll?.cancel()
        poll = null
    }

    private fun push(error: Throwable? = _state.value.error) {
        val player = instance ?: return
        val duration = player.duration
        _state.value = EngineState(
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            ended = player.playbackState == Player.STATE_ENDED,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = if (duration == C.TIME_UNSET || duration < 0) 0 else duration,
            error = error,
        )
    }

    /**
     * Media3 failures say what broke technically; the app speaks in terms of the source.
     * An expired signature comes back as a rejected request, which the controller answers by
     * resolving the episode again before anyone is told anything.
     */
    private fun translate(error: PlaybackException): Throwable = when (val cause = error.cause) {
        is HttpDataSource.InvalidResponseCodeException -> SourceUnavailable(SourceUnavailableReason.REJECTED, error)
        is IOException -> NetworkUnavailable(cause)
        else -> error
    }

    private companion object {
        const val POLL_INTERVAL_MS = 250L
    }
}
