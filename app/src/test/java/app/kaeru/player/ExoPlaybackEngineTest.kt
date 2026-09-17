package app.kaeru.player

import android.content.Context
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The one thing the real engine does that a fake cannot stand in for: turning a media3 player
 * down while somebody talks over it, and back up to exactly the volume it had.
 */
@OptIn(UnstableApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ExoPlaybackEngineTest {

    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(StandardTestDispatcher() + SupervisorJob())
    private lateinit var database: StandaloneDatabaseProvider
    private lateinit var cache: SimpleCache
    private lateinit var engine: ExoPlaybackEngine

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = StandaloneDatabaseProvider(context)
        cache = SimpleCache(folder.newFolder("downloads"), NoOpCacheEvictor(), database)
        engine = ExoPlaybackEngine(context, scope, CacheDataSource.Factory().setCache(cache))
    }

    @After
    fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        engine.shutdownIfIdle()
        scope.cancel()
        cache.release()
        database.close()
    }

    @Test
    fun `ducking turns the picture down and restores exactly the volume it had`() {
        engine.acquirePlayer().volume = 0.7f

        engine.duck(true)
        assertEquals(0.2f, engine.acquirePlayer().volume, 0.0001f)

        // Asked twice — a clip arriving while the microphone is already held — it must not
        // remember the ducked level as the one to go back to.
        engine.duck(true)
        engine.duck(false)
        assertEquals(0.7f, engine.acquirePlayer().volume, 0.0001f)
    }

    @Test
    fun `a duck asked for before there is a player lands on the one built next`() {
        // After a cast the local player may already be gone; the switch back asks for the duck
        // before the episode is prepared, and the player built for it has to start quiet.
        engine.duck(true)

        assertEquals(0.2f, engine.acquirePlayer().volume, 0.0001f)

        engine.duck(false)
        assertEquals(1f, engine.acquirePlayer().volume, 0.0001f)
    }

    @Test
    fun `letting the episode go puts the volume back`() {
        engine.acquirePlayer().volume = 0.7f
        engine.duck(true)

        engine.release()

        assertEquals(0.7f, engine.acquirePlayer().volume, 0.0001f)
    }
}
