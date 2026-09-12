package app.kaeru.data.kodik

import java.util.Base64

data class KodikTranslationOption(
    val id: Int,
    val title: String,
    val type: TranslationType,
    val episodesCount: Int?,
    val mediaId: String,
    val mediaHash: String,
)

enum class TranslationType { VOICE, SUBTITLES }

data class KodikEpisodeOption(
    val number: Int,
    val mediaId: String,
    val mediaHash: String,
    val title: String?,
)

data class KodikPlayerPage(
    val domain: String,
    val dSign: String,
    val pd: String,
    val pdSign: String,
    val ref: String,
    val refSign: String,
    val currentType: String,
    val currentHash: String,
    val currentId: String,
    val translations: List<KodikTranslationOption>,
    val episodes: List<KodikEpisodeOption>,
    val ftorPath: String = "/ftor",
)

/**
 * Parses the Kodik player HTML page (`get-player` response) into the signing
 * parameters, current episode, and the translation/episode option lists needed
 * to resolve HLS links via `/ftor`.
 *
 * Network-free and pure: all inputs are plain strings, no I/O.
 */
object KodikHtmlParser {

    private val optionRegex = Regex("<option\\b([\\s\\S]*?)>([\\s\\S]*?)</option>", RegexOption.IGNORE_CASE)
    private val attrRegex = Regex("([a-zA-Z][a-zA-Z0-9-]*)\\s*=\\s*\"([^\"]*)\"")
    private val episodeCountInTextRegex = Regex("""\((\d+)\s*эп\.\)""")
    private val trailingEpisodeCountRegex = Regex("""\s*\(\d+\s*эп\.\)\s*$""")
    private val atobRegex = Regex("""atob\("([^"]*)"\)""")
    private val tokenRegex = Regex("""token\s*=\s*"([a-z0-9]+)"""")

    /** Throws KodikError.ParserBroken(step) naming the first missing piece. */
    fun parse(html: String): KodikPlayerPage {
        val domain = require(html, Regex("""\bdomain\s*=\s*"([^"]*)""""), "domain")
        val dSign = require(html, Regex("""\bd_sign\s*=\s*"([^"]*)""""), "d_sign")
        val pd = require(html, Regex("""\bpd\s*=\s*"([^"]*)""""), "pd")
        val pdSign = require(html, Regex("""\bpd_sign\s*=\s*"([^"]*)""""), "pd_sign")
        val ref = require(html, Regex("""\bref\s*=\s*"([^"]*)""""), "ref")
        val refSign = require(html, Regex("""\bref_sign\s*=\s*"([^"]*)""""), "ref_sign")

        val currentType = require(html, Regex("""vInfo\.type\s*=\s*'([^']*)'"""), "vInfo.type")
        val currentHash = require(html, Regex("""vInfo\.hash\s*=\s*'([^']*)'"""), "vInfo.hash")
        val currentId = require(html, Regex("""vInfo\.id\s*=\s*'([^']*)'"""), "vInfo.id")

        val translations = parseTranslations(html)
        if (translations.isEmpty()) throw KodikError.ParserBroken("translations")

        val episodes = parseEpisodes(html)
        if (episodes.isEmpty()) throw KodikError.ParserBroken("episodes")

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
        val boxRegex = Regex("<div\\s+class=\"$boxClass\">[\\s\\S]*?<select>([\\s\\S]*?)</select>")
        return boxRegex.find(html)?.groupValues?.get(1)
    }

    private fun attributesOf(tag: String): Map<String, String> =
        attrRegex.findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }

    private fun parseTranslations(html: String): List<KodikTranslationOption> {
        val content = boxSelectContent(html, "serial-translations-box") ?: return emptyList()
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
            val title = attrs["data-title"] ?: text.replace(trailingEpisodeCountRegex, "")
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
            KodikEpisodeOption(number, mediaId, mediaHash, attrs["data-title"])
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
                String(Base64.getDecoder().decode(match.groupValues[1]))
            }.getOrNull()
            if (decoded != null && decoded.startsWith("/")) return decoded
        }
        return "/ftor"
    }
}
