package org.hisn.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import org.hisn.app.R

/**
 * Typography tuned for Arabic first.
 *
 * Arabic script sits taller than Latin (ascenders, descenders and diacritics all extend
 * further), so every style gets a noticeably larger line height than the Material default
 * and line-height trimming is disabled — otherwise the system font clips marks such as
 * shadda and the tails of ي / ج.
 */

private val ArabicLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

/**
 * Almarai — the app's typeface, bundled rather than requested from the system so the app looks
 * the same on every device and works with no network.
 *
 * The family ships four weights only (300/400/700/800). The scale below therefore asks for
 * weights that exist: asking for 500 or 600 would make Android synthesise a fake bold, which
 * smears Arabic joins and diacritics.
 */
val Almarai = FontFamily(
    Font(R.font.almarai_light, FontWeight.Light),
    Font(R.font.almarai_regular, FontWeight.Normal),
    Font(R.font.almarai_bold, FontWeight.Bold),
    Font(R.font.almarai_extrabold, FontWeight.ExtraBold),
)

private fun style(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: Double = 0.0,
) = TextStyle(
    fontFamily = Almarai,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    // Arabic has no case and very few standalone letterforms; tracking hurts legibility.
    letterSpacing = letterSpacing.sp,
    lineHeightStyle = ArabicLineHeight,
)

val HisnTypography = Typography(
    displayLarge = style(52, 70, FontWeight.ExtraBold),
    displayMedium = style(42, 58, FontWeight.ExtraBold),
    displaySmall = style(34, 48, FontWeight.Bold),
    headlineLarge = style(30, 44, FontWeight.Bold),
    headlineMedium = style(26, 40, FontWeight.Bold),
    headlineSmall = style(22, 34, FontWeight.Bold),
    titleLarge = style(21, 32, FontWeight.Bold),
    titleMedium = style(17, 28, FontWeight.Bold),
    titleSmall = style(15, 24, FontWeight.Bold),
    bodyLarge = style(17, 28),
    bodyMedium = style(15, 24),
    bodySmall = style(13, 20),
    labelLarge = style(15, 24, FontWeight.Bold),
    labelMedium = style(13, 21, FontWeight.Normal),
    labelSmall = style(11, 19, FontWeight.Normal),
)

/**
 * Monospace style for secrets, URLs and one-time codes.
 *
 * This is the one place that deliberately does not use Almarai. Almarai is proportional, and in a
 * proportional face `l` `I` `1` and `O` `0` are hard to tell apart — which matters when someone is
 * reading a generated password off the screen to type somewhere else. Every other pixel of the app
 * is Almarai; secrets stay in a fixed-width face so each character is unambiguous.
 *
 * The text direction is forced left-to-right even though the app lays out right-to-left:
 * a password such as `aB3!x` reordered by the bidi algorithm would be *displayed* in a
 * different order than it is stored, which is unusable for something the user must read
 * character by character.
 */
val SecretTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 17.sp,
    lineHeight = 26.sp,
    letterSpacing = 1.0.sp,
    lineHeightStyle = ArabicLineHeight,
    textDirection = TextDirection.Ltr,
)

/** Large monospace style for the TOTP code on the detail screen. */
val CodeTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 30.sp,
    lineHeight = 38.sp,
    letterSpacing = 4.sp,
    lineHeightStyle = ArabicLineHeight,
    textDirection = TextDirection.Ltr,
)
