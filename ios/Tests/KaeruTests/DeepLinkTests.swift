import XCTest
@testable import Kaeru

final class DeepLinkTests: XCTestCase {
    func testTitleLinksCarryTheEpisodeTheNotificationMeant() throws {
        XCTAssertEqual(DeepLink.parse(URL(string: "kaeru://anime/42")!), .title(id: 42, episode: nil))
        XCTAssertEqual(DeepLink.parse(URL(string: "kaeru://anime/42?episode=7")!), .title(id: 42, episode: 7))
        XCTAssertEqual(DeepLink.parse(URL(string: "KAERU://ANIME/42")!), .title(id: 42, episode: nil))
    }

    /// The two URLs a new-episode notification actually carries have to survive the round trip,
    /// because nothing else in the app constructs them.
    func testNotificationURLsRoundTrip() {
        let release = EpisodeRelease(animeID: 1234, title: "Тайтл", episode: 5, aired: 6)
        XCTAssertEqual(DeepLink.parse(release.titleURL), .title(id: 1234, episode: nil))
        XCTAssertEqual(DeepLink.parse(release.watchURL), .title(id: 1234, episode: 5))
    }

    func testMalformedTitleLinksAreRefused() {
        for raw in [
            "kaeru://anime",
            "kaeru://anime/",
            "kaeru://anime/0",
            "kaeru://anime/-1",
            "kaeru://anime/12a",
            "kaeru://anime/12/34",
            "kaeru://anime/1234567890123",
            "kaeru://anime/42?episode=0",
            "kaeru://anime/42?episode=abc",
            "kaeru://anime/42?episode=1&episode=2",
            "kaeru://anime/42?episode=99999",
            "kaeru://anime/42#fragment",
            "https://kaeru.vitaliy.velikodniy.name/anime/42",
            "kaeru://user:secret@anime/42",
        ] { XCTAssertNil(DeepLink.parse(URL(string: raw)!), raw) }
    }

    /// The callback comes back through the authorization session, never through the router: a page
    /// able to fire `kaeru://oauth` must not reach anything here.
    func testOAuthCallbacksAndUnknownHostsAreNotRouted() {
        XCTAssertNil(DeepLink.parse(URL(string: "kaeru://oauth?code=abc&state=def")!))
        XCTAssertNil(DeepLink.parse(URL(string: "kaeru://settings")!))
        XCTAssertNil(DeepLink.parse(URL(string: "kaeru://")!))
    }

    func testInvitationsArriveOverBothTheWebAddressAndTheScheme() throws {
        let invitation = try TogetherInvitation.random()
        XCTAssertEqual(DeepLink.parse(invitation.shareURL), .watch(invitation))
        let lan = try TogetherInvitation(roomID: invitation.roomID, key: invitation.key,
                                         lan: TogetherLANEndpoint(host: "192.168.1.9", port: 8080))
        XCTAssertEqual(DeepLink.parse(lan.shareURL), .watch(lan))
        let keyInQuery = URL(string: "kaeru://watch?r=\(invitation.roomID)&k=\(invitation.key.togetherBase64)")!
        XCTAssertEqual(DeepLink.parse(keyInQuery), .watch(invitation))
    }

    func testHostileInvitationsAreRefused() throws {
        let invitation = try TogetherInvitation.random()
        for raw in [
            "https://kaeru.example.com/w/\(invitation.roomID)#\(invitation.key.togetherBase64)",
            "https://kaeru.vitaliy.velikodniy.name/w/\(invitation.roomID)?k=\(invitation.key.togetherBase64)",
            "kaeru://watch?r=\(invitation.roomID)",
            "kaeru://watch?r=\(invitation.roomID)&h=8.8.8.8&p=80#\(invitation.key.togetherBase64)",
        ] { XCTAssertNil(DeepLink.parse(URL(string: raw)!), raw) }
    }

    func testPairingLinksAreRefusedUnlessTheyPointAtThisNetwork() {
        guard case .pair(let invitation)? = DeepLink.parse(URL(string: "kaeru://pair?host=192.168.1.2&port=4321&nonce=abc&name=Гостиная")!) else {
            return XCTFail("a link to a television on this network is a pairing link")
        }
        XCTAssertEqual(invitation.host, "192.168.1.2")
        XCTAssertEqual(invitation.nonce, "abc")
        XCTAssertNil(DeepLink.parse(URL(string: "kaeru://pair?host=8.8.8.8&port=4321&nonce=abc")!))
        XCTAssertNil(DeepLink.parse(URL(string: "kaeru://pair?host=127.0.0.1&port=4321&nonce=abc")!))
    }

    /// A room key and a television's one-time secret must not reach a log through a description.
    func testDescriptionsCarryNoSecret() throws {
        let invitation = try TogetherInvitation.random()
        let watch = DeepLink.watch(invitation)
        XCTAssertFalse("\(watch)".contains(invitation.key.togetherBase64))
        let pair = DeepLink.pair(try PairingInvitation.parse(URL(string: "kaeru://pair?host=10.0.0.5&port=9&nonce=s3cret")!))
        XCTAssertFalse("\(pair)".contains("s3cret"))
    }

    func testAnInvitationWaitsForSignIn() throws {
        let invitation = try TogetherInvitation.random()
        XCTAssertEqual(DeepLinkRouting.destination(for: .watch(invitation), signedIn: false, playerOpen: false),
                       .held(.watch(invitation)))
        XCTAssertEqual(DeepLinkRouting.destination(for: .watch(invitation), signedIn: true, playerOpen: false),
                       .watch(invitation))
    }

    /// A tap on a notification while an episode is playing means «покажи тайтл», never «открой
    /// второй плеер поверх первого».
    func testAnOpenPlayerKeepsALinkFromOpeningASecond() {
        XCTAssertEqual(DeepLinkRouting.destination(for: .title(id: 7, episode: 3), signedIn: true, playerOpen: false),
                       .title(id: 7, episode: 3))
        XCTAssertEqual(DeepLinkRouting.destination(for: .title(id: 7, episode: 3), signedIn: true, playerOpen: true),
                       .title(id: 7, episode: nil))
        XCTAssertEqual(DeepLinkRouting.destination(for: .title(id: 7, episode: nil), signedIn: false, playerOpen: false),
                       .title(id: 7, episode: nil))
    }

    /// Signing a television in is the phone's own authorization; it needs no account here first.
    func testPairingIsRoutedWhetherOrNotThePhoneIsSignedIn() throws {
        let invitation = try PairingInvitation.parse(URL(string: "kaeru://pair?host=10.0.0.5&port=9&nonce=abc")!)
        XCTAssertEqual(DeepLinkRouting.destination(for: .pair(invitation), signedIn: false, playerOpen: false),
                       .pair(invitation))
        XCTAssertEqual(DeepLinkRouting.destination(for: .pair(invitation), signedIn: true, playerOpen: true),
                       .pair(invitation))
    }
}
