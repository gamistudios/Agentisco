package com.awaki

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.awaki.ui.theme.AA_TEXT
import com.awaki.ui.theme.DefaultUiTheme
import com.awaki.ui.theme.ResolvedTheme
import com.awaki.ui.theme.UiPalette
import com.awaki.ui.theme.contrast
import com.awaki.ui.theme.resolveUiTheme
import com.awaki.ui.theme.uiThemeByKey
import com.awaki.ui.theme.uiThemes
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The promise the theme system makes: a theme is twelve colours, and every colour the
 * app derives from them stays readable where the app actually paints it.
 *
 * This is the check that catches a new theme at the desk instead of on the phone. It
 * walks each one through the surfaces the UI is built from — the page, every rung of the
 * surface ladder, a filled button, a status chip, a toast — instead of trusting that a
 * palette which looks good in a mockup has contrast.
 *
 * Plain JUnit on purpose: the derivation is arithmetic on [Color] with no Android
 * framework in the path, so all seventeen themes are audited in well under a second.
 */
class UiThemeContrastTest {

  private val problems = mutableListOf<String>()

  /** WCAG's bar for body text. The derivation aims above it; the promise is at it. */
  private val aa = 4.5f

  private fun surfacesOf(scheme: ColorScheme) = listOf(
    "page" to scheme.background,
    "surface" to scheme.surface,
    "cLowest" to scheme.surfaceContainerLowest,
    "cLow" to scheme.surfaceContainerLow,
    "container" to scheme.surfaceContainer,
    "cHigh" to scheme.surfaceContainerHigh,
    "cHighest" to scheme.surfaceContainerHighest
  )

  /** The rungs a row, a card or a chip is painted on. */
  private val rowLadder = setOf("page", "surface", "container", "cHigh")

  private fun audit(body: (UiPalette, ResolvedTheme) -> Unit) {
    uiThemes.forEach { body(it, resolveUiTheme(it)) }
    if (problems.isNotEmpty()) fail("${problems.size} colour pair(s) fail:\n" + problems.joinToString("\n"))
  }

  private fun expectContrast(
    theme: UiPalette,
    label: String,
    ink: Color,
    on: Color,
    minimum: Float
  ) {
    val ratio = contrast(ink, on)
    if (ratio < minimum) {
      problems += "%-13s %-32s %5.2f:1 (needs %.1f) — %s on %s".format(
        theme.name, label, ratio, minimum, hex(ink), hex(on)
      )
    }
  }

  private fun hex(c: Color): String {
    fun channel(v: Float) = (v * 255f).roundToInt().coerceIn(0, 255)
    return "#%02X%02X%02X".format(channel(c.red), channel(c.green), channel(c.blue))
  }

  /** Contrast against black is a monotone stand-in for luminance ordering. */
  private fun brighter(c: Color): Float = contrast(c, Color.Black)

  /** One step of the ladder: the two rungs differ, and the higher one sits the way a
   *  higher rung has to sit on a theme of this family. */
  private fun assertRung(theme: UiPalette, label: String, lower: Color, higher: Color) {
    val step = contrast(lower, higher)
    assertTrue(
      "${theme.name}: $label are the same colour (${hex(lower)} and ${hex(higher)}, %.2f:1)".format(step),
      step > 1.02f
    )
    assertEquals(
      "${theme.name}: $label moves the wrong way for a ${if (theme.dark) "dark" else "light"} theme (${hex(lower)} to ${hex(higher)})",
      theme.dark,
      brighter(higher) > brighter(lower)
    )
  }

  @Test
  fun `body text is readable on every surface the theme paints`() = audit { theme, resolved ->
    val scheme = resolved.scheme
    surfacesOf(scheme).forEach { (name, surface) ->
      expectContrast(theme, "primary text on $name", scheme.onBackground, surface, aa)
      expectContrast(theme, "secondary text on $name", scheme.onSurfaceVariant, surface, aa)
      expectContrast(theme, "monospace text on $name", resolved.extras.textCode, surface, aa)
    }
  }

  @Test
  fun `muted text is readable wherever a row puts it`() = audit { theme, resolved ->
    val scheme = resolved.scheme
    surfacesOf(scheme)
      .filter { (name, _) -> name in rowLadder }
      .forEach { (name, surface) ->
        expectContrast(theme, "muted text on $name", resolved.extras.textMuted, surface, aa)
      }
  }

  @Test
  fun `an accent used as label text survives the row ladder`() = audit { theme, resolved ->
    val scheme = resolved.scheme
    val extras = resolved.extras
    val accents = listOf(
      "primary" to scheme.primary,
      "secondary" to scheme.secondary,
      "tertiary" to scheme.tertiary,
      "success" to extras.success,
      "warning" to extras.warning,
      "error" to scheme.error
    )
    surfacesOf(scheme)
      .filter { (name, _) -> name in rowLadder }
      .forEach { (surfaceName, surface) ->
        accents.forEach { (accentName, accent) ->
          expectContrast(theme, "$accentName label on $surfaceName", accent, surface, aa)
        }
      }
  }

  @Test
  fun `a filled surface carries its own ink`() = audit { theme, resolved ->
    val scheme = resolved.scheme
    val extras = resolved.extras
    listOf(
      "on primary" to (scheme.primary to scheme.onPrimary),
      "on secondary" to (scheme.secondary to scheme.onSecondary),
      "on tertiary" to (scheme.tertiary to scheme.onTertiary),
      "on error" to (scheme.error to scheme.onError),
      "on success" to (extras.success to extras.onSuccess),
      "on warning" to (extras.warning to extras.onWarning),
      "on primary container" to (scheme.primaryContainer to scheme.onPrimaryContainer),
      "on secondary container" to (scheme.secondaryContainer to scheme.onSecondaryContainer),
      "on tertiary container" to (scheme.tertiaryContainer to scheme.onTertiaryContainer),
      "on error container" to (scheme.errorContainer to scheme.onErrorContainer)
    ).forEach { (label, pair) ->
      expectContrast(theme, label, pair.second, pair.first, aa)
    }
  }

  @Test
  fun `a toast is readable`() = audit { theme, resolved ->
    expectContrast(
      theme,
      "toast ink on toast",
      resolved.scheme.inverseOnSurface,
      resolved.scheme.inverseSurface,
      aa
    )
  }

  @Test
  fun `an accent as an icon clears the graphics bar`() = audit { theme, resolved ->
    val scheme = resolved.scheme
    // Non-text graphics need 3:1 rather than 4.5:1, and an icon is painted at that
    // weight on the busiest rung a row reaches.
    listOf(scheme.primary, scheme.secondary, scheme.tertiary, scheme.error, resolved.extras.success)
      .forEach { accent ->
        expectContrast(theme, "icon on cHigh", accent, scheme.surfaceContainerHigh, 3.0f)
      }
  }

  @Test
  fun `the surface ladder climbs away from the page`() {
    uiThemes.forEach { theme ->
      val scheme = resolveUiTheme(theme).scheme
      // The rungs a card, a row and a pressed row are painted on. surfaceContainerLowest
      // is not in this list: it deliberately sits on the far side of the page.
      val ladder = listOf(
        scheme.background,
        scheme.surfaceContainerLow,
        scheme.surfaceContainer,
        scheme.surfaceContainerHigh,
        scheme.surfaceContainerHighest
      )
      ladder.windowed(2).forEachIndexed { index, (lower, higher) ->
        assertRung(theme, "rung $index to ${index + 1}", lower, higher)
      }
      val span = contrast(scheme.background, scheme.surfaceContainerHighest)
      assertTrue(
        "${theme.name}: the ladder spans only %.2f:1 from the page to its top rung".format(span),
        span > 1.2f
      )
      // The one step the eye reads as "this is a panel rather than the page".
      val card = contrast(scheme.background, scheme.surfaceContainer)
      assertTrue(
        "${theme.name}: a card does not separate from the page (%.2f:1, %s on %s)".format(
          card, hex(scheme.surfaceContainer), hex(scheme.background)
        ),
        card > 1.05f
      )
    }
  }

  @Test
  fun `the deepest rung sits under the page`() {
    uiThemes.forEach { theme ->
      val scheme = resolveUiTheme(theme).scheme
      // Direction only, with no minimum step: contrast is a ratio, so at the near-black
      // end a whole rung is worth 1.03:1 no matter how clearly it reads. A light theme's
      // lowest rung floats above the page instead, which is where its shadows would have
      // put it, and is the far side all the same.
      val lowest = scheme.surfaceContainerLowest
      val underPage = if (theme.dark) brighter(lowest) < brighter(scheme.background)
      else brighter(lowest) > brighter(scheme.background)
      assertTrue(
        "${theme.name}: surfaceContainerLowest (${hex(lowest)}) should sit on the far side of the page (${hex(scheme.background)})",
        underPage
      )
    }
  }

  @Test
  fun `the catalogue holds the themes the settings screen promises`() {
    assertEquals(
      listOf(
        "nocturne", "midnight", "graphite", "abyss", "evergreen", "ember", "grape", "sail_night",
        "one_dark", "monokai_pro", "tokyo_night", "github_dark",
        "daylight", "porcelain", "sandstone", "mint", "sail_day"
      ),
      uiThemes.map { it.key }
    )
    val duplicates = uiThemes.groupBy { it.key }.filterValues { it.size > 1 }.keys
    assertTrue("keys must be unique, found $duplicates", duplicates.isEmpty())
    assertTrue("Nocturne is the theme a fresh install gets", uiThemes.first() === DefaultUiTheme)
    uiThemes.forEach {
      assertTrue("${it.key}: no display name", it.name.isNotBlank())
      assertTrue("${it.key}: no one-line description", it.blurb.isNotBlank())
    }
  }

  @Test
  fun `a stored key resolves, and one this build does not know falls back`() {
    uiThemes.forEach { assertEquals(it, uiThemeByKey(it.key)) }
    assertEquals(DefaultUiTheme, uiThemeByKey("a theme a newer build invented"))
    assertEquals(DefaultUiTheme, uiThemeByKey(""))
  }

  @Test
  fun `the derivation aims above the bar it is checked against`() {
    // AA_TEXT is where every walked colour is stopped. If it ever sits exactly on the
    // bar, a rounding step has room to fail the tests above on a future theme.
    assertTrue("AA_TEXT ($AA_TEXT) must leave margin over $aa", AA_TEXT > aa)
  }
}
