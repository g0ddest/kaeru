package app.kaeru.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.kaeru.ui.common.design.ProgressStrip as DesignProgressStrip

/** Kept for callers outside `ui.common.design`; the strip itself lives with the design system. */
@Composable
fun ProgressStrip(progress: Float, modifier: Modifier = Modifier) = DesignProgressStrip(progress, modifier)
