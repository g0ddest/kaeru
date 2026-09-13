package app.kaeru.player

import androidx.media3.cast.CastPlayer
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import app.kaeru.domain.error.CastLoadFailed
import app.kaeru.domain.error.SourceUnavailable
import app.kaeru.domain.error.SourceUnavailableReason
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The engine that plays on a Chromecast: the same seam as the local one, over media3's
 * [CastPlayer] instead of an ExoPlayer.
 *
 * Two things make it different from [ExoPlaybackEngine], and both are why casting works at all:
 *
 * - **Nothing is decoded here.** [videoPlayer] is always null, so the player screen has no
 *   surface to attach and shows the remote control instead.
 * - **The headers are not ours to send.** The receiver fetches the manifest and every segment
 *   itself, from its own IP, with its own user agent. Kodik's CDN answers those requests with
 *   `Access-Control-Allow-Origin: *` and asks for no `Referer`, which is the one fact this
 *   whole feature rests on; the signed link is still short-lived and looks IP-bound, so it is
 *   resolved on the phone moments before it is handed over, on the same network.
 *
 * Pinned to the main thread, like everything else Media3.
 */
@UnstableApi
class CastPlaybackEngine(
    castContext: CastContext,
    private val scope: CoroutineScope,
) : PlaybackEngine {

    /**
     * Deprecated in media3 1.11 in favour of `CastPlayer.Builder`, which builds a player that
     * wraps a local one and transfers between them by itself. That is the same job this
     * controller's engine switch already does, one layer lower and without the progress
     * flush — so the plain remote player is what is wanted here.
     */
    @Suppress("DEPRECATION")
    private val player = CastPlayer(castContext)

    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    /** There is no picture on this phone while a receiver has it. */
    override val videoPlayer: StateFlow<Player?> = MutableStateFlow(null)

    private var poll: Job? = null
    private var loadWatchdog: Job? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = push()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startPolling() else stopPolling()
            push()
        }

        override fun onPlayerError(error: PlaybackException) = push(translate(error))
    }

    init {
        player.addListener(listener)
    }

    override fun prepare(url: String, headers: StreamHeaders, startPositionMs: Long, metadata: StreamMetadata?) {
        player.setMediaItem(MediaItemFactory.castMediaItem(url, metadata), startPositionMs)
        player.prepare()
        // Stated rather than read back: the receiver takes seconds to answer, and until it does
        // the previous episode's numbers must not linger on the remote control.
        _state.value = EngineState(isBuffering = true, positionMs = startPositionMs)
        startPolling()
        armLoadWatchdog()
    }

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        push()
    }

    /**
     * Stops what the receiver is playing and lets go of the episode, which is what ends a cast
     * of ours without ending the session. The [CastPlayer] itself stays: it is the process's one
     * connection to the framework, and the next episode reuses it.
     */
    override fun release() {
        stopPolling()
        loadWatchdog?.cancel()
        loadWatchdog = null
        player.stop()
        player.clearMediaItems()
        _state.value = EngineState()
    }

    /**
     * Gives the [CastPlayer] itself back, not just the episode: the session is over and the
     * connection behind it is gone. Done by [PlayServicesCastFramework] when a session ends;
     * the next one builds another engine.
     */
    fun shutdown() {
        release()
        player.removeListener(listener)
        player.release()
    }

    /**
     * A receiver that refuses a stream usually says so. One that simply never starts — no
     * error, no first frame — would otherwise leave a spinner up for as long as the viewer
     * is willing to watch it, so silence gets a deadline and a name of its own.
     */
    private fun armLoadWatchdog() {
        loadWatchdog?.cancel()
        loadWatchdog = scope.launch {
            delay(LOAD_TIMEOUT_MS)
            if (player.playbackState != Player.STATE_READY) push(CastLoadFailed())
        }
    }

    /** A receiver reports its position on request, not on its own; twice a second is enough. */
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
        val playbackState = player.playbackState
        // Anything that is playing or has played is proof the receiver took the stream.
        if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
            loadWatchdog?.cancel()
            loadWatchdog = null
        }
        _state.value = EngineState(
            isPlaying = player.isPlaying,
            isBuffering = playbackState == Player.STATE_BUFFERING,
            ended = playbackState == Player.STATE_ENDED,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = if (duration == C.TIME_UNSET || duration < 0) 0 else duration,
            error = error,
        )
    }

    /**
     * Everything a receiver can refuse looks the same from here: it could not load what we gave
     * it. The likeliest reason by far is an expired signature, so this is reported as the source
     * turning us away — which is what buys the controller its one silent re-resolve before
     * anyone is told anything.
     */
    private fun translate(error: PlaybackException): Throwable =
        SourceUnavailable(SourceUnavailableReason.REJECTED, error)

    private companion object {
        const val POLL_INTERVAL_MS = 500L

        /** How long a receiver may say nothing at all before that counts as a refusal. */
        const val LOAD_TIMEOUT_MS = 20_000L
    }
}
