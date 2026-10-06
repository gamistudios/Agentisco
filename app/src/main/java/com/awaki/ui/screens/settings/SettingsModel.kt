package com.awaki.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.ui.graphics.vector.ImageVector
import com.awaki.agent.model.PermissionMode

/**
 * The groups the settings are folded into, in the order they appear.
 *
 * Order is the argument for the split: what a user sets once and forgets (Advanced,
 * About) sits below what they tune every session (AI & Agent, Tools), and Quick
 * access holds the handful they actually reach for.
 */
enum class SettingsGroup(val label: String, val icon: ImageVector) {
  QuickAccess("Quick access", Icons.Outlined.Bolt),
  AiAgent("AI & Agent", Icons.Outlined.Psychology),
  Tools("Tools", Icons.Outlined.Build),
  Editor("Editor", Icons.Outlined.Tab),
  Appearance("Appearance", Icons.Outlined.Palette),
  Execution("Execution", Icons.Outlined.Memory),
  Advanced("Advanced", Icons.Outlined.Settings),
  About("About", Icons.Outlined.Info)
}

/** Every detail screen that opens from a row, one per bottom sheet. */
enum class SettingsSheet {
  ModelPicker,
  ToolPermissions,
  FileEditing,
  TerminalSafety,
  Compaction,
  WebAccess,
  SkippedFolders,
  BackgroundChecks,
  AgentTeam,
  Skills,
  UiTheme,
  SyntaxTheme,
  Updates,
  About
}

/** How a trailing value is coloured: a setting in a state worth noticing. */
enum class ValueTone { Neutral, Accent, Warning, Danger }

/**
 * What sits at the end of a row. Four shapes cover every setting the app has: a
 * switch for on/off, a value plus chevron for something configured on a detail
 * screen, a stepper for a small number, and a bare chevron for a doorway.
 */
sealed interface RowEnd {
  object Chevron : RowEnd

  /** The row is one choice among several and this is the one taken. */
  object Check : RowEnd

  data class Switch(
    val checked: Boolean,
    val onToggle: (Boolean) -> Unit,
    /** Where a test finds this control; a row without one is addressed by its id. */
    val tag: String? = null
  ) : RowEnd

  data class Value(
    val text: String,
    val tone: ValueTone = ValueTone.Accent,
    val tag: String? = null
  ) : RowEnd

  data class Stepper(
    val text: String,
    val onDecrease: () -> Unit,
    val onIncrease: () -> Unit,
    val canDecrease: Boolean = true,
    val canIncrease: Boolean = true,
    val tag: String? = null
  ) : RowEnd
}

/**
 * One row on the settings screen.
 *
 * The row carries its own search text because search is the only place that has to
 * know about every setting at once; building the list from live state means a
 * result is the real control, not a link to it, and the index cannot drift from the
 * screen it describes.
 */
data class SettingsItem(
  val id: String,
  val group: SettingsGroup,
  val title: String,
  val icon: ImageVector,
  val detail: String = "",
  val keywords: List<String> = emptyList(),
  val end: RowEnd = RowEnd.Chevron,
  val onClick: (() -> Unit)? = null
) {
  /** The text a query is matched against: what is on screen plus the words a user
   *  would type for it, which is rarely the label ("battery" for the wake lock). */
  val searchText: String
    get() = buildString {
      append(title.lowercase())
      append(' ')
      append(detail.lowercase())
      append(' ')
      append(group.label.lowercase())
      keywords.forEach { append(' '); append(it.lowercase()) }
    }
}

/**
 * The rows matching [query], as a flat list grouped only by their section headers.
 *
 * Quick access is left out: its rows are duplicates of rows that live in a section,
 * so matching them too would list the same setting twice under two headings.
 * Every whitespace-separated word must match, so "context window" finds the
 * compaction row and not merely every row mentioning context.
 */
fun filterSettings(items: List<SettingsItem>, query: String): List<SettingsItem> {
  val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
  if (tokens.isEmpty()) return emptyList()
  return items.filter { item ->
    item.group != SettingsGroup.QuickAccess && tokens.all { item.searchText.contains(it) }
  }
}

/** The sections a filtered list touches, in screen order, each with its matches. */
fun groupSettings(items: List<SettingsItem>): List<Pair<SettingsGroup, List<SettingsItem>>> =
  SettingsGroup.values().mapNotNull { group ->
    val inGroup = items.filter { it.group == group }
    if (inGroup.isEmpty()) null else group to inGroup
  }

/** A permission mode as the two words a row has room for. */
internal fun modeLabel(mode: PermissionMode): String = when (mode) {
  PermissionMode.ALWAYS_ASK -> "Ask first"
  PermissionMode.AUTO_APPROVE_PROJECT -> "Auto in project"
  PermissionMode.ALLOW_SAFE -> "Safe only"
  PermissionMode.ALLOW_ALL -> "Allow all"
  PermissionMode.NEVER_ALLOW -> "Never"
}
