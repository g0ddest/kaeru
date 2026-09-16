package app.kaeru.ui.mobile.together

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruError
import app.kaeru.ui.common.theme.KaeruTheme
import app.kaeru.ui.common.together.TogetherCopy
import app.kaeru.ui.common.together.VoiceCapture
import kotlinx.coroutines.delay

private val Disc = Color.Black.copy(alpha = 0.32f)
private val OnVideo = Color.White
private val OnVideoMuted = Color(0xFFD6D9DE)

/** How far sideways is «не отправляй», and how far up is «дальше без меня». */
private val CancelDistance = 72.dp
private val LockDistance = 64.dp

/** How often the bar is redrawn while somebody speaks. Twelve a second reads as a voice. */
private const val LEVEL_TICK_MS = 80L
private const val BARS = 14

/** When the counter turns amber: five seconds left to say the rest of it. */
private const val WARNING_MS = 25_000

/**
 * Hold to speak.
 *
 * The whole of the gesture is here because the three outcomes are three parts of one movement:
 * lift and it sends, swipe left and it is gone with nothing asked, swipe up and the finger is free
 * while the microphone stays open. Telegram taught everybody this and it is not worth teaching
 * them something else.
 *
 * Thirty seconds is the ceiling, and hitting it **sends** rather than discards. Throwing away
 * half a minute of somebody's speech because they did not watch a counter is the worst thing this
 * button could do, so it does the opposite.
 *
 * The red ring is not decoration. In landscape the status bar is hidden, which takes the system's
 * own microphone indicator off the screen, and this is then the only thing that says the
 * microphone is open.
 *
 * The permission is asked for here, at the first hold, rather than on the way into the session:
 * this is the moment a person can see what it is for.
 */
@Composable
fun VoiceButton(
    recorder: VoiceCapture,
    onClip: (ByteArray, Int) -> Unit,
    onDenied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var recording by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var elapsed by remember { mutableIntStateOf(0) }
    val levels = remember { mutableStateListOf<Float>() }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = allowed
        // Nothing starts recording on the way back: the finger that asked has long since lifted,
        // and audio a person did not know had started is the one thing a microphone must never do.
        if (!allowed) onDenied()
    }

    fun finish(send: Boolean) {
        val clip = if (send) recorder.stop() else null.also { recorder.cancel() }
        recording = false
        locked = false
        cancelling = false
        elapsed = 0
        levels.clear()
        clip?.let { onClip(it.bytes, it.durationMs) }
    }

    // The microphone is closed with the screen, whatever the finger was doing at the time.
    DisposableEffect(recorder) { onDispose { recorder.cancel() } }

    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        while (true) {
            delay(LEVEL_TICK_MS)
            if (!recorder.recording) break
            levels += recorder.level()
            if (levels.size > BARS) levels.removeAt(0)
            elapsed = recorder.elapsedMs()
            if (elapsed >= recorder.maxDurationMs) {
                finish(send = true)
                break
            }
        }
    }

    Column(modifier, horizontalAlignment = Alignment.Start) {
        if (recording) {
            RecordingBar(
                levels = levels,
                elapsedMs = elapsed,
                maxMs = recorder.maxDurationMs,
                locked = locked,
                cancelling = cancelling,
                onSend = { finish(send = true) },
                onCancel = { finish(send = false) },
            )
            Spacer(Modifier.height(KaeruTokens.Space2))
        }
        Box(
            Modifier
                .size(KaeruTokens.MinTouchTarget)
                .clip(CircleShape)
                .background(Disc)
                .semantics { contentDescription = TogetherCopy.VOICE }
                .pointerInput(granted, locked) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (locked) {
                            // While locked the disc is «отправить», not «держать».
                            down.consume()
                            waitForUp(down.id)
                            finish(send = true)
                            return@awaitEachGesture
                        }
                        if (!granted) {
                            down.consume()
                            waitForUp(down.id)
                            ask.launch(Manifest.permission.RECORD_AUDIO)
                            return@awaitEachGesture
                        }
                        if (!recorder.start()) {
                            down.consume()
                            waitForUp(down.id)
                            onDenied()
                            return@awaitEachGesture
                        }
                        recording = true
                        val cancelPx = CancelDistance.toPx()
                        val lockPx = LockDistance.toPx()
                        var dx = 0f
                        var dy = 0f
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            dx = change.position.x - down.position.x
                            dy = change.position.y - down.position.y
                            cancelling = dx < -cancelPx
                            if (dy < -lockPx) {
                                locked = true
                                change.consume()
                                return@awaitEachGesture
                            }
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            change.consume()
                        }
                        finish(send = dx >= -cancelPx)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (recording) {
                CircularProgressIndicator(
                    progress = { elapsed.toFloat() / recorder.maxDurationMs },
                    color = KaeruError,
                    trackColor = Color.Transparent,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(KaeruTokens.MinTouchTarget),
                )
            }
            Icon(
                Icons.Default.Mic,
                contentDescription = null,
                tint = if (cancelling) KaeruError else OnVideo,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** What is being recorded, while it is being recorded: the level, the clock and the two ways out. */
@Composable
private fun RecordingBar(
    levels: List<Float>,
    elapsedMs: Int,
    maxMs: Int,
    locked: Boolean,
    cancelling: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(KaeruTokens.RadiusChip))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = KaeruTokens.Space3, vertical = KaeruTokens.Space2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
    ) {
        Text(
            if (locked) TogetherCopy.VOICE_CANCEL else if (cancelling) TogetherCopy.VOICE_CANCEL else TogetherCopy.VOICE_LOCK,
            style = MaterialTheme.typography.labelMedium,
            color = if (cancelling) KaeruError else OnVideoMuted,
        )
        Waveform(levels)
        Text(
            "${TogetherCopy.clipLength(elapsedMs)} / ${TogetherCopy.clipLength(maxMs)}",
            style = MaterialTheme.typography.labelMedium,
            color = if (elapsedMs >= WARNING_MS) KaeruAccent else OnVideo,
        )
        if (locked) {
            TextButton(onClick = onSend) { Text(TogetherCopy.SEND, color = OnVideo) }
            TextButton(onClick = onCancel) { Text("Отмена", color = OnVideoMuted) }
        }
    }
}

/** Fourteen bars of however loud it has been. Not a spectrum; a sign of life. */
@Composable
private fun Waveform(levels: List<Float>) {
    Row(
        Modifier.width(96.dp).height(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(BARS) { index ->
            val level = levels.getOrNull(index) ?: 0f
            Box(
                Modifier
                    .width(4.dp)
                    .height((3f + level * 17f).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(OnVideo.copy(alpha = 0.32f + level * 0.68f)),
            )
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.waitForUp(
    id: androidx.compose.ui.input.pointer.PointerId,
) {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: return
        if (!change.pressed) return
        change.consume()
    }
}

@Preview(name = "Запись", showBackground = true, backgroundColor = 0xFF0B0C10, widthDp = 360, heightDp = 140)
@Composable
private fun RecordingBarPreview() = KaeruTheme {
    Column(Modifier.padding(KaeruTokens.Space4)) {
        RecordingBar(
            levels = listOf(0.1f, 0.3f, 0.6f, 0.9f, 0.6f, 0.3f, 0.1f, 0.2f, 0.5f, 0.8f, 0.4f, 0.2f, 0.1f, 0.3f),
            elapsedMs = 7_400,
            maxMs = 30_000,
            locked = false,
            cancelling = false,
            onSend = {},
            onCancel = {},
        )
    }
}
