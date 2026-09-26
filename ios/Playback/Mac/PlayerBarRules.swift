import Foundation

/// The arithmetic behind the Mac player's control bar (PlayerBar.swift): its clock, its timeline
/// and its volume. Apart from the view so it can be tested without drawing anything.
enum PlayerBarRules {
    /// «1:05», or «1:05:03» past an hour — and past an hour too when `reference`, the episode's
    /// length, is, so the two ends of the bar keep their width as the playhead crosses the hour.
    static func clock(_ seconds: Double, reference: Double = 0) -> String {
        let total = seconds.isFinite ? Int(max(0, seconds)) : 0
        let hours = total / 3600, minutes = (total % 3600) / 60, rest = total % 60
        let long = hours > 0 || (reference.isFinite && reference >= 3600)
        return long ? String(format: "%d:%02d:%02d", hours, minutes, rest)
                    : String(format: "%d:%02d", minutes, rest)
    }

    /// What is left of the episode, counting down: «−23:00».
    static func remaining(position: Double, duration: Double) -> String {
        let left = duration.isFinite && position.isFinite ? max(0, duration - position) : 0
        return "−" + clock(left, reference: duration)
    }

    /// How far into the episode the playhead is, from 0 to 1; nothing while its length is unknown.
    static func progress(position: Double, duration: Double) -> Double {
        guard duration.isFinite, duration > 0, position.isFinite else { return 0 }
        return min(1, max(0, position / duration))
    }

    /// Where along a track of `width` points the pointer is, from 0 to 1.
    static func fraction(at x: Double, width: Double) -> Double {
        guard width > 0, x.isFinite else { return 0 }
        return min(1, max(0, x / width))
    }

    /// The second a released scrub goes to, or nothing while the episode's length is unknown.
    static func seekTarget(fraction: Double, duration: Double) -> Double? {
        guard duration.isFinite, duration > 0, fraction.isFinite else { return nil }
        return min(1, max(0, fraction)) * duration
    }

    /// The fifth of the volume the episode keeps while a friend talks over it. Android's
    /// `ExoPlaybackEngine.DUCKED_VOLUME`.
    static let duckedShare: Float = 0.2

    /// What the player actually plays at: the viewer's own volume, turned down to a fifth while a
    /// friend talks over the episode — a fifth of what the viewer chose, not of the loudest.
    static func effectiveVolume(user: Float, ducked: Bool) -> Float {
        let chosen = user.isFinite ? min(1, max(0, user)) : 1
        return ducked ? chosen * duckedShare : chosen
    }

    /// The speaker drawn on the mute button: struck through when there is nothing to hear.
    static func volumeSymbol(volume: Float, muted: Bool) -> String {
        if muted || volume <= 0 { return "speaker.slash.fill" }
        if volume < 0.34 { return "speaker.wave.1.fill" }
        if volume < 0.67 { return "speaker.wave.2.fill" }
        return "speaker.wave.3.fill"
    }

    /// The volume slider is out beside the speaker while the pointer is on the pair, while it is
    /// being dragged — the pointer may wander off the thin track — and while VoiceOver is on it.
    static func volumeExpanded(hovering: Bool, dragging: Bool, focused: Bool) -> Bool {
        hovering || dragging || focused
    }
}
