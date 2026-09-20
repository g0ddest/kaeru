import Foundation

/// A release of this app that can actually be reached: a version, what changed, and the way in.
///
/// Everything on it is already resolved. There is no «assets» list and no draft flag, because a
/// release that reaches this type has passed the questions that have assets and flags in them, and
/// the screen above it should not be re-deciding them while it draws.
///
/// The one place iOS parts company with Android is `manifest`. An iOS app cannot install anything
/// itself — there is no equivalent of handing an APK to a system installer — so the best this app
/// can do is hand iOS an over-the-air manifest and let *it* do the installing. A release that
/// carries no manifest still has a page, and the page is offered instead: a viewer who is told
/// there is a new version and given no way to reach it is worse off than one who is given a link.
struct UpdateRelease: Codable, Equatable, Sendable {
    /// Without the leading `v`: what the screen shows, and what is compared against the install.
    var version: String
    /// When it was published, or nil for a release GitHub gave no date for.
    var publishedAt: Date?
    /// The release body as plain text: no markdown left in it, line breaks and bullets kept.
    var notes: String
    /// The `.plist` attached to the release — an over-the-air manifest, when there is one.
    var manifest: URL?
    /// The release's own page on GitHub. Always present, and always somewhere to send somebody.
    var page: URL?
    /// The size of the `.ipa` as GitHub reports it, or zero when the release has no build attached.
    var sizeBytes: Int64

    /// The link that makes iOS install this release, or nil when nothing here can.
    ///
    /// `itms-services` is the only door there is, and it opens only onto an `https` manifest —
    /// iOS refuses a plain `http` one without a word. Refusing it here instead means the screen
    /// offers the release page rather than a button that silently does nothing.
    var install: URL? {
        guard let manifest, manifest.scheme?.lowercased() == "https" else { return nil }
        var encoded = CharacterSet.alphanumerics
        encoded.insert(charactersIn: "-._~")
        guard let escaped = manifest.absoluteString.addingPercentEncoding(withAllowedCharacters: encoded) else { return nil }
        return URL(string: "itms-services://?action=download-manifest&url=\(escaped)")
    }
}

/// What one completed check found, and when.
///
/// A nil `release` is the answer «nothing newer than what is installed», not «nothing was found»:
/// a check that failed is a failure and is never written down as a result. That is what lets a
/// screen opened without a network show the last real answer instead of an empty one.
struct UpdateResult: Codable, Equatable, Sendable {
    var checkedAt: Date
    /// The version that was installed when the check ran, so a stale result can be recognised.
    var installedVersion: String
    var release: UpdateRelease?
}

/// Why an update could not be checked or opened.
///
/// Each of these reads differently to a viewer and leads somewhere different, which is why they
/// are separate rather than one «не удалось». A rate limit is over in an hour and nothing but
/// waiting fixes it; no network is worth pressing the button again for once there is one. The
/// wording lives with the rest of the screen's copy, as every user-facing string in this app does.
///
/// Android's list is longer by four — the failures of downloading an APK and handing it to a
/// system installer. None of them can happen here: this app never holds the file.
enum UpdateFailure: Equatable, Sendable {
    /// The network could not be reached at all.
    case noNetwork
    /// GitHub turned the unauthenticated request away: sixty an hour, per address.
    case rateLimited
    /// iOS did not open the link that installs the release.
    case installerRefused
    /// Something else — an answer that would not parse, a status nobody expected.
    case unknown
}

/// A failure carrying the one thing the screen has to know about it.
struct UpdateFailed: LocalizedError, Equatable {
    let reason: UpdateFailure
    var errorDescription: String? { UpdateCopy.failure(reason) }
}

/// A day, which is how often an app that ships a release a week is worth asking about.
private let defaultUpdateInterval: TimeInterval = 24 * 60 * 60

/// When the quiet check is allowed to run.
///
/// The check itself is a few kilobytes, so the interval is not about bandwidth — it is about the
/// rate limit. GitHub gives an unauthenticated address sixty requests an hour, and that address is
/// shared by everyone behind one router; an app that asked on every launch would spend somebody
/// else's budget as well as its own. Once a day is more than often enough for a release schedule
/// measured in weeks.
///
/// The viewer's own «Проверить» never comes through here. A press is a question the app has been
/// asked directly, and answering it with a day-old cached result would be the button doing nothing.
struct UpdatePolicy: Sendable {
    var interval: TimeInterval = defaultUpdateInterval

    /// Whether a check is owed, given when the last one finished.
    ///
    /// A `lastCheckedAt` in the future is due rather than never: the only way to get one is a
    /// clock that was wrong and has since been corrected, and a device that fell into that hole
    /// would otherwise stop checking for as long as the wrong reading stayed in the future.
    func due(lastCheckedAt: Date?, now: Date) -> Bool {
        guard let lastCheckedAt else { return true }
        if now < lastCheckedAt { return true }
        return now >= lastCheckedAt.addingTimeInterval(interval)
    }
}
