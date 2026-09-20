package app.kaeru.domain.discover

import java.time.Instant
import java.time.ZoneId

/**
 * The four broadcast seasons anime is scheduled in, in the order the year runs through them.
 *
 * The order of the constants is the order of the calendar, which is what [Season.previous] and
 * [Season.next] step through; nothing else may be inserted between them.
 */
enum class SeasonKind { WINTER, SPRING, SUMMER, FALL }

/**
 * One broadcast season: which quarter of which year.
 *
 * Anime schedules by quarter rather than by month, so «what is airing now» is a season and not a
 * date range. The mapping is the one the industry and Shikimori both use — January starts winter,
 * April spring, July summer, October autumn — and it is fixed, so this whole type is arithmetic
 * with no catalogue behind it and no suspending anywhere.
 *
 * There is no display name here. A season's name is a user-facing string and belongs to the UI
 * layer with the rest of them; [apiValue] is the wire format, which is a protocol detail.
 */
data class Season(val kind: SeasonKind, val year: Int) {
    /** `summer_2026` — the value Shikimori's `season` filter takes. */
    val apiValue: String get() = "${kind.name.lowercase()}_$year"

    /** The season before this one, crossing into the previous year at winter. */
    fun previous(): Season = when (kind) {
        SeasonKind.WINTER -> Season(SeasonKind.FALL, year - 1)
        else -> Season(SeasonKind.entries[kind.ordinal - 1], year)
    }

    /** The season after this one, crossing into the next year at autumn. */
    fun next(): Season = when (kind) {
        SeasonKind.FALL -> Season(SeasonKind.WINTER, year + 1)
        else -> Season(SeasonKind.entries[kind.ordinal + 1], year)
    }

    companion object {
        /**
         * The season the viewer is living in.
         *
         * [zone] is an argument rather than a lookup because the answer differs across the date
         * line on four evenings a year: 22:00 on 31 December in London is already the new year's
         * winter in Auckland, and a viewer there should be offered their own season.
         */
        fun current(now: Instant, zone: ZoneId): Season {
            val date = now.atZone(zone).toLocalDate()
            return Season(SeasonKind.entries[(date.monthValue - 1) / 3], date.year)
        }
    }
}

/**
 * The three seasons worth offering: the one that just finished, the one airing, the one coming.
 *
 * Three rather than a scrollable history, because the row answers «what is popular around now»
 * and a viewer who wants 2014 is looking for a catalogue, not a home screen. [current] sits in
 * the middle so the chips read as a timeline left to right.
 */
fun seasonChoices(current: Season): List<Season> = listOf(current.previous(), current, current.next())
