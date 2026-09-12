package app.kaeru.ui.common.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.tv.material3.LocalContentColor as TvLocalContentColor
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.darkColorScheme as tvDarkColorScheme

private val MobileColors = darkColorScheme(
    primary = KaeruAccent, onPrimary = Color.Black,
    background = KaeruBackground, onBackground = KaeruText,
    surface = KaeruSurface, onSurface = KaeruText,
    surfaceVariant = KaeruElevated, onSurfaceVariant = KaeruSecondary,
)

private val TvColors = tvDarkColorScheme(
    primary = KaeruAccent, onPrimary = Color.Black,
    background = KaeruBackground, onBackground = KaeruText,
    surface = KaeruSurface, onSurface = KaeruText,
)

/**
 * Root theme for the phone. The [Surface] paints the app background and, more importantly, sets
 * `LocalContentColor`: without a root Surface every `Text` without an explicit color renders black.
 */
@Composable
fun KaeruTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MobileColors,
        typography = KaeruTypography,
        shapes = MaterialTheme.shapes.copy(medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(12.dp)),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            content = content,
        )
    }
}

@Composable
fun KaeruTvTheme(content: @Composable () -> Unit) {
    KaeruTheme {
        TvMaterialTheme(colorScheme = TvColors, typography = KaeruTvTypography) {
            CompositionLocalProvider(TvLocalContentColor provides TvColors.onBackground, content = content)
        }
    }
}
