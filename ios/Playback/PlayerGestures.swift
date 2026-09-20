import Foundation

/// Where a double tap landed, and therefore what it means.
enum PlayerTapZone: Equatable { case back, middle, forward }

/// The arithmetic behind the player's gestures, with no UIKit in it — a port of Android's
/// `GestureMath`, so a double tap means the same thing on both phones.
enum PlayerGestures {
    /// Thirds, not halves: the middle of the picture is where a thumb lands to bring the controls
    /// back, and a double tap there must not throw the video ten seconds off course.
    static func zone(x: Double, width: Double) -> PlayerTapZone {
        guard width > 0, x.isFinite else { return .middle }
        let third = width / 3
        if x < third { return .back }
        if x > width - third { return .forward }
        return .middle
    }
}
