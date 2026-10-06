package com.awaki.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.awaki.agent.model.AgentRole
import com.awaki.agent.model.AgentRoles
import com.awaki.ui.WorkspaceViewModel
import com.awaki.ui.theme.AwakiTheme

/**
 * Who the agent can hand work to, and the user's own specialists.
 *
 * The built-in seats are the app's and are only shown here: what a user can add
 * is a seat of their own, which `delegate` can name as soon as it is saved.
 *
 * Rendered inside Settings' "Agent team" sheet, so it carries no card of its own —
 * the sheet is already the container.
 */
@Composable
fun AgentTeamSection(viewModel: WorkspaceViewModel, modifier: Modifier = Modifier) {
  val customAgents by viewModel.customAgents.collectAsState()
  var editorTarget by remember { mutableStateOf<AgentRole?>(null) }
  var editorOpen by remember { mutableStateOf(false) }
  var deleting by remember { mutableStateOf<AgentRole?>(null) }

  Column(modifier = modifier.fillMaxWidth()) {
    Text("Built in", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    Spacer(modifier = Modifier.height(6.dp))
    AgentRoles.builtIn.forEach { role -> AgentRoleRow(role) }

    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(12.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text("My agents", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
      TextButton(
        onClick = { editorTarget = null; editorOpen = true },
        modifier = Modifier.testTag("btn_new_agent")
      ) {
        Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text("New agent", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
      }
    }

    if (customAgents.isEmpty()) {
      Text(
        "No agents of your own yet. A specialist you define is delegated to exactly like a " +
          "built-in one - say \"have the DB reviewer check my migration\" and it will be used.",
        color = AwakiTheme.extra.textMuted,
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
      containerColor = MaterialTheme.colorScheme.surface,
      title = { Text("Remove ${role.name}?", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp) },
      text = {
        Text(
          "The agent will no longer be one of the seats this workspace can delegate to. " +
            "Conversations that already used it keep their reports.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        ) { Text("Remove", color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
      },
      dismissButton = {
        TextButton(onClick = { deleting = null }) { Text("Keep", color = AwakiTheme.extra.textMuted, fontSize = 13.sp) }
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
      Text(role.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
      Spacer(modifier = Modifier.width(6.dp))
      if (role.readOnly) RoleBadge("read-only", AwakiTheme.extra.success)
      if (role.modelId.isNotBlank()) RoleBadge("own model", MaterialTheme.colorScheme.primary)
      if (role.toolNames.isNotEmpty()) RoleBadge("${role.toolNames.size} tools", MaterialTheme.colorScheme.onSurfaceVariant)
      Spacer(modifier = Modifier.weight(1f))
      if (onEdit != null) {
        IconButton(onClick = onEdit, modifier = Modifier.size(28.dp).testTag("btn_edit_agent_${role.id}")) {
          Icon(Icons.Default.Edit, contentDescription = "Edit", tint = AwakiTheme.extra.textMuted, modifier = Modifier.size(14.dp))
        }
      }
      if (onDelete != null) {
        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp).testTag("btn_delete_agent_${role.id}")) {
          Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
        }
      }
    }
    Text(
      role.purpose,
      color = AwakiTheme.extra.textMuted,
      fontSize = 11.sp,
      lineHeight = 14.sp,
      modifier = Modifier.padding(top = 2.dp)
    )
    if (role.scope.isNotBlank()) {
      Text(
        "Works in: ${role.scope}",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
      .background(MaterialTheme.colorScheme.background)
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
      .padding(horizontal = 5.dp, vertical = 1.dp)
  ) { Text(label, color = tint, fontSize = 9.sp) }
}
