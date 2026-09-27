package app.kaeru.shared.domain.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The `/sync` wire; the full set of cases is in `sync-vectors.json`. */
class SyncWireTest {

    @Test
    fun aBatchCarriesMillisecondsAndLeavesOutWhatIsAbsent() {
        val body = SyncWire.body(
            mapOf(
                "5" to SyncTitle(
                    dub = SyncDub(610, "AniLibria.TV", 1_790_000_000_000),
                    eps = mapOf("3" to SyncPosition(861_000, 1_440_000, 1_790_000_000_001)),
                ),
                "6" to SyncTitle(gone = 1_790_000_000_002),
            ),
        )
        assertEquals(
            """{"titles":{"5":{"dub":{"id":610,"title":"AniLibria.TV","at":1790000000000},""" +
                """"eps":{"3":{"p":861000,"d":1440000,"at":1790000000001}}},"6":{"gone":1790000000002}}}""",
            body,
        )
    }

    @Test
    fun anAnswerIsReadAsFarAsItCanBeTheRestSkipped() {
        val titles = SyncWire.titles(
            """{"titles":{
                "5":{"dub":{"id":610,"title":"A","at":10},"eps":{"1":{"p":5,"d":9,"at":11},"x":{"p":1,"d":2,"at":3},"2":{"p":"5","d":9,"at":1}},"secret":{"on":true,"watched":3,"at":4}},
                "6":{"gone":12,"dub":{"id":1,"at":2}},
                "abc":{"gone":1},
                "7":"nonsense"
            }}""",
        )!!
        assertEquals(setOf("5", "6"), titles.keys)
        assertEquals(SyncDub(610, "A", 10), titles.getValue("5").dub)
        assertEquals(mapOf("1" to SyncPosition(5, 9, 11)), titles.getValue("5").eps)
        assertEquals(12L, titles.getValue("6").gone)
        assertNull(titles.getValue("6").dub)
        assertEquals(SyncSecret(true, 3, 4), titles.getValue("5").secret)
    }

    @Test
    fun secretGoesOutAndComesBackAsTheWorkerWritesIt() {
        val body = SyncWire.body(mapOf("5" to SyncTitle(secret = SyncSecret(on = true, watched = 3, at = 4))))
        assertEquals("""{"titles":{"5":{"secret":{"on":true,"watched":3,"at":4}}}}""", body)
        assertEquals(SyncSecret(true, 3, 4), SyncWire.titles(body)!!.getValue("5").secret)
        val broken = SyncWire.titles("""{"titles":{"5":{"secret":{"on":"yes","watched":3,"at":4}}}}""")!!
        assertNull(broken.getValue("5").secret)
    }

    @Test
    fun anAnswerWithoutTitlesIsUnreadable() {
        assertNull(SyncWire.titles("""{"error":"x"}"""))
        assertNull(SyncWire.titles("not json"))
    }
}
