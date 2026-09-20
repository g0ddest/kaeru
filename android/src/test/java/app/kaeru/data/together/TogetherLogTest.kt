package app.kaeru.data.together

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The journal a release build gives up over `adb pull`: a line per event, stamped, capped, and
 * never in the way of the session that writes it.
 */
class TogetherLogTest {
    private val directory: File = Files.createTempDirectory("together-log").toFile()

    @After
    fun tearDown() {
        TogetherLog.install(null)
        directory.deleteRecursively()
    }

    @Test
    fun `every line is stamped with the time of day and kept in order`() {
        TogetherLog.install(directory)
        TogetherLog.write("connect room=AAAAAAAAAAA as=guest host=relay.test")
        TogetherLog.write("dial ok")
        TogetherLog.flush()

        val lines = File(directory, "together.log").readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0], Regex("""^\d\d:\d\d:\d\d\.\d\d\d connect room=AAAAAAAAAAA as=guest host=relay\.test$""").matches(lines[0]))
        assertTrue(lines[1].endsWith(" dial ok"))
        assertEquals(lines.joinToString("\n") + "\n", TogetherLog.read())
    }

    /**
     * For the last evening, not for all of them: past 512 KB the file is started again. Half a
     * megabyte, because at 64 KB an evening of two phones testing rolled the file over in the
     * middle of the very session that needed reading.
     */
    @Test
    fun `past its cap the journal starts again rather than growing`() {
        TogetherLog.install(directory)
        val line = "state in pos=1234567 playing=true buffering=false here=1234000/true"
        repeat(9_000) { TogetherLog.write(line) }
        TogetherLog.flush()
        val file = File(directory, "together.log")
        assertTrue(file.length() < 512 * 1024 + 2 * line.length + 32)
        assertTrue(file.length() > 0)
    }

    @Test
    fun `with nowhere to write, writing is nothing`() {
        TogetherLog.install(null)
        TogetherLog.write("dial ok")
        TogetherLog.flush()
        assertFalse(File(directory, "together.log").exists())
        assertEquals("", TogetherLog.read())
    }
}
