package app.kaeru.ui.common.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.ProgressStrip
import app.kaeru.ui.common.theme.KaeruError
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText

/**
 * The pieces of prose the «Обновления» page is made of, shared by the phone and the television.
 *
 * They are the settings page's voice — a loud line naming the thing, a quiet one explaining it —
 * written here rather than borrowed, because a feature package reaching into another feature's
 * components is how two screens come to share a bug. What they share instead is the type scale,
 * which is the design system's.
 */

/** What this part of the page is about: a version, an outcome, a state. */
@Composable
fun UpdateHeadline(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = KaeruText, modifier = modifier)
}

/** The quiet line under it: a date, a size, a sentence saying what happens next. */
@Composable
fun UpdateNote(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = KaeruSecondary, modifier = modifier)
}

/**
 * Something went wrong, in red and in one line.
 *
 * Red rather than the app's usual secondary grey, and it is the only red on this page: everything
 * else here is either a fact or an offer, and a failure is neither. It never replaces what is
 * already on screen — a check that failed over a release already found leaves the release where
 * it was and adds this underneath.
 */
@Composable
fun UpdateError(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = KaeruError, modifier = modifier)
}

/**
 * The release body, already reduced to plain text by the time it gets here.
 *
 * [maxLines] is for the television, where the page cannot scroll under a remote except by moving
 * focus and a block of prose is not a thing to land on. On a phone it is unbounded, which is the
 * default, and a long note simply scrolls.
 */
@Composable
fun UpdateNotes(notes: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        notes,
        style = MaterialTheme.typography.bodyMedium,
        color = KaeruSecondary,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * How far the download has got: the app's own four device-independent pixels of amber, and the
 * count underneath.
 *
 * Determinate, because the size came with the release. The indeterminate strip elsewhere in the
 * app is for work whose end is unknown, and a thirty-megabyte transfer with a known length is
 * exactly the case that should not borrow it.
 */
@Composable
fun UpdateProgress(
    progress: Float,
    line: String,
    modifier: Modifier = Modifier,
    stripWidth: Dp? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space2)) {
        // The strip fills what it is given, so a screen that wants it shorter than the text
        // column — the television, where a rule across a panel is a browser loading bar — says so
        // here rather than wrapping it in a box of its own.
        ProgressStrip(progress, if (stripWidth != null) Modifier.width(stripWidth) else Modifier)
        UpdateNote(line)
    }
}
