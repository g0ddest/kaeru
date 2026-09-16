package app.kaeru.ui.mobile.together

import android.graphics.Color as AndroidColor
import android.text.InputFilter
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.kaeru.domain.together.ReactionKind
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruSurface
import app.kaeru.ui.common.theme.KaeruText
import app.kaeru.ui.common.theme.KaeruTheme
import app.kaeru.ui.common.together.ConversationItem
import app.kaeru.ui.common.together.NoVoiceCapture
import app.kaeru.ui.common.together.NoticeLine
import app.kaeru.ui.common.together.TogetherCopy
import app.kaeru.ui.common.together.TogetherPhase
import app.kaeru.ui.common.together.TogetherUiState
import app.kaeru.ui.common.together.VoiceCapture
import app.kaeru.ui.common.together.VoiceClipItem
import app.kaeru.ui.common.together.WaitExit
import app.kaeru.ui.common.together.WaitLine
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The app's own secondary text colour, as a platform int for the one platform view here. */
private const val HINT_COLOR = 0xFF9AA0AA.toInt()

private val Disc = Color.Black.copy(alpha = 0.32f)
private val Slate = Color.Black.copy(alpha = 0.62f)
private val OnVideo = Color.White
private val OnVideoMuted = Color(0xFFD6D9DE)

/** How wide the conversation is allowed to get before it starts covering the picture. */
private val ColumnWidth = 360.dp

/** Where the bottom of the conversation sits with the controls up, clear of the timeline. */
private val AboveControls = 198.dp
private val AboveNothing = 24.dp

/** The row of six closes itself when nobody picks one. */
private const val PICKER_LINGER_MS = 4_000L

private val FADE = tween<Float>(300)

/**
 * People talking over a video.
 *
 * Two places, and the split is the whole idea. What the machine did — a pause that came from the
 * other phone, a friend catching up — appears at the top, centred, for three seconds. What a
 * person said appears at the bottom left, stacked, newest underneath. If both spoke from the same
 * corner, «Вася поставил на паузу» would read as something Вася typed.
 *
 * The conversation is tied to the bottom of whatever is free rather than to the bottom of the
 * screen: when the controls come up the whole column slides above the timeline in one movement, so
 * the two round buttons stay where the thumb left them relative to what is on screen. It also
 * shrinks to a single line there — somebody reaching for the timeline is not reading the chat.
 *
 * Nothing here is written down. Tapping the stack opens the session's own history, which exists
 * for exactly as long as the session does; that tap is also what makes the seven-second fade
 * acceptable under WCAG 2.2.1, because it is the other way to the same information.
 */
@Composable
fun TogetherOverlay(
    state: TogetherUiState,
    controlsVisible: Boolean,
    onSendChat: (String) -> Unit,
    onReaction: (ReactionKind) -> Unit,
    onVoice: (ByteArray, Int) -> Unit,
    onMicDenied: () -> Unit,
    onOpenHistory: () -> Unit,
    onCloseHistory: () -> Unit,
    onReplay: (Long) -> Unit,
    onLeaveWait: () -> Unit,
    modifier: Modifier = Modifier,
    recorder: VoiceCapture = NoVoiceCapture,
) {
    Box(modifier.fillMaxSize()) {
        ReactionBurst(state.reactions)

        Column(
            Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
        ) {
            state.notice?.let { Notice(it) }
            state.wait?.let { Wait(it, onLeaveWait) }
        }

        if (state.live) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .safeDrawingPadding()
                    .padding(start = KaeruTokens.Space4, bottom = if (controlsVisible) AboveControls else AboveNothing)
                    .widthIn(max = ColumnWidth),
                verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
            ) {
                Stack(
                    items = if (controlsVisible) state.stack.takeLast(1) else state.stack,
                    onOpen = onOpenHistory,
                    onReplay = onReplay,
                )
                Controls(
                    recorder = recorder,
                    onSendChat = onSendChat,
                    onReaction = onReaction,
                    onVoice = onVoice,
                    onMicDenied = onMicDenied,
                )
            }
        }
    }

    if (state.historyOpen) HistorySheet(state.history, onReplay, onCloseHistory)
}

/** One line at the top about what the other phone did. */
@Composable
private fun Notice(notice: NoticeLine) {
    Text(
        notice.text,
        style = MaterialTheme.typography.labelMedium,
        color = OnVideo,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(KaeruTokens.ChipShape)
            .background(Slate)
            .padding(horizontal = KaeruTokens.Space3, vertical = KaeruTokens.Space2)
            // Read out as it arrives, without taking focus off whatever the viewer was on.
            .semantics { liveRegion = LiveRegionMode.Assertive },
    )
}

/**
 * A wait, and the button that ends it.
 *
 * There is no arrangement of this component without the button: [WaitLine] does not exist without
 * an exit, which is the one rule this whole feature is built to keep.
 */
@Composable
private fun Wait(wait: WaitLine, onExit: () -> Unit) {
    Row(
        Modifier
            .clip(KaeruTokens.ChipShape)
            .background(Slate)
            .padding(start = KaeruTokens.Space3, end = KaeruTokens.Space1)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(wait.text, style = MaterialTheme.typography.labelMedium, color = OnVideo, maxLines = 1)
        TextButton(onClick = onExit) {
            Text(TogetherCopy.exitLabel(wait.exit), color = KaeruAccent, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** The corner: at most three things, newest at the bottom, each fading out on its own clock. */
@Composable
private fun Stack(items: List<ConversationItem>, onOpen: () -> Unit, onReplay: (Long) -> Unit) {
    if (items.isEmpty()) return
    Column(
        Modifier
            .clickable(onClick = onOpen, onClickLabel = TogetherCopy.HISTORY)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items.forEach { item ->
            AnimatedVisibility(visible = true, enter = fadeIn(FADE), exit = fadeOut(FADE)) {
                Bubble(item, onReplay)
            }
        }
    }
}

/** One thing somebody said: a line, or a clip with the length of it and a way to hear it again. */
@Composable
private fun Bubble(item: ConversationItem, onReplay: (Long) -> Unit) {
    Row(
        Modifier
            .clip(KaeruTokens.ChipShape)
            .background(Slate)
            .padding(horizontal = KaeruTokens.Space3, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
    ) {
        Text(
            item.author,
            style = MaterialTheme.typography.labelMedium,
            color = if (item.mine) OnVideoMuted else KaeruAccent,
            maxLines = 1,
        )
        if (item.clip != null) {
            Clip(item.clip, onReplay = { onReplay(item.id) })
        } else {
            Text(
                item.text.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = OnVideo,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Clip(clip: VoiceClipItem, onReplay: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space1)) {
        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = OnVideo, modifier = Modifier.size(16.dp))
        Text(TogetherCopy.clipLength(clip.durationMs), style = MaterialTheme.typography.labelMedium, color = OnVideo)
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable(onClick = onReplay, onClickLabel = TogetherCopy.REPLAY),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Replay, contentDescription = null, tint = OnVideoMuted, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * Everything a person can say, in one row above the timeline.
 *
 * One row and not three: in landscape there are about 147 free points under the picture, and a
 * column of presets over a field over two buttons is all of it. The keyboard is the last resort
 * rather than the default — a tap on 😀 or on a preset says something without one, which is the
 * whole reason those are in front of the field rather than behind it.
 */
@Composable
private fun Controls(
    recorder: VoiceCapture,
    onSendChat: (String) -> Unit,
    onReaction: (ReactionKind) -> Unit,
    onVoice: (ByteArray, Int) -> Unit,
    onMicDenied: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    var composing by remember { mutableStateOf(false) }
    LaunchedEffect(picking) {
        if (!picking) return@LaunchedEffect
        delay(PICKER_LINGER_MS)
        picking = false
    }
    if (composing) {
        ChatInput(
            onSend = {
                onSendChat(it)
                composing = false
            },
            onDismiss = { composing = false },
        )
        return
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
    ) {
        Box(
            Modifier
                .size(KaeruTokens.MinTouchTarget)
                .clip(CircleShape)
                .background(Disc)
                .clickable(onClick = { picking = !picking }, onClickLabel = TogetherCopy.REACTIONS),
            contentAlignment = Alignment.Center,
        ) {
            Text("😀", style = MaterialTheme.typography.titleMedium)
        }
        VoiceButton(recorder = recorder, onClip = onVoice, onDenied = onMicDenied)
        if (picking) {
            ReactionPicker(
                onPick = {
                    onReaction(it)
                    picking = false
                },
            )
        } else {
            TogetherCopy.PRESETS.forEach { preset ->
                Pill(preset) { onSendChat(preset) }
            }
            Pill(TogetherCopy.WRITE_PLACEHOLDER, muted = true) { composing = true }
        }
    }
}

@Composable
private fun Pill(text: String, muted: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (muted) OnVideoMuted else OnVideo,
        maxLines = 1,
        modifier = Modifier
            .heightIn(min = KaeruTokens.MinTouchTarget)
            .clip(KaeruTokens.ChipShape)
            .background(Disc)
            .clickable(onClick = onClick)
            .padding(horizontal = KaeruTokens.Space4, vertical = 14.dp),
    )
}

/**
 * The keyboard, on the two occasions somebody wants one.
 *
 * A platform `EditText` rather than a Compose field, for one reason: in landscape the IME goes
 * full screen and covers the video, the chat and the person's own words, and the only thing that
 * suppresses it is `IME_FLAG_NO_FULLSCREEN` on the editor — which Compose's text field does not
 * hand out. The 200-character ceiling is the protocol's, applied at the keyboard so the limit is
 * something a person runs into rather than something that silently truncates what they wrote.
 */
@Composable
private fun ChatInput(onSend: (String) -> Unit, onDismiss: () -> Unit) {
    val focus = LocalFocusManager.current
    Row(
        Modifier.width(ColumnWidth),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
    ) {
        AndroidView(
            factory = { context ->
                EditText(context).apply {
                    hint = TogetherCopy.WRITE_PLACEHOLDER
                    setHintTextColor(HINT_COLOR)
                    setTextColor(AndroidColor.WHITE)
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    textSize = 15f
                    isSingleLine = true
                    filters = arrayOf(InputFilter.LengthFilter(TogetherCopy.MAX_CHARS))
                    imeOptions = EditorInfo.IME_ACTION_SEND or
                        EditorInfo.IME_FLAG_NO_FULLSCREEN or
                        EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    setOnEditorActionListener { view, action, _ ->
                        if (action != EditorInfo.IME_ACTION_SEND) return@setOnEditorActionListener false
                        onSend(view.text.toString())
                        view.text = null
                        true
                    }
                    requestFocus()
                }
            },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = KaeruTokens.MinTouchTarget)
                .clip(KaeruTokens.ChipShape)
                .background(Slate)
                .padding(horizontal = KaeruTokens.Space3),
        )
        TextButton(
            onClick = {
                focus.clearFocus()
                onDismiss()
            },
        ) {
            Text("Закрыть", color = OnVideoMuted, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * Everything said this session, which is the other way to anything the corner has taken away.
 *
 * In memory and nowhere else: this is a conversation about one episode, and an app that kept it
 * would have to grow a screen for reading old ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(items: List<ConversationItem>, onReplay: (Long) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = KaeruSurface,
    ) {
        HistoryContent(items, onReplay)
    }
}

@Composable
private fun HistoryContent(items: List<ConversationItem>, onReplay: (Long) -> Unit) {
    if (items.isEmpty()) {
        Text(
            TogetherCopy.EMPTY_HISTORY,
            style = MaterialTheme.typography.bodyMedium,
            color = KaeruSecondary,
            modifier = Modifier.fillMaxWidth().padding(KaeruTokens.Space6),
        )
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space4),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
    ) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.author,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (item.mine) KaeruSecondary else KaeruAccent,
                    modifier = Modifier.width(72.dp),
                )
                Box(Modifier.weight(1f)) {
                    if (item.clip != null) {
                        Clip(item.clip, onReplay = { onReplay(item.id) })
                    } else {
                        Text(item.text.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = KaeruText)
                    }
                }
                Text(
                    clockOf(item.at),
                    style = MaterialTheme.typography.labelMedium,
                    color = KaeruSecondary,
                    modifier = Modifier.padding(start = KaeruTokens.Space2),
                )
            }
        }
    }
}

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

private fun clockOf(at: Long): String =
    if (at <= 0) "" else timeFormat.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

// --- previews -------------------------------------------------------------------------------------

private val sample = listOf(
    ConversationItem(1, mine = false, author = "Вася", text = "это же тот самый кадр", at = 1_758_000_000_000),
    ConversationItem(2, mine = true, author = "Вы", text = "ага", at = 1_758_000_001_000),
    ConversationItem(3, mine = false, author = "Вася", clip = VoiceClipItem(ByteArray(0), 7_400), at = 1_758_000_002_000),
)

@Preview(name = "Оверлей", showBackground = true, backgroundColor = 0xFF0B0C10, widthDp = 720, heightDp = 360)
@Composable
private fun OverlayPreview() = KaeruTheme {
    TogetherOverlay(
        state = TogetherUiState(
            phase = TogetherPhase.LIVE,
            peerName = "Вася",
            notice = NoticeLine(1, "Вася поставил(а) на паузу"),
            stack = sample,
        ),
        controlsVisible = false,
        onSendChat = {},
        onReaction = {},
        onVoice = { _, _ -> },
        onMicDenied = {},
        onOpenHistory = {},
        onCloseHistory = {},
        onReplay = {},
        onLeaveWait = {},
    )
}

@Preview(name = "Ждём друга", showBackground = true, backgroundColor = 0xFF0B0C10, widthDp = 720, heightDp = 360)
@Composable
private fun OverlayWaitPreview() = KaeruTheme {
    TogetherOverlay(
        state = TogetherUiState(
            phase = TogetherPhase.HOSTING,
            wait = WaitLine(TogetherCopy.WAITING_FRIEND, WaitExit.KEEP_WATCHING),
        ),
        controlsVisible = false,
        onSendChat = {},
        onReaction = {},
        onVoice = { _, _ -> },
        onMicDenied = {},
        onOpenHistory = {},
        onCloseHistory = {},
        onReplay = {},
        onLeaveWait = {},
    )
}

@Preview(name = "Связь потеряна", showBackground = true, backgroundColor = 0xFF0B0C10, widthDp = 720, heightDp = 360)
@Composable
private fun OverlayLostPreview() = KaeruTheme {
    TogetherOverlay(
        state = TogetherUiState(
            phase = TogetherPhase.LOST,
            wait = WaitLine("Связь с другом потеряна", WaitExit.WATCH_ALONE),
        ),
        controlsVisible = true,
        onSendChat = {},
        onReaction = {},
        onVoice = { _, _ -> },
        onMicDenied = {},
        onOpenHistory = {},
        onCloseHistory = {},
        onReplay = {},
        onLeaveWait = {},
    )
}

@Preview(name = "Переписка", showBackground = true, backgroundColor = 0xFF15171E, widthDp = 400, heightDp = 260)
@Composable
private fun HistoryPreview() = KaeruTheme {
    Column(Modifier.background(KaeruSurface)) { HistoryContent(sample, onReplay = {}) }
}
