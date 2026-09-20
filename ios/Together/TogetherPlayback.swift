import Foundation

struct TogetherEpisode: Equatable, Sendable {
    var animeID: Int
    var episode: Int
    var translationID: Int? = nil
    var positionMs: Int64 = 0
}
struct TogetherPlaybackSnapshot: Equatable, Sendable {
    var animeID: Int? = nil
    var episode: Int? = nil
    var translationID: Int? = nil
    var positionMs: Int64 = 0
    var playing = false
    var buffering = false
    var ready = false
    var failed = false
}
/// The parent emits localAction only for actual user actions/autoplay, never for these methods.
@MainActor protocol TogetherPlayback: AnyObject {
    var togetherSnapshot: TogetherPlaybackSnapshot { get }
    var togetherSupportsRate: Bool { get }
    func togetherPlay()
    func togetherPause()
    func togetherSeek(toMilliseconds position: Int64)
    func togetherSetRate(_ factor: Float)
    func togetherDuck(_ on: Bool)
    /// Return only once the requested episode is seekable, or throw. Respect Task cancellation.
    func togetherOpen(_ episode: TogetherEpisode) async throws
}
enum TogetherLocalAction: Sendable {
    case play(Int64), pause(Int64), seek(Int64), episode(TogetherEpisode)
}
/// What a transport has to say. The last two are a relay dialling itself back: `reconnecting` is
/// said before the waiting starts so a screen can stop claiming the friend is there, `reconnected`
/// once a socket is up again and everything held back has been written to it.
enum TogetherTransportEvent: Sendable { case frame(Data), peerLeft, reconnecting, reconnected }
@MainActor protocol TogetherTransport: AnyObject {
    func connect(_ invitation: TogetherInvitation, asHost: Bool) async throws
    func receive() async throws -> TogetherTransportEvent
    func send(_ frame: Data) async throws
    func close()
}
