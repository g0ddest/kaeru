package app.kaeru.ui.tv

import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

internal const val TV_TAG = "KaeruTv"

/**
 * Requests focus, reports the miss instead of swallowing it, and says whether it landed.
 *
 * [FocusRequester.requestFocus] throws when nothing focusable is attached yet, which on a
 * television means the screen is left with no D-pad focus at all: a silent failure here is
 * invisible in logs and fatal to navigation. The boolean is what lets a caller tell a claim that
 * worked from one that has to be made again when the target is finally composed — latching on the
 * attempt rather than on the result is how a screen ends up permanently unfocusable.
 */
internal fun FocusRequester.requestFocusOrLog(what: String): Boolean = try {
    requestFocus()
    true
} catch (error: IllegalStateException) {
    Log.w(TV_TAG, "No attached focus target for $what; screen starts without D-pad focus", error)
    false
}

/**
 * Asks for the focus, and asks again on the next frame if the first ask found nothing to give it to.
 *
 * A node can be composed and not yet placed — which is exactly the frame a row that has just been
 * scrolled to a remembered card is in — and [FocusRequester.requestFocus] throws at one of those.
 * Two goes a frame apart is the difference between a screen that lands on the card the viewer left
 * and one the D-pad cannot move at all.
 */
internal suspend fun FocusRequester.claimFocus(what: String): Boolean {
    if (requestFocusOrLog(what)) return true
    withFrameNanos { }
    return requestFocusOrLog(what)
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

/** A remembered card and where it sits: which row, and how far along that row it is. */
data class TvRestoreTarget(val row: String, val id: Int, val index: Int)

/**
 * The same answer as [tvRestoreFocus], with the one thing a focus request cannot do without: the
 * card's index in its row.
 *
 * A lazy row composes about a screenful of cards and no more, so a request aimed at the twelfth
 * card of a row that is showing the first four goes nowhere — the node it names does not exist.
 * Knowing the index is what lets the screen take the row there first and then ask.
 */
fun tvRestoreTarget(remembered: TvFocusKey?, rows: List<TvFocusRow>): TvRestoreTarget? {
    val key = tvRestoreFocus(remembered, rows) ?: return null
    val index = rows.firstOrNull { it.key == key.row }?.ids?.indexOf(key.id) ?: return null
    return if (index < 0) null else TvRestoreTarget(key.row, key.id, index)
}

/**
 * Where a row has to be taken so the remembered card exists to be focused, or null to leave it
 * exactly where it is.
 *
 * Both halves matter. A row that has never been moved has to be scrolled, or a card past the first
 * screenful is never composed and nothing on the screen claims focus. A row that *has* been moved —
 * which is every row on the way back from a title card — must not be scrolled, because taking it
 * to the remembered index would put the card the viewer was sitting on at the left edge: the
 * artwork sliding under a remote nobody has touched.
 *
 * [viewport] is how many cards are known to be composed from [firstVisible] onwards. It is counted
 * conservatively — see [TvLayout.RowViewport] — because the cost of one needless scroll is a small
 * shift, and the cost of one missed scroll is a screen the D-pad cannot move.
 */
fun tvRowScroll(index: Int, firstVisible: Int, viewport: Int): Int? =
    if (index >= firstVisible && index < firstVisible + viewport) null else index

/**
 * How far along each row of a screen is scrolled, held above the screen that loses it.
 *
 * Every row of the home screen is a `LazyRow` inside a lazy item of a `LazyColumn`, and both go
 * when a title card replaces the screen. Without this, the vertical position came back and every
 * row reopened at its first card — so a viewer who scrubbed six titles into «Продолжить», opened
 * one and pressed back landed at the start of the row.
 *
 * A plain map rather than a snapshot one on purpose: a row's state object never changes once made,
 * and nothing should recompose because a row was asked for. What is observable is each
 * [LazyListState], which is the part that moves.
 *
 * Not saved across a process death, deliberately. The card the viewer was on is
 * ([TvFocusMemory] holds it, and the screen scrolls its row back to it), and restoring the exact
 * offset of the four rows they were not looking at is not worth a bundle.
 */
@Stable
class TvRowStates {
    private val states = HashMap<String, LazyListState>()

    /** The row's scroll position, made the first time the row is drawn and kept after that. */
    fun of(row: String): LazyListState = states.getOrPut(row) { LazyListState() }
}
