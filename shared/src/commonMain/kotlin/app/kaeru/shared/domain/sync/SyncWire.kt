package app.kaeru.shared.domain.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.abs
import kotlin.math.floor

/**
 * The `/sync` document on the wire. Written by hand rather than by a serializer, for the two things
 * a generated one would get wrong: an absent field is left out rather than sent as null — the
 * worker takes a null `dub` for a malformed one and refuses the whole batch — and an answer is read
 * as far as it can be, a title or field this build does not understand skipped.
 */
object SyncWire {
    private val TITLE_ID = Regex("^\\d{1,9}$")
    private val EPISODE = Regex("^\\d{1,5}$")

    /** What a POST carries: `{ "titles": { … } }`, titles and episodes in key order. */
    fun body(titles: SyncTitles): String = buildJsonObject {
        putJsonObject("titles") {
            for ((id, title) in titles.entries.sortedBy { it.key }) {
                putJsonObject(id) {
                    title.dub?.let { dub ->
                        putJsonObject("dub") {
                            put("id", dub.id)
                            put("title", dub.title)
                            put("at", dub.at)
                        }
                    }
                    title.eps?.let { eps ->
                        putJsonObject("eps") {
                            for ((episode, position) in eps.entries.sortedBy { it.key }) {
                                putJsonObject(episode) {
                                    put("p", position.p)
                                    put("d", position.d)
                                    put("at", position.at)
                                }
                            }
                        }
                    }
                    title.secret?.let { secret ->
                        putJsonObject("secret") {
                            put("on", secret.on)
                            put("watched", secret.watched)
                            put("at", secret.at)
                        }
                    }
                    title.gone?.let { put("gone", it) }
                }
            }
        }
    }.toString()

    /**
     * Whatever of the document this build can read; null when there is no `titles` object at all —
     * an answer that is not JSON, or a 200 whose shape the two sides disagree on.
     *
     * A title id is 1–9 digits and an episode 1–5; anything else is skipped, as is a title that is
     * not an object. A field missing a part, or with a part of the wrong type, is skipped on its own;
     * an episode map left empty reads as none.
     */
    fun titles(text: String): SyncTitles? {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        val titles = root?.get("titles") as? JsonObject ?: return null
        val read = LinkedHashMap<String, SyncTitle>()
        for ((id, value) in titles) {
            if (!TITLE_ID.matches(id)) continue
            val source = value as? JsonObject ?: continue
            read[id] = title(source)
        }
        return read
    }

    private fun title(source: JsonObject): SyncTitle {
        val dub = (source["dub"] as? JsonObject)?.let { raw ->
            val id = integer(raw["id"])
            val name = (raw["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val at = integer(raw["at"])
            if (id != null && name != null && at != null) SyncDub(id.toInt(), name, at) else null
        }
        val eps = (source["eps"] as? JsonObject)?.mapNotNull { (episode, raw) ->
            if (!EPISODE.matches(episode)) return@mapNotNull null
            val position = raw as? JsonObject ?: return@mapNotNull null
            val p = integer(position["p"])
            val d = integer(position["d"])
            val at = integer(position["at"])
            if (p != null && d != null && at != null) episode to SyncPosition(p, d, at) else null
        }?.toMap()?.takeIf { it.isNotEmpty() }
        val secret = (source["secret"] as? JsonObject)?.let { raw ->
            val on = (raw["on"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
            val watched = integer(raw["watched"])
            val at = integer(raw["at"])
            if (on != null && watched != null && at != null) {
                SyncSecret(on, watched.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), at)
            } else {
                null
            }
        }
        return SyncTitle(dub = dub, eps = eps, gone = integer(source["gone"]), secret = secret)
    }

    /**
     * A JSON number, whole or not, rounded half up as Java's `Math.round`; a string or a boolean is
     * not one, and neither is anything as large as 9e15 (past what a JavaScript number holds exactly).
     */
    private fun integer(value: JsonElement?): Long? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString || primitive.booleanOrNull != null) return null
        val number = primitive.doubleOrNull ?: return null
        if (!number.isFinite() || abs(number) >= 9e15) return null
        val whole = floor(number)
        return (if (number - whole >= 0.5) whole + 1 else whole).toLong()
    }
}
