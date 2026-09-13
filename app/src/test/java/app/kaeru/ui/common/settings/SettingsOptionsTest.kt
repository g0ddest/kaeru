package app.kaeru.ui.common.settings

import app.kaeru.domain.model.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two single-choice rows, as lists of words and values.
 *
 * The rule worth pinning is the one that keeps the row honest: whatever is stored is on the row.
 * A row of four chips with none of them lit would be telling the viewer their setting is one of
 * these four when it is not.
 */
class SettingsOptionsTest {

    @Test
    fun `quality offers auto first and then the rungs, smallest first`() {
        assertEquals(
            listOf("Авто", "360p", "480p", "720p"),
            qualityOptions(null).map { it.label },
        )
        assertEquals(
            listOf(null, Quality.P360, Quality.P480, Quality.P720),
            qualityOptions(null).map { it.quality },
        )
    }

    @Test
    fun `a stored quality this row does not normally offer joins it, in its place`() {
        assertEquals(
            listOf("Авто", "360p", "480p", "720p", "1080p"),
            qualityOptions(Quality.P1080).map { it.label },
        )
    }

    @Test
    fun `a stored quality already on the row is not doubled`() {
        assertEquals(4, qualityOptions(Quality.P480).size)
    }

    @Test
    fun `a percentage is one word, so it cannot break across lines`() {
        // A plain space between the number and the sign is a line break waiting to happen.
        assertTrue(thresholdOptions(0.9f).all { ' ' !in it.label })
    }

    @Test
    fun `the threshold row reads as percentages, smallest first`() {
        assertEquals(
            listOf("80\u00A0%", "85\u00A0%", "90\u00A0%", "95\u00A0%"),
            thresholdOptions(0.9f).map { it.label },
        )
        assertEquals(
            listOf(0.8f, 0.85f, 0.9f, 0.95f),
            thresholdOptions(0.9f).map { it.fraction },
        )
    }

    @Test
    fun `a stored threshold the row does not offer joins it, in its place`() {
        assertEquals(
            listOf("70\u00A0%", "80\u00A0%", "85\u00A0%", "90\u00A0%", "95\u00A0%"),
            thresholdOptions(0.7f).map { it.label },
        )
        assertEquals(
            listOf("80\u00A0%", "85\u00A0%", "90\u00A0%", "95\u00A0%", "99\u00A0%"),
            thresholdOptions(0.99f).map { it.label },
        )
    }

    @Test
    fun `a stored threshold a rounding away from one on the row does not add a second chip`() {
        assertEquals(4, thresholdOptions(0.9001f).size)
        assertTrue(thresholdChosen(0.9f, 0.9001f))
        assertFalse(thresholdChosen(0.85f, 0.9f))
    }
}
