package app.kaeru.data.notify

import org.junit.Assert.assertEquals
import org.junit.Test

/** Every line the new-episode notification can show, with no Android in the way. */
class NewEpisodeNotificationTextTest {

    @Test
    fun `the line under the title names the episode that came out`() {
        assertEquals("Вышла 7 серия", NewEpisodeNotificationText.episode(7))
    }

    @Test
    fun `a title in the summary is named beside its episode`() {
        assertEquals("Тайтл, 7 серия", NewEpisodeNotificationText.line("Тайтл", 7))
    }

    @Test
    fun `the summary counts titles the way Russian counts`() {
        assertEquals("1 тайтл", NewEpisodeNotificationText.titles(1))
        assertEquals("3 тайтла", NewEpisodeNotificationText.titles(3))
        assertEquals("5 тайтлов", NewEpisodeNotificationText.titles(5))
        assertEquals("11 тайтлов", NewEpisodeNotificationText.titles(11))
        assertEquals("21 тайтл", NewEpisodeNotificationText.titles(21))
        assertEquals("22 тайтла", NewEpisodeNotificationText.titles(22))
    }

    @Test
    fun `an episode number is an ordinal, so the noun never goes plural`() {
        assertEquals("Вышла 11 серия", NewEpisodeNotificationText.episode(11))
        assertEquals("Вышла 21 серия", NewEpisodeNotificationText.episode(21))
        assertEquals("Вышла 5 серия", NewEpisodeNotificationText.episode(5))
    }
}
