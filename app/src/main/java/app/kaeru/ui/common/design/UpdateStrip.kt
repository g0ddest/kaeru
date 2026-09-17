package app.kaeru.ui.common.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruSurface
import app.kaeru.ui.common.theme.KaeruTheme

private val ChevronSize = 18.dp

/**
 * `Доступна версия 0.4.0` — the whole of what the row says, and the headline of the screen it
 * leads to.
 *
 * It lives in the design system rather than beside the screen because both need it and a screen
 * may not reach into another screen's package. A signpost worded differently from the page it
 * points at would read as two separate pieces of news about one release.
 */
fun updateAvailableText(version: String): String = "Доступна версия $version"

/**
 * One line at the top of the home screen saying a newer version exists, and leading to it.
 *
 * It is modelled on [OfflineStrip] and is deliberately as quiet: surface colour, secondary text,
 * no badge, no colour, no animation. An update is not news the app should interrupt anybody
 * with — nothing is wrong, nothing is expiring, and the episode the viewer opened the app for is
 * two rows below. So it is stated once, at the top, in three words, and it is stated by a row
 * rather than by a dialog because a dialog would have to be dismissed.
 *
 * The one thing it has that the offline strip does not is a chevron and a press. It goes
 * somewhere, and the chevron is how every other row in this app says so.
 *
 * It is a 48dp target and takes the focus treatment, so the remote can land on it: on a television
 * this is the only way to «Обновления» that is not three presses into the settings page.
 */
@Composable
fun UpdateStrip(
    version: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    gutter: Dp = KaeruTokens.GutterPhone,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(KaeruSurface)
            // A full-width row has nowhere to grow into, so the ring carries the whole focus
            // signal — as it does on a settings row, and for the same reason.
            .kaeruFocus(KaeruTokens.CardShape, focusedScale = 1f)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = KaeruTokens.MinTouchTarget)
            .padding(horizontal = gutter, vertical = KaeruTokens.Space3)
            // One announcement rather than two: the sentence is what this row is.
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            updateAvailableText(version),
            style = MaterialTheme.typography.bodyMedium,
            color = KaeruSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(KaeruTokens.Space2))
        // Undescribed on purpose: the line beside it already says where this goes.
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = KaeruSecondary,
            modifier = Modifier.size(ChevronSize),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0C10, widthDp = 360, heightDp = 56)
@Composable
private fun UpdateStripPreview() = KaeruTheme { UpdateStrip("0.4.0", {}) }

@Preview(showBackground = true, backgroundColor = 0xFF0B0C10, widthDp = 720, heightDp = 56)
@Composable
private fun UpdateStripTvPreview() = KaeruTheme {
    UpdateStrip("0.4.0", {}, gutter = KaeruTokens.GutterTv)
}
