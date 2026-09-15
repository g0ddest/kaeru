package app.kaeru.domain.download

import app.kaeru.domain.model.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one guard that stands between a viewer and a full phone. */
class DownloadPolicyTest {

    private val fiveGb = 5L * 1024 * 1024 * 1024

    @Test
    fun `a download that lands exactly on the limit still fits`() {
        val policy = DownloadPolicy.DEFAULT
        assertEquals(fiveGb, policy.limitBytes)

        assertTrue(policy.fits(usedBytes = fiveGb - 100, estimateBytes = 100))
        assertFalse(policy.fits(usedBytes = fiveGb - 100, estimateBytes = 101))
    }

    @Test
    fun `no limit means everything fits`() {
        val unlimited = DownloadPolicy.DEFAULT.copy(limitBytes = null)

        assertTrue(unlimited.fits(usedBytes = Long.MAX_VALUE / 2, estimateBytes = Long.MAX_VALUE / 2))
    }

    @Test
    fun `a phone already over its limit takes nothing more`() {
        assertFalse(DownloadPolicy.DEFAULT.fits(usedBytes = fiveGb + 1, estimateBytes = 0))
    }

    @Test
    fun `the defaults are the ones the spec names`() {
        val policy = DownloadPolicy.DEFAULT

        assertEquals(fiveGb, policy.limitBytes)
        assertTrue(policy.wifiOnly)
        assertFalse(policy.deleteWatched)
        assertEquals(Quality.P720, policy.quality)
        assertEquals(400L * 1024 * 1024, DownloadPolicy.FALLBACK_ESTIMATE)
    }
}
