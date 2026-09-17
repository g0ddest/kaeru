package app.kaeru.ui.common.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruSurface
import app.kaeru.ui.common.theme.KaeruTheme

/** What the phone says: there is a way to keep watching, and the strip names it. */
const val OFFLINE_WITH_DOWNLOADS = "Нет сети — доступны скачанные серии"

/** What the television says, where there are no downloads to point at. */
const val OFFLINE = "Нет сети"

/**
 * One line saying the phone is offline.
 *
 * Deliberately the quietest thing it could be: surface colour, secondary text, no icon, no
 * animation, no way to dismiss it. Being offline is a condition rather than a failure — the app
 * goes on working, which is the whole point of the downloads — so it is stated once at the top of
 * the screen and never again. A red banner would be the app treating a tunnel as an incident, and
 * a spinner would promise a retry that is not happening.
 *
 * It appears and disappears with the network and nothing else: there is no enter animation,
 * because the rows below it move when it lands and a slide would turn that into a lurch.
 *
 * [compact] halves the air around the line, and exists for exactly one caller: the television home
 * screen, where the panel is 540dp tall and every one of them is spoken for. The line is the same
 * line — same colour, same ground, same words — drawn in [app.kaeru.ui.tv.TvLayout.NoticeHeight]
 * rather than in fifty-four. A phone has the room and keeps the air.
 */
@Composable
fun OfflineStrip(
    modifier: Modifier = Modifier,
    text: String = OFFLINE_WITH_DOWNLOADS,
    gutter: Dp = KaeruTokens.GutterPhone,
    compact: Boolean = false,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = KaeruSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .background(KaeruSurface)
            .padding(
                horizontal = gutter,
                vertical = if (compact) KaeruTokens.Space1 else KaeruTokens.Space3,
            ),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0C10, widthDp = 360, heightDp = 48)
@Composable
private fun OfflineStripPreview() = KaeruTheme { OfflineStrip() }

@Preview(showBackground = true, backgroundColor = 0xFF0B0C10, widthDp = 720, heightDp = 48)
@Composable
private fun OfflineStripTvPreview() = KaeruTheme {
    OfflineStrip(text = OFFLINE, gutter = KaeruTokens.GutterTv, compact = true)
}
