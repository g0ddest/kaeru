package app.kaeru.ui.common.design

import app.kaeru.domain.model.Quality
import org.junit.Assert.assertEquals
import org.junit.Test

private const val KB = 1024L
private const val MB = 1024L * 1024
private const val GB = 1024L * 1024 * 1024

/**
 * How a size reads on a screen of downloads.
 *
 * Two rules, and both come from the same place: a viewer deciding whether one more episode fits.
 * Megabytes are whole numbers, because the tenth of a megabyte nobody can act on; gigabytes carry
 * one decimal, because the difference between 4 GB and 4,9 GB is a whole episode. The separator is
 * a comma, which is how a decimal is written in Russian.
 */
class FormatBytesTest {

    @Test
    fun `nothing downloaded reads as zero bytes`() {
        assertEquals("0 Б", formatBytes(0))
    }

    @Test
    fun `a negative count is still nothing rather than a minus sign`() {
        assertEquals("0 Б", formatBytes(-1))
    }

    @Test
    fun `bytes below a kilobyte are bytes`() {
        assertEquals("512 Б", formatBytes(512))
    }

    @Test
    fun `a few kilobytes are whole kilobytes`() {
        assertEquals("4 КБ", formatBytes(4 * KB))
    }

    @Test
    fun `an episode reads in whole megabytes`() {
        assertEquals("320 МБ", formatBytes(320 * MB))
    }

    @Test
    fun `a megabyte and a half loses the half, because nobody acts on it`() {
        assertEquals("1 МБ", formatBytes(MB + MB / 2))
    }

    @Test
    fun `a gigabyte and a bit carries one decimal, with a comma`() {
        assertEquals("1,2 ГБ", formatBytes((1.2 * GB).toLong()))
    }

    @Test
    fun `a round gigabyte still carries its decimal, so a line reads in one format`() {
        assertEquals("5,0 ГБ", formatBytes(5 * GB))
    }

    @Test
    fun `just under a gigabyte is still megabytes`() {
        assertEquals("1023 МБ", formatBytes(GB - MB))
    }

    // --- the words on the download policy chips ------------------------------------------------

    @Test
    fun `a height with no choice behind it says what it takes instead`() {
        assertEquals("Как при просмотре", downloadQualityLabel(null))
    }

    @Test
    fun `a chosen height is the height`() {
        assertEquals("720p", downloadQualityLabel(Quality.P720))
    }

    @Test
    fun `no limit is said in words rather than as a number`() {
        assertEquals("Без лимита", downloadLimitLabel(null))
    }

    @Test
    fun `a limit is round gigabytes, without the decimal a usage line carries`() {
        assertEquals("5 ГБ", downloadLimitLabel(5 * GB))
        assertEquals("20 ГБ", downloadLimitLabel(20 * GB))
    }
}
