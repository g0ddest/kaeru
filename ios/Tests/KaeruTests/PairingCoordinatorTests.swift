import XCTest
@testable import Kaeru

@MainActor final class PairingCoordinatorTests: XCTestCase {
    private let link = URL(string: "kaeru://pair?host=192.168.1.2&port=4321&nonce=tv-nonce&name=Гостиная")!

    private func make(authorize: @escaping () async throws -> String = { "code" },
                      deliver: @escaping (PairingInvitation, String) async throws -> Void = { _, _ in }) -> PairingCoordinator {
        PairingCoordinator(authorize: authorize, deliver: deliver)
    }

    func testAValidLinkAsksBeforeAnythingIsAuthorized() {
        let coordinator = make(authorize: { XCTFail("nothing is authorized before «Войти»"); return "" })
        coordinator.open(link)
        XCTAssertEqual(coordinator.stage, .confirm)
        XCTAssertEqual(coordinator.invitation?.name, "Гостиная")
        XCTAssertNil(coordinator.message)
    }

    /// The television has not been touched at this point, so the link is what is wrong.
    func testABadLinkSaysTheLinkIsBad() {
        let coordinator = make()
        coordinator.open(URL(string: "kaeru://pair?host=8.8.8.8&port=4321&nonce=abc")!)
        XCTAssertEqual(coordinator.stage, .failed)
        XCTAssertNil(coordinator.invitation)
        XCTAssertEqual(coordinator.message, TogetherError.invalidInvitation.errorDescription)
    }

    /// The whole point of C3: what crosses the network is the code this phone was just issued,
    /// not anything the viewer read off the television.
    func testTheTelevisionGetsThisPhonesOwnAuthorizationCode() async {
        var delivered: (PairingInvitation, String)?
        let coordinator = make(authorize: { "fresh-authorization-code" }, deliver: { delivered = ($0, $1) })
        coordinator.open(link)
        await coordinator.confirm()
        XCTAssertEqual(delivered?.1, "fresh-authorization-code")
        XCTAssertEqual(delivered?.0.nonce, "tv-nonce")
        XCTAssertEqual(coordinator.stage, .done)
        XCTAssertNil(coordinator.message)
    }

    /// A code is spent whether or not it landed, so «Повторить» has to mean a new authorization —
    /// which is why the coordinator keeps none.
    func testARefusedHandoffReturnsToConfirmAndAuthorizesAfresh() async {
        var codes = ["first", "second"]
        var delivered: [String] = []
        let coordinator = make(authorize: { codes.removeFirst() }, deliver: { _, code in
            delivered.append(code)
            if delivered.count == 1 { throw PairingError.exchangeFailed }
        })
        coordinator.open(link)
        await coordinator.confirm()
        XCTAssertEqual(coordinator.stage, .confirm)
        XCTAssertEqual(coordinator.message, PairingError.exchangeFailed.errorDescription)
        await coordinator.confirm()
        XCTAssertEqual(delivered, ["first", "second"])
        XCTAssertEqual(coordinator.stage, .done)
    }

    func testAnExpiredInvitationIsReported() async {
        let coordinator = make(deliver: { _, _ in throw PairingError.expired })
        coordinator.open(link)
        await coordinator.confirm()
        XCTAssertEqual(coordinator.stage, .confirm)
        XCTAssertEqual(coordinator.message, PairingError.expired.errorDescription)
    }

    /// Closing the browser is an answer, not a failure worth a red line.
    func testACancelledAuthorizationSaysNothing() async {
        let coordinator = make(authorize: { throw CancellationError() }, deliver: { _, _ in XCTFail("nothing to deliver") })
        coordinator.open(link)
        await coordinator.confirm()
        XCTAssertEqual(coordinator.stage, .confirm)
        XCTAssertNil(coordinator.message)
    }

    func testConfirmDoesNothingWithoutATelevision() async {
        let coordinator = make(authorize: { XCTFail("no television is waiting"); return "" })
        await coordinator.confirm()
        XCTAssertEqual(coordinator.stage, .idle)
    }

    func testDismissForgetsTheTelevision() async {
        let coordinator = make()
        coordinator.open(link)
        await coordinator.confirm()
        coordinator.dismiss()
        XCTAssertEqual(coordinator.stage, .idle)
        XCTAssertNil(coordinator.invitation)
        XCTAssertNil(coordinator.message)
    }

    /// Byte-compatibility with the Android television: the payload it parses is this exact object.
    func testTheWirePayloadCarriesTheCodeAndTheRedirectItBelongsTo() throws {
        let invitation = try PairingInvitation.parse(link)
        let request = try PairingWire.request(invitation: invitation, code: "fresh-authorization-code")
        let body = request.subdata(in: request.range(of: Data("\r\n\r\n".utf8))!.upperBound..<request.count)
        XCTAssertEqual(try JSONSerialization.jsonObject(with: body) as? [String: String],
                       ["nonce": "tv-nonce", "code": "fresh-authorization-code", "redirectUri": "kaeru://oauth"])
    }
}
