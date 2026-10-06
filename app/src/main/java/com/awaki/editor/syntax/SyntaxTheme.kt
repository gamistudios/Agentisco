package com.awaki.editor.syntax

import androidx.compose.ui.graphics.Color
import com.awaki.ui.theme.DarkBackground
import com.awaki.ui.theme.DarkError
import com.awaki.ui.theme.DarkPrimary
import com.awaki.ui.theme.DarkSurfaceContainer
import com.awaki.ui.theme.DarkSurfaceContainerLow
import com.awaki.ui.theme.DarkSurfaceContainerLowest
import com.awaki.ui.theme.DarkSyntaxFunction
import com.awaki.ui.theme.DarkSyntaxKeyword
import com.awaki.ui.theme.DarkSyntaxNumber
import com.awaki.ui.theme.DarkSyntaxPunctuation
import com.awaki.ui.theme.DarkSyntaxString
import com.awaki.ui.theme.DarkSyntaxType
import com.awaki.ui.theme.DarkTertiary
import com.awaki.ui.theme.DarkTextCode
import com.awaki.ui.theme.DarkTextMuted

data class SyntaxTheme(
  val name: String,
  val background: Color,
  val surface: Color,
  val activeLineBg: Color,
  val selectionBg: Color,
  val gutterBg: Color,
  val gutterText: Color,
  val text: Color,
  val keyword: Color,
  val function: Color,
  val classType: Color,
  val variable: Color,
  val string: Color,
  val number: Color,
  val comment: Color,
  val operator: Color,
  val constant: Color,
  val decorator: Color,
  val tag: Color,
  val attribute: Color,
  val cssProperty: Color,
  val jsonKey: Color,
  val markdownHeading: Color,
  val markdownBold: Color,
  val markdownCode: Color,
  val bracketMatchBg: Color,
  val bracketMatchBorder: Color,
  val errorWave: Color
) {
  companion object {
    /** The built-in palette is the app palette, so it reads the tokens rather than copying them. */
    val AwakiDark = SyntaxTheme(
      name = "Awaki Dark",
      background = DarkBackground,
      surface = DarkSurfaceContainerLow,
      activeLineBg = DarkSurfaceContainer,
      selectionBg = DarkPrimary.copy(alpha = 0.25f),
      gutterBg = DarkSurfaceContainerLowest,
      gutterText = DarkTextMuted,
      text = DarkTextCode,
      keyword = DarkSyntaxKeyword,     // Rose
      function = DarkSyntaxFunction,   // Azure
      classType = DarkSyntaxType,      // Amber
      variable = DarkTertiary,         // Periwinkle
      string = DarkSyntaxString,       // Emerald
      number = DarkSyntaxNumber,       // Violet
      comment = DarkTextMuted,         // Blue-tinted slate
      operator = DarkSyntaxPunctuation,
      constant = DarkSyntaxType,
      decorator = DarkTertiary,
      tag = DarkSyntaxKeyword,
      attribute = DarkTertiary,
      cssProperty = DarkSyntaxFunction,
      jsonKey = DarkTertiary,
      markdownHeading = DarkSyntaxFunction,
      markdownBold = DarkSyntaxType,
      markdownCode = DarkSyntaxString,
      bracketMatchBg = DarkSyntaxFunction.copy(alpha = 0.2f),
      bracketMatchBorder = DarkSyntaxFunction,
      errorWave = DarkError
    )

    val OneDark = SyntaxTheme(
      name = "One Dark Pro",
      background = Color(0xFF1E1E2E),
      surface = Color(0xFF24273A),
      activeLineBg = Color(0xFF282C40),
      selectionBg = Color(0x40589BF6),
      gutterBg = Color(0xFF1E1E2E),
      gutterText = Color(0xFF5C6370),
      text = Color(0xFFABB2BF),
      keyword = Color(0xFFC678DD),     // Purple
      function = Color(0xFF61AFEF),    // Blue
      classType = Color(0xFFE5C07B),   // Gold
      variable = Color(0xFFE06C75),    // Coral
      string = Color(0xFF98C379),      // Soft Green
      number = Color(0xFFD19A66),      // Orange
      comment = Color(0xFF5C6370),     // Gray
      operator = Color(0xFF56B6C2),    // Cyan
      constant = Color(0xFFD19A66),
      decorator = Color(0xFFC678DD),
      tag = Color(0xFFE06C75),
      attribute = Color(0xFFD19A66),
      cssProperty = Color(0xFF56B6C2),
      jsonKey = Color(0xFFE06C75),
      markdownHeading = Color(0xFF61AFEF),
      markdownBold = Color(0xFFE5C07B),
      markdownCode = Color(0xFF98C379),
      bracketMatchBg = Color(0x3361AFEF),
      bracketMatchBorder = Color(0xFF61AFEF),
      errorWave = Color(0xFFE06C75)
    )

    val MonokaiPro = SyntaxTheme(
      name = "Monokai Pro",
      background = Color(0xFF19181A),
      surface = Color(0xFF221F22),
      activeLineBg = Color(0xFF2D2A2E),
      selectionBg = Color(0x40FFD866),
      gutterBg = Color(0xFF19181A),
      gutterText = Color(0xFF727072),
      text = Color(0xFFFCFCFA),
      keyword = Color(0xFFFF6188),     // Magenta
      function = Color(0xFFA9DC76),    // Lime
      classType = Color(0xFF78DCE8),   // Cyan
      variable = Color(0xFFFCFCFA),    // White
      string = Color(0xFFFFD866),      // Yellow
      number = Color(0xFFAB9DF2),      // Lilac
      comment = Color(0xFF727072),     // Muted Gray
      operator = Color(0xFFFF6188),    // Magenta
      constant = Color(0xFFAB9DF2),
      decorator = Color(0xFF78DCE8),
      tag = Color(0xFFFF6188),
      attribute = Color(0xFF78DCE8),
      cssProperty = Color(0xFF78DCE8),
      jsonKey = Color(0xFFFF6188),
      markdownHeading = Color(0xFFFF6188),
      markdownBold = Color(0xFFFFD866),
      markdownCode = Color(0xFFA9DC76),
      bracketMatchBg = Color(0x33FFD866),
      bracketMatchBorder = Color(0xFFFFD866),
      errorWave = Color(0xFFFF6188)
    )

    val TokyoNight = SyntaxTheme(
      name = "Tokyo Night",
      background = Color(0xFF1A1B26),
      surface = Color(0xFF24283B),
      activeLineBg = Color(0xFF292E42),
      selectionBg = Color(0x407AA2F7),
      gutterBg = Color(0xFF1A1B26),
      gutterText = Color(0xFF565F89),
      text = Color(0xFFA9B1D6),
      keyword = Color(0xFFBB9AF7),     // Purple
      function = Color(0xFF7AA2F7),    // Blue
      classType = Color(0xFF2AC3DE),   // Aqua
      variable = Color(0xFFC0CAF5),    // Bright Text
      string = Color(0xFF9ECE6A),      // Green
      number = Color(0xFFFF9E64),      // Orange
      comment = Color(0xFF565F89),     // Deep Slate
      operator = Color(0xFF89DDFF),    // Cyan
      constant = Color(0xFFFF9E64),
      decorator = Color(0xFF7DCFFF),
      tag = Color(0xFFF7768E),
      attribute = Color(0xFFBB9AF7),
      cssProperty = Color(0xFF7AA2F7),
      jsonKey = Color(0xFF7AA2F7),
      markdownHeading = Color(0xFF7AA2F7),
      markdownBold = Color(0xFFFF9E64),
      markdownCode = Color(0xFF9ECE6A),
      bracketMatchBg = Color(0x337AA2F7),
      bracketMatchBorder = Color(0xFF7AA2F7),
      errorWave = Color(0xFFF7768E)
    )

    val GitHubDark = SyntaxTheme(
      name = "GitHub Dark",
      background = Color(0xFF0D1117),
      surface = Color(0xFF161B22),
      activeLineBg = Color(0xFF21262D),
      selectionBg = Color(0x4058A6FF),
      gutterBg = Color(0xFF0D1117),
      gutterText = Color(0xFF6E7681),
      text = Color(0xFFC9D1D9),
      keyword = Color(0xFFFF7B72),     // Red
      function = Color(0xFFD2A8FF),    // Lilac
      classType = Color(0xFFFFA657),   // Amber
      variable = Color(0xFF79C0FF),    // Light Blue
      string = Color(0xFFA5D6FF),      // Sky Blue
      number = Color(0xFF79C0FF),      // Blue
      comment = Color(0xFF8B949E),     // Gray
      operator = Color(0xFFFF7B72),
      constant = Color(0xFF79C0FF),
      decorator = Color(0xFFD2A8FF),
      tag = Color(0xFF7EE787),
      attribute = Color(0xFF79C0FF),
      cssProperty = Color(0xFF79C0FF),
      jsonKey = Color(0xFF7EE787),
      markdownHeading = Color(0xFF58A6FF),
      markdownBold = Color(0xFFFFA657),
      markdownCode = Color(0xFFA5D6FF),
      bracketMatchBg = Color(0x3358A6FF),
      bracketMatchBorder = Color(0xFF58A6FF),
      errorWave = Color(0xFFF85149)
    )

    val allThemes = listOf(AwakiDark, OneDark, MonokaiPro, TokyoNight, GitHubDark)

    fun getByName(name: String): SyntaxTheme {
      return allThemes.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: AwakiDark
    }
  }
}
