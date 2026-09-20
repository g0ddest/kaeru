package app.kaeru.shared.data.kodik

import kotlin.io.encoding.Base64
import io.ktor.http.decodeURLQueryComponent
import kotlinx.serialization.json.*

data class KodikTranslationOption(
    val id: Int,
    val title: String,
    val type: TranslationType,
    val episodesCount: Int?,
    val mediaId: String,
    val mediaHash: String,
)

enum class TranslationType { VOICE, SUBTITLES }

internal data class KodikEpisodeOption(
    val number: Int,
    val mediaId: String,
    val mediaHash: String,
    val title: String?,
)

internal data class KodikPlayerPage(
    val domain: String,
    val dSign: String,
    val pd: String,
    val pdSign: String,
    val ref: String,
    val refSign: String,
    val currentType: String,
    val currentHash: String,
    val currentId: String,
    /**
     * The track this page is itself showing, as the page names it in its own script.
     *
     * Every player page carries these, chooser or no chooser, which is what makes a film with a
     * single voice playable: it has no translations box to read, but it still says which voice it
     * is. Null when a page names neither — nothing is guessed from a page that will not say.
     */
    val currentTranslationId: Int? = null,
    val currentTranslationTitle: String? = null,
    val translations: List<KodikTranslationOption>,
    val episodes: List<KodikEpisodeOption>,
    val ftorPath: String = "/ftor",
    /**
     * The URL this page was fetched from, filled in by [KodikClient] and
     * null for a page parsed straight from a string. Kodik checks `Referer` on
     * the `/ftor` call, and it has to be the page the call came from.
     */
    val sourceUrl: String? = null,
)

/**
 * Parses the Kodik player HTML page (`get-player` response) into the signing
 * parameters, current episode, and the translation/episode option lists needed
 * to resolve HLS links via `/ftor`.
 *
 * Network-free and pure: all inputs are plain strings, no I/O.
 */
internal object KodikHtmlParser {

    private val optionRegex = Regex("<option\\b([\\s\\S]*?)>([\\s\\S]*?)</option>", RegexOption.IGNORE_CASE)
    private val attrRegex = Regex("([a-zA-Z][a-zA-Z0-9-]*)\\s*=\\s*\"([^\"]*)\"")
    private val episodeCountInTextRegex = Regex("""\((\d+)\s*эп\.\)""")
    private val trailingEpisodeCountRegex = Regex("""\s*\(\d+\s*эп\.\)\s*$""")
    private val atobRegex = Regex("""atob\(["']([^"']*)["']\)""")
    private val tokenRegex = Regex("""token\s*=\s*"([a-z0-9]+)"""")
    private val currentTranslationIdRegex = Regex("""\btranslationId\s*=\s*(\d+)""")
    private val currentTranslationTitleRegex = Regex("""\btranslationTitle\s*=\s*"([^"]*)"""")
    private val entityRegex = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);")
    /** Serial and movie players wrap the same `<option>` shape in differently named divs. */
    private val TRANSLATION_BOX_CLASSES = listOf("serial-translations-box", "movie-translations-box")
    private val namedEntities = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
    )

    /** Throws KodikError.ParserBroken(step) naming the first missing piece. */
    fun parse(html: String): KodikPlayerPage {
        val params = Regex("""\burlParams\s*=\s*'([^']*)'""").find(html)?.groupValues?.get(1)
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        fun signing(name: String, key: String = name): String {
            val direct = Regex("""\b${Regex.escape(name)}\s*=\s*"([^"]*)"""").find(html)?.groupValues?.get(1)
            if (direct != null) return direct
            val value = (params?.get(key) as? JsonPrimitive)?.contentOrNull
                ?: throw KodikError.ParserBroken(name)
            return if (key == "ref") value.decodeURLQueryComponent(plusIsSpace = false) else value
        }
        val domain = signing("domain", "d")
        val dSign = signing("d_sign")
        val pd = signing("pd")
        val pdSign = signing("pd_sign")
        val ref = signing("ref")
        val refSign = signing("ref_sign")

        val currentType = require(html, Regex("""vInfo\.type\s*=\s*'([^']*)'"""), "vInfo.type")
        val currentHash = require(html, Regex("""vInfo\.hash\s*=\s*'([^']*)'"""), "vInfo.hash")
        val currentId = require(html, Regex("""vInfo\.id\s*=\s*'([^']*)'"""), "vInfo.id")

        // A movie page (vInfo.type != "seria") has neither an episode list nor a
        // serial translations box; a serial page missing either of them is broken.
        val isSerial = currentType == "seria"

        val translations = parseTranslations(html)
        if (translations.isEmpty() && isSerial) throw KodikError.ParserBroken("translations")

        val episodes = parseEpisodes(html)
        if (episodes.isEmpty() && isSerial) throw KodikError.ParserBroken("episodes")

        return KodikPlayerPage(
            domain = domain,
            dSign = dSign,
            pd = pd,
            pdSign = pdSign,
            ref = ref,
            refSign = refSign,
            currentType = currentType,
            currentHash = currentHash,
            currentId = currentId,
            currentTranslationId = currentTranslationIdRegex.find(html)?.groupValues?.get(1)?.toIntOrNull(),
            currentTranslationTitle = currentTranslationTitleRegex.find(html)
                ?.groupValues?.get(1)
                ?.let { decodeHtmlEntities(it) }
                ?.takeIf { it.isNotBlank() },
            translations = translations,
            episodes = episodes,
            ftorPath = extractFtorPath(html),
        )
    }

    /** Regex `token="([a-z0-9]+)"` over the add-players.min.js contents. */
    fun extractPublicToken(addPlayersJs: String): String? =
        tokenRegex.find(addPlayersJs)?.groupValues?.get(1)

    private fun require(html: String, regex: Regex, step: String): String =
        regex.find(html)?.groupValues?.get(1) ?: throw KodikError.ParserBroken(step)

    private fun boxSelectContent(html: String, boxClass: String): String? {
        val escaped = Regex.escape(boxClass)
        // Match boxClass as a whole class-attribute token (delimited by the
        // attribute's edges or whitespace), not merely as a substring — a div
        // with class "serial-series-box-extra" must NOT match "serial-series-box".
        val classTokenRegex = Regex("(?:^|\\s)$escaped(?:\\s|$)")
        val divOpenRegex = Regex("<div\\s+class=\"([^\"]*)\"[^>]*>")
        val selectRegex = Regex("<select>([\\s\\S]*?)</select>")

        for (divMatch in divOpenRegex.findAll(html)) {
            if (!classTokenRegex.containsMatchIn(divMatch.groupValues[1])) continue
            val afterDiv = html.substring(divMatch.range.last + 1)
            val selectMatch = selectRegex.find(afterDiv) ?: continue
            return selectMatch.groupValues[1]
        }
        return null
    }

    /**
     * Decodes the handful of HTML entities that appear in Kodik's option text
     * and `data-title` attributes (e.g. `&amp;` in translator names): the five
     * named entities, decimal `&#NNN;`, and hex `&#xHH;`. Pure and Android-free.
     */
    internal fun decodeHtmlEntities(text: String): String {
        if (!text.contains('&')) return text
        return entityRegex.replace(text) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x", ignoreCase = true) ->
                    body.substring(2).toIntOrNull(16)?.let { codePointOrNull(it) } ?: match.value
                body.startsWith("#") ->
                    body.substring(1).toIntOrNull()?.let { codePointOrNull(it) } ?: match.value
                else -> namedEntities[body.lowercase()] ?: match.value
            }
        }
    }

    /** Reject out-of-range values and lone surrogates; encode astral code points as a UTF-16 pair. */
    private fun codePointOrNull(codePoint: Int): String? {
        if (codePoint !in 0..0x10FFFF || codePoint in 0xD800..0xDFFF) return null
        if (codePoint <= 0xFFFF) return codePoint.toChar().toString()
        val offset = codePoint - 0x10000
        return "${(0xD800 + (offset shr 10)).toChar()}${(0xDC00 + (offset and 0x3FF)).toChar()}"
    }

    private fun attributesOf(tag: String): Map<String, String> =
        attrRegex.findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }

    private fun parseTranslations(html: String): List<KodikTranslationOption> {
        val content = TRANSLATION_BOX_CLASSES.firstNotNullOfOrNull { boxSelectContent(html, it) }
            ?: return emptyList()
        return optionRegex.findAll(content).mapNotNull { match ->
            val attrs = attributesOf(match.groupValues[1])
            val text = match.groupValues[2].trim()
            val id = attrs["data-id"]?.toIntOrNull() ?: return@mapNotNull null
            val mediaId = attrs["data-media-id"] ?: return@mapNotNull null
            val mediaHash = attrs["data-media-hash"] ?: return@mapNotNull null
            val type = when (attrs["data-translation-type"]) {
                "voice" -> TranslationType.VOICE
                "subtitles" -> TranslationType.SUBTITLES
                else -> return@mapNotNull null
            }
            val episodesCount = attrs["data-episode-count"]?.toIntOrNull()
                ?: episodeCountInTextRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()
            val title = decodeHtmlEntities(attrs["data-title"] ?: text.replace(trailingEpisodeCountRegex, ""))
            KodikTranslationOption(id, title, type, episodesCount, mediaId, mediaHash)
        }.toList()
    }

    private fun parseEpisodes(html: String): List<KodikEpisodeOption> {
        val content = boxSelectContent(html, "serial-series-box") ?: return emptyList()
        return optionRegex.findAll(content).mapNotNull { match ->
            val attrs = attributesOf(match.groupValues[1])
            val number = attrs["value"]?.toIntOrNull() ?: return@mapNotNull null
            val mediaId = attrs["data-id"] ?: return@mapNotNull null
            val mediaHash = attrs["data-hash"] ?: return@mapNotNull null
            KodikEpisodeOption(number, mediaId, mediaHash, attrs["data-title"]?.let { decodeHtmlEntities(it) })
        }.toList()
    }

    /**
     * Some pages point `/ftor` at a different path via an inline `atob("...")`
     * call. We decode any such literal (without loading the external script)
     * and use it when it decodes to an absolute path; otherwise default to
     * `/ftor`.
     */
    private fun extractFtorPath(html: String): String {
        atobRegex.findAll(html).forEach { match ->
            val decoded = runCatching {
                Base64.decode(match.groupValues[1]).decodeToString(throwOnInvalidSequence = true)
            }.getOrNull()
            if (decoded != null && decoded.startsWith("/")) return decoded
        }
        return "/ftor"
    }
}
