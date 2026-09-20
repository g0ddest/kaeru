import AuthenticationServices
import Foundation

/// Where a hand-off has got to. Nothing here is reached without somebody pressing something.
enum PairingStage: Equatable {
    /// No television has asked for anything.
    case idle
    /// A television asked, and the person has not answered yet.
    case confirm
    /// The authorization page is open and a code is expected back.
    case awaitingCode
    /// The code is on its way across the local network.
    case sending
    /// The television is signed in.
    case done
    /// The link itself was no good, so there is nothing to confirm.
    case failed
}

/// The phone's side of signing a television in: read the link, ask, fetch one code, hand it over.
///
/// This is the fix for a television that could never finish signing in. What the television shows
/// is a QR code carrying its own address and a one-time nonce; it never shows a code to type. The
/// code that has to cross the local network is an **authorization code this phone has just been
/// issued** — the television spends it on a token itself, which is the whole point of the
/// arrangement, because the alternative is typing an authorization code with a remote control.
///
/// The phone is a courier: it never exchanges the code and never keeps it. The code exists only as
/// a local value between [confirm]'s authorization and the socket write, so «Повторить» after a
/// refusal necessarily means a fresh authorization — a spent code is worth nothing to anybody.
///
/// The `state` of the authorization is checked inside `ASWebAuthenticationSession`'s own callback
/// (`OAuthAttempt`), which on iOS is delivered to the session that opened it rather than to the
/// app's URL handler — so unlike Android there is no window in which another app could fire
/// `kaeru://oauth` and have a television sign into somebody else's account.
@MainActor @Observable final class PairingCoordinator {
    private(set) var stage: PairingStage = .idle
    private(set) var invitation: PairingInvitation?
    /// What to show the person, in their language. Never carries a code, a nonce or an address.
    private(set) var message: String?

    @ObservationIgnored private let authorize: () async throws -> String
    @ObservationIgnored private let deliver: (PairingInvitation, String) async throws -> Void
    /// Fences a hand-off against a screen that was dismissed or pointed at another television.
    @ObservationIgnored private var generation = UUID()

    init(authorize: @escaping () async throws -> String,
         deliver: @escaping (PairingInvitation, String) async throws -> Void = { invitation, code in
             try await TVPairingClient.pair(invitation: invitation, code: code)
         }) {
        self.authorize = authorize
        self.deliver = deliver
    }

    /// Reads one `kaeru://pair` link, from the scanner or from a deep link. Anything can fire one,
    /// so it is checked rather than trusted.
    func open(_ url: URL) {
        do { open(try PairingInvitation.parse(url)) }
        catch {
            generation = UUID()
            invitation = nil; stage = .failed; message = error.localizedDescription
        }
    }

    func open(_ value: PairingInvitation) {
        generation = UUID()
        invitation = value; stage = .confirm; message = nil
    }

    /// Answers «Войти»: one authorization, one code, one POST to the television.
    func confirm() async {
        guard let invitation, stage == .confirm || stage == .done else { return }
        generation = UUID()
        let fence = generation
        stage = .awaitingCode; message = nil
        do {
            let code = try await authorize()
            guard fence == generation else { return }
            stage = .sending
            try await deliver(invitation, code)
            guard fence == generation else { return }
            stage = .done; message = nil
        } catch let error where Self.isCancellation(error) {
            // Closing the browser is an answer, not a failure worth a red line.
            guard fence == generation else { return }
            stage = .confirm; message = nil
        } catch {
            guard fence == generation else { return }
            // Back to the start rather than to a dead end: the code is spent either way.
            stage = .confirm; message = error.localizedDescription
        }
    }

    /// «Отмена» and «Готово» are the same act: the television is no longer this screen's business.
    func dismiss() {
        generation = UUID()
        invitation = nil; stage = .idle; message = nil
    }

    /// Both spellings of «the person closed the browser»: the task's own cancellation and the one
    /// `ASWebAuthenticationSession` reports when its window is dismissed.
    private static func isCancellation(_ error: Error) -> Bool {
        if error is CancellationError { return true }
        let value = error as NSError
        return value.domain == ASWebAuthenticationSessionError.errorDomain
            && value.code == ASWebAuthenticationSessionError.canceledLogin.rawValue
    }
}
