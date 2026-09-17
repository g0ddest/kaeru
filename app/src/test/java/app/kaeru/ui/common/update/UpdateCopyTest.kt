package app.kaeru.ui.common.update

import app.kaeru.domain.update.UpdateRelease
import app.kaeru.ui.common.design.updateAvailableText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** Every line the page builds out of a release, read as a value rather than off a device. */
class UpdateCopyTest {
    private val utc = ZoneOffset.UTC

    private fun release(
        publishedAt: Instant? = Instant.parse("2026-09-16T08:00:00Z"),
        sizeBytes: Long = 31_457_280,
    ) = UpdateRelease(
        version = "0.4.0",
        publishedAt = publishedAt,
        notes = "",
        apkUrl = "https://example.test/a.apk",
        apkName = "a.apk",
        sizeBytes = sizeBytes,
    )

    @Test
    fun `the headline names the version`() {
        assertEquals("Доступна версия 0.4.0", availableLine("0.4.0"))
    }

    /** The row on the home screen is a signpost to this page and must word it identically. */
    @Test
    fun `the home row and the screen say the same thing`() {
        assertEquals(updateAvailableText("0.4.0"), availableLine("0.4.0"))
    }

    @Test
    fun `the installed line reads like the settings page`() {
        assertEquals("Kaeru 0.3.0", installedLine("0.3.0"))
    }

    @Test
    fun `a release says when it came out and what it weighs`() {
        assertEquals("16 сентября 2026, 30 МБ", releaseLine(release(), utc))
    }

    @Test
    fun `a release with no date says only its size`() {
        assertEquals("30 МБ", releaseLine(release(publishedAt = null), utc))
    }

    @Test
    fun `a release with neither says nothing rather than punctuation`() {
        assertNull(releaseLine(release(publishedAt = null, sizeBytes = 0), utc))
    }

    @Test
    fun `the check date is a date and not a relative day`() {
        assertEquals(
            "Проверено 16 сентября 2026",
            checkedLine(Instant.parse("2026-09-16T08:00:00Z"), utc),
        )
    }

    @Test
    fun `a device that has never checked says nothing about when it did`() {
        assertNull(checkedLine(null, utc))
    }

    @Test
    fun `the progress line counts in the units the rest of the app uses`() {
        assertEquals("12 МБ из 30 МБ", downloadedLine(12L * 1024 * 1024, 31_457_280))
    }

    @Test
    fun `a transfer with no known total promises no finish line`() {
        assertEquals("12 МБ", downloadedLine(12L * 1024 * 1024, 0))
    }
}
