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
 * Typography tuned for Arabic first, on the Aman family's shared terms.
 *
 * Arabic script sits taller than Latin (ascenders, descenders and diacritics all extend
 * further), and Almarai's own leading is a tight 1.116em — left alone it clips marks such
 * as shadda and the tails of ي / ج. So every slot carries an explicit line height of about
 * 1.5x its size, and line-height trimming is disabled.
 */

private val ArabicLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

/**
 * Almarai — the family's typeface, bundled rather than requested from the system so the app
 * looks the same on every device and works with no network.
 *
 * The family ships two weights only: regular 400 and bold 700. SemiBold is bound to the bold
 * file explicitly — otherwise Android's weight matcher would synthesise a fake 600, which
 * smears Arabic joins and diacritics.
 */
val Almarai = FontFamily(
    Font(R.font.almarai_regular, FontWeight.Normal),
    Font(R.font.almarai_bold, FontWeight.SemiBold),
    Font(R.font.almarai_bold, FontWeight.Bold),
)

private fun style(
    size: Int,
    lineHeight: Double,
    weight: FontWeight = FontWeight.Normal,
) = TextStyle(
    fontFamily = Almarai,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    // Arabic has no case and very few standalone letterforms; tracking hurts legibility.
    letterSpacing = 0.sp,
    lineHeightStyle = ArabicLineHeight,
)

/**
 * The scale is Material 3's defaults minus 1sp across the board (family decision
 * 2026-08-13: Almarai reads visually larger than other faces at the same sp), with the
 * explicit ~1.5x line heights described above. Only weights that exist are asked for:
 * Normal and Bold.
 */
val HisnTypography = Typography(
    displayLarge = style(56, 84.0, FontWeight.Bold),
    displayMedium = style(44, 66.0, FontWeight.Bold),
    displaySmall = style(35, 52.5, FontWeight.Bold),
    headlineLarge = style(31, 46.5, FontWeight.Bold),
    headlineMedium = style(27, 40.5, FontWeight.Bold),
    headlineSmall = style(23, 34.5, FontWeight.Bold),
    titleLarge = style(21, 31.5, FontWeight.Bold),
    titleMedium = style(15, 22.5, FontWeight.Bold),
    titleSmall = style(13, 19.5, FontWeight.Bold),
    bodyLarge = style(15, 22.5),
    bodyMedium = style(13, 19.5),
    bodySmall = style(11, 16.5),
    labelLarge = style(13, 19.5, FontWeight.Bold),
    labelMedium = style(11, 16.5, FontWeight.Normal),
    labelSmall = style(10, 15.0, FontWeight.Normal),
)

/**
 * Monospace style for secrets, URLs and one-time codes.
 *
 * This is the one place that deliberately does not use Almarai. Almarai is proportional, and in a
 * proportional face `l` `I` `1` and `O` `0` are hard to tell apart — which matters when someone is
 * reading a generated password off the screen to type somewhere else. Every other pixel of the app
 * is Almarai; secrets stay in a fixed-width face so each character is unambiguous.
 *
 * The text direction is forced left-to-right even though Arabic screens lay out right-to-left:
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
