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
        precondition(NextEpisodeRules.shared.hasNextEpisode(episode: 3, availableEpisodes: 5))
        precondition(EpisodeProgressRules.shared.watched(positionMs: 900, durationMs: 1000, threshold: 0.9))
        precondition(TranslationRanker.shared.pick(tracks: [TranslationCandidate(id: 4, title: "AniLibria", kind: .voice, episodesCount: nil)], preferred: [], rememberedId: KotlinInt(int: 4), usage: [:])?.id == 4)
        print("Swift playback bridge PASS")
    }
}
