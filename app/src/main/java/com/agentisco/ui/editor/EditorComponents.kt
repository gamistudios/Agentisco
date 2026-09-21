package com.agentisco.ui.editor

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.agentisco.core.model.AppDestination
import com.agentisco.editor.model.EditorSettings
import com.agentisco.editor.model.EditorTab
import com.agentisco.editor.model.FindReplaceState
import com.agentisco.editor.syntax.Language
import com.agentisco.editor.syntax.SyntaxHighlightTransformation
import com.agentisco.editor.syntax.SyntaxHighlighter
import com.agentisco.editor.syntax.SyntaxTheme
import com.agentisco.ui.components.MarkdownText
import com.agentisco.ui.theme.*

/**
 * Clean, compact breadcrumbs bar showing project name, path hierarchy,
 * language indicator, and quick action to reveal in Files screen.
 */
@Composable
fun EditorBreadcrumbsBar(
  projectName: String,
  filePath: String,
  language: Language,
  isDirty: Boolean,
  fileSize: Long,
  lineCount: Int,
  charCount: Int,
  onOpenLanguageSelector: () -> Unit,
  onRevealInFiles: () -> Unit,
  modifier: Modifier = Modifier
) {
  val pathSegments = remember(filePath) {
    filePath.split("/").filter { it.isNotBlank() }
  }

  Surface(
    modifier = modifier.fillMaxWidth(),
    color = DarkSurface,
    border = BorderStroke(0.5.dp, DarkBorderSubtle)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Breadcrumbs row
      Row(
        modifier = Modifier
          .weight(1f)
          .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Files back icon
        IconButton(
          onClick = onRevealInFiles,
          modifier = Modifier.size(24.dp)
        ) {
          Icon(
            imageVector = Icons.Default.FolderOpen,
            contentDescription = "Reveal in Files",
            tint = CyanAccent,
            modifier = Modifier.size(14.dp)
          )
        }

        Spacer(modifier = Modifier.width(2.dp))

        // Project root
        Text(
          text = projectName.ifBlank { "Project" },
          color = TextMuted,
          fontSize = 11.sp,
          fontWeight = FontWeight.Medium,
          fontFamily = FontFamily.Monospace
        )

        // Path segments with separators
        for ((idx, seg) in pathSegments.withIndex()) {
          Text(
            text = " / ",
            color = DarkBorder,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
          )
          val isLast = idx == pathSegments.lastIndex
          Text(
            text = seg,
            color = if (isLast) TextPrimary else TextSecondary,
            fontSize = 11.sp,
            fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }

        if (isDirty) {
          Spacer(modifier = Modifier.width(6.dp))
          Box(
            modifier = Modifier
              .size(6.dp)
              .clip(CircleShape)
              .background(WarningAmber)
          )
        }
      }

      Spacer(modifier = Modifier.width(8.dp))

      // Right: Language badge + Stats
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        // Stats pill (lines, chars)
        Text(
          text = "${lineCount}L  ${charCount}C",
          color = TextMuted,
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace
        )

        // Language selector badge
        Box(
          modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(language.accentColor.copy(alpha = 0.15f))
            .border(1.dp, language.accentColor.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            .clickable(onClick = onOpenLanguageSelector)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag("badge_language_selector")
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
          ) {
            Box(
              modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(language.accentColor)
            )
            Text(
              text = language.displayName,
              color = language.accentColor,
              fontSize = 10.sp,
              fontWeight = FontWeight.SemiBold,
              fontFamily = FontFamily.Monospace
            )
            Icon(
              imageVector = Icons.Default.ArrowDropDown,
              contentDescription = "Change Language",
              tint = language.accentColor,
              modifier = Modifier.size(12.dp)
            )
          }
        }
      }
    }
  }
}

/**
 * Mobile-friendly multi-file Tab Bar.
 * Allows switching tabs, closing tabs, seeing dirty states, and tab overflow actions.
 */
@Composable
fun EditorTabBar(
  tabs: List<EditorTab>,
  activeTabIndex: Int,
  onSelectTab: (Int) -> Unit,
  onCloseTab: (Int) -> Unit,
  onCloseOtherTabs: (Int) -> Unit,
  onCloseAllTabs: () -> Unit,
  onReopenLastClosedTab: () -> Unit,
  hasClosedTabs: Boolean,
  modifier: Modifier = Modifier
) {
  var showTabMenu by remember { mutableStateOf(false) }

  Surface(
    modifier = modifier.fillMaxWidth(),
    color = DarkSurfaceElevated,
    border = BorderStroke(0.5.dp, DarkBorderSubtle)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Scrollable Tabs
      Row(
        modifier = Modifier
          .weight(1f)
          .horizontalScroll(rememberScrollState())
          .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        tabs.forEachIndexed { index, tab ->
          val isActive = index == activeTabIndex
          val lang = tab.effectiveLanguage

          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
              .background(if (isActive) DarkBackground else DarkSurface)
              .border(
                1.dp,
                if (isActive) ElectricBlue.copy(alpha = 0.6f) else DarkBorderSubtle,
                RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
              )
              .clickable { onSelectTab(index) }
              .padding(start = 8.dp, end = 4.dp, top = 5.dp, bottom = 5.dp)
              .testTag("editor_tab_${tab.file.name}")
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
              // Language color dot
              Box(
                modifier = Modifier
                  .size(7.dp)
                  .clip(CircleShape)
                  .background(lang.accentColor)
              )

              // File name
              Text(
                text = tab.file.name,
                color = if (isActive) TextPrimary else TextSecondary,
                fontSize = 11.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
              )

              // Unsaved dot or close icon
              if (tab.isDirty) {
                Box(
                  modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(WarningAmber)
                    .clickable { onCloseTab(index) }
                    .testTag("tab_dirty_dot_${tab.file.name}")
                )
              }

              // Close tab button
              IconButton(
                onClick = { onCloseTab(index) },
                modifier = Modifier
                  .size(18.dp)
                  .testTag("btn_close_tab_${tab.file.name}")
              ) {
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = "Close Tab",
                  tint = if (isActive) TextSecondary else TextMuted,
                  modifier = Modifier.size(11.dp)
                )
              }
            }
          }
        }
      }

      // Tab Management Overflow Menu
      Box {
        IconButton(
          onClick = { showTabMenu = true },
          modifier = Modifier
            .size(32.dp)
            .testTag("btn_tabs_overflow")
        ) {
          Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = "Tabs Menu",
            tint = TextSecondary,
            modifier = Modifier.size(16.dp)
          )
        }

        DropdownMenu(
          expanded = showTabMenu,
          onDismissRequest = { showTabMenu = false },
          modifier = Modifier.background(DarkSurface)
        ) {
          DropdownMenuItem(
            text = { Text("Close Other Tabs", color = TextPrimary, fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.ClearAll, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp)) },
            onClick = {
              showTabMenu = false
              onCloseOtherTabs(activeTabIndex)
            }
          )
          DropdownMenuItem(
            text = { Text("Close All Tabs", color = TextPrimary, fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Close, contentDescription = null, tint = DangerRed, modifier = Modifier.size(16.dp)) },
            onClick = {
              showTabMenu = false
              onCloseAllTabs()
            }
          )
          if (hasClosedTabs) {
            DropdownMenuItem(
              text = { Text("Reopen Closed Tab", color = TextPrimary, fontSize = 12.sp) },
              leadingIcon = { Icon(Icons.Default.Restore, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(16.dp)) },
              onClick = {
                showTabMenu = false
                onReopenLastClosedTab()
              }
            )
          }
        }
      }
    }
  }
}

/**
 * Compact Editor Action Bar.
 * Hosts Save, Undo, Redo, Find/Replace, Outline, Preview, and More Actions.
 */
@Composable
fun EditorActionBar(
  isDirty: Boolean,
  canUndo: Boolean,
  canRedo: Boolean,
  isFindOpen: Boolean,
  language: Language,
  isMarkdownPreviewActive: Boolean,
  onSave: () -> Unit,
  onUndo: () -> Unit,
  onRedo: () -> Unit,
  onToggleFind: () -> Unit,
  onOpenGoToLine: () -> Unit,
  onOpenGoToSymbol: () -> Unit,
  onToggleMarkdownPreview: () -> Unit,
  onOpenHtmlPreview: () -> Unit,
  onFormatJson: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenAiMenu: () -> Unit,
  onNavigateDiff: () -> Unit,
  onToggleWordWrap: () -> Unit,
  isWordWrapEnabled: Boolean,
  modifier: Modifier = Modifier
) {
  var showOverflowMenu by remember { mutableStateOf(false) }

  Surface(
    modifier = modifier.fillMaxWidth(),
    color = DarkSurface,
    border = BorderStroke(0.5.dp, DarkBorderSubtle)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Left: Save + Undo + Redo
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        // Save Button
        Button(
          onClick = onSave,
          colors = ButtonDefaults.buttonColors(
            containerColor = if (isDirty) ElectricBlue else DarkSurfaceElevated,
            contentColor = if (isDirty) Color.White else TextSecondary
          ),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
          modifier = Modifier
            .height(28.dp)
            .testTag("btn_save_file")
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Save,
              contentDescription = "Save",
              modifier = Modifier.size(12.dp)
            )
            Text(
              text = if (isDirty) "Save *" else "Saved",
              fontSize = 11.sp,
              fontWeight = FontWeight.SemiBold
            )
          }
        }

        // Undo
        IconButton(
          onClick = onUndo,
          enabled = canUndo,
          modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(DarkSurfaceElevated)
            .testTag("btn_editor_undo")
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.Undo,
            contentDescription = "Undo",
            tint = if (canUndo) ElectricBlueGlow else TextMuted,
            modifier = Modifier.size(14.dp)
          )
        }

        // Redo
        IconButton(
          onClick = onRedo,
          enabled = canRedo,
          modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(DarkSurfaceElevated)
            .testTag("btn_editor_redo")
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.Redo,
            contentDescription = "Redo",
            tint = if (canRedo) ElectricBlueGlow else TextMuted,
            modifier = Modifier.size(14.dp)
          )
        }

        // Find & Replace toggle
        IconButton(
          onClick = onToggleFind,
          modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (isFindOpen) ElectricBlue.copy(alpha = 0.25f) else DarkSurfaceElevated)
            .testTag("btn_editor_find")
        ) {
          Icon(
            imageVector = Icons.Default.Search,
            contentDescription = "Find and Replace",
            tint = if (isFindOpen) ElectricBlueGlow else TextSecondary,
            modifier = Modifier.size(14.dp)
          )
        }
      }

      // Right: Special Actions & Overflow
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        // Special file preview button
        if (language == Language.HTML) {
          OutlinedButton(
            onClick = onOpenHtmlPreview,
            modifier = Modifier.height(28.dp).testTag("btn_html_preview"),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            border = BorderStroke(1.dp, CyanAccent.copy(alpha = 0.6f))
          ) {
            Icon(Icons.Default.Language, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(13.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Preview", color = CyanAccent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
          }
        } else if (language == Language.MARKDOWN) {
          OutlinedButton(
            onClick = onToggleMarkdownPreview,
            modifier = Modifier.height(28.dp).testTag("btn_markdown_preview"),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            border = BorderStroke(1.dp, if (isMarkdownPreviewActive) ElectricBlueGlow else DarkBorder)
          ) {
            Icon(
              imageVector = if (isMarkdownPreviewActive) Icons.Outlined.Edit else Icons.Outlined.Visibility,
              contentDescription = null,
              tint = if (isMarkdownPreviewActive) ElectricBlueGlow else TextSecondary,
              modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = if (isMarkdownPreviewActive) "Edit" else "Preview",
              color = if (isMarkdownPreviewActive) ElectricBlueGlow else TextSecondary,
              fontSize = 11.sp
            )
          }
        } else if (language == Language.JSON) {
          IconButton(
            onClick = onFormatJson,
            modifier = Modifier
              .size(28.dp)
              .clip(RoundedCornerShape(4.dp))
              .background(DarkSurfaceElevated)
              .testTag("btn_format_json")
          ) {
            Icon(Icons.Default.AutoFixHigh, contentDescription = "Format JSON", tint = CyanAccent, modifier = Modifier.size(14.dp))
          }
        }

        // Symbol Outline
        IconButton(
          onClick = onOpenGoToSymbol,
          modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(DarkSurfaceElevated)
            .testTag("btn_editor_symbols")
        ) {
          Icon(
            imageVector = Icons.Default.Segment,
            contentDescription = "Go to Symbol",
            tint = TextSecondary,
            modifier = Modifier.size(14.dp)
          )
        }

        // AI Agent Quick Action
        IconButton(
          onClick = onOpenAiMenu,
          modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(ElectricBlue.copy(alpha = 0.15f))
            .border(1.dp, ElectricBlue.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .testTag("btn_editor_ai")
        ) {
          Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = "AI Assistant",
            tint = ElectricBlueGlow,
            modifier = Modifier.size(14.dp)
          )
        }

        // Diff review
        IconButton(
          onClick = onNavigateDiff,
          modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(DarkSurfaceElevated)
            .testTag("btn_editor_diff")
        ) {
          Icon(
            imageVector = Icons.Outlined.Difference,
            contentDescription = "Review Diffs",
            tint = CyanAccent,
            modifier = Modifier.size(14.dp)
          )
        }

        // Overflow menu
        Box {
          IconButton(
            onClick = { showOverflowMenu = true },
            modifier = Modifier
              .size(28.dp)
              .clip(RoundedCornerShape(4.dp))
              .background(DarkSurfaceElevated)
              .testTag("btn_editor_more_actions")
          ) {
            Icon(
              imageVector = Icons.Default.MoreHoriz,
              contentDescription = "More Actions",
              tint = TextSecondary,
              modifier = Modifier.size(14.dp)
            )
          }

          DropdownMenu(
            expanded = showOverflowMenu,
            onDismissRequest = { showOverflowMenu = false },
            modifier = Modifier.background(DarkSurface)
          ) {
            DropdownMenuItem(
              text = { Text("Go to Line...", color = TextPrimary, fontSize = 12.sp) },
              leadingIcon = { Icon(Icons.Default.Pin, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(16.dp)) },
              onClick = {
                showOverflowMenu = false
                onOpenGoToLine()
              }
            )
            DropdownMenuItem(
              text = { Text(if (isWordWrapEnabled) "Disable Word Wrap" else "Enable Word Wrap", color = TextPrimary, fontSize = 12.sp) },
              leadingIcon = { Icon(Icons.Default.WrapText, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp)) },
              onClick = {
                showOverflowMenu = false
                onToggleWordWrap()
              }
            )
            DropdownMenuItem(
              text = { Text("Editor Settings", color = TextPrimary, fontSize = 12.sp) },
              leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp)) },
              onClick = {
                showOverflowMenu = false
                onOpenSettings()
              }
            )
          }
        }
      }
    }
  }
}

/**
 * IDE-grade Find & Replace Bar embedded above the code area.
 */
@Composable
fun EditorFindReplaceBar(
  state: FindReplaceState,
  onQueryChange: (String) -> Unit,
  onReplaceQueryChange: (String) -> Unit,
  onNextMatch: () -> Unit,
  onPrevMatch: () -> Unit,
  onReplaceCurrent: () -> Unit,
  onReplaceAll: () -> Unit,
  onToggleCase: () -> Unit,
  onToggleWord: () -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier
) {
  var showReplaceRow by remember { mutableStateOf(false) }

  Surface(
    modifier = modifier.fillMaxWidth(),
    color = DarkSurfaceElevated,
    border = BorderStroke(1.dp, DarkBorder)
  ) {
    Column(modifier = Modifier.padding(6.dp)) {
      // Search Row
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        // Toggle replace toggle button
        IconButton(
          onClick = { showReplaceRow = !showReplaceRow },
          modifier = Modifier.size(24.dp)
        ) {
          Icon(
            imageVector = if (showReplaceRow) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Toggle Replace",
            tint = TextMuted,
            modifier = Modifier.size(16.dp)
          )
        }

        // Find input
        Box(
          modifier = Modifier
            .weight(1f)
            .height(30.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(DarkBackground)
            .border(1.dp, DarkBorderSubtle, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp),
          contentAlignment = Alignment.CenterStart
        ) {
          BasicTextField(
            value = state.findQuery,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(ElectricBlueGlow),
            modifier = Modifier.fillMaxWidth().testTag("input_find_query"),
            decorationBox = { innerTextField ->
              if (state.findQuery.isEmpty()) {
                Text("Find...", color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
              }
              innerTextField()
            }
          )
        }

        // Match count indicator
        Text(
          text = if (state.totalMatches > 0) "${state.currentMatchIndex + 1}/${state.totalMatches}" else "0/0",
          color = if (state.totalMatches > 0) TextSecondary else TextMuted,
          fontSize = 11.sp,
          fontFamily = FontFamily.Monospace
        )

        // Case Sensitive [Aa]
        Box(
          modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (state.matchCase) ElectricBlue.copy(alpha = 0.25f) else Color.Transparent)
            .clickable(onClick = onToggleCase),
          contentAlignment = Alignment.Center
        ) {
          Text("Aa", color = if (state.matchCase) ElectricBlueGlow else TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        // Whole Word [\b]
        Box(
          modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (state.wholeWord) ElectricBlue.copy(alpha = 0.25f) else Color.Transparent)
            .clickable(onClick = onToggleWord),
          contentAlignment = Alignment.Center
        ) {
          Text("W", color = if (state.wholeWord) ElectricBlueGlow else TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        // Previous
        IconButton(
          onClick = onPrevMatch,
          enabled = state.totalMatches > 0,
          modifier = Modifier.size(24.dp).testTag("btn_find_prev")
        ) {
          Icon(Icons.Default.ArrowUpward, contentDescription = "Previous", tint = if (state.totalMatches > 0) TextPrimary else TextMuted, modifier = Modifier.size(14.dp))
        }

        // Next
        IconButton(
          onClick = onNextMatch,
          enabled = state.totalMatches > 0,
          modifier = Modifier.size(24.dp).testTag("btn_find_next")
        ) {
          Icon(Icons.Default.ArrowDownward, contentDescription = "Next", tint = if (state.totalMatches > 0) TextPrimary else TextMuted, modifier = Modifier.size(14.dp))
        }

        // Close
        IconButton(
          onClick = onClose,
          modifier = Modifier.size(24.dp).testTag("btn_find_close")
        ) {
          Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted, modifier = Modifier.size(14.dp))
        }
      }

      // Replace Row (Animated)
      AnimatedVisibility(visible = showReplaceRow) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, start = 30.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Box(
            modifier = Modifier
              .weight(1f)
              .height(30.dp)
              .clip(RoundedCornerShape(4.dp))
              .background(DarkBackground)
              .border(1.dp, DarkBorderSubtle, RoundedCornerShape(4.dp))
              .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
          ) {
            BasicTextField(
              value = state.replaceQuery,
              onValueChange = onReplaceQueryChange,
              singleLine = true,
              textStyle = TextStyle(color = TextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
              cursorBrush = SolidColor(ElectricBlueGlow),
              modifier = Modifier.fillMaxWidth().testTag("input_replace_query"),
              decorationBox = { innerTextField ->
                if (state.replaceQuery.isEmpty()) {
                  Text("Replace with...", color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                innerTextField()
              }
            )
          }

          OutlinedButton(
            onClick = onReplaceCurrent,
            enabled = state.totalMatches > 0,
            modifier = Modifier.height(28.dp).testTag("btn_replace_one"),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
          ) {
            Text("Replace", fontSize = 11.sp)
          }

          OutlinedButton(
            onClick = onReplaceAll,
            enabled = state.totalMatches > 0,
            modifier = Modifier.height(28.dp).testTag("btn_replace_all"),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
          ) {
            Text("All", fontSize = 11.sp)
          }
        }
      }
    }
  }
}

/**
 * High-performance Code Canvas for touch interaction and programming.
 * Implements line numbers, active line highlight, syntax token highlighting,
 * code folding indicators, touch zoom, and smooth editing.
 */
@Composable
fun CodeEditorCanvas(
  textFieldValue: TextFieldValue,
  onValueChange: (TextFieldValue) -> Unit,
  language: Language,
  settings: EditorSettings,
  isEditMode: Boolean,
  onEnterEditMode: () -> Unit,
  scrollState: ScrollState = rememberScrollState(),
  listState: LazyListState = rememberLazyListState(),
  findMatches: List<IntRange> = emptyList(),
  activeMatchIndex: Int = -1,
  onKeyEvent: (KeyEvent) -> Boolean = { false },
  modifier: Modifier = Modifier
) {
  val theme = settings.theme
  val lines = remember(textFieldValue.text) { textFieldValue.text.lines() }

  // Detect active line based on cursor position
  val cursorOffset = textFieldValue.selection.start
  val activeLineIndex = remember(cursorOffset, textFieldValue.text) {
    if (cursorOffset <= 0) 0
    else {
      val textBeforeCursor = textFieldValue.text.take(cursorOffset.coerceAtMost(textFieldValue.text.length))
      textBeforeCursor.count { it == '\n' }.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
    }
  }

  val syntaxTransformation = remember(language, theme, findMatches, activeMatchIndex) {
    SyntaxHighlightTransformation(language, theme, findMatches, activeMatchIndex)
  }

  val verticalScrollState = scrollState
  val horizontalScrollState = rememberScrollState()
  val density = androidx.compose.ui.platform.LocalDensity.current
  val lineHeightSp = (settings.fontSize * settings.lineHeightMultiplier).sp
  val lineHeightDp = with(density) { lineHeightSp.toDp() }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(theme.background)
  ) {
    Row(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(verticalScrollState)
    ) {
      // 1. Line Numbers Gutter
      if (settings.showLineNumbers) {
        Column(
          modifier = Modifier
            .width(46.dp)
            .background(theme.gutterBg)
            .padding(vertical = 6.dp),
          horizontalAlignment = Alignment.End
        ) {
          lines.indices.forEach { idx ->
            val lineNum = idx + 1
            val isActive = settings.highlightActiveLine && idx == activeLineIndex
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .height(lineHeightDp)
                .background(if (isActive) theme.activeLineBg else Color.Transparent)
                .padding(end = 6.dp),
              contentAlignment = Alignment.CenterEnd
            ) {
              Text(
                text = "$lineNum",
                color = if (isActive) ElectricBlueGlow else theme.gutterText,
                fontSize = (settings.fontSize - 1).sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
              )
            }
          }
        }

        // Gutter vertical separator line
        Box(
          modifier = Modifier
            .width(1.dp)
            .height(lineHeightDp * lines.size.coerceAtLeast(1))
            .background(theme.activeLineBg)
        )
      }

      // 2. Syntax-Highlighted Code Editor Canvas
      Box(
        modifier = Modifier
          .weight(1f)
          .then(
            if (settings.wordWrap) Modifier.fillMaxWidth()
            else Modifier.horizontalScroll(horizontalScrollState)
          )
          .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 48.dp)
          .clickable(!isEditMode) { onEnterEditMode() }
      ) {
        // Active line background highlight behind text
        if (settings.highlightActiveLine && activeLineIndex in lines.indices) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .offset(y = lineHeightDp * activeLineIndex)
              .height(lineHeightDp)
              .background(theme.activeLineBg.copy(alpha = 0.5f))
          )
        }

        BasicTextField(
          value = textFieldValue,
          onValueChange = onValueChange,
          readOnly = !isEditMode,
          visualTransformation = syntaxTransformation,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("editor_textarea")
            .onPreviewKeyEvent { event ->
              onKeyEvent(event)
            },
          textStyle = TextStyle(
            color = theme.text,
            fontSize = settings.fontSize.sp,
            lineHeight = lineHeightSp,
            fontFamily = FontFamily.Monospace
          ),
          cursorBrush = SolidColor(ElectricBlueGlow)
        )
      }
    }
  }
}

/**
 * TrebEdit-inspired live HTML Preview dialog/view using a native Android WebView.
 */
@Composable
fun HtmlPreviewDialog(
  htmlContent: String,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("Close", color = CyanAccent)
      }
    },
    title = {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Icon(Icons.Default.Language, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(18.dp))
          Text("HTML Live Preview", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
      }
    },
    text = {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(420.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(Color.White)
      ) {
        AndroidView(
          factory = { ctx ->
            WebView(ctx).apply {
              webViewClient = WebViewClient()
              settings.javaScriptEnabled = true
              settings.domStorageEnabled = true
              loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
            }
          },
          update = { webView ->
            webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
          },
          modifier = Modifier.fillMaxSize()
        )
      }
    },
    containerColor = DarkSurface,
    shape = RoundedCornerShape(16.dp)
  )
}

/**
 * Clean live Markdown Preview with formatted headers, code blocks, lists, and links.
 */
@Composable
fun MarkdownPreviewPane(
  content: String,
  modifier: Modifier = Modifier
) {
  Box(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBackground)
      .padding(14.dp)
      .verticalScroll(rememberScrollState())
  ) {
    MarkdownText(
      text = content,
      textColor = TextPrimary
    )
  }
}

/**
 * Image Viewer for image files opened in the editor (.png, .jpg, .svg, etc.)
 */
@Composable
fun ImagePreviewPane(
  fileName: String,
  fileSize: Long,
  modifier: Modifier = Modifier
) {
  Box(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBackground)
      .padding(24.dp),
    contentAlignment = Alignment.Center
  ) {
    Surface(
      color = DarkSurface,
      shape = RoundedCornerShape(12.dp),
      border = BorderStroke(1.dp, DarkBorder),
      modifier = Modifier.padding(16.dp)
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Icon(
          imageVector = Icons.Default.Image,
          contentDescription = "Image Preview",
          tint = CyanAccent,
          modifier = Modifier.size(56.dp)
        )
        Text(
          text = fileName,
          color = TextPrimary,
          fontSize = 14.sp,
          fontWeight = FontWeight.SemiBold,
          fontFamily = FontFamily.Monospace
        )
        Text(
          text = "Size: ${fileSize / 1024} KB  •  Image File",
          color = TextMuted,
          fontSize = 12.sp
        )
      }
    }
  }
}
