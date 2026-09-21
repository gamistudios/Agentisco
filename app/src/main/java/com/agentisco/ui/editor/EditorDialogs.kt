package com.agentisco.ui.editor

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentisco.editor.model.EditorSettings
import com.agentisco.editor.syntax.CodeSymbol
import com.agentisco.editor.syntax.Language
import com.agentisco.editor.syntax.SymbolKind
import com.agentisco.editor.syntax.SyntaxTheme
import com.agentisco.ui.theme.*

/**
 * Go to line dialog with validation and direct jump.
 */
@Composable
fun GoToLineDialog(
  totalLines: Int,
  currentLine: Int,
  onJumpToLine: (Int) -> Unit,
  onDismiss: () -> Unit
) {
  var lineInput by remember { mutableStateOf("$currentLine") }
  var isError by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
      Button(
        onClick = {
          val num = lineInput.toIntOrNull()
          if (num != null && num in 1..totalLines) {
            onJumpToLine(num)
            onDismiss()
          } else {
            isError = true
          }
        },
        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
        modifier = Modifier.testTag("btn_confirm_goto_line")
      ) {
        Text("Jump")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel", color = TextSecondary)
      }
    },
    title = {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Pin, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(20.dp))
        Text("Go to Line", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
      }
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Enter line number between 1 and $totalLines:", color = TextSecondary, fontSize = 13.sp)
        OutlinedTextField(
          value = lineInput,
          onValueChange = {
            lineInput = it.filter { char -> char.isDigit() }
            isError = false
          },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
          keyboardActions = KeyboardActions(onGo = {
            val num = lineInput.toIntOrNull()
            if (num != null && num in 1..totalLines) {
              onJumpToLine(num)
              onDismiss()
            } else {
              isError = true
            }
          }),
          isError = isError,
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ElectricBlueGlow,
            unfocusedBorderColor = DarkBorder,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary
          ),
          modifier = Modifier.fillMaxWidth().testTag("input_goto_line")
        )
        if (isError) {
          Text("Invalid line number", color = DangerRed, fontSize = 11.sp)
        }
      }
    },
    containerColor = DarkSurface,
    shape = RoundedCornerShape(16.dp)
  )
}

/**
 * Go to Symbol / Code Outline sheet.
 * Extracts functions, classes, interfaces, and allows instant navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoToSymbolSheet(
  symbols: List<CodeSymbol>,
  onSelectSymbol: (CodeSymbol) -> Unit,
  onDismiss: () -> Unit
) {
  var searchQuery by remember { mutableStateOf("") }
  val filteredSymbols = remember(symbols, searchQuery) {
    if (searchQuery.isBlank()) symbols
    else symbols.filter { it.name.contains(searchQuery, ignoreCase = true) || it.signature.contains(searchQuery, ignoreCase = true) }
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = DarkSurface,
    dragHandle = { BottomSheetDefaults.DragHandle(color = DarkBorder) },
    modifier = Modifier.testTag("sheet_goto_symbol")
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .padding(bottom = 24.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Icon(Icons.AutoMirrored.Filled.Segment, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(20.dp))
          Text("File Symbols & Outline", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Text("${symbols.size} symbols", color = TextMuted, fontSize = 12.sp)
      }

      Spacer(modifier = Modifier.height(12.dp))

      // Search bar
      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        placeholder = { Text("Filter symbols...", color = TextMuted, fontSize = 13.sp) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp)) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = ElectricBlueGlow,
          unfocusedBorderColor = DarkBorder,
          focusedTextColor = TextPrimary,
          unfocusedTextColor = TextPrimary
        ),
        modifier = Modifier.fillMaxWidth().height(50.dp)
      )

      Spacer(modifier = Modifier.height(12.dp))

      if (filteredSymbols.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
          contentAlignment = Alignment.Center
        ) {
          Text("No matching symbols found in file", color = TextMuted, fontSize = 13.sp)
        }
      } else {
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 350.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          items(filteredSymbols) { sym ->
            Surface(
              onClick = {
                onSelectSymbol(sym)
                onDismiss()
              },
              color = DarkSurfaceElevated,
              shape = RoundedCornerShape(8.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                  // Symbol kind icon
                  val (icon, color) = when (sym.kind) {
                    SymbolKind.CLASS -> Icons.Default.Category to WarningAmber
                    SymbolKind.INTERFACE -> Icons.Default.Handshake to CyanAccent
                    SymbolKind.FUNCTION -> Icons.Default.Code to ElectricBlueGlow
                    SymbolKind.STRUCT -> Icons.Default.Widgets to Color(0xFFDEA584)
                    SymbolKind.ENUM -> Icons.AutoMirrored.Filled.List to Color(0xFFA78BFA)
                    SymbolKind.HEADING -> Icons.Default.Title to TerminalGreen
                    SymbolKind.VARIABLE -> Icons.Default.DataObject to TextSecondary
                  }
                  Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                  Column {
                    Text(
                      text = sym.name,
                      color = TextPrimary,
                      fontSize = 13.sp,
                      fontWeight = FontWeight.SemiBold,
                      fontFamily = FontFamily.Monospace
                    )
                    if (sym.signature.isNotBlank()) {
                      Text(
                        text = sym.signature,
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                      )
                    }
                  }
                }
                Text(
                  text = "Line ${sym.line}",
                  color = ElectricBlueGlow,
                  fontSize = 11.sp,
                  fontFamily = FontFamily.Monospace
                )
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Dialog to select and override programming language for the active file.
 */
@Composable
fun LanguageSelectorDialog(
  currentLanguage: Language,
  onSelectLanguage: (Language) -> Unit,
  onDismiss: () -> Unit
) {
  var searchQuery by remember { mutableStateOf("") }
  val allLanguages = remember { Language.entries }
  val filtered = remember(searchQuery) {
    if (searchQuery.isBlank()) allLanguages
    else allLanguages.filter { it.displayName.contains(searchQuery, ignoreCase = true) || it.id.contains(searchQuery, ignoreCase = true) }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = {},
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Close", color = TextSecondary) }
    },
    title = {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Translate, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(20.dp))
        Text("Select Language Mode", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
      }
    },
    text = {
      Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
          value = searchQuery,
          onValueChange = { searchQuery = it },
          placeholder = { Text("Filter languages...", color = TextMuted, fontSize = 12.sp) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().height(48.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .height(280.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          items(filtered) { lang ->
            val isSelected = lang == currentLanguage
            Surface(
              onClick = {
                onSelectLanguage(lang)
                onDismiss()
              },
              color = if (isSelected) ElectricBlue.copy(alpha = 0.2f) else DarkSurfaceElevated,
              shape = RoundedCornerShape(6.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  Box(
                    modifier = Modifier
                      .size(8.dp)
                      .clip(CircleShape)
                      .background(lang.accentColor)
                  )
                  Text(
                    text = lang.displayName,
                    color = if (isSelected) ElectricBlueGlow else TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                  )
                }
                if (isSelected) {
                  Icon(Icons.Default.Check, contentDescription = null, tint = ElectricBlueGlow, modifier = Modifier.size(16.dp))
                }
              }
            }
          }
        }
      }
    },
    containerColor = DarkSurface,
    shape = RoundedCornerShape(16.dp)
  )
}

/**
 * Comprehensive Editor Settings Modal Sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorSettingsSheet(
  settings: EditorSettings,
  onUpdateSettings: (EditorSettings) -> Unit,
  onDismiss: () -> Unit
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = DarkSurface,
    dragHandle = { BottomSheetDefaults.DragHandle(color = DarkBorder) }
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp)
        .padding(bottom = 32.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Tune, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(20.dp))
        Text("Editor Preferences", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
      }

      // Font Size
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Text("Font Size", color = TextPrimary, fontSize = 13.sp)
          Text("${settings.fontSize} sp", color = CyanAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
          value = settings.fontSize.toFloat(),
          onValueChange = { onUpdateSettings(settings.copy(fontSize = it.toInt())) },
          valueRange = 10f..22f,
          steps = 11,
          colors = SliderDefaults.colors(thumbColor = CyanAccent, activeTrackColor = ElectricBlue)
        )
      }

      HorizontalDivider(color = DarkBorderSubtle)

      // Theme Selection
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Color Theme", color = TextPrimary, fontSize = 13.sp)
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          SyntaxTheme.allThemes.forEach { th ->
            val isSelected = th.name == settings.syntaxThemeName
            FilterChip(
              selected = isSelected,
              onClick = { onUpdateSettings(settings.copy(syntaxThemeName = th.name)) },
              label = { Text(th.name, fontSize = 11.sp) },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = ElectricBlue.copy(alpha = 0.3f),
                selectedLabelColor = ElectricBlueGlow
              )
            )
          }
        }
      }

      HorizontalDivider(color = DarkBorderSubtle)

      // Toggle Switches
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text("Word Wrap", color = TextPrimary, fontSize = 13.sp)
          Text("Wrap long lines horizontally", color = TextMuted, fontSize = 11.sp)
        }
        Switch(
          checked = settings.wordWrap,
          onCheckedChange = { onUpdateSettings(settings.copy(wordWrap = it)) },
          colors = SwitchDefaults.colors(checkedThumbColor = ElectricBlueGlow, checkedTrackColor = ElectricBlue)
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text("Show Line Numbers", color = TextPrimary, fontSize = 13.sp)
          Text("Gutter line indicators", color = TextMuted, fontSize = 11.sp)
        }
        Switch(
          checked = settings.showLineNumbers,
          onCheckedChange = { onUpdateSettings(settings.copy(showLineNumbers = it)) },
          colors = SwitchDefaults.colors(checkedThumbColor = ElectricBlueGlow, checkedTrackColor = ElectricBlue)
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text("Highlight Active Line", color = TextPrimary, fontSize = 13.sp)
          Text("Subtle background glow on current line", color = TextMuted, fontSize = 11.sp)
        }
        Switch(
          checked = settings.highlightActiveLine,
          onCheckedChange = { onUpdateSettings(settings.copy(highlightActiveLine = it)) },
          colors = SwitchDefaults.colors(checkedThumbColor = ElectricBlueGlow, checkedTrackColor = ElectricBlue)
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text("Auto Save", color = TextPrimary, fontSize = 13.sp)
          Text("Save changes on switching tabs", color = TextMuted, fontSize = 11.sp)
        }
        Switch(
          checked = settings.autoSave,
          onCheckedChange = { onUpdateSettings(settings.copy(autoSave = it)) },
          colors = SwitchDefaults.colors(checkedThumbColor = ElectricBlueGlow, checkedTrackColor = ElectricBlue)
        )
      }
    }
  }
}

/**
 * Contextual AI Action Dialog for code selection or file queries.
 */
@Composable
fun AiEditorActionDialog(
  fileName: String,
  selectedCode: String,
  onSendToAgent: (prompt: String) -> Unit,
  onDismiss: () -> Unit
) {
  var customPrompt by remember { mutableStateOf("") }

  val quickActions = listOf(
    "Explain what this code does" to "Explain the following code in $fileName:\n\n```\n$selectedCode\n```",
    "Fix errors / bugs" to "Review the following code in $fileName and fix any bugs or errors:\n\n```\n$selectedCode\n```",
    "Refactor & Clean up" to "Refactor the following code in $fileName for better readability and performance:\n\n```\n$selectedCode\n```",
    "Generate unit tests" to "Write comprehensive unit tests for this code in $fileName:\n\n```\n$selectedCode\n```",
    "Add documentation / docstrings" to "Add clean documentation comments to this code in $fileName:\n\n```\n$selectedCode\n```"
  )

  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
      Button(
        onClick = {
          if (customPrompt.isNotBlank()) {
            val fullPrompt = if (selectedCode.isNotBlank()) {
              "$customPrompt\n\nFile: $fileName\n```\n$selectedCode\n```"
            } else {
              "$customPrompt\n\nFile: $fileName"
            }
            onSendToAgent(fullPrompt)
            onDismiss()
          }
        },
        enabled = customPrompt.isNotBlank(),
        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
        modifier = Modifier.testTag("btn_send_ai_prompt")
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(14.dp))
          Text("Ask Agent")
        }
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
    },
    title = {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = ElectricBlueGlow, modifier = Modifier.size(20.dp))
        Text("Agent Coding Assistant", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
      }
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Text("Select a quick action or write a custom prompt for $fileName:", color = TextSecondary, fontSize = 12.sp)

        // Quick action chips
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          quickActions.forEach { (label, prompt) ->
            Surface(
              onClick = {
                onSendToAgent(prompt)
                onDismiss()
              },
              color = DarkSurfaceElevated,
              shape = RoundedCornerShape(6.dp),
              border = BorderStroke(1.dp, DarkBorderSubtle),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Text(label, color = TextPrimary, fontSize = 12.sp)
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Custom Prompt input
        OutlinedTextField(
          value = customPrompt,
          onValueChange = { customPrompt = it },
          placeholder = { Text("Ask anything about this file...", color = TextMuted, fontSize = 12.sp) },
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ElectricBlueGlow,
            unfocusedBorderColor = DarkBorder,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary
          ),
          modifier = Modifier.fillMaxWidth().height(80.dp).testTag("input_custom_ai_prompt")
        )
      }
    },
    containerColor = DarkSurface,
    shape = RoundedCornerShape(16.dp)
  )
}
