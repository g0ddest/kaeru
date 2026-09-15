package app.kaeru.ui.common.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import app.kaeru.ui.common.theme.KaeruAccent

/** How much of the track the travelling segment covers. */
private const val SEGMENT = 0.35f

/** The distance the segment travels, as a multiple of its own width. */
private const val TRAVEL = 1f / SEGMENT - 1f

/** Slow enough to read as one thing crossing, fast enough that a second pass is never waited for. */
private const val TRAVEL_MS = 1400

/**
 * How far into an episode the viewer got, as a 4dp strip.
 *
 * It is the second and last place amber appears on a screen full of artwork, and it is the same
 * amber as the watch button on purpose: the strip and the button are the same fact.
 */
@Composable
fun ProgressStrip(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(KaeruTokens.ProgressHeight)
            .background(Color.White.copy(alpha = 0.18f)),
    ) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(KaeruAccent))
    }
}

/**
 * The same strip, for work whose end is not known: a segment of accent crossing the track.
 *
 * It is deliberately the same four device-independent pixels of amber as [ProgressStrip] rather
 * than a spinner. The app has said «this is how far along something is» in exactly one shape since
 * the first poster card, and a circle borrowed from Material for the one screen where the end is
 * unknown would be a second vocabulary for the same idea — on top of skeleton blocks that are
 * already pulsing, it would also read as a second thing loading.
 *
 * The travel is read inside `graphicsLayer`, so it animates in the draw phase and recomposes
 * nothing, and it is expressed in multiples of the segment's own width so the strip needs to know
 * neither the density nor the width it was given.
 */
@Composable
fun IndeterminateStrip(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "indeterminate")
    val travel by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        // It sweeps back rather than snapping to the left: the skeleton blocks beside it breathe
        // in and out on the same kind of reversing clock, and a strip that jumped home every
        // second-and-a-bit would be the one thing on the screen keeping a different time. The
        // easing a `tween` brings by default is what the turn at each end needs anyway.
        animationSpec = infiniteRepeatable(tween(TRAVEL_MS), RepeatMode.Reverse),
        label = "travel",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(KaeruTokens.ProgressHeight)
            .background(Color.White.copy(alpha = 0.18f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(SEGMENT)
                .fillMaxHeight()
                .graphicsLayer { translationX = travel * size.width * TRAVEL }
                .background(KaeruAccent),
        )
    }
}
