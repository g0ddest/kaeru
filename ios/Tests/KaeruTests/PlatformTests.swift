import UserNotifications
import XCTest
@testable import Kaeru

/// What the iPad and the Mac builds do differently, pinned where the difference is a decision.
@MainActor final class PlatformTests: XCTestCase {
    /// The one rule the notification service and the background check both read: the system has
    /// said yes, outright or quietly. `.ephemeral` is an App Clip's and exists only on iOS.
    func testOnlyAYesFromTheSystemLetsTheAppPost() {
        XCTAssertTrue(UNAuthorizationStatus.authorized.allowsPosting)
        XCTAssertTrue(UNAuthorizationStatus.provisional.allowsPosting)
        XCTAssertFalse(UNAuthorizationStatus.denied.allowsPosting)
        XCTAssertFalse(UNAuthorizationStatus.notDetermined.allowsPosting)
        #if os(iOS)
        XCTAssertTrue(UNAuthorizationStatus.ephemeral.allowsPosting)
        #endif
    }

    #if os(macOS)
    /// There is no Chromecast on a Mac. The manager the app builds there is never connected, and a
    /// cast asked of it fails at once, without resolving a stream nobody would play.
    func testTheMacHasNothingToCastTo() async {
        let service = CountingService()
        let manager = CastManager(service: service)
        manager.start()
        manager.presentDevices()
        XCTAssertFalse(manager.isConnected)
        await manager.load(anime: Anime(id: 42, title: "Example"), episode: 1, translation: 7, quality: 720)
        XCTAssertEqual(manager.state.error, .notConnected)
        XCTAssertEqual(service.resolved, 0)
    }

    /// The code was written for iOS, where Application Support and Caches belong to one app. On a
    /// Mac they are the user's shared folders unless the app is sandboxed — and
    /// `LocalStore.discardingCache()` deletes `default.store` in whichever one it is given. This is
    /// the test host, which is the app itself, with the app's entitlements.
    func testEverythingTheAppKeepsIsInItsOwnContainer() {
        let container = "/Library/Containers/app.kaeru.mac/Data"
        XCTAssertTrue(NSHomeDirectory().hasSuffix(container), NSHomeDirectory())
        XCTAssertTrue(URL.applicationSupportDirectory.path.contains(container + "/"), URL.applicationSupportDirectory.path)
        XCTAssertTrue(URL.cachesDirectory.path.contains(container + "/"), URL.cachesDirectory.path)
    }
    #endif
}

#if os(macOS)
@MainActor private final class CountingService: AnimeService {
    var resolved = 0
    func discover() async throws -> [Anime] { [] }
    func search(_ query: String) async throws -> [Anime] { [] }
    func details(_ id: Int) async throws -> Anime { Anime(id: id, title: "Example") }
    func library(_ userID: Int64, token: String) async throws -> [Kaeru.LibraryItem] { [] }
    func exchange(_ code: String) async throws -> Tokens { throw CancellationError() }
    func refresh(_ token: String) async throws -> Tokens { throw CancellationError() }
    func account(_ token: String) async throws -> Account { throw CancellationError() }
    func setRate(_ pending: PendingRate, userID: Int64, rateID: Int64, token: String) async throws -> Kaeru.LibraryItem { throw CancellationError() }
    func translations(_ id: Int) async throws -> [Kaeru.Translation] { [] }
    func resolve(_ id: Int, translation: Int, episode: Int) async throws -> Kaeru.Stream {
        resolved += 1
        throw CancellationError()
    }
}
#endif
