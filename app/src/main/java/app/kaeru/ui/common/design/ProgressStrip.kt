package app.kaeru.ui.common.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.kaeru.ui.common.theme.KaeruAccent

/**
 * How far into an episode the viewer got, as a 4dp strip.
 *
 * It is the second and last place amber appears on a screen full of artwork, and it is the same
 * amber as the watch button on purpose: the strip and the button are the same fact.
 */
@Composable
fun ProgressStrip(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(KaeruTokens.ProgressHeight)
            .background(Color.White.copy(alpha = 0.18f)),
    ) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(KaeruAccent))
    }
}
