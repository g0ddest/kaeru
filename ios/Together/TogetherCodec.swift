import Foundation
import CryptoKit

enum TogetherSide: UInt8, Sendable { case host = 0, guest = 1; var other: Self { self == .host ? .guest : .host } }
enum TogetherMessageType: String, Codable, Sendable { case hello, play, pause, seek, episode, state, chat, reaction, voice, ping, pong, bye }
enum TogetherReaction: String, Codable, CaseIterable, Sendable {
    case heart, laugh, wow, sad, fire, clap
    var symbol: String { switch self { case .heart: "❤️"; case .laugh: "😂"; case .wow: "😮"; case .sad: "😢"; case .fire: "🔥"; case .clap: "👏" } }
}
struct TogetherMessage: Codable, Equatable, Sendable {
    var t: TogetherMessageType
    var seq: Int64
    var name: String? = nil
    var animeId: Int? = nil
    var episode: Int? = nil
    var translationId: Int? = nil
    var positionMs: Int64? = nil
    var playing: Bool? = nil
    var buffering: Bool? = nil
    var sentAt: Int64? = nil
    var text: String? = nil
    var kind: TogetherReaction? = nil
    var chunk: Int? = nil
    var total: Int? = nil
    var bytes: String? = nil
    var durationMs: Int64? = nil
    var pingSentAt: Int64? = nil
    var receivedAt: Int64? = nil
    var isControl: Bool { [.play, .pause, .seek, .episode].contains(t) }
    func validate() throws {
        guard seq > 0, seq < Int64.max else { throw TogetherError.invalidMessage }
        if let positionMs, !(0...604_800_000).contains(positionMs) { throw TogetherError.invalidMessage }
        for time in [sentAt, receivedAt, pingSentAt].compactMap({ $0 }) {
            guard (0...100_000_000_000_000).contains(time) else { throw TogetherError.invalidMessage }
        }
        if let translationId, translationId < 0 { throw TogetherError.invalidMessage }
        let valid: Bool
        switch t {
        case .hello: valid = name != nil && name!.utf8.count <= 1024 && animeId != nil && animeId! >= 0 && episode != nil && episode! >= 0 && positionMs != nil && playing != nil
        case .play, .pause, .seek: valid = positionMs != nil
        case .episode: valid = episode != nil && (1...100_000).contains(episode!)
        case .state: valid = positionMs != nil && playing != nil && buffering != nil && sentAt != nil
        case .chat: valid = text != nil && text!.utf8.count <= 4096
        case .reaction: valid = kind != nil
        case .ping: valid = sentAt != nil
        case .pong: valid = pingSentAt != nil && receivedAt != nil && sentAt != nil
        case .bye: valid = true
        case .voice:
            valid = chunk != nil && total != nil && (1...8).contains(total!) && (0..<total!).contains(chunk!) && durationMs != nil && (1...30_000).contains(durationMs!) && bytes != nil && bytes!.count <= 43_691 && Data(togetherBase64: bytes!)?.isEmpty == false && Data(togetherBase64: bytes!)!.count <= 32_768
        }
        guard valid else { throw TogetherError.invalidMessage }
    }
}

enum TogetherCodec {
    static let maximumFrameBytes = 65_536
    static func encode(_ message: TogetherMessage, invitation: TogetherInvitation, from: TogetherSide, nonce: Data? = nil) throws -> Data {
        try message.validate()
        let json = JSONEncoder(); json.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let data = try json.encode(message)
        guard data.count + 28 <= maximumFrameBytes else { throw TogetherError.frameTooLarge }
        let nonce = try nonce.map { try AES.GCM.Nonce(data: $0) } ?? AES.GCM.Nonce()
        let sealed = try AES.GCM.seal(data, using: SymmetricKey(data: invitation.key), nonce: nonce, authenticating: aad(invitation, from))
        guard let frame = sealed.combined else { throw TogetherError.authentication }
        return frame
    }
    static func decode(_ frame: Data, invitation: TogetherInvitation, from: TogetherSide) throws -> TogetherMessage {
        guard frame.count <= maximumFrameBytes else { throw TogetherError.frameTooLarge }
        guard frame.count > 28 else { throw TogetherError.authentication }
        let data: Data
        do { data = try AES.GCM.open(AES.GCM.SealedBox(combined: frame), using: SymmetricKey(data: invitation.key), authenticating: aad(invitation, from)) }
        catch { throw TogetherError.authentication }
        return try decodeJSON(data)
    }
    static func decodeJSON(_ data: Data) throws -> TogetherMessage {
        guard data.count <= maximumFrameBytes - 28 else { throw TogetherError.frameTooLarge }
        do { let message = try JSONDecoder().decode(TogetherMessage.self, from: data); try message.validate(); return message }
        catch { throw TogetherError.invalidMessage }
    }
    private static func aad(_ invitation: TogetherInvitation, _ side: TogetherSide) -> Data { Data(invitation.roomID.utf8) + Data([side.rawValue]) }
}
