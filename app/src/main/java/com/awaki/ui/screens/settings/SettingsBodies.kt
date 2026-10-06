package com.awaki.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.awaki.BuildConfig
import com.awaki.ui.WorkspaceViewModel
import com.awaki.ui.theme.DarkBackground
import com.awaki.ui.theme.DarkBorder
import com.awaki.ui.theme.DarkBorderSubtle
import com.awaki.ui.theme.DarkSurfaceElevated
import com.awaki.ui.theme.ElectricBlue
import com.awaki.ui.theme.ElectricBlueGlow
import com.awaki.ui.theme.TerminalGreen
import com.awaki.ui.theme.TextMuted
import com.awaki.ui.theme.TextPrimary
import com.awaki.ui.theme.TextSecondary
import com.awaki.ui.theme.WarningAmber

/**
 * The three settings surfaces that stayed long documents rather than becoming rows:
 * the web-access tiers, the skipped-folder list, and the About page. Each keeps the
 * heading it had as a card, because a sheet with no title of its own reads as a
 * fragment of the screen behind it.
 */

// ---- Web access ----

/**
 * Which tier answers the agent's web tools.
 *
 * `web_fetch` asks Jina.ai's reader first — 20 pages a minute with no key at all —
 * then rotates through the keys below, then fetches the page itself. `web_search`
 * needs a key for Jina and otherwise scrapes DuckDuckGo. Turning this off means no
 * third party ever sees the URL a fetch is asked for.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WebAccessCard(viewModel: WorkspaceViewModel, modifier: Modifier = Modifier) {
  val settings by viewModel.webAccess.collectAsState()
  val handles by viewModel.jinaKeyHandles.collectAsState()
  val report by viewModel.webAccessReport.collectAsState()
  val checking by viewModel.webAccessChecking.collectAsState()
  val bundled = viewModel.bundledJinaKeyCount
  var newKey by remember { mutableStateOf("") }
  var keyVisible by remember { mutableStateOf(false) }
  var addNote by remember { mutableStateOf<String?>(null) }

  Column(modifier = modifier.fillMaxWidth()) {
    Text(
      "Web Access (Jina.ai)",
      color = TextPrimary,
      fontSize = 16.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(bottom = 4.dp)
    )
    Text(
      "Reader: Jina.ai first (20 pages a minute with no key), then a key, then the page itself. " +
        "Search: Jina.ai only with a key, otherwise DuckDuckGo.",
      color = TextMuted,
      fontSize = 11.sp,
      lineHeight = 15.sp,
      modifier = Modifier.padding(bottom = 12.dp)
    )

    SettingsRowGroup(
      listOf(
        SettingsItem(
          id = "prefer_jina",
          group = SettingsGroup.Tools,
          title = "Route web tools through Jina.ai",
          icon = Icons.Outlined.Language,
          detail = if (settings.preferJina) {
            "On: cleaner markdown, and a paid budget is spent only once the free tier is used up."
          } else {
            "Off: the URL is never sent to a third party — pages are fetched and scraped directly."
          },
          end = RowEnd.Switch(settings.preferJina, viewModel::setPreferJina, "switch_prefer_jina")
        )
      )
    )

    Spacer(modifier = Modifier.height(14.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text("Your keys", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
      Text(
        "rotation: ${handles.size + bundled}",
        color = TextMuted,
        fontSize = 11.sp,
        modifier = Modifier.testTag("txt_jina_rotation")
      )
    }
    Spacer(modifier = Modifier.height(6.dp))
    if (handles.isEmpty()) {
      Text(
        if (bundled > 0) "Trying with the public free tier. Visit https://jina.ai to create API Key!"
        else "Visit https://jina.ai to create API Key! — the free tier and the direct route carry every call.",
        color = TextMuted,
        fontSize = 11.sp,
        lineHeight = 14.sp
      )
    } else {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        handles.forEach { handle ->
          KeyChip(handle = handle, onRemove = { viewModel.removeJinaKey(handle) })
        }
      }
    }

    Spacer(modifier = Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      OutlinedTextField(
        value = newKey,
        onValueChange = { newKey = it; addNote = null },
        placeholder = { Text("jina_… — paste one or several", color = TextMuted, fontSize = 12.sp) },
        singleLine = true,
        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
          IconButton(
            onClick = { keyVisible = !keyVisible },
            modifier = Modifier.testTag("btn_toggle_jina_key_visible")
          ) {
            Icon(
              imageVector = if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
              contentDescription = if (keyVisible) "Hide the key" else "Show the key",
              tint = TextMuted,
              modifier = Modifier.size(16.dp)
            )
          }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier
          .weight(1f)
          .testTag("input_jina_key"),
        textStyle = TextStyle(fontSize = 13.sp, color = TextPrimary, fontFamily = FontFamily.Monospace),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = ElectricBlue,
          unfocusedBorderColor = DarkBorder,
          focusedContainerColor = DarkBackground,
          unfocusedContainerColor = DarkBackground,
          focusedTextColor = TextPrimary,
          unfocusedTextColor = TextPrimary
        )
      )
      Spacer(modifier = Modifier.width(8.dp))
      Button(
        onClick = {
          val added = viewModel.addJinaKeys(newKey)
          addNote = if (added == 0) "Nothing added — that held no key."
          else "Added $added key${if (added > 1) "s" else ""} to the rotation."
          newKey = ""
        },
        enabled = newKey.isNotBlank(),
        modifier = Modifier.testTag("btn_add_jina_key"),
        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
      ) { Text("Add", fontSize = 12.sp) }
    }
    Text(
      addNote ?: "Keys stay in this device's private storage and go only to jina.ai.",
      color = if (addNote == null) TextMuted else ElectricBlueGlow,
      fontSize = 10.sp,
      lineHeight = 13.sp,
      modifier = Modifier.testTag("txt_jina_key_note")
    )

    HorizontalDivider(color = DarkBorderSubtle, modifier = Modifier.padding(vertical = 12.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Test what answers now", color = TextPrimary, fontSize = 13.sp)
        Text(
          "One reader call, plus one search call where a key is configured.",
          color = TextMuted,
          fontSize = 10.sp,
          lineHeight = 13.sp
        )
      }
      Button(
        onClick = { viewModel.checkWebAccess() },
        enabled = !checking,
        modifier = Modifier.testTag("btn_test_web_access"),
        colors = ButtonDefaults.buttonColors(
          containerColor = DarkSurfaceElevated,
          contentColor = TextPrimary,
          disabledContainerColor = DarkSurfaceElevated,
          disabledContentColor = TextMuted
        ),
        border = BorderStroke(1.dp, DarkBorder)
      ) { Text(if (checking) "Checking…" else "Test", fontSize = 12.sp) }
    }

    if (report.isNotEmpty()) {
      Spacer(modifier = Modifier.height(8.dp))
      Column(modifier = Modifier.testTag("txt_web_access_report")) {
        report.forEach { line ->
          Text(line, color = TextSecondary, fontSize = 10.sp, lineHeight = 14.sp)
        }
      }
    }
  }
}

/** A key by its handle only: the secret is never rendered, and a tap removes it. */
@Composable
private fun KeyChip(handle: String, onRemove: () -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(DarkSurfaceElevated)
      .border(1.dp, DarkBorderSubtle, RoundedCornerShape(6.dp))
      .clickable(onClick = onRemove)
      .padding(horizontal = 8.dp, vertical = 5.dp)
      .testTag("chip_jina_key_$handle")
  ) {
    Text(handle, color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    Spacer(modifier = Modifier.width(5.dp))
    Icon(
      imageVector = Icons.Default.Close,
      contentDescription = "Remove the key $handle",
      tint = TextMuted,
      modifier = Modifier.size(12.dp)
    )
  }
}

// ---- Skipped folders ----

/**
 * Editable scan-exclusion list: the folder names skipped by tree scans, searches,
 * agent tools and imports. Users can remove defaults, add their own, or replace the
 * built-in list entirely.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScanExclusionsBody(viewModel: WorkspaceViewModel, modifier: Modifier = Modifier) {
  val settings by viewModel.scanIgnoreSettings.collectAsState()
  val skippedDirs by viewModel.effectiveIgnoredDirs.collectAsState()
  var newDirInput by remember { mutableStateOf("") }

  Column(modifier = modifier.fillMaxWidth()) {
    Text(
      "Skipped folders",
      color = TextPrimary,
      fontSize = 16.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(bottom = 4.dp)
    )
    Text(
      "Folders listed here are skipped everywhere: the Files tree, project search, agent tools and folder " +
        "imports. Generated folders like node_modules or build outputs often hold hundreds of thousands of " +
        "files — skipping them is what keeps huge projects fast to open. Only remove one if you truly need " +
        "to browse it.",
      color = TextMuted,
      fontSize = 11.sp,
      lineHeight = 15.sp,
      modifier = Modifier.padding(bottom = 12.dp)
    )

    SettingsRowGroup(
      listOf(
        SettingsItem(
          id = "ignore_override",
          group = SettingsGroup.Tools,
          title = "Only my custom list",
          icon = Icons.Outlined.FolderOff,
          detail = if (settings.useCustomListOnly) {
            "Built-in defaults are fully overridden — only the folders below are skipped."
          } else {
            "Off: built-in defaults, plus folders you add, minus any you remove."
          },
          end = RowEnd.Switch(
            settings.useCustomListOnly,
            viewModel::setIgnoredDirsOverride,
            "switch_ignore_override"
          )
        )
      )
    )

    Spacer(modifier = Modifier.height(14.dp))
    Text(
      if (settings.useCustomListOnly) "Skipped (custom)" else "Skipped",
      color = TextSecondary,
      fontSize = 12.sp,
      fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(6.dp))
    if (skippedDirs.isEmpty()) {
      Text(
        "Nothing is skipped — opening huge projects may be slow or run out of memory.",
        color = WarningAmber,
        fontSize = 11.sp,
        modifier = Modifier.testTag("txt_ignore_warning")
      )
    } else {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        skippedDirs.forEach { name ->
          IgnoreChip(
            name = name,
            custom = settings.extraDirs.contains(name),
            onRemove = { viewModel.removeIgnoredDir(name) }
          )
        }
      }
    }

    if (!settings.useCustomListOnly && settings.removedDefaults.isNotEmpty()) {
      Spacer(modifier = Modifier.height(10.dp))
      Text("Removed from defaults — tap to restore", color = TextMuted, fontSize = 11.sp)
      Spacer(modifier = Modifier.height(6.dp))
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        settings.removedDefaults.sorted().forEach { name ->
          IgnoreChip(
            name = name,
            custom = false,
            struckThrough = true,
            onRemove = { viewModel.addIgnoredDir(name) }
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      OutlinedTextField(
        value = newDirInput,
        onValueChange = { newDirInput = it },
        placeholder = { Text("folder name, e.g. third_party", color = TextMuted, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier
          .weight(1f)
          .testTag("input_ignore_dir"),
        textStyle = TextStyle(fontSize = 13.sp, color = TextPrimary),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = ElectricBlue,
          unfocusedBorderColor = DarkBorder,
          focusedContainerColor = DarkBackground,
          unfocusedContainerColor = DarkBackground,
          focusedTextColor = TextPrimary,
          unfocusedTextColor = TextPrimary
        )
      )
      Spacer(modifier = Modifier.width(8.dp))
      Button(
        onClick = {
          viewModel.addIgnoredDir(newDirInput)
          newDirInput = ""
        },
        enabled = newDirInput.isNotBlank(),
        modifier = Modifier.testTag("btn_add_ignore_dir"),
        colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
      ) { Text("Add", fontSize = 12.sp) }
    }
    Text(
      "Restore built-in defaults",
      color = TextMuted,
      fontSize = 12.sp,
      modifier = Modifier
        .padding(top = 10.dp)
        .clickable { viewModel.restoreDefaultIgnoredDirs() }
        .testTag("btn_reset_ignore_dirs")
    )
  }
}

@Composable
private fun IgnoreChip(
  name: String,
  custom: Boolean,
  struckThrough: Boolean = false,
  onRemove: () -> Unit
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(DarkSurfaceElevated)
      .border(
        1.dp,
        if (custom) ElectricBlue.copy(alpha = 0.6f) else DarkBorderSubtle,
        RoundedCornerShape(6.dp)
      )
      .clickable(onClick = onRemove)
      .padding(horizontal = 8.dp, vertical = 5.dp)
      .testTag("chip_ignore_$name")
  ) {
    Text(
      text = name,
      color = if (struckThrough) TextMuted else TextPrimary,
      fontSize = 11.sp,
      fontFamily = FontFamily.Monospace,
      textDecoration = if (struckThrough) TextDecoration.LineThrough else TextDecoration.None
    )
    Spacer(modifier = Modifier.width(5.dp))
    Icon(
      imageVector = if (struckThrough) Icons.Default.Add else Icons.Default.Close,
      contentDescription = if (struckThrough) "Restore $name" else "Stop skipping $name",
      tint = TextMuted,
      modifier = Modifier.size(12.dp)
    )
  }
}

// ---- About ----

private const val DEVELOPER_NAME = "Gemechis Chala"
private const val DEVELOPER_EMAIL = "gladsonchala@gmail.com"
private const val DEVELOPER_TELEGRAM = "venopyx"
private const val DEVELOPER_LOCATION = "Addis Ababa, Ethiopia"

@Composable
internal fun AboutCard(modifier: Modifier = Modifier) {
  val uriHandler = LocalUriHandler.current
  Column(modifier = modifier.fillMaxWidth()) {
    Text(
      "About",
      color = TextPrimary,
      fontSize = 16.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(bottom = 8.dp)
    )
    Text(
      text = "Awaki is a coding workspace that runs on the device itself: an Ubuntu " +
        "userland, a real terminal, and an agent that reads, edits, builds and tests your " +
        "projects. Projects, chats and settings stay in the app's private storage — only the " +
        "prompts you send leave it, for the model provider you configure.",
      color = TextSecondary,
      fontSize = 12.sp,
      lineHeight = 17.sp
    )
    Spacer(modifier = Modifier.height(10.dp))
    Text(
      text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
      color = TextMuted,
      fontSize = 10.5.sp,
      fontFamily = FontFamily.Monospace
    )

    Spacer(modifier = Modifier.height(14.dp))
    Text(DEVELOPER_NAME, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Text("Developer · $DEVELOPER_LOCATION", color = TextMuted, fontSize = 11.sp)
    Spacer(modifier = Modifier.height(10.dp))
    ContactRow(
      icon = Icons.Outlined.MailOutline,
      label = "Email me",
      value = DEVELOPER_EMAIL,
      tint = ElectricBlueGlow,
      tag = "about_email",
      onClick = { uriHandler.openUri("mailto:$DEVELOPER_EMAIL") }
    )
    Spacer(modifier = Modifier.height(8.dp))
    ContactRow(
      icon = Icons.AutoMirrored.Outlined.Send,
      label = "Telegram",
      value = "@$DEVELOPER_TELEGRAM",
      tint = TerminalGreen,
      tag = "about_telegram",
      onClick = { uriHandler.openUri("https://t.me/$DEVELOPER_TELEGRAM") }
    )
  }
}

@Composable
private fun ContactRow(
  icon: ImageVector,
  label: String,
  value: String,
  tint: Color,
  tag: String,
  onClick: () -> Unit
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(DarkSurfaceElevated)
      .border(1.dp, DarkBorderSubtle, RoundedCornerShape(10.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 9.dp)
      .testTag(tag)
  ) {
    Box(
      modifier = Modifier
        .size(26.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(tint.copy(alpha = 0.14f)),
      contentAlignment = Alignment.Center
    ) {
      Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
    }
    Spacer(modifier = Modifier.width(10.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(label, color = TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
      Text(value, color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
    Icon(
      imageVector = Icons.Default.ChevronRight,
      contentDescription = null,
      tint = TextMuted,
      modifier = Modifier.size(16.dp)
    )
  }
}
