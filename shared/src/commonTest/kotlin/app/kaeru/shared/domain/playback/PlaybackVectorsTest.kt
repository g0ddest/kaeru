package app.kaeru.shared.domain.playback

import app.kaeru.shared.TestFixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.float
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
 * `playback-vectors.json`: the cases every implementation of the player rules runs — this module
 * here, the web client and Swift from the same file. A case that fails names its group and name.
 */
class PlaybackVectorsTest {
    private val vectors = Json.parseToJsonElement(TestFixtures.text("playback-vectors.json")).jsonObject

    private fun cases(group: String): List<JsonObject> =
        vectors.getValue(group).jsonArray.map { it.jsonObject }.also { assertTrue(it.isNotEmpty(), "no cases in $group") }

    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.bool(key: String) = getValue(key).jsonPrimitive.boolean
    private fun JsonObject.float(key: String) = getValue(key).jsonPrimitive.float
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.intOrNull(key: String) = get(key)?.jsonPrimitive?.intOrNull
    private fun JsonObject.ints(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.int }
    private fun JsonObject.intMap(key: String) =
        getValue(key).jsonObject.entries.associate { (k, v) -> k.toInt() to v.jsonPrimitive.int }
    private val JsonObject.label get() = string("name")

    private fun track(json: JsonElement) = json.jsonObject.let {
        TranslationCandidate(
            id = it.int("id"),
            title = it.string("title"),
            kind = when (val kind = it.string("kind")) {
                "voice" -> TrackKind.VOICE
                "subtitles" -> TrackKind.SUBTITLES
                else -> error("Unknown kind $kind")
            },
            episodesCount = it.intOrNull("episodes"),
        )
    }

    private fun JsonObject.tracks() = getValue("tracks").jsonArray.map(::track)
    private fun JsonObject.preferred() = getValue("preferred").jsonArray.map { it.jsonPrimitive.content }

    private fun interval(json: JsonElement?): SkipInterval? =
        if (json == null || json is JsonNull) null else json.jsonObject.let { SkipInterval(it.long("startMs"), it.long("endMs")) }

    private fun JsonObject.marks() = SkipMarks(interval(get("opening")), interval(get("ending")))

    @Test
    fun constantsMatch() {
        val c = vectors.getValue("constants").jsonObject
        assertEquals(TranslationRanker.DEFAULT_STUDIOS, c.getValue("defaultStudios").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(TranslationUsage.OFTEN_CHOSEN_FROM, c.int("oftenChosenFrom"))
        assertEquals(EpisodeProgressRules.STARTED_MS, c.long("startedMs"))
        assertEquals(EpisodeProgressRules.STARTED_FRACTION, c.float("startedFraction"))
        assertEquals(EpisodeProgressRules.DEFAULT_WATCHED_THRESHOLD, c.float("defaultWatchedThreshold"))
        assertEquals(NextEpisodeRules.NEXT_EPISODE_LEAD_MS, c.long("nextEpisodeLeadMs"))
        assertEquals(NextEpisodeRules.AUTOPLAY_COUNTDOWN_SEC, c.int("autoplayCountdownSec"))
        assertEquals(SkipRules.BUTTON_WINDOW_MS, c.long("skipButtonWindowMs"))
        assertEquals(SkipRules.OPENING_STARTS_WITHIN_MS, c.long("openingStartsWithinMs"))
        assertEquals(SkipRules.ENDING_ENDS_WITHIN_MS, c.long("endingEndsWithinMs"))
        assertEquals(SkipRules.MIN_LENGTH_MS, c.long("skipMinLengthMs"))
        assertEquals(SkipRules.MAX_LENGTH_MS, c.long("skipMaxLengthMs"))
        assertEquals(SkipRules.SEEK_SETTLE_MS, c.long("seekSettleMs"))
    }

    @Test
    fun translationOrder() = cases("translationOrder").forEach {
        val rememberedId = it.intOrNull("rememberedId")
        assertEquals(it.ints("expected"), TranslationRanker.order(it.tracks(), it.preferred(), rememberedId, it.intMap("usage")), it.label)
    }

    @Test
    fun substitutionOrder() = cases("substitutionOrder").forEach {
        val actual = TranslationRanker.substitutionOrder(
            it.tracks(), it.int("chosenId"), it.preferred(), it.intOrNull("rememberedId"), it.intMap("usage"),
        )
        assertEquals(it.ints("expected"), actual, it.label)
    }

    @Test
    fun carriesEpisode() = cases("carriesEpisode").forEach {
        val listed = it.getValue("listedEpisodes").takeIf { json -> json !is JsonNull }?.jsonArray?.map { e -> e.jsonPrimitive.int }?.toSet()
        val actual = TranslationRanker.carriesEpisode(it.int("episode"), listed, it.int("season"), it.intOrNull("episodesCount"))
        assertEquals(it.getValue("expected").jsonPrimitive.booleanOrNull, actual, it.label)
        assertEquals(actual == false, TranslationRanker.lacksEpisode(it.int("episode"), listed, it.int("season"), it.intOrNull("episodesCount")), it.label)
    }

    @Test
    fun translationUsage() = cases("translationUsage").forEach {
        val remembered = it.getValue("remembered").jsonArray.map { row ->
            row.jsonObject.let { r -> RememberedTrack(r.int("animeId"), r.intOrNull("translationId")) }
        }
        assertEquals(it.intMap("expected"), TranslationUsage.of(remembered), it.label)
    }

    @Test
    fun oftenChosen() = cases("oftenChosen").forEach {
        assertEquals(it.bool("expected"), TranslationUsage.oftenChosen(it.intMap("usage"), it.int("id")), it.label)
    }

    @Test
    fun substitutionNotice() = cases("substitutionNotice").forEach {
        assertEquals(
            it.string("expected"),
            TranslationRanker.substitutionNotice(it.string("askedFor"), it.int("episode"), it.string("playing")),
            it.label,
        )
    }

    @Test
    fun started() = cases("started").forEach {
        assertEquals(it.bool("expected"), EpisodeProgressRules.started(it.long("positionMs"), it.long("durationMs")), it.label)
    }

    @Test
    fun watched() = cases("watched").forEach {
        val actual = EpisodeProgressRules.watched(it.long("positionMs"), it.long("durationMs"), it.float("threshold"))
        assertEquals(it.bool("expected"), actual, it.label)
    }

    @Test
    fun unfinished() = cases("unfinished").forEach {
        val actual = EpisodeProgressRules.unfinished(it.long("positionMs"), it.long("durationMs"), it.float("threshold"))
        assertEquals(it.bool("expected"), actual, it.label)
    }

    @Test
    fun resumePosition() = cases("resumePosition").forEach {
        val actual = EpisodeProgressRules.resumePosition(it.long("positionMs"), it.long("durationMs"), it.float("threshold"))
        assertEquals(it.long("expected"), actual, it.label)
    }

    @Test
    fun availableEpisodes() = cases("availableEpisodes").forEach {
        val status = AiringStatus.valueOf(it.string("status").uppercase())
        assertEquals(it.int("expected"), NextEpisodeRules.availableEpisodes(status, it.int("episodes"), it.int("episodesAired")), it.label)
    }

    @Test
    fun hasNextEpisode() = cases("hasNextEpisode").forEach {
        assertEquals(it.bool("expected"), NextEpisodeRules.hasNextEpisode(it.int("episode"), it.int("availableEpisodes")), it.label)
    }

    @Test
    fun nextEpisodeDue() = cases("nextEpisodeDue").forEach {
        val actual = NextEpisodeRules.nextEpisodeDue(it.long("positionMs"), it.long("durationMs"), it.bool("ended"))
        assertEquals(it.bool("expected"), actual, it.label)
    }

    @Test
    fun countdownSeconds() = cases("countdownSeconds").forEach {
        val actual = NextEpisodeRules.countdownSeconds(it.long("positionMs"), it.long("durationMs"), it.bool("ended"))
        assertEquals(it.intOrNull("expected"), actual, it.label)
        assertEquals(actual != null, NextEpisodeRules.countdownDue(it.long("positionMs"), it.long("durationMs"), it.bool("ended")), it.label)
    }

    @Test
    fun skipAccept() = cases("skipAccept").forEach {
        val expected = it.getValue("expected").jsonObject.marks()
        assertEquals(expected, SkipRules.accept(it.marks(), it.long("durationMs")), it.label)
    }

    @Test
    fun skipOffer() = cases("skipOffer").forEach {
        val expected = it.getValue("expected").takeIf { json -> json !is JsonNull }?.jsonObject?.let { offer ->
            SkipOffer(SkipKind.valueOf(offer.string("kind").uppercase()), SkipInterval(offer.long("startMs"), offer.long("endMs")))
        }
        assertEquals(expected, SkipRules.offer(it.marks(), it.long("positionMs"), it.long("durationMs")), it.label)
    }

    @Test
    fun endingSkipDue() = cases("endingSkipDue").forEach {
        assertEquals(it.bool("expected"), SkipRules.endingSkipDue(it.marks(), it.long("positionMs"), it.long("durationMs")), it.label)
    }

    @Test
    fun insideEnding() = cases("insideEnding").forEach {
        assertEquals(it.bool("expected"), SkipRules.insideEnding(it.marks(), it.long("positionMs"), it.long("durationMs")), it.label)
    }

    @Test
    fun offerCompletion() = cases("offerCompletion").forEach {
        val actual = CompletionRules.offerCompletion(
            it.int("episode"),
            it.int("announcedEpisodes"),
            it.getValue("nextEpisodeAtMs").jsonPrimitive.longOrNull,
            it.long("nowMs"),
        )
        assertEquals(it.bool("expected"), actual, it.label)
    }
}
