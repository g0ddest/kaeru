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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import app.kaeru.ui.common.theme.KaeruSurface

private val HeaderBlock = 20.dp
private val TitleBlock = 14.dp
private const val GRID_COLUMNS = 3

/**
 * A block standing in for content that has not arrived.
 *
 * It breathes rather than sweeps: a slow alpha pulse reads as waiting, while a shimmer travelling
 * across three rows of blocks reads as a second thing happening on the screen.
 */
@Composable
fun Skeleton(modifier: Modifier = Modifier, shape: Shape = KaeruTokens.CardShape) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.24f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    Box(modifier.clip(shape).background(Color.White.copy(alpha = alpha)))
}

/**
 * The hero's place, held at its real shape so nothing below it jumps when the feed lands.
 *
 * [aspect] is the phone's 4:5 by default; a television, where the hero is a band across the top
 * rather than half the screen, passes its own.
 */
@Composable
fun SkeletonHero(modifier: Modifier = Modifier, aspect: Float = KaeruTokens.HeroAspect) {
    Skeleton(
        modifier.fillMaxWidth().aspectRatio(aspect).background(KaeruSurface),
        shape = RectangleShape,
    )
}

/** One row of poster cards, header included, at the width the real row will have. */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier, count: Int = 3) {
    Column(modifier.fillMaxWidth().clipToBounds()) {
        Skeleton(Modifier.padding(horizontal = KaeruTokens.GutterPhone).width(140.dp).height(HeaderBlock))
        Row(
            Modifier.padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space3),
            horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
        ) {
            repeat(count) {
                Column(Modifier.width(KaeruTokens.PosterWidthPhone)) {
                    Skeleton(Modifier.fillMaxWidth().aspectRatio(KaeruTokens.PosterAspect))
                    Skeleton(Modifier.padding(top = KaeruTokens.Space2).fillMaxWidth(0.85f).height(TitleBlock))
                }
            }
        }
    }
}

/** The library and search grid, three posters across, at the same pitch as the real one. */
@Composable
fun SkeletonGrid(modifier: Modifier = Modifier, count: Int = 6) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = KaeruTokens.GutterPhone),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
    ) {
        (0 until count).chunked(GRID_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3)) {
                row.forEach { _ ->
                    Column(Modifier.weight(1f)) {
                        Skeleton(Modifier.fillMaxWidth().aspectRatio(KaeruTokens.PosterAspect))
                        Skeleton(Modifier.padding(top = KaeruTokens.Space2).fillMaxWidth(0.85f).height(TitleBlock))
                    }
                }
                repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
