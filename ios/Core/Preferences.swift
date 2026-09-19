import Foundation

struct PlaybackPreferences: Codable, Equatable {
    var playbackSpeed = 1.0
    var watchedThreshold = 0.9
    var autoSkipOpening = false
    var autoSkipEnding = false
    var skipSeconds = 10
    var backgroundPlayback = false
    var pipOnLeave = true
    var studios: [String] = []
    var appearance = "system"
    var notifications = false

    static let defaultStudios = ["AniLibria", "AniDUB", "Crunchyroll", "Amazing Dubbing", "AniBaza", "AniMaunt", "JAM", "Dream Cast", "SHIZA Project"]

    mutating func normalize() {
        playbackSpeed = playbackSpeed.isFinite ? min(2, max(0.5, playbackSpeed)) : 1
        watchedThreshold = watchedThreshold.isFinite ? min(1, max(0.5, watchedThreshold)) : 0.9
        skipSeconds = min(90, max(5, skipSeconds))
        var seen = Set<String>()
        studios = studios.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty && seen.insert($0.lowercased()).inserted }
    }
}

enum TranslationPreference {
    static func pick(_ available: [Translation], episode: Int, remembered: Int?, studios: [String], usage: [Int: Int]) -> Int {
        let eligible = available.enumerated().filter { $0.element.episodes == 0 || $0.element.episodes >= episode }
        func rank(_ title: String, _ values: [String]) -> Int {
            values.firstIndex { !$0.isEmpty && title.localizedCaseInsensitiveContains($0) } ?? values.count
        }
        return eligible.min { left, right in
            let a = left.element, b = right.element
            let ar = [a.id == remembered ? 0 : 1, rank(a.title, studios), -(usage[a.id] ?? 0), rank(a.title, PlaybackPreferences.defaultStudios), a.kind == "subtitles" ? 1 : 0, -a.episodes, left.offset]
            let br = [b.id == remembered ? 0 : 1, rank(b.title, studios), -(usage[b.id] ?? 0), rank(b.title, PlaybackPreferences.defaultStudios), b.kind == "subtitles" ? 1 : 0, -b.episodes, right.offset]
            return ar.lexicographicallyPrecedes(br)
        }?.element.id ?? 0
    }
}
