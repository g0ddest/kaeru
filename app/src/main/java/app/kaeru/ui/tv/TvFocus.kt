package app.kaeru.ui.tv

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester

internal const val TV_TAG = "KaeruTv"

/**
 * Requests focus and reports the miss instead of swallowing it. [FocusRequester.requestFocus]
 * throws when nothing focusable is attached yet, which on TV means the screen is left with no
 * D-pad focus at all: a silent failure here is invisible in logs and fatal to navigation.
 */
internal fun FocusRequester.requestFocusOrLog(what: String) {
    try {
        requestFocus()
    } catch (error: IllegalStateException) {
        Log.w(TV_TAG, "No attached focus target for $what; screen starts without D-pad focus", error)
    }
}

/** One row of focusable cards as the focus memory sees it: a stable heading and the ids under it. */
data class TvFocusRow(val key: String, val ids: List<Int>)

/** The card the D-pad should be sitting on. */
data class TvFocusKey(val row: String, val id: Int)

/**
 * Which card to hand focus to when a screen is shown again.
 *
 * A television has no pointer, so «where was I» is the whole of its navigation state: coming back
 * from a title card to the top-left corner of the home screen would make the remote useless for
 * browsing. Three answers, in order:
 *
 * 1. the remembered card, exactly where it was;
 * 2. the same title in a different row — watching an episode moves a card out of «Новые серии»
 *    and into «Продолжить», and the viewer should land on the card, not at the top of the screen;
 * 3. the first card there is, for a first visit or a title that has left the list entirely.
 *
 * Rows with nothing in them are stepped over rather than focused, which is what a lazy list does
 * with them anyway.
 */
fun tvRestoreFocus(remembered: TvFocusKey?, rows: List<TvFocusRow>): TvFocusKey? {
    if (remembered != null) {
        val sameRow = rows.firstOrNull { it.key == remembered.row && remembered.id in it.ids }
        if (sameRow != null) return remembered
        val moved = rows.firstOrNull { remembered.id in it.ids }
        if (moved != null) return TvFocusKey(moved.key, remembered.id)
    }
    val first = rows.firstOrNull { it.ids.isNotEmpty() } ?: return null
    return TvFocusKey(first.key, first.ids.first())
}

/**
 * Where one destination's D-pad focus was, held above the screen that lost it.
 *
 * Kept apart from the screen's own state because the screen is taken down every time a title card
 * opens over it. It survives a process death too: the id and the heading are two small values, and
 * restoring the shell without them would put the viewer back at the top of a list they had
 * scrolled halfway down.
 */
@Stable
class TvFocusMemory(key: TvFocusKey? = null) {
    var key: TvFocusKey? by mutableStateOf(key)

    companion object {
        val Saver: Saver<TvFocusMemory, List<Any>> = Saver(
            save = { memory -> memory.key?.let { listOf(it.row, it.id) } ?: emptyList() },
            restore = { saved ->
                TvFocusMemory(
                    if (saved.size == 2) TvFocusKey(saved[0] as String, saved[1] as Int) else null,
                )
            },
        )
    }
}

@Composable
fun rememberTvFocusMemory(): TvFocusMemory =
    rememberSaveable(saver = TvFocusMemory.Saver) { TvFocusMemory() }
