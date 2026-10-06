package com.awaki

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.ui.graphics.vector.ImageVector
import com.awaki.agent.model.PermissionMode
import com.awaki.ui.screens.settings.RowEnd
import com.awaki.ui.screens.settings.SettingsGroup
import com.awaki.ui.screens.settings.SettingsItem
import com.awaki.ui.screens.settings.filterSettings
import com.awaki.ui.screens.settings.groupSettings
import com.awaki.ui.screens.settings.modeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The search index and the screen are the same list, so what has to hold here is that
 * the list filters the way a user expects: every word must match, a section's own
 * words count, the quick-access shortcuts do not produce duplicate hits, and a query
 * with no match is reported rather than shown as an empty page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsSearchTest {

  private fun item(
    id: String,
    group: SettingsGroup,
    title: String,
    detail: String = "",
    keywords: List<String> = emptyList()
  ) = SettingsItem(
    id = id,
    group = group,
    title = title,
    icon = iconFor(group),
    detail = detail,
    keywords = keywords,
    end = RowEnd.Switch(false, {}),
    onClick = {}
  )

  private fun iconFor(group: SettingsGroup): ImageVector = when (group) {
    SettingsGroup.Execution -> Icons.Outlined.BatterySaver
    SettingsGroup.QuickAccess -> Icons.Outlined.Bolt
    else -> Icons.AutoMirrored.Outlined.Rule
  }

  private val catalog = listOf(
    item("quick_background", SettingsGroup.QuickAccess, "Run while backgrounded", "Keep work running"),
    item("model", SettingsGroup.AiAgent, "Model", "The model a new turn starts with", listOf("chat", "llm")),
    item(
      "compaction",
      SettingsGroup.AiAgent,
      "Context & compaction",
      "When a long conversation gets summarized",
      listOf("tokens", "window", "threshold")
    ),
    item(
      "background_wakelock",
      SettingsGroup.Execution,
      "Keep the CPU awake",
      "For a turn, a build, apt",
      listOf("wake lock", "battery", "sleep")
    ),
    item(
      "interrupted_work",
      SettingsGroup.Execution,
      "Work lost with the last process",
      "Nothing restarts on its own",
      listOf("crash", "killed")
    ),
    item(
      "background_execution",
      SettingsGroup.Execution,
      "Background execution",
      "Keep turns and builds running while backgrounded",
      listOf("process")
    ),
    item(
      "skipped_folders",
      SettingsGroup.Tools,
      "Skipped folders",
      "What tree scans, search and the agent ignore",
      listOf("node modules", "ignore", "scan")
    )
  )

  @Test
  fun `a label, a keyword and the section name all find the row`() {
    assertEquals(listOf("model"), filterSettings(catalog, "Model").map { it.id })
    assertEquals(listOf("background_wakelock"), filterSettings(catalog, "battery").map { it.id })
    // Nothing on screen says "execution" except the header, and a user searching by
    // the heading they remember should still get the rows under it.
    assertTrue(filterSettings(catalog, "execution").map { it.id }.containsAll(listOf("background_wakelock", "interrupted_work")))
  }

  @Test
  fun `every word must match, so a phrase narrows instead of scattering`() {
    assertEquals(listOf("compaction"), filterSettings(catalog, "context window").map { it.id })
    assertTrue("both words are present but not in one row", filterSettings(catalog, "battery window").isEmpty())
  }

  @Test
  fun `matching ignores case and survives the extra spaces a paste brings`() {
    assertEquals(filterSettings(catalog, "model"), filterSettings(catalog, "  MODEL  "))
    assertEquals(filterSettings(catalog, "model"), filterSettings(catalog, "model   "))
  }

  @Test
  fun `quick access shortcuts never answer a search twice`() {
    // The shortcut copies the wording of a row that lives in a section, so a search
    // that matched both would offer the same setting under two headings.
    val hits = filterSettings(catalog, "backgrounded")
    assertEquals(listOf("background_execution"), hits.map { it.id })
  }

  @Test
  fun `an empty or whitespace query is not a search`() {
    assertTrue(filterSettings(catalog, "").isEmpty())
    assertTrue(filterSettings(catalog, "   ").isEmpty())
  }

  @Test
  fun `hits are folded back into their sections, in screen order`() {
    val hits = filterSettings(catalog, "the")
    assertEquals(listOf("model", "background_wakelock", "interrupted_work", "skipped_folders"), hits.map { it.id })
    val sections = groupSettings(hits).map { it.first }
    assertEquals(listOf(SettingsGroup.AiAgent, SettingsGroup.Tools, SettingsGroup.Execution), sections)
    assertEquals(
      mapOf(
        SettingsGroup.AiAgent to listOf("model"),
        SettingsGroup.Tools to listOf("skipped_folders"),
        SettingsGroup.Execution to listOf("background_wakelock", "interrupted_work")
      ),
      groupSettings(hits).associate { (group, matched) -> group to matched.map { it.id } }
    )
  }

  @Test
  fun `a section with no hits contributes no header`() {
    val grouped = groupSettings(filterSettings(catalog, "node modules"))
    assertEquals(1, grouped.size)
    assertEquals(SettingsGroup.Tools, grouped.single().first)
    assertEquals(listOf("skipped_folders"), grouped.single().second.map { it.id })
  }

  @Test
  fun `grouping an empty result is empty rather than a list of blank headers`() {
    assertTrue(groupSettings(emptyList()).isEmpty())
  }

  @Test
  fun `a permission mode reads as the two words a row has room for`() {
    assertEquals("Ask first", modeLabel(PermissionMode.ALWAYS_ASK))
    assertEquals("Auto in project", modeLabel(PermissionMode.AUTO_APPROVE_PROJECT))
    assertEquals("Safe only", modeLabel(PermissionMode.ALLOW_SAFE))
    assertEquals("Allow all", modeLabel(PermissionMode.ALLOW_ALL))
    assertEquals("Never", modeLabel(PermissionMode.NEVER_ALLOW))
  }
}
