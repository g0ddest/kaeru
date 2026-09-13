package app.kaeru.domain.discover

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class SeasonTest {
    private val utc = ZoneOffset.UTC

    private fun at(iso: String, zone: ZoneId = utc) = Season.current(Instant.parse(iso), zone)

    // --- current -------------------------------------------------------------------------------

    @Test
    fun `january through march is winter`() {
        assertEquals(Season(SeasonKind.WINTER, 2026), at("2026-01-01T00:00:00Z"))
        assertEquals(Season(SeasonKind.WINTER, 2026), at("2026-03-31T23:59:59Z"))
    }

    @Test
    fun `april through june is spring`() {
        assertEquals(Season(SeasonKind.SPRING, 2026), at("2026-04-01T00:00:00Z"))
        assertEquals(Season(SeasonKind.SPRING, 2026), at("2026-06-30T12:00:00Z"))
    }

    @Test
    fun `july through september is summer`() {
        assertEquals(Season(SeasonKind.SUMMER, 2026), at("2026-07-01T00:00:00Z"))
        assertEquals(Season(SeasonKind.SUMMER, 2026), at("2026-09-13T20:00:00Z"))
    }

    @Test
    fun `october through december is fall`() {
        assertEquals(Season(SeasonKind.FALL, 2026), at("2026-10-01T00:00:00Z"))
        assertEquals(Season(SeasonKind.FALL, 2026), at("2026-12-31T23:00:00Z"))
    }

    @Test
    fun `the season is the one the viewer is living in, not the one in UTC`() {
        // 22:00 on 31 December in London is already 11:00 on 1 January in Auckland.
        val newYear = Instant.parse("2026-12-31T22:00:00Z")
        assertEquals(Season(SeasonKind.FALL, 2026), Season.current(newYear, utc))
        assertEquals(Season(SeasonKind.WINTER, 2027), Season.current(newYear, ZoneId.of("Pacific/Auckland")))
    }

    // --- previous and next ---------------------------------------------------------------------

    @Test
    fun `previous walks back through the year`() {
        assertEquals(Season(SeasonKind.SPRING, 2026), Season(SeasonKind.SUMMER, 2026).previous())
        assertEquals(Season(SeasonKind.WINTER, 2026), Season(SeasonKind.SPRING, 2026).previous())
    }

    @Test
    fun `previous of winter is the autumn of the year before`() {
        assertEquals(Season(SeasonKind.FALL, 2025), Season(SeasonKind.WINTER, 2026).previous())
    }

    @Test
    fun `next walks forward through the year`() {
        assertEquals(Season(SeasonKind.SUMMER, 2026), Season(SeasonKind.SPRING, 2026).next())
        assertEquals(Season(SeasonKind.FALL, 2026), Season(SeasonKind.SUMMER, 2026).next())
    }

    @Test
    fun `next of autumn is the winter of the year after`() {
        assertEquals(Season(SeasonKind.WINTER, 2027), Season(SeasonKind.FALL, 2026).next())
    }

    @Test
    fun `stepping one way and back returns where it started`() {
        val summer = Season(SeasonKind.SUMMER, 2026)
        assertEquals(summer, summer.next().previous())
        assertEquals(summer, summer.previous().next())
    }

    // --- the wire ------------------------------------------------------------------------------

    @Test
    fun `apiValue is what Shikimori calls the season`() {
        assertEquals("winter_2026", Season(SeasonKind.WINTER, 2026).apiValue)
        assertEquals("spring_2026", Season(SeasonKind.SPRING, 2026).apiValue)
        assertEquals("summer_2026", Season(SeasonKind.SUMMER, 2026).apiValue)
        assertEquals("fall_2026", Season(SeasonKind.FALL, 2026).apiValue)
    }

    // --- the three choices ---------------------------------------------------------------------

    @Test
    fun `the choices are the season before, the one now, and the one coming`() {
        val summer = Season(SeasonKind.SUMMER, 2026)
        assertEquals(
            listOf(Season(SeasonKind.SPRING, 2026), summer, Season(SeasonKind.FALL, 2026)),
            seasonChoices(summer),
        )
    }

    @Test
    fun `the choices cross the new year without losing a season`() {
        assertEquals(
            listOf(Season(SeasonKind.FALL, 2025), Season(SeasonKind.WINTER, 2026), Season(SeasonKind.SPRING, 2026)),
            seasonChoices(Season(SeasonKind.WINTER, 2026)),
        )
    }
}
