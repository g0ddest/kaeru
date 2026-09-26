#if os(macOS)
import XCTest
import IOKit.pwr_mgt
@testable import Kaeru

@MainActor final class DisplayAwakeTests: XCTestCase {
    /// How many «keep the display on» assertions this process holds, as the power manager sees them.
    private func held() -> Int {
        var raw: Unmanaged<CFDictionary>?
        guard IOPMCopyAssertionsByProcess(&raw) == kIOReturnSuccess, let all = raw?.takeRetainedValue() as? [NSNumber: [[String: Any]]] else { return 0 }
        let mine = all[NSNumber(value: ProcessInfo.processInfo.processIdentifier)] ?? []
        return mine.filter { ($0[kIOPMAssertionTypeKey] as? String) == kIOPMAssertionTypePreventUserIdleDisplaySleep }.count
    }

    func testKeepsTheScreenOnWhilePlayingAndLetsGoOnPause() {
        let before = held()
        let awake = DisplayAwake()
        awake.set(true)
        XCTAssertEqual(held(), before + 1)
        awake.set(true)
        XCTAssertEqual(held(), before + 1, "one assertion, however often playback is reported")
        awake.set(false)
        XCTAssertEqual(held(), before)
    }
}
#endif
