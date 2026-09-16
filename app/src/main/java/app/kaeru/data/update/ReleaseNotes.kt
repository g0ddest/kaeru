package app.kaeru.data.update

/**
 * A release body, as a screen with no markdown renderer on it can show.
 *
 * The app has no markdown engine and is not getting one for this: a release note is a handful of
 * lines, and the whole of what markup does for it is make a list look like a list. So the markup
 * is removed rather than rendered, and the two things that carry meaning without it — the line
 * breaks and the bullets — are kept. `## Что нового` becomes «Что нового», `- исправлен плеер`
 * becomes «• исправлен плеер», and `[выпуск](https://…)` becomes «выпуск», because a URL nobody
 * can tap is noise in the middle of a sentence.
 *
 * Fenced code is left exactly as written. It is the one part of a release note where the
 * characters are the content, and stripping asterisks out of a diff would corrupt it.
 */

private val HTML_COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
private val FENCE = Regex("""^\s{0,3}(```|~~~)""")
private val HEADING = Regex("""^\s{0,3}#{1,6}\s*""")
private val RULE = Regex("""^\s{0,3}([-*_])([ \t]*\1){2,}[ \t]*$""")
private val QUOTE = Regex("""^\s{0,3}>\s?""")
private val BULLET = Regex("""^([ \t]*)[-*+][ \t]+""")
private val SETEXT = Regex("""^\s{0,3}(=+|-{2,})\s*$""")

private val IMAGE = Regex("""!\[([^\]]*)]\([^)]*\)""")
private val LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
private val REFERENCE_LINK = Regex("""\[([^\]]*)]\[[^\]]*]""")
private val AUTOLINK = Regex("""<((?:https?|mailto):[^>\s]+)>""")
private val HTML_TAG = Regex("""</?[A-Za-z][^>]*>""")
private val BOLD_STAR = Regex("""\*\*(.+?)\*\*""")
private val BOLD_UNDERSCORE = Regex("""__(.+?)__""")
private val ITALIC_STAR = Regex("""(?<![*\w])\*(?!\s)(.+?)(?<!\s)\*(?![*\w])""")
private val ITALIC_UNDERSCORE = Regex("""(?<![_\w])_(?!\s)(.+?)(?<!\s)_(?![_\w])""")
private val STRIKETHROUGH = Regex("""~~(.+?)~~""")
private val INLINE_CODE = Regex("""`([^`]+)`""")
private val ESCAPE = Regex("""\\([\\`*_{}\[\]()#+\-.!~>|])""")
private val BLANK_RUN = Regex("""\n{3,}""")

/** The gap a removed image or link leaves in the middle of a sentence. Indentation is left alone. */
private val DOUBLE_SPACE = Regex("""(\S)[ \t]{2,}""")

/** The bullet every list marker becomes, so `-`, `*` and `+` read as one kind of thing. */
private const val BULLET_GLYPH = "• "

/** A body GitHub gave as null or as nothing at all. */
private const val NO_NOTES = ""

fun releaseNotes(body: String?): String {
    if (body.isNullOrBlank()) return NO_NOTES
    val withoutComments = body.replace("\r\n", "\n").replace('\r', '\n').replace(HTML_COMMENT, "")
    var fenced = false
    val lines = withoutComments.split('\n').mapNotNull { raw ->
        when {
            FENCE.containsMatchIn(raw) -> {
                fenced = !fenced
                // The fence itself is markup; what it wrapped is not.
                null
            }
            fenced -> raw.trimEnd()
            RULE.matches(raw) || SETEXT.matches(raw) -> null
            else -> plainLine(raw)
        }
    }
    return lines.joinToString("\n").replace(BLANK_RUN, "\n\n").trim()
}

private fun plainLine(raw: String): String {
    val unquoted = raw.replace(QUOTE, "")
    val unheaded = unquoted.replace(HEADING, "")
    val bulleted = unheaded.replace(BULLET, "$1$BULLET_GLYPH")
    return inline(bulleted).replace(DOUBLE_SPACE, "$1 ").trimEnd()
}

/**
 * The markup that sits inside a line.
 *
 * Order matters in two places: an image is a link with a `!` in front of it, so it goes first or
 * its alt text survives as a stray word; and the two-character emphases go before the
 * one-character ones, or `**x**` leaves a pair of asterisks behind.
 */
private fun inline(line: String): String = line
    .replace(IMAGE, "")
    .replace(LINK, "$1")
    .replace(REFERENCE_LINK, "$1")
    .replace(AUTOLINK, "$1")
    .replace(HTML_TAG, "")
    .replace(BOLD_STAR, "$1")
    .replace(BOLD_UNDERSCORE, "$1")
    .replace(STRIKETHROUGH, "$1")
    .replace(ITALIC_STAR, "$1")
    .replace(ITALIC_UNDERSCORE, "$1")
    .replace(INLINE_CODE, "$1")
    .replace(ESCAPE, "$1")
