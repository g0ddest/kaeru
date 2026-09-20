import XCTest
@testable import Kaeru

final class PairingProtocolTests: XCTestCase {
    func testValidatedLinkAndBoundedHTTPCodePayload() throws {
        let invitation = try PairingInvitation.parse(URL(string: "kaeru://pair?host=192.168.1.2&port=4321&nonce=abc&name=Living%20Room")!)
        XCTAssertEqual(invitation.name, "Living Room")
        let request = try PairingWire.request(invitation: invitation, code: "one-time-code")
        let text = String(decoding: request, as: UTF8.self)
        XCTAssertTrue(text.hasPrefix("POST /pair HTTP/1.1\r\n"))
        XCTAssertTrue(text.contains("Connection: close\r\n"))
        let body = request.subdata(in: request.range(of: Data("\r\n\r\n".utf8))!.upperBound..<request.count)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String:String])
        XCTAssertEqual(json, ["nonce":"abc", "code":"one-time-code", "redirectUri":"kaeru://oauth"])
        XCTAssertTrue(text.contains("Content-Length: \(body.count)\r\n"))
    }
    func testPairingMalformedLinks() {
        for raw in [
            "kaeru://pair?host=8.8.8.8&port=80&nonce=a",
            "kaeru://pair?host=192.168.1.2&port=0&nonce=a",
            "kaeru://pair?host=192.168.1.2&port=80&nonce=",
            "kaeru://pair?host=192.168.1.2&port=80&port=81&nonce=a",
            "kaeru://pair/extra?host=192.168.1.2&port=80&nonce=a"
        ] { XCTAssertThrowsError(try PairingInvitation.parse(URL(string: raw)!), raw) }
    }
    func testPairingOnlyAcceptsBoundedAffirmativeResponse() throws {
        XCTAssertTrue(try PairingWire.response(Data("HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\n{\"ok\":true}".utf8)))
        for raw in [
            "HTTP/1.1 200 OK\r\nContent-Length: 12\r\n\r\n{\"ok\":false}",
            "HTTP/1.1 302 Found\r\nContent-Length: 11\r\n\r\n{\"ok\":true}",
            "HTTP/1.1 200 OK\r\nContent-Length: 90000\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 11\r\nContent-Length: 11\r\n\r\n{\"ok\":true}"
        ] { XCTAssertThrowsError(try PairingWire.response(Data(raw.utf8)), raw) }
    }
}
