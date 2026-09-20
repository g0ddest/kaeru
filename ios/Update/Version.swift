import Foundation

/// A version of this app, as the one question about it that matters: is that one newer than this one.
///
/// Numbers rather than text, because text gets this wrong in exactly the way that hurts. `0.10.0`
/// sorts before `0.9.0` as a string, so a lexicographic comparison stops offering updates the first
/// time a component reaches ten — a bug nobody notices until the release that triggers it.
///
/// Only the numeric part is compared. A tag may be written `v0.4.0`, and a release may carry a
/// suffix — `0.4.0-rc1`, `0.4.0+ci7` — but neither is part of the ordering this app needs: what is
/// published on GitHub is what is offered, and a pre-release is offered like any other. Dropping
/// the suffix therefore makes `0.4.0-rc1` and `0.4.0` the same version, which is the honest answer
/// for an app that cannot tell the two builds apart anyway.
///
/// Missing components are zero, so `1.0` and `1.0.0` are one version rather than two.
struct Version: Comparable, Equatable, Sendable {
    let parts: [Int]

    static func < (lhs: Self, rhs: Self) -> Bool {
        for index in 0..<max(lhs.parts.count, rhs.parts.count) {
            let mine = index < lhs.parts.count ? lhs.parts[index] : 0
            let theirs = index < rhs.parts.count ? rhs.parts[index] : 0
            if mine != theirs { return mine < theirs }
        }
        return false
    }

    static func == (lhs: Self, rhs: Self) -> Bool { !(lhs < rhs) && !(rhs < lhs) }

    /// A tag or a `CFBundleShortVersionString` as a version, or nil when there is no number in it.
    ///
    /// Nil rather than a zero version: «this string is not a version» and «this is version zero»
    /// lead to opposite decisions, and an unreadable tag must never look older than what is
    /// installed and so be silently skipped — the caller is told instead.
    static func parse(_ raw: String) -> Self? {
        var trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("v") || trimmed.hasPrefix("V") { trimmed.removeFirst() }
        // A suffix is not part of the ordering, and everything from the first one is dropped —
        // including a `-rc.1`, whose own dots would otherwise be read as components.
        let numeric = trimmed.prefix { $0 != "-" && $0 != "+" && $0 != " " }
        var parts: [Int] = []
        for component in numeric.split(separator: ".", omittingEmptySubsequences: false) {
            // A component with no digits ends the version rather than reading as zero:
            // `1.x.3` is `1`, not `1.0.3`.
            let digits = component.prefix { $0.isNumber }
            guard !digits.isEmpty, let value = Int(digits) else { break }
            parts.append(value)
        }
        return parts.isEmpty ? nil : Self(parts: parts)
    }
}

/// Whether `candidate` is worth offering to somebody running `installed`.
///
/// False whenever either side cannot be read as a version. An update is an install prompt, and
/// raising one on the strength of a tag nobody can parse is worse than missing a release: the next
/// release fixes the miss, and nothing fixes an install the viewer did not want.
func isNewerVersion(_ candidate: String, than installed: String) -> Bool {
    guard let newer = Version.parse(candidate), let current = Version.parse(installed) else { return false }
    return newer > current
}
