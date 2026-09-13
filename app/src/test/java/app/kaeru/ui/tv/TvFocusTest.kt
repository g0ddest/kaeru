package app.kaeru.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvFocusTest {

    private val rows = listOf(
        TvFocusRow("Новые серии", listOf(10, 11, 12)),
        TvFocusRow("Продолжить", listOf(20, 21)),
    )

    @Test
    fun `nothing remembered opens on the first card of the first row`() {
        assertEquals(TvFocusKey("Новые серии", 10), tvRestoreFocus(null, rows))
    }

    @Test
    fun `a remembered card still in its row keeps the focus`() {
        val remembered = TvFocusKey("Продолжить", 21)
        assertEquals(remembered, tvRestoreFocus(remembered, rows))
    }

    /**
     * Watching an episode moves a title out of «Новые серии» and into «Продолжить». Coming back to
     * a card that has changed rows should land on the card, not on the top of the screen.
     */
    @Test
    fun `a remembered card that changed rows is followed to its new row`() {
        val remembered = TvFocusKey("Новые серии", 21)
        assertEquals(TvFocusKey("Продолжить", 21), tvRestoreFocus(remembered, rows))
    }

    @Test
    fun `a remembered card that left the feed falls back to the first card`() {
        assertEquals(TvFocusKey("Новые серии", 10), tvRestoreFocus(TvFocusKey("Продолжить", 99), rows))
    }

    @Test
    fun `no rows means nothing to focus`() {
        assertNull(tvRestoreFocus(TvFocusKey("Продолжить", 21), emptyList()))
    }

    @Test
    fun `a row with no cards is stepped over`() {
        val withEmpty = listOf(TvFocusRow("Скоро", emptyList())) + rows
        assertEquals(TvFocusKey("Новые серии", 10), tvRestoreFocus(null, withEmpty))
    }
}
