package app.kaeru.ui.common.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import androidx.tv.material3.Typography as TvTypography

/**
 * The type scale, checked against the font file it is drawn in.
 *
 * Every other test of the scale takes [MANROPE_BOX] on trust — a number in `Type.kt` that says
 * Manrope needs 1.366 of the font size to draw itself. This one opens `manrope.ttf` and works it
 * out, so the constant cannot drift away from the file and a new family cannot be dropped in under
 * a scale built for the old one.
 *
 * It is a plain JVM test on purpose. The obvious way to check a line box is to draw a line and
 * measure it, and under Robolectric that proves nothing: its stand-in face reports the same metrics
 * at 11sp as at 52sp, and Compose never hands a paragraph less box than the font asks for, so the
 * measurement comes out right whatever the scale declares. Measured against a scale whose
 * `titleSmall` had been mutated to a line height equal to its font size, a drawing test passed.
 * The font file is the only thing on this machine that knows what Manrope actually needs.
 *
 * Two tables say it, and both are read. `hhea` carries the ascender, descender and line gap a
 * renderer lays lines out with; `OS/2` carries the typographic trio that is meant to agree with it.
 * In this file they do, and the test says so — a family where they disagree is one to look at
 * rather than to trust.
 */
class ManropeLineBoxTest {

    private val font: File = listOf(
        // Unit tests run with the module directory as their working directory; the fallbacks are
        // for a runner that picks the project root or the source root instead.
        "src/main/res/font/manrope.ttf",
        "app/src/main/res/font/manrope.ttf",
        "../app/src/main/res/font/manrope.ttf",
    ).map(::File).firstOrNull { it.isFile } ?: error("manrope.ttf not found from ${File("").absolutePath}")

    /** The tables this test reads, by their four-character tag and their offset in the file. */
    private val tables: Map<String, Int> = run {
        val bytes = font.readBytes()
        // A TrueType collection points at its first font; a plain font starts its directory at 0.
        val directory = if (String(bytes, 0, 4, Charsets.ISO_8859_1) == "ttcf") bytes.u32(12) else 0
        val count = bytes.u16(directory + 4)
        (0 until count).associate { index ->
            val record = directory + 12 + 16 * index
            String(bytes, record, 4, Charsets.ISO_8859_1) to bytes.u32(record + 8)
        }
    }

    private val bytes: ByteArray = font.readBytes()

    private fun table(tag: String): Int = tables[tag] ?: error("$tag missing from ${font.name}")

    /** Design units to the em, the denominator every metric below is a fraction of. */
    private val unitsPerEm: Int = bytes.u16(table("head") + 18)

    /** What a renderer lays a line out with: above the baseline, below it, and between lines. */
    private val hheaBox: Double =
        (bytes.s16(table("hhea") + 4) - bytes.s16(table("hhea") + 6) + bytes.s16(table("hhea") + 8))
            .toDouble() / unitsPerEm

    /** The same three from `OS/2`, which is meant to agree. */
    private val typoBox: Double =
        (bytes.s16(table("OS/2") + 68) - bytes.s16(table("OS/2") + 70) + bytes.s16(table("OS/2") + 72))
            .toDouble() / unitsPerEm

    private fun Typography.styles(): Map<String, TextStyle> = mapOf(
        "displayLarge" to displayLarge, "displayMedium" to displayMedium, "displaySmall" to displaySmall,
        "headlineLarge" to headlineLarge, "headlineMedium" to headlineMedium, "headlineSmall" to headlineSmall,
        "titleLarge" to titleLarge, "titleMedium" to titleMedium, "titleSmall" to titleSmall,
        "bodyLarge" to bodyLarge, "bodyMedium" to bodyMedium, "bodySmall" to bodySmall,
        "labelLarge" to labelLarge, "labelMedium" to labelMedium, "labelSmall" to labelSmall,
    )

    private fun TvTypography.styles(): Map<String, TextStyle> = mapOf(
        "displayLarge" to displayLarge, "displayMedium" to displayMedium, "displaySmall" to displaySmall,
        "headlineLarge" to headlineLarge, "headlineMedium" to headlineMedium, "headlineSmall" to headlineSmall,
        "titleLarge" to titleLarge, "titleMedium" to titleMedium, "titleSmall" to titleSmall,
        "bodyLarge" to bodyLarge, "bodyMedium" to bodyMedium, "bodySmall" to bodySmall,
        "labelLarge" to labelLarge, "labelMedium" to labelMedium, "labelSmall" to labelSmall,
    )

    private fun everyScale(): Map<String, Map<String, TextStyle>> = mapOf(
        "phone" to KaeruTypography.styles(),
        "television" to KaeruTvTypography.styles(),
        "television material" to KaeruTvMaterialTypography.styles(),
    )

    @Test
    fun `the font's two sets of vertical metrics agree with each other`() {
        assertEquals(
            "hhea says $hheaBox of the em and OS/2 says $typoBox; a family where they disagree " +
                "needs a decision about which one the scale is built on",
            hheaBox,
            typoBox,
            0.0001,
        )
    }

    @Test
    fun `no style asks for a line box smaller than the one the font draws in`() {
        everyScale().forEach { (scale, styles) ->
            styles.forEach { (name, style) ->
                val ratio = style.lineHeight.value.toDouble() / style.fontSize.value.toDouble()
                assertTrue(
                    "$scale $name is ${style.fontSize.value}/${style.lineHeight.value}, a ratio of " +
                        "$ratio, and ${font.name} draws itself in $hheaBox",
                    ratio >= hheaBox,
                )
            }
        }
    }

    /**
     * And the constants the rest of the scale is checked against are the file's, not a memory of
     * it. Without this the two could drift: a family swapped for a taller one would leave
     * `TypeScaleTest` certifying a scale that clips, exactly as the 1.2 and 1.1 bars did before.
     */
    @Test
    fun `the constants the scale is held to come from the font file`() {
        assertEquals("MANROPE_BOX is the box ${font.name} declares", hheaBox, MANROPE_BOX, 0.0005)
        assertTrue(
            "the scale is held to $MIN_LINE_RATIO, which has to clear the font's own $hheaBox",
            MIN_LINE_RATIO >= hheaBox,
        )
    }
}

/** Big-endian unsigned 16-bit, the width of most of an sfnt table directory. */
private fun ByteArray.u16(at: Int): Int = ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

/** Big-endian signed 16-bit: every vertical metric in `hhea` and `OS/2` is one of these. */
private fun ByteArray.s16(at: Int): Int = u16(at).toShort().toInt()

private fun ByteArray.u32(at: Int): Int = (u16(at) shl 16) or u16(at + 2)
