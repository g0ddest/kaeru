import AuthenticationServices
import UIKit

@MainActor final class Authentication: NSObject, ASWebAuthenticationPresentationContextProviding {
    private var browser: ASWebAuthenticationSession?
    private var attempt = OAuthAttempt()
    func signIn(clientID: String) async throws -> String {
        guard browser == nil else { throw AppError.message("Вход уже открыт.") }
        let state = UUID().uuidString + UUID().uuidString
        attempt.begin(state: state)
        var url = URLComponents(string: "https://shikimori.io/oauth/authorize")!
        url.queryItems = [URLQueryItem(name: "client_id", value: clientID), URLQueryItem(name: "redirect_uri", value: "kaeru://oauth"), URLQueryItem(name: "response_type", value: "code"), URLQueryItem(name: "scope", value: "user_rates"), URLQueryItem(name: "state", value: state)]
        defer { browser = nil; attempt.cancel() }
        return try await withCheckedThrowingContinuation { continuation in
            let session = ASWebAuthenticationSession(url: url.url!, callbackURLScheme: "kaeru") { [weak self] url, error in
                Task { @MainActor in
                    guard let self else { continuation.resume(throwing: CancellationError()); return }
                    do {
                        if let error { throw error }
                        guard let url else { throw AppError.invalidCallback }
                        continuation.resume(returning: try self.attempt.consume(url))
                    } catch { continuation.resume(throwing: error) }
                }
            }
            session.presentationContextProvider = self
            session.prefersEphemeralWebBrowserSession = true
            browser = session
            if !session.start() { continuation.resume(throwing: AppError.message("Не удалось открыть окно входа.")) }
        }
    }
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows).first(where: \.isKeyWindow) ?? ASPresentationAnchor()
    }
}
