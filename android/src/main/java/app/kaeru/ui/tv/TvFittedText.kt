package app.kaeru.ui.tv

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

/**
 * A name that steps down the type scale before it lets itself be cut.
 *
 * A television title has one place to be read in full: the hero band, or the head of a title card.
 * Both are a fixed number of lines, and the catalogue's names are not — «Приговорённый быть героем:
 * Тюремные хроники…» lost its second half to an ellipsis at `displaySmall` on a panel that had room
 * for the whole of it two sizes down. So the text is laid out at the first of [styles]; if that
 * overflows [maxLines] it is laid out again at the next, and only when the last one still does not
 * fit is the end of the name given up.
 *
 * Each step is one extra layout pass on the frame the words change, which is a press of the
 * remote, and never on a frame they do not. The step is forgotten with the words, so a short name
 * after a long one gets the full size back.
 */
@Composable
fun TvFittedText(
    text: String,
    styles: List<TextStyle>,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    var step by remember(text) { mutableIntStateOf(0) }
    Text(
        text,
        style = styles[step.coerceAtMost(styles.lastIndex)],
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        onTextLayout = { if (it.hasVisualOverflow && step < styles.lastIndex) step++ },
    )
}
