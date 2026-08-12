package org.hisn.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.hisn.app.ui.theme.CopperDeep
import org.hisn.app.ui.theme.HisnTheme

/**
 * The Hisn mark: a copper shield whose top edge is castle crenellations, with a keyhole cut
 * out of the body. Drawn from the same geometry as the desktop icon (a 256×256 artboard) so
 * the two stay identical without shipping a drawable.
 */
private const val ART = 256f

/** Circle of the keyhole: chord (115,138)→(141,138) with r=26 taking the long way over the top. */
private val KEYHOLE_CENTER = Offset(128f, 115.483f)
private const val KEYHOLE_RADIUS = 26f

@Composable
fun ShieldLogo(
    modifier: Modifier = Modifier,
    withPlate: Boolean = true,
    contentDescription: String? = null,
) {
    val palette = HisnTheme.palette
    val shieldPath = remember { buildShieldPath() }
    val merlonPath = remember { buildMerlonPath() }
    val shieldBrush = Brush.verticalGradient(
        colors = listOf(palette.shield, CopperDeep),
        startY = 48f,
        endY = 216f,
    )

    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            )
    ) {
        val scale = size.minDimension / ART
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            if (withPlate) {
                drawRoundRect(
                    color = palette.plate,
                    size = Size(ART, ART),
                    cornerRadius = CornerRadius(56f, 56f),
                )
            }
            drawPath(shieldPath, brush = shieldBrush)
            // Lit rim: the same edge the desktop icon gets from its bevel.
            drawPath(
                shieldPath,
                color = palette.shieldMerlon.copy(alpha = 0.35f),
                style = Stroke(width = 3f),
            )
            drawPath(merlonPath, color = palette.shieldMerlon)
        }
    }
}

/** Body plus keyhole in one even-odd path, so the keyhole is a true cut-out. */
private fun buildShieldPath(): Path = Path().apply {
    fillType = PathFillType.EvenOdd

    moveTo(56f, 48f)
    lineTo(88f, 48f)
    lineTo(88f, 76f)
    lineTo(112f, 76f)
    lineTo(112f, 48f)
    lineTo(144f, 48f)
    lineTo(144f, 76f)
    lineTo(168f, 76f)
    lineTo(168f, 48f)
    lineTo(200f, 48f)
    lineTo(200f, 140f)
    // Two quadratics from the source artwork, written as the equivalent cubics.
    cubicTo(200f, 172f, 176f, 197.333f, 128f, 216f)
    cubicTo(80f, 197.333f, 56f, 172f, 56f, 140f)
    close()

    moveTo(115f, 138f)
    arcTo(
        rect = Rect(center = KEYHOLE_CENTER, radius = KEYHOLE_RADIUS),
        startAngleDegrees = 120f,
        sweepAngleDegrees = 300f,
        forceMoveTo = false,
    )
    lineTo(147f, 182f)
    lineTo(109f, 182f)
    close()
}

/** The centre merlon, picked out in pale brass. */
private fun buildMerlonPath(): Path = Path().apply {
    moveTo(112f, 48f)
    lineTo(144f, 48f)
    lineTo(144f, 76f)
    lineTo(112f, 76f)
    close()
}

/**
 * A fingerprint, drawn as concentric arcs rather than shipped as a vector: the real
 * fingerprint glyph only exists in `material-icons-extended`, which the app does not use.
 */
@Composable
fun FingerprintGlyph(
    modifier: Modifier = Modifier,
    glyphSize: Dp = 20.dp,
    color: Color = HisnTheme.palette.brass,
) {
    Canvas(modifier = modifier.size(glyphSize)) {
        val stroke = size.minDimension / 11f
        drawRidge(color, 0.92f, 200f, 140f, stroke)
        drawRidge(color, 0.70f, 190f, 160f, stroke)
        drawRidge(color, 0.48f, 185f, 170f, stroke)
        drawRidge(color, 0.26f, 195f, 210f, stroke)
    }
}

private fun DrawScope.drawRidge(
    color: Color,
    fraction: Float,
    startAngle: Float,
    sweep: Float,
    stroke: Float,
) {
    val side = size.minDimension * fraction
    val inset = (size.minDimension - side) / 2f
    drawArc(
        color = color,
        startAngle = startAngle,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = Size(side, side),
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
}
