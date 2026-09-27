package app.kaeru.data.viewsync

import app.kaeru.domain.viewsync.SyncDub
import app.kaeru.domain.viewsync.SyncFailure
import app.kaeru.domain.viewsync.SyncPosition
import app.kaeru.domain.viewsync.SyncSecret
import app.kaeru.domain.viewsync.SyncTitle
import app.kaeru.domain.viewsync.SyncTitles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The `/sync` document on the wire. Written by hand rather than by a serializer, for the two things
 * a generated one would get wrong: an absent field is left out rather than sent as null — the
 * worker takes a null `dub` for a malformed one and refuses the whole batch — and an answer is read
 * as far as it can be, a title or field this build does not understand skipped, as the web and iOS
 * clients do.
 */
object SyncWire {
    private val TITLE_ID = Regex("^\\d{1,9}$")
    private val EPISODE = Regex("^\\d{1,5}$")

    /** What a POST carries: `{ "titles": { … } }`. */
    fun body(titles: SyncTitles): String = buildJsonObject {
        putJsonObject("titles") {
            for ((id, title) in titles.toSortedMap()) {
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
                            for ((episode, position) in eps.toSortedMap()) {
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

    /** Whatever of the document this build can read. A 200 without `titles` means the two disagree on the shape. */
    fun titles(text: String): SyncTitles {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        val titles = root?.get("titles") as? JsonObject ?: throw SyncFailure(SyncFailure.Kind.PARSER)
        val read = mutableMapOf<String, SyncTitle>()
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

    /** A JSON number, whole or not; a string or a boolean is not one. */
    private fun integer(value: JsonElement?): Long? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString || primitive.booleanOrNull != null) return null
        val number = primitive.doubleOrNull ?: return null
        if (!number.isFinite() || kotlin.math.abs(number) >= 9e15) return null
        return Math.round(number)
    }
}
