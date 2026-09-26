import XCTest
@testable import Kaeru

@MainActor private final class StubSource: UpdateSource {
    var calls = 0
    var answer: () async throws -> [GitHubRelease] = { [] }
    func releases() async throws -> [GitHubRelease] { calls += 1; return try await answer() }
}

@MainActor private final class MemoryStore: LocalStorage {
    var values: [String: Data] = [:]
    var writes = 0
    func read<T: Decodable>(_ type: T.Type, key: String) throws -> T? {
        try values[key].map { try JSONDecoder().decode(type, from: $0) }
    }
    func write<T: Encodable>(_ value: T, key: String) throws {
        writes += 1
        values[key] = try JSONEncoder().encode(value)
    }
    func readAll<T: Decodable>(_ type: T.Type, prefix: String) throws -> [String: T] {
        try values.filter { $0.key.hasPrefix(prefix) }.mapValues { try JSONDecoder().decode(type, from: $0) }
    }
    func remove(_ keys: [String]) throws { for key in keys { values[key] = nil } }
}

@MainActor final class UpdateRepositoryTests: XCTestCase {
    private let epoch = Date(timeIntervalSince1970: 1_758_326_400)
    private var moment = Date(timeIntervalSince1970: 1_758_326_400)
    private let source = StubSource()
    private var calls: Int { source.calls }

    private func release(_ tag: String, assets: [GitHubAsset] = [], draft: Bool = false,
                         body: String? = nil, page: String? = "https://example.test/r") -> GitHubRelease {
        GitHubRelease(tagName: tag, body: body, draft: draft, publishedAt: "2026-09-16T08:00:00Z",
                      htmlURL: page, assets: assets)
    }
    private func asset(_ name: String, size: Int64 = 0, url: String = "https://example.test/f") -> GitHubAsset {
        var value = GitHubAsset(); value.name = name; value.size = size; value.browserDownloadURL = url
        return value
    }
    private func repository(_ store: MemoryStore, installed: String = "0.5.1",
                            answering: @escaping () async throws -> [GitHubRelease]) -> GitHubUpdateRepository {
        source.answer = answering
        return GitHubUpdateRepository(source: source, store: store, installedVersion: installed, now: { self.moment })
    }

    // MARK: - what is offered

    func testANewerTagIsOffered() async throws {
        let repo = repository(MemoryStore()) { [self] in [release("v0.6.0", assets: [asset("manifest.plist")])] }
        let result = try await repo.check(force: false).get()
        XCTAssertEqual(result.release?.version, "0.6.0")
        XCTAssertEqual(result.checkedAt, epoch)
        XCTAssertEqual(result.installedVersion, "0.5.1")
    }

    func testTheInstalledVersionIsNotOffered() async throws {
        let repo = repository(MemoryStore()) { [self] in [release("0.5.1"), release("0.4.0")] }
        let result = try await repo.check(force: false).get()
        XCTAssertNil(result.release)
    }

    /// A draft is a maintainer's scratch space with no published build behind it.
    func testADraftIsNeverOffered() async throws {
        let repo = repository(MemoryStore()) { [self] in [release("9.9.9", draft: true), release("0.6.0")] }
        let result = try await repo.check(force: false).get()
        XCTAssertEqual(result.release?.version, "0.6.0")
    }

    /// The API answers newest-created first, which stops being newest the moment a patch is
    /// published to an older line.
    func testTheNewestVersionWinsOverTheNewestEntry() async throws {
        let repo = repository(MemoryStore()) { [self] in [release("0.5.2"), release("0.6.0")] }
        let result = try await repo.check(force: false).get()
        XCTAssertEqual(result.release?.version, "0.6.0")
    }

    // MARK: - what each system installs from

    func testAManifestBecomesAnInstallLink() {
        let offered = ReleaseSelection.release(from: release("0.6.0", assets: [
            asset("Kaeru-0.6.0.ipa", size: 31_457_280, url: "https://example.test/Kaeru.ipa"),
            asset("manifest.plist", url: "https://example.test/m.plist")
        ]), for: .iOS)
        XCTAssertEqual(offered.install?.absoluteString,
                       "itms-services://?action=download-manifest&url=https%3A%2F%2Fexample.test%2Fm.plist")
        // The build is named only so the screen can say what the release weighs.
        XCTAssertEqual(offered.sizeBytes, 31_457_280)
    }

    /// iOS refuses a plain `http` manifest without a word, so the button that would do nothing is
    /// never offered — the release page is.
    func testAnInsecureManifestIsNotAnInstallLink() {
        let offered = ReleaseSelection.release(from: release("0.6.0", assets: [
            asset("manifest.plist", url: "http://example.test/m.plist")
        ]), for: .iOS)
        XCTAssertNil(offered.install)
        XCTAssertEqual(offered.page?.absoluteString, "https://example.test/r")
    }

    /// One release carries every system's files. The Mac takes the disk image — the browser
    /// downloads it, and the viewer drags Kaeru out of it — and never the iPhone's manifest or the
    /// APK; the same release still hands the iPhone its own.
    func testTheMacTakesTheDiskImage() {
        let published = release("0.7.0", assets: [
            asset("Kaeru-0.7.0.apk", size: 23_068_672, url: "https://example.test/Kaeru-0.7.0.apk"),
            asset("Kaeru-0.7.0.ipa", size: 31_457_280, url: "https://example.test/Kaeru-0.7.0.ipa"),
            asset("manifest.plist", url: "https://example.test/m.plist"),
            asset("Kaeru-0.7.0-mac.dmg", size: 9_437_184, url: "https://example.test/Kaeru-0.7.0-mac.dmg")
        ])
        let mac = ReleaseSelection.release(from: published, for: .macOS)
        XCTAssertEqual(mac.install?.absoluteString, "https://example.test/Kaeru-0.7.0-mac.dmg")
        XCTAssertEqual(mac.sizeBytes, 9_437_184)
        let phone = ReleaseSelection.release(from: published, for: .iOS)
        XCTAssertEqual(phone.install?.scheme, "itms-services")
        XCTAssertEqual(phone.sizeBytes, 31_457_280)
    }

    /// Every release so far carries an APK and nothing a Mac can open. It is still offered — through
    /// its page, as a release with no manifest is on the iPhone — and it weighs nothing it can name.
    func testAReleaseWithNoDiskImageSendsTheMacToItsPage() {
        let offered = ReleaseSelection.release(from: release("0.7.0", assets: [
            asset("Kaeru-0.7.0.apk", size: 23_068_672, url: "https://example.test/Kaeru-0.7.0.apk"),
            asset("Kaeru-0.7.0.ipa", size: 31_457_280, url: "https://example.test/Kaeru-0.7.0.ipa"),
            asset("manifest.plist", url: "https://example.test/m.plist")
        ]), for: .macOS)
        XCTAssertNil(offered.install)
        XCTAssertEqual(offered.sizeBytes, 0)
        XCTAssertEqual(offered.page?.absoluteString, "https://example.test/r")
    }

    /// A disk image anybody on the way could swap is not one to hand a Mac to install: the page,
    /// which is https, is offered instead.
    func testAnInsecureDiskImageIsNotAnInstallLink() {
        let offered = ReleaseSelection.release(from: release("0.7.0", assets: [
            asset("Kaeru-0.7.0-mac.dmg", url: "http://example.test/Kaeru-0.7.0-mac.dmg")
        ]), for: .macOS)
        XCTAssertNil(offered.install)
        XCTAssertEqual(offered.page?.absoluteString, "https://example.test/r")
    }

    /// Which system's file is offered is the build's to say, not the release's.
    func testTheCheckOffersTheFileOfTheSystemItRunsOn() async throws {
        let repo = repository(MemoryStore()) { [self] in
            [release("0.7.0", assets: [asset("manifest.plist", url: "https://example.test/m.plist"),
                                       asset("Kaeru-0.7.0-mac.dmg", url: "https://example.test/Kaeru-0.7.0-mac.dmg")])]
        }
        let offered = try await repo.check(force: false).get().release
        #if os(macOS)
        XCTAssertEqual(offered?.install?.absoluteString, "https://example.test/Kaeru-0.7.0-mac.dmg")
        #else
        XCTAssertEqual(offered?.install?.scheme, "itms-services")
        #endif
    }

    /// Android calls a release with no file attached a failure. Here it is still an offer, on
    /// either system: every release has a page, and a page is somewhere to send somebody.
    func testAReleaseWithNoManifestIsStillOfferedThroughItsPage() async throws {
        let repo = repository(MemoryStore()) { [self] in [release("0.6.0")] }
        let offered = try await repo.check(force: false).get().release
        XCTAssertNotNil(offered)
        XCTAssertNil(offered?.install)
        XCTAssertEqual(offered?.page?.absoluteString, "https://example.test/r")
    }

    func testTheNotesAreStrippedOfTheirMarkdown() async throws {
        let repo = repository(MemoryStore()) { [self] in [release("0.6.0", body: "## Что нового\n\n- **Обновления**")] }
        let result = try await repo.check(force: false).get()
        XCTAssertEqual(result.release?.notes, "Что нового\n\n• Обновления")
    }

    // MARK: - the throttle

    func testAnAnswerFromTodayIsNotAskedForAgain() async throws {
        let store = MemoryStore()
        let repo = repository(store) { [self] in [release("0.6.0")] }
        _ = try await repo.check(force: false).get()
        moment = epoch.addingTimeInterval(23 * 60 * 60)
        _ = try await repo.check(force: false).get()
        XCTAssertEqual(calls, 1)
    }

    func testADayLaterItIsAskedForAgain() async throws {
        let store = MemoryStore()
        let repo = repository(store) { [self] in [release("0.6.0")] }
        _ = try await repo.check(force: false).get()
        moment = epoch.addingTimeInterval(24 * 60 * 60)
        _ = try await repo.check(force: false).get()
        XCTAssertEqual(calls, 2)
    }

    /// A press is a question put directly, and answering it out of a cache would be a button that
    /// does nothing.
    func testTheViewersOwnPressGoesPastTheThrottle() async throws {
        let store = MemoryStore()
        let repo = repository(store) { [self] in [release("0.6.0")] }
        _ = try await repo.check(force: false).get()
        _ = try await repo.check(force: true).get()
        XCTAssertEqual(calls, 2)
    }

    /// An app updated since the last check has to go and ask again rather than sit out the day on
    /// an answer about the build it replaced.
    func testARecordWrittenByAnotherBuildDoesNotHoldTheThrottle() async throws {
        let store = MemoryStore()
        _ = try await repository(store, installed: "0.5.1") { [self] in [release("0.6.0")] }.check(force: false).get()
        _ = try await repository(store, installed: "0.6.0") { [self] in [release("0.6.0")] }.check(force: false).get()
        XCTAssertEqual(calls, 2)
    }

    /// One tunnel must not cost a day of checks.
    func testAFailedCheckIsNeverWrittenDown() async throws {
        let store = MemoryStore()
        let repo = repository(store) { () -> [GitHubRelease] in throw UpdateFailed(reason: .noNetwork) }
        if case .success = await repo.check(force: false) { XCTFail("a failure is not a result") }
        XCTAssertNil(repo.lastResult)
        XCTAssertEqual(store.writes, 0)
    }

    // MARK: - the stored answer

    /// The update installing itself out of existence: the record written by 0.5.1 survives the
    /// install, and the new process must not go on announcing the version it is running.
    func testAStoredReleaseIsFilteredByTheBuildThatIsRunning() async throws {
        let store = MemoryStore()
        _ = try await repository(store, installed: "0.5.1") { [self] in [release("0.6.0")] }.check(force: false).get()
        let after = repository(store, installed: "0.6.0") { [] }
        XCTAssertNil(after.lastResult?.release)
        // What survives is the check's date, which is still true: the app did ask, on that day.
        XCTAssertEqual(after.lastResult?.checkedAt, epoch)
    }

    func testATunnelLeavesTheLastRealAnswerReadable() async throws {
        let store = MemoryStore()
        _ = try await repository(store) { [self] in [release("0.6.0")] }.check(force: false).get()
        let offline = repository(store) { () -> [GitHubRelease] in throw UpdateFailed(reason: .noNetwork) }
        moment = epoch.addingTimeInterval(48 * 60 * 60)
        if case .success = await offline.check(force: true) { XCTFail("no network is not a result") }
        XCTAssertEqual(offline.lastResult?.release?.version, "0.6.0")
    }

    func testARecordThatWillNotDecodeIsNoRecord() {
        let store = MemoryStore()
        store.values[GitHubUpdateRepository.storageKey] = Data("не json".utf8)
        XCTAssertNil(repository(store) { [] }.lastResult)
    }

    // MARK: - why a request was refused

    func testTheRateLimitIsToldApartFromAnOrdinaryRefusal() throws {
        let url = try XCTUnwrap(URL(string: "https://api.github.com/x"))
        func response(_ code: Int, _ headers: [String: String]) throws -> HTTPURLResponse {
            try XCTUnwrap(HTTPURLResponse(url: url, statusCode: code, httpVersion: nil, headerFields: headers))
        }
        XCTAssertEqual(GitHubReleaseSource.reason(for: try response(429, [:])), .rateLimited)
        XCTAssertEqual(GitHubReleaseSource.reason(for: try response(403, ["X-RateLimit-Remaining": "0"])), .rateLimited)
        // The secondary limit leaves the count alone and sends this instead.
        XCTAssertEqual(GitHubReleaseSource.reason(for: try response(403, ["Retry-After": "60"])), .rateLimited)
        XCTAssertEqual(GitHubReleaseSource.reason(for: try response(403, ["X-RateLimit-Remaining": "41"])), .unknown)
        XCTAssertEqual(GitHubReleaseSource.reason(for: try response(404, [:])), .unknown)
    }
}
