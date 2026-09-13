package app.kaeru.ui.common.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import app.kaeru.ui.common.theme.KaeruAccent

/**
 * Forces the focus treatment on, for previews.
 *
 * A static preview cannot move focus, so without this there is no way to see across a room what a
 * television will actually show. Nothing in the app sets it.
 */
internal val LocalFocusPreview = staticCompositionLocalOf { false }

/**
 * The focus treatment, applied to every shared control so a television screen gets it for free.
 *
 * Two signals, because one is not enough from three metres away: the control grows by six per cent
 * and takes a 3dp ring. No glow — a bloom around every focusable turns a row into a smear.
 *
 * On a phone this never fires: touch does not move focus. It does fire for a hardware keyboard,
 * which is the accessible behaviour anyway.
 *
 * [borderColor] exists for the one case the accent cannot cover itself: an amber ring around an
 * amber button is invisible, so [PrimaryButton] and a selected [StatusPill] ring in text colour
 * instead. Everything else keeps the accent the design system asks for.
 */
@Composable
fun Modifier.kaeruFocus(
    shape: Shape = KaeruTokens.ButtonShape,
    borderColor: Color = KaeruAccent,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val shown = focused || LocalFocusPreview.current
    val scale by animateFloatAsState(
        targetValue = if (shown) KaeruTokens.FocusScale else 1f,
        animationSpec = tween(KaeruTokens.DurationFast),
        label = "kaeruFocusScale",
    )
    return this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .scale(scale)
        .then(if (shown) Modifier.border(KaeruTokens.FocusBorder, borderColor, shape) else Modifier)
}
