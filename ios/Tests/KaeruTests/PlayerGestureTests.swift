import XCTest
@testable import Kaeru

final class PlayerGestureTests: XCTestCase {
    func testTheEdgesSeekAndTheMiddleDoesNot() {
        XCTAssertEqual(PlayerGestures.zone(x: 10, width: 900), .back)
        XCTAssertEqual(PlayerGestures.zone(x: 890, width: 900), .forward)
        XCTAssertEqual(PlayerGestures.zone(x: 450, width: 900), .middle)
    }
    /// A third exactly: the middle owns its own boundary, so a tap that lands on the line brings
    /// the controls back rather than moving the video.
    func testTheBoundariesBelongToTheMiddle() {
        XCTAssertEqual(PlayerGestures.zone(x: 300, width: 900), .middle)
        XCTAssertEqual(PlayerGestures.zone(x: 600, width: 900), .middle)
        XCTAssertEqual(PlayerGestures.zone(x: 299.9, width: 900), .back)
        XCTAssertEqual(PlayerGestures.zone(x: 600.1, width: 900), .forward)
    }
    /// A view with no width yet, and a tap that arrived as a NaN. Neither may seek.
    func testNothingIsSeekedOnNonsense() {
        XCTAssertEqual(PlayerGestures.zone(x: 10, width: 0), .middle)
        XCTAssertEqual(PlayerGestures.zone(x: .nan, width: 900), .middle)
        XCTAssertEqual(PlayerGestures.zone(x: -50, width: 900), .back)
    }
}
