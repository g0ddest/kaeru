import Foundation

/// Where the releases of this app are published.
private let githubReleasesURL = "https://api.github.com/repos/g0ddest/kaeru/releases"

/// How many releases are read.
///
/// More than one, because the newest is not always the first thing in the list — a patch published
/// to an older line lands on top of it — and few, because everything past the first handful is
/// history nobody is going to be offered.
private let releasesPerPage = 5

/// GitHub says how much of the hourly budget is left in this header, and zero is the refusal.
private let rateLimitRemaining = "X-RateLimit-Remaining"
/// How the secondary limit says the same thing: come back in this many seconds.
private let retryAfter = "Retry-After"

struct GitHubAsset: Decodable, Sendable {
    var name = ""
    var size: Int64 = 0
    var browserDownloadURL = ""

    private enum CodingKeys: String, CodingKey {
        case name, size
        case browserDownloadURL = "browser_download_url"
    }
}

struct GitHubRelease: Decodable, Sendable {
    var tagName = ""
    var body: String?
    /// An unpublished draft, visible only to the maintainer and never offered.
    var draft = false
    /// Every release of this app so far is one, so it is read and deliberately not filtered on.
    var prerelease = false
    var publishedAt: String?
    var htmlURL: String?
    var assets: [GitHubAsset] = []

    private enum CodingKeys: String, CodingKey {
        case body, draft, prerelease, assets
        case tagName = "tag_name"
        case publishedAt = "published_at"
        case htmlURL = "html_url"
    }

    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        tagName = try values.decodeIfPresent(String.self, forKey: .tagName) ?? ""
        body = try values.decodeIfPresent(String.self, forKey: .body)
        draft = try values.decodeIfPresent(Bool.self, forKey: .draft) ?? false
        prerelease = try values.decodeIfPresent(Bool.self, forKey: .prerelease) ?? false
        publishedAt = try values.decodeIfPresent(String.self, forKey: .publishedAt)
        htmlURL = try values.decodeIfPresent(String.self, forKey: .htmlURL)
        assets = try values.decodeIfPresent([GitHubAsset].self, forKey: .assets) ?? []
    }

    init(tagName: String, body: String? = nil, draft: Bool = false, prerelease: Bool = true,
         publishedAt: String? = nil, htmlURL: String? = nil, assets: [GitHubAsset] = []) {
        self.tagName = tagName; self.body = body; self.draft = draft; self.prerelease = prerelease
        self.publishedAt = publishedAt; self.htmlURL = htmlURL; self.assets = assets
    }
}

/// What this app asks GitHub. A protocol so the rules above it can be tested without a network.
@MainActor protocol UpdateSource {
    func releases() async throws -> [GitHubRelease]
}

/// The releases of `g0ddest/kaeru`, unauthenticated.
///
/// The repository is public, so no token is sent and none is needed; what that costs is the rate
/// limit — sixty requests an hour per address — which is why `UpdatePolicy` exists. The `Accept`
/// header is GitHub's own versioned media type: without it the API answers with whatever its
/// default version is that month, and the field names are what this file parses.
struct GitHubReleaseSource: UpdateSource {
    var session: URLSession = .shared

    func releases() async throws -> [GitHubRelease] {
        guard var components = URLComponents(string: githubReleasesURL) else { throw UpdateFailed(reason: .unknown) }
        components.queryItems = [URLQueryItem(name: "per_page", value: String(releasesPerPage))]
        guard let url = components.url else { throw UpdateFailed(reason: .unknown) }
        var request = URLRequest(url: url)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        let data: Data, response: URLResponse
        do { (data, response) = try await session.data(for: request) }
        // A request that never reached GitHub is one sentence, and «Нет связи» is that sentence.
        catch let failure as URLError where failure.code != .cancelled { throw UpdateFailed(reason: .noNetwork) }
        if let http = response as? HTTPURLResponse, http.statusCode >= 400 {
            throw UpdateFailed(reason: Self.reason(for: http))
        }
        do { return try JSONDecoder().decode([GitHubRelease].self, from: data) }
        catch { throw UpdateFailed(reason: .unknown) }
    }

    /// A refusal as the reason the screen names.
    ///
    /// The rate limit is the one worth telling apart: GitHub answers it with a `403`, which on
    /// every other endpoint means «signed out» — so the header is what separates «wait an hour»
    /// from a real refusal. A `429` is the same thing said the modern way, and is read the same.
    static func reason(for response: HTTPURLResponse) -> UpdateFailure {
        switch response.statusCode {
        case 429: return .rateLimited
        // Two different limits answer with a 403. The hourly budget spends itself down to a
        // remaining count of zero; the secondary limit, which is about asking too fast rather than
        // too often, leaves that count alone and sends `Retry-After` instead. Both mean the same
        // thing to a viewer — wait — so both are read the same way.
        case 403:
            let remaining = response.value(forHTTPHeaderField: rateLimitRemaining)
            let after = response.value(forHTTPHeaderField: retryAfter)
            return remaining == "0" || after != nil ? .rateLimited : .unknown
        default: return .unknown
        }
    }
}

/// Which of the releases GitHub listed is the one to offer, and what this app makes of it.
enum ReleaseSelection {
    /// The over-the-air manifest, which is the only kind of file iOS will install from.
    private static let manifestSuffix = ".plist"
    /// The build itself. Never fetched here — it is named only so the screen can say what it weighs.
    private static let buildSuffix = ".ipa"

    /// Which of the releases GitHub listed is the one to offer.
    ///
    /// Drafts are left out: a draft is a maintainer's scratch space, visible only to them, and it
    /// has no published build behind it. Pre-releases are kept, and that is not a concession —
    /// every release of this app so far has been marked one, so filtering them out would filter
    /// out all of them.
    ///
    /// «Newest» is by version rather than by position in the list. The API answers newest-created
    /// first, which is usually the same thing and stops being the same thing the moment a patch is
    /// published to an older line: `0.3.1` created after `0.4.0` would sit on top of it, and
    /// taking the first entry would then hide the release the viewer actually wants. Ties — and
    /// releases whose tags will not parse at all — fall back to the order GitHub gave.
    static func newest(_ releases: [GitHubRelease]) -> GitHubRelease? {
        let published = releases.filter { !$0.draft }
        guard let first = published.first else { return nil }
        var best: (release: GitHubRelease, version: Version)?
        for release in published {
            guard let version = Version.parse(release.tagName) else { continue }
            // Strictly greater keeps the first of equal maxima, which is the API's own order.
            if best == nil || version > best!.version { best = (release, version) }
        }
        return best?.release ?? first
    }

    /// A release as this app uses it.
    ///
    /// Never nil, unlike Android's, and that is the platform difference in one line: there a
    /// release with no APK on it is a failure, because there is nothing left to do with it. Here
    /// the page is always a destination, so a release with no manifest is still a release worth
    /// telling somebody about.
    static func release(from release: GitHubRelease) -> UpdateRelease {
        let manifest = release.assets.first { $0.name.lowercased().hasSuffix(manifestSuffix) && !$0.browserDownloadURL.isEmpty }
        let build = release.assets.first { $0.name.lowercased().hasSuffix(buildSuffix) }
        var version = release.tagName.trimmingCharacters(in: .whitespacesAndNewlines)
        if version.hasPrefix("v") || version.hasPrefix("V") { version.removeFirst() }
        return UpdateRelease(version: version,
                             publishedAt: publishedTime(release.publishedAt),
                             notes: ReleaseNotes.plain(release.body),
                             manifest: manifest.flatMap { URL(string: $0.browserDownloadURL) },
                             page: release.htmlURL.flatMap(URL.init(string:)),
                             sizeBytes: build?.size ?? 0)
    }

    /// GitHub's own timestamps, which are ISO-8601 in UTC.
    ///
    /// A date that will not parse is no date rather than an error: the screen simply shows the
    /// version and the size, which is the part that matters, instead of the whole check failing
    /// over a line of metadata.
    static func publishedTime(_ raw: String?) -> Date? {
        guard let raw, !raw.isEmpty else { return nil }
        return ISO8601DateFormatter().date(from: raw)
    }
}
