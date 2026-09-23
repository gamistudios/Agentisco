package com.agentisco.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.agentisco.AgentiscoApplication
import com.agentisco.ui.theme.DangerRed
import com.agentisco.ui.theme.DarkBorder
import com.agentisco.ui.theme.DarkSurface
import com.agentisco.ui.theme.DarkSurfaceElevated
import com.agentisco.ui.theme.ElectricBlue
import com.agentisco.ui.theme.TextPrimary
import com.agentisco.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Debug-only crash inspector.
 *
 * The app's [AgentiscoApplication] uncaught-exception handler persists every
 * hard crash to `files/scoos-last-crash.txt` before the process dies. This
 * dialog reads that file back on the next launch and hands the user the full
 * stack trace — copy it, or export it as a `.txt` through the system share
 * sheet — so a crash seen out in the field can be diagnosed without logcat.
 *
 * It dismisses itself when there is nothing to show, so debug callers can
 * hoist a plain boolean and leave it mounted.
 */
@Composable
fun CrashLogDialog(
  visible: Boolean,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier
) {
  if (!visible) return

  val context = LocalContext.current
  val app = remember { context.applicationContext as AgentiscoApplication }
  val clipboard = LocalClipboardManager.current
  val scope = rememberCoroutineScope()

  var crashLog by remember { mutableStateOf<String?>(null) }
  var logLoaded by remember { mutableStateOf(false) }

  LaunchedEffect(visible) {
    crashLog = withContext(Dispatchers.IO) { app.readLastCrashLog() }
    logLoaded = true
  }

  // Nothing crashed (or the log was already cleared): get out of the way.
  if (logLoaded && crashLog == null) {
    onDismiss()
    return
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = modifier,
    icon = { Icon(Icons.Outlined.BugReport, contentDescription = null, tint = DangerRed) },
    title = { Text("Crash Log (debug)", color = TextPrimary, fontWeight = FontWeight.Bold) },
    text = {
      Column {
        Text(
          "The app captured an uncaught exception from its last run. " +
            "Copy or export the trace below to inspect why it crashed.",
          color = TextSecondary,
          fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Box(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 340.dp)
            .background(DarkSurfaceElevated)
            .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
        ) {
          if (!logLoaded) {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .height(90.dp),
              contentAlignment = Alignment.Center
            ) {
              CircularProgressIndicator(color = ElectricBlue, strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
            }
          } else {
            Text(
              text = crashLog ?: "",
              color = TextPrimary,
              fontSize = 10.sp,
              lineHeight = 14.sp,
              fontFamily = FontFamily.Monospace,
              modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(10.dp)
            )
          }
        }
      }
    },
    confirmButton = {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Export the trace as a real file handed to the system share sheet, so
        // it can be saved to Downloads, mailed, or attached to an issue.
        OutlinedButton(
          onClick = {
            val log = crashLog ?: return@OutlinedButton
            scope.launch {
              val uri = withContext(Dispatchers.IO) {
                runCatching { exportCrashLog(context, log) }.getOrNull()
              }
              if (uri != null) {
                val share = Intent(Intent.ACTION_SEND).apply {
                  type = "text/plain"
                  putExtra(Intent.EXTRA_SUBJECT, "ScoOS crash log")
                  putExtra(Intent.EXTRA_STREAM, uri)
                  addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching {
                  context.startActivity(Intent.createChooser(share, "Save / share crash log"))
                }
              } else {
                Toast.makeText(context, "Could not export crash log", Toast.LENGTH_SHORT).show()
              }
            }
          },
          enabled = logLoaded,
          border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
        ) {
          Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.height(14.dp), tint = TextSecondary)
          Spacer(modifier = Modifier.width(4.dp))
          Text("Download", fontSize = 12.sp, color = TextPrimary)
        }

        Button(
          onClick = {
            clipboard.setText(AnnotatedString(crashLog ?: ""))
            Toast.makeText(context, "Crash log copied", Toast.LENGTH_SHORT).show()
          },
          enabled = logLoaded,
          colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
        ) {
          Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.height(14.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("Copy", fontSize = 12.sp)
        }
      }
    },
    dismissButton = {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Throw the trace away so it stops popping up on every cold start.
        OutlinedButton(onClick = {
          scope.launch {
            withContext(Dispatchers.IO) { app.clearLastCrashLog() }
            onDismiss()
          }
        }) {
          Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.height(14.dp), tint = DangerRed)
          Spacer(modifier = Modifier.width(4.dp))
          Text("Clear", fontSize = 12.sp, color = DangerRed)
        }

        OutlinedButton(onClick = onDismiss) {
          Text("Close", fontSize = 12.sp, color = TextSecondary)
        }
      }
    },
    containerColor = DarkSurface,
    shape = RoundedCornerShape(12.dp)
  )
}

/**
 * Stages the crash trace in app-specific external storage (already covered by
 * the workspace FileProvider) and returns a content:// URI the share sheet can
 * hand to other apps — app-specific external storage needs no runtime
 * permission, unlike a public Downloads/ write.
 */
private fun exportCrashLog(context: android.content.Context, log: String): android.net.Uri {
  @Suppress("DEPRECATION")
  val dir = context.getExternalFilesDir("crash-logs")
    ?: File(context.filesDir, "crash-logs").also { it.mkdirs() }
  val stamp = android.text.format.DateFormat.format("yyyy-MM-dd_HH-mm-ss", java.util.Date())
  val outFile = File(dir, "scoos-crash-$stamp.txt")
  outFile.writeText(log)
  return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
}
