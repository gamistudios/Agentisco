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
  listOf("ESC", "/", "HOME", "↑", "END", "PGUP"),
  listOf("TAB", "-", "←", "↓", "→", "PGDN")
)

private fun keyBytes(key: String): ByteArray = when (key) {
  "ESC" -> byteArrayOf(0x1b)
  "TAB" -> byteArrayOf(0x09)
  "/" -> byteArrayOf('/'.code.toByte())
  "-" -> byteArrayOf('-'.code.toByte())
  "↑" -> "\u001b[A".toByteArray()
  "↓" -> "\u001b[B".toByteArray()
  "→" -> "\u001b[C".toByteArray()
  "←" -> "\u001b[D".toByteArray()
  "HOME" -> "\u001b[H".toByteArray()
  "END" -> "\u001b[F".toByteArray()
  "PGUP" -> "\u001b[5~".toByteArray()
  "PGDN" -> "\u001b[6~".toByteArray()
  else -> key.toByteArray()
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

        AndroidView(
          factory = { ctx ->
            TerminalView(ctx, null).apply {
              setTerminalViewClient(ScoTerminalViewClient())
            }
          },
          update = { view ->
            val session = activePty
            if (session != null && session !== attachedSession) {
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
        TerminalExtraKeysGrid(onKey = { key -> activePty?.let { pty ->
          val bytes = keyBytes(key)
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
            text = "Debian Linux Terminal (proot)",
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
          )
        }
        Text(
          text = currentDirLabel,
          color = TextMuted,
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
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

/** Full-screen bootstrap progress shown while the Debian rootfs is being set up. */
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
      is LinuxEnvironmentState.NotBootstrapped -> "Preparing Linux environment" to "The Debian-based rootfs will be downloaded once and stored in app-private storage."
      is LinuxEnvironmentState.Downloading -> {
        val pct = if (state.totalBytes > 0) (state.bytesSoFar * 100 / state.totalBytes).coerceIn(0, 100) else 0
        "Downloading Debian rootfs — $pct%" to "${formatBytes(state.bytesSoFar)} of ${formatBytes(state.totalBytes)}"
      }
      is LinuxEnvironmentState.Extracting -> "Extracting rootfs (${state.entriesDone} files)" to state.currentPath
      is LinuxEnvironmentState.Configuring -> "Configuring environment" to state.detail
      is LinuxEnvironmentState.Failed -> "Bootstrap failed" to state.reason
      is LinuxEnvironmentState.Ready -> "Ready" to ""
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
    if (state is LinuxEnvironmentState.Downloading) {
      LinearProgressIndicator(
        progress = {
          if (state.totalBytes > 0) (state.bytesSoFar.toFloat() / state.totalBytes).coerceIn(0f, 1f) else 0f
        },
        modifier = Modifier.fillMaxWidth(),
        color = ElectricBlue
      )
    }
    if (state is LinuxEnvironmentState.Failed) {
      Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)) {
        Text("Retry", color = Color.White)
      }
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

/** Minimal TerminalView client: default behaviors, no custom key handling. */
private class ScoTerminalViewClient : com.termux.view.TerminalViewClient {
  override fun onScale(scaleFactor: Float): Float = 1.0f
  override fun onSingleTapUp(e: MotionEvent?) {}
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
  override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false
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
  onKey: (String) -> Unit,
  modifier: Modifier = Modifier
) {
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
          Box(
            modifier = Modifier
              .weight(1f)
              .fillMaxHeight()
              .clip(RoundedCornerShape(6.dp))
              .background(DarkBackground)
              .border(1.dp, DarkBorderSubtle, RoundedCornerShape(6.dp))
              .clickable { onKey(key) }
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
