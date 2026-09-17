package app.kaeru.domain.update

/**
 * A version of this app, as the one question about it that matters: is that one newer than this one.
 *
 * Numbers rather than text, because text gets this wrong in exactly the way that hurts. `0.10.0`
 * sorts before `0.9.0` as a string, so a lexicographic comparison stops offering updates the first
 * time a component reaches ten — which is a bug nobody notices until the release that triggers it.
 *
 * Only the numeric part is compared. A tag may be written `v0.4.0`, and a release may carry a
 * suffix — `0.4.0-rc1`, `0.4.0+ci7` — but neither is part of the ordering this app needs: what is
 * published on GitHub is what is offered, and a pre-release is offered like any other. Dropping
 * the suffix therefore makes `0.4.0-rc1` and `0.4.0` the same version, which is the honest answer
 * for an app that cannot distinguish them anyway.
 *
 * Missing components are zero, so `1.0` and `1.0.0` are one version rather than two.
 */
data class Version(val parts: List<Int>) : Comparable<Version> {

    override fun compareTo(other: Version): Int {
        repeat(maxOf(parts.size, other.parts.size)) { index ->
            val mine = parts.getOrElse(index) { 0 }
            val theirs = other.parts.getOrElse(index) { 0 }
            if (mine != theirs) return mine.compareTo(theirs)
        }
        return 0
    }

    override fun toString(): String = parts.joinToString(".")

    companion object {

        /**
         * A tag or a `versionName` as a version, or null when there is no number in it at all.
         *
         * Null rather than a zero version: «this string is not a version» and «this is version
         * zero» lead to opposite decisions, and an unparseable tag must never look older than
         * what is installed and so be silently skipped — the caller is told instead.
         */
        fun parse(raw: String): Version? {
            val trimmed = raw.trim().removePrefix("v").removePrefix("V")
            // A suffix is not part of the ordering, and everything from the first one is dropped
            // — including a `-rc.1`, whose own dots would otherwise be read as components.
            val numeric = trimmed.takeWhile { it != '-' && it != '+' && it != ' ' }
            val parts = numeric.split('.')
                .map { component -> component.takeWhile(Char::isDigit) }
                // A component with no digits ends the version rather than reading as zero:
                // `1.x.3` is `1`, not `1.0.3`.
                .takeWhile { it.isNotEmpty() }
                .mapNotNull { it.toIntOrNull() }
            return parts.takeIf { it.isNotEmpty() }?.let(::Version)
        }
    }
}

/**
 * Whether [candidate] is worth offering to somebody running [installed].
 *
 * False whenever either side cannot be read as a version. An update is a download and an install
 * prompt, and offering one on the strength of a tag nobody can parse is worse than missing a
 * release: the next release fixes the miss, and nothing fixes an install the viewer did not want.
 */
fun isNewerVersion(candidate: String, installed: String): Boolean {
    val newer = Version.parse(candidate) ?: return false
    val current = Version.parse(installed) ?: return false
    return newer > current
}
