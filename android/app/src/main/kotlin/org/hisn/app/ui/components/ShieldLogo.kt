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
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.hisn.app.ui.theme.CopperDeep
import org.hisn.app.ui.theme.HisnTheme

/**
 * علامة حصن — «برج المفتاح» (تعديل المالك 2026-08-14): برجٌ مشرّف على قاعدة،
 * وفتحته مدخلُ مفتاحٍ مُصغّر. الهندسة نفسها المعتمدة في أيقونة المشغّل (108)
 * مرفوعةً إلى لوحة 256 — فتبقى العلامتان توأمين بلا drawable إضافي.
 */
private const val ART = 256f

/** دائرة مدخل المفتاح: المركز والقطر من هندسة المشغّل ×(256/108). */
private val KEYHOLE_CENTER = Offset(128f, 118.5f)
private const val KEYHOLE_RADIUS = 11.85f

// ألوان الهوية المعتمدة — مقفلة، لا تتبع الثيم: هي عين ألوان أيقونة المشغّل.
private val SkyLight = Color(0xFFE2A275)
private val SkyMid = Color(0xFFC4764E)
private val SkyDeep = Color(0xFF8F4E2E)
private val IvoryMass = Color(0xFFFDF3D8)
private val LitMerlon = Color(0xFFE5A98A)
private val DawnWash = Color(0xFFFFF6E0)

@Composable
fun ShieldLogo(
    modifier: Modifier = Modifier,
    withPlate: Boolean = true,
    contentDescription: String? = null,
) {
    val shieldPath = remember { buildShieldPath() }
    val merlonPath = remember { buildMerlonPath() }
    val threadPath = remember { buildThreadPath() }

    val description = contentDescription
    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .then(
                if (description != null) {
                    Modifier.semantics { this.contentDescription = description }
                } else {
                    Modifier
                }
            )
    ) {
        val scale = size.minDimension / ART
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            if (withPlate) {
                // توحيد الهوية (2026-08-14 بأمر المالك): الشعار الداخلي هو أيقونة المشغّل
                // ذاتها — سماء النحاس بغسلة الفجر المنشورة ونجمتاها، لا بلاطة ليل ببرج نحاسي.
                val tile = androidx.compose.ui.graphics.Path().apply {
                    addRoundRect(
                        androidx.compose.ui.geometry.RoundRect(
                            0f, 0f, ART, ART, CornerRadius(56f, 56f),
                        )
                    )
                }
                clipPath(tile) {
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(SkyLight, SkyMid, SkyDeep),
                            start = Offset(47.4f, 0f),
                            end = Offset(208.6f, 256f),
                        ),
                        size = Size(ART, ART),
                    )
                    // غسلة الفجر المنشورة — نق 115×(256/108)=272، ذروة .30 وتلاشٍ رباعي.
                    drawRect(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to DawnWash.copy(alpha = 0.30f),
                                0.45f to DawnWash.copy(alpha = 0.14f),
                                0.85f to DawnWash.copy(alpha = 0.05f),
                                1f to DawnWash.copy(alpha = 0f),
                            ),
                            center = Offset(71f, 47.4f),
                            radius = 272f,
                        ),
                        size = Size(ART, ART),
                    )
                    drawCircle(Color.White.copy(alpha = 0.48f), radius = 2.6f, center = Offset(50f, 37.9f))
                    drawCircle(Color.White.copy(alpha = 0.41f), radius = 2.1f, center = Offset(208.3f, 61.6f))
                }
            }
            drawPath(shieldPath, color = IvoryMass)
            drawPath(merlonPath, color = LitMerlon)
            // خيط الضوء على الشرفات — من مذهب صحوة الضوء المعتمد.
            drawPath(
                threadPath,
                color = Color(0xFFFFFDF2).copy(alpha = 0.75f),
                style = Stroke(width = 2.85f),
            )
        }
    }
}

/** خيط الضوء: الحواف العليا للشرفات الثلاث (إحداثيات المشغّل ×2.370). */
private fun buildThreadPath(): Path = Path().apply {
    moveTo(97.2f, 64f); lineTo(113.8f, 64f)
    moveTo(119.7f, 64f); lineTo(136.3f, 64f)
    moveTo(142.2f, 64f); lineTo(158.8f, 64f)
}

/** جسد البرج وقاعدته ومدخل المفتاح في مسار even-odd واحد — فالمدخل قصٌّ حقيقي لا رسمٌ فوقه. */
private fun buildShieldPath(): Path = Path().apply {
    fillType = PathFillType.EvenOdd

    // الشرفات الثلاث ثم الجدران — إحداثيات المشغّل (108) مضروبة في 2.370.
    moveTo(97.2f, 61.6f)
    lineTo(113.8f, 61.6f)
    lineTo(113.8f, 77f)
    lineTo(119.7f, 77f)
    lineTo(119.7f, 61.6f)
    lineTo(136.3f, 61.6f)
    lineTo(136.3f, 77f)
    lineTo(142.2f, 77f)
    lineTo(142.2f, 61.6f)
    lineTo(158.8f, 61.6f)
    lineTo(158.8f, 180.1f)
    lineTo(97.2f, 180.1f)
    close()

    // القاعدة.
    moveTo(87.7f, 180.1f)
    lineTo(168.3f, 180.1f)
    lineTo(168.3f, 189.6f)
    lineTo(87.7f, 189.6f)
    close()

    // مدخل المفتاح.
    moveTo(123.2f, 128.7f)
    arcTo(
        rect = Rect(center = KEYHOLE_CENTER, radius = KEYHOLE_RADIUS),
        startAngleDegrees = 115f,
        sweepAngleDegrees = 310f,
        forceMoveTo = false,
    )
    lineTo(136.3f, 149.3f)
    lineTo(119.7f, 149.3f)
    close()
}

/** الشرفة الوسطى وحدها مضاءة بالنحاس الشاحب — العلامة التي تُقرأ صغيرة. */
private fun buildMerlonPath(): Path = Path().apply {
    moveTo(119.7f, 61.6f)
    lineTo(136.3f, 61.6f)
    lineTo(136.3f, 77f)
    lineTo(119.7f, 77f)
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
