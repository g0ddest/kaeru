package app.kaeru.ui.common.settings

import app.kaeru.domain.model.Quality
import kotlin.math.abs
import kotlin.math.roundToInt

/** One chip in the quality row: the word on it and the setting behind it. */
data class QualityOption(val label: String, val quality: Quality?)

/** One chip in the watched-threshold row. */
data class ThresholdOption(val label: String, val fraction: Float)

private const val AUTO = "Авто"

/**
 * The rungs worth offering on a phone. 1080p is left off on purpose: on a screen this size it
 * costs mobile data and buys nothing the eye can find, and a viewer who wants it can still be
 * shown it — see [qualityOptions].
 */
private val QualityRungs = listOf(Quality.P360, Quality.P480, Quality.P720)

private val Thresholds = listOf(0.8f, 0.85f, 0.9f, 0.95f)

/** Two values that round to the same whole percent are the same choice as far as a chip row goes. */
private const val SamePercent = 0.005f

/**
 * «Авто» and the rungs, with whatever is stored guaranteed to be among them.
 *
 * [current] joins the row when it is not one of the rungs the phone normally offers, in its place
 * by height. A row where nothing is lit would be claiming the setting is one of four values when
 * it is a fifth, and the viewer would have no way to see or keep what they had.
 */
fun qualityOptions(current: Quality?): List<QualityOption> {
    val rungs = (QualityRungs + listOfNotNull(current)).distinct().sortedBy { it.height }
    return listOf(QualityOption(AUTO, null)) + rungs.map { QualityOption("${it.height}p", it) }
}

/** The four shares of an episode, plus whatever is stored if it is none of them. See [qualityOptions]. */
fun thresholdOptions(current: Float): List<ThresholdOption> {
    val extra = current.takeIf { value -> Thresholds.none { thresholdChosen(it, value) } }
    return (Thresholds + listOfNotNull(extra)).sorted().map { ThresholdOption(percent(it), it) }
}

/** Whether a chip is the one lit. Tolerant of a rounding, which a stored float can carry. */
fun thresholdChosen(option: Float, current: Float): Boolean = abs(option - current) < SamePercent

/** A non-breaking space before the sign: «80 %» is one word in Russian and never wraps. */
private fun percent(fraction: Float): String = "${(fraction * 100).roundToInt()}\u00A0%"

/**
 * A stable key per studio row, for a list that draws its rows lazily.
 *
 * The name itself is the key, and that is the point: moving «AniDUB» up has to carry the row — and
 * the remote sitting on it — up with it, which keying by position would not do. Keying by name has
 * one hazard, a lazy list throwing on a key it has already seen, so a repeat gets a numbered key
 * rather than a duplicate. Nothing in the app writes a list with a name twice (`shown` hands over
 * either the viewer's own order or the app's, and the editor refuses a name already in the list),
 * so this is a guard against a stored list from an older build, not an expected shape.
 */
fun studioKeys(studios: List<String>): List<String> {
    val used = HashSet<String>(studios.size)
    return studios.map { name ->
        var key = name
        var seen = 1
        while (!used.add(key)) {
            seen++
            key = "$name#$seen"
        }
        key
    }
}
