package com.awaki.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val AwakiDarkColors = darkColorScheme(
  primary = DarkPrimary,
  onPrimary = DarkOnPrimary,
  primaryContainer = DarkPrimaryContainer,
  onPrimaryContainer = DarkOnPrimaryContainer,
  secondary = DarkSecondary,
  onSecondary = DarkOnSecondary,
  secondaryContainer = DarkSecondaryContainer,
  onSecondaryContainer = DarkOnSecondaryContainer,
  tertiary = DarkTertiary,
  onTertiary = DarkOnTertiary,
  tertiaryContainer = DarkTertiaryContainer,
  onTertiaryContainer = DarkOnTertiaryContainer,
  error = DarkError,
  onError = DarkOnError,
  errorContainer = DarkErrorContainer,
  onErrorContainer = DarkOnErrorContainer,
  background = DarkBackground,
  onBackground = DarkTextPrimary,
  surface = DarkSurface,
  onSurface = DarkTextPrimary,
  surfaceVariant = DarkSurfaceContainer,
  onSurfaceVariant = DarkTextSecondary,
  surfaceContainerLowest = DarkSurfaceContainerLowest,
  surfaceContainerLow = DarkSurfaceContainerLow,
  surfaceContainer = DarkSurfaceContainer,
  surfaceContainerHigh = DarkSurfaceContainerHigh,
  surfaceContainerHighest = DarkSurfaceContainerHighest,
  surfaceTint = Color.Transparent,
  outline = DarkBorder,
  outlineVariant = DarkBorderSubtle,
  inverseSurface = LightSurfaceContainerHigh,
  inverseOnSurface = LightTextPrimary,
  inversePrimary = LightPrimary,
  scrim = Color.Black
)

private val AwakiLightColors = lightColorScheme(
  primary = LightPrimary,
  onPrimary = LightOnPrimary,
  primaryContainer = LightPrimaryContainer,
  onPrimaryContainer = LightOnPrimaryContainer,
  secondary = LightSecondary,
  onSecondary = LightOnSecondary,
  secondaryContainer = LightSecondaryContainer,
  onSecondaryContainer = LightOnSecondaryContainer,
  tertiary = LightTertiary,
  onTertiary = LightOnTertiary,
  tertiaryContainer = LightTertiaryContainer,
  onTertiaryContainer = LightOnTertiaryContainer,
  error = LightError,
  onError = LightOnError,
  errorContainer = LightErrorContainer,
  onErrorContainer = LightOnErrorContainer,
  background = LightBackground,
  onBackground = LightTextPrimary,
  surface = LightSurface,
  onSurface = LightTextPrimary,
  surfaceVariant = LightSurfaceContainer,
  onSurfaceVariant = LightTextSecondary,
  surfaceContainerLowest = LightSurfaceContainerLowest,
  surfaceContainerLow = LightSurfaceContainerLow,
  surfaceContainer = LightSurfaceContainer,
  surfaceContainerHigh = LightSurfaceContainerHigh,
  surfaceContainerHighest = LightSurfaceContainerHighest,
  surfaceTint = Color.Transparent,
  outline = LightBorder,
  outlineVariant = LightBorderSubtle,
  inverseSurface = DarkSurfaceContainerHigh,
  inverseOnSurface = DarkTextPrimary,
  inversePrimary = DarkPrimary,
  scrim = Color.Black
)

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

private val DarkExtras = AwakiExtraColors(
  textMuted = DarkTextMuted,
  textCode = DarkTextCode,
  success = DarkSuccess,
  onSuccess = DarkOnSuccess,
  successContainer = DarkSuccessContainer,
  warning = DarkWarning,
  onWarning = DarkOnWarning,
  warningContainer = DarkWarningContainer,
  syntaxKeyword = DarkSyntaxKeyword,
  syntaxFunction = DarkSyntaxFunction,
  syntaxString = DarkSyntaxString,
  syntaxType = DarkSyntaxType,
  syntaxComment = DarkSyntaxComment,
  syntaxNumber = DarkSyntaxNumber,
  syntaxPunctuation = DarkSyntaxPunctuation
)

private val LightExtras = AwakiExtraColors(
  textMuted = LightTextMuted,
  textCode = LightTextCode,
  success = LightSuccess,
  onSuccess = LightOnSuccess,
  successContainer = LightSuccessContainer,
  warning = LightWarning,
  onWarning = LightOnWarning,
  warningContainer = LightWarningContainer,
  syntaxKeyword = LightSyntaxKeyword,
  syntaxFunction = LightSyntaxFunction,
  syntaxString = LightSyntaxString,
  syntaxType = LightSyntaxType,
  syntaxComment = LightSyntaxComment,
  syntaxNumber = LightSyntaxNumber,
  syntaxPunctuation = LightSyntaxPunctuation
)

val LocalAwakiColors = staticCompositionLocalOf<AwakiExtraColors> {
  error("AwakiExtraColors not provided")
}

object AwakiTheme {
  val extra: AwakiExtraColors
    @Composable get() = LocalAwakiColors.current
}

/** Swap the default for `isSystemInDarkTheme()` when light mode ships. */
@Composable
fun AwakiTheme(
  darkTheme: Boolean = true,
  content: @Composable () -> Unit
) {
  val colorScheme = if (darkTheme) AwakiDarkColors else AwakiLightColors
  val extras = if (darkTheme) DarkExtras else LightExtras

  CompositionLocalProvider(LocalAwakiColors provides extras) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = Typography,
      content = content
    )
  }
}
