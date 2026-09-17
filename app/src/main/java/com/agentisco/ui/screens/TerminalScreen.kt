package com.agentisco.ui.screens

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.agentisco.core.model.AppDestination
import com.agentisco.ui.WorkspaceViewModel
import com.agentisco.ui.theme.*
import com.agentisco.workspace.terminal.LinuxEnvironmentState
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import java.util.Locale

// Termux-style extra keys row: two fixed rows of six keys. Every key sends the
// real byte sequence through the PTY, so the shell inside the rootfs sees a
// genuine keyboard event.
private val TermuxKeyRows = listOf(
  listOf("CTRL", "ESC", "HOME", "↑", "END", "PGUP"),
  listOf("TAB", "/", "←", "↓", "→", "PGDN")
)

// ctrlKey function implements proper terminal control sequences
private fun ctrlKey(char: Char): ByteArray {
  return when (char) {
    '[' -> byteArrayOf(0x1b) // Ctrl+[
    '\\' -> byteArrayOf(0x1c) // Ctrl+\
    ']' -> byteArrayOf(0x1d) // Ctrl+]
    '^' -> byteArrayOf(0x1e) // Ctrl+^
    '_' -> byteArrayOf(0x1f) // Ctrl+_
    '?' -> byteArrayOf(0x7f) // Ctrl+? (Delete)
    else -> {
      // For other characters, implement standard terminal Ctrl behavior:
      // ASCII value bitwise AND with 0x1f (31) to make it a control character
      val ctrlCode = (char.code and 0x1f).toByte()
      byteArrayOf(ctrlCode)
    }
  }
}

private fun keyBytes(key: String, ctrlActive: Boolean = false): ByteArray {
  return when (key) {
    "CTRL" -> byteArrayOf(0x1b) // Toggle Ctrl mode
    "ESC" -> byteArrayOf(0x1b)
    "TAB" -> byteArrayOf(0x09)
    "/" -> byteArrayOf('/'.code.toByte())
    "↑" -> "\u001b[A".toByteArray()
    "↓" -> "\u001b[B".toByteArray()
    "→" -> "\u001b[C".toByteArray()
    "←" -> "\u001b[D".toByteArray()
    "HOME" -> "\u001b[H".toByteArray()
    "END" -> "\u001b[F".toByteArray()
    "PGUP" -> "\u001b[5~".toByteArray()
    "PGDN" -> "\u001b[6~".toByteArray()
    else -> {
      if (ctrlActive && key.length == 1) {
        // Common control sequences that need special handling
        return when (key[0]) {
          'c' -> byteArrayOf(0x03) // Ctrl+C = SIGINT
          'z' -> byteArrayOf(0x1a) // Ctrl+Z = SIGTSTP
          'd' -> byteArrayOf(0x04) // Ctrl+D = EOF
          's' -> byteArrayOf(0x13) // Ctrl+S = XOFF
          'q' -> byteArrayOf(0x11) // Ctrl+Q = XON
          else -> {
            // Standard terminal Ctrl behavior: ASCII value bitwise AND with 0x1f (31)
            val ctrlCode = (key[0].code and 0x1f).toByte()
            byteArrayOf(ctrlCode)
          }
        }
      } else {
        key.toByteArray()
      }
    }
  }
}

@Composable
fun TerminalScreen(
  viewModel: WorkspaceViewModel,
  onNavigate: (AppDestination) -> Unit,
  modifier: Modifier = Modifier
) {
  val sessions by viewModel.terminalSessions.collectAsState()
  val activeSessionId by viewModel.activeTerminalSessionId.collectAsState()
  val ptySessions by viewModel.ptySessions.collectAsState()
  val envState by viewModel.linuxEnvironmentState.collectAsState()

  var showNewSessionDialog by remember { mutableStateOf(false) }
  var newSessionNameInput by remember { mutableStateOf("") }

  val currentSession = remember(sessions, activeSessionId) {
    sessions.find { it.id == activeSessionId } ?: sessions.first()
  }
  val activePty = ptySessions[currentSession.id]

  // Kick off the real Debian bootstrap on first open, and create the PTY
  // session for the visible tab once the rootfs is ready.
  LaunchedEffect(envState) {
    when (envState) {
      LinuxEnvironmentState.NotBootstrapped -> viewModel.startLinuxBootstrap()
      is LinuxEnvironmentState.Failed -> Unit // stays until the user taps Retry
      LinuxEnvironmentState.Ready -> sessions.forEach { viewModel.ensurePtySession(it.id, it.name) }
      else -> Unit
    }
  }
  LaunchedEffect(sessions, envState) {
    if (envState is LinuxEnvironmentState.Ready) {
      sessions.forEach { viewModel.ensurePtySession(it.id, it.name) }
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBackground)
      .imePadding()
  ) {
    TerminalHeader(
      currentDirLabel = currentSession.currentDir,
      onNewSession = { showNewSessionDialog = true }
    )
    SessionTabs(
      sessions = sessions,
      activeSessionId = activeSessionId,
      onSelect = { viewModel.selectTerminalSession(it) },
      onClose = { viewModel.closeTerminalSession(it) },
      onNewSession = { showNewSessionDialog = true }
    )

    when (val st = envState) {
      is LinuxEnvironmentState.Ready -> {
        var attachedSession by remember { mutableStateOf<TerminalSession?>(null) }

        if (activePty == null) {
          Column(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, alignment = Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Text("Starting shell…", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
              "If this message stays visible, the shell could not be started. Check logcat for tag \"ScoOS-Terminal\".",
              color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
          }
        } else AndroidView(
          factory = { ctx ->
            val view = TerminalView(ctx, null).apply {
              // Creates mRenderer; without it updateSize() crashes on layout
              // when the view sizes before a session is attached.
              setTextSize(26)
              // Termux sets these in its layout XML; without them
              // requestFocus() fails and neither the IME nor the long-press
              // text-selection mode can start.
              isFocusable = true
              isFocusableInTouchMode = true
            }
            view.setTerminalViewClient(ScoTerminalViewClient(view))
            view
          },
          update = { view ->
            val session = activePty
            if (session !== attachedSession) {
              // Wire redraw notifications from the session's client bridge.
              viewModel.repository.terminalClientRegistry[currentSession.id]?.let { bridge ->
                bridge.redrawCallback = { view.post { view.onScreenUpdated() } }
              }
              view.attachSession(session)
              attachedSession = session
            }
            view.updateSize()
          },
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .background(Color.Black)
            .testTag("terminal_console")
        )
        TerminalExtraKeysGrid(onKey = { key, ctrlActive -> activePty?.let { pty ->
          val bytes = keyBytes(key, ctrlActive)
          if (bytes.isNotEmpty()) pty.write(bytes, 0, bytes.size)
        } })
      }

      else -> BootstrapPane(
        state = st,
        onRetry = { viewModel.startLinuxBootstrap() },
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
      )
    }
  }

  if (showNewSessionDialog) {
    AlertDialog(
      onDismissRequest = { showNewSessionDialog = false },
      title = {
        Text("New Terminal Session", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("Open another shell inside the Debian environment:", color = TextSecondary, fontSize = 12.sp)
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            listOf("bash", "build", "test", "dev").forEach { preset ->
              Surface(
                onClick = { newSessionNameInput = preset },
                shape = RoundedCornerShape(6.dp),
                color = if (newSessionNameInput == preset) ElectricBlue.copy(alpha = 0.2f) else DarkSurfaceElevated,
                border = BorderStroke(1.dp, if (newSessionNameInput == preset) ElectricBlue else DarkBorderSubtle)
              ) {
                Text(
                  text = preset,
                  color = if (newSessionNameInput == preset) ElectricBlueGlow else TextPrimary,
                  fontSize = 11.sp,
                  fontFamily = FontFamily.Monospace,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
              }
            }
          }
          OutlinedTextField(
            value = newSessionNameInput,
            onValueChange = { newSessionNameInput = it },
            placeholder = { Text("e.g. bash-2, build, test", color = TextMuted, fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("input_new_session_name"),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = ElectricBlue,
              unfocusedBorderColor = DarkBorderSubtle,
              focusedTextColor = TextPrimary,
              unfocusedTextColor = TextPrimary
            )
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            val name = newSessionNameInput.trim().ifEmpty { "bash-${sessions.size + 1}" }
            viewModel.createTerminalSession(name)
            newSessionNameInput = ""
            showNewSessionDialog = false
          },
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
          modifier = Modifier.testTag("btn_confirm_new_session")
        ) {
          Text("Create Session", color = Color.White)
        }
      },
      dismissButton = {
        TextButton(onClick = { showNewSessionDialog = false }) {
          Text("Cancel", color = TextMuted)
        }
      },
      containerColor = DarkSurface,
      shape = RoundedCornerShape(12.dp)
    )
  }
}

@Composable
private fun TerminalHeader(currentDirLabel: String, onNewSession: () -> Unit) {
  val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = DarkSurface,
    border = BorderStroke(1.dp, DarkBorderSubtle)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.weight(1f, fill = false)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(8.dp)
              .clip(CircleShape)
              .background(TerminalGreen)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "Ubuntu Linux",
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
          )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = currentDirLabel,
            color = TextMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
          )
          Icon(
            Icons.Outlined.ContentCopy,
            contentDescription = "Copy path",
            tint = TextMuted,
            modifier = Modifier
              .padding(start = 4.dp)
              .size(11.dp)
              .clickable { clipboard.setText(AnnotatedString(currentDirLabel)) }
          )
        }
      }

      IconButton(
        onClick = onNewSession,
        modifier = Modifier
          .size(28.dp)
          .clip(RoundedCornerShape(6.dp))
          .background(DarkSurfaceElevated)
          .testTag("btn_new_terminal_session")
      ) {
        Icon(Icons.Default.Add, contentDescription = "New Session", tint = ElectricBlueGlow, modifier = Modifier.size(16.dp))
      }
    }
  }
}

@Composable
private fun SessionTabs(
  sessions: List<com.agentisco.data.model.TerminalSession>,
  activeSessionId: String,
  onSelect: (String) -> Unit,
  onClose: (String) -> Unit,
  onNewSession: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .horizontalScroll(rememberScrollState())
      .padding(horizontal = 10.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    sessions.forEach { s ->
      val isSelected = s.id == activeSessionId
      Box(
        modifier = Modifier
          .clip(RoundedCornerShape(6.dp))
          .background(if (isSelected) DarkSurfaceHighlight else DarkBackground)
          .border(1.dp, if (isSelected) ElectricBlue else DarkBorderSubtle, RoundedCornerShape(6.dp))
          .clickable { onSelect(s.id) }
          .padding(horizontal = 8.dp, vertical = 4.dp)
          .testTag("tab_session_${s.name}")
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(6.dp)
              .clip(CircleShape)
              .background(if (isSelected) TerminalGreen else TextMuted)
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = s.name,
            color = if (isSelected) TextPrimary else TextSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
          )
          Spacer(modifier = Modifier.width(6.dp))
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Close session",
            tint = if (isSelected) TextSecondary else TextMuted,
            modifier = Modifier
              .size(13.dp)
              .clip(CircleShape)
              .clickable { onClose(s.id) }
              .testTag("btn_close_session_${s.name}")
          )
        }
      }
    }

    Surface(
      onClick = onNewSession,
      shape = RoundedCornerShape(6.dp),
      color = DarkSurfaceElevated,
      border = BorderStroke(1.dp, DarkBorderSubtle)
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(Icons.Default.Add, contentDescription = "New Tab", tint = TextSecondary, modifier = Modifier.size(12.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text("New Tab", color = TextSecondary, fontSize = 11.sp)
      }
    }
  }
}

/** Full-screen bootstrap progress shown while the bundled Debian rootfs is set up. */
@Composable
private fun BootstrapPane(state: LinuxEnvironmentState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier
      .background(DarkBackground)
      .padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp, alignment = Alignment.CenterVertically),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    val (title, detail) = when (state) {
      is LinuxEnvironmentState.NotBootstrapped -> "Preparing Linux environment" to "The bundled Debian-based rootfs will be extracted to app-private storage."
      is LinuxEnvironmentState.Verifying -> {
        val pct = if (state.totalBytes > 0) (state.bytesChecked * 100 / state.totalBytes).coerceIn(0, 100) else 0
        "Verifying rootfs — $pct%" to "${formatBytes(state.bytesChecked)} of ${formatBytes(state.totalBytes)} checked"
      }
      is LinuxEnvironmentState.Extracting -> "Extracting rootfs (${state.entriesDone} files)" to state.currentPath
      is LinuxEnvironmentState.Configuring -> "Configuring environment" to state.detail
      is LinuxEnvironmentState.Failed -> "Setup failed" to state.reason
      is LinuxEnvironmentState.Ready -> "Ready" to ""
    }

    // Stage checklist so the user always knows where setup stands.
    val stageIndex = when (state) {
      is LinuxEnvironmentState.NotBootstrapped -> 0
      is LinuxEnvironmentState.Verifying -> 0
      is LinuxEnvironmentState.Extracting -> 1
      is LinuxEnvironmentState.Configuring -> 2
      is LinuxEnvironmentState.Ready -> 3
      is LinuxEnvironmentState.Failed -> 0
    }
    val stages = listOf("Verify", "Extract", "Configure")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
      stages.forEachIndexed { index, label ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = if (index < stageIndex) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = when {
              index < stageIndex -> TerminalGreen
              index == stageIndex && state !is LinuxEnvironmentState.Failed -> ElectricBlueGlow
              else -> TextMuted
            },
            modifier = Modifier.size(14.dp)
          )
          Spacer(modifier = Modifier.width(3.dp))
          Text(label, color = if (index == stageIndex) TextPrimary else TextMuted, fontSize = 11.sp)
        }
        if (index < stages.lastIndex) Text("—", color = TextMuted, fontSize = 11.sp)
      }
    }

    Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    if (detail.isNotBlank()) {
      Text(
        detail,
        color = TextSecondary,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center
      )
    }
    when (state) {
      is LinuxEnvironmentState.Verifying -> LinearProgressIndicator(
        progress = {
          if (state.totalBytes > 0) (state.bytesChecked.toFloat() / state.totalBytes).coerceIn(0f, 1f) else 0f
        },
        modifier = Modifier.fillMaxWidth(),
        color = ElectricBlue
      )
      is LinuxEnvironmentState.Extracting, is LinuxEnvironmentState.Configuring -> LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth(),
        color = ElectricBlue
      )
      is LinuxEnvironmentState.Failed -> Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)) {
        Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Retry", color = Color.White)
      }
      else -> Unit
    }
    Text(
      "Real apt, real dpkg, real shell — nothing is simulated.",
      color = TextMuted,
      fontSize = 10.sp,
      fontFamily = FontFamily.Monospace
    )
  }
}

private fun formatBytes(bytes: Long): String = when {
  bytes >= 1 shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
  bytes >= 1 shl 10 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
  else -> "$bytes B"
}

/** TerminalView client: raises the soft keyboard on tap; defaults elsewhere. */
private class ScoTerminalViewClient(private val view: TerminalView) : com.termux.view.TerminalViewClient {
  override fun onScale(scaleFactor: Float): Float = 1.0f
  override fun onSingleTapUp(e: MotionEvent?) {
    // TerminalView already requestFocus()es on tap; without an explicit
    // showSoftInput the keyboard never appears in a Compose AndroidView.
    view.requestFocus()
    val imm = view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
    imm?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
  }
  override fun shouldBackButtonBeMappedToEscape(): Boolean = false
  override fun shouldEnforceCharBasedInput(): Boolean = true
  override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
  override fun isTerminalViewSelected(): Boolean = true
  override fun copyModeChanged(copyMode: Boolean) {}
  override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
  override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
  override fun onLongPress(event: MotionEvent?): Boolean = false
  override fun readControlKey(): Boolean = false
  override fun readAltKey(): Boolean = false
  override fun readShiftKey(): Boolean = false
  override fun readFnKey(): Boolean = false
override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
  // Handle Ctrl sequences properly
  if (ctrlDown) {
    // Handle common control sequences that need specific values
    when (codePoint) {
      'c'.code -> {
        session?.write(byteArrayOf(0x03), 0, 1) // Ctrl+C = SIGINT
        return true
      }
      'z'.code -> {
        session?.write(byteArrayOf(0x1a), 0, 1) // Ctrl+Z = SIGTSTP
        return true
      }
      'd'.code -> {
        session?.write(byteArrayOf(0x04), 0, 1) // Ctrl+D = EOF
        return true
      }
      's'.code -> {
        session?.write(byteArrayOf(0x13), 0, 1) // Ctrl+S = XOFF
        return true
      }
      'q'.code -> {
        session?.write(byteArrayOf(0x11), 0, 1) // Ctrl+Q = XON
        return true
      }
      '['.code -> {
        session?.write(byteArrayOf(0x1b), 0, 1) // Ctrl+[
        return true
      }
      '\\'.code -> {
        session?.write(byteArrayOf(0x1c), 0, 1) // Ctrl+\
        return true
      }
      ']'.code -> {
        session?.write(byteArrayOf(0x1d), 0, 1) // Ctrl+]
        return true
      }
      '^'.code -> {
        session?.write(byteArrayOf(0x1e), 0, 1) // Ctrl+^
        return true
      }
      '_'.code -> {
        session?.write(byteArrayOf(0x1f), 0, 1) // Ctrl+_
        return true
      }
      '?'.code -> {
        session?.write(byteArrayOf(0x7f), 0, 1) // Ctrl+? (Delete)
        return true
      }
      else -> {
        // Standard Ctrl behavior: mask with 0x1f
        val ctrlCode = codePoint and 0x1f
        if (ctrlCode != codePoint) { // Only if it's actually a control character
          session?.write(byteArrayOf(ctrlCode.toByte()), 0, 1)
          return true
        }
      }
    }
  }
  return false
}
  override fun onEmulatorSet() {}
  override fun logError(tag: String?, message: String?) {}
  override fun logWarn(tag: String?, message: String?) {}
  override fun logInfo(tag: String?, message: String?) {}
  override fun logDebug(tag: String?, message: String?) {}
  override fun logVerbose(tag: String?, message: String?) {}
  override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
  override fun logStackTrace(tag: String?, e: Exception?) {}
}

@Composable
private fun TerminalExtraKeysGrid(
  onKey: (String, Boolean) -> Unit,
  modifier: Modifier = Modifier
) {
  var ctrlActive by remember { mutableStateOf(false) }
  
  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(DarkSurface)
      .padding(horizontal = 4.dp, vertical = 4.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp)
  ) {
    TermuxKeyRows.forEach { row ->
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(40.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        row.forEach { key ->
          val isCtrlKey = key == "CTRL"
          val isActive = isCtrlKey && ctrlActive
          
          Box(
            modifier = Modifier
              .weight(1f)
              .fillMaxHeight()
              .clip(RoundedCornerShape(6.dp))
              .background(if (isActive) ElectricBlue.copy(alpha = 0.3f) else DarkBackground)
              .border(1.dp, if (isActive) ElectricBlue else DarkBorderSubtle, RoundedCornerShape(6.dp))
              .clickable { 
                if (isCtrlKey) {
                  ctrlActive = !ctrlActive
                } else {
                  onKey(key, ctrlActive)
                  // Reset CTRL state after any key press when CTRL is active
                  if (ctrlActive) {
                    ctrlActive = false
                  }
                }
              }
              .testTag("term_key_$key"),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = key,
              color = TextCode,
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Medium
            )
          }
        }
      }
    }
  }
}
