package app.kaeru.data.update

import app.kaeru.domain.update.UpdateRelease
import app.kaeru.domain.update.Version
import java.time.Instant
import java.time.format.DateTimeParseException

/** The only kind of file this app can install. */
private const val APK_SUFFIX = ".apk"

/**
 * Which of the releases GitHub listed is the one to offer.
 *
 * Drafts are left out: a draft is a maintainer's scratch space, visible only to them, and it has
 * no published build behind it. Pre-releases are kept, and that is not a concession — every
 * release of this app so far has been marked one, so filtering them out would filter out all of
 * them.
 *
 * «Newest» is by version rather than by position in the list. The API answers newest-created
 * first, which is usually the same thing and stops being the same thing the moment a patch is
 * published to an older line: `0.3.1` created after `0.4.0` would sit on top of it, and taking
 * the first entry would then hide the release the viewer actually wants. Ties — and releases
 * whose tags will not parse at all — fall back to the order GitHub gave.
 */
internal fun newestRelease(releases: List<GitHubReleaseDto>): GitHubReleaseDto? {
    val published = releases.filterNot { it.draft }
    if (published.isEmpty()) return null
    // maxByOrNull keeps the first of equal maxima, which is the API's own order.
    val newest = published
        .mapNotNull { release -> Version.parse(release.tagName)?.let { release to it } }
        .maxByOrNull { (_, version) -> version }
        ?.first
    return newest ?: published.first()
}

/**
 * The APK attached to a release, or null when nobody attached one.
 *
 * Matched on the extension rather than on a name pattern: the file has been called
 * `Kaeru-0.3.0.apk` so far, and a release that names it something else is still a release this
 * app can install. The first one wins, so a release that somehow carried two APKs offers the one
 * GitHub lists first rather than refusing to choose.
 */
internal fun apkAsset(release: GitHubReleaseDto): GitHubAssetDto? = release.assets.firstOrNull {
    it.name.endsWith(APK_SUFFIX, ignoreCase = true) && it.browserDownloadUrl.isNotBlank()
}

/**
 * A release as this app uses it, or null when it has no file to install.
 *
 * Null is not «no update»: the caller turns it into the failure that says a release exists and
 * has nothing attached to it, which is a different sentence from «у вас последняя версия» and
 * points at a different person to fix it.
 */
internal fun GitHubReleaseDto.toUpdateRelease(): UpdateRelease? {
    val asset = apkAsset(this) ?: return null
    return UpdateRelease(
        version = tagName.trim().removePrefix("v").removePrefix("V"),
        publishedAt = parseGitHubTime(publishedAt),
        notes = releaseNotes(body),
        apkUrl = asset.browserDownloadUrl,
        apkName = asset.name,
        sizeBytes = asset.size,
    )
}

/**
 * GitHub's own timestamps, which are ISO-8601 in UTC.
 *
 * A date that will not parse is no date rather than an exception: the screen simply shows the
 * version and the size, which is the part that matters, instead of the whole check failing over
 * a line of metadata.
 */
internal fun parseGitHubTime(raw: String?): Instant? {
    if (raw.isNullOrBlank()) return null
    return try {
        Instant.parse(raw)
    } catch (invalid: DateTimeParseException) {
        null
    }
}
