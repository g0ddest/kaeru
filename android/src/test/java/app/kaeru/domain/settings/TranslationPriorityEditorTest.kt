package app.kaeru.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The list the settings screen edits, without a screen.
 *
 * Every edit here is one press of a control a viewer can press twice by accident, so the rules
 * that matter are the ones at the edges: the top row's «выше», the bottom row's «ниже», a name
 * typed a second time with different capitals.
 */
class TranslationPriorityEditorTest {

    private val defaults = listOf("AniLibria", "AniDUB", "Crunchyroll")
    private val mine = listOf("JAM", "SHIZA Project", "Dream Cast")

    @Test
    fun `an empty store shows the order the app ships with`() {
        assertEquals(defaults, TranslationPriorityEditor.shown(emptyList(), defaults))
    }

    @Test
    fun `a stored order is shown instead of the defaults`() {
        assertEquals(mine, TranslationPriorityEditor.shown(mine, defaults))
    }

    @Test
    fun `moving up swaps with the row above`() {
        assertEquals(
            listOf("AniDUB", "AniLibria", "Crunchyroll"),
            TranslationPriorityEditor.moveUp(defaults, 1),
        )
    }

    @Test
    fun `the first row cannot move up`() {
        assertSame(defaults, TranslationPriorityEditor.moveUp(defaults, 0))
    }

    @Test
    fun `moving down swaps with the row below`() {
        assertEquals(
            listOf("AniLibria", "Crunchyroll", "AniDUB"),
            TranslationPriorityEditor.moveDown(defaults, 1),
        )
    }

    @Test
    fun `the last row cannot move down`() {
        assertSame(defaults, TranslationPriorityEditor.moveDown(defaults, 2))
    }

    @Test
    fun `an index outside the list moves nothing`() {
        assertSame(defaults, TranslationPriorityEditor.moveUp(defaults, 7))
        assertSame(defaults, TranslationPriorityEditor.moveUp(defaults, -1))
        assertSame(defaults, TranslationPriorityEditor.moveDown(defaults, 7))
        assertSame(defaults, TranslationPriorityEditor.moveDown(defaults, -1))
    }

    @Test
    fun `removing drops that row and keeps the rest in order`() {
        assertEquals(listOf("AniLibria", "Crunchyroll"), TranslationPriorityEditor.remove(defaults, 1))
    }

    @Test
    fun `removing an index outside the list removes nothing`() {
        assertSame(defaults, TranslationPriorityEditor.remove(defaults, 3))
        assertSame(defaults, TranslationPriorityEditor.remove(defaults, -1))
    }

    @Test
    fun `removing the only row leaves nothing stored`() {
        assertEquals(emptyList<String>(), TranslationPriorityEditor.remove(listOf("JAM"), 0))
    }

    @Test
    fun `adding puts the studio last, where a new name has the weakest claim`() {
        assertEquals(defaults + "JAM", TranslationPriorityEditor.add(defaults, "JAM"))
    }

    @Test
    fun `adding trims what was typed`() {
        assertEquals(defaults + "JAM", TranslationPriorityEditor.add(defaults, "  JAM \n"))
    }

    @Test
    fun `adding nothing adds nothing`() {
        assertSame(defaults, TranslationPriorityEditor.add(defaults, ""))
        assertSame(defaults, TranslationPriorityEditor.add(defaults, "   "))
    }

    @Test
    fun `a studio already on the list is not added twice, whatever the capitals`() {
        assertSame(defaults, TranslationPriorityEditor.add(defaults, "anilibria"))
        assertSame(defaults, TranslationPriorityEditor.add(defaults, " ANIDUB "))
    }

    @Test
    fun `the defaults are what a reset stores, which is nothing`() {
        // Reset writes an empty list; the store then answers with the defaults again, and the
        // screen can still tell "never chose" from "chose exactly these" — the difference that
        // decides whether the reset control is offered at all.
        val stored = TranslationPriorityEditor.moveUp(TranslationPriorityEditor.shown(emptyList(), defaults), 1)
        assertEquals(listOf("AniDUB", "AniLibria", "Crunchyroll"), stored)
        assertEquals(defaults, TranslationPriorityEditor.shown(emptyList(), defaults))
    }
}
