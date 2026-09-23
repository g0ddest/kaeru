package app.kaeru.ui.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import kotlin.math.abs

/**
 * Scroll by the least that shows the focused thing, and not at all when it is already shown.
 *
 * On a television the platform's own rule is a pivot: a focused child is brought to three-tenths
 * of the way down — or along — its scroll container, wherever it was. That is right for a row of
 * posters, where the focused card should sit in the same place while the rest slides past it, and
 * wrong for a page of settings or a column of rows: a page that opened on a switch a third of the
 * way down the panel scrolled itself 111dp to put that switch on the pivot line, and the account
 * along the top edge went with it.
 *
 * This is the rule a phone uses, written out because the foundation library keeps its own copy
 * internal. A child taller than the container is left where it is: there is no scroll that shows
 * all of it, and the D-pad will ask again for whatever inside it takes the focus next.
 */
@OptIn(ExperimentalFoundationApi::class)
object TvLeastScroll : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailingEdge = offset + size
        val leadingEdge = offset
        return when {
            leadingEdge >= 0 && trailingEdge <= containerSize -> 0f
            leadingEdge < 0 && trailingEdge > containerSize -> 0f
            abs(leadingEdge) < abs(trailingEdge - containerSize) -> leadingEdge
            else -> trailingEdge - containerSize
        }
    }
}
