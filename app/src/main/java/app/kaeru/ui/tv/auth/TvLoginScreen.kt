package app.kaeru.ui.tv.auth

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.kaeru.ui.common.auth.AuthUiState
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

private fun qrBitmap(value: String, size: Int = 360): Bitmap {
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { i ->
        if (matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@Composable
fun TvLoginScreen(authorizeUrl: String, state: AuthUiState, code: String, onCode: (String) -> Unit, onSubmit: () -> Unit) {
    val qr = remember(authorizeUrl) { qrBitmap(authorizeUrl).asImageBitmap() }
    Row(
        Modifier.fillMaxSize().padding(72.dp),
        horizontalArrangement = Arrangement.spacedBy(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(qr, contentDescription = "QR-код входа через Shikimori", modifier = Modifier.size(360.dp))
        Column(verticalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.weight(1f)) {
            Text("Вход в Kaeru", style = MaterialTheme.typography.displayMedium)
            Text("1. Отсканируйте QR-код телефоном\n2. Разрешите доступ\n3. Введите показанный Shikimori код")
            OutlinedTextField(
                value = code,
                onValueChange = onCode,
                singleLine = true,
                label = { androidx.compose.material3.Text("Код авторизации") },
            )
            Button(onClick = onSubmit, enabled = code.isNotBlank() && !state.exchanging) {
                Text(if (state.exchanging) "Проверяем…" else "Войти")
            }
            state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
