package app.kaeru.ui.common.design

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText

/**
 * The other card shape, and the first thing on the home screen: what to watch next, big enough to
 * be the screen rather than a banner across the top of it.
 *
 * The backdrop crossfades when it changes — the one piece of motion in the app that nobody asked
 * for — because the text over it does not move, so a cut would read as a glitch while a fade reads
 * as the same card showing a different title.
 *
 * [statusLine] is a sentence, not a joined meta string; build it with `episodeLine`.
 */
@Composable
fun HeroBanner(
    title: String,
    statusLine: String,
    backdropUrl: String?,
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxWidth().aspectRatio(KaeruTokens.HeroAspect)) {
        Crossfade(
            targetState = backdropUrl,
            animationSpec = tween(KaeruTokens.DurationHero),
            label = "heroBackdrop",
        ) { url ->
            Backdrop(url, Modifier.fillMaxSize())
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = KaeruTokens.GutterPhone)
                .padding(bottom = KaeruTokens.Space6),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.displaySmall,
                // Anime names in Russian run long — «Фрирен, провожающая в последний путь» is not
                // unusual — and a hero headline that ellipsises is a headline that failed. The
                // display size is a ceiling rather than a fixed size: short names get all 34sp,
                // long ones step down until they fit two lines.
                autoSize = TextAutoSize.StepBased(minFontSize = 22.sp, maxFontSize = 34.sp, stepSize = 1.sp),
                color = KaeruText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                statusLine,
                style = MaterialTheme.typography.bodyMedium,
                color = KaeruSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = KaeruTokens.Space2),
            )
            // «Продолжить с 14:20» beside «Подробнее» is about 358dp of buttons against the
            // 328dp inside the gutters of a 360dp phone, and the plan's own sketch puts exactly
            // those two side by side. Squeezing the primary would truncate the one label that
            // says what the press does, so the pair wraps to a second line instead.
            FlowRow(
                Modifier.padding(top = KaeruTokens.Space4),
                horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
                verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
            ) {
                PrimaryButton(primaryLabel, onPrimary, icon = Icons.Default.PlayArrow)
                if (secondaryLabel != null && onSecondary != null) {
                    SecondaryButton(secondaryLabel, onSecondary)
                }
            }
        }
    }
}
