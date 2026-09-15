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
import app.kaeru.ui.common.theme.KaeruBackground
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
 *
 * Its label is the app's own near-black rather than white: `#F2F3F5` on `#E5484D` measures 3.5:1,
 * which clears the floor for large text only, while `#0B0C10` measures 5.0:1 and clears 4.5:1 at
 * any size. The label on the one irreversible control in the app should not be the one label a
 * viewer has to squint at.
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
            contentColor = KaeruBackground,
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
 *
 * [compact] is the same button in a column a third of the screen wide: the card action under a
 * poster in a grid. It keeps the shape, the border and the colours and gives up the things that do
 * not fit — the 52dp height drops to the 48dp floor, the 24dp side padding to 8, and the label to
 * the size a card title is set in. Nothing else changes, so «В планы» under a poster and
 * «Подробнее» beside the hero still read as the same control.
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .kaeruFocus(KaeruTokens.ButtonShape)
            .heightIn(min = if (compact) KaeruTokens.MinTouchTarget else KaeruTokens.ButtonHeight),
        shape = KaeruTokens.ButtonShape,
        border = BorderStroke(1.dp, KaeruDivider),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = KaeruElevated.copy(alpha = 0.85f),
            contentColor = KaeruText,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = KaeruSecondary,
        ),
        contentPadding = PaddingValues(horizontal = if (compact) KaeruTokens.Space2 else KaeruTokens.Space6),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(IconSize))
            Spacer(Modifier.width(KaeruTokens.Space2))
        }
        Text(
            text,
            style = if (compact) {
                MaterialTheme.typography.titleSmall
            } else {
                MaterialTheme.typography.titleMedium
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
