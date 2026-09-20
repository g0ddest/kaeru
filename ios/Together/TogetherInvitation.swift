import Foundation
import CryptoKit

/// Public errors deliberately omit URLs, room keys, authorization codes and payloads.
enum TogetherError: LocalizedError, Equatable {
    case invalidInvitation, invalidMessage, frameTooLarge, authentication, notConfigured
    case disconnected, roomFull, expired, timeout, playbackUnavailable, unsupportedVoice
    /// Both phones opened the link, so both sealed their frames as the guest — and a frame is
    /// sealed against the side that sent it, which means neither could read a word of the other.
    /// The room looks alive from the relay's side and says nothing at all from inside.
    case sameSide
    var errorDescription: String? {
        switch self {
        case .invalidInvitation: "Некорректная ссылка приглашения."
        case .invalidMessage, .authentication: "Не удалось проверить сообщение собеседника."
        case .frameTooLarge: "Сообщение слишком большое."
        case .notConfigured: "Сервер совместного просмотра не настроен."
        case .disconnected: "Связь прервалась. Можно продолжить смотреть самостоятельно."
        case .roomFull: "В комнате уже два зрителя."
        case .expired: "Комната закрыта. Создайте новое приглашение."
        case .timeout: "Время ожидания истекло."
        case .playbackUnavailable: "Сначала откройте серию в плеере."
        case .unsupportedVoice: "Этот формат голосового сообщения не поддерживается устройством."
        case .sameSide: "Ссылку открыли оба. Комнату держит тот, кто её создал, — второму надо выйти и остаться в своей."
        }
    }
}

extension Data {
    var togetherBase64: String { base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") }
    init?(togetherBase64 value: String) {
        guard value.utf8.allSatisfy({ (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 45 || $0 == 95 }), value.count % 4 != 1 else { return nil }
        let padded = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/") + String(repeating: "=", count: (4 - value.count % 4) % 4)
        guard let data = Data(base64Encoded: padded), data.togetherBase64 == value else { return nil }
        self = data
    }
}

enum TogetherLANAddress {
    static func isValid(_ value: String) -> Bool {
        let parts = value.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return false }
        var octets = [Int]()
        for part in parts {
            guard !part.isEmpty, part.count <= 3, part.utf8.allSatisfy({ (48...57).contains($0) }), !(part.count > 1 && part.first == "0"), let n = Int(part), n <= 255 else { return false }
            octets.append(n)
        }
        return octets[0] == 10 || (octets[0] == 172 && (16...31).contains(octets[1])) || (octets[0] == 192 && octets[1] == 168) || (octets[0] == 169 && octets[1] == 254)
    }
}

struct TogetherLANEndpoint: Equatable, Sendable { let host: String; let port: UInt16 }
struct TogetherInvitation: Equatable, Sendable, CustomStringConvertible {
    let roomID: String
    let key: Data
    let lan: TogetherLANEndpoint?
    var description: String { "TogetherInvitation(key: <redacted>)" }
    init(roomID: String, key: Data, lan: TogetherLANEndpoint? = nil) throws {
        guard Data(togetherBase64: roomID)?.count == 8, key.count == 16,
              lan == nil || (TogetherLANAddress.isValid(lan!.host) && lan!.port > 0) else { throw TogetherError.invalidInvitation }
        self.roomID = roomID; self.key = key; self.lan = lan
    }
    static func random(lan: TogetherLANEndpoint? = nil) throws -> Self {
        let room = SymmetricKey(size: .bits128).withUnsafeBytes { Data($0.prefix(8)) }
        let key = SymmetricKey(size: .bits128).withUnsafeBytes { Data($0) }
        return try Self(roomID: room.togetherBase64, key: key, lan: lan)
    }
    var shareURL: URL {
        var parts = URLComponents()
        if let lan {
            parts.scheme = "kaeru"; parts.host = "watch"
            parts.queryItems = [.init(name: "h", value: lan.host), .init(name: "p", value: String(lan.port)), .init(name: "r", value: roomID)]
        } else {
            parts.scheme = "https"; parts.host = "kaeru.vitaliy.velikodniy.name"; parts.path = "/w/" + roomID
        }
        parts.fragment = key.togetherBase64
        return parts.url!
    }
    static func parse(_ url: URL) throws -> Self {
        guard url.absoluteString.utf8.count <= 2048, let p = URLComponents(url: url, resolvingAgainstBaseURL: false), p.user == nil, p.password == nil, p.port == nil else { throw TogetherError.invalidInvitation }
        let room: String; let encoded: String?; var endpoint: TogetherLANEndpoint?
        if p.scheme?.lowercased() == "https", p.host?.lowercased() == "kaeru.vitaliy.velikodniy.name", p.path.hasPrefix("/w/") {
            room = String(p.path.dropFirst(3)); encoded = p.percentEncodedFragment
        } else if p.scheme?.lowercased() == "kaeru", p.host?.lowercased() == "watch", p.path.isEmpty {
            let q = try uniqueQuery(p)
            guard let id = q["r"] else { throw TogetherError.invalidInvitation }
            room = id; encoded = p.percentEncodedFragment ?? q["k"]
            if q["h"] != nil || q["p"] != nil {
                guard let h = q["h"], TogetherLANAddress.isValid(h), let raw = q["p"], raw.utf8.allSatisfy({ (48...57).contains($0) }), let port = UInt16(raw), port > 0 else { throw TogetherError.invalidInvitation }
                endpoint = TogetherLANEndpoint(host: h, port: port)
            }
        } else { throw TogetherError.invalidInvitation }
        guard let encoded, let key = Data(togetherBase64: encoded) else { throw TogetherError.invalidInvitation }
        return try Self(roomID: room, key: key, lan: endpoint)
    }
    static func uniqueQuery(_ components: URLComponents) throws -> [String:String] {
        var values = [String:String]()
        for item in components.queryItems ?? [] {
            guard values[item.name] == nil, let value = item.value else { throw TogetherError.invalidInvitation }
            values[item.name] = value
        }
        return values
    }
}
