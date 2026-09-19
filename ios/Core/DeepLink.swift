import Foundation

/// Everything the app can be opened at from outside it: a new-episode notification, a television's
/// QR code, an invitation somebody sent in a messenger.
///
/// Parsing lives here rather than in a view because every one of these arrives from somewhere the
/// app does not control, and because the rules are then testable without a screen. Nothing is
/// trusted on the way through: a pairing link is refused unless it points at an address that
/// cannot be routed off this network, an invitation is validated by `TogetherInvitation`, and both
/// carry their secret in a value whose description is redacted.
enum DeepLink: Equatable, CustomStringConvertible {
    /// One title's screen, and — when a notification's «Смотреть» asked for it — one episode.
    case title(id: Int, episode: Int?)
    case watch(TogetherInvitation)
    case pair(PairingInvitation)

    var description: String {
        switch self {
        case .title(let id, let episode): "title(\(id), episode: \(episode.map(String.init) ?? "—"))"
        case .watch: "watch(<redacted>)"
        case .pair: "pair(<redacted>)"
        }
    }

    /// The largest episode number worth honouring. Long-running shows are in the hundreds; a link
    /// asking for more than this is somebody probing, not somebody watching.
    private static let episodeLimit = 10_000

    /// Nil for everything this app does not open, which includes an OAuth callback: that one comes
    /// back through the authorization session that started it, and a page able to fire
    /// `kaeru://oauth` must find nothing waiting here.
    static func parse(_ url: URL) -> DeepLink? {
        guard url.absoluteString.utf8.count <= 2048 else { return nil }
        // The invitation is looked at first because it is the only link that also arrives over
        // https, where there is no `kaeru` scheme to recognise it by.
        if let invitation = try? TogetherInvitation.parse(url) { return .watch(invitation) }
        guard let parts = URLComponents(url: url, resolvingAgainstBaseURL: false),
              parts.scheme?.lowercased() == "kaeru",
              parts.user == nil, parts.password == nil, parts.port == nil else { return nil }
        switch parts.host?.lowercased() {
        case "anime": return title(parts)
        case "pair": return (try? PairingInvitation.parse(url)).map(DeepLink.pair)
        // An invitation `TogetherInvitation.parse` refused, an OAuth callback, anything else.
        default: return nil
        }
    }

    private static func title(_ parts: URLComponents) -> DeepLink? {
        guard parts.fragment == nil, parts.path.hasPrefix("/"),
              let query = try? TogetherInvitation.uniqueQuery(parts),
              let id = number(String(parts.path.dropFirst()), limit: 999_999_999) else { return nil }
        guard let raw = query["episode"] else { return .title(id: id, episode: nil) }
        guard let episode = number(raw, limit: episodeLimit) else { return nil }
        return .title(id: id, episode: episode)
    }

    private static func number(_ value: String, limit: Int) -> Int? {
        guard !value.isEmpty, value.utf8.count <= 9, value.utf8.allSatisfy({ (48...57).contains($0) }),
              let parsed = Int(value), (1...limit).contains(parsed) else { return nil }
        return parsed
    }
}

/// What a link turns into once the state of the app is taken into account.
enum DeepLinkDestination: Equatable {
    case title(id: Int, episode: Int?)
    case watch(TogetherInvitation)
    case pair(PairingInvitation)
    /// Nowhere to go yet: held until somebody signs in, as Android's `PendingWatchLink` holds one.
    case held(DeepLink)
}

enum DeepLinkRouting {
    /// Pure, so the two rules that are easy to get wrong are the two that are tested.
    ///
    /// An invitation opened while signed out is the ordinary case rather than an edge — the
    /// landing page tells a person to install the app and open the link again, and an app that has
    /// just been installed has no account. Throwing it away would leave the room joined against
    /// nobody with no way back to the invitation.
    static func destination(for link: DeepLink, signedIn: Bool, playerOpen: Bool) -> DeepLinkDestination {
        switch link {
        // A second full-screen player over the first is never what a tap meant; the title's screen,
        // where the episode can be started deliberately, is.
        case .title(let id, let episode): .title(id: id, episode: playerOpen ? nil : episode)
        case .watch(let invitation): signedIn ? .watch(invitation) : .held(link)
        // Signing a television in is this phone's own authorization; it needs no account here.
        case .pair(let invitation): .pair(invitation)
        }
    }
}
