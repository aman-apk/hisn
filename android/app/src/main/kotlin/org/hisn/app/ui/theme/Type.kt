package org.hisn.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp

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

private fun style(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: Double = 0.0,
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    // Arabic has no case and very few standalone letterforms; tracking hurts legibility.
    letterSpacing = letterSpacing.sp,
    lineHeightStyle = ArabicLineHeight,
)

val HisnTypography = Typography(
    displayLarge = style(52, 68, FontWeight.Bold),
    displayMedium = style(42, 56, FontWeight.Bold),
    displaySmall = style(34, 46, FontWeight.SemiBold),
    headlineLarge = style(30, 42, FontWeight.SemiBold),
    headlineMedium = style(26, 38, FontWeight.SemiBold),
    headlineSmall = style(22, 32, FontWeight.SemiBold),
    titleLarge = style(21, 30, FontWeight.SemiBold),
    titleMedium = style(17, 26, FontWeight.Medium),
    titleSmall = style(15, 22, FontWeight.Medium),
    bodyLarge = style(17, 28),
    bodyMedium = style(15, 24),
    bodySmall = style(13, 20),
    labelLarge = style(15, 22, FontWeight.Medium),
    labelMedium = style(13, 20, FontWeight.Medium),
    labelSmall = style(11, 18, FontWeight.Medium),
)

/**
 * Monospace style for secrets, URLs and one-time codes.
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
