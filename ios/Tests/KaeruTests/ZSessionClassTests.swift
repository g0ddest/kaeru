import AVFoundation
import XCTest

/// The cast that was silently false.
///
/// `AVAssetDownloadURLSession(configuration:…)` returns a `__NSURLBackgroundSession`: a class
/// cluster, not a subclass. `session as? AVAssetDownloadURLSession` is therefore nil for a session
/// that is one, and code that picked its download task by that cast made an ordinary download task
/// on a session that answers those with an Objective-C exception — an `abort()` in Swift.
final class AssetDownloadSessionCastTests: XCTestCase {
    @MainActor func testTheCastCannotBeTrusted() {
        final class Delegate: NSObject, AVAssetDownloadDelegate {}
        let configuration = URLSessionConfiguration.background(withIdentifier: "probe.\(UUID().uuidString)")
        let session = AVAssetDownloadURLSession(configuration: configuration,
                                                assetDownloadDelegate: Delegate(), delegateQueue: .main)
        // If this ever starts passing, Apple changed the cluster — the code does not depend on it
        // either way, and `BackgroundTransfers` keeps its HLS sessions at their own type.
        XCTAssertNil((session as URLSession) as? AVAssetDownloadURLSession)
        session.invalidateAndCancel()
    }
}
