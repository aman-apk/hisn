package org.hisn.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The whole application palette. Copper gradations plus warm neutrals only — there is no
 * green, blue or violet anywhere in Hisn, including for success/error semantics: "good"
 * is expressed with bright brass and "bad" with deep rust.
 *
 * The few shades that are not in the brand sheet ([CopperLight], [BrassDeep], [RustLight])
 * exist because the sheet colours do not reach a 4.5:1 contrast ratio on every surface;
 * each is a gradation of its brand parent, picked to clear that ratio on the surface it is
 * used on (noted per colour).
 */

// -- Copper ramp, darkest to lightest ---------------------------------------------------

/** Deepest shade; used for scrims and pressed states over the dark background. */
val CopperInk = Color(0xFF160F0B)

/** Brand: bgDark. */
val CopperBg = Color(0xFF211711)

/** Brand: surfaceDark. */
val CopperSurface = Color(0xFF2A1D14)

/** One step above the dark surface, for cards that must separate from the background. */
val CopperSurfaceHigh = Color(0xFF362519)

/** Outline / divider tone on dark surfaces. */
val CopperOutlineDark = Color(0xFF4A3423)

/** Brand: primaryDeep (burnt copper). */
val CopperDeep = Color(0xFF8F451D)

/** Brand: primaryStrong. Contrast 5.6:1 against white — the light theme's primary. */
val CopperStrong = Color(0xFFA64E1F)

/** Brand: primary. */
val Copper = Color(0xFFC2622B)

/** Lightened copper. Contrast 6.1:1 on bgDark — the dark theme's primary. */
val CopperLight = Color(0xFFD9834D)

/** Pale copper for large decorative fills and disabled accents. */
val CopperPale = Color(0xFFE9B48C)

/** Very pale copper wash, for tinted containers on light backgrounds. */
val CopperWash = Color(0xFFF6E3D4)

// -- Brass (the "strong / positive" family) ---------------------------------------------

/** Brand: brass, a pale highlight. */
val Brass = Color(0xFFF3DCA8)

/** Brand: brassStrong. Used as a fill (bars, rings); too light for text on white. */
val BrassStrong = Color(0xFFC99C3B)

/** Darkened brass, 5.1:1 on the light background — brass-toned text in the light theme. */
val BrassDeep = Color(0xFF8A6A18)

// -- Rust (the "danger / weak" family) --------------------------------------------------

/** Brand: rust. 6.3:1 on the light background. */
val Rust = Color(0xFFA63D22)

/** Lightened rust, 5.1:1 on bgDark — error text and weak-strength fills in the dark theme. */
val RustLight = Color(0xFFD9694A)

// -- Neutrals ---------------------------------------------------------------------------

/** Brand: onDark. */
val WarmOnDark = Color(0xFFF0E5D8)

/** Brand: onLight. */
val WarmOnLight = Color(0xFF2B211A)

/** Brand: bgLight. */
val WarmBgLight = Color(0xFFF8F1E5)

/** Brand: surfaceLight. */
val WarmSurfaceLight = Color(0xFFFFFDF8)

/** One step down from the light surface, for inset fields and list separators. */
val WarmSurfaceLightSunk = Color(0xFFF1E7D8)

/** Brand: muted. Secondary text; 4.6:1 on bgDark, so it is only used on dark surfaces. */
val Muted = Color(0xFFA08D77)

/** Secondary text on light surfaces (the muted tone is too light there). */
val MutedOnLight = Color(0xFF6E5B47)

/** Outline / divider tone on light surfaces. */
val WarmOutlineLight = Color(0xFFDCCBB4)

// -- App icon -------------------------------------------------------------------------

/** Shield body in the app icon. */
val ShieldCopper = Color(0xFFC97F48)

/** Centre merlon of the shield's crenellated top edge. */
val ShieldMerlon = Brass
