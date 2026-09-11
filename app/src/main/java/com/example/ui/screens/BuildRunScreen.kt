package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppDestination
import com.example.ui.WorkspaceViewModel
import com.example.ui.theme.*

@Composable
fun BuildRunScreen(
  viewModel: WorkspaceViewModel,
  onNavigate: (AppDestination) -> Unit,
  modifier: Modifier = Modifier
) {
  val activeProject by viewModel.activeProject.collectAsState()
  val isDevRunning by viewModel.isDevServerRunning.collectAsState()

  var isBuildingApk by remember { mutableStateOf(false) }
  var apkBuildProgress by remember { mutableFloatStateOf(0f) }
  var apkBuildComplete by remember { mutableStateOf(false) }
  var showPreviewModal by remember { mutableStateOf(false) }

  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .background(DarkBackground)
      .padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    item {
      Spacer(modifier = Modifier.height(10.dp))
      Text("Run & Build Center", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
      Text(
        text = "${activeProject.name} deployment & live runtime",
        color = TextMuted,
        fontSize = 12.sp
      )
    }

    // Dev Server Card
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .border(1.dp, if (isDevRunning) TerminalGreen.copy(alpha = 0.5f) else DarkBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Box(
                modifier = Modifier
                  .size(10.dp)
                  .clip(CircleShape)
                  .background(if (isDevRunning) TerminalGreen else TextMuted)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = if (isDevRunning) "Dev Server Running" else "Dev Server Stopped",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
              )
            }

            Switch(
              checked = isDevRunning,
              onCheckedChange = { viewModel.toggleDevServer() },
              colors = SwitchDefaults.colors(
                checkedThumbColor = TerminalGreen,
                checkedTrackColor = TerminalGreenBg
              ),
              modifier = Modifier.testTag("switch_dev_server")
            )
          }

          Spacer(modifier = Modifier.height(6.dp))

          Text(
            text = "http://localhost:5173",
            color = CyanAccent,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
          )

          Spacer(modifier = Modifier.height(10.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            Button(
              onClick = { showPreviewModal = true },
              enabled = isDevRunning,
              modifier = Modifier
                .weight(1f)
                .height(38.dp)
                .testTag("btn_open_preview"),
              colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
              shape = RoundedCornerShape(8.dp)
            ) {
              Icon(Icons.Outlined.Preview, contentDescription = "Preview", modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Open Preview", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            OutlinedButton(
              onClick = { onNavigate(AppDestination.TERMINAL) },
              modifier = Modifier
                .weight(1f)
                .height(38.dp),
              border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
              shape = RoundedCornerShape(8.dp)
            ) {
              Icon(Icons.Outlined.Terminal, contentDescription = "Terminal", modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("View Logs", fontSize = 12.sp, color = TextPrimary)
            }
          }
        }
      }
    }

    // Automated Tests Summary Card
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, contentDescription = "Tests", tint = TerminalGreen, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column {
              Text("Unit & Component Tests", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
              Text("42 tests passed · 0 failed · 1.4s", color = TextSecondary, fontSize = 11.sp)
            }
          }

          OutlinedButton(
            onClick = {
              viewModel.executeTerminalCommand("npm test")
              onNavigate(AppDestination.TERMINAL)
            },
            modifier = Modifier.height(30.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
          ) {
            Text("Re-run", fontSize = 11.sp, color = TextSecondary)
          }
        }
      }
    }

    // Android APK / Bundle Build Center (Section 27)
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .border(1.dp, DarkBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = DarkSurface)
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Outlined.Android, contentDescription = "Android", tint = TerminalGreen, modifier = Modifier.size(22.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Column {
                Text("Build Target: Android APK", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Release APK & Live Device Install", color = TextMuted, fontSize = 11.sp)
              }
            }
          }

          Spacer(modifier = Modifier.height(10.dp))

          if (isBuildingApk) {
            LinearProgressIndicator(
              progress = { apkBuildProgress },
              modifier = Modifier.fillMaxWidth(),
              color = ElectricBlueGlow,
              trackColor = DarkSurfaceElevated
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text("Agent monitoring build steps...", color = ElectricBlueGlow, fontSize = 11.sp)
          } else if (apkBuildComplete) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(TerminalGreenBg.copy(alpha = 0.4f))
                .padding(10.dp)
            ) {
              Icon(Icons.Default.Check, contentDescription = "Built", tint = TerminalGreen, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text("app-release.apk ready (18.4 MB)", color = TerminalGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
          }

          Spacer(modifier = Modifier.height(12.dp))

          Button(
            onClick = {
              isBuildingApk = true
              apkBuildProgress = 0.2f
              apkBuildComplete = false
            },
            enabled = !isBuildingApk,
            modifier = Modifier
              .fillMaxWidth()
              .height(40.dp)
              .testTag("btn_build_apk"),
            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue),
            shape = RoundedCornerShape(8.dp)
          ) {
            Icon(Icons.Default.Build, contentDescription = "Build", modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (apkBuildComplete) "Rebuild APK" else "Build APK with Agent Monitoring", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
          }
        }
      }
    }

    item {
      Spacer(modifier = Modifier.height(24.dp))
    }
  }

  // Simulated Live App Preview Modal
  if (showPreviewModal) {
    AlertDialog(
      onDismissRequest = { showPreviewModal = false },
      containerColor = DarkSurface,
      title = {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(TerminalGreen))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Preview: localhost:5173", color = TextPrimary, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
          }
          IconButton(onClick = { showPreviewModal = false }, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextMuted)
          }
        }
      },
      text = {
        // Simulated App Viewport Frame
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(DarkBackground)
            .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
            .padding(14.dp)
        ) {
          Column {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text("ScoSpace Chat", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
              Text("● 3 Online", color = TerminalGreen, fontSize = 10.sp)
            }
            HorizontalDivider(color = DarkBorderSubtle, modifier = Modifier.padding(vertical = 8.dp))
            Text("Gladson: Hey team, check the new release!", color = TextSecondary, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Text("Agent: Verified chat state cache. All messages persisted.", color = CyanAccent, fontSize = 11.sp)
          }
        }
      },
      confirmButton = {
        Button(onClick = { showPreviewModal = false }, colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)) {
          Text("Done", fontSize = 12.sp)
        }
      }
    )
  }
}
