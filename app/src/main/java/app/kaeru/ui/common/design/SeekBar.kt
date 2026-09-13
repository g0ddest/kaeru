package app.kaeru.ui.common.design

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText
import app.kaeru.ui.common.theme.KaeruTheme

private val ThumbSize = 12.dp
private val ThumbSizeDragging = 16.dp

/**
 * A seek bar in the manner of the streaming players this app takes its cues from: one continuous
 * accent line for what has played, nothing where it meets the thumb. The stock Material slider
 * instead leaves a gap and a notch around its thumb and a dot at the far end, which reads as the
 * track tearing rather than one line draining.
 *
 * @param progress how far into the media the played position is, from 0f to 1f.
 * @param onScrub called continuously while a drag or tap is moving the thumb, with the new
 *   fraction; the caller should hold this locally and only act on it in [onScrubEnd].
 * @param onScrubEnd called once, when the drag or tap ends, to commit the seek.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KaeruSeekBar(
    progress: Float,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Slider(
        value = progress.coerceIn(0f, 1f),
        onValueChange = onScrub,
        modifier = modifier,
        enabled = enabled,
        onValueChangeFinished = onScrubEnd,
        interactionSource = interactionSource,
        thumb = {
            SeekThumb(interactionSource, color = if (enabled) KaeruAccent else KaeruSecondary)
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                modifier = Modifier.height(KaeruTokens.ProgressHeight),
                enabled = enabled,
                colors = SliderDefaults.colors(
                    activeTrackColor = KaeruAccent,
                    inactiveTrackColor = KaeruText.copy(alpha = 0.24f),
                    disabledActiveTrackColor = KaeruSecondary,
                    disabledInactiveTrackColor = KaeruText.copy(alpha = 0.16f),
                ),
                drawStopIndicator = null,
                thumbTrackGapSize = 0.dp,
                trackInsideCornerSize = 0.dp,
            )
        },
        valueRange = 0f..1f,
    )
}

/** A plain dot, slightly larger while a finger is actually on it. No M3 pill-squish. */
@Composable
private fun SeekThumb(interactionSource: MutableInteractionSource, color: Color) {
    val interactions = remember { mutableStateListOf<Interaction>() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> interactions.add(interaction)
                is PressInteraction.Release -> interactions.remove(interaction.press)
                is PressInteraction.Cancel -> interactions.remove(interaction.press)
                is DragInteraction.Start -> interactions.add(interaction)
                is DragInteraction.Stop -> interactions.remove(interaction.start)
                is DragInteraction.Cancel -> interactions.remove(interaction.start)
            }
        }
    }
    val size = if (interactions.isNotEmpty()) ThumbSizeDragging else ThumbSize
    Spacer(Modifier.size(size).background(color, CircleShape))
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0C10, widthDp = 320, heightDp = 160)
@Composable
private fun KaeruSeekBarPreview() = KaeruTheme {
    Column(
        Modifier.fillMaxWidth().padding(KaeruTokens.GutterPhone),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
    ) {
        KaeruSeekBar(progress = 0.08f, onScrub = {}, onScrubEnd = {})
        KaeruSeekBar(progress = 0.42f, onScrub = {}, onScrubEnd = {})
        KaeruSeekBar(progress = 0.93f, onScrub = {}, onScrubEnd = {})
        KaeruSeekBar(progress = 0.5f, onScrub = {}, onScrubEnd = {}, enabled = false)
    }
}
