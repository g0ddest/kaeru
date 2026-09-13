package app.kaeru.ui.tv.auth

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kaeru.domain.pairing.PairingRequest
import app.kaeru.domain.pairing.PairingSession
import app.kaeru.domain.pairing.TvPairingServer
import app.kaeru.domain.repository.AuthRepository
import app.kaeru.ui.common.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

/** What the television is doing about the code on its screen. */
enum class TvPairingStatus {
    /** The port is being opened; there is no code to show yet. */
    STARTING,

    /** A code is on screen and nothing has scanned it. */
    WAITING,

    /** A phone sent a code and the tokens are being fetched. */
    CONFIRMING,

    /** The five minutes ran out. Nothing is listening until a new code is asked for. */
    EXPIRED,

    /** This television has no address on a local network, so no phone can reach it. */
    UNAVAILABLE,
}

data class TvPairingUiState(
    val deviceName: String = "",
    val pairingUri: String? = null,
    val expiresAt: Instant? = null,
    val status: TvPairingStatus = TvPairingStatus.STARTING,
    val errorMessage: String? = null,
)

/**
 * Holds the television's end of the hand-off open for exactly as long as its login screen is.
 *
 * The port is the reason this is a view model rather than a remembered value: a listening socket
 * that outlives the screen it belongs to is a socket nobody is watching, and one that is torn down
 * on every recomposition never gets scanned. It is opened when the screen appears, closed when the
 * screen goes, and closed again by the five-minute timer whichever comes first.
 */
@HiltViewModel
class TvPairingViewModel @Inject constructor(
    private val server: TvPairingServer,
    private val auth: AuthRepository,
    private val clock: Clock,
    @ApplicationContext context: Context,
) : ViewModel() {

    private val deviceName = televisionName(context)
    private val state = MutableStateFlow(TvPairingUiState(deviceName = deviceName))
    val uiState: StateFlow<TvPairingUiState> = state.asStateFlow()

    private var expiry: Job? = null

    /** Opens a port and puts a fresh code on screen. Calling it again replaces both. */
    fun start() {
        expiry?.cancel()
        state.value = TvPairingUiState(deviceName = deviceName, status = TvPairingStatus.STARTING)
        val session = PairingSession.create(clock)
        server.start(session, ::exchange).fold(
            onSuccess = { endpoint -> offer(session, endpoint) },
            onFailure = { error ->
                state.value = TvPairingUiState(
                    deviceName = deviceName,
                    status = TvPairingStatus.UNAVAILABLE,
                    errorMessage = error.toUserMessage(),
                )
            },
        )
    }

    fun stop() {
        expiry?.cancel()
        expiry = null
        server.stop()
    }

    override fun onCleared() {
        stop()
    }

    private fun offer(session: PairingSession, endpoint: TvPairingServer.Endpoint) {
        state.value = TvPairingUiState(
            deviceName = deviceName,
            pairingUri = PairingRequest(endpoint.host, endpoint.port, session.nonce, deviceName).toUri(),
            expiresAt = session.expiresAt,
            status = TvPairingStatus.WAITING,
        )
        expiry = viewModelScope.launch {
            delay(session.remaining(clock.instant()).toMillis())
            // The port closes with the code: an offer that is no longer on screen should not still
            // be answerable by something that photographed it.
            server.stop()
            state.update { it.copy(pairingUri = null, expiresAt = null, status = TvPairingStatus.EXPIRED) }
        }
    }

    /**
     * Runs on the server's own worker while the phone holds the connection open, so the screen
     * says what is happening before the token request starts rather than after it comes back.
     */
    private suspend fun exchange(code: String, redirectUri: String): Result<Unit> {
        state.update { it.copy(status = TvPairingStatus.CONFIRMING, errorMessage = null) }
        return auth.exchangePairedCode(code, redirectUri).onFailure { error ->
            state.update { it.copy(status = TvPairingStatus.WAITING, errorMessage = error.toUserMessage()) }
        }
    }
}

/**
 * What to call this television on the phone that is about to sign it in.
 *
 * The name somebody gave the device comes first, because that is the one a person recognises;
 * a model number is the fallback, and is at least something to compare against the label on the
 * box the television came in.
 */
internal fun televisionName(context: Context): String {
    val chosen = runCatching {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
    }.getOrNull()
    return chosen?.trim()?.takeIf { it.isNotEmpty() }
        ?: Build.MODEL?.trim()?.takeIf { it.isNotEmpty() }
        ?: "Android TV"
}
