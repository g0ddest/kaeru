package app.kaeru.ui.mobile.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.kaeru.ui.common.design.DestructiveButton
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.RowHeader
import app.kaeru.ui.common.design.SecondaryButton
import app.kaeru.ui.common.design.formatBytes
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruSurface
import app.kaeru.ui.common.theme.KaeruTheme

private const val TITLE = "Удалить загрузку?"
private const val REMOVE = "Удалить"
private const val CANCEL = "Отмена"

/**
 * Asked before an episode leaves the device.
 *
 * A download is minutes of somebody's connection, and on a train it is the difference between
 * having something to watch and not, so it is the one control on the player that asks. The size is
 * in the sentence because it is the reason a viewer would say yes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoveDownloadSheet(bytes: Long, onRemove: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = KaeruSurface,
    ) {
        RemoveDownloadContent(bytes, onRemove, onDismiss)
    }
}

/** Apart from the sheet so a preview can draw it; `ModalBottomSheet` previews as an empty screen. */
@Composable
private fun RemoveDownloadContent(bytes: Long, onRemove: () -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.padding(bottom = KaeruTokens.Space6)) {
        RowHeader(TITLE)
        if (bytes > 0) {
            Text(
                "Освободится ${formatBytes(bytes)}",
                style = MaterialTheme.typography.bodyMedium,
                color = KaeruSecondary,
                modifier = Modifier.padding(horizontal = KaeruTokens.GutterPhone, vertical = KaeruTokens.Space2),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(KaeruTokens.GutterPhone),
            horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
        ) {
            DestructiveButton(REMOVE, onRemove, Modifier.weight(1f))
            SecondaryButton(CANCEL, onDismiss, Modifier.weight(1f))
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF15171E, widthDp = 360, heightDp = 220)
@Composable
private fun RemoveDownloadPreview() = KaeruTheme {
    Column(Modifier.background(KaeruSurface)) {
        RemoveDownloadContent(320L * 1024 * 1024, {}, {})
    }
}
