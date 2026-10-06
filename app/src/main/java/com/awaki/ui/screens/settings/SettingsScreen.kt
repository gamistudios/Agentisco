package com.awaki.ui.screens.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.DataArray
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LineStyle
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SpaceBar
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.automirrored.outlined.WrapText
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.awaki.BuildConfig
import com.awaki.agent.model.UNLIMITED_ITERATIONS
import com.awaki.background.RequirementStatus
import com.awaki.core.model.AppDestination
import com.awaki.editor.model.EditorSettings
import com.awaki.ui.UpdateViewModel
import com.awaki.ui.WorkspaceViewModel
import com.awaki.ui.theme.AwakiTheme

/**
 * Awaki's configuration, in one scrollable list rather than fourteen cards.
 *
 * Every setting is a row, every row is described once by a [SettingsItem], and the
 * same list drives the sections, the quick-access shortcuts and the search results —
 * so a setting cannot be reachable by scrolling and missing from search. Anything
 * that is more than one control opens a sheet instead of growing the page.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
  viewModel: WorkspaceViewModel,
  updateViewModel: UpdateViewModel,
  onNavigate: (AppDestination) -> Unit,
  onShowCrashLog: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  var query by remember { mutableStateOf("") }
  var sheet by remember { mutableStateOf<SettingsSheet?>(null) }

  val items = SettingsCatalog(
    viewModel = viewModel,
    updateViewModel = updateViewModel,
    openSheet = { sheet = it },
    onNavigate = onNavigate,
    onShowCrashLog = onShowCrashLog
  )
  val results = filterSettings(items, query)

  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .padding(horizontal = 14.dp)
      .testTag("settings_list"),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    stickyHeader {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(MaterialTheme.colorScheme.background)
          .padding(top = 10.dp, bottom = 12.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(
            onClick = { onNavigate(AppDestination.AGENT) },
            modifier = Modifier.size(32.dp).testTag("btn_settings_back")
          ) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "Back", tint = AwakiTheme.extra.textMuted)
          }
          Spacer(modifier = Modifier.width(6.dp))
          Column {
            Text("Settings", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Every switch, value and list Awaki keeps", color = AwakiTheme.extra.textMuted, fontSize = 11.sp)
          }
        }
        Spacer(modifier = Modifier.height(10.dp))
        SettingsSearchField(query = query, onQueryChange = { query = it })
      }
    }

    if (query.isBlank()) {
      SettingsGroup.values().forEach { group ->
        val inGroup = items.filter { it.group == group }
        if (inGroup.isNotEmpty()) {
          item(key = "section_${group.name}") { SettingsSection(group, inGroup) }
        }
      }
    } else {
      if (results.isEmpty()) {
        item(key = "no_results") { SettingsNoResults(query) }
      } else {
        groupSettings(results).forEach { (group, matched) ->
          item(key = "found_${group.name}") { SettingsSection(group, matched) }
        }
      }
    }

    item(key = "bottom_space") { Spacer(modifier = Modifier.height(24.dp)) }
  }

  SettingsSheetHost(
    sheet = sheet,
    onDismiss = { sheet = null },
    viewModel = viewModel,
    updateViewModel = updateViewModel,
    onNavigate = { destination ->
      sheet = null
      onNavigate(destination)
    }
  )
}

/**
 * The whole screen as data: every row, in section order, with its live value.
 *
 * This is a composable rather than a pure function because the values are state —
 * a row reads the model that is selected, the folders that are skipped and whether
 * the battery exemption was granted, and each of those is a flow the screen has to
 * observe.
 */
@Composable
private fun SettingsCatalog(
  viewModel: WorkspaceViewModel,
  updateViewModel: UpdateViewModel,
  openSheet: (SettingsSheet) -> Unit,
  onNavigate: (AppDestination) -> Unit,
  onShowCrashLog: () -> Unit
): List<SettingsItem> {
  val permissions by viewModel.permissions.collectAsState()
  val selectedModel by viewModel.selectedModel.collectAsState()
  val providers by viewModel.providers.collectAsState()
  val localModels by viewModel.localModels.collectAsState()
  val compact by viewModel.compactSettings.collectAsState()
  val webAccess by viewModel.webAccess.collectAsState()
  val skippedDirs by viewModel.effectiveIgnoredDirs.collectAsState()
  val chatDisplay by viewModel.chatDisplay.collectAsState()
  val editor by viewModel.editorSettings.collectAsState()
  val allowed by viewModel.allowBackgroundExecution.collectAsState()
  val wakeLock by viewModel.backgroundWakeLockEnabled.collectAsState()
  val terminalHold by viewModel.terminalHeld.collectAsState()
  val requirements by viewModel.backgroundRequirements.collectAsState()
  val interrupted by viewModel.interruptedBackgroundWork.collectAsState()
  val alerts by viewModel.workAlerts.collectAsState()
  val customAgents by viewModel.customAgents.collectAsState()
  val skills = remember(viewModel) { viewModel.skills() }
  val autoUpdate by updateViewModel.uiState.collectAsState()

  val installedLocal = localModels.count { it.installed }
  val needsAction = requirements.count {
    it.status == RequirementStatus.ACTION_REQUIRED && it.blocking
  }
  val enabledTools = listOf(
    permissions.readFiles, permissions.createFiles, permissions.modifyFiles, permissions.deleteFiles,
    permissions.runCommands, permissions.installPackages, permissions.networkCommands,
    permissions.gitStatus, permissions.gitDiff, permissions.gitCommit, permissions.gitPush,
    permissions.alwaysAskDangerous
  ).count { it }
  val providerName = selectedModel?.providerId?.let { id -> providers.firstOrNull { it.id == id }?.name }

  fun setEditor(transform: (EditorSettings) -> EditorSettings) = viewModel.updateEditorSettings(transform(editor))

  return buildList {
    // ---- Quick access ----
    add(
      SettingsItem(
        id = "quick_model",
        group = SettingsGroup.QuickAccess,
        title = "AI model",
        icon = Icons.Outlined.AutoAwesome,
        detail = providerName ?: "No provider selected yet",
        end = RowEnd.Value(selectedModel?.displayName ?: "None", ValueTone.Accent, "txt_quick_model"),
        onClick = { openSheet(SettingsSheet.ModelPicker) }
      )
    )
    add(
      SettingsItem(
        id = "quick_background",
        group = SettingsGroup.QuickAccess,
        title = "Run while backgrounded",
        icon = Icons.Outlined.Bolt,
        detail = "Keep work running after you leave",
        end = RowEnd.Switch(allowed, viewModel::setAllowBackgroundExecution)
      )
    )
    add(
      SettingsItem(
        id = "quick_permissions",
        group = SettingsGroup.QuickAccess,
        title = "Tool permissions",
        icon = Icons.AutoMirrored.Outlined.Rule,
        detail = "What the agent may use at all",
        end = RowEnd.Value("$enabledTools of 12", ValueTone.Accent, "txt_quick_tools"),
        onClick = { openSheet(SettingsSheet.ToolPermissions) }
      )
    )
    add(
      SettingsItem(
        id = "quick_theme",
        group = SettingsGroup.QuickAccess,
        title = "Syntax theme",
        icon = Icons.Outlined.ColorLens,
        end = RowEnd.Value(editor.syntaxThemeName, ValueTone.Accent, "txt_quick_theme"),
        onClick = { openSheet(SettingsSheet.SyntaxTheme) }
      )
    )

    // ---- AI & Agent ----
    add(
      SettingsItem(
        id = "model",
        group = SettingsGroup.AiAgent,
        title = "Model",
        icon = Icons.Outlined.AutoAwesome,
        detail = "What a new turn starts with",
        keywords = listOf("chat", "llm", "select", "switch"),
        end = RowEnd.Value(selectedModel?.displayName ?: "None", ValueTone.Accent, "txt_model"),
        onClick = { openSheet(SettingsSheet.ModelPicker) }
      )
    )
    add(
      SettingsItem(
        id = "providers",
        group = SettingsGroup.AiAgent,
        title = "Providers",
        icon = Icons.Outlined.Api,
        detail = "Connections, keys and their models",
        keywords = listOf("openai", "anthropic", "gemini", "base url", "api key"),
        end = RowEnd.Value("${providers.size} configured", ValueTone.Neutral, "txt_providers"),
        onClick = { onNavigate(AppDestination.AI_PROVIDERS) }
      )
    )
    add(
      SettingsItem(
        id = "local_models",
        group = SettingsGroup.AiAgent,
        title = "Local models",
        icon = Icons.Outlined.Memory,
        detail = "Models that run on this device",
        keywords = listOf("gguf", "offline", "on device", "llama"),
        end = RowEnd.Value(
          if (installedLocal == 0) "None installed" else "$installedLocal installed",
          if (installedLocal == 0) ValueTone.Warning else ValueTone.Neutral,
          "txt_local_models"
        ),
        onClick = { onNavigate(AppDestination.LOCAL_MODELS) }
      )
    )
    add(
      SettingsItem(
        id = "plan_mode",
        group = SettingsGroup.AiAgent,
        title = "Planning mode",
        icon = Icons.Outlined.AccountTree,
        detail = "Research first, change nothing",
        keywords = listOf("plan", "read only", "proposal"),
        end = RowEnd.Switch(permissions.planMode, { on ->
          viewModel.updatePermissions { it.copy(planMode = on) }
        }, "switch_plan_mode")
      )
    )
    add(
      SettingsItem(
        id = "compaction",
        group = SettingsGroup.AiAgent,
        title = "Context & compaction",
        icon = Icons.Outlined.Calculate,
        detail = "When a long conversation gets summarized",
        keywords = listOf("tokens", "window", "threshold", "summarize", "compress"),
        end = RowEnd.Value("at ${compact.thresholdPercent}%", ValueTone.Accent, "txt_compaction"),
        onClick = { openSheet(SettingsSheet.Compaction) }
      )
    )
    add(
      SettingsItem(
        id = "agent_team",
        group = SettingsGroup.AiAgent,
        title = "Agent team",
        icon = Icons.Outlined.Groups,
        detail = "Specialists the agent hands work to",
        keywords = listOf("delegate", "subagent", "role", "expert"),
        end = RowEnd.Value("${customAgents.size} custom", ValueTone.Neutral, "txt_agent_team"),
        onClick = { openSheet(SettingsSheet.AgentTeam) }
      )
    )
    add(
      SettingsItem(
        id = "skills",
        group = SettingsGroup.AiAgent,
        title = "Skills",
        icon = Icons.Outlined.School,
        detail = "House rules the agent reads first",
        keywords = listOf("instructions", "document", "project rules"),
        end = RowEnd.Value("${skills.size} found", ValueTone.Neutral, "txt_skills"),
        onClick = { openSheet(SettingsSheet.Skills) }
      )
    )

    // ---- Tools ----
    add(
      SettingsItem(
        id = "file_editing",
        group = SettingsGroup.Tools,
        title = "File editing",
        icon = Icons.Outlined.Security,
        detail = "Whether a write waits for you",
        keywords = listOf("permission", "approve", "modify", "patch"),
        end = RowEnd.Value(modeLabel(permissions.fileEditing), ValueTone.Accent, "txt_file_editing"),
        onClick = { openSheet(SettingsSheet.FileEditing) }
      )
    )
    add(
      SettingsItem(
        id = "terminal_safety",
        group = SettingsGroup.Tools,
        title = "Terminal safety",
        icon = Icons.Outlined.Terminal,
        detail = "Which commands run unattended",
        keywords = listOf("permission", "shell", "bash", "approve", "dangerous"),
        end = RowEnd.Value(
          modeLabel(permissions.terminalCommands),
          if (permissions.terminalCommands == com.awaki.agent.model.PermissionMode.ALLOW_ALL) ValueTone.Danger
          else ValueTone.Accent,
          "txt_terminal_safety"
        ),
        onClick = { openSheet(SettingsSheet.TerminalSafety) }
      )
    )
    add(
      SettingsItem(
        id = "tool_permissions",
        group = SettingsGroup.Tools,
        title = "Tool permissions",
        icon = Icons.AutoMirrored.Outlined.Rule,
        detail = "Read, write, git, packages — one list",
        keywords = listOf("permission", "allow", "git push", "delete", "install"),
        end = RowEnd.Value("$enabledTools of 12", ValueTone.Accent, "txt_tool_permissions"),
        onClick = { openSheet(SettingsSheet.ToolPermissions) }
      )
    )
    add(
      SettingsItem(
        id = "network_access",
        group = SettingsGroup.Tools,
        title = "Network access",
        icon = Icons.Outlined.Public,
        detail = "Let the agent fetch docs and packages",
        keywords = listOf("internet", "offline", "fetch", "web"),
        end = RowEnd.Switch(permissions.networkAccess, { on ->
          viewModel.updatePermissions { it.copy(networkAccess = on) }
        }, "switch_network_access")
      )
    )
    add(
      SettingsItem(
        id = "tool_iterations",
        group = SettingsGroup.Tools,
        title = "Tool loop limit",
        icon = Icons.Outlined.Refresh,
        detail = "Tool calls allowed in one turn",
        keywords = listOf("iterations", "budget", "unlimited", "autonomous"),
        end = RowEnd.Stepper(
          text = if (permissions.maxToolIterations == UNLIMITED_ITERATIONS) "∞" else "${permissions.maxToolIterations}",
          onDecrease = {
            viewModel.updatePermissions {
              val v = it.maxToolIterations
              it.copy(maxToolIterations = if (v == UNLIMITED_ITERATIONS) 100 else (v - 5).coerceAtLeast(1))
            }
          },
          onIncrease = {
            viewModel.updatePermissions {
              val v = it.maxToolIterations
              it.copy(
                maxToolIterations = when {
                  v == UNLIMITED_ITERATIONS -> v
                  v >= 100 -> UNLIMITED_ITERATIONS
                  else -> v + 5
                }
              )
            }
          }
        )
      )
    )
    add(
      SettingsItem(
        id = "web_access",
        group = SettingsGroup.Tools,
        title = "Web access",
        icon = Icons.Outlined.Language,
        detail = "Which tier answers web tools",
        keywords = listOf("jina", "reader", "search", "web_fetch", "web_search", "scrape", "duckduckgo", "key"),
        end = RowEnd.Value(
          if (webAccess.preferJina) "Jina.ai first" else "Direct",
          ValueTone.Accent,
          "txt_web_access"
        ),
        onClick = { openSheet(SettingsSheet.WebAccess) }
      )
    )
    add(
      SettingsItem(
        id = "skipped_folders",
        group = SettingsGroup.Tools,
        title = "Skipped folders",
        icon = Icons.Outlined.FolderOff,
        detail = "What tree scans, search and the agent ignore",
        keywords = listOf("node modules", "build", "scan", "ignore", "performance", "huge project"),
        end = RowEnd.Value("${skippedDirs.size}", ValueTone.Neutral, "txt_skipped_count"),
        onClick = { openSheet(SettingsSheet.SkippedFolders) }
      )
    )

    // ---- Editor ----
    add(
      SettingsItem(
        id = "editor_font_size",
        group = SettingsGroup.Editor,
        title = "Font size",
        icon = Icons.Outlined.FormatSize,
        keywords = listOf("text", "zoom", "code size"),
        end = RowEnd.Stepper(
          text = "${editor.fontSize} sp",
          onDecrease = { setEditor { it.copy(fontSize = (it.fontSize - 1).coerceAtLeast(10)) } },
          onIncrease = { setEditor { it.copy(fontSize = (it.fontSize + 1).coerceAtMost(24)) } },
          canDecrease = editor.fontSize > 10,
          canIncrease = editor.fontSize < 24
        )
      )
    )
    add(
      SettingsItem(
        id = "editor_line_height",
        group = SettingsGroup.Editor,
        title = "Line height",
        icon = Icons.Outlined.LineStyle,
        keywords = listOf("leading", "spacing", "rows"),
        end = RowEnd.Stepper(
          text = "×${String.format(java.util.Locale.US, "%.2f", editor.lineHeightMultiplier)}",
          onDecrease = { setEditor { it.copy(lineHeightMultiplier = (it.lineHeightMultiplier - 0.05f).coerceAtLeast(1.0f)) } },
          onIncrease = { setEditor { it.copy(lineHeightMultiplier = (it.lineHeightMultiplier + 0.05f).coerceAtMost(2.0f)) } },
          canDecrease = editor.lineHeightMultiplier > 1.0f,
          canIncrease = editor.lineHeightMultiplier < 2.0f
        )
      )
    )
    add(
      SettingsItem(
        id = "editor_tab_size",
        group = SettingsGroup.Editor,
        title = "Tab size",
        icon = Icons.Outlined.Tab,
        keywords = listOf("indent", "spaces", "columns"),
        end = RowEnd.Stepper(
          text = "${editor.tabSize}",
          onDecrease = { setEditor { it.copy(tabSize = when (it.tabSize) { 4 -> 2; 8 -> 4; else -> 2 }) } },
          onIncrease = { setEditor { it.copy(tabSize = when (it.tabSize) { 2 -> 4; 4 -> 8; else -> 8 }) } }
        )
      )
    )
    add(
      SettingsItem(
        id = "editor_use_spaces",
        group = SettingsGroup.Editor,
        title = "Indent with spaces",
        icon = Icons.Outlined.SpaceBar,
        detail = "Off inserts a tab character instead",
        keywords = listOf("tab", "indent"),
        end = RowEnd.Switch(editor.useSpaces, { on -> setEditor { it.copy(useSpaces = on) } })
      )
    )
    add(
      SettingsItem(
        id = "editor_word_wrap",
        group = SettingsGroup.Editor,
        title = "Word wrap",
        icon = Icons.AutoMirrored.Outlined.WrapText,
        detail = "Wrap long lines instead of scrolling",
        keywords = listOf("line", "soft wrap"),
        end = RowEnd.Switch(editor.wordWrap, { on -> setEditor { it.copy(wordWrap = on) } })
      )
    )
    add(
      SettingsItem(
        id = "editor_auto_save",
        group = SettingsGroup.Editor,
        title = "Auto-save",
        icon = Icons.Outlined.Save,
        detail = "Write to disk as you type, after a pause",
        keywords = listOf("save", "persist"),
        end = RowEnd.Switch(editor.autoSave, { on -> setEditor { it.copy(autoSave = on) } })
      )
    )
    add(
      SettingsItem(
        id = "editor_format_on_save",
        group = SettingsGroup.Editor,
        title = "Format on save",
        icon = Icons.Outlined.AutoFixHigh,
        detail = "Format the file when it is written",
        keywords = listOf("prettier", "gofmt", "black", "formatter"),
        end = RowEnd.Switch(editor.formatOnSave, { on -> setEditor { it.copy(formatOnSave = on) } })
      )
    )
    add(
      SettingsItem(
        id = "editor_bracket_matching",
        group = SettingsGroup.Editor,
        title = "Bracket matching",
        icon = Icons.Outlined.DataArray,
        detail = "Highlight the pair around the cursor",
        keywords = listOf("brace", "paren"),
        end = RowEnd.Switch(editor.bracketMatching, { on -> setEditor { it.copy(bracketMatching = on) } })
      )
    )
    add(
      SettingsItem(
        id = "editor_code_folding",
        group = SettingsGroup.Editor,
        title = "Code folding",
        icon = Icons.Outlined.UnfoldMore,
        detail = "Collapse a block to one line",
        keywords = listOf("fold", "collapse"),
        end = RowEnd.Switch(editor.codeFolding, { on -> setEditor { it.copy(codeFolding = on) } })
      )
    )
    add(
      SettingsItem(
        id = "editor_touch_shortcuts",
        group = SettingsGroup.Editor,
        title = "Shortcut keybar",
        icon = Icons.Outlined.TouchApp,
        detail = "Extra keys above the keyboard",
        keywords = listOf("ctrl", "escape", "dev keys"),
        end = RowEnd.Switch(editor.touchShortcutsExpanded, { on -> setEditor { it.copy(touchShortcutsExpanded = on) } })
      )
    )

    // ---- Appearance ----
    add(
      SettingsItem(
        id = "syntax_theme",
        group = SettingsGroup.Appearance,
        title = "Syntax theme",
        icon = Icons.Outlined.ColorLens,
        detail = "How code is painted in the editor",
        keywords = listOf("colours", "colors", "one dark", "monokai", "tokyo", "github"),
        end = RowEnd.Value(editor.syntaxThemeName, ValueTone.Accent, "txt_syntax_theme"),
        onClick = { openSheet(SettingsSheet.SyntaxTheme) }
      )
    )
    add(
      SettingsItem(
        id = "editor_line_numbers",
        group = SettingsGroup.Appearance,
        title = "Show line numbers",
        icon = Icons.Outlined.FormatListNumbered,
        keywords = listOf("gutter"),
        end = RowEnd.Switch(editor.showLineNumbers, { on -> setEditor { it.copy(showLineNumbers = on) } })
      )
    )
    add(
      SettingsItem(
        id = "editor_active_line",
        group = SettingsGroup.Appearance,
        title = "Highlight the current line",
        icon = Icons.Outlined.LineStyle,
        keywords = listOf("caret", "cursor"),
        end = RowEnd.Switch(editor.highlightActiveLine, { on -> setEditor { it.copy(highlightActiveLine = on) } })
      )
    )
    add(
      SettingsItem(
        id = "editor_minimap",
        group = SettingsGroup.Appearance,
        title = "Show minimap",
        icon = Icons.Outlined.Map,
        detail = "The overview strip down the right edge",
        keywords = listOf("outline", "scrollbar"),
        end = RowEnd.Switch(editor.showMinimap, { on -> setEditor { it.copy(showMinimap = on) } })
      )
    )
    add(
      SettingsItem(
        id = "show_tool_json",
        group = SettingsGroup.Appearance,
        title = "Show raw JSON in tool cards",
        icon = Icons.Outlined.DataArray,
        detail = if (chatDisplay.showToolJson) "Expanded cards also show the request sent"
        else "Structured view only: command, diff, output",
        keywords = listOf("request", "debug", "tool activity"),
        end = RowEnd.Switch(chatDisplay.showToolJson, viewModel::setChatToolJsonVisible, "switch_show_tool_json")
      )
    )
    add(
      SettingsItem(
        id = "show_context_usage",
        group = SettingsGroup.Appearance,
        title = "Show context percentage",
        icon = Icons.Outlined.Science,
        detail = "Live share of the context window",
        keywords = listOf("tokens", "usage", "meter"),
        end = RowEnd.Switch(compact.showContextUsage, viewModel::setContextUsageVisible, "switch_show_context_usage")
      )
    )

    // ---- Execution ----
    add(
      SettingsItem(
        id = "background_execution",
        group = SettingsGroup.Execution,
        title = "Run while backgrounded",
        icon = Icons.Outlined.Bolt,
        detail = "A foreground service while work is live",
        keywords = listOf("minimize", "leave the app", "ongoing notification"),
        end = RowEnd.Switch(allowed, viewModel::setAllowBackgroundExecution, "switch_background_execution")
      )
    )
    add(
      SettingsItem(
        id = "background_wakelock",
        group = SettingsGroup.Execution,
        title = "Keep the CPU awake",
        icon = Icons.Outlined.BatterySaver,
        detail = "For a turn or a build, never a download",
        keywords = listOf("wake lock", "battery", "sleep", "idle"),
        end = RowEnd.Switch(wakeLock, viewModel::setBackgroundWakeLockEnabled, "switch_background_wakelock")
      )
    )
    add(
      SettingsItem(
        id = "terminal_hold",
        group = SettingsGroup.Execution,
        title = "Keep the terminal awake",
        icon = Icons.Outlined.Timer,
        detail = "Keep a shell you started by hand alive",
        keywords = listOf("wake lock", "server", "interactive"),
        end = RowEnd.Switch(terminalHold, viewModel::setTerminalHeld, "switch_terminal_hold")
      )
    )
    add(
      SettingsItem(
        id = "background_checks",
        group = SettingsGroup.Execution,
        title = "Battery & permissions",
        icon = Icons.Outlined.GppGood,
        detail = "Battery and notification checks",
        keywords = listOf("optimization", "ignore battery", "permission", "granted", "exemption"),
        end = RowEnd.Value(
          if (needsAction == 0) "All granted" else "$needsAction to fix",
          if (needsAction == 0) ValueTone.Neutral else ValueTone.Warning,
          "txt_background_checks"
        ),
        onClick = { openSheet(SettingsSheet.BackgroundChecks) }
      )
    )
    if (interrupted.isNotEmpty()) {
      add(
        SettingsItem(
          id = "interrupted_work",
          group = SettingsGroup.Execution,
          title = "Work lost with the last process",
          icon = Icons.Outlined.History,
          detail = "Nothing restarts on its own",
          keywords = listOf("interrupted", "killed", "crash", "journal"),
          end = RowEnd.Value("${interrupted.size}", ValueTone.Warning, "txt_interrupted_count"),
          onClick = { openSheet(SettingsSheet.BackgroundChecks) }
        )
      )
    }
    add(
      SettingsItem(
        id = "alert_approval",
        group = SettingsGroup.Execution,
        title = "Alert: approval requested",
        icon = Icons.Outlined.Notifications,
        detail = "Ping when a turn waits for your answer",
        keywords = listOf("notification", "attention", "sound"),
        end = RowEnd.Switch(alerts.approvalRequested, viewModel::setAlertApprovalRequested, "switch_alert_approval")
      )
    )
    add(
      SettingsItem(
        id = "alert_interrupted",
        group = SettingsGroup.Execution,
        title = "Alert: work was interrupted",
        icon = Icons.Outlined.Notifications,
        detail = "Ping once after a restart about lost work",
        keywords = listOf("notification", "recovery", "lost"),
        end = RowEnd.Switch(alerts.interruptedWork, viewModel::setAlertInterruptedWork, "switch_alert_interrupted")
      )
    )
    add(
      SettingsItem(
        id = "alert_update",
        group = SettingsGroup.Execution,
        title = "Alert: update ready",
        icon = Icons.Outlined.Notifications,
        detail = "Ping when a download finishes off screen",
        keywords = listOf("notification", "install", "apk"),
        end = RowEnd.Switch(alerts.updateReady, viewModel::setAlertUpdateReady, "switch_alert_update")
      )
    )

    // ---- Advanced ----
    add(
      SettingsItem(
        id = "updates",
        group = SettingsGroup.Advanced,
        title = "App updates",
        icon = Icons.Outlined.Update,
        detail = if (autoUpdate.autoUpdateEnabled) "Checking on launch and daily" else "Only when you ask",
        keywords = listOf("version", "apk", "new release", "download"),
        end = RowEnd.Value("v${BuildConfig.VERSION_NAME}", ValueTone.Neutral, "txt_version"),
        onClick = { openSheet(SettingsSheet.Updates) }
      )
    )
    if (BuildConfig.DEBUG) {
      add(
        SettingsItem(
          id = "crash_log",
          group = SettingsGroup.Advanced,
          title = "Last crash log",
          icon = Icons.Outlined.BugReport,
          detail = "The trace captured on the previous run",
          keywords = listOf("debug", "diagnostics", "stack trace", "export"),
          onClick = onShowCrashLog
        )
      )
    }

    // ---- About ----
    add(
      SettingsItem(
        id = "about",
        group = SettingsGroup.About,
        title = "About Awaki",
        icon = Icons.Outlined.Info,
        detail = "What runs on the device and what leaves it",
        keywords = listOf("developer", "email", "telegram", "contact", "privacy"),
        onClick = { openSheet(SettingsSheet.About) }
      )
    )
  }
}

