package com.agentisco.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentisco.agent.model.AgentRole
import com.agentisco.agent.model.AgentRoles
import com.agentisco.ui.WorkspaceViewModel
import com.agentisco.ui.theme.DarkBackground
import com.agentisco.ui.theme.DarkBorder
import com.agentisco.ui.theme.DarkBorderSubtle
import com.agentisco.ui.theme.DangerRed
import com.agentisco.ui.theme.DarkSurface
import com.agentisco.ui.theme.ElectricBlue
import com.agentisco.ui.theme.TextMuted
import com.agentisco.ui.theme.TextPrimary
import com.agentisco.ui.theme.TextSecondary

/**
 * Who the agent can hand work to, and the user's own specialists.
 *
 * The built-in seats are the app's and are only shown here: what a user can add
 * is a seat of their own, which `delegate` can name as soon as it is saved.
 */
@Composable
fun AgentTeamCard(viewModel: WorkspaceViewModel, modifier: Modifier = Modifier) {
  val customAgents by viewModel.customAgents.collectAsState()
  var editorTarget by remember { mutableStateOf<AgentRole?>(null) }
  var editorOpen by remember { mutableStateOf(false) }
  var deleting by remember { mutableStateOf<AgentRole?>(null) }

  Card(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .border(1.dp, DarkBorder, RoundedCornerShape(12.dp))
      .testTag("card_agent_team"),
    colors = CardDefaults.cardColors(containerColor = DarkSurface)
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Text("Agent Team", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        "A task that needs several kinds of work can be handed to a specialist instead of done " +
          "all at once. Each one gets its own brief, its own tools and its own slice of the " +
          "project, and reports back when it is done - so the main agent keeps the overview.",
        color = TextMuted,
        fontSize = 11.sp,
        lineHeight = 15.sp
      )
      Spacer(modifier = Modifier.height(12.dp))

      Text("Built in", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
      Spacer(modifier = Modifier.height(6.dp))
      AgentRoles.builtIn.forEach { role -> AgentRoleRow(role) }

      Spacer(modifier = Modifier.height(12.dp))
      HorizontalDivider(color = DarkBorderSubtle)
      Spacer(modifier = Modifier.height(12.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text("My agents", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        TextButton(
          onClick = { editorTarget = null; editorOpen = true },
          modifier = Modifier.testTag("btn_new_agent")
        ) {
          Icon(Icons.Default.Add, contentDescription = null, tint = ElectricBlue, modifier = Modifier.size(14.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("New agent", color = ElectricBlue, fontSize = 12.sp)
        }
      }

      if (customAgents.isEmpty()) {
        Text(
          "No agents of your own yet. A specialist you define is delegated to exactly like a " +
            "built-in one - say \"have the DB reviewer check my migration\" and it will be used.",
          color = TextMuted,
          fontSize = 11.sp,
          lineHeight = 15.sp,
          modifier = Modifier.testTag("txt_no_custom_agents")
        )
      } else {
        customAgents.forEach { role ->
          AgentRoleRow(
            role = role,
            onEdit = { editorTarget = role; editorOpen = true },
            onDelete = { deleting = role }
          )
        }
      }
    }
  }

  if (editorOpen) {
    AgentEditorDialog(
      original = editorTarget,
      viewModel = viewModel,
      onDismiss = { editorOpen = false },
      onSaved = { editorOpen = false }
    )
  }

  deleting?.let { role ->
    AlertDialog(
      onDismissRequest = { deleting = null },
      containerColor = DarkSurface,
      title = { Text("Remove ${role.name}?", color = TextPrimary, fontSize = 16.sp) },
      text = {
        Text(
          "The agent will no longer be one of the seats this workspace can delegate to. " +
            "Conversations that already used it keep their reports.",
          color = TextSecondary,
          fontSize = 12.sp
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            viewModel.deleteCustomAgent(role.id)
            deleting = null
          },
          modifier = Modifier.testTag("btn_confirm_delete_agent")
        ) { Text("Remove", color = DangerRed, fontSize = 13.sp) }
      },
      dismissButton = {
        TextButton(onClick = { deleting = null }) { Text("Keep", color = TextMuted, fontSize = 13.sp) }
      }
    )
  }
}

@Composable
private fun AgentRoleRow(
  role: AgentRole,
  onEdit: (() -> Unit)? = null,
  onDelete: (() -> Unit)? = null
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp)
      .testTag("row_agent_${role.id}")
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(role.name, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
      Spacer(modifier = Modifier.width(6.dp))
      if (role.readOnly) RoleBadge("read-only", TerminalGreenLabel)
      if (role.modelId.isNotBlank()) RoleBadge("own model", ElectricBlue)
      if (role.toolNames.isNotEmpty()) RoleBadge("${role.toolNames.size} tools", TextSecondary)
      Spacer(modifier = Modifier.weight(1f))
      if (onEdit != null) {
        IconButton(onClick = onEdit, modifier = Modifier.size(28.dp).testTag("btn_edit_agent_${role.id}")) {
          Icon(Icons.Default.Edit, contentDescription = "Edit", tint = TextMuted, modifier = Modifier.size(14.dp))
        }
      }
      if (onDelete != null) {
        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp).testTag("btn_delete_agent_${role.id}")) {
          Icon(Icons.Default.Delete, contentDescription = "Delete", tint = DangerRed, modifier = Modifier.size(14.dp))
        }
      }
    }
    Text(
      role.purpose,
      color = TextMuted,
      fontSize = 11.sp,
      lineHeight = 14.sp,
      modifier = Modifier.padding(top = 2.dp)
    )
    if (role.scope.isNotBlank()) {
      Text(
        "Works in: ${role.scope}",
        color = TextSecondary,
        fontSize = 10.sp,
        modifier = Modifier.padding(top = 2.dp)
      )
    }
  }
}

@Composable
private fun RoleBadge(label: String, tint: androidx.compose.ui.graphics.Color) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(DarkBackground)
      .border(1.dp, DarkBorderSubtle, RoundedCornerShape(4.dp))
      .padding(horizontal = 5.dp, vertical = 1.dp)
  ) { Text(label, color = tint, fontSize = 9.sp) }
}

private val TerminalGreenLabel = androidx.compose.ui.graphics.Color(0xFF10B981)
