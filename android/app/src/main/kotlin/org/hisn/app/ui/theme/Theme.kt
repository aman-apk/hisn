package org.hisn.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import org.hisn.app.data.ThemeMode

/**
 * Brand tokens that Material's [ColorScheme] has no slot for.
 *
 * Notably [strong] and [weak]: Hisn expresses "good" and "bad" with brass and rust rather
 * than the usual green and red, so password strength, sync success and validation errors
 * all pull their colour from here instead of from `colorScheme.error` semantics.
 */
@Immutable
data class HisnPalette(
    /** Positive / high-quality tone (bright brass). */
    val strong: Color,
    /** Negative / low-quality tone (deep rust). */
    val weak: Color,
    /** Midpoint of the strong→weak ramp. */
    val middling: Color,
    /** Pale brass highlight, for accents on copper fills. */
    val brass: Color,
    /** Secondary text: readable but recessive. */
    val muted: Color,
    /** Hairline dividers and field outlines. */
    val outline: Color,
    /** Fill behind avatars, chips and other tinted blocks. */
    val tintedFill: Color,
    /** Surface for cards that must lift off the background. */
    val raised: Color,
    /** Backdrop of the app-icon plate on the unlock screen. */
    val plate: Color,
    /** Shield body in the app icon. */
    val shield: Color,
    /** Centre merlon of the shield. */
    val shieldMerlon: Color,
    val isDark: Boolean,
) {
    /** Left-to-right ramp used by the strength bar and the sync progress bar. */
    val copperRamp: Brush
        get() = Brush.horizontalGradient(listOf(weak, middling, strong))

    /** Fill for the shield, a copper gradation from lit edge to shadow. */
    val shieldFill: Brush
        get() = Brush.verticalGradient(listOf(shield, CopperDeep))

    /**
     * Colour for a password-strength score in 0..4 (bad → excellent):
     * deep rust for the worst, bright brass for the best.
     */
    fun strengthColor(score: Int): Color = when (score.coerceIn(0, 4)) {
        0 -> weak
        1 -> weak
        2 -> middling
        3 -> CopperLight
        else -> strong
    }
}

private val DarkPalette = HisnPalette(
    strong = Brass,
    weak = RustLight,
    middling = Copper,
    brass = Brass,
    muted = Muted,
    outline = CopperOutlineDark,
    tintedFill = CopperSurfaceHigh,
    raised = CopperSurface,
    plate = CopperBg,
    shield = ShieldCopper,
    shieldMerlon = ShieldMerlon,
    isDark = true,
)

private val LightPalette = HisnPalette(
    strong = BrassDeep,
    weak = Rust,
    middling = Copper,
    brass = BrassStrong,
    muted = MutedOnLight,
    outline = WarmOutlineLight,
    tintedFill = CopperWash,
    raised = WarmSurfaceLight,
    plate = CopperBg,
    shield = ShieldCopper,
    shieldMerlon = ShieldMerlon,
    isDark = false,
)

private val DarkColors = darkColorScheme(
    primary = CopperLight,
    onPrimary = CopperBg,
    primaryContainer = CopperDeep,
    onPrimaryContainer = Brass,
    inversePrimary = CopperStrong,
    secondary = CopperPale,
    onSecondary = CopperBg,
    secondaryContainer = CopperSurfaceHigh,
    onSecondaryContainer = WarmOnDark,
    tertiary = Brass,
    onTertiary = CopperBg,
    tertiaryContainer = CopperOutlineDark,
    onTertiaryContainer = Brass,
    background = CopperBg,
    onBackground = WarmOnDark,
    surface = CopperBg,
    onSurface = WarmOnDark,
    surfaceVariant = CopperSurface,
    onSurfaceVariant = Muted,
    surfaceTint = CopperLight,
    inverseSurface = WarmBgLight,
    inverseOnSurface = WarmOnLight,
    error = RustLight,
    onError = CopperInk,
    errorContainer = Rust,
    onErrorContainer = WarmOnDark,
    outline = CopperOutlineDark,
    outlineVariant = CopperSurfaceHigh,
    scrim = CopperInk,
)

private val LightColors = lightColorScheme(
    primary = CopperStrong,
    onPrimary = WarmSurfaceLight,
    primaryContainer = CopperWash,
    onPrimaryContainer = CopperDeep,
    inversePrimary = CopperLight,
    secondary = CopperDeep,
    onSecondary = WarmSurfaceLight,
    secondaryContainer = WarmSurfaceLightSunk,
    onSecondaryContainer = WarmOnLight,
    tertiary = BrassDeep,
    onTertiary = WarmSurfaceLight,
    tertiaryContainer = Brass,
    onTertiaryContainer = WarmOnLight,
    background = WarmBgLight,
    onBackground = WarmOnLight,
    surface = WarmSurfaceLight,
    onSurface = WarmOnLight,
    surfaceVariant = WarmSurfaceLightSunk,
    onSurfaceVariant = MutedOnLight,
    surfaceTint = CopperStrong,
    inverseSurface = CopperSurface,
    inverseOnSurface = WarmOnDark,
    error = Rust,
    onError = WarmSurfaceLight,
    errorContainer = CopperWash,
    onErrorContainer = Rust,
    outline = WarmOutlineLight,
    outlineVariant = WarmSurfaceLightSunk,
    scrim = CopperInk,
)

val LocalHisnPalette = staticCompositionLocalOf { DarkPalette }

object HisnTheme {
    val palette: HisnPalette
        @Composable @ReadOnlyComposable get() = LocalHisnPalette.current
}

/**
 * @param layoutDirection Follows the resolved locale by default — Arabic devices lay out
 *   right-to-left, English ones left-to-right. Hisn is Arabic-first in its copy, not in its
 *   geometry: every composable uses start/end, so forcing RTL under an English locale would
 *   only mirror the UI against the language on screen. Overridable for previews.
 */
@Composable
fun HisnTheme(
    themeMode: ThemeMode = ThemeMode.System,
    layoutDirection: LayoutDirection = LocalLayoutDirection.current,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    CompositionLocalProvider(
        LocalHisnPalette provides if (dark) DarkPalette else LightPalette,
        LocalLayoutDirection provides layoutDirection,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = HisnTypography,
            content = content,
        )
    }
}
