package app.kaeru.shared.domain.sync

import app.kaeru.shared.TestFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `sync-vectors.json`: the cases every implementation of the sync rules runs — this module here,
 * the web client, the worker and Swift from the same file. A case that fails names its group and name.
 *
 * Titles in the file are read by a strict reader of their own rather than [SyncWire], so a case for
 * [SyncMerge] does not depend on the codec it is not about.
 */
class SyncVectorsTest {
    private val vectors = Json.parseToJsonElement(TestFixtures.text("sync-vectors.json")).jsonObject

    private fun cases(group: String): List<JsonObject> =
        vectors.getValue(group).jsonArray.map { it.jsonObject }.also { assertTrue(it.isNotEmpty(), "no cases in $group") }

    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.boolean
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.intOrNull(key: String) = getValue(key).jsonPrimitive.intOrNull
    private fun JsonObject.isNull(key: String) = getValue(key) is JsonNull
    private val JsonObject.label get() = string("name")

    private fun title(json: JsonElement): SyncTitle = json.jsonObject.let { o ->
        SyncTitle(
            dub = o["dub"]?.jsonObject?.let { SyncDub(it.int("id"), it.string("title"), it.long("at")) },
            eps = o["eps"]?.jsonObject?.mapValues { (_, p) -> p.jsonObject.let { SyncPosition(it.long("p"), it.long("d"), it.long("at")) } },
            gone = o["gone"]?.jsonPrimitive?.long,
            secret = o["secret"]?.jsonObject?.let(::secret),
        )
    }

    private fun secret(json: JsonObject) = SyncSecret(json.bool("on"), json.int("watched"), json.long("at"))

    private fun titles(json: JsonElement): SyncTitles = json.jsonObject.mapValues { (_, t) -> title(t) }

    private fun intKeys(json: JsonElement) = json.jsonObject.mapKeys { (k, _) -> k.toInt() }

    private fun dubs(json: JsonElement) = intKeys(json).mapValues { (_, d) ->
        d.jsonObject.let { RememberedDub(it.int("id"), it["title"]?.takeUnless { t -> t is JsonNull }?.jsonPrimitive?.content) }
    }

    private fun stamps(json: JsonElement) = intKeys(json).mapValues { (_, at) -> at.jsonPrimitive.long }

    private fun position(json: JsonElement) = json.jsonObject.let {
        EpisodePosition(it.int("animeId"), it.int("episode"), it.long("positionMs"), it.long("durationMs"), it.long("at"))
    }

    private fun local(json: JsonElement) = json.jsonObject.let { o ->
        LocalSyncState(
            positions = o.getValue("positions").jsonArray.map(::position),
            dubs = dubs(o.getValue("dubs")),
            dubStamps = stamps(o.getValue("dubStamps")),
            secrets = intKeys(o.getValue("secrets")).mapValues { (_, s) -> secret(s.jsonObject) },
            // Optional in the file: a case that does not name it knows no title's length.
            announcedEpisodes = o["announcedEpisodes"]?.let { intKeys(it).mapValues { (_, n) -> n.jsonPrimitive.int } }.orEmpty(),
        )
    }

    @Test
    fun constantsMatch() {
        val c = vectors.getValue("constants").jsonObject
        assertEquals(SyncRules.EPISODES_PER_TITLE, c.int("episodesPerTitle"))
        assertEquals(SyncRules.MAX_DUB_TITLE, c.int("maxDubTitle"))
    }

    @Test
    fun merge() = cases("merge").forEach {
        val base = if (it.isNull("base")) null else title(it.getValue("base"))
        assertEquals(title(it.getValue("expected")), SyncMerge.merge(base, title(it.getValue("patch"))), it.label)
    }

    @Test
    fun without() = cases("without").forEach {
        val actual = SyncMerge.without(title(it.getValue("title")), title(it.getValue("covered")))
        assertEquals(title(it.getValue("expected")), actual, it.label)
    }

    @Test
    fun newer() = cases("newer").forEach {
        val actual = SyncRules.newer(titles(it.getValue("remote")), local(it.getValue("local")))
        val expected = it.getValue("expected").jsonObject
        assertEquals(
            expected.getValue("positions").jsonArray.map(::position).sortedWith(compareBy({ p -> p.animeId }, { p -> p.episode })),
            actual.positions.sortedWith(compareBy({ p -> p.animeId }, { p -> p.episode })),
            "${it.label}: positions",
        )
        assertEquals(stamps(expected.getValue("tombstones")), actual.tombstones, "${it.label}: tombstones")
        assertEquals(dubs(expected.getValue("dubs")), actual.dubs, "${it.label}: dubs")
        assertEquals(stamps(expected.getValue("dubStamps")), actual.dubStamps, "${it.label}: dubStamps")
        val secrets = expected.getValue("secrets").jsonArray.map { s ->
            s.jsonObject.let { o -> TitleSecret(o.int("animeId"), o.bool("on"), o.int("watched"), o.long("at")) }
        }
        assertEquals(secrets.sortedBy { s -> s.animeId }, actual.secrets.sortedBy { s -> s.animeId }, "${it.label}: secrets")
    }

    @Test
    fun seed() = cases("seed").forEach {
        val finished = it.getValue("finished").jsonArray.map { id -> id.jsonPrimitive.int }.toSet()
        val actual = SyncRules.seed(local(it.getValue("local")), finished, titles(it.getValue("remote")))
        assertEquals(titles(it.getValue("expected")), actual, it.label)
    }

    @Test
    fun wireParse() = cases("wireParse").forEach {
        val expected = if (it.isNull("expected")) null else titles(it.getValue("expected"))
        assertEquals(expected, SyncWire.titles(it.string("text")), it.label)
    }

    @Test
    fun wireSerialize() = cases("wireSerialize").forEach {
        val body = SyncWire.body(titles(it.getValue("titles")))
        assertEquals(it.string("text"), body, it.label)
        assertEquals(it.getValue("expected"), Json.parseToJsonElement(body), it.label)
        // What goes out comes back as it went.
        assertEquals(titles(it.getValue("titles")), SyncWire.titles(body), "${it.label}: read back")
    }

    @Test
    fun secretFinished() = cases("secretFinished").forEach {
        val actual = SecretRules.finished(
            it.bool("released"),
            it.int("announcedEpisodes"),
            it.int("watched"),
            it.getValue("nextEpisodeAtMs").jsonPrimitive.longOrNull,
            it.long("nowMs"),
        )
        assertEquals(it.bool("expected"), actual, it.label)
    }

    @Test
    fun secretTurnedOn() = cases("secretTurnedOn").forEach {
        assertEquals(it.int("expected"), SecretRules.watchedWhenTurnedOn(it.intOrNull("counted")), it.label)
    }

    @Test
    fun secretTurnedOff() = cases("secretTurnedOff").forEach {
        assertEquals(it.intOrNull("expected"), SecretRules.episodesToSendWhenTurnedOff(it.int("watched"), it.intOrNull("counted")), it.label)
    }

    @Test
    fun secretAfterMark() = cases("secretAfterMark").forEach {
        assertEquals(it.intOrNull("expected"), SecretRules.watchedAfterMark(it.int("watched"), it.int("episodes")), it.label)
    }
}
