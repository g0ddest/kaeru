package app.kaeru.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.kaeru.ui.common.design.Skeleton as DesignSkeleton

/** Kept for callers outside `ui.common.design`; the block itself lives with the design system. */
@Composable
fun Skeleton(modifier: Modifier = Modifier) = DesignSkeleton(modifier)
