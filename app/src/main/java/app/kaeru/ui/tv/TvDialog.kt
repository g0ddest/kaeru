package app.kaeru.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.theme.KaeruBackground
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruSurface
import app.kaeru.ui.common.theme.KaeruText

/** Wide enough for a studio name and a caption, narrow enough not to become the screen. */
private val PanelWidth = 560.dp

/**
 * A panel over the screen: the television's answer to the phone's bottom sheet.
 *
 * A real dialog window rather than a box drawn over the content, and that is the whole point on a
 * television: a remote walking the D-pad out of an overlay and into the controls behind it is the
 * classic way a TV screen becomes unusable, and a window is what actually keeps focus inside.
 *
 * The scrim is the app's own near-black rather than Material's grey: it exists so the panel can be
 * read against artwork, which is the one thing a scrim is allowed to be for here.
 *
 * [text] is the sentence under the heading, for a dialog that asks rather than lists.
 */
@Composable
fun TvDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    width: Dp = PanelWidth,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(KaeruBackground.copy(alpha = 0.78f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier
                    .width(width)
                    .clip(KaeruTokens.CardShape)
                    .background(KaeruSurface)
                    .padding(KaeruTokens.Space8),
                verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = KaeruText,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (text != null) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = KaeruSecondary,
                        modifier = Modifier.padding(bottom = KaeruTokens.Space2),
                    )
                }
                content()
            }
        }
    }
}
