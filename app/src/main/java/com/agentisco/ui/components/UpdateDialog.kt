package com.agentisco.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.agentisco.data.repository.UpdateRepository
import com.agentisco.ui.theme.*

/**
 * Dialog that notifies about an available update and drives download/install
 * actions. When [isDownloading] is true it shows live progress.
 */
@Composable
fun UpdateDialog(
    availableVersion: String,
    releaseNotes: String,
    updateState: UpdateRepository.UpdateState,
    progress: Float,
    error: String?,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancelDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(DarkSurface, RoundedCornerShape(16.dp))
                .border(1.dp, DarkBorder, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Text(
                "Update Available",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Version $availableVersion is ready to download.",
                color = TextSecondary,
                fontSize = 12.sp
            )

            if (releaseNotes.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp)
                        .background(DarkSurfaceElevated, RoundedCornerShape(8.dp))
                        .border(1.dp, DarkBorderSubtle, RoundedCornerShape(8.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp)
                ) {
                    Text(
                        releaseNotes,
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            when (updateState) {
                UpdateRepository.UpdateState.DOWNLOADING -> {
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = ElectricBlue,
                        trackColor = DarkSurfaceHighlight
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Downloading… ${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                    Text(
                        "You can hide this and keep working — progress shows in the header.",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onCancelDownload) {
                            Text("Cancel download", color = DangerRed)
                        }
                        Spacer(Modifier.width(6.dp))
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceElevated)
                        ) {
                            Text("Hide", color = TextPrimary, fontSize = 13.sp)
                        }
                    }
                }
                UpdateRepository.UpdateState.DOWNLOADED -> {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Later", color = TextSecondary)
                        }
                        Spacer(Modifier.width(6.dp))
                        Button(
                            onClick = onInstall,
                            colors = ButtonDefaults.buttonColors(containerColor = TerminalGreen)
                        ) {
                            Text("Install", color = DarkBackground, fontSize = 13.sp)
                        }
                    }
                }
                else -> {
                    if (error != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(error, color = DangerRed, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Later", color = TextSecondary)
                        }
                        Spacer(Modifier.width(6.dp))
                        Button(
                            onClick = onDownload,
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                        ) {
                            Text("Download", color = TextPrimary, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
