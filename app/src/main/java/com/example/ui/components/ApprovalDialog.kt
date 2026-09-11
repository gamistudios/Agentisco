package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.PendingApproval
import com.example.ui.theme.*

@Composable
fun ApprovalDialog(
  approval: PendingApproval,
  onResolve: (Boolean) -> Unit
) {
  Dialog(onDismissRequest = { onResolve(false) }) {
    Surface(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .border(1.dp, if (approval.isDestructive) DangerRed.copy(alpha = 0.5f) else DarkBorder, RoundedCornerShape(16.dp)),
      color = DarkSurface,
      tonalElevation = 10.dp
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(20.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (approval.isDestructive) {
            Icon(
              imageVector = Icons.Default.Warning,
              contentDescription = "Warning",
              tint = DangerRed,
              modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "⚠ Destructive command",
              color = DangerRed,
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold
            )
          } else {
            Text(
              text = approval.title,
              color = TextPrimary,
              fontSize = 14.sp,
              fontWeight = FontWeight.SemiBold
            )
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Command display box
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DarkBackground)
            .border(1.dp, DarkBorderSubtle, RoundedCornerShape(8.dp))
            .padding(12.dp)
        ) {
          Text(
            text = "$ ${approval.command}",
            color = if (approval.isDestructive) DangerRed else ElectricBlueGlow,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
          text = approval.impactDescription,
          color = TextSecondary,
          fontSize = 12.sp,
          lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        if (approval.isDestructive) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            OutlinedButton(
              onClick = { onResolve(false) },
              modifier = Modifier
                .weight(1f)
                .testTag("dialog_cancel_destructive"),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
              border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
              Text("Cancel", fontSize = 12.sp)
            }

            Button(
              onClick = { onResolve(true) },
              modifier = Modifier
                .weight(1f)
                .testTag("dialog_allow_destructive"),
              colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
            ) {
              Text("Allow", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
          }
        } else {
          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Button(
              onClick = { onResolve(true) },
              modifier = Modifier
                .fillMaxWidth()
                .testTag("dialog_allow_once"),
              colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
            ) {
              Text("Allow once", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }

            OutlinedButton(
              onClick = { onResolve(true) },
              modifier = Modifier
                .fillMaxWidth()
                .testTag("dialog_allow_session"),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
              border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
              Text("Allow for this session", fontSize = 12.sp)
            }

            TextButton(
              onClick = { onResolve(false) },
              modifier = Modifier
                .fillMaxWidth()
                .testTag("dialog_deny")
            ) {
              Text("Deny", color = TextMuted, fontSize = 12.sp)
            }
          }
        }
      }
    }
  }
}
