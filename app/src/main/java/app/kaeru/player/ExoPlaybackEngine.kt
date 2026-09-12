package app.kaeru.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
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
 * The real engine: one [ExoPlayer] for the whole process, shared by the player screen and
 * the media session so a rotation, a trip to the home screen or the notification never
 * restarts the video.
 *
 * The player is pinned to the main looper, so every method here has to be called from the
 * main thread — which is where the controller's scope and every UI callback already run.
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

    private val player: ExoPlayer = ExoPlayer.Builder(context)
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

    override val videoPlayer: Player get() = player

    private var poll: Job? = null

    override fun prepare(url: String, headers: StreamHeaders, startPositionMs: Long) {
        player.setMediaSource(mediaSource(url, headers))
        player.seekTo(startPositionMs)
        player.prepare()
        // Stated rather than read back: until the manifest is parsed the player reports
        // neither a length nor an error, and the previous episode's numbers must not linger.
        _state.value = EngineState(isBuffering = true, positionMs = startPositionMs)
        startPolling()
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        push()
    }

    override fun release() {
        stopPolling()
        player.stop()
        player.clearMediaItems()
        _state.value = EngineState()
    }

    /** Gives the decoder back for good. Only the service that owns this process calls it. */
    fun shutdown() {
        stopPolling()
        player.removeListener(listener)
        player.release()
        _state.value = EngineState()
    }

    /**
     * Kodik hands out one signed manifest per height, so there is no adaptive master playlist
     * to switch inside: a quality change is a new source at the same position. Both the manifest
     * and every segment have to carry the browser headers, hence the shared data source factory.
     */
    private fun mediaSource(url: String, headers: StreamHeaders): MediaSource {
        val http: DataSource.Factory = DefaultHttpDataSource.Factory()
            .setUserAgent(headers.userAgent)
            .setDefaultRequestProperties(headers.requestProperties)
            .setAllowCrossProtocolRedirects(true)
        val item = MediaItem.fromUri(url)
        val uri = item.localConfiguration?.uri
        return if (uri != null && Util.inferContentType(uri) == C.CONTENT_TYPE_HLS) {
            HlsMediaSource.Factory(http).createMediaSource(item)
        } else {
            DefaultMediaSourceFactory(http).createMediaSource(item)
        }
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
