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

    // ---- where the row has to be taken before the card can be focused at all ----

    private val long = listOf(TvFocusRow("Продолжить", (1..20).toList()))

    @Test
    fun `the target carries the card's index in its row`() {
        assertEquals(
            TvRestoreTarget("Продолжить", 12, index = 11),
            tvRestoreTarget(TvFocusKey("Продолжить", 12), long),
        )
    }

    @Test
    fun `nothing remembered targets the first card of the first row`() {
        assertEquals(TvRestoreTarget("Новые серии", 10, index = 0), tvRestoreTarget(null, rows))
    }

    /** Following a card into its new row means following it to its index there, not to index 0. */
    @Test
    fun `a card that changed rows is targeted at its index in the new row`() {
        assertEquals(
            TvRestoreTarget("Продолжить", 21, index = 1),
            tvRestoreTarget(TvFocusKey("Новые серии", 21), rows),
        )
    }

    @Test
    fun `no rows means no target`() {
        assertNull(tvRestoreTarget(TvFocusKey("Продолжить", 21), emptyList()))
    }

    /**
     * The fault this answers: a card past the first screenful is not composed, so the focus request
     * that was supposed to land on it went nowhere and the screen was left with no D-pad focus.
     */
    @Test
    fun `a card past the first screenful of a row the viewer has not moved is scrolled to`() {
        assertEquals(11, tvRowScroll(index = 11, firstVisible = 0, viewport = 4))
    }

    @Test
    fun `a card inside the first screenful is already composed and the row is left alone`() {
        assertNull(tvRowScroll(index = 2, firstVisible = 0, viewport = 4))
    }

    /**
     * Coming back from a title card, the row is still where the viewer left it. Scrolling it again
     * would put the card they were on at the left edge — the artwork moving under a remote nobody
     * has touched.
     */
    @Test
    fun `a row still showing the remembered card is not moved`() {
        assertNull(tvRowScroll(index = 12, firstVisible = 10, viewport = 4))
    }

    @Test
    fun `a remembered card ahead of a scrolled row is scrolled to`() {
        assertEquals(30, tvRowScroll(index = 30, firstVisible = 10, viewport = 4))
    }

    @Test
    fun `a remembered card behind a scrolled row is scrolled back to`() {
        assertEquals(2, tvRowScroll(index = 2, firstVisible = 10, viewport = 4))
    }

    /**
     * The library asks the same two questions of one synthetic row keyed on the open tab, so a
     * grid cell twelve titles down is scrolled to exactly as a card twelve along a row is.
     */
    @Test
    fun `the library's open tab is one row of its own`() {
        val tab = listOf(TvFocusRow("WATCHING", (100..140).toList()))
        val target = tvRestoreTarget(TvFocusKey("WATCHING", 130), tab)
        assertEquals(TvRestoreTarget("WATCHING", 130, index = 30), target)
        assertEquals(30, tvRowScroll(target!!.index, firstVisible = 0, viewport = 10))
    }
}
