package app.kaeru.ui.common.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruDivider
import app.kaeru.ui.common.theme.KaeruElevated
import app.kaeru.ui.common.theme.KaeruError
import app.kaeru.ui.common.theme.KaeruOnAccent
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText

private val IconSize = 20.dp
private val ActionIconSize = 22.dp

/**
 * The amber button. There is one of these in view at a time — it is what the screen is for — so it
 * carries no elevation and needs none: on this background amber is already the loudest thing.
 *
 * The icon beside the label is deliberately not described. The label already says what will
 * happen, and a description here would make a screen reader say it twice.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            // A ring in text colour, not amber: an amber ring around an amber button is invisible.
            .kaeruFocus(KaeruTokens.ButtonShape, borderColor = KaeruText)
            .heightIn(min = KaeruTokens.ButtonHeight),
        shape = KaeruTokens.ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = KaeruAccent,
            contentColor = KaeruOnAccent,
            disabledContainerColor = KaeruElevated,
            disabledContentColor = KaeruSecondary,
        ),
        elevation = null,
        contentPadding = PaddingValues(horizontal = KaeruTokens.Space6),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(IconSize))
            Spacer(Modifier.width(KaeruTokens.Space2))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The button that takes something away and cannot be pressed again to undo it: signing out, and
 * later anything that deletes.
 *
 * It is red rather than amber because amber means «go» everywhere else in the app, and the one
 * irreversible control should not wear the colour of the one the viewer presses all evening. It is
 * shaped and sized exactly like [PrimaryButton], so a confirmation dialog reads as the same kind of
 * choice — only the colour says what kind of thing is about to happen.
 *
 * Confirm with it; never use it as the way *into* a dialog, where the quiet [SecondaryButton] is
 * the honest affordance.
 */
@Composable
fun DestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            // As on the amber button: a ring in the container's own colour would be invisible.
            .kaeruFocus(KaeruTokens.ButtonShape, borderColor = KaeruText)
            .heightIn(min = KaeruTokens.ButtonHeight),
        shape = KaeruTokens.ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = KaeruError,
            contentColor = KaeruText,
            disabledContainerColor = KaeruElevated,
            disabledContentColor = KaeruSecondary,
        ),
        elevation = null,
        contentPadding = PaddingValues(horizontal = KaeruTokens.Space6),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(IconSize))
            Spacer(Modifier.width(KaeruTokens.Space2))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The quieter of the two. Its container is translucent rather than transparent so it survives
 * sitting on a bright screenshot next to the amber one, which is where it usually is.
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .kaeruFocus(KaeruTokens.ButtonShape)
            .heightIn(min = KaeruTokens.ButtonHeight),
        shape = KaeruTokens.ButtonShape,
        border = BorderStroke(1.dp, KaeruDivider),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = KaeruElevated.copy(alpha = 0.85f),
            contentColor = KaeruText,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = KaeruSecondary,
        ),
        contentPadding = PaddingValues(horizontal = KaeruTokens.Space6),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(IconSize))
            Spacer(Modifier.width(KaeruTokens.Space2))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * An action with no room for a word: back, cast, settings, close.
 *
 * [overArtwork] puts a dark disc under the glyph. Over a screenshot a bare white icon disappears
 * against anything pale, and the disc is the smallest thing that fixes it without adding chrome to
 * screens that have a solid background anyway.
 *
 * [contentDescription] is required, not optional: this is the one control whose meaning exists
 * nowhere else on the screen.
 */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    overArtwork: Boolean = false,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .defaultMinSize(KaeruTokens.MinTouchTarget, KaeruTokens.MinTouchTarget)
            .kaeruFocus(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (overArtwork) {
            Box(Modifier.size(KaeruTokens.MinTouchTarget).clip(CircleShape).background(Color.Black.copy(alpha = 0.42f)))
        }
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(KaeruTokens.MinTouchTarget)) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (enabled) KaeruText else KaeruSecondary,
                modifier = Modifier.size(ActionIconSize),
            )
        }
    }
}
