package com.agentisco.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.agentisco.agent.model.PendingApproval
import com.agentisco.ui.theme.*

@Composable
fun ApprovalDialog(
  approval: PendingApproval,
  onResolve: (allowed: Boolean, answer: String?) -> Unit
) {
  val freeText = remember { mutableStateOf("") }

  Dialog(onDismissRequest = { onResolve(false, null) }) {
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
            text = if (approval.isQuestion) approval.command else "$ ${approval.command}",
            color = when {
              approval.isQuestion -> TextPrimary
              approval.isDestructive -> DangerRed
              else -> ElectricBlueGlow
            },
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (approval.impactDescription != approval.command) {
          Text(
            text = approval.impactDescription,
            color = TextSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp
          )
        }

        Spacer(modifier = Modifier.height(20.dp))

        when {
          // A question: the model asked the user to choose, not to approve.
          approval.isQuestion -> Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            approval.options.forEachIndexed { index, option ->
              Button(
                onClick = { onResolve(true, option) },
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("dialog_answer_option_$index"),
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
              ) {
                Text(option, fontSize = 13.sp, fontWeight = FontWeight.Medium)
              }
            }

            if (approval.allowFreeText) {
              OutlinedTextField(
                value = freeText.value,
                onValueChange = { freeText.value = it },
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("dialog_answer_free_text"),
                placeholder = { Text("Type your own answer…", fontSize = 12.sp) },
                textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                  focusedBorderColor = ElectricBlue,
                  unfocusedBorderColor = DarkBorder,
                  cursorColor = ElectricBlueGlow,
                  focusedLabelColor = TextSecondary,
                  unfocusedLabelColor = TextMuted
                )
              )
              Button(
                onClick = { onResolve(true, freeText.value.trim().ifBlank { null }) },
                enabled = freeText.value.isNotBlank(),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("dialog_answer_submit"),
                colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
              ) {
                Text("Send answer", fontSize = 13.sp, fontWeight = FontWeight.Medium)
              }
            }

            TextButton(
              onClick = { onResolve(false, null) },
              modifier = Modifier
                .fillMaxWidth()
                .testTag("dialog_answer_skip")
            ) {
              Text("Don't answer", color = TextMuted, fontSize = 12.sp)
            }
          }

          approval.isDestructive -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            OutlinedButton(
              onClick = { onResolve(false, null) },
              modifier = Modifier
                .weight(1f)
                .testTag("dialog_cancel_destructive"),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
              border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
              Text("Cancel", fontSize = 12.sp)
            }

            Button(
              onClick = { onResolve(true, null) },
              modifier = Modifier
                .weight(1f)
                .testTag("dialog_allow_destructive"),
              colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
            ) {
              Text("Allow", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
          }

          else -> Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Button(
              onClick = { onResolve(true, null) },
              modifier = Modifier
                .fillMaxWidth()
                .testTag("dialog_allow_once"),
              colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
            ) {
              Text("Allow once", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }

            OutlinedButton(
              onClick = { onResolve(true, null) },
              modifier = Modifier
                .fillMaxWidth()
                .testTag("dialog_allow_session"),
              colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
              border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
              Text("Allow for this session", fontSize = 12.sp)
            }

            TextButton(
              onClick = { onResolve(false, null) },
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
