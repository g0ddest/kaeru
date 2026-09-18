package app.kaeru.data.notify

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import app.kaeru.domain.notify.NewEpisode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** What is actually posted: one notification per title, a poster on it, and a way to press play. */
@RunWith(RobolectricTestRunner::class)
class NewEpisodeNotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    private var poster: Bitmap? = createBitmap(4, 6)
    private val asked = mutableListOf<String>()

    private val notifications = NewEpisodeNotifications(
        context = context,
        titleScreen = { animeId -> Intent("open-title").putExtra("animeId", animeId) },
        watchEpisode = { animeId, episode -> Intent("watch").putExtra("animeId", animeId).putExtra("episode", episode) },
        posters = { url ->
            asked += url
            poster
        },
    )

    private fun episode(id: Int, episode: Int, poster: String? = "poster-$id", aired: Int = episode) =
        NewEpisode(animeId = id, title = "Аниме $id", posterUrl = poster, episode = episode, aired = aired)

    private fun bigTextOf(notification: Notification): String? =
        notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    private fun textOf(notification: Notification): String? =
        notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun titleOf(notification: Notification): String? =
        notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    @Test
    fun `one title is one notification, named and numbered`() = runTest {
        notifications.post(listOf(episode(1, 7)))

        val one = posted().single()
        assertEquals("Аниме 1", titleOf(one))
        assertEquals("Вышла 7 серия", textOf(one))
        assertEquals(NewEpisodeNotifications.CHANNEL_ID, one.channelId)
    }

    @Test
    fun `the line names the episode that aired, not the one the viewer is on`() = runTest {
        notifications.post(listOf(episode(1, episode = 6, aired = 9)))

        assertEquals("Вышла 9 серия", textOf(posted().single()))
    }

    @Test
    fun `a viewer behind gets a second line saying where to start`() = runTest {
        notifications.post(listOf(episode(1, episode = 6, aired = 9)))

        assertEquals("Вышла 9 серия\nсмотреть с 6-й", bigTextOf(posted().single()))
    }

    @Test
    fun `a viewer who is up to date is told nothing extra`() = runTest {
        notifications.post(listOf(episode(1, episode = 9, aired = 9)))

        assertNull(bigTextOf(posted().single()))
    }

    @Test
    fun `the summary names what aired too`() = runTest {
        notifications.post(listOf(episode(1, episode = 6, aired = 9), episode(2, 3)))

        val summary = posted().single { it.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
        assertEquals(
            listOf("Аниме 1, 9 серия", "Аниме 2, 3 серия"),
            summary.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() },
        )
    }

    @Test
    fun `the channel exists before anything is ever posted`() {
        notifications.prepare()

        assertNotNull(manager.getNotificationChannel(NewEpisodeNotifications.CHANNEL_ID))
    }

    @Test
    fun `the poster is asked for and carried as the large icon`() = runTest {
        notifications.post(listOf(episode(1, 7)))

        assertEquals(listOf("poster-1"), asked)
        assertNotNull(posted().single().getLargeIcon())
    }

    @Test
    fun `a title with no poster is still announced`() = runTest {
        notifications.post(listOf(episode(1, 7, poster = null)))

        assertTrue(asked.isEmpty())
        assertEquals("Аниме 1", titleOf(posted().single()))
        assertNull(posted().single().getLargeIcon())
    }

    @Test
    fun `a poster that could not be fetched costs the picture and nothing else`() = runTest {
        poster = null

        notifications.post(listOf(episode(1, 7)))

        assertEquals(listOf("poster-1"), asked)
        assertEquals("Вышла 7 серия", textOf(posted().single()))
        assertNull(posted().single().getLargeIcon())
    }

    @Test
    fun `every notification offers to start the episode`() = runTest {
        notifications.post(listOf(episode(1, 7)))

        val action = posted().single().actions.single()
        assertEquals("Смотреть", action.title.toString())
    }

    @Test
    fun `one title needs no summary over it`() = runTest {
        notifications.post(listOf(episode(1, 7)))

        assertEquals(1, posted().size)
    }

    @Test
    fun `two titles are gathered under a summary that counts them`() = runTest {
        notifications.post(listOf(episode(1, 7), episode(2, 3)))

        val all = posted()
        assertEquals(3, all.size)
        val summary = all.single { it.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
        assertEquals("Новые серии", titleOf(summary))
        assertEquals("2 тайтла", textOf(summary))
        assertTrue(all.all { it.group == NewEpisodeNotifications.GROUP })
    }

    @Test
    fun `a later check about one title takes the stale summary away`() = runTest {
        notifications.post(listOf(episode(1, 7), episode(2, 3)))

        notifications.post(listOf(episode(1, 8)))

        assertTrue(posted().none { it.flags and Notification.FLAG_GROUP_SUMMARY != 0 })
    }

    @Test
    fun `clearing a title takes its notification out of the shade`() = runTest {
        notifications.post(listOf(episode(1, 7), episode(2, 3)))

        notifications.clear(1)

        assertEquals(listOf("Аниме 2"), posted().mapNotNull(::titleOf))
    }

    @Test
    fun `a summary left with one title under it goes too`() = runTest {
        notifications.post(listOf(episode(1, 7), episode(2, 3)))

        notifications.clear(1)

        assertTrue(posted().none { it.flags and Notification.FLAG_GROUP_SUMMARY != 0 })
    }

    @Test
    fun `a summary with titles left counts what is left, not what was`() = runTest {
        notifications.post(listOf(episode(1, 7), episode(2, 3), episode(3, 5)))

        notifications.clear(1)

        val summary = posted().single { it.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
        assertEquals("2 тайтла", textOf(summary))
        assertEquals(
            listOf("Аниме 2, 3 серия", "Аниме 3, 5 серия"),
            summary.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() },
        )
    }

    @Test
    fun `clearing a title nobody announced changes nothing`() = runTest {
        notifications.post(listOf(episode(1, 7), episode(2, 3)))

        notifications.clear(9)

        assertEquals(3, posted().size)
    }

    @Test
    fun `the channel the system shows is the one the viewer can turn off`() = runTest {
        notifications.post(listOf(episode(1, 7)))

        val channel = manager.getNotificationChannel(NewEpisodeNotifications.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals("Новые серии", channel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }
}
