import XCTest
@testable import Kaeru

final class KeychainTests: XCTestCase {
    func testSecureSessionRoundtripAndDelete() throws {
        let service = "app.kaeru.ios.tests.\(UUID().uuidString)"
        defer { try? KeychainSession.write(nil, service: service) }
        XCTAssertNil(try KeychainSession.read(service: service))
        let session = Session(account: Account(id: 123, nickname: "Fixture", avatar: ""), tokens: Tokens(access_token: "dummy", refresh_token: "dummy-refresh", expires_in: 60, created_at: 1))
        try KeychainSession.write(session, service: service)
        XCTAssertEqual(try KeychainSession.read(service: service)?.tokens.refresh_token, "dummy-refresh")
        try KeychainSession.write(nil, service: service)
        XCTAssertNil(try KeychainSession.read(service: service))
    }
}
