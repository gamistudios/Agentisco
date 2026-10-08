package com.awaki.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.awaki.agent.tool.OnDeviceTools
import com.awaki.agent.tool.ToolOffering
import com.awaki.core.model.AppDestination
import com.awaki.local.LocalAiRuntime
import com.awaki.local.model.LocalModel
import com.awaki.ui.WorkspaceViewModel
import com.awaki.ui.components.LocalModelsSection
import com.awaki.ui.components.MiniAction
import com.awaki.ui.theme.AwakiTheme

/**
 * Management surface for the models that run on this device.
 *
 * Separate from [AiProvidersScreen] because the two manage different objects: a provider
 * row is an address and a secret, a model row is a file on disk with a download to resume,
 * a size to reclaim and runtime settings of its own. Both are doorways from the same place
 * in Settings, and a model still reaches the agent as a provider — only its management
 * lives here.
 */
@Composable
fun LocalModelsScreen(
  viewModel: WorkspaceViewModel,
  onNavigate: (AppDestination) -> Unit,
  modifier: Modifier = Modifier
) {
  val models by viewModel.localModels.collectAsState()
  val selectedId by viewModel.selectedModel.collectAsState()
  // The tool set belongs to one model, and the model a user is working with is the
  // selected one — so this is where its tools can be chosen.
  val chosen = models.firstOrNull { it.installed && selectedId?.id == LocalAiRuntime.recordId(it.id) }

  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    item {
      Spacer(modifier = Modifier.height(10.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(
          onClick = { onNavigate(AppDestination.SETTINGS) },
          modifier = Modifier.size(32.dp).testTag("btn_local_models_back")
        ) {
          Icon(Icons.Default.ChevronLeft, contentDescription = "Back to settings", tint = AwakiTheme.extra.textMuted)
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text("Local Models", color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
      }
    }

    item {
      LocalModelsCard {
        // The section itself is not scrollable; this LazyColumn provides the scrolling
        // for it (nesting a verticalScroll Column here would crash).
        LocalModelsSection(viewModel = viewModel, modifier = Modifier.padding(14.dp))
      }
    }

    chosen?.let { model ->
      item {
        LocalModelsCard {
          LocalToolPicker(
            model = model,
            choices = viewModel.localToolChoices(),
            modifier = Modifier.padding(14.dp),
            onSave = { names ->
              viewModel.updateLocalAllowedTools(model.id, names)
            }
          )
        }
      }
    }

    item {
      Spacer(modifier = Modifier.height(24.dp))
    }
  }
}

@Composable
private fun LocalModelsCard(content: @Composable () -> Unit) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
  ) {
    content()
  }
}

/**
 * The tools an on-device model is offered, chosen beside the download and settings they
 * belong to.
 *
 * A phone prefills every character of a prompt before the model's first word, and one tool
 * costs its description plus its JSON schema — so this is a trade made against a number
 * the user can see: each name carries its own cost and the header the running total
 * against the context this model was given. Checking nothing in means the built-in set,
 * which is why an explicit choice equal to it is stored as none.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalToolPicker(
  model: LocalModel,
  choices: List<ToolOffering>,
  onSave: (Set<String>?) -> Unit,
  modifier: Modifier = Modifier
) {
  // Keyed on what is saved, not on the model's id: a record for the same model can arrive with
  // different tools (a reset, an install that re-published it), and chips that remembered the old
  // set would show a choice the model no longer has while claiming it was the saved one.
  var selected by remember(model.configuration.toolsOrDefault()) { mutableStateOf(model.configuration.toolsOrDefault()) }
  val cost = choices.filter { selected.contains(it.name) }.sumOf { it.estimatedTokens }
  val window = model.configuration.runtime.contextSize

  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Tools offered", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
          "${selected.size} of ${choices.size} · ~$cost tokens of $window context",
          color = if (cost > window / 2) MaterialTheme.colorScheme.primary else AwakiTheme.extra.textMuted,
          fontSize = 10.sp,
          fontFamily = FontFamily.Monospace
        )
      }
      Text(
        if (selected == model.configuration.toolsOrDefault()) "Saved" else "Not saved",
        color = if (selected == model.configuration.toolsOrDefault()) AwakiTheme.extra.textMuted else AwakiTheme.extra.warning,
        fontSize = 10.sp
      )
    }

    Spacer(modifier = Modifier.height(8.dp))
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(5.dp),
      verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
      choices.forEach { offering ->
        val on = selected.contains(offering.name)
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.background)
            .clickable {
              selected = if (on) selected - offering.name else selected + offering.name
            }
            .padding(horizontal = 7.dp, vertical = 3.dp)
            .testTag("chip_local_tool_${offering.name}")
        ) {
          Box(
            modifier = Modifier
              .size(7.dp)
              .clip(CircleShape)
              .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
          )
          Spacer(modifier = Modifier.width(5.dp))
          Text(offering.name, color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
          Spacer(modifier = Modifier.width(5.dp))
          Text("~${offering.estimatedTokens}", color = AwakiTheme.extra.textMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
      }
    }

    Spacer(modifier = Modifier.height(10.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      MiniAction(label = "Default", modifier = Modifier.testTag("btn_local_tools_default")) {
        selected = OnDeviceTools.DEFAULT
      }
      MiniAction(label = "All", modifier = Modifier.testTag("btn_local_tools_all")) {
        selected = choices.map { it.name }.toSet()
      }
      MiniAction(label = "None", modifier = Modifier.testTag("btn_local_tools_none")) {
        selected = emptySet()
      }
      Spacer(modifier = Modifier.weight(1f))
      MiniAction(
        label = "Save",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag("btn_local_tools_save"),
        onClick = { onSave(if (selected == OnDeviceTools.DEFAULT) null else selected) }
      )
    }
  }
}
