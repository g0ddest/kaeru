import Foundation

/// What this device knows about releases of this app.
///
/// One record is kept: the last check that completed, whatever it found. It is both the throttle's
/// timestamp and the answer a screen opened on a train shows — those are the same fact, and
/// keeping them apart is how they come to disagree.
@MainActor protocol UpdateRepository: AnyObject {
    /// The last completed check, or nil on a device that has never managed one.
    var lastResult: UpdateResult? { get }

    /// Asks GitHub, or answers from the last check when `force` is false and one is recent enough.
    ///
    /// `force` is the viewer pressing «Проверить»; false is the app asking on its own at launch.
    /// A failure is never written down, so the next start tries again rather than waiting out a
    /// day because of one tunnel.
    func check(force: Bool) async -> Result<UpdateResult, Error>
}

/// What GitHub says about releases of this app, and what this device remembers of the answer.
///
/// The rule the whole screen rests on is here and nowhere else: a release is offered when its tag
/// is a greater version than the one this build was compiled with. The installed version is handed
/// in rather than read from the bundle in place, so the rule can be tested against any pair of
/// versions instead of against whatever the tree happens to be at.
@MainActor final class GitHubUpdateRepository: UpdateRepository {
    /// One JSON record rather than six keys. A result is written and read whole, never field by
    /// field, and six keys would be six chances for a half-written record to describe a release
    /// that never existed.
    static let storageKey = "updates.lastResult"

    private let source: any UpdateSource
    private let store: any LocalStorage
    private let policy: UpdatePolicy
    private let now: @MainActor () -> Date
    let installedVersion: String

    init(source: any UpdateSource, store: any LocalStorage, installedVersion: String,
         policy: UpdatePolicy = UpdatePolicy(), now: @escaping @MainActor () -> Date = { Date() }) {
        self.source = source; self.store = store; self.installedVersion = installedVersion
        self.policy = policy; self.now = now
    }

    /// The stored answer, re-read against the build that is actually running.
    ///
    /// This is the update installing itself out of existence. A record written by 0.5.1 offering
    /// 0.6.0 stays on disk through the install, and the new process reads it before it has had
    /// time to ask GitHub anything — so without this the home screen says «Доступна версия 0.6.0»
    /// while running 0.6.0, and goes on saying it for as long as the next check keeps failing.
    ///
    /// The comparison is the one the whole feature rests on, applied a second time at the point of
    /// reading rather than only at the point of writing. What survives is the check's date, which
    /// is still true: the app did ask, on that day, and the answer just stopped being an offer.
    ///
    /// A record that will not decode is no record. The only way to get one is a build that changed
    /// this shape, and the cost of that is one extra check on one launch — a far better answer
    /// than an error on the path that builds the home screen.
    var lastResult: UpdateResult? {
        guard var stored = try? store.read(UpdateResult.self, key: Self.storageKey) else { return nil }
        if let release = stored.release, !isNewerVersion(release.version, than: installedVersion) { stored.release = nil }
        return stored
    }

    func check(force: Bool) async -> Result<UpdateResult, Error> {
        let moment = now()
        // The record keeps the version it was written by, which is what the throttle is asking
        // about: an app updated since the last check has to go and ask again rather than sit out
        // the day on an answer about the build it replaced.
        let usable = lastResult.flatMap { $0.installedVersion == installedVersion ? $0 : nil }
        if !force, let usable, !policy.due(lastCheckedAt: usable.checkedAt, now: moment) {
            return .success(usable)
        }
        do {
            let result = resultOf(try await source.releases(), now: moment)
            // A store that will not write is not a failed check: the answer is right, and the only
            // cost of losing it is asking again sooner than a day from now.
            try? store.write(result, key: Self.storageKey)
            return .success(result)
        } catch is CancellationError {
            return .failure(CancellationError())
        } catch {
            return .failure(error as? UpdateFailed ?? UpdateFailed(reason: .unknown))
        }
    }

    /// The answer, given what GitHub listed: there is nothing newer, or there is.
    ///
    /// Android has a third outcome here — a newer release with no APK attached, which it reports
    /// as a failure because there is nothing left to do with it. On iOS every release has a page,
    /// so the offer is always worth making.
    private func resultOf(_ releases: [GitHubRelease], now: Date) -> UpdateResult {
        let upToDate = UpdateResult(checkedAt: now, installedVersion: installedVersion, release: nil)
        guard let newest = ReleaseSelection.newest(releases),
              isNewerVersion(newest.tagName, than: installedVersion) else { return upToDate }
        return UpdateResult(checkedAt: now, installedVersion: installedVersion,
                            release: ReleaseSelection.release(from: newest))
    }
}

/// The version this build was compiled with, as the bundle reports it.
///
/// `CFBundleShortVersionString` and nothing else: the build number beside it in Settings moves
/// between builds of one release, and comparing on it would offer 0.6.0 to a phone already
/// running it.
@MainActor func installedAppVersion(_ bundle: Bundle = .main) -> String {
    bundle.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
}
