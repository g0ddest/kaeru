package app.kaeru.data.download

import org.junit.Assert.assertEquals
import org.junit.Test

/** Every line the download notification can show, with no Android and no media3 in the way. */
class DownloadNotificationTextTest {

    private fun item(title: String? = "Тайтл", episode: Int = 7, percent: Int = 42) =
        DownloadNotificationText.Item(title = title, episode = episode, percent = percent)

    @Test
    fun `one download names the title, the episode and how far it has got`() {
        assertEquals("Тайтл, 7 серия, 42 %", DownloadNotificationText.progress(listOf(item())))
    }

    @Test
    fun `several downloads are counted, not listed`() {
        val many = listOf(item(episode = 1), item(episode = 2), item(episode = 3))

        assertEquals("Загружается 3 серии", DownloadNotificationText.progress(many))
    }

    @Test
    fun `the count is counted the way Russian counts`() {
        fun n(count: Int) = DownloadNotificationText.progress(List(count) { item(episode = it) })

        assertEquals("Загружается 2 серии", n(2))
        assertEquals("Загружается 5 серий", n(5))
        assertEquals("Загружается 11 серий", n(11))
        assertEquals("Загружается 21 серия", n(21))
    }

    @Test
    fun `a finished download says so by name`() {
        assertEquals("Скачано: Тайтл, 7 серия", DownloadNotificationText.completed(item()))
    }

    @Test
    fun `a failed download says so by name, in the case the verb takes`() {
        // «скачать» governs the accusative: «Не удалось скачать 7 серия» is the same mistake
        // pluralEpisodesAccusative exists to prevent on the title screen.
        assertEquals("Не удалось скачать Тайтл, 7 серию", DownloadNotificationText.failed(item()))
    }

    @Test
    fun `an episode number is an ordinal, so the noun never goes plural`() {
        // «11 серию» is «одиннадцатую серию», not eleven of them — the count rule would say
        // «11 серий» and mean something else.
        assertEquals(
            "Не удалось скачать Тайтл, 11 серию",
            DownloadNotificationText.failed(item(episode = 11)),
        )
        assertEquals("Скачано: Тайтл, 11 серия", DownloadNotificationText.completed(item(episode = 11)))
    }

    @Test
    fun `a download whose title we never learned still names its episode`() {
        val nameless = item(title = null)

        assertEquals("7 серия, 42 %", DownloadNotificationText.progress(listOf(nameless)))
        assertEquals("Скачано: 7 серия", DownloadNotificationText.completed(nameless))
        assertEquals("Не удалось скачать 7 серию", DownloadNotificationText.failed(nameless))
    }

    @Test
    fun `waiting for the network is said rather than shown as nought per cent`() {
        assertEquals(
            "Ожидание сети",
            DownloadNotificationText.progress(listOf(item(percent = 0)), waitingForNetwork = true),
        )
    }

    @Test
    fun `nothing in flight still has a line`() {
        assertEquals("Подготовка", DownloadNotificationText.progress(emptyList()))
    }

    @Test
    fun `a percentage media3 has not worked out yet reads as nought`() {
        assertEquals("Тайтл, 7 серия, 0 %", DownloadNotificationText.progress(listOf(item(percent = -1))))
        assertEquals("Тайтл, 7 серия, 100 %", DownloadNotificationText.progress(listOf(item(percent = 140))))
    }

    @Test
    fun `the title line is fixed`() {
        assertEquals("Загрузка серий", DownloadNotificationText.TITLE)
    }
}
