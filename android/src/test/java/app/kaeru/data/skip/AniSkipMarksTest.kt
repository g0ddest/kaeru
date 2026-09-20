package app.kaeru.data.skip

import app.kaeru.data.local.SkipMarksDao
import app.kaeru.data.local.SkipMarksEntity
import app.kaeru.domain.playback.SkipInterval
import app.kaeru.domain.playback.SkipMarks
import app.kaeru.test.MutableClock
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * The marks as the player asks for them: once per episode, out of the table when it is fresh
 * enough, and never in a way that can stop an episode playing.
 */
class AniSkipMarksTest {

    private val now = Instant.parse("2026-09-19T10:00:00Z")
    private val clock = MutableClock(now)
    private val api = FakeAniSkipApi()
    private val dao = FakeSkipMarksDao()
    private val marks = AniSkipMarks(api, dao, clock)

    /** Frieren, episode 1, as AniSkip actually answered during the spike. */
    private val frieren = """
        {"found":true,"results":[
          {"interval":{"startTime":3.0,"endTime":93.0},"skipType":"op","skipId":"a","episodeLength":1560.0},
          {"interval":{"startTime":1460.0,"endTime":1560.0},"skipType":"ed","skipId":"b","episodeLength":1560.0}
        ],"message":"","statusCode":200}
    """.trimIndent()

    @Test
    fun `an opening and an ending come back as milliseconds of this episode`() = runTest {
        api.answer = frieren

        val found = marks.marks(100, 1, 1_560_000)

        assertEquals(SkipMarks(SkipInterval(3_000, 93_000), SkipInterval(1_460_000, 1_560_000)), found)
    }

    @Test
    fun `the length the engine reports is what is asked for`() = runTest {
        api.answer = frieren

        marks.marks(100, 7, 1_470_000)

        assertEquals(listOf("100/7 op,ed,mixed-op,mixed-ed @1470"), api.asked)
    }

    @Test
    fun `an ending marked at the start of the episode never leaves this layer`() = runTest {
        api.answer = """
            {"found":true,"results":[
              {"interval":{"startTime":5.0,"endTime":95.0},"skipType":"ed","skipId":"c","episodeLength":1446.0}
            ]}
        """.trimIndent()

        assertEquals(SkipMarks.NONE, marks.marks(100, 1, 1_446_000))
    }

    @Test
    fun `nothing marked for this length is nothing to show`() = runTest {
        api.answer = """{"found":false,"results":[],"statusCode":404}"""

        assertEquals(SkipMarks.NONE, marks.marks(100, 1, 1_446_000))
    }

    @Test
    fun `mixed openings and endings count as the real thing`() = runTest {
        api.answer = """
            {"found":true,"results":[
              {"interval":{"startTime":3.0,"endTime":93.0},"skipType":"mixed-op","skipId":"d","episodeLength":1560.0},
              {"interval":{"startTime":1460.0,"endTime":1560.0},"skipType":"mixed-ed","skipId":"e","episodeLength":1560.0}
            ]}
        """.trimIndent()

        val found = marks.marks(100, 1, 1_560_000)

        assertEquals(SkipMarks(SkipInterval(3_000, 93_000), SkipInterval(1_460_000, 1_560_000)), found)
    }

    // --- the cache -------------------------------------------------------------------------------

    @Test
    fun `the second time the same episode is played nothing is asked`() = runTest {
        api.answer = frieren
        marks.marks(100, 1, 1_560_000)
        clock.advance(Duration.ofDays(6))

        val again = marks.marks(100, 1, 1_560_000)

        assertEquals(1, api.asked.size)
        assertEquals(SkipMarks(SkipInterval(3_000, 93_000), SkipInterval(1_460_000, 1_560_000)), again)
    }

    @Test
    fun `an episode nobody marked is not asked about again for a week either`() = runTest {
        api.answer = """{"found":false,"results":[]}"""
        marks.marks(100, 1, 1_560_000)
        clock.advance(Duration.ofDays(6))

        assertEquals(SkipMarks.NONE, marks.marks(100, 1, 1_560_000))
        assertEquals(1, api.asked.size)
    }

    @Test
    fun `a week later it is worth asking again`() = runTest {
        api.answer = """{"found":false,"results":[]}"""
        marks.marks(100, 1, 1_560_000)
        clock.advance(Duration.ofDays(8))
        api.answer = frieren

        val again = marks.marks(100, 1, 1_560_000)

        assertEquals(2, api.asked.size)
        assertEquals(SkipInterval(3_000, 93_000), again.opening)
    }

    @Test
    fun `the same episode in another voice is another length and another row`() = runTest {
        api.answer = frieren
        marks.marks(100, 1, 1_560_000)

        api.answer = """{"found":false,"results":[]}"""
        assertEquals(SkipMarks.NONE, marks.marks(100, 1, 1_446_000))
        assertEquals(2, api.asked.size)
        assertEquals(2, dao.rows.size)
    }

    // --- and what a bad day does -----------------------------------------------------------------

    @Test
    fun `a request that fails shows nothing and says nothing`() = runTest {
        api.failure = IOException("no network")

        assertEquals(SkipMarks.NONE, marks.marks(100, 1, 1_560_000))
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `what was cached before the network went is what plays offline`() = runTest {
        api.answer = frieren
        marks.marks(100, 1, 1_560_000)
        clock.advance(Duration.ofDays(30))
        api.failure = IOException("no network")

        val offline = marks.marks(100, 1, 1_560_000)

        assertEquals(SkipInterval(3_000, 93_000), offline.opening)
    }

    @Test
    fun `an episode with no length yet is not asked about at all`() = runTest {
        assertEquals(SkipMarks.NONE, marks.marks(100, 1, 0))
        assertTrue(api.asked.isEmpty())
    }

    // --- a refusal is an answer -------------------------------------------------------------------

    @Test
    fun `a refusal is how this service says nobody marked this one`() = runTest {
        api.failure = notFound()

        assertEquals(SkipMarks.NONE, marks.marks(100, 1, 1_560_000))

        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `an episode the service refused is not asked about again for a week`() = runTest {
        api.failure = notFound()
        marks.marks(100, 1, 1_560_000)
        clock.advance(Duration.ofDays(6))

        assertEquals(SkipMarks.NONE, marks.marks(100, 1, 1_560_000))
        assertEquals(1, api.asked.size)
    }

    @Test
    fun `a week after a refusal it is worth asking again`() = runTest {
        api.failure = notFound()
        marks.marks(100, 1, 1_560_000)
        clock.advance(Duration.ofDays(8))
        api.failure = null
        api.answer = frieren

        assertEquals(SkipInterval(3_000, 93_000), marks.marks(100, 1, 1_560_000).opening)
        assertEquals(2, api.asked.size)
    }

    @Test
    fun `a service that is merely broken is not an answer to remember`() = runTest {
        api.failure = HttpException(Response.error<AniSkipResponse>(500, "".toResponseBody(JSON)))

        assertEquals(SkipMarks.NONE, marks.marks(100, 1, 1_560_000))
        assertTrue(dao.rows.isEmpty())
    }

    // --- one file, one row ------------------------------------------------------------------------

    @Test
    fun `the length is asked for in whole seconds, rounded rather than cut`() = runTest {
        marks.marks(100, 1, 1_559_700)

        assertEquals(listOf("100/1 op,ed,mixed-op,mixed-ed @1560"), api.asked)
    }

    @Test
    fun `a length that moved by a second is the same file and the same row`() = runTest {
        api.answer = frieren
        marks.marks(100, 1, 1_560_000)

        val again = marks.marks(100, 1, 1_558_400)

        assertEquals(1, api.asked.size)
        assertEquals(1, dao.rows.size)
        assertEquals(SkipInterval(3_000, 93_000), again.opening)
    }

    @Test
    fun `a length three seconds out is another file and another question`() = runTest {
        api.answer = frieren
        marks.marks(100, 1, 1_560_000)

        marks.marks(100, 1, 1_556_000)

        assertEquals(2, api.asked.size)
    }

    /** A 404 exactly as Retrofit raises it: the body decodes, and the call still throws. */
    private fun notFound() =
        HttpException(Response.error<AniSkipResponse>(404, """{"found":false}""".toResponseBody(JSON)))
}

private val JSON = "application/json".toMediaType()

/** Answers with whatever the test pasted in, and remembers what it was asked. */
private class FakeAniSkipApi : AniSkipApi {
    var answer: String = """{"found":false,"results":[]}"""
    var failure: Throwable? = null
    val asked = mutableListOf<String>()

    override suspend fun skipTimes(
        malId: Int,
        episode: Int,
        types: List<String>,
        episodeLength: Int,
    ): AniSkipResponse {
        asked += "$malId/$episode ${types.joinToString(",")} @$episodeLength"
        failure?.let { throw it }
        return aniSkipJson().decodeFromString(AniSkipResponse.serializer(), answer)
    }
}

private class FakeSkipMarksDao : SkipMarksDao {
    val rows = mutableMapOf<Triple<Int, Int, Int>, SkipMarksEntity>()

    override suspend fun find(animeId: Int, episode: Int, lengthSec: Int, toleranceSec: Int): SkipMarksEntity? =
        rows.values
            .filter { it.animeId == animeId && it.episode == episode }
            .filter { kotlin.math.abs(it.lengthSec - lengthSec) <= toleranceSec }
            .minByOrNull { kotlin.math.abs(it.lengthSec - lengthSec) }

    override suspend fun upsert(marks: SkipMarksEntity) {
        rows[Triple(marks.animeId, marks.episode, marks.lengthSec)] = marks
    }
}
