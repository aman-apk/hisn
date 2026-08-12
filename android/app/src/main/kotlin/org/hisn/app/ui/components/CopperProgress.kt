package org.hisn.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.hisn.app.ui.theme.HisnTheme

/**
 * Progress bar in the brand copper ramp.
 *
 * Hand-drawn rather than Material's indicator so the fill can be the weak→strong gradient
 * the rest of the app uses, and so the sweep runs start→end under RTL.
 *
 * @param progress 0..1, or null for the indeterminate sweep used while a peer is contacted.
 */
@Composable
fun CopperProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    val palette = HisnTheme.palette
    val transition = rememberInfiniteTransition(label = "copperProgress")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )
    val determinate by animateFloatAsState(
        targetValue = progress?.coerceIn(0f, 1f) ?: 0f,
        animationSpec = tween(durationMillis = 240),
        label = "determinate",
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
    ) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        val mirrored = layoutDirection == LayoutDirection.Rtl
        drawRoundRect(color = palette.outline, size = size, cornerRadius = radius)

        val (offsetFraction, widthFraction) = if (progress != null) {
            0f to determinate
        } else {
            // A band that slides in from the start edge and out past the end edge.
            val bandWidth = 0.35f
            (sweep * (1f + bandWidth) - bandWidth) to bandWidth
        }
        val width = size.width * widthFraction.coerceIn(0f, 1f)
        if (width <= 0f) return@Canvas

        val rawStart = size.width * offsetFraction
        val clippedStart = rawStart.coerceIn(0f, size.width)
        val clippedEnd = (rawStart + width).coerceIn(0f, size.width)
        val visibleWidth = clippedEnd - clippedStart
        if (visibleWidth <= 0f) return@Canvas

        val x = if (mirrored) size.width - clippedStart - visibleWidth else clippedStart
        drawRoundRect(
            brush = palette.copperRamp,
            topLeft = Offset(x, 0f),
            size = Size(visibleWidth, size.height),
            cornerRadius = radius,
        )
    }
}
