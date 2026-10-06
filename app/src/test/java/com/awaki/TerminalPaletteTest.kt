package com.awaki

import com.awaki.ui.theme.Daylight
import com.awaki.ui.theme.DefaultUiTheme
import com.awaki.ui.theme.Evergreen
import com.awaki.workspace.terminal.TerminalPalette
import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle
import androidx.compose.ui.graphics.toArgb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The console is a plain Android view reading a static colour array, so the theme has to
 * be pushed into it by hand. What is pinned here is exactly how far that reach goes: the
 * default ink, canvas and cursor follow the app, and the indexed ANSI colours do not — a
 * theme that repainted those would change what `ls` and `vim` mean by "red".
 */
class TerminalPaletteTest {

  @After
  fun restoreDefaultTheme() {
    // COLOR_SCHEME is process-wide static data; leave it as the app would start it.
    TerminalPalette.apply(DefaultUiTheme)
  }

  private fun defaults() = TerminalColors.COLOR_SCHEME.mDefaultColors

  @Test
  fun `the console takes its ink, canvas and cursor from the theme`() {
    TerminalPalette.apply(Evergreen)

    val expected = listOf(
      TextStyle.COLOR_INDEX_FOREGROUND to Evergreen.textPrimary,
      TextStyle.COLOR_INDEX_BACKGROUND to Evergreen.background,
      TextStyle.COLOR_INDEX_CURSOR to Evergreen.primary
    )
    expected.forEach { (index, color) ->
      assertEquals(
        "index $index should hold the theme's own colour",
        color.toArgb(),
        defaults()[index]
      )
    }
  }

  @Test
  fun `a theme change moves the defaults but leaves the ansi palette alone`() {
    val ansiBefore = (0 until 16).map { defaults()[it] }
    val cubeBefore = defaults()[196]

    TerminalPalette.apply(Daylight)

    assertEquals(Daylight.background.toArgb(), defaults()[TextStyle.COLOR_INDEX_BACKGROUND])
    assertEquals(
      "the 16 ANSI colours and the 256-colour cube are emitted by index, not by theme",
      ansiBefore,
      (0 until 16).map { defaults()[it] }
    )
    assertEquals(cubeBefore, defaults()[196])
  }

  @Test
  fun `a shell that recoloured itself is pulled back to the theme`() {
    TerminalPalette.apply(Evergreen)
    val themeInk = defaults()[TextStyle.COLOR_INDEX_FOREGROUND]

    val colors = TerminalColors()
    assertEquals(
      "a fresh emulator starts on the theme, not on the library's own defaults",
      themeInk,
      colors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND]
    )

    // What a program inside the shell does with an OSC 10 sequence.
    colors.tryParseColor(TextStyle.COLOR_INDEX_FOREGROUND, "#FF0000")
    assertNotEquals(themeInk, colors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND])

    // What TerminalPalette.refresh does to a live session.
    colors.reset()
    assertEquals(themeInk, colors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND])
  }
}
