package org.hisn.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The whole application palette. Copper gradations plus warm neutrals only — there is no
 * green, blue or violet *accent* anywhere in Hisn, including for success/error semantics:
 * "good" is expressed with bright brass and "bad" with deep rust.
 *
 * Since joining the Aman family the copper no longer sits on its own brown ground: the dark
 * theme is seated on the family's shared night (`#10121D` and its surface steps, the same
 * ground every family member uses), and the light theme on the family's warm paper
 * (`#FBF8F2` — never stark white). The copper itself is the member tint reserved for Hisn
 * in the family register: `#C4764E`, with `#E2A275` (light) and `#8F4E2E` (deep).
 *
 * Every text-on-surface pair below is computed against WCAG 2.x relative luminance and
 * documented at the colour that carries it; body-text pairs all clear 4.5:1.
 */

// -- Copper ramp, darkest to lightest ---------------------------------------------------

/** Deepest shade; scrims and pressed states over the night ground. Also the ink on error
 *  fills: 5.6:1 on [RustLight]. */
val CopperInk = Color(0xFF0D0E16)

/** Family night ground — shared by every Aman member, so the apps feel like one house. */
val CopperBg = Color(0xFF10121D)

/** Family night surface, one step above the ground. */
val CopperSurface = Color(0xFF171827)

/** Family night raised step, for cards that must separate from the background. */
val CopperSurfaceHigh = Color(0xFF1E2033)

/** Outline / divider tone on the night surfaces. */
val CopperOutlineDark = Color(0xFF272A40)

/** Brand: the member tint's deep gradation. Ivory text reads at 5.3:1 on it. */
val CopperDeep = Color(0xFF8F4E2E)

/** Deepened copper for the light theme's primary: 5.4:1 on [WarmBgLight], and
 *  [WarmSurfaceLight] text on a fill of it reads at 5.6:1. */
val CopperStrong = Color(0xFF9C5330)

/** Brand: the member tint itself. 5.4:1 on the night ground. */
val Copper = Color(0xFFC4764E)

/** Brand: the member tint's light gradation — the dark theme's primary.
 *  8.6:1 on the night ground, 8.1:1 on [CopperSurface]. */
val CopperLight = Color(0xFFE2A275)

/** Pale copper for large decorative fills and disabled accents. 11.7:1 on night. */
val CopperPale = Color(0xFFEFC5A6)

/** Very pale copper wash, for tinted containers on light backgrounds.
 *  [CopperDeep] on it: 5.2:1; [Rust] on it: 5.1:1. */
val CopperWash = Color(0xFFF6E4D6)

// -- Brass (the "strong / positive" family) ---------------------------------------------

/** Brand: brass, a pale highlight. 13.9:1 on the night ground. */
val Brass = Color(0xFFF3DCA8)

/** Brand: brassStrong. Used as a fill (bars, rings); too light for text on paper. */
val BrassStrong = Color(0xFFC99C3B)

/** Darkened brass, 5.6:1 on [WarmBgLight] — brass-toned text in the light theme. */
val BrassDeep = Color(0xFF7E6013)

// -- Rust (the "danger / weak" family) --------------------------------------------------

/** Brand: rust. 6.0:1 on [WarmBgLight], 6.2:1 on [WarmSurfaceLight]. */
val Rust = Color(0xFFA63D22)

/** Lightened rust, 5.4:1 on the night ground and 5.1:1 on [CopperSurface] — error text
 *  and weak-strength fills in the dark theme. */
val RustLight = Color(0xFFD9694A)

// -- Neutrals ---------------------------------------------------------------------------

/** Family ivory — body text on the night ground (15.4:1) and surfaces (14.5:1). */
val WarmOnDark = Color(0xFFEDE9DD)

/** Warm ink — body text in the light theme: 14.8:1 on [WarmBgLight]. */
val WarmOnLight = Color(0xFF2B211A)

/** Family warm paper — the light theme's ground; deliberately not stark white. */
val WarmBgLight = Color(0xFFFBF8F2)

/** Brand: surfaceLight — a warm near-white a breath above the paper. */
val WarmSurfaceLight = Color(0xFFFFFDF8)

/** One step down from the light surface, for inset fields and list separators. */
val WarmSurfaceLightSunk = Color(0xFFF2EBDF)

/** Brand: muted. Secondary text on the night surfaces: 5.8:1 on the ground, 5.5:1 on
 *  [CopperSurface], 5.0:1 on [CopperSurfaceHigh] — so it clears 4.5:1 everywhere it sits. */
val Muted = Color(0xFFA08D77)

/** Secondary text on light surfaces: 6.1:1 on [WarmBgLight], 5.5:1 on the sunken step. */
val MutedOnLight = Color(0xFF6E5B47)

/** Outline / divider tone on light surfaces. */
val WarmOutlineLight = Color(0xFFDDD3C4)

// -- App icon -------------------------------------------------------------------------

/** Shield body in the app icon — the member tint, as in the approved identity. */
val ShieldCopper = Color(0xFFC4764E)

/** Centre merlon of the shield's crenellated top edge. */
val ShieldMerlon = Brass
