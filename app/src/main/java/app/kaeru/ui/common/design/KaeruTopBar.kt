package app.kaeru.ui.common.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kaeru.ui.common.theme.KaeruBackground
import app.kaeru.ui.common.theme.KaeruText

private val BarHeight = 56.dp

/**
 * The bar at the top of a screen, in two kinds.
 *
 * Opaque, it is the screen's own header and its title is set in headline: this is where you are.
 * Transparent, it is chrome floating on a backdrop, so it drops its container entirely and its
 * title steps down to title weight — the picture and the hero headline under it are the loud
 * things, and a wordmark competing with them would be noise.
 *
 * The bar applies the status-bar inset itself, so place it as the topmost element of a screen
 * rather than inside `Scaffold`'s top bar slot.
 */
@Composable
fun KaeruTopBar(
    title: String?,
    modifier: Modifier = Modifier,
    transparent: Boolean = false,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (transparent) Modifier else Modifier.background(KaeruBackground))
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(BarHeight)
            .padding(horizontal = KaeruTokens.Space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        navigationIcon?.invoke()
        if (title != null) {
            Text(
                title,
                style = if (transparent) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.headlineMedium
                },
                color = KaeruText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = KaeruTokens.Space3),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        actions()
    }
}
