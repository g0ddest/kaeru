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

    private fun episode(id: Int, episode: Int, poster: String? = "poster-$id") =
        NewEpisode(animeId = id, title = "Аниме $id", posterUrl = poster, episode = episode)

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
    fun `the channel the system shows is the one the viewer can turn off`() = runTest {
        notifications.post(listOf(episode(1, 7)))

        val channel = manager.getNotificationChannel(NewEpisodeNotifications.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals("Новые серии", channel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }
}
