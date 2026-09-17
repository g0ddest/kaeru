package app.kaeru.data.update

import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import app.kaeru.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

/**
 * The three declarations without which the install silently does nothing, checked here because
 * none of them is reachable from Kotlin: a missing permission makes the installer refuse, a
 * missing provider makes the `content://` URI throw at the moment it is built, and a path
 * configuration that names the wrong directory makes it throw for the file this app actually
 * downloads.
 *
 * All three fail only on a device, and only at the last step of the one flow that matters.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateManifestTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `the app may ask the system to install a package`() {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )

        assertTrue(
            "REQUEST_INSTALL_PACKAGES is missing, so the installer refuses the APK",
            info.requestedPermissions.orEmpty().contains(android.Manifest.permission.REQUEST_INSTALL_PACKAGES),
        )
    }

    @Test
    fun `the file provider is declared under the authority the installer is handed`() {
        val providers = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS)
            .providers
            .orEmpty()

        val updates = providers.firstOrNull { it.authority == context.packageName + FILE_PROVIDER_SUFFIX }
        assertTrue("no provider for ${context.packageName}$FILE_PROVIDER_SUFFIX", updates != null)
        assertEquals("androidx.core.content.FileProvider", updates!!.name)
        assertTrue("the grant is what lets the installer read the file", updates.grantUriPermissions)
        assertFalse("nothing outside this app addresses it", updates.exported)
    }

    /**
     * The path configuration, read as the provider reads it: a `cache-path` rooted at `updates/`,
     * which is exactly where [ApkDownloader] writes. A provider whose paths named `files` instead
     * would build no URI for a file in the cache, and the failure would be an exception at the
     * end of a thirty-megabyte download.
     */
    @Test
    fun `the paths name the cache directory the download writes into`() {
        val parser = context.resources.getXml(R.xml.file_paths)
        var found = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "cache-path") {
                // No namespace on these attributes, which is how FileProvider itself reads them.
                assertEquals("updates/", parser.getAttributeValue(null, "path"))
                assertEquals("updates", parser.getAttributeValue(null, "name"))
                found = true
            }
        }

        assertTrue("file_paths.xml declares no cache-path", found)
    }

    /**
     * The permission check the screen gates on exists from the oldest device this app runs on.
     *
     * Read from the merged manifest rather than from `Build.VERSION.SDK_INT`, which under
     * Robolectric reports whichever SDK the test is configured for and so can never fail. What
     * this pins is the real thing: `canRequestPackageInstalls()` arrived in API 26, and a
     * `minSdk` lowered below that would make the whole install path unreachable on the devices
     * it let in.
     */
    @Test
    fun `the install permission can be asked about on the minimum supported release`() {
        val minSdk = context.packageManager
            .getApplicationInfo(context.packageName, 0)
            .minSdkVersion

        assertTrue(
            "canRequestPackageInstalls needs API ${Build.VERSION_CODES.O} and minSdk is $minSdk",
            minSdk >= Build.VERSION_CODES.O,
        )
    }
}
