package app.kaeru.ui.mobile.together

import androidx.compose.runtime.Immutable
import app.kaeru.domain.together.ReactionKind
import app.kaeru.domain.together.NoVoiceCapture
import app.kaeru.ui.common.together.TogetherUiState
import app.kaeru.domain.together.VoiceCapture
import app.kaeru.domain.together.VoicePlayback

/**
 * Everything the player screen needs in order to carry a shared viewing.
 *
 * One parameter instead of fifteen. The player's own signature is already long, and this is one
 * feature that either is switched on or is not — bundling it keeps «watch together» a single thing
 * the screen is handed rather than a dozen lambdas interleaved with the ones about playback.
 *
 * Every field has a default that does nothing, so a preview, a test and a build of the screen
 * without a session all draw the same player with no overlay on it.
 */
@Immutable
data class TogetherControls(
    val state: TogetherUiState = TogetherUiState(),
    /** The microphone, or one that is not there. */
    val recorder: VoiceCapture = NoVoiceCapture,
    /** The speaker for arriving clips, or nothing to play them through. */
    val player: VoicePlayback? = null,
    val onShare: () -> Unit = {},
    val onLeave: () -> Unit = {},
    val onShareShown: () -> Unit = {},
    val onSendChat: (String) -> Unit = {},
    val onReaction: (ReactionKind) -> Unit = {},
    val onVoice: (ByteArray, Int) -> Unit = { _, _ -> },
    val onMicDenied: () -> Unit = {},
    val onOpenHistory: () -> Unit = {},
    val onCloseHistory: () -> Unit = {},
    val onReplay: (Long) -> Unit = {},
    val onClipPlayed: () -> Unit = {},
    val onLeaveWait: () -> Unit = {},
    val onMessageShown: () -> Unit = {},
    /** This screen has opened. Clears a receipt left behind by a session that ended elsewhere. */
    val onPlayerAttached: () -> Unit = {},
    /** Whether the corner may empty itself on a timer; false while a screen reader is running. */
    val onAutoHide: (Boolean) -> Unit = {},
    /**
     * Whether something of the system's is up over the player, taking the viewer out of the app
     * mid-decision: the share chooser, the microphone permission. Whoever owns the window has to
     * know, because neither is an activity of this app's and nothing else here can tell.
     *
     * False is as important as true: a question that is answered without ever being drawn — an
     * already-granted permission — never pauses the activity, so nothing else would take it down.
     */
    val onSystemPrompt: (Boolean) -> Unit = {},
    /** Whether the player offers to start one at all. Off on a build with no session behind it. */
    val enabled: Boolean = false,
)
