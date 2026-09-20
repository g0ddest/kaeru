package app.kaeru.data.notify

/**
 * Whether this is a television rather than a phone.
 *
 * A seam rather than a `PackageManager` call at the point of use, because what depends on it — the
 * decision never to schedule the check at all — is worth a test, and a shadow package manager is
 * not what that test should be about. The real one asks for `FEATURE_LEANBACK`.
 */
fun interface Television {
    fun isTelevision(): Boolean
}
