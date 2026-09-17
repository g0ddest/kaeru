package app.kaeru.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comparison the whole feature stands on: whether the release on GitHub is newer than the app
 * doing the asking. Get it wrong in one direction and nobody is ever offered an update; get it
 * wrong in the other and every launch offers the version already installed.
 */
class VersionTest {

    @Test
    fun `a later patch is newer`() {
        assertTrue(isNewerVersion("0.3.1", "0.3.0"))
    }

    @Test
    fun `a later minor is newer`() {
        assertTrue(isNewerVersion("0.4.0", "0.3.0"))
    }

    @Test
    fun `the same version is not newer`() {
        assertFalse(isNewerVersion("0.3.0", "0.3.0"))
    }

    @Test
    fun `an earlier version is not newer`() {
        assertFalse(isNewerVersion("0.2.9", "0.3.0"))
    }

    /** The one a string comparison gets wrong, and the reason this is not a string comparison. */
    @Test
    fun `ten is newer than nine`() {
        assertTrue(isNewerVersion("0.10.0", "0.9.0"))
        assertFalse(isNewerVersion("0.9.0", "0.10.0"))
    }

    @Test
    fun `a hundred is newer than ninety-nine`() {
        assertTrue(isNewerVersion("1.100.0", "1.99.0"))
    }

    @Test
    fun `a leading v is not part of the version`() {
        assertTrue(isNewerVersion("v0.4.0", "0.3.0"))
        assertFalse(isNewerVersion("v0.3.0", "0.3.0"))
    }

    @Test
    fun `a missing component reads as zero`() {
        assertFalse(isNewerVersion("1.0", "1.0.0"))
        assertFalse(isNewerVersion("1.0.0", "1.0"))
        assertTrue(isNewerVersion("1.0.1", "1.0"))
    }

    @Test
    fun `a pre-release suffix is not part of the ordering`() {
        assertFalse(isNewerVersion("0.3.0-rc1", "0.3.0"))
        assertTrue(isNewerVersion("0.4.0-rc.1", "0.3.0"))
    }

    @Test
    fun `build metadata is not part of the ordering`() {
        assertFalse(isNewerVersion("0.3.0+ci7", "0.3.0"))
    }

    /** Never offered: an update that cannot be read is a download nobody asked for. */
    @Test
    fun `a tag with no number in it is never newer`() {
        assertFalse(isNewerVersion("latest", "0.3.0"))
        assertFalse(isNewerVersion("0.4.0", "nightly"))
    }

    @Test
    fun `parsing keeps the numbers and drops the rest`() {
        assertEquals(listOf(0, 4, 0), Version.parse("v0.4.0")?.parts)
        assertEquals(listOf(1, 2), Version.parse("1.2-beta")?.parts)
        assertEquals("0.4.0", Version.parse("v0.4.0").toString())
    }

    /** `1.x.3` is version one, not `1.0.3`: a component with no digits ends the version. */
    @Test
    fun `a non-numeric component ends the version`() {
        assertEquals(listOf(1), Version.parse("1.x.3")?.parts)
    }

    @Test
    fun `a string with no version in it parses to nothing`() {
        assertNull(Version.parse("latest"))
        assertNull(Version.parse(""))
        assertNull(Version.parse("v"))
    }

    @Test
    fun `versions sort by component and not by text`() {
        val sorted = listOf("0.10.0", "0.2.0", "0.9.1", "1.0.0")
            .mapNotNull(Version::parse)
            .sorted()
            .map(Version::toString)

        assertEquals(listOf("0.2.0", "0.9.1", "0.10.0", "1.0.0"), sorted)
    }
}
