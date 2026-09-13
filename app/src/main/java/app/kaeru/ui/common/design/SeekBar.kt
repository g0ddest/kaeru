package app.kaeru.ui.common.design

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText
import app.kaeru.ui.common.theme.KaeruTheme

/** Resting and pressed diameters. Neither changes the layout: both are drawn, not laid out. */
private val ThumbSize = 14.dp
private val ThumbSizeDragging = 18.dp

/**
 * Where a touch at [x] falls on a track that runs from [inset] to `width - inset`, as a fraction
 * of the episode.
 *
 * The component is wider than its track on purpose — the thumb overhangs both ends — so the
 * fraction is measured against the track, and the overhang at either side reads as the beginning
 * or the end rather than as a dead strip.
 */
fun seekFraction(x: Float, width: Float, inset: Float): Float {
    val span = width - inset * 2f
    if (span <= 0f) return 0f
    return ((x - inset) / span).coerceIn(0f, 1f)
}

/**
 * The timeline, drawn rather than assembled.
 *
 * Material's slider builds its track out of laid-out pieces and leaves a notch around the thumb
 * and a dot at the far end, which is what made the bar read as broken on a phone: the accent line
 * tears where the thumb sits instead of draining into it. Here the whole bar — track, the part the
 * player has buffered, the part that has played, and the thumb — is one `drawBehind` pass, so the
 * line is continuous and the thumb growing under a finger cannot move anything.
 *
 * The track is inset by [KaeruTokens.SeekInset] at both ends, which is the same inset the row of
 * timecodes above it uses, so the first timecode sits over the start of the track rather than over
 * the overhang.
 *
 * @param progress how far into the media the played position is, from 0f to 1f.
 * @param onScrub called continuously while a press or a drag is moving the thumb, with the new
 *   fraction; the caller should hold this locally and only act on it in [onScrubEnd].
 * @param onScrubEnd called once, when the press or drag ends, to commit the seek.
 * @param buffered how much of the media is downloaded and ready to play, from 0f to 1f. Drawn as a
 *   paler head on the track, so a stall is visibly the network's doing rather than the app's.
 */
@Composable
fun KaeruSeekBar(
    progress: Float,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    buffered: Float = 0f,
) {
    var dragging by remember { mutableStateOf(false) }
    val played = progress.coerceIn(0f, 1f)
    val ready = buffered.coerceIn(0f, 1f)

    val trackColour = KaeruText.copy(alpha = if (enabled) 0.24f else 0.16f)
    val readyColour = KaeruText.copy(alpha = if (enabled) 0.44f else 0.24f)
    val playedColour = if (enabled) KaeruAccent else KaeruSecondary

    val thumbDiameter by animateDpAsState(
        targetValue = if (dragging) ThumbSizeDragging else ThumbSize,
        animationSpec = tween(KaeruTokens.DurationFast),
        label = "seekThumb",
    )
    val density = LocalDensity.current
    val insetPx = with(density) { KaeruTokens.SeekInset.toPx() }
    val trackPx = with(density) { KaeruTokens.ProgressHeight.toPx() }
    val thumbPx = with(density) { thumbDiameter.toPx() / 2f }

    Spacer(
        modifier
            .fillMaxWidth()
            .height(KaeruTokens.SeekBarHeight)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(played, 0f..1f)
                if (enabled) {
                    setProgress { target ->
                        onScrub(target.coerceIn(0f, 1f))
                        onScrubEnd()
                        true
                    }
                } else {
                    disabled()
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    // Unconsumed is not required: the controls above the video own a tap of their
                    // own, and this gesture takes precedence over it inside the bar.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val width = size.width.toFloat()
                    dragging = true
                    // A press seeks at once rather than on release: the thumb has to be under the
                    // finger before it can be dragged anywhere.
                    onScrub(seekFraction(down.position.x, width, insetPx))
                    var pressed = true
                    while (pressed) {
                        val pointer = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                        if (pointer == null) {
                            pressed = false
                        } else {
                            onScrub(seekFraction(pointer.position.x, width, insetPx))
                            pointer.consume()
                            pressed = pointer.pressed
                        }
                    }
                    dragging = false
                    onScrubEnd()
                }
            }
            .drawBehind {
                val middle = size.height / 2f
                val left = insetPx
                val span = (size.width - insetPx * 2f).coerceAtLeast(0f)
                drawLine(
                    color = trackColour,
                    start = Offset(left, middle),
                    end = Offset(left + span, middle),
                    strokeWidth = trackPx,
                    cap = StrokeCap.Round,
                )
                if (ready > 0f) {
                    drawLine(
                        color = readyColour,
                        start = Offset(left, middle),
                        end = Offset(left + span * ready, middle),
                        strokeWidth = trackPx,
                        cap = StrokeCap.Round,
                    )
                }
                if (played > 0f) {
                    drawLine(
                        color = playedColour,
                        start = Offset(left, middle),
                        end = Offset(left + span * played, middle),
                        strokeWidth = trackPx,
                        cap = StrokeCap.Round,
                    )
                }
                drawCircle(
                    color = playedColour,
                    radius = thumbPx,
                    center = Offset(left + span * played, middle),
                )
            },
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0C10, widthDp = 360, heightDp = 240)
@Composable
private fun KaeruSeekBarPreview() = KaeruTheme {
    Column(
        Modifier.fillMaxWidth().padding(KaeruTokens.GutterPhone),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
    ) {
        KaeruSeekBar(progress = 0f, buffered = 0.1f, onScrub = {}, onScrubEnd = {})
        KaeruSeekBar(progress = 0.42f, buffered = 0.61f, onScrub = {}, onScrubEnd = {})
        KaeruSeekBar(progress = 0.93f, buffered = 1f, onScrub = {}, onScrubEnd = {})
        KaeruSeekBar(progress = 0.5f, buffered = 0.5f, onScrub = {}, onScrubEnd = {}, enabled = false)
    }
}
