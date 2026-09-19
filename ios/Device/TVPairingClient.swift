import Foundation

@MainActor enum TVPairingClient {
    static func pair(invitation: PairingInvitation, code: String) async throws {
        let connection = try KaeruLANConnection(host: invitation.host, port: invitation.port)
        defer { connection.close() }
        try await connection.start()
        try await connection.send(try PairingWire.request(invitation: invitation, code: code))
        var response = Data()
        while response.count <= PairingWire.maximumBytes {
            if let expected = try PairingWire.expectedLength(response), response.count >= expected {
                try PairingWire.response(response.prefix(expected))
                return
            }
            let remaining = PairingWire.maximumBytes - response.count
            guard remaining > 0 else { throw PairingError.malformedResponse }
            response.append(try await connection.read(maximum: min(4096, remaining)))
        }
        throw PairingError.malformedResponse
    }
}
