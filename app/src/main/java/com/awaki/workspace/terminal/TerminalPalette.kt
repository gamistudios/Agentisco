package com.awaki.workspace.terminal

import androidx.compose.ui.graphics.toArgb
import com.awaki.ui.theme.UiPalette
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle

/**
 * The terminal's default ink, canvas and cursor, taken from the UI theme.
 *
 * The console is a plain Android view that reads a static array rather than
 * MaterialTheme, so the theme has to be handed to it explicitly. Only those three
 * follow the app: the 16 ANSI colours and the 256-colour cube are emitted by index by
 * shell programs, and a UI theme that repainted them would change what `ls` and `vim`
 * mean by "red".
 */
object TerminalPalette {

  fun apply(palette: UiPalette) {
    val defaults = TerminalColors.COLOR_SCHEME.mDefaultColors
    defaults[TextStyle.COLOR_INDEX_FOREGROUND] = palette.textPrimary.toArgb()
    defaults[TextStyle.COLOR_INDEX_BACKGROUND] = palette.background.toArgb()
    defaults[TextStyle.COLOR_INDEX_CURSOR] = palette.primary.toArgb()
  }

  /**
   * Pull a live shell back to the theme's defaults — a program inside it may have
   * recoloured itself with OSC sequences — and ask its view to redraw, which the
   * session's client bridge forwards to the attached TerminalView.
   */
  fun refresh(session: TerminalSession) {
    session.emulator?.mColors?.reset()
    session.onColorsChanged()
  }
}
