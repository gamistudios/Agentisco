package com.awaki.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Dark — cool blue-tinted neutrals: the page is a little blue rather than pure
// black, and depth comes from the surfaceContainer ladder, not from shadows.
// ---------------------------------------------------------------------------

val DarkBackground = Color(0xFF090D16)
val DarkSurface = Color(0xFF0E1320)
val DarkSurfaceContainerLowest = Color(0xFF070A12)
val DarkSurfaceContainerLow = Color(0xFF111726)
val DarkSurfaceContainer = Color(0xFF172033)
val DarkSurfaceContainerHigh = Color(0xFF1E293B)
val DarkSurfaceContainerHighest = Color(0xFF273449)
val DarkBorder = Color(0xFF2B3A57)
val DarkBorderSubtle = Color(0xFF1E283C)

val DarkTextPrimary = Color(0xFFF1F5F9)
val DarkTextSecondary = Color(0xFF94A3B8)

// #7586A0 was the starting estimate but only reached 3.95:1 on surfaceContainerHigh;
// this clears AA on every step of the ladder the app actually paints.
val DarkTextMuted = Color(0xFF8293AB)
val DarkTextCode = Color(0xFFE2E8F0)

// Blue is the action, slate-blue the active state, violet the highlight.
val DarkPrimary = Color(0xFF5A94FF)
val DarkOnPrimary = Color(0xFF031A47)
val DarkPrimaryContainer = Color(0xFF1D3F94)
val DarkOnPrimaryContainer = Color(0xFFDBE8FF)
val DarkSecondary = Color(0xFF9DB4DB)
val DarkOnSecondary = Color(0xFF13203D)
val DarkSecondaryContainer = Color(0xFF222F4B)
val DarkOnSecondaryContainer = Color(0xFFDCE6FA)
val DarkTertiary = Color(0xFFA5A8FF)
val DarkOnTertiary = Color(0xFF1B1D6B)
val DarkTertiaryContainer = Color(0xFF2E3190)
val DarkOnTertiaryContainer = Color(0xFFE0E1FF)

val DarkError = Color(0xFFF87171)
val DarkOnError = Color(0xFF450A0A)
val DarkErrorContainer = Color(0xFF7F1D1D)
val DarkOnErrorContainer = Color(0xFFFEE2E2)
val DarkSuccess = Color(0xFF10B981)
val DarkOnSuccess = Color(0xFF04150F)
val DarkSuccessContainer = Color(0xFF064E3B)
val DarkWarning = Color(0xFFF59E0B)
val DarkOnWarning = Color(0xFF2B1400)
val DarkWarningContainer = Color(0xFF78350F)

val DarkSyntaxKeyword = Color(0xFFFB7185)
val DarkSyntaxFunction = Color(0xFF6AA8FF)
val DarkSyntaxString = Color(0xFF34D399)
val DarkSyntaxType = Color(0xFFFBBF24)
val DarkSyntaxComment = Color(0xFF7586A0)
val DarkSyntaxNumber = Color(0xFFA78BFA)
val DarkSyntaxPunctuation = Color(0xFF94A3B8)

// ---------------------------------------------------------------------------
// Light — the same ladder, inverted: white cards on a blue-tinted page.
// ---------------------------------------------------------------------------

val LightBackground = Color(0xFFF6F8FC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF1F4FA)
val LightSurfaceContainer = Color(0xFFEBF0F8)
val LightSurfaceContainerHigh = Color(0xFFE4EAF4)
val LightSurfaceContainerHighest = Color(0xFFDCE4F0)
val LightBorder = Color(0xFFC3CDDF)
val LightBorderSubtle = Color(0xFFE0E6F1)

val LightTextPrimary = Color(0xFF0F172A)
val LightTextSecondary = Color(0xFF475569)
val LightTextMuted = Color(0xFF54637B)
val LightTextCode = Color(0xFF1E293B)

val LightPrimary = Color(0xFF1D4ED8)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFDBE6FF)
val LightOnPrimaryContainer = Color(0xFF0B1F5C)
val LightSecondary = Color(0xFF475B85)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFDDE5F5)
val LightOnSecondaryContainer = Color(0xFF111D3A)
val LightTertiary = Color(0xFF5B47D6)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFE9E5FF)
val LightOnTertiaryContainer = Color(0xFF21185F)

val LightError = Color(0xFFC81E1E)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFEE2E2)
val LightOnErrorContainer = Color(0xFF450A0A)
val LightSuccess = Color(0xFF047857)
val LightOnSuccess = Color(0xFFECFDF5)
val LightSuccessContainer = Color(0xFFD1FAE5)
val LightWarning = Color(0xFFA84D09)
val LightOnWarning = Color(0xFFFFFBEB)
val LightWarningContainer = Color(0xFFFEF3C7)

val LightSyntaxKeyword = Color(0xFFC41B47)
val LightSyntaxFunction = Color(0xFF1D4ED8)
val LightSyntaxString = Color(0xFF047857)
val LightSyntaxType = Color(0xFFA84D09)
val LightSyntaxComment = Color(0xFF54637B)
val LightSyntaxNumber = Color(0xFF6D4AE0)
val LightSyntaxPunctuation = Color(0xFF475569)
