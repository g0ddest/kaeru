package app.kaeru.ui.common.theme

import androidx.compose.ui.graphics.Color

/**
 * The whole palette, fixed by the spec. Nine values and no tenth: the screens are meant to take
 * their colour from posters and screenshots, so every surface here is a step of the same near-black
 * and the amber is spent only on the thing the viewer is meant to press.
 */
val KaeruBackground = Color(0xFF0B0C10)
val KaeruSurface = Color(0xFF15171E)
val KaeruElevated = Color(0xFF1E212B)

/** The only line colour in the app: outlines, dividers, the resting border of a control. */
val KaeruDivider = Color(0xFF2A2E3A)

val KaeruText = Color(0xFFF2F3F5)
val KaeruSecondary = Color(0xFF9AA0AA)
val KaeruAccent = Color(0xFFF5A524)

/** Text on the amber. Deep brown-black rather than pure black, which vibrates against this hue. */
val KaeruOnAccent = Color(0xFF1A1200)

val KaeruError = Color(0xFFE5484D)
