package app.kaeru.ui.common.theme

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

@Composable
fun KaeruTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MobileColors,
        typography = KaeruTypography,
        shapes = MaterialTheme.shapes.copy(medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(12.dp)),
        content = content,
    )
}

@Composable
fun KaeruTvTheme(content: @Composable () -> Unit) {
    KaeruTheme { TvMaterialTheme(colorScheme = TvColors, content = content) }
}
