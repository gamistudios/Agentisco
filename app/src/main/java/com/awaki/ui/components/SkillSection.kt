package com.awaki.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.awaki.agent.skill.AgentSkill
import com.awaki.agent.skill.SkillScope
import com.awaki.agent.skill.SkillStore
import com.awaki.ui.WorkspaceViewModel
import com.awaki.ui.theme.AwakiTheme

/**
 * The know-how this workspace can hand the agent.
 *
 * A skill is a file, so this is a view of folders rather than of a database: what is
 * listed is what is on disk right now, and writing one here is the same act as
 * creating it by hand or committing it with the repo.
 *
 * Rendered inside Settings' "Skills" sheet, which carries the heading and the
 * explanation, so this holds only the list and its editors.
 */
@Composable
fun SkillSection(viewModel: WorkspaceViewModel, modifier: Modifier = Modifier) {
  // Re-read on each recomposition of the sheet: cheap, and it picks up
  // a skill the agent itself just wrote into the project.
  var refresh by remember { mutableStateOf(0) }
  val skills = remember(refresh) { viewModel.skills() }
  var editing by remember { mutableStateOf<AgentSkill?>(null) }
  var creating by remember { mutableStateOf(false) }
  var deleting by remember { mutableStateOf<AgentSkill?>(null) }

  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.End
    ) {
      TextButton(
        onClick = { creating = true },
        modifier = Modifier.testTag("btn_new_skill")
      ) {
        Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text("New skill", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
      }
    }

    if (skills.isEmpty()) {
      Text(
        "No skills yet. One page of instructions is usually enough to change how the agent works " +
          "in a project you know.",
        color = AwakiTheme.extra.textMuted,
        fontSize = 11.sp,
        modifier = Modifier.testTag("txt_no_skills")
      )
    } else {
      skills.forEach { skill ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .testTag("row_skill_${skill.name}"),
          verticalAlignment = Alignment.Top
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(skill.displayName, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(skill.description, color = AwakiTheme.extra.textMuted, fontSize = 11.sp, lineHeight = 14.sp)
            Text(
              if (skill.scope == SkillScope.PROJECT) "this project · ${skill.path}" else "every project · ${skill.path}",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              fontSize = 10.sp
            )
          }
          IconButton(
            onClick = { editing = skill },
            modifier = Modifier
              .size(28.dp)
              .testTag("btn_edit_skill_${skill.name}")
          ) {
            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = AwakiTheme.extra.textMuted, modifier = Modifier.size(14.dp))
          }
          IconButton(
            onClick = { deleting = skill },
            modifier = Modifier
              .size(28.dp)
              .testTag("btn_delete_skill_${skill.name}")
          ) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
          }
        }
        if (skill != skills.last()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      }
    }
  }

  if (creating) {
    SkillEditorDialog(
      original = null,
      onDismiss = { creating = false },
      onSave = { skill ->
        viewModel.saveSkill(skill)
        creating = false
        refresh++
      }
    )
  }

  editing?.let { skill ->
    SkillEditorDialog(
      original = skill,
      onDismiss = { editing = null },
      onSave = { edited ->
        // The old copy goes first, so editing an installed skill from a project
        // does not leave two files answering to one name.
        viewModel.deleteSkill(skill.name, skill.scope)
        viewModel.saveSkill(edited)
        editing = null
        refresh++
      }
    )
  }

  deleting?.let { skill ->
    AlertDialog(
      onDismissRequest = { deleting = null },
      containerColor = MaterialTheme.colorScheme.surface,
      title = { Text("Delete ${skill.displayName}?", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp) },
      text = {
        Text(
          "This removes ${skill.path} from ${if (skill.scope == SkillScope.PROJECT) "the project" else "the app's skills"}. " +
            "Conversations that already followed it keep their work.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          fontSize = 12.sp
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            viewModel.deleteSkill(skill.name, skill.scope)
            deleting = null
            refresh++
          },
          modifier = Modifier.testTag("btn_confirm_delete_skill")
        ) { Text("Delete", color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
      },
      dismissButton = {
        TextButton(onClick = { deleting = null }) { Text("Keep", color = AwakiTheme.extra.textMuted, fontSize = 13.sp) }
      }
    )
  }
}

/** Write one skill: what it is called, when it applies, and what to do. */
@Composable
private fun SkillEditorDialog(
  original: AgentSkill?,
  onDismiss: () -> Unit,
  onSave: (AgentSkill) -> Unit
) {
  var label by remember { mutableStateOf(original?.displayName ?: "") }
  var name by remember { mutableStateOf(original?.name ?: "") }
  var description by remember { mutableStateOf(original?.description ?: "") }
  var instructions by remember { mutableStateOf(original?.instructions ?: "") }
  var scope by remember { mutableStateOf(original?.scope ?: SkillScope.PROJECT) }

  val derivedName = SkillStore.slug(name.ifBlank { label })
  val valid = derivedName.isNotEmpty() && description.isNotBlank() && instructions.isNotBlank()

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(16.dp),
    title = {
      Text(
        if (original == null) "New skill" else "Edit ${original.displayName}",
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold
      )
    },
    text = {
      Column(modifier = Modifier.fillMaxWidth()) {
        AgentField(
          value = label,
          onValueChange = { label = it },
          label = "Title",
          placeholder = "e.g. Writing tests",
          testTag = "input_skill_label",
          singleLine = true
        )
        AgentField(
          value = name,
          onValueChange = { name = it },
          label = "Name the agent uses",
          placeholder = derivedName.ifBlank { "how the agent calls it, e.g. testing" },
          testTag = "input_skill_name",
          singleLine = true
        )
        AgentField(
          value = description,
          onValueChange = { description = it },
          label = "When to use it",
          placeholder = "The agent chooses from this one line on every turn",
          testTag = "input_skill_description",
          singleLine = true
        )
        AgentField(
          value = instructions,
          onValueChange = { instructions = it },
          label = "Instructions",
          placeholder = "What to actually do. Written for the agent to follow step by step.",
          testTag = "input_skill_instructions",
          minLines = 6
        )

        Text("Where it lives", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          ScopeChoice(
            modifier = Modifier.weight(1f),
            selected = scope == SkillScope.PROJECT,
            label = "This project",
            caption = ".awaki/skills - travels with the repo",
            testTag = "btn_skill_scope_project",
            onClick = { scope = SkillScope.PROJECT }
          )
          ScopeChoice(
            modifier = Modifier.weight(1f),
            selected = scope == SkillScope.APP,
            label = "Every project",
            caption = "the app's own folder, on this device",
            testTag = "btn_skill_scope_app",
            onClick = { scope = SkillScope.APP }
          )
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = {
          onSave(
            AgentSkill(
              name = derivedName,
              displayName = label.ifBlank { derivedName },
              description = description.trim(),
              instructions = instructions.trim(),
              scope = scope,
              path = ""
            )
          )
        },
        enabled = valid,
        modifier = Modifier.testTag("btn_save_skill")
      ) { Text("Save", color = if (valid) MaterialTheme.colorScheme.primary else AwakiTheme.extra.textMuted, fontSize = 13.sp) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.testTag("btn_cancel_skill")) {
        Text("Cancel", color = AwakiTheme.extra.textMuted, fontSize = 13.sp)
      }
    }
  )
}

@Composable
private fun ScopeChoice(
  modifier: Modifier,
  selected: Boolean,
  label: String,
  caption: String,
  testTag: String,
  onClick: () -> Unit
) {
  Column(
    modifier = modifier
      .clip(RoundedCornerShape(8.dp))
      .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
      .clickable(onClick = onClick)
      .padding(10.dp)
      .testTag(testTag)
  ) {
    Text(label, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    Text(caption, color = AwakiTheme.extra.textMuted, fontSize = 10.sp, lineHeight = 13.sp)
  }
}
