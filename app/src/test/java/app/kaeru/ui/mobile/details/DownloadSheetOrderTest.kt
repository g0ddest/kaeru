package app.kaeru.ui.mobile.details

import app.kaeru.ui.common.details.DownloadChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order the «Скачать серии» sheet lists episodes in.
 *
 * It exists because of what a real phone showed: a viewer on episode eleven opened the sheet, saw
 * five watched episodes with empty checkboxes, and a button that said «Скачать 1 серию (~400 МБ)»
 * about a tick two screens further down. The summary and the ticks have to be readable together.
 */
class DownloadSheetOrderTest {

    private fun choices(vararg pairs: Pair<Int, Boolean>) =
        pairs.map { (episode, watched) -> DownloadChoice(episode, watched) }

    @Test
    fun `what the sheet opens ticked is what it lists first`() {
        val (unwatched, watched) = sheetSections(
            choices(5 to true, 6 to true, 7 to false, 8 to false),
        )

        assertEquals(listOf(7, 8), unwatched.map { it.episode })
        assertEquals(listOf(5, 6), watched.map { it.episode })
    }

    @Test
    fun `each block keeps episode order, because that is the order they are watched in`() {
        val (unwatched, watched) = sheetSections(
            choices(1 to true, 2 to false, 3 to true, 4 to false, 5 to false),
        )

        assertEquals(listOf(2, 4, 5), unwatched.map { it.episode })
        assertEquals(listOf(1, 3), watched.map { it.episode })
    }

    @Test
    fun `a season nobody has started is one block and no heading`() {
        val (unwatched, watched) = sheetSections(choices(1 to false, 2 to false))

        assertEquals(listOf(1, 2), unwatched.map { it.episode })
        assertTrue(watched.isEmpty())
    }

    @Test
    fun `a rewatch is the other way round, and also one block`() {
        val (unwatched, watched) = sheetSections(choices(1 to true, 2 to true))

        assertTrue(unwatched.isEmpty())
        assertEquals(listOf(1, 2), watched.map { it.episode })
    }

    @Test
    fun `nothing on offer is nothing in either block`() {
        val (unwatched, watched) = sheetSections(emptyList())

        assertTrue(unwatched.isEmpty())
        assertTrue(watched.isEmpty())
    }
}
