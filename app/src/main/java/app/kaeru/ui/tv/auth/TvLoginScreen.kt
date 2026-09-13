package app.kaeru.ui.tv.auth

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.kaeru.ui.common.auth.AuthFailure
import app.kaeru.ui.common.auth.AuthUiState
import app.kaeru.ui.common.design.KaeruTextField
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.PrimaryButton
import app.kaeru.ui.common.design.SecondaryButton
import app.kaeru.ui.common.design.formatTime
import app.kaeru.ui.common.theme.KaeruDivider
import app.kaeru.ui.common.theme.KaeruError
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import java.time.Instant

private const val HEADING = "Войдите с телефона"
private const val SCAN_HINT = "Отсканируйте камерой телефона с Kaeru"
private const val FALLBACK_HINT = "Отсканируйте камерой телефона и введите код с экрана Shikimori"
private const val UNNAMED_TV = "Этот телевизор"

private const val STATUS_STARTING = "Готовим код…"
private const val STATUS_WAITING = "Ждём телефон…"
private const val STATUS_CONFIRMING = "Телефон подтвердил, входим…"
private const val STATUS_EXPIRED = "Код устарел"

private const val NEW_QR = "Новый QR"
private const val TYPED_HEADING = "Или введите код"
private const val TYPED_STEPS = "1. Отсканируйте этот код\n2. Разрешите доступ\n3. Введите код с экрана Shikimori"
private const val CODE_PLACEHOLDER = "Код авторизации"
private const val CODE_CLEAR = "Очистить код"
private const val SIGN_IN = "Войти"
private const val CODE_CHECKING = "Входим…"
private const val CODE_REJECTED =
    "Код не подошёл или уже использован. Получите новый код и попробуйте ещё раз"
private const val CODE_OFFLINE = "Нет связи с Shikimori. Повторить"

private val PairingQr = 224.dp
private val TypedQr = 132.dp

/** The white surround a camera needs to find the code's edges from across a room. */
private val QrQuietZone = 12.dp

private val ColumnGap = 56.dp
private val ScreenPadding = 48.dp

/**
 * Signing a television in, without anybody typing anything.
 *
 * Two ways out of the same screen, in the order they are meant to be tried. The left half is the
 * one that works: a code big enough to scan from the sofa, the television's own name under it so
 * the phone's question can be checked against something, and one line saying what the television
 * is waiting for. The right half is the way this used to work and still does when the television
 * has no local network — a Shikimori page, read off the screen, typed back in with a remote.
 *
 * Nothing here asks for focus. On a television, focus landing in a text field brings the system
 * keyboard up over the screen, which would cover the code the screen exists to show.
 */
@Composable
fun TvLoginScreen(
    authorizeUrl: String,
    state: AuthUiState,
    pairing: TvPairingUiState,
    code: String,
    onCode: (String) -> Unit,
    onSubmit: () -> Unit,
    onNewQr: () -> Unit,
) {
    Row(
        Modifier.fillMaxSize().padding(horizontal = KaeruTokens.GutterTv, vertical = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(ColumnGap),
    ) {
        PairingHalf(pairing, authorizeUrl, onNewQr, Modifier.weight(1f))
        TypedCodeHalf(authorizeUrl, state, code, onCode, onSubmit, Modifier.weight(1f))
    }
}

@Composable
private fun PairingHalf(
    pairing: TvPairingUiState,
    authorizeUrl: String,
    onNewQr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space4)) {
        Text(HEADING, style = MaterialTheme.typography.headlineMedium, color = KaeruText)
        QrCard(tvLoginQr(pairing, authorizeUrl), PairingQr)
        Text(tvLoginHint(pairing), style = MaterialTheme.typography.bodyMedium, color = KaeruSecondary)
        Text(tvLoginName(pairing), style = MaterialTheme.typography.titleMedium, color = KaeruText)
        StatusLine(pairing)
        if (tvLoginOffersNewQr(pairing)) {
            SecondaryButton(NEW_QR, onNewQr, Modifier.padding(top = KaeruTokens.Space2))
        }
    }
}

/**
 * What the television is waiting for, and how long the code still has.
 *
 * The countdown ticks in its own composable so the second that passes redraws one line of text
 * rather than the screen — and it is set in the quietest role on the scale, because it is a fact
 * to glance at, not an instruction.
 */
@Composable
private fun StatusLine(pairing: TvPairingUiState) {
    val now by produceState(Instant.now(), pairing.expiresAt) {
        while (pairing.expiresAt != null) {
            delay(1_000)
            value = Instant.now()
        }
    }
    Row(
        Modifier.padding(top = KaeruTokens.Space2),
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tvLoginStatus(pairing),
            style = MaterialTheme.typography.labelLarge,
            color = if (pairing.errorMessage != null) KaeruError else KaeruSecondary,
            modifier = Modifier.weight(1f, fill = false),
        )
        tvLoginCountdown(pairing, now)?.let { left ->
            Text(left, style = MaterialTheme.typography.labelMedium, color = KaeruSecondary)
        }
    }
}

@Composable
private fun TypedCodeHalf(
    authorizeUrl: String,
    state: AuthUiState,
    code: String,
    onCode: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space4)) {
        Text(TYPED_HEADING, style = MaterialTheme.typography.headlineSmall, color = KaeruText)
        Text(TYPED_STEPS, style = MaterialTheme.typography.bodyMedium, color = KaeruSecondary)
        QrCard(authorizeUrl, TypedQr)
        KaeruTextField(
            value = code,
            onValueChange = onCode,
            modifier = Modifier.width(TypedFieldWidth),
            enabled = tvCodeFieldEnabled(state),
            placeholder = CODE_PLACEHOLDER,
            clearLabel = CODE_CLEAR,
            onSubmit = onSubmit,
        )
        PrimaryButton(SIGN_IN, onSubmit, enabled = tvCodeSubmitEnabled(state, code))
        // One line for this half, in the place the old error text was: what is happening, or what
        // went wrong and which of the two things to do about it.
        tvCodeStatus(state)?.let { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.exchanging) KaeruSecondary else KaeruError,
                modifier = Modifier.width(TypedFieldWidth),
            )
        }
    }
}

/** Wide enough for a Shikimori code and nothing like the full column, which would read as a form. */
private val TypedFieldWidth = 360.dp

/**
 * A code on white, because a camera reads dark modules on a light ground and nothing else
 * reliably. It is the one place in the app where a colour outside the palette is on screen, and it
 * is there for the scanner rather than for the eye.
 */
@Composable
private fun QrCard(payload: String, size: Dp) {
    val bitmap = remember(payload) { qrBitmap(payload).asImageBitmap() }
    Box(
        Modifier
            .clip(KaeruTokens.CardShape)
            .background(Color.White)
            .padding(QrQuietZone),
    ) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(size))
    }
}

private const val QR_PIXELS = 480

private fun qrBitmap(value: String, size: Int = QR_PIXELS): Bitmap {
    // A television is scanned at an angle, in a lit room, sometimes through a glossy panel. The
    // middle correction level survives that; the default leaves no margin for any of it.
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
    )
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size) { i ->
        if (matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/**
 * What goes in the large code.
 *
 * A television with no address on a local network cannot be handed anything, so rather than show a
 * code that leads nowhere the big square falls back to the Shikimori page — the same one the right
 * half offers, which at that point is the only way in.
 */
internal fun tvLoginQr(pairing: TvPairingUiState, authorizeUrl: String): String =
    pairing.pairingUri ?: authorizeUrl

internal fun tvLoginHint(pairing: TvPairingUiState): String =
    if (pairing.pairingUri == null) FALLBACK_HINT else SCAN_HINT

internal fun tvLoginStatus(pairing: TvPairingUiState): String = pairing.errorMessage ?: when (pairing.status) {
    TvPairingStatus.STARTING -> STATUS_STARTING
    TvPairingStatus.WAITING -> STATUS_WAITING
    TvPairingStatus.CONFIRMING -> STATUS_CONFIRMING
    TvPairingStatus.EXPIRED -> STATUS_EXPIRED
    // Its own line is the message from the failure, so this is only reached if there was none.
    TvPairingStatus.UNAVAILABLE -> FALLBACK_HINT
}

/** «Ещё 4:37», or nothing at all once there is no offer left to count down. */
internal fun tvLoginCountdown(pairing: TvPairingUiState, now: Instant): String? {
    val expiresAt = pairing.expiresAt ?: return null
    val left = expiresAt.toEpochMilli() - now.toEpochMilli()
    return if (left <= 0) null else "Ещё ${formatTime(left)}"
}

internal fun tvLoginOffersNewQr(pairing: TvPairingUiState): Boolean =
    pairing.status == TvPairingStatus.EXPIRED || pairing.status == TvPairingStatus.UNAVAILABLE

/**
 * The one line under the typed-code field.
 *
 * A refused code and a refused connection are the same red text to look at and two different
 * things to do, so they are never collapsed into one message. Nothing is said before the first
 * attempt: an untouched field with a warning under it reads as an error the viewer already made.
 */
internal fun tvCodeStatus(state: AuthUiState): String? = when {
    state.exchanging -> CODE_CHECKING
    state.failure == AuthFailure.NO_CONNECTION -> CODE_OFFLINE
    state.failure == AuthFailure.CODE_REJECTED -> CODE_REJECTED
    else -> null
}

/** Nothing is typed into a field whose contents are already on their way to Shikimori. */
internal fun tvCodeFieldEnabled(state: AuthUiState): Boolean = !state.exchanging

internal fun tvCodeSubmitEnabled(state: AuthUiState, code: String): Boolean =
    code.isNotBlank() && !state.exchanging

internal fun tvLoginName(pairing: TvPairingUiState): String =
    pairing.deviceName.trim().ifEmpty { UNNAMED_TV }
