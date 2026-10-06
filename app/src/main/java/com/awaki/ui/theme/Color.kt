package com.awaki.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// The editor's built-in code surface.
//
// These are the only fixed colours left in the app: a syntax palette is an
// independent choice (Settings → Editor) and deliberately does not follow the UI
// theme, so code keeps the same colours while the chrome around it changes. Every
// colour the rest of the UI paints comes from a UiPalette, resolved in UiTheme.kt.
// ---------------------------------------------------------------------------

val DarkBackground = Color(0xFF090D16)
val DarkSurfaceContainerLowest = Color(0xFF070A12)
val DarkSurfaceContainerLow = Color(0xFF111726)
val DarkSurfaceContainer = Color(0xFF172033)

// #7586A0 was the starting estimate but only reached 3.95:1 on surfaceContainerHigh;
// this clears AA on every step of the ladder the app actually paints.
val DarkTextMuted = Color(0xFF8293AB)
val DarkTextCode = Color(0xFFE2E8F0)

// Blue is the action, violet the highlight.
val DarkPrimary = Color(0xFF5A94FF)
val DarkTertiary = Color(0xFFA5A8FF)

val DarkError = Color(0xFFF87171)
val DarkWarning = Color(0xFFF59E0B)
val DarkOnWarning = Color(0xFF2B1400)

val DarkSyntaxKeyword = Color(0xFFFB7185)
val DarkSyntaxFunction = Color(0xFF6AA8FF)
val DarkSyntaxString = Color(0xFF34D399)
val DarkSyntaxType = Color(0xFFFBBF24)
val DarkSyntaxNumber = Color(0xFFA78BFA)
val DarkSyntaxPunctuation = Color(0xFF94A3B8)
