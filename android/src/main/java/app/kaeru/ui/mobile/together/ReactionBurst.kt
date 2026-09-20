package app.kaeru.ui.mobile.together

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.kaeru.domain.together.ReactionKind
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.theme.KaeruTheme
import app.kaeru.ui.common.together.FlyingReaction
import kotlin.math.PI
import kotlin.math.sin

/** How far an emoji gets before it is gone. */
private val Rise = 120.dp

/** Fourteen points of horizontal wander, so three at once do not travel as one column. */
private val Drift = 14.dp

private const val FLIGHT_MS = 1_200

private val Glyph = 22.dp

/** The row that slides out of the 😀 button: six, at the size everything here is reachable at. */
private val PickSize = KaeruTokens.MinTouchTarget

/** What each of the six is drawn as. The protocol carries the name, the screen picks the picture. */
internal fun reactionGlyph(kind: ReactionKind): String = when (kind) {
    ReactionKind.HEART -> "❤️"
    ReactionKind.LAUGH -> "😂"
    ReactionKind.WOW -> "😮"
    ReactionKind.SAD -> "😢"
    ReactionKind.FIRE -> "🔥"
    ReactionKind.CLAP -> "👏"
}

/**
 * Whether the phone has been told to stop moving things.
 *
 * Android has one switch for this and it is the animator duration scale: a developer option, but
 * also what the accessibility setting «Удалить анимации» writes. WCAG 2.3.3 asks that motion
 * started by an interaction can be turned off, and this is the only place a person can turn it off
 * — so it is the only place worth reading.
 */
@Composable
internal fun reducedMotion(): Boolean {
    if (LocalInspectionMode.current) return false
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * Whether somebody is driving this screen by ear.
 *
 * Touch exploration is what TalkBack turns on, and it is the one signal that says the corner is
 * being listened to rather than glanced at. Everything that disappears on a timer stops doing so
 * while it is true.
 */
@Composable
internal fun touchExploration(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    return remember(context) {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        manager?.isTouchExplorationEnabled == true
    }
}

/**
 * Emoji going up the right-hand side of the picture.
 *
 * Three at a time at the very most, which the view model enforces rather than this: a friend
 * tapping the row six times should read as enthusiasm, not as weather. Where a reaction starts is
 * the only thing that says whose it is — this viewer's leaves from beside the button they pressed,
 * the other phone's from higher up — which is cheaper than a name beside every one of them.
 *
 * With animations turned off nothing travels: the emoji appears where it would have started and
 * holds there for the same 1.2 seconds. Same information, no movement.
 */
@Composable
fun ReactionBurst(reactions: List<FlyingReaction>, modifier: Modifier = Modifier) {
    val still = reducedMotion()
    Box(modifier.fillMaxSize()) {
        reactions.forEach { reaction ->
            key(reaction.id) {
                Flight(reaction, still, Modifier.align(if (reaction.mine) Alignment.BottomEnd else Alignment.CenterEnd))
            }
        }
    }
}

@Composable
private fun Flight(reaction: FlyingReaction, still: Boolean, modifier: Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(reaction.id) {
        if (!still) progress.animateTo(1f, tween(FLIGHT_MS, easing = LinearOutSlowInEasing))
    }
    val travelled = progress.value
    Text(
        reactionGlyph(reaction.kind),
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier
            .padding(end = KaeruTokens.Space6, bottom = 96.dp)
            .size(Glyph)
            .offset(
                x = Drift * sin(travelled * PI.toFloat()),
                y = -Rise * travelled,
            )
            // Fades over the back half only: a glyph that starts disappearing the moment it
            // appears is a glyph nobody catches.
            .alpha(((1f - travelled) * 2f).coerceIn(0f, 1f))
            // Its own emoji is what it says; nothing reads «❤️» out usefully, and the arrival is
            // already announced by the corner.
            .clearAndSetSemantics {},
    )
}

/** The six, in a row, for as long as the viewer is choosing between them. */
@Composable
fun ReactionPicker(onPick: (ReactionKind) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space1)) {
        ReactionKind.entries.forEach { kind ->
            Box(
                Modifier
                    .size(PickSize)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(onClick = { onPick(kind) }, onClickLabel = reactionName(kind)),
                contentAlignment = Alignment.Center,
            ) {
                Text(reactionGlyph(kind), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** What a screen reader says instead of the picture. */
private fun reactionName(kind: ReactionKind): String = when (kind) {
    ReactionKind.HEART -> "Сердце"
    ReactionKind.LAUGH -> "Смех"
    ReactionKind.WOW -> "Удивление"
    ReactionKind.SAD -> "Грусть"
    ReactionKind.FIRE -> "Огонь"
    ReactionKind.CLAP -> "Аплодисменты"
}

@Preview(name = "Реакции", showBackground = true, backgroundColor = 0xFF0B0C10, widthDp = 360, heightDp = 120)
@Composable
private fun ReactionPickerPreview() = KaeruTheme {
    Box(Modifier.padding(KaeruTokens.Space4)) { ReactionPicker(onPick = {}) }
}
