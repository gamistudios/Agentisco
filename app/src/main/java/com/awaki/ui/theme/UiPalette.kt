package com.awaki.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The twelve slots a UI theme has to supply. Everything else the app paints — the
 * surface ladder, the `on*` inks, the containers, the editor's built-in syntax
 * colours — is derived from these in [resolveUiTheme], which is why a theme is a
 * handful of values rather than a whole colour system.
 */
@Immutable
data class UiPalette(
  val key: String,
  val name: String,
  val blurb: String,
  val dark: Boolean,
  val background: Color,
  val surface: Color,
  val surfaceContainer: Color,
  val primary: Color,
  val secondary: Color,
  val tertiary: Color,
  val textPrimary: Color,
  val textSecondary: Color,
  val border: Color,
  val success: Color,
  val warning: Color,
  val error: Color
)

private fun hex(v: Int) = Color(v.toLong() or 0xFF000000L)

// ---------------------------------------------------------------------------
// Dark
// ---------------------------------------------------------------------------

/** Awaki's own dark: blue-tinted neutrals, azure for the action, violet for the highlight. */
val Nocturne = UiPalette(
  key = "nocturne",
  name = "Nocturne",
  blurb = "Awaki dark",
  dark = true,
  background = hex(0x090D16),
  surface = hex(0x0E1320),
  surfaceContainer = hex(0x172033),
  primary = hex(0x5A94FF),
  secondary = hex(0x9DB4DB),
  tertiary = hex(0xA5A8FF),
  textPrimary = hex(0xF1F5F9),
  textSecondary = hex(0x94A3B8),
  border = hex(0x2B3A57),
  success = hex(0x10B981),
  warning = hex(0xF59E0B),
  error = hex(0xF87171)
)

/** Nocturne pushed further down: a near-black page with more blue in it. */
val Midnight = UiPalette(
  key = "midnight",
  name = "Midnight",
  blurb = "Deeper blue-black",
  dark = true,
  background = hex(0x04060C),
  surface = hex(0x0A0F1A),
  surfaceContainer = hex(0x121A2B),
  primary = hex(0x6FA8FF),
  secondary = hex(0x8FA6CC),
  tertiary = hex(0x98A0FF),
  textPrimary = hex(0xE8EEF8),
  textSecondary = hex(0x92A2BC),
  border = hex(0x24334D),
  success = hex(0x22C55E),
  warning = hex(0xFBBF24),
  error = hex(0xFB7185)
)

/** No hue in the page at all: the accent does the talking. */
val Graphite = UiPalette(
  key = "graphite",
  name = "Graphite",
  blurb = "Neutral grey",
  dark = true,
  background = hex(0x111111),
  surface = hex(0x17181A),
  surfaceContainer = hex(0x202226),
  primary = hex(0xA8B0BC),
  secondary = hex(0x8C95A3),
  tertiary = hex(0xC6CEFF),
  textPrimary = hex(0xF2F3F5),
  textSecondary = hex(0x9CA3AF),
  border = hex(0x33373D),
  success = hex(0x34D399),
  warning = hex(0xFBBF24),
  error = hex(0xF87171)
)

val Abyss = UiPalette(
  key = "abyss",
  name = "Abyss",
  blurb = "Deep navy",
  dark = true,
  background = hex(0x050B1A),
  surface = hex(0x0B1428),
  surfaceContainer = hex(0x14203C),
  primary = hex(0x5D90FB),
  secondary = hex(0x7FA6D9),
  tertiary = hex(0x8B9CFF),
  textPrimary = hex(0xE6ECFA),
  textSecondary = hex(0x8FA0C0),
  border = hex(0x24365C),
  success = hex(0x2DD4A7),
  warning = hex(0xF0A72A),
  error = hex(0xFF6B6B)
)

val Evergreen = UiPalette(
  key = "evergreen",
  name = "Evergreen",
  blurb = "Dark green",
  dark = true,
  background = hex(0x07130F),
  surface = hex(0x0C1C16),
  surfaceContainer = hex(0x12291F),
  primary = hex(0x4ADE80),
  secondary = hex(0x86B79C),
  tertiary = hex(0x7DD3FC),
  textPrimary = hex(0xE8F5EE),
  textSecondary = hex(0x8FAD9C),
  border = hex(0x1F4032),
  success = hex(0x34D399),
  warning = hex(0xFBBF24),
  error = hex(0xFB7185)
)

val Ember = UiPalette(
  key = "ember",
  name = "Ember",
  blurb = "Warm orange",
  dark = true,
  background = hex(0x160C07),
  surface = hex(0x20130B),
  surfaceContainer = hex(0x2C1B10),
  primary = hex(0xFF9A5C),
  secondary = hex(0xD9A27E),
  // The highlight stays cool. An amber accent here would be indistinguishable from
  // the warning colour, and warnings are the one thing this theme paints often.
  tertiary = hex(0xB9A0FF),
  textPrimary = hex(0xF8EDE5),
  textSecondary = hex(0xB79684),
  border = hex(0x472A1B),
  success = hex(0x4ADE80),
  warning = hex(0xFBBF24),
  error = hex(0xFF6B6B)
)

val Grape = UiPalette(
  key = "grape",
  name = "Grape",
  blurb = "Purple",
  dark = true,
  background = hex(0x100914),
  surface = hex(0x181022),
  surfaceContainer = hex(0x221631),
  primary = hex(0xB58CFF),
  secondary = hex(0x9D8FC7),
  tertiary = hex(0xFF8FD1),
  textPrimary = hex(0xF2EAFB),
  textSecondary = hex(0xA795C0),
  border = hex(0x382650),
  success = hex(0x4ADE80),
  warning = hex(0xFBBF24),
  // Coral rather than rose: this theme's highlight accent is already pink, and an
  // error the same colour as the special-effect accent helps nobody.
  error = hex(0xFF6B6B)
)

val SailNight = UiPalette(
  key = "sail_night",
  name = "Sail Night",
  blurb = "Dark pink and red",
  dark = true,
  background = hex(0x150810),
  surface = hex(0x1F0D18),
  surfaceContainer = hex(0x2B1322),
  primary = hex(0xF26D9B),
  secondary = hex(0xC08FA6),
  // A cool sky for the highlight: warm amber would read as the same colour as the
  // warning, on a page that is already pink, red and amber.
  tertiary = hex(0x6FD3FF),
  textPrimary = hex(0xFAEAF0),
  textSecondary = hex(0xC095A6),
  border = hex(0x47213A),
  success = hex(0x4ADE80),
  warning = hex(0xFBBF24),
  // A red that leans orange: close enough to read as "wrong" at a glance, far enough
  // from the rose primary to tell the two apart. It also has to be bright enough to
  // stay legible as label text on this theme's own busiest surface.
  error = hex(0xFF5C4D)
)

// ---------------------------------------------------------------------------
// Editor palettes, reused as UI themes.
//
// These share their names with the editor's SyntaxTheme entries on purpose: picking
// "Tokyo Night" in the gallery makes the chrome match the code it frames, while the
// syntax theme stays an independent choice — a Tokyo Night UI with Monokai Pro code
// is a legitimate combination.
// ---------------------------------------------------------------------------

/** One Dark's slate-violet page with its azure function colour as the accent. */
val OneDark = UiPalette(
  key = "one_dark",
  name = "One Dark Pro",
  blurb = "Editor palette, as UI",
  dark = true,
  background = hex(0x1E1E2E),
  surface = hex(0x24273A),
  surfaceContainer = hex(0x282C40),
  primary = hex(0x61AFEF),
  secondary = hex(0xCB84E0),
  tertiary = hex(0x56B6C2),
  textPrimary = hex(0xE4E6F0),
  textSecondary = hex(0xA3ABC0),
  border = hex(0x3F4560),
  success = hex(0x98C379),
  warning = hex(0xE5C07B),
  error = hex(0xEA7F89)
)

/** Monokai Pro's near-black grey with the yellow accent and its magenta as error. */
val MonokaiPro = UiPalette(
  key = "monokai_pro",
  name = "Monokai Pro",
  blurb = "Editor palette, as UI",
  dark = true,
  background = hex(0x19181A),
  surface = hex(0x221F22),
  surfaceContainer = hex(0x2D2A2E),
  primary = hex(0xFFD866),
  secondary = hex(0xAB9DF2),
  tertiary = hex(0x78DCE8),
  textPrimary = hex(0xFCFCFA),
  textSecondary = hex(0xBDBABF),
  border = hex(0x4A474C),
  success = hex(0xA9DC76),
  warning = hex(0xFC9867),
  error = hex(0xFF6F93)
)

/** Tokyo Night's indigo page; blue accent, lilac secondary, aqua highlight. */
val TokyoNight = UiPalette(
  key = "tokyo_night",
  name = "Tokyo Night",
  blurb = "Editor palette, as UI",
  dark = true,
  background = hex(0x1A1B26),
  surface = hex(0x24283B),
  surfaceContainer = hex(0x292E42),
  primary = hex(0x7AA2F7),
  secondary = hex(0xBB9AF7),
  tertiary = hex(0x2AC3DE),
  textPrimary = hex(0xC0CAF5),
  textSecondary = hex(0xA7B2CF),
  border = hex(0x3B4261),
  success = hex(0x9ECE6A),
  warning = hex(0xE0AF68),
  error = hex(0xF88096)
)

/** GitHub's dimmed canvas: the blue link colour up front, green for a job well done. */
val GitHubDark = UiPalette(
  key = "github_dark",
  name = "GitHub Dark",
  blurb = "Editor palette, as UI",
  dark = true,
  background = hex(0x0D1117),
  surface = hex(0x161B22),
  surfaceContainer = hex(0x21262D),
  primary = hex(0x58A6FF),
  secondary = hex(0xBC8CFF),
  tertiary = hex(0xF778BA),
  textPrimary = hex(0xE6EDF3),
  textSecondary = hex(0x9CA6B2),
  border = hex(0x30363D),
  success = hex(0x3FB950),
  warning = hex(0xD29922),
  error = hex(0xF96D66)
)

// ---------------------------------------------------------------------------
// Light
// ---------------------------------------------------------------------------

/** The plain one: white cards on a cool grey page, royal blue for the action. */
val Daylight = UiPalette(
  key = "daylight",
  name = "Daylight",
  blurb = "Clean light",
  dark = false,
  background = hex(0xF6F8FC),
  surface = hex(0xFFFFFF),
  surfaceContainer = hex(0xEBF0F8),
  primary = hex(0x1D4ED8),
  secondary = hex(0x475B85),
  tertiary = hex(0x5B47D6),
  textPrimary = hex(0x0F172A),
  textSecondary = hex(0x475569),
  border = hex(0xC3CDDF),
  // A shade darker than the #047857 it started on: success doubles as label text on
  // this theme's busiest row surface, where the lighter green landed on 4.49:1.
  success = hex(0x047057),
  warning = hex(0xA84D09),
  error = hex(0xC81E1E)
)

/** Daylight with more blue in the paper: porcelain, not printer stock. */
val Porcelain = UiPalette(
  key = "porcelain",
  name = "Porcelain",
  blurb = "Soft blue-white",
  dark = false,
  background = hex(0xEEF3FA),
  surface = hex(0xFAFCFF),
  surfaceContainer = hex(0xDFE7F3),
  primary = hex(0x1D4ED8),
  secondary = hex(0x44618A),
  // Plum, not the indigo a blue page would pull you toward: it has to stay
  // distinguishable from the royal primary it sits next to.
  tertiary = hex(0x7C2F86),
  textPrimary = hex(0x101A2C),
  textSecondary = hex(0x42546E),
  border = hex(0xBACBE4),
  success = hex(0x0C6D55),
  warning = hex(0x8C5006),
  error = hex(0xA93834)
)

/** Warm cream and terracotta, for people who dislike blue at 2am. */
val Sandstone = UiPalette(
  key = "sandstone",
  name = "Sandstone",
  blurb = "Warm cream",
  dark = false,
  background = hex(0xFAF5EB),
  surface = hex(0xFFFCF5),
  surfaceContainer = hex(0xEDE2CF),
  primary = hex(0x874A19),
  secondary = hex(0x645848),
  tertiary = hex(0x266456),
  textPrimary = hex(0x241B10),
  // This theme's ladder runs cream to tan, the widest of the light set, so its
  // secondary text starts darker than the others to stay readable on the busiest rung.
  textSecondary = hex(0x544C3D),
  border = hex(0xD9C9AC),
  success = hex(0x3D5A17),
  warning = hex(0x6B5A00),
  error = hex(0xA3322B)
)

/** Pale green with a jade accent. */
val Mint = UiPalette(
  key = "mint",
  name = "Mint",
  blurb = "Pale green",
  dark = false,
  background = hex(0xEEF7F1),
  surface = hex(0xFAFEFB),
  surfaceContainer = hex(0xD9EBDF),
  // A green page already owns the colour "success" lives on, so the action accent
  // takes the teal side of it and the highlight goes violet.
  primary = hex(0x0F5F73),
  secondary = hex(0x3B6754),
  tertiary = hex(0x6B3FA8),
  textPrimary = hex(0x0D2018),
  textSecondary = hex(0x3D584A),
  border = hex(0xB4D2BE),
  success = hex(0x126D34),
  warning = hex(0x8C5006),
  error = hex(0xAB2F4C)
)

/** Sail Night turned inside out: blush paper, claret action, sky highlight. */
val SailDay = UiPalette(
  key = "sail_day",
  name = "Sail Day",
  blurb = "Warm light",
  dark = false,
  background = hex(0xFFF5F1),
  surface = hex(0xFFFBF9),
  surfaceContainer = hex(0xFFE1D6),
  primary = hex(0xB12254),
  secondary = hex(0x7C5160),
  // The night half of this pair highlights in sky for the same reason: rust on a
  // page this warm is the warning's colour, not a highlight's.
  tertiary = hex(0x175F86),
  textPrimary = hex(0x2B1119),
  textSecondary = hex(0x6A4550),
  border = hex(0xF0C3B4),
  success = hex(0x0C6C3E),
  warning = hex(0x974608),
  error = hex(0xB32525)
)

// ---------------------------------------------------------------------------
// Registry
// ---------------------------------------------------------------------------

/** Order is the gallery's order: dark themes first, Nocturne ahead of everything. */
val uiThemes: List<UiPalette> = listOf(
  Nocturne,
  Midnight,
  Graphite,
  Abyss,
  Evergreen,
  Ember,
  Grape,
  SailNight,
  OneDark,
  MonokaiPro,
  TokyoNight,
  GitHubDark,
  Daylight,
  Porcelain,
  Sandstone,
  Mint,
  SailDay
)

/** The theme every fresh install and every test host starts from. */
val DefaultUiTheme: UiPalette = Nocturne

/**
 * Unknown keys fall back to the default instead of throwing: the key comes from a
 * file a user can't edit but a downgrade can still leave holding a newer value.
 */
fun uiThemeByKey(key: String): UiPalette =
  uiThemes.firstOrNull { it.key == key } ?: DefaultUiTheme
