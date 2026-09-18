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
    fun `an episode number is an ordinal, so the noun never goes plural`() {
        assertEquals("Вышла 11 серия", NewEpisodeNotificationText.episode(11))
        assertEquals("Вышла 21 серия", NewEpisodeNotificationText.episode(21))
        assertEquals("Вышла 5 серия", NewEpisodeNotificationText.episode(5))
    }
}
