package com.awaki.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The colours Material 3 has no role for: the muted text a settings row explains
 * itself with, the code surface, status beyond error, and the syntax palette.
 */
@Immutable
data class AwakiExtraColors(
  val textMuted: Color,
  val textCode: Color,
  val success: Color,
  val onSuccess: Color,
  val successContainer: Color,
  val warning: Color,
  val onWarning: Color,
  val warningContainer: Color,
  val syntaxKeyword: Color,
  val syntaxFunction: Color,
  val syntaxString: Color,
  val syntaxType: Color,
  val syntaxComment: Color,
  val syntaxNumber: Color,
  val syntaxPunctuation: Color
)

val LocalAwakiColors = staticCompositionLocalOf<AwakiExtraColors> {
  error("AwakiExtraColors not provided")
}

object AwakiTheme {
  val extra: AwakiExtraColors
    @Composable get() = LocalAwakiColors.current
}

/**
 * Paint [content] with [palette].
 *
 * The palette's twelve slots go through [resolveUiTheme], which derives the rest of
 * the scheme — so nesting this composable with a different palette is all a theme
 * preview needs to render real UI in colours the app has never shown.
 */
@Composable
fun AwakiTheme(
  palette: UiPalette = DefaultUiTheme,
  content: @Composable () -> Unit
) {
  val resolved = remember(palette) { resolveUiTheme(palette) }

  CompositionLocalProvider(LocalAwakiColors provides resolved.extras) {
    MaterialTheme(
      colorScheme = resolved.scheme,
      typography = Typography,
      content = content
    )
  }
}
