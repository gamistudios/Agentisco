package com.awaki.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.awaki.agent.model.AgentRole
import com.awaki.agent.model.AgentRoles
import com.awaki.ui.WorkspaceViewModel
import com.awaki.ui.theme.DarkBackground
import com.awaki.ui.theme.DarkBorder
import com.awaki.ui.theme.DarkBorderSubtle
import com.awaki.ui.theme.DarkSurface
import com.awaki.ui.theme.DarkSurfaceElevated
import com.awaki.ui.theme.DarkSurfaceHighlight
import com.awaki.ui.theme.ElectricBlue
import com.awaki.ui.theme.TextMuted
import com.awaki.ui.theme.TextPrimary
import com.awaki.ui.theme.TextSecondary

/**
 * Define a specialist the agent can delegate to.
 *
 * What is written here is what the run receives, unchanged apart from the team
 * rules it cannot opt out of - so the brief has to be the whole brief. The tool
 * checklist is the list a delegated run may actually hold, which is why an empty
 * selection means "all of them" rather than "none".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentEditorDialog(
  original: AgentRole?,
  viewModel: WorkspaceViewModel,
  onDismiss: () -> Unit,
  onSaved: () -> Unit
) {
  val models by viewModel.aiModels.collectAsState()

  var name by remember { mutableStateOf(original?.name ?: "") }
  var purpose by remember { mutableStateOf(original?.purpose ?: "") }
  var systemPrompt by remember { mutableStateOf(original?.systemPrompt ?: "") }
  var scope by remember { mutableStateOf(original?.scope ?: "") }
  var modelId by remember { mutableStateOf(original?.modelId ?: "") }
  var readOnly by remember { mutableStateOf(original?.readOnly ?: false) }
  var tools by remember { mutableStateOf(original?.toolNames?.toSet() ?: emptySet()) }
  var modelMenuOpen by remember { mutableStateOf(false) }

  val reachable = viewModel.delegableToolNames(readOnly)
  // A tool that the new mode cannot grant is no longer offered, so the checklist
  // keeps showing only what the run will really be given.
  val selected = tools.intersect(reachable.toSet())
  val valid = name.isNotBlank() && systemPrompt.isNotBlank()

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = DarkSurface,
    shape = RoundedCornerShape(16.dp),
    title = {
      Text(
        if (original == null) "New agent" else "Edit ${original.name}",
        color = TextPrimary,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold
      )
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 480.dp)
          .verticalScroll(rememberScrollState())
      ) {
        AgentField(
          value = name,
          onValueChange = { name = it },
          label = "Name",
          placeholder = "e.g. Migration Reviewer",
          testTag = "input_agent_name",
          singleLine = true
        )
        AgentField(
          value = purpose,
          onValueChange = { purpose = it },
          label = "One-line purpose",
          placeholder = "Shown to the main agent when it decides who to ask",
          testTag = "input_agent_purpose",
          singleLine = true
        )
        AgentField(
          value = systemPrompt,
          onValueChange = { systemPrompt = it },
          label = "System prompt",
          placeholder =
            "Who this agent is and how it works. It sees the project, the tools you " +
              "check below and the other agents, and it must report back when done.",
          testTag = "input_agent_prompt",
          minLines = 5
        )
        AgentField(
          value = scope,
          onValueChange = { scope = it },
          label = "Scope (optional)",
          placeholder = "e.g. app/src/main/java and the Room schemas",
          testTag = "input_agent_scope",
          singleLine = true
        )

        Spacer(modifier = Modifier.height(10.dp))
        Text("Model", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Box {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .border(1.dp, DarkBorder, RoundedCornerShape(8.dp))
              .clickable { modelMenuOpen = true }
              .padding(horizontal = 12.dp, vertical = 10.dp)
              .testTag("btn_agent_model"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            val chosen = models.firstOrNull { it.id == modelId || it.modelId == modelId }
            Text(
              chosen?.displayName ?: "Use my current model",
              color = if (chosen == null) TextMuted else TextPrimary,
              fontSize = 13.sp
            )
            Text("▾", color = TextMuted, fontSize = 11.sp)
          }
          DropdownMenu(
            expanded = modelMenuOpen,
            onDismissRequest = { modelMenuOpen = false },
            containerColor = DarkSurfaceElevated,
            modifier = Modifier.heightIn(max = 300.dp)
          ) {
            DropdownMenuItem(
              text = { Text("Use my current model", color = TextSecondary, fontSize = 13.sp) },
              onClick = { modelId = ""; modelMenuOpen = false }
            )
            models.forEach { model ->
              DropdownMenuItem(
                text = {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    if (model.id == modelId) {
                      Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = ElectricBlue,
                        modifier = Modifier.size(14.dp)
                      )
                      Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(model.displayName, color = TextPrimary, fontSize = 13.sp)
                  }
                },
                modifier = Modifier.testTag("menu_agent_model_${model.id}"),
                onClick = { modelId = model.id; modelMenuOpen = false }
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Read-only", color = TextPrimary, fontSize = 13.sp)
            Text(
              "Can look and propose, never change a file or run a command.",
              color = TextMuted,
              fontSize = 11.sp,
              lineHeight = 14.sp
            )
          }
          Switch(
            checked = readOnly,
            onCheckedChange = { readOnly = it },
            modifier = Modifier.testTag("switch_agent_readonly"),
            colors = SwitchDefaults.colors(
              checkedThumbColor = ElectricBlue,
              checkedTrackColor = ElectricBlue.copy(alpha = 0.35f),
              checkedBorderColor = ElectricBlue,
              uncheckedThumbColor = TextSecondary,
              uncheckedTrackColor = DarkSurfaceHighlight,
              uncheckedBorderColor = DarkBorder
            )
          )
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = DarkBorderSubtle)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
          if (selected.isEmpty()) "Tools - all a specialist may have" else "Tools - ${selected.size} chosen",
          color = TextSecondary,
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          if (selected.isEmpty())
            "Nothing checked means everything it is allowed to use. Check a few to narrow it down - " +
              "a delegated agent can never be given more than this list, whatever it is asked for."
          else
            "This agent will only ever be given the checked tools.",
          color = TextMuted,
          fontSize = 11.sp,
          lineHeight = 14.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          reachable.forEach { tool ->
            val on = selected.contains(tool)
            Text(
              tool,
              color = if (on) ElectricBlue else TextMuted,
              fontSize = 11.sp,
              modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, if (on) ElectricBlue else DarkBorder, RoundedCornerShape(6.dp))
                .background(if (on) ElectricBlue.copy(alpha = 0.12f) else DarkBackground)
                .clickable {
                  tools = if (on) selected - tool else selected + tool
                }
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag("chip_agent_tool_$tool")
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          viewModel.saveCustomAgent(
            AgentRoles.custom(
              id = original?.id ?: "",
              name = name.trim(),
              purpose = purpose.trim(),
              systemPrompt = systemPrompt,
              scope = scope.trim(),
              toolNames = selected.sorted(),
              modelId = modelId,
              readOnly = readOnly
            )
          )
          onSaved()
        },
        enabled = valid,
        modifier = Modifier.testTag("btn_save_agent")
      ) { Text("Save", color = if (valid) ElectricBlue else TextMuted, fontSize = 13.sp) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.testTag("btn_cancel_agent")) {
        Text("Cancel", color = TextMuted, fontSize = 13.sp)
      }
    }
  )
}

@Composable
internal fun AgentField(
  value: String,
  onValueChange: (String) -> Unit,
  label: String,
  placeholder: String,
  testTag: String,
  singleLine: Boolean = false,
  minLines: Int = 1
) {
  Column(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
    Text(label, color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    Spacer(modifier = Modifier.height(4.dp))
    OutlinedTextField(
      value = value,
      onValueChange = onValueChange,
      placeholder = { Text(placeholder, color = TextMuted, fontSize = 12.sp) },
      singleLine = singleLine,
      minLines = minLines,
      modifier = Modifier.fillMaxWidth().testTag(testTag),
      textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = TextPrimary),
      shape = RoundedCornerShape(8.dp),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = ElectricBlue,
        unfocusedBorderColor = DarkBorder,
        focusedContainerColor = DarkBackground,
        unfocusedContainerColor = DarkBackground
      )
    )
  }
}
