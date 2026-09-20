package app.kaeru.data.auth

import app.kaeru.data.library.AppPreferences
import app.kaeru.data.shikimori.ShikimoriApi
import app.kaeru.data.shikimori.toDomainFailure
import app.kaeru.domain.error.AuthCallbackRejected
import app.kaeru.domain.error.SignInUnavailable
import app.kaeru.domain.repository.AuthRepository
import app.kaeru.domain.repository.MOBILE_REDIRECT
import app.kaeru.domain.repository.OOB_REDIRECT
import app.kaeru.domain.repository.PairingAuthorization
import app.kaeru.shared.data.shikimori.ShikimoriClient
import app.kaeru.shared.data.shikimori.UserDto
import app.kaeru.shared.data.shikimori.shikimoriUrl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Sign-in and sign-out, over two halves of the shared client: the code exchange goes to Kaeru's
 * proxy through [client] directly — there is no session yet to speak of — and the `whoami` that
 * verifies the identity goes through [api] with the candidate token named explicitly, so it is
 * never swapped for the account's and never refreshed.
 */
@Singleton
class ShikimoriAuthRepository @Inject constructor(
    private val client: ShikimoriClient,
    private val api: ShikimoriApi,
    private val session: AccountSession,
    private val prefs: AppPreferences,
    @param:Named("shikimoriClientId") private val clientId: String,
    private val clock: Clock,
) : AuthRepository {
    private val random = SecureRandom()

    /**
     * The `state` of the authorization attempt started last, held in memory only: after process
     * death there is no attempt in flight, and a callback arriving then is exactly the unsolicited
     * one we want to refuse.
     */
    @Volatile private var pendingState: String? = null

    override val isLoggedIn: Flow<Boolean> = session.userId.map { it != null }.distinctUntilChanged()

    override fun authorizeUrl(redirectUri: String): String {
        val state = newState()
        pendingState = state
        return authorizePage(redirectUri, state)
    }

    /**
     * The same page, with the `state` handed out rather than remembered: a code fetched here is
     * for a television, and leaving one armed on this phone would outlive the hand-off by the
     * life of the process.
     */
    override fun pairingAuthorization(): PairingAuthorization =
        newState().let { state -> PairingAuthorization(authorizePage(MOBILE_REDIRECT, state), state) }

    private fun newState(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also(random::nextBytes))

    private fun authorizePage(redirectUri: String, state: String): String {
        val redirect = URLEncoder.encode(redirectUri, "UTF-8")
        val client = URLEncoder.encode(clientId, "UTF-8")
        return "${ShikimoriClient.BASE_URL}/oauth/authorize?client_id=$client&redirect_uri=$redirect" +
            "&response_type=code&scope=user_rates&state=${URLEncoder.encode(state, "UTF-8")}"
    }

    override suspend fun exchangeRedirectCode(code: String, state: String?): Result<Unit> {
        // A silent account switch would wipe the signed-in user's cached rates and watch history.
        if (isLoggedIn.first()) return rejected("An account is already signed in")
        val expected = pendingState
        pendingState = null // Single use: a replayed callback finds nothing pending.
        if (expected == null) return rejected("No authorization was started from this app")
        if (state == null || !matches(expected, state)) return rejected("Callback state does not match")
        if (code.isBlank()) return rejected("Callback carried no authorization code")
        return exchangeCode(code, MOBILE_REDIRECT)
    }

    /**
     * The television's side of the QR hand-off. The guards mirror [exchangeRedirectCode]'s: an
     * account already signed in is never swapped out from under itself, and the redirect the phone
     * reports has to be one of this app's own — a caller on the local network must not be able to
     * choose where the token request claims it came from.
     */
    override suspend fun exchangePairedCode(code: String, redirectUri: String): Result<Unit> {
        if (isLoggedIn.first()) return rejected("An account is already signed in")
        if (redirectUri != MOBILE_REDIRECT && redirectUri != OOB_REDIRECT) {
            return rejected("Pairing named a redirect this app does not use")
        }
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return rejected("Pairing carried no authorization code")
        return exchangeCode(trimmed, redirectUri)
    }

    override suspend fun exchangeTypedCode(code: String): Result<Unit> =
        exchangeCode(code.trim(), OOB_REDIRECT)

    /**
     * The `whoami` that verifies the identity also names it, so the nickname and avatar the
     * settings screen shows are written down here rather than fetched again on first open.
     *
     * The name is written outside the sign-in's own `try`, after the transition has committed. By
     * that point the tokens and the user id are already in their two stores and the viewer *is*
     * signed in; a failure writing a nickname must not be reported as a sign-in that did not
     * happen. See [rememberProfile].
     */
    internal suspend fun exchangeCode(code: String, redirectUri: String): Result<Unit> {
        var profile: UserDto? = null
        val signedIn = try {
            session.login {
                // A build assembled without the worker's address has nowhere to send the code,
                // and sending it to Shikimori regardless would earn an `invalid_client` and burn
                // it. Failing first keeps the code on the device.
                if (!client.oauthConfigured) throw SignInUnavailable()
                val tokens = client.token("authorization_code", code, redirectUri)
                val user = api.whoami(accessToken = tokens.accessToken)
                profile = user
                AuthTokens(
                    tokens.accessToken,
                    tokens.refreshToken,
                    clock.instant().epochSecond + tokens.expiresIn,
                    user.id,
                )
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error.toDomainFailure())
        }
        if (signedIn.isSuccess) rememberProfile(profile)
        return signedIn
    }

    /**
     * Writes the name and the face down, best effort.
     *
     * Two things can go wrong here and neither is worth a failed sign-in. The preference store is a
     * different file from the token store, so it can fail on its own; and a sign-out winning the
     * race leaves a nickname with no user id beside it, which `AppPreferences.account` reads as
     * nobody signed in. Either way the name is recoverable — `AccountRepository.refresh()` asks
     * again when the settings screen opens — and the sign-in itself has already committed.
     */
    private suspend fun rememberProfile(profile: UserDto?) {
        if (profile == null) return
        try {
            prefs.setAccountProfile(profile.nickname, shikimoriUrl(profile.avatar))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Deliberately swallowed: see above. The next `whoami` fills the gap.
        }
    }

    override suspend fun logout() = session.logout()

    private fun rejected(reason: String): Result<Unit> = Result.failure(AuthCallbackRejected(reason))

    private fun matches(expected: String, actual: String) =
        MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))
}
