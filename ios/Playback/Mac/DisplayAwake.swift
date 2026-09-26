import Foundation
import IOKit.pwr_mgt

/// Keeps the Mac's screen on while an episode plays, as QuickTime and a browser do: without it the
/// display dimmed and slept in the middle of an episode nobody was touching the keyboard during.
/// Released on pause and when the player goes, so a paused episode lets the Mac sleep as usual.
@MainActor final class DisplayAwake {
    private var assertion: IOPMAssertionID = 0
    private var held = false

    func set(_ awake: Bool) {
        if awake == held { return }
        if awake {
            let reason = "Kaeru: идёт серия" as CFString
            held = IOPMAssertionCreateWithName(kIOPMAssertionTypePreventUserIdleDisplaySleep as CFString,
                                               IOPMAssertionLevel(kIOPMAssertionLevelOn), reason, &assertion) == kIOReturnSuccess
        } else {
            IOPMAssertionRelease(assertion)
            held = false
        }
    }

    deinit {
        // `deinit` is not on the main actor; releasing an id is safe from anywhere.
        if held { IOPMAssertionRelease(assertion) }
    }
}
