import Foundation

struct PairingInvitation: Equatable, Identifiable, Sendable, CustomStringConvertible {
    let host: String
    let port: UInt16
    let nonce: String
    let name: String
    var id: String { host + ":" + String(port) }
    var description: String { "PairingInvitation(nonce: <redacted>)" }
    static func parse(_ url: URL) throws -> Self {
        guard url.absoluteString.utf8.count <= 2048, let p = URLComponents(url: url, resolvingAgainstBaseURL: false), p.scheme?.lowercased() == "kaeru", p.host?.lowercased() == "pair", p.path.isEmpty, p.user == nil, p.password == nil, p.port == nil, p.fragment == nil else { throw TogetherError.invalidInvitation }
        let q = try TogetherInvitation.uniqueQuery(p)
        guard let host = q["host"], TogetherLANAddress.isValid(host), let raw = q["port"], raw.utf8.allSatisfy({ (48...57).contains($0) }), let port = UInt16(raw), port > 0, let nonce = q["nonce"], !nonce.isEmpty, nonce.utf8.count <= 128, !nonce.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else { throw TogetherError.invalidInvitation }
        return Self(host: host, port: port, nonce: nonce, name: String((q["name"] ?? "").prefix(24)))
    }
}

enum PairingError: LocalizedError, Equatable {
    case refused, malformedResponse, expired, exchangeFailed, unavailable
    var errorDescription: String? {
        switch self {
        case .refused: "Телевизор не принял код. Откройте новое приглашение."
        case .malformedResponse: "Телевизор вернул некорректный ответ."
        case .expired: "Приглашение истекло или уже использовано."
        case .exchangeFailed: "Не удалось войти на телевизоре. Попробуйте снова."
        case .unavailable: "Не удалось подключиться к телевизору. Проверьте общую сеть Wi-Fi."
        }
    }
}

enum PairingWire {
    static let maximumBytes = 16_384
    static func request(invitation: PairingInvitation, code: String) throws -> Data {
        guard TogetherLANAddress.isValid(invitation.host), invitation.port > 0, !code.isEmpty, code.utf8.count <= 4096 else { throw TogetherError.invalidInvitation }
        let body = try JSONSerialization.data(withJSONObject: ["nonce": invitation.nonce, "code": code, "redirectUri": "kaeru://oauth"], options: [.sortedKeys, .withoutEscapingSlashes])
        let header = "POST /pair HTTP/1.1\r\nHost: \(invitation.host):\(invitation.port)\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: \(body.count)\r\nConnection: close\r\n\r\n"
        return Data(header.utf8) + body
    }
    /// Returns nil until a complete bounded response has arrived. Never follows redirects.
    static func expectedLength(_ data: Data) throws -> Int? {
        guard data.count <= maximumBytes else { throw PairingError.malformedResponse }
        guard let boundary = data.range(of: Data("\r\n\r\n".utf8)) else {
            guard data.count <= 8192 else { throw PairingError.malformedResponse }; return nil
        }
        guard boundary.lowerBound <= 8192, let header = String(data: data[..<boundary.lowerBound], encoding: .utf8) else { throw PairingError.malformedResponse }
        let lines = header.components(separatedBy: "\r\n")
        var length: Int?
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { throw PairingError.malformedResponse }
            let field = line[..<colon].lowercased()
            if field == "transfer-encoding" { throw PairingError.malformedResponse }
            if field == "content-length" {
                let raw = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
                guard length == nil, !raw.isEmpty, raw.utf8.allSatisfy({ (48...57).contains($0) }), let parsed = Int(raw), (1...8192).contains(parsed) else { throw PairingError.malformedResponse }
                length = parsed
            }
        }
        guard let length else { throw PairingError.malformedResponse }
        return boundary.upperBound + length
    }
    static func response(_ data: Data) throws -> Bool {
        guard let length = try expectedLength(data), length == data.count, let boundary = data.range(of: Data("\r\n\r\n".utf8)), let header = String(data: data[..<boundary.lowerBound], encoding: .utf8) else { throw PairingError.malformedResponse }
        let status = header.components(separatedBy: "\r\n")[0].split(separator: " ")
        guard status.count >= 2, ["HTTP/1.1", "HTTP/1.0"].contains(String(status[0])), let code = Int(status[1]) else { throw PairingError.malformedResponse }
        let body = data[boundary.upperBound...]
        if code == 410 { throw PairingError.expired }
        if code == 409, let value = try? JSONSerialization.jsonObject(with: body) as? [String:String], value["error"] == "exchange_failed" { throw PairingError.exchangeFailed }
        guard (200...299).contains(code) else { throw PairingError.refused }
        struct Answer: Decodable { let ok: Bool }
        guard (try? JSONDecoder().decode(Answer.self, from: body).ok) == true else { throw PairingError.refused }
        return true
    }
}
