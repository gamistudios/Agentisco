package com.awaki.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * Everything a UI theme needs to paint the app, derived from a [UiPalette]'s twelve
 * semantic slots. Pure functions on [Color] — no composable context, no Android
 * framework — so the contrast rules below are assertable from a plain JVM test.
 */
@Immutable
data class ResolvedTheme(val scheme: ColorScheme, val extras: AwakiExtraColors)

// ---------------------------------------------------------------------------
// Colour maths. Compose keeps Color components as raw sRGB floats, so the WCAG
// formulas apply directly after the standard transfer-function decode.
// ---------------------------------------------------------------------------

private fun channelLinear(c: Float): Float =
  if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

private fun luminance(c: Color): Float =
  0.2126f * channelLinear(c.red) + 0.7152f * channelLinear(c.green) + 0.0722f * channelLinear(c.blue)
/** WCAG 2.1 contrast ratio, 1.0 (identical) to 21.0 (black on white). */
fun contrast(a: Color, b: Color): Float {
  val la = luminance(a)
  val lb = luminance(b)
  return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

private val WhiteInk = Color(0xFFFFFFFF)
private val BlackInk = Color(0xFF000000)

private fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

/**
 * [from] mixed into [to] by [amount], which may exceed 1 or go negative to continue
 * the same line past either end — that is how one container step spans the ladder.
 */
private fun blend(from: Color, to: Color, amount: Float): Color =
  Color(
    red = clamp01(from.red + (to.red - from.red) * amount),
    green = clamp01(from.green + (to.green - from.green) * amount),
    blue = clamp01(from.blue + (to.blue - from.blue) * amount),
    alpha = from.alpha
  )

/** Blend toward white with a positive amount, toward black with a negative one. */
private fun shade(c: Color, amount: Float): Color =
  blend(c, if (amount >= 0f) WhiteInk else BlackInk, kotlin.math.abs(amount))

/**
 * Walk [from] along the line toward [to] — past it if necessary — until the surface
 * has moved by [target] in luminance.
 *
 * Depth is a perceptual property, so the rungs of the surface ladder are spaced in
 * luminance rather than as a fixed blend ratio: ten percent toward the border shifts
 * the eye much further near white than it does near black. Bisection because the
 * blend is linear in gamma-encoded channels while luminance is not, and the answer
 * has no closed form.
 */
private fun climb(from: Color, to: Color, target: Float): Color {
  val furthest = blend(from, to, 4f)
  if (kotlin.math.abs(luminance(furthest) - luminance(from)) < kotlin.math.abs(target)) {
    return furthest
  }
  var lo = 0f
  var hi = 4f
  repeat(20) {
    val mid = (lo + hi) / 2f
    val moved = kotlin.math.abs(luminance(blend(from, to, mid)) - luminance(from))
    if (moved >= kotlin.math.abs(target)) hi = mid else lo = mid
  }
  return blend(from, to, hi)
}

/**
 * The readable ink for a filled surface: the hue-tinted near-black or near-white,
 * whichever carries more contrast. This is what keeps a bright primary from being
 * labelled with white text that only reaches 2.5:1.
 */
private fun inkOn(fill: Color): Color {
  val deep = shade(fill, -0.93f)
  val pale = shade(fill, 0.95f)
  return if (contrast(deep, fill) >= contrast(pale, fill)) deep else pale
}

/**
 * [from] walked toward [toward] in small steps until it clears [minimum] against
 * [busy], the busiest surface it can be painted on. The step direction always
 * raises contrast, because [toward] is the theme's own brightest or darkest ink.
 */
private fun atLeast(from: Color, toward: Color, busy: Color, minimum: Float): Color {
  var best = from
  var step = 0
  while (contrast(best, busy) < minimum && step < 24) {
    best = blend(best, toward, 0.06f)
    step++
  }
  return if (contrast(best, busy) < minimum) toward else best
}

// ---------------------------------------------------------------------------
// Resolution
// ---------------------------------------------------------------------------

/**
 * Small text and non-essential graphics need 3:1 and body text 4.5:1. The derivation
 * aims a tenth above the text bar, so a value that passes here can't fall under it
 * when a rounding step moves it a unit.
 */
const val AA_TEXT = 4.6f

/**
 * Expand a palette's twelve slots into the full Material scheme plus the extras the
 * scheme has no role for.
 *
 * The surface ladder hangs on one idea: the distance from the page to the container
 * is the theme's unit of depth, and the rungs above the container climb by 0.8 and
 * 1.7 of that unit in luminance, using the theme's border as the direction. On
 * Nocturne and Daylight this reproduces the hand-picked ladders they shipped with to
 * within a unit or two per channel; a new theme gets the same relationship for free,
 * and cannot build a surface its own text cannot survive on.
 */
fun resolveUiTheme(p: UiPalette): ResolvedTheme {
  val container = p.surfaceContainer
  val depth = luminance(container) - luminance(p.background)
  val lowest = blend(p.background, container, -0.2f)
  val low = blend(p.background, container, 0.55f)
  val high = climb(container, p.border, 0.8f * depth)
  val highest = climb(container, p.border, 1.7f * depth)

  // A tier below the secondary text, wherever the row ladder leaves room for one.
  // Muted copy lives on rows and chips, which stop at cHigh; cHighest is only what
  // Material components reach internally, and those use the scheme's own inks.
  val dimmed = shade(p.textSecondary, if (p.dark) -0.12f else 0.12f)
  val mutedText = atLeast(dimmed, p.textSecondary, high, AA_TEXT)

  val onPrimary = inkOn(p.primary)
  val onSecondary = inkOn(p.secondary)
  val onTertiary = inkOn(p.tertiary)
  val onSuccess = inkOn(p.success)
  val onWarning = inkOn(p.warning)
  val onError = inkOn(p.error)

  // A status's own well: deep and saturated on a dark page, washed out on a light one.
  fun well(c: Color, lift: Float) = shade(c, if (p.dark) -0.72f else lift)

  val primaryContainer = well(p.primary, 0.84f)
  val secondaryContainer = well(p.secondary, 0.82f)
  val tertiaryContainer = well(p.tertiary, 0.84f)
  val successContainer = well(p.success, 0.86f)
  val warningContainer = well(p.warning, 0.86f)
  val errorContainer = well(p.error, 0.86f)

  fun inkIn(well: Color) = if (p.dark) shade(well, 0.92f) else shade(well, -0.9f)

  // One call path for both halves of Material: the two builders take identical
  // argument lists, and the roles not named here (the fixed/expressive tiers) keep
  // whichever family the theme belongs to.
  val scheme = (if (p.dark) darkColorScheme() else lightColorScheme()).copy(
    primary = p.primary,
    onPrimary = onPrimary,
    primaryContainer = primaryContainer,
    onPrimaryContainer = inkIn(primaryContainer),
    secondary = p.secondary,
    onSecondary = onSecondary,
    secondaryContainer = secondaryContainer,
    onSecondaryContainer = inkIn(secondaryContainer),
    tertiary = p.tertiary,
    onTertiary = onTertiary,
    tertiaryContainer = tertiaryContainer,
    onTertiaryContainer = inkIn(tertiaryContainer),
    error = p.error,
    onError = onError,
    errorContainer = errorContainer,
    onErrorContainer = inkIn(errorContainer),
    background = p.background,
    onBackground = p.textPrimary,
    surface = p.surface,
    onSurface = p.textPrimary,
    surfaceVariant = container,
    onSurfaceVariant = p.textSecondary,
    surfaceContainerLowest = lowest,
    surfaceContainerLow = low,
    surfaceContainer = container,
    surfaceContainerHigh = high,
    surfaceContainerHighest = highest,
    surfaceTint = Color.Transparent,
    outline = p.border,
    outlineVariant = shade(p.border, if (p.dark) -0.35f else 0.45f),
    // A toast inverts the theme: on a dark page a light pill, on a light page a dark
    // one. Lifting the theme's own ink a little keeps it in the same family.
    inverseSurface = shade(p.textPrimary, if (p.dark) -0.06f else 0.12f),
    inverseOnSurface = if (p.dark) shade(p.textPrimary, -0.94f) else WhiteInk,
    inversePrimary = shade(p.primary, if (p.dark) -0.55f else 0.35f),
    scrim = BlackInk
  )

  val extras = AwakiExtraColors(
    textMuted = mutedText,
    textCode = if (p.dark) shade(p.textPrimary, -0.05f) else p.textPrimary,
    success = p.success,
    onSuccess = onSuccess,
    successContainer = successContainer,
    warning = p.warning,
    onWarning = onWarning,
    warningContainer = warningContainer,
    // The editor's built-in palette is the theme's own accents, so code colours move
    // with the UI theme while the imported syntax themes stay fixed.
    syntaxKeyword = p.error,
    syntaxFunction = p.primary,
    syntaxString = p.success,
    syntaxType = p.warning,
    syntaxComment = mutedText,
    syntaxNumber = p.tertiary,
    syntaxPunctuation = p.textSecondary
  )

  return ResolvedTheme(scheme, extras)
}
