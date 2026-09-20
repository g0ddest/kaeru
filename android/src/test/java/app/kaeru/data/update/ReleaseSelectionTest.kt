package app.kaeru.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** Which of the releases GitHub listed is the one this app offers, and what it turns into. */
class ReleaseSelectionTest {

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = true,
        assets: List<GitHubAssetDto> = listOf(apk(tag)),
        publishedAt: String? = "2026-09-16T08:00:00Z",
        body: String? = null,
    ) = GitHubReleaseDto(
        tagName = tag,
        draft = draft,
        prerelease = prerelease,
        publishedAt = publishedAt,
        body = body,
        assets = assets,
    )

    private fun apk(tag: String, size: Long = 31_457_280) = GitHubAssetDto(
        name = "Kaeru-${tag.removePrefix("v")}.apk",
        size = size,
        browserDownloadUrl = "https://github.com/g0ddest/kaeru/releases/download/$tag/Kaeru.apk",
    )

    @Test
    fun `a draft is never offered`() {
        val newest = newestRelease(listOf(release("v0.5.0", draft = true), release("v0.4.0")))

        assertEquals("v0.4.0", newest?.tagName)
    }

    /** Every release of this app so far is marked a pre-release, so filtering them out is fatal. */
    @Test
    fun `a pre-release is offered like any other`() {
        val newest = newestRelease(listOf(release("v0.4.0", prerelease = true)))

        assertEquals("v0.4.0", newest?.tagName)
    }

    @Test
    fun `nothing but drafts is nothing`() {
        assertNull(newestRelease(listOf(release("v0.5.0", draft = true))))
        assertNull(newestRelease(emptyList()))
    }

    /**
     * The case the API's own ordering gets wrong: a patch to an older line, published last, sits
     * on top of the list while being the older version.
     */
    @Test
    fun `the newest release is the greatest version and not the first entry`() {
        val newest = newestRelease(listOf(release("v0.3.1"), release("v0.4.0"), release("v0.2.9")))

        assertEquals("v0.4.0", newest?.tagName)
    }

    @Test
    fun `ten beats nine here too`() {
        val newest = newestRelease(listOf(release("v0.9.0"), release("v0.10.0")))

        assertEquals("v0.10.0", newest?.tagName)
    }

    /** With no readable tag anywhere, GitHub's own order is the best answer left. */
    @Test
    fun `an unreadable set of tags falls back to the order GitHub gave`() {
        val newest = newestRelease(listOf(release("nightly"), release("latest")))

        assertEquals("nightly", newest?.tagName)
    }

    @Test
    fun `the apk asset is the one picked`() {
        val assets = listOf(
            GitHubAssetDto("kaeru-mapping.txt", 1024, "https://example.test/mapping"),
            GitHubAssetDto("Kaeru-0.4.0.apk", 4096, "https://example.test/apk"),
        )

        assertEquals("Kaeru-0.4.0.apk", apkAsset(release("v0.4.0", assets = assets))?.name)
    }

    @Test
    fun `a release with no apk on it has no file to offer`() {
        val assets = listOf(GitHubAssetDto("notes.txt", 12, "https://example.test/notes"))

        assertNull(apkAsset(release("v0.4.0", assets = assets)))
        assertNull(release("v0.4.0", assets = assets).toUpdateRelease())
    }

    @Test
    fun `an asset with no download url is not a file`() {
        val assets = listOf(GitHubAssetDto("Kaeru-0.4.0.apk", 4096, ""))

        assertNull(apkAsset(release("v0.4.0", assets = assets)))
    }

    @Test
    fun `a release becomes a version, a date, a size and a file`() {
        val mapped = release("v0.4.0", body = "## Что нового\n- быстрее").toUpdateRelease()

        assertEquals("0.4.0", mapped?.version)
        assertEquals(Instant.parse("2026-09-16T08:00:00Z"), mapped?.publishedAt)
        assertEquals(31_457_280L, mapped?.sizeBytes)
        assertEquals("Kaeru-0.4.0.apk", mapped?.apkName)
        assertEquals("Что нового\n• быстрее", mapped?.notes)
    }

    @Test
    fun `a release with no date still maps`() {
        val mapped = release("v0.4.0", publishedAt = null).toUpdateRelease()

        assertNull(mapped?.publishedAt)
        assertEquals("0.4.0", mapped?.version)
    }

    @Test
    fun `a date that will not parse is no date rather than a failure`() {
        assertNull(parseGitHubTime("not a date"))
        assertNull(parseGitHubTime(null))
        assertNull(parseGitHubTime(""))
    }
}
