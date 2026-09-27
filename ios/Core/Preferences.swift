import Foundation

struct PlaybackPreferences: Codable, Equatable {
    var playbackSpeed = 1.0
    var watchedThreshold = 0.9
    var autoSkipEnding = false
    var skipSeconds = 10
    var backgroundPlayback = false
    var pipOnLeave = true
    var studios: [String] = []
    var appearance = "system"
    var notifications = false
    /// Spatial audio for AirPods and the like: the sound anchored to where the device stands.
    /// Optional so settings saved before it existed still decode; unset means the platform's
    /// default — off on the Mac, where it made dialogue sound muffled and off to the side (a
    /// browser plays the same stream as plain stereo), on elsewhere, as the system has it.
    var spatialAudio: Bool?
    /// «Синхронизация между устройствами»: positions and dubs through the Kaeru server. Opt-in —
    /// off unless switched on, so a viewer with one device costs the server nothing.
    var sync: Bool?
    var syncOn: Bool {
        get { sync ?? false }
        set { sync = newValue }
    }
    var spatialAudioOn: Bool {
        get {
            #if os(macOS)
            spatialAudio ?? false
            #else
            spatialAudio ?? true
            #endif
        }
        set { spatialAudio = newValue }
    }

    /// The studios the app ships with (the shared ranker's list), shown until the viewer edits their own.
    static var defaultStudios: [String] { TranslationPreference.defaultStudios }

    mutating func normalize() {
        playbackSpeed = playbackSpeed.isFinite ? min(2, max(0.5, playbackSpeed)) : 1
        watchedThreshold = watchedThreshold.isFinite ? min(1, max(0.5, watchedThreshold)) : 0.9
        skipSeconds = min(90, max(5, skipSeconds))
        appearance = AppAppearance(stored: appearance).rawValue
        var seen = Set<String>()
        studios = studios.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && seen.insert($0.lowercased()).inserted }
    }
}

/// What the app should look like — the one setting allowed to disagree with the system, and only
/// because somebody asked it to.
///
/// The default is to follow iOS, schedule and all. A build that forces a look of its own takes a
/// phone set to light for a reason — a bright room, an eye condition, a preference — and overrules
/// it, which is exactly what the system setting exists to prevent.
enum AppAppearance: String, CaseIterable, Identifiable, Sendable {
    case system, light, dark
    var id: Self { self }

    /// Anything unknown — an older build's value, a hand-edited file — reads as «как в системе».
    init(stored value: String?) { self = AppAppearance(rawValue: value ?? "") ?? .system }

    var title: String {
        switch self {
        case .system: "Как в системе"
        case .light: "Светлая"
        case .dark: "Тёмная"
        }
    }
}
