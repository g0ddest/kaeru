package app.kaeru.ui.common.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kaeru.ui.common.theme.KaeruSurface

private val HeaderBlock = 20.dp

/** About the length of a two-word row title, so the bar stands where the words will. */
private val HeaderBlockWidth = 140.dp
private val TitleBlock = 14.dp
private const val GRID_COLUMNS = 3
private const val PULSE_MIN = 0.10f
private const val PULSE_MAX = 0.24f
private const val PULSE_MS = 700

/**
 * The pulse a group of skeleton blocks shares.
 *
 * One transition for the whole group rather than one per block: twelve blocks in a grid, each with
 * its own transition started whenever it happened to be composed, drift out of phase and read as
 * twelve things loading instead of one screen loading.
 */
private val LocalSkeletonPulse = compositionLocalOf<State<Float>?> { null }

@Composable
private fun rememberSkeletonPulse(): State<Float> {
    val transition = rememberInfiniteTransition(label = "skeleton")
    return transition.animateFloat(
        initialValue = PULSE_MIN,
        targetValue = PULSE_MAX,
        animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
}

/**
 * Puts every [Skeleton] below it on one clock.
 *
 * Wrap a screen's own arrangement of blocks in this rather than scattering bare [Skeleton]s: each
 * one outside a group starts an infinite transition of its own, and a dozen of them drifting out
 * of phase reads as a dozen things happening instead of one screen waiting.
 */
@Composable
fun SkeletonGroup(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSkeletonPulse provides rememberSkeletonPulse(), content = content)
}

/**
 * A block standing in for content that has not arrived.
 *
 * It breathes rather than sweeps: a slow alpha pulse reads as waiting, while a shimmer travelling
 * across three rows of blocks reads as a second thing happening on the screen. The pulse is read
 * inside `graphicsLayer`, so it animates in the draw phase and never recomposes the block.
 */
@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: Shape = KaeruTokens.CardShape) {
    val pulse = LocalSkeletonPulse.current ?: rememberSkeletonPulse()
    Box(
        modifier
            .clip(shape)
            .graphicsLayer { alpha = pulse.value }
            .background(Color.White),
    )
}

/**
 * The hero's place, held at its real shape so nothing below it jumps when the feed lands.
 *
 * [aspect] is the phone's 4:5 by default; a television, where the hero is a band across the top
 * rather than half the screen, passes its own.
 */
@Composable
fun SkeletonHero(modifier: Modifier = Modifier, aspect: Float = KaeruTokens.HeroAspect) = SkeletonGroup {
    Skeleton(
        modifier.fillMaxWidth().aspectRatio(aspect).background(KaeruSurface),
        shape = RectangleShape,
    )
}

/** One row of poster cards, header included, at the width and gutter the real row will have. */
@Composable
fun SkeletonRow(
    modifier: Modifier = Modifier,
    count: Int = 3,
    gutter: Dp = KaeruTokens.GutterPhone,
    posterWidth: Dp = KaeruTokens.PosterWidthPhone,
) = SkeletonGroup {
    Column(modifier.fillMaxWidth().clipToBounds()) {
        Skeleton(Modifier.padding(horizontal = gutter).width(HeaderBlockWidth).height(HeaderBlock))
        Cards(count, gutter, posterWidth)
    }
}

/**
 * The cards of a loading row and nothing else, for a row whose real heading is already on screen.
 *
 * A section that knows its own title before its titles arrive — the discovery rows, whose headings
 * are constants — should print that title and wait underneath it: it is readable a beat earlier
 * than a grey bar standing in for it, and the page does not move when the cards land. Using
 * [SkeletonRow] there would draw a second, fake heading under the real one.
 */
@Composable
fun SkeletonCardsRow(
    modifier: Modifier = Modifier,
    count: Int = 3,
    gutter: Dp = KaeruTokens.GutterPhone,
    posterWidth: Dp = KaeruTokens.PosterWidthPhone,
) = SkeletonGroup {
    Column(modifier.fillMaxWidth().clipToBounds()) { Cards(count, gutter, posterWidth) }
}

@Composable
private fun Cards(count: Int, gutter: Dp, posterWidth: Dp) {
    Row(
        Modifier.padding(horizontal = gutter, vertical = KaeruTokens.Space3),
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
    ) {
        repeat(count) {
            Column(Modifier.width(posterWidth)) {
                Skeleton(Modifier.fillMaxWidth().aspectRatio(KaeruTokens.PosterAspect))
                Skeleton(Modifier.padding(top = KaeruTokens.Space2).fillMaxWidth(0.85f).height(TitleBlock))
            }
        }
    }
}

/** The library and search grid, three posters across, at the same pitch as the real one. */
@Composable
fun SkeletonGrid(
    modifier: Modifier = Modifier,
    count: Int = 6,
    gutter: Dp = KaeruTokens.GutterPhone,
    columns: Int = GRID_COLUMNS,
) = SkeletonGroup {
    Column(
        modifier.fillMaxWidth().padding(horizontal = gutter),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
    ) {
        (0 until count).chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3)) {
                row.forEach { _ ->
                    Column(Modifier.weight(1f)) {
                        Skeleton(Modifier.fillMaxWidth().aspectRatio(KaeruTokens.PosterAspect))
                        Skeleton(Modifier.padding(top = KaeruTokens.Space2).fillMaxWidth(0.85f).height(TitleBlock))
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
