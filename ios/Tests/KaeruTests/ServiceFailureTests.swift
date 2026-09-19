import XCTest
import KaeruShared
@testable import Kaeru

/// Reading a status off a failure rather than out of a sentence. The whole point is that a message
/// may be reworded or localised at any time and none of this may notice.
final class ServiceFailureTests: XCTestCase {
    /// How Kotlin/Native hands a thrown exception to Swift: an `NSError` with the throwable inside.
    private func bridged(_ exception: ApiException) -> NSError {
        NSError(domain: "KotlinException", code: 0, userInfo: ["KotlinException": exception])
    }

    func testTheStatusComesOffTheExceptionNotTheMessage() throws {
        let failure = try XCTUnwrap(ServiceFailure.of(bridged(ApiException(status: 401, oauthError: nil, message: "Что угодно"))))
        XCTAssertEqual(failure.status, 401)
        XCTAssertNil(failure.oauthError)
    }

    func testARejectedRefreshIsNamedByItsOAuthError() throws {
        let failure = try XCTUnwrap(ServiceFailure.of(bridged(ApiException(status: 400, oauthError: "invalid_grant", message: "…"))))
        XCTAssertEqual(failure.oauthError, "invalid_grant")
        XCTAssertEqual(failure.status, 400)
    }

    /// The old reading would have taken all three of these for an expired token.
    func testASentenceThatMerelyMentionsAStatusIsNotOne() {
        for message in ["Не удалось загрузить: 401 эпизод", "Ошибка 401", "invalid_grant"] {
            XCTAssertNil(ServiceFailure.of(AppError.message(message)), message)
        }
    }

    func testWrappingLeavesAnythingItCannotTypeAlone() {
        let plain = AppError.signedOut
        XCTAssertNil(ServiceFailure.of(plain))
        XCTAssertEqual((ServiceFailure.wrap(plain) as? AppError)?.localizedDescription, plain.localizedDescription)
        let typed = ServiceFailure(status: 404, oauthError: nil, underlying: plain)
        XCTAssertEqual(ServiceFailure.of(typed)?.status, 404)
    }
}
