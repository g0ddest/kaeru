import Foundation
import KaeruShared

@main struct SharedBridgeSmoke {
    static func main() async {
        precondition(CommandLine.arguments.count == 2, "Pass the local fixture proxy URL")
        let api = NativeApi(clientId: "test", proxyUrl: CommandLine.arguments[1])
        defer { api.close() }
        do {
            _ = try await api.exchange(code: "test+&日本")
            fatalError("Expected HTTP 401 from the fixture proxy")
        } catch {
            let description = (error as NSError).localizedDescription
            precondition(description.contains("401"), description)
            precondition(description.contains("Authentication"), description)
            precondition(!description.contains("private-response-body"), description)
            print("HTTP 401 NSError bridge PASS: \(description)")
        }
        precondition(PlaybackRules.shared.nextEpisode(watched: 3, aired: 5) == 4)
        precondition(PlaybackRules.shared.shouldMarkWatched(positionMs: 900, durationMs: 1000))
        precondition(PlaybackRules.shared.preferredTranslation(ids: [KotlinInt(int: 4)], remembered: 4) == 4)
        print("Swift playback bridge PASS")
    }
}
