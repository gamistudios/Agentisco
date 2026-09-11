package com.agentisco.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AgentIDEDarkColorScheme = darkColorScheme(
  primary = ElectricBlue,
  onPrimary = Color.White,
  primaryContainer = DarkSurfaceElevated,
  onPrimaryContainer = ElectricBlueGlow,
  secondary = CyanAccent,
  onSecondary = Color.Black,
  secondaryContainer = DarkSurfaceHighlight,
  onSecondaryContainer = CyanAccent,
  tertiary = IndigoAccent,
  onTertiary = Color.White,
  background = DarkBackground,
  onBackground = TextPrimary,
  surface = DarkSurface,
  onSurface = TextPrimary,
  surfaceVariant = DarkSurfaceElevated,
  onSurfaceVariant = TextSecondary,
  outline = DarkBorder,
  outlineVariant = DarkBorderSubtle,
  error = DangerRed,
  onError = Color.White
)

@Composable
fun AgentiscoTheme(
  darkTheme: Boolean = true, // Developer IDE defaults to sleek dark mode
  dynamicColor: Boolean = false, // Keep high-contrast developer branding
  content: @Composable () -> Unit
) {
  MaterialTheme(
    colorScheme = AgentIDEDarkColorScheme,
    typography = Typography,
    content = content
  )
}
