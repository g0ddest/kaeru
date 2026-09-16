package app.kaeru.data.update

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * The shape of the two intents this app fires at the system, checked because neither of them can
 * be observed anywhere short of a device: one opens a settings screen, the other opens the
 * installer, and both fail silently if a flag or a type is wrong.
 */
@RunWith(RobolectricTestRunner::class)
class SystemApkInstallerTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val installer = SystemApkInstaller(app)

    /**
     * There is no installer and no settings screen in this sandbox, and Robolectric otherwise
     * answers a start with the same `ActivityNotFoundException` a stripped device would. The
     * intent is still recorded either way, so turning the check off is what lets the test say
     * «this was handed over» rather than only «this is the shape it would have had».
     */
    @Before
    fun setUp() = shadowOf(app).checkActivities(false)

    private fun apk(): File = File(app.cacheDir, "updates").let { dir ->
        dir.mkdirs()
        File(dir, "Kaeru-0.4.0.apk").apply { writeBytes(ByteArray(16)) }
    }

    /**
     * One test rather than four, and not for brevity: `FileProvider` caches its path strategy in a
     * static map keyed by authority, while Robolectric hands every test method a fresh data
     * directory — so the second test in a class to build a `content://` URI looks up a root that
     * no longer exists. On a device the cache directory never moves and the cache is right.
     *
     * The grant is what lets the installer — another process — read a file in this app's cache.
     * The task flag is not optional either: this is started from the application context, which
     * has no task of its own to put an activity in.
     */
    @Test
    fun `the installer is handed a content uri, the package-archive type and both flags`() {
        assertTrue(installer.install(apk().absolutePath))

        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("content", intent.data?.scheme)
        assertEquals("${app.packageName}.updates", intent.data?.authority)
        assertTrue("no read grant", intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue("no new task", intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `a file that is not there is never handed over`() {
        assertFalse(installer.install(File(app.cacheDir, "updates/gone.apk").absolutePath))

        assertEquals(null, shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `the permission request opens the system screen for this package`() {
        assertTrue(installer.requestPermission())

        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${app.packageName}", intent.data.toString())
        assertNotNull(intent.data)
    }
}
