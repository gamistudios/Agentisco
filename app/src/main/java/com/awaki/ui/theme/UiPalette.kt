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
// Favourite colours, night cuts.
//
// Twenty palettes the user picked by eye, each in two cuts. The page is tinted from the
// palette's own secondary hue, and every accent was walked — hue kept, lightness moved —
// until it reads as label text on the page, the surface, the container and a border-tinted
// high rung, so the derivation has nothing left to correct.
// ---------------------------------------------------------------------------

/** Turquoise Sunset, night cut. */
val TurquoiseSunsetNight = UiPalette(
  key = "turquoise_sunset_night",
  name = "Turquoise Sunset Night",
  blurb = "Dark · Turquoise Sunset",
  dark = true,
  background = hex(0x060A16),
  surface = hex(0x0A1022),
  surfaceContainer = hex(0x0E1731),
  primary = hex(0x40E0D0),
  secondary = hex(0x8094CF),
  tertiary = hex(0xFF6B00),
  textPrimary = hex(0xEAEDF5),
  textSecondary = hex(0xA8B0C7),
  border = hex(0x213263),
  success = hex(0x84CC16),
  warning = hex(0xEAB308),
  error = hex(0xEC6C88)
)

/** Royal Berry, night cut. */
val RoyalBerryNight = UiPalette(
  key = "royal_berry_night",
  name = "Royal Berry Night",
  blurb = "Dark · Royal Berry",
  dark = true,
  background = hex(0x160710),
  surface = hex(0x210A18),
  surfaceContainer = hex(0x310F23),
  primary = hex(0xA48EDA),
  secondary = hex(0xCA84AE),
  tertiary = hex(0xFF9A76),
  textPrimary = hex(0xF5EAF1),
  textSecondary = hex(0xC7A8BB),
  border = hex(0x622249),
  success = hex(0x4ADE80),
  warning = hex(0xEAB308),
  error = hex(0xFB7185)
)

/** Forest Bloom, night cut. */
val ForestBloomNight = UiPalette(
  key = "forest_bloom_night",
  name = "Forest Bloom Night",
  blurb = "Dark · Forest Bloom",
  dark = true,
  background = hex(0x0E0814),
  surface = hex(0x150C1F),
  surfaceContainer = hex(0x1F122E),
  primary = hex(0x32A874),
  secondary = hex(0xA48AC3),
  tertiary = hex(0xF2B84B),
  textPrimary = hex(0xEFEAF5),
  textSecondary = hex(0xB6A8C7),
  border = hex(0x40295C),
  success = hex(0x84CC16),
  warning = hex(0xF97316),
  error = hex(0xEC6A86)
)

/** Cobalt Lemon, night cut. */
val CobaltLemonNight = UiPalette(
  key = "cobalt_lemon_night",
  name = "Cobalt Lemon Night",
  blurb = "Dark · Cobalt Lemon",
  dark = true,
  background = hex(0x060A16),
  surface = hex(0x0A1022),
  surfaceContainer = hex(0x0E1731),
  primary = hex(0x7193E7),
  secondary = hex(0x7F94D1),
  tertiary = hex(0xF4D44D),
  textPrimary = hex(0xEAEDF5),
  textSecondary = hex(0xA8B0C7),
  border = hex(0x213263),
  success = hex(0x4ADE80),
  warning = hex(0xF97316),
  error = hex(0xEC6C88)
)

/** Rosewood, night cut. */
val RosewoodNight = UiPalette(
  key = "rosewood_night",
  name = "Rosewood Night",
  blurb = "Dark · Rosewood",
  dark = true,
  background = hex(0x15070B),
  surface = hex(0x200B11),
  surfaceContainer = hex(0x2F101A),
  primary = hex(0xCB8596),
  secondary = hex(0xC8879B),
  tertiary = hex(0xE7B873),
  textPrimary = hex(0xF5EAEE),
  textSecondary = hex(0xC7A8B1),
  border = hex(0x602537),
  success = hex(0x14B8A6),
  warning = hex(0xF97316),
  error = hex(0xFF6759)
)

/** Coral Lagoon, night cut. */
val CoralLagoonNight = UiPalette(
  key = "coral_lagoon_night",
  name = "Coral Lagoon Night",
  blurb = "Dark · Coral Lagoon",
  dark = true,
  background = hex(0x061615),
  surface = hex(0x0A2220),
  surfaceContainer = hex(0x0E312F),
  primary = hex(0xF6A49D),
  secondary = hex(0x2ACDC4),
  tertiary = hex(0xFFD166),
  textPrimary = hex(0xEAF5F4),
  textSecondary = hex(0xAAC9C7),
  border = hex(0x21635F),
  success = hex(0x6CCD7A),
  warning = hex(0xFBA468),
  error = hex(0xF3A2B4)
)

/** Indigo Apricot, night cut. */
val IndigoApricotNight = UiPalette(
  key = "indigo_apricot_night",
  name = "Indigo Apricot Night",
  blurb = "Dark · Indigo Apricot",
  dark = true,
  background = hex(0x080914),
  surface = hex(0x0C0E1F),
  surfaceContainer = hex(0x11142E),
  primary = hex(0x868FCC),
  secondary = hex(0x8A90C5),
  tertiary = hex(0xFFAD69),
  textPrimary = hex(0xEAEBF5),
  textSecondary = hex(0xA8ABC7),
  border = hex(0x272D5D),
  success = hex(0x3FB950),
  warning = hex(0xEAB308),
  error = hex(0xEB6683)
)

/** Meadow Plum, night cut. */
val MeadowPlumNight = UiPalette(
  key = "meadow_plum_night",
  name = "Meadow Plum Night",
  blurb = "Dark · Meadow Plum",
  dark = true,
  background = hex(0x110A12),
  surface = hex(0x1A0F1C),
  surfaceContainer = hex(0x27162A),
  primary = hex(0x67AB51),
  secondary = hex(0xB38FB8),
  tertiary = hex(0xF1D27A),
  textPrimary = hex(0xF4EAF5),
  textSecondary = hex(0xC3A8C7),
  border = hex(0x4F3054),
  success = hex(0x14B8A6),
  warning = hex(0xF97418),
  error = hex(0xED758F)
)

/** Sky Cherry, night cut. */
val SkyCherryNight = UiPalette(
  key = "sky_cherry_night",
  name = "Sky Cherry Night",
  blurb = "Dark · Sky Cherry",
  dark = true,
  background = hex(0x070B15),
  surface = hex(0x0B1121),
  surfaceContainer = hex(0x101930),
  primary = hex(0x4A9FD8),
  secondary = hex(0x8396C7),
  tertiary = hex(0xE27686),
  textPrimary = hex(0xEAEDF5),
  textSecondary = hex(0xA8B0C7),
  border = hex(0x243560),
  success = hex(0x84CC16),
  warning = hex(0xEAB308),
  error = hex(0xFF6355)
)

/** Midnight Orchid, night cut. */
val MidnightOrchidNight = UiPalette(
  key = "midnight_orchid_night",
  name = "Midnight Orchid Night",
  blurb = "Dark · Midnight Orchid",
  dark = true,
  background = hex(0x070815),
  surface = hex(0x0B0D21),
  surfaceContainer = hex(0x101330),
  primary = hex(0xA480D4),
  secondary = hex(0x878CC9),
  tertiary = hex(0xEFA4D0),
  textPrimary = hex(0xEAEBF5),
  textSecondary = hex(0xA8ABC7),
  border = hex(0x242960),
  success = hex(0x84CC16),
  warning = hex(0xEAB308),
  error = hex(0xFF5C4D)
)

/** Citrus Ink, night cut. */
val CitrusInkNight = UiPalette(
  key = "citrus_ink_night",
  name = "Citrus Ink Night",
  blurb = "Dark · Citrus Ink",
  dark = true,
  background = hex(0x0A0D12),
  surface = hex(0x0F151C),
  surfaceContainer = hex(0x161E29),
  primary = hex(0xE5A51C),
  secondary = hex(0x8D9EB7),
  tertiary = hex(0x5BBF8A),
  textPrimary = hex(0xEAEFF5),
  textSecondary = hex(0xA8B5C7),
  border = hex(0x313F54),
  success = hex(0x84CC16),
  warning = hex(0xF9781E),
  error = hex(0xEE7892)
)

/** Ocean Terracotta, night cut. */
val OceanTerracottaNight = UiPalette(
  key = "ocean_terracotta_night",
  name = "Ocean Terracotta Night",
  blurb = "Dark · Ocean Terracotta",
  dark = true,
  background = hex(0x081114),
  surface = hex(0x0C1B20),
  surfaceContainer = hex(0x11272F),
  primary = hex(0x3AB9C3),
  secondary = hex(0x7FB0C1),
  tertiary = hex(0xE1967F),
  textPrimary = hex(0xEAF2F5),
  textSecondary = hex(0xA8BFC7),
  border = hex(0x27505E),
  success = hex(0x84CC16),
  warning = hex(0xEAB308),
  error = hex(0xF08CA2)
)

/** Lilac Moss, night cut. */
val LilacMossNight = UiPalette(
  key = "lilac_moss_night",
  name = "Lilac Moss Night",
  blurb = "Dark · Lilac Moss",
  dark = true,
  background = hex(0x0E120A),
  surface = hex(0x161C10),
  surfaceContainer = hex(0x202917),
  primary = hex(0xB7A4D9),
  secondary = hex(0xA3B193),
  tertiary = hex(0xE99A72),
  textPrimary = hex(0xF0F5EA),
  textSecondary = hex(0xB8C7A8),
  border = hex(0x435332),
  success = hex(0x15C1AE),
  warning = hex(0xEAB308),
  error = hex(0xF190A5)
)

/** Peach Navy, night cut. */
val PeachNavyNight = UiPalette(
  key = "peach_navy_night",
  name = "Peach Navy Night",
  blurb = "Dark · Peach Navy",
  dark = true,
  background = hex(0x070C15),
  surface = hex(0x0B1220),
  surfaceContainer = hex(0x101B30),
  primary = hex(0xEF8D72),
  secondary = hex(0x839AC6),
  tertiary = hex(0x8BC7B5),
  textPrimary = hex(0xEAEEF5),
  textSecondary = hex(0xA8B3C7),
  border = hex(0x253960),
  success = hex(0x84CC16),
  warning = hex(0xEAB308),
  error = hex(0xED738E)
)

/** Pistachio Ink, night cut. */
val PistachioInkNight = UiPalette(
  key = "pistachio_ink_night",
  name = "Pistachio Ink Night",
  blurb = "Dark · Pistachio Ink",
  dark = true,
  background = hex(0x0F1408),
  surface = hex(0x171F0C),
  surfaceContainer = hex(0x222E12),
  primary = hex(0x97C161),
  secondary = hex(0xACB7B1),
  tertiary = hex(0xE6A0BE),
  textPrimary = hex(0xF0F5EA),
  textSecondary = hex(0xBAC7A8),
  border = hex(0x465C28),
  success = hex(0x16CAB7),
  warning = hex(0xFB9E5E),
  error = hex(0xFF988E)
)

/** Electric Plum, night cut. */
val ElectricPlumNight = UiPalette(
  key = "electric_plum_night",
  name = "Electric Plum Night",
  blurb = "Dark · Electric Plum",
  dark = true,
  background = hex(0x0B0716),
  surface = hex(0x110A21),
  surfaceContainer = hex(0x180F31),
  primary = hex(0xBE73D7),
  secondary = hex(0x9A86CC),
  tertiary = hex(0x62D6C3),
  textPrimary = hex(0xEDEAF5),
  textSecondary = hex(0xB1A8C7),
  border = hex(0x342262),
  success = hex(0x84CC16),
  warning = hex(0xEAB308),
  error = hex(0xFF5C4D)
)

/** Clay Sky, night cut. */
val ClaySkyNight = UiPalette(
  key = "clay_sky_night",
  name = "Clay Sky Night",
  blurb = "Dark · Clay Sky",
  dark = true,
  background = hex(0x090E13),
  surface = hex(0x0E161E),
  surfaceContainer = hex(0x14212C),
  primary = hex(0xD49075),
  secondary = hex(0x87A4BC),
  tertiary = hex(0xE7C45B),
  textPrimary = hex(0xEAF0F5),
  textSecondary = hex(0xA8B9C7),
  border = hex(0x2C4459),
  success = hex(0x3FB950),
  warning = hex(0xF97E28),
  error = hex(0xEE7E97)
)

/** Jade Papaya, night cut. */
val JadePapayaNight = UiPalette(
  key = "jade_papaya_night",
  name = "Jade Papaya Night",
  blurb = "Dark · Jade Papaya",
  dark = true,
  background = hex(0x090F13),
  surface = hex(0x0E171D),
  surfaceContainer = hex(0x15222B),
  primary = hex(0x1BB68E),
  secondary = hex(0x8AA6B9),
  tertiary = hex(0xF39A3D),
  textPrimary = hex(0xEAF1F5),
  textSecondary = hex(0xA8BAC7),
  border = hex(0x2E4657),
  success = hex(0x84CC16),
  warning = hex(0xEAB308),
  error = hex(0xEF8098)
)

/** Denim Peony, night cut. */
val DenimPeonyNight = UiPalette(
  key = "denim_peony_night",
  name = "Denim Peony Night",
  blurb = "Dark · Denim Peony",
  dark = true,
  background = hex(0x100A12),
  surface = hex(0x18101C),
  surfaceContainer = hex(0x241729),
  primary = hex(0x749ECD),
  secondary = hex(0xAC90B7),
  tertiary = hex(0xE77A8D),
  textPrimary = hex(0xF2EAF5),
  textSecondary = hex(0xBEA8C7),
  border = hex(0x493153),
  success = hex(0x84CC16),
  warning = hex(0xEAB308),
  error = hex(0xFF695B)
)

/** Butterfly Blue, night cut. */
val ButterflyBlueNight = UiPalette(
  key = "butterfly_blue_night",
  name = "Butterfly Blue Night",
  blurb = "Dark · Butterfly Blue",
  dark = true,
  background = hex(0x0D0913),
  surface = hex(0x140D1E),
  surfaceContainer = hex(0x1D142C),
  primary = hex(0x5D98E1),
  secondary = hex(0xA08CC0),
  tertiary = hex(0xF2A65A),
  textPrimary = hex(0xEEEAF5),
  textSecondary = hex(0xB4A8C7),
  border = hex(0x3D2B59),
  success = hex(0x3FB950),
  warning = hex(0xEAB308),
  error = hex(0xEC6A86)
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
// Favourite colours, day cuts — the same twenty palettes on light paper. The
// accents here are the darkened halves of the night cuts: a bright turquoise or lemon
// cannot reach AA as text on cream, so the hue stays and the lightness drops.
// ---------------------------------------------------------------------------

/** Turquoise Sunset, day cut. */
val TurquoiseSunset = UiPalette(
  key = "turquoise_sunset",
  name = "Turquoise Sunset",
  blurb = "Light · Turquoise Sunset",
  dark = false,
  background = hex(0xFFF8EF),
  surface = hex(0xFFFDFA),
  surfaceContainer = hex(0xD1F5EF),
  primary = hex(0x116B62),
  secondary = hex(0x3157C7),
  tertiary = hex(0xA14400),
  textPrimary = hex(0x16213A),
  textSecondary = hex(0x495062),
  border = hex(0xBFE8E2),
  success = hex(0x3D5A17),
  warning = hex(0x6B5A00),
  error = hex(0xAB2F4C)
)

/** Royal Berry, day cut. */
val RoyalBerry = UiPalette(
  key = "royal_berry",
  name = "Royal Berry",
  blurb = "Light · Royal Berry",
  dark = false,
  background = hex(0xFFF7FB),
  surface = hex(0xFFFDFE),
  surfaceContainer = hex(0xF1EAFF),
  primary = hex(0x6740C2),
  secondary = hex(0x9E3072),
  tertiary = hex(0xAB2D00),
  textPrimary = hex(0x21172F),
  textSecondary = hex(0x52485C),
  border = hex(0xD9C9F4),
  success = hex(0x116932),
  warning = hex(0x6B5A00),
  error = hex(0xA62E4A)
)

/** Forest Bloom, day cut. */
val ForestBloom = UiPalette(
  key = "forest_bloom",
  name = "Forest Bloom",
  blurb = "Light · Forest Bloom",
  dark = false,
  background = hex(0xFFFCF4),
  surface = hex(0xFFFEFC),
  surfaceContainer = hex(0xE4F5EB),
  primary = hex(0x206C4B),
  secondary = hex(0x7847B2),
  tertiary = hex(0x825809),
  textPrimary = hex(0x172A20),
  textSecondary = hex(0x4A584F),
  border = hex(0xC6E2D1),
  success = hex(0x3D5A17),
  warning = hex(0x9C4808),
  error = hex(0xAB2F4C)
)

/** Cobalt Lemon, day cut. */
val CobaltLemon = UiPalette(
  key = "cobalt_lemon",
  name = "Cobalt Lemon",
  blurb = "Light · Cobalt Lemon",
  dark = false,
  background = hex(0xFFFDF1),
  surface = hex(0xFFFEFB),
  surfaceContainer = hex(0xEAF1FF),
  primary = hex(0x2354CF),
  secondary = hex(0x172B65),
  tertiary = hex(0x705C07),
  textPrimary = hex(0x101B35),
  textSecondary = hex(0x454D5E),
  border = hex(0xC7D5F5),
  success = hex(0x126D34),
  warning = hex(0x994608),
  error = hex(0xAB2F4C)
)

/** Rosewood, day cut. */
val Rosewood = UiPalette(
  key = "rosewood",
  name = "Rosewood",
  blurb = "Light · Rosewood",
  dark = false,
  background = hex(0xFFF8EF),
  surface = hex(0xFFFDFA),
  surfaceContainer = hex(0xF9E9ED),
  primary = hex(0x964055),
  secondary = hex(0x6F263C),
  tertiary = hex(0x7D5315),
  textPrimary = hex(0x301822),
  textSecondary = hex(0x5E494F),
  border = hex(0xE9CBD2),
  success = hex(0x046851),
  warning = hex(0x6B5A00),
  error = hex(0xA3322B)
)

/** Coral Lagoon, day cut. */
val CoralLagoon = UiPalette(
  key = "coral_lagoon",
  name = "Coral Lagoon",
  blurb = "Light · Coral Lagoon",
  dark = false,
  background = hex(0xF3FCFA),
  surface = hex(0xFBFEFE),
  surfaceContainer = hex(0xFFEAE5),
  primary = hex(0xB51F11),
  secondary = hex(0x076862),
  tertiary = hex(0x7A5600),
  textPrimary = hex(0x19302F),
  textSecondary = hex(0x465958),
  border = hex(0xF3CBC5),
  success = hex(0x3D5A17),
  warning = hex(0x974508),
  error = hex(0xA92F4B)
)

/** Indigo Apricot, day cut. */
val IndigoApricot = UiPalette(
  key = "indigo_apricot",
  name = "Indigo Apricot",
  blurb = "Light · Indigo Apricot",
  dark = false,
  background = hex(0xFFF8F1),
  surface = hex(0xFFFDFB),
  surfaceContainer = hex(0xE9ECFF),
  primary = hex(0x4653A8),
  secondary = hex(0x252B62),
  tertiary = hex(0x964400),
  textPrimary = hex(0x171A35),
  textSecondary = hex(0x4A4B5E),
  border = hex(0xCED2F0),
  success = hex(0x116932),
  warning = hex(0x6B5A00),
  error = hex(0xA82E4B)
)

/** Meadow Plum, day cut. */
val MeadowPlum = UiPalette(
  key = "meadow_plum",
  name = "Meadow Plum",
  blurb = "Light · Meadow Plum",
  dark = false,
  background = hex(0xFFFBF0),
  surface = hex(0xFFFEFB),
  surfaceContainer = hex(0xE7F4E0),
  primary = hex(0x3F6932),
  secondary = hex(0x693C70),
  tertiary = hex(0x765B0C),
  textPrimary = hex(0x20251D),
  textSecondary = hex(0x51544B),
  border = hex(0xCFDFC5),
  success = hex(0x046C54),
  warning = hex(0x9A4708),
  error = hex(0xAB2F4C)
)

/** Sky Cherry, day cut. */
val SkyCherry = UiPalette(
  key = "sky_cherry",
  name = "Sky Cherry",
  blurb = "Light · Sky Cherry",
  dark = false,
  background = hex(0xFFF7F8),
  surface = hex(0xFFFDFD),
  surfaceContainer = hex(0xDDF1FF),
  primary = hex(0x206492),
  secondary = hex(0x263B73),
  tertiary = hex(0xB5263B),
  textPrimary = hex(0x17243A),
  textSecondary = hex(0x4A5264),
  border = hex(0xC7E1F2),
  success = hex(0x3D5A17),
  warning = hex(0x6B5A00),
  error = hex(0xA3322B)
)

/** Midnight Orchid, day cut. */
val MidnightOrchid = UiPalette(
  key = "midnight_orchid",
  name = "Midnight Orchid",
  blurb = "Light · Midnight Orchid",
  dark = false,
  background = hex(0xFAF8FF),
  surface = hex(0xFEFDFF),
  surfaceContainer = hex(0xF2EBFB),
  primary = hex(0x733FB7),
  secondary = hex(0x171B46),
  tertiary = hex(0xA91E70),
  textPrimary = hex(0x15152B),
  textSecondary = hex(0x47475A),
  border = hex(0xD9CBEB),
  success = hex(0x3D5A17),
  warning = hex(0x6B5A00),
  error = hex(0xA3322B)
)

/** Citrus Ink, day cut. */
val CitrusInk = UiPalette(
  key = "citrus_ink",
  name = "Citrus Ink",
  blurb = "Light · Citrus Ink",
  dark = false,
  background = hex(0xF8FAFC),
  surface = hex(0xFDFEFE),
  surfaceContainer = hex(0xFFEFB4),
  primary = hex(0x7C590E),
  secondary = hex(0x28364A),
  tertiary = hex(0x2A6B49),
  textPrimary = hex(0x17202D),
  textSecondary = hex(0x48505B),
  border = hex(0xE7DDAF),
  success = hex(0x3D5A17),
  warning = hex(0x9C4808),
  error = hex(0xAB2F4C)
)

/** Ocean Terracotta, day cut. */
val OceanTerracotta = UiPalette(
  key = "ocean_terracotta",
  name = "Ocean Terracotta",
  blurb = "Light · Ocean Terracotta",
  dark = false,
  background = hex(0xFFF7F2),
  surface = hex(0xFFFDFB),
  surfaceContainer = hex(0xDEF2F0),
  primary = hex(0x20676D),
  secondary = hex(0x214B59),
  tertiary = hex(0x9F4125),
  textPrimary = hex(0x17282C),
  textSecondary = hex(0x4A5658),
  border = hex(0xC5DEDE),
  success = hex(0x3D5A17),
  warning = hex(0x6B5A00),
  error = hex(0xAB2F4C)
)

/** Lilac Moss, day cut. */
val LilacMoss = UiPalette(
  key = "lilac_moss",
  name = "Lilac Moss",
  blurb = "Light · Lilac Moss",
  dark = false,
  background = hex(0xFAF8F1),
  surface = hex(0xFEFDFB),
  surfaceContainer = hex(0xF0EBFA),
  primary = hex(0x6D49AE),
  secondary = hex(0x52623F),
  tertiary = hex(0x9A4418),
  textPrimary = hex(0x25222D),
  textSecondary = hex(0x545158),
  border = hex(0xDCD3E9),
  success = hex(0x046A52),
  warning = hex(0x6B5A00),
  error = hex(0xA92F4B)
)

/** Peach Navy, day cut. */
val PeachNavy = UiPalette(
  key = "peach_navy",
  name = "Peach Navy",
  blurb = "Light · Peach Navy",
  dark = false,
  background = hex(0xF5F8FC),
  surface = hex(0xFCFDFE),
  surfaceContainer = hex(0xFFE7DD),
  primary = hex(0xA93313),
  secondary = hex(0x233B68),
  tertiary = hex(0x326757),
  textPrimary = hex(0x17243A),
  textSecondary = hex(0x485365),
  border = hex(0xF0D0C7),
  success = hex(0x3D5A17),
  warning = hex(0x6B5A00),
  error = hex(0xA92F4B)
)

/** Pistachio Ink, day cut. */
val PistachioInk = UiPalette(
  key = "pistachio_ink",
  name = "Pistachio Ink",
  blurb = "Light · Pistachio Ink",
  dark = false,
  background = hex(0xFBFAF5),
  surface = hex(0xFEFEFC),
  surfaceContainer = hex(0xE8F3D6),
  primary = hex(0x4C6829),
  secondary = hex(0x303A35),
  tertiary = hex(0xAE2D63),
  textPrimary = hex(0x202720),
  textSecondary = hex(0x50554F),
  border = hex(0xD5E2C2),
  success = hex(0x046C54),
  warning = hex(0x8C5006),
  error = hex(0xA3322B)
)

/** Electric Plum, day cut. */
val ElectricPlum = UiPalette(
  key = "electric_plum",
  name = "Electric Plum",
  blurb = "Light · Electric Plum",
  dark = false,
  background = hex(0xF8F7FF),
  surface = hex(0xFDFDFF),
  surfaceContainer = hex(0xF6E7FB),
  primary = hex(0x8A30A8),
  secondary = hex(0x35206A),
  tertiary = hex(0x1B6659),
  textPrimary = hex(0x21142B),
  textSecondary = hex(0x50465A),
  border = hex(0xE0C7E8),
  success = hex(0x3D5A17),
  warning = hex(0x6B5A00),
  error = hex(0xA3322B)
)

/** Clay Sky, day cut. */
val ClaySky = UiPalette(
  key = "clay_sky",
  name = "Clay Sky",
  blurb = "Light · Clay Sky",
  dark = false,
  background = hex(0xF3F8FC),
  surface = hex(0xFBFDFE),
  surfaceContainer = hex(0xF7EAE2),
  primary = hex(0x90492D),
  secondary = hex(0x3A6181),
  tertiary = hex(0x725911),
  textPrimary = hex(0x2A211E),
  textSecondary = hex(0x56504F),
  border = hex(0xE5D1C7),
  success = hex(0x126B33),
  warning = hex(0x884E06),
  error = hex(0xA82E4B)
)

/** Jade Papaya, day cut. */
val JadePapaya = UiPalette(
  key = "jade_papaya",
  name = "Jade Papaya",
  blurb = "Light · Jade Papaya",
  dark = false,
  background = hex(0xFFF8EC),
  surface = hex(0xFFFDF9),
  surfaceContainer = hex(0xD9F4E9),
  primary = hex(0x106C54),
  secondary = hex(0x385A72),
  tertiary = hex(0x904E09),
  textPrimary = hex(0x172824),
  textSecondary = hex(0x4A5650),
  border = hex(0xBFE2D5),
  success = hex(0x3D5A17),
  warning = hex(0x6B5A00),
  error = hex(0xAB2F4C)
)

/** Denim Peony, day cut. */
val DenimPeony = UiPalette(
  key = "denim_peony",
  name = "Denim Peony",
  blurb = "Light · Denim Peony",
  dark = false,
  background = hex(0xFCF8FA),
  surface = hex(0xFEFDFE),
  surfaceContainer = hex(0xE4EFFB),
  primary = hex(0x356193),
  secondary = hex(0x754B86),
  tertiary = hex(0xB6213B),
  textPrimary = hex(0x1C2431),
  textSecondary = hex(0x4D535D),
  border = hex(0xCCDCEC),
  success = hex(0x3D5A17),
  warning = hex(0x6B5A00),
  error = hex(0xA3322B)
)

/** Butterfly Blue, day cut. */
val ButterflyBlue = UiPalette(
  key = "butterfly_blue",
  name = "Butterfly Blue",
  blurb = "Light · Butterfly Blue",
  dark = false,
  background = hex(0xFBF8FF),
  surface = hex(0xFEFDFF),
  surfaceContainer = hex(0xE3EFFF),
  primary = hex(0x205FAD),
  secondary = hex(0x714CAB),
  tertiary = hex(0x914E0B),
  textPrimary = hex(0x1B2034),
  textSecondary = hex(0x4C5061),
  border = hex(0xD0DDF2),
  success = hex(0x126D34),
  warning = hex(0x6B5A00),
  error = hex(0xAB2F4C)
)

// ---------------------------------------------------------------------------
// Registry
// ---------------------------------------------------------------------------

/**
 * Order is the gallery's order: the house darks, the editor palettes worn as UI, the
 * favourites' night cuts, then the light set and the favourites' day cuts. Nocturne
 * leads, because it is what a fresh install gets.
 */
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
  TurquoiseSunsetNight,
  RoyalBerryNight,
  ForestBloomNight,
  CobaltLemonNight,
  RosewoodNight,
  CoralLagoonNight,
  IndigoApricotNight,
  MeadowPlumNight,
  SkyCherryNight,
  MidnightOrchidNight,
  CitrusInkNight,
  OceanTerracottaNight,
  LilacMossNight,
  PeachNavyNight,
  PistachioInkNight,
  ElectricPlumNight,
  ClaySkyNight,
  JadePapayaNight,
  DenimPeonyNight,
  ButterflyBlueNight,
  Daylight,
  Porcelain,
  Sandstone,
  Mint,
  SailDay,
  TurquoiseSunset,
  RoyalBerry,
  ForestBloom,
  CobaltLemon,
  Rosewood,
  CoralLagoon,
  IndigoApricot,
  MeadowPlum,
  SkyCherry,
  MidnightOrchid,
  CitrusInk,
  OceanTerracotta,
  LilacMoss,
  PeachNavy,
  PistachioInk,
  ElectricPlum,
  ClaySky,
  JadePapaya,
  DenimPeony,
  ButterflyBlue
)

/** The theme every fresh install and every test host starts from. */
val DefaultUiTheme: UiPalette = Nocturne

/**
 * Unknown keys fall back to the default instead of throwing: the key comes from a
 * file a user can't edit but a downgrade can still leave holding a newer value.
 */
fun uiThemeByKey(key: String): UiPalette =
  uiThemes.firstOrNull { it.key == key } ?: DefaultUiTheme
