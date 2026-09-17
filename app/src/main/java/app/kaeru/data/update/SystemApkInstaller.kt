package app.kaeru.data.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import app.kaeru.domain.update.UpdateInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What the platform calls an APK, and the only type its installer answers to. */
private const val APK_MIME = "application/vnd.android.package-archive"

/** The authority declared in the manifest, which is the application id with this on the end. */
internal const val FILE_PROVIDER_SUFFIX = ".updates"

/**
 * The system installer, asked to install a file this app has just downloaded.
 *
 * Nothing here installs anything. A sideloaded app cannot, and should not want to: all three of
 * these calls end in a screen the viewer answers themselves — the permission prompt the first
 * time, and the installer's own «обновить?» every time. What this class does is hand the file
 * over in the one shape the platform accepts.
 *
 * The file travels as a `content://` URI from a `FileProvider` rather than as a path. A `file://`
 * URI has been an exception since Android 7, and the read permission that comes with the grant is
 * what lets the installer — a different process — open something in this app's cache.
 */
@Singleton
class SystemApkInstaller @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : UpdateInstaller {

    override fun allowed(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun requestPermission(): Boolean = start(
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri(),
        ),
    )

    override fun install(path: String): Boolean {
        val file = File(path)
        if (!file.isFile) return false
        val uri = runCatching {
            FileProvider.getUriForFile(context, context.packageName + FILE_PROVIDER_SUFFIX, file)
        }.getOrNull() ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return start(intent)
    }

    /**
     * Started from the application context, which is why the new-task flag is not optional.
     *
     * A device with nothing behind the intent — a television with no settings screen for unknown
     * sources, a stripped build — is told about rather than crashed on: the screen says the
     * installer refused, which is the truth from the viewer's side.
     */
    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (missing: ActivityNotFoundException) {
        false
    } catch (refused: SecurityException) {
        false
    }
}
