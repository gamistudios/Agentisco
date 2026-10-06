package com.awaki.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.awaki.ui.AgentPlan
import com.awaki.ui.PlanStep
import com.awaki.ui.PlanStepState
import com.awaki.ui.theme.AwakiTheme
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.delay

/**
 * The task plan, pinned above the composer.
 *
 * It is one persistent component rather than a run of tool cards: every
 * `task_plan` update the agent publishes lands here, in place, so the plan
 * stays where the user looks for it while the conversation keeps scrolling
 * above. Collapsed it is a single row that costs almost no height; expanded it
 * shows the whole step list, which scrolls instead of pushing the composer off
 * screen however many steps there are.
 */
@Composable
fun PlanCard(
  plan: AgentPlan,
  expanded: Boolean,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier
) {
  // An open step counts up while the agent works, so "in progress" is live
  // rather than a claim frozen at the moment the plan was published. Only while
  // the list is showing: collapsed, no elapsed time is on screen to keep fresh.
  var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
  val ticking = expanded && plan.running && plan.current != null
  if (ticking) {
    LaunchedEffect(plan.turnId, plan.current?.content) {
      while (true) {
        delay(1000L)
        nowMs = System.currentTimeMillis()
      }
    }
  }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(MaterialTheme.colorScheme.surfaceContainer)
      .border(
        width = 1.dp,
        brush = Brush.linearGradient(
          listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f))
        ),
        shape = RoundedCornerShape(12.dp)
      )
      .testTag("plan_card")
  ) {
    if (expanded) {
      Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
        PlanHeaderRow(
          plan = plan,
          expanded = true,
          onToggle = onToggle,
          showLabel = true
        )
        if (plan.title.isNotBlank() || plan.note.isNotBlank()) {
          Spacer(modifier = Modifier.height(6.dp))
          if (plan.title.isNotBlank()) {
            Text(
              text = plan.title,
              color = MaterialTheme.colorScheme.onSurface,
              fontSize = 13.sp,
              fontWeight = FontWeight.SemiBold,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis
            )
          }
          if (plan.note.isNotBlank()) {
            Text(
              text = plan.note,
              color = AwakiTheme.extra.textMuted,
              fontSize = 11.sp,
              lineHeight = 14.sp,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Capped height: the list scrolls instead of the card growing, so the
        // composer stays reachable with a hundred steps.
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 232.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
        ) {
          LazyColumn(modifier = Modifier.fillMaxWidth().testTag("plan_steps")) {
            items(plan.steps, key = { it.number }) { step ->
              PlanStepRow(
                step = step,
                elapsedSeconds = if (ticking && step.state == PlanStepState.RUNNING) {
                  elapsedSince(plan.stepStartedAt, nowMs)
                } else 0.0
              )
              if (step.number < plan.total) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
              }
            }
          }
        }
      }
    } else {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable(onClick = onToggle)
          .padding(horizontal = 10.dp, vertical = 7.dp)
          .testTag("plan_toggle"),
        verticalAlignment = Alignment.CenterVertically
      ) {
        PlanBadge()
        Spacer(modifier = Modifier.width(8.dp))
        PlanLabel()
        Spacer(modifier = Modifier.width(10.dp))
        PlanProgressBar(
          progress = plan.progress,
          modifier = Modifier.weight(1f).testTag("plan_progress")
        )
        Spacer(modifier = Modifier.width(10.dp))
        PlanCountPill(plan)
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
          imageVector = Icons.Default.KeyboardArrowDown,
          contentDescription = "Expand plan",
          tint = AwakiTheme.extra.textMuted,
          modifier = Modifier.size(18.dp)
        )
      }
    }
  }
}

@Composable
private fun PlanHeaderRow(plan: AgentPlan, expanded: Boolean, onToggle: () -> Unit, showLabel: Boolean) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .clickable(onClick = onToggle)
      .testTag("plan_toggle"),
    verticalAlignment = Alignment.CenterVertically
  ) {
    PlanBadge()
    Spacer(modifier = Modifier.width(8.dp))
    if (showLabel) PlanLabel()
    Spacer(modifier = Modifier.width(10.dp))
    PlanCountPill(plan)
    Spacer(modifier = Modifier.width(10.dp))
    PlanProgressBar(progress = plan.progress, modifier = Modifier.weight(1f).testTag("plan_progress"))
    Spacer(modifier = Modifier.width(8.dp))
    Text(
      text = if (expanded) "Collapse" else "Expand",
      color = AwakiTheme.extra.textMuted,
      fontSize = 11.sp,
      fontWeight = FontWeight.Medium
    )
    Icon(
      imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
      contentDescription = null,
      tint = AwakiTheme.extra.textMuted,
      modifier = Modifier.size(18.dp)
    )
  }
}

@Composable
private fun PlanBadge() {
  Box(
    modifier = Modifier
      .size(26.dp)
      .clip(RoundedCornerShape(9.dp))
      .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f), MaterialTheme.colorScheme.tertiary.copy(alpha = 0.28f)))),
    contentAlignment = Alignment.Center
  ) {
    Icon(
      imageVector = Icons.Default.AutoAwesome,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(15.dp)
    )
  }
}

@Composable
private fun PlanLabel() {
  Text(
    text = "Plan",
    color = MaterialTheme.colorScheme.onSurface,
    fontSize = 13.sp,
    fontWeight = FontWeight.Bold
  )
}

@Composable
private fun PlanCountPill(plan: AgentPlan) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(8.dp))
      .background(MaterialTheme.colorScheme.surfaceContainerHigh)
      .padding(horizontal = 7.dp, vertical = 2.dp)
  ) {
    Text(
      text = "${plan.done} / ${plan.total}",
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      fontSize = 11.sp,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1
    )
  }
}

@Composable
private fun PlanProgressBar(progress: Float, modifier: Modifier = Modifier) {
  val animated by animateFloatAsState(
    targetValue = progress.coerceIn(0f, 1f),
    animationSpec = tween(320),
    label = "plan-progress"
  )
  Box(
    modifier = modifier
      .height(5.dp)
      .clip(RoundedCornerShape(3.dp))
      .background(MaterialTheme.colorScheme.surfaceContainerHigh)
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth(animated)
        .fillMaxHeight()
        .clip(RoundedCornerShape(3.dp))
        .background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)))
    )
  }
}

@Composable
private fun PlanStepRow(step: PlanStep, elapsedSeconds: Double) {
  val accent = planStepColor(step.state)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(if (step.state == PlanStepState.RUNNING) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
      .padding(horizontal = 9.dp, vertical = 7.dp)
      .testTag("plan_step_${step.number}"),
    verticalAlignment = Alignment.CenterVertically
  ) {
    PlanStepMarker(step.state)
    Spacer(modifier = Modifier.width(8.dp))
    Text(
      text = "${step.number}.",
      color = AwakiTheme.extra.textMuted,
      fontSize = 11.sp,
      fontWeight = FontWeight.Medium,
      maxLines = 1
    )
    Spacer(modifier = Modifier.width(6.dp))
    Text(
      text = step.content,
      color = if (step.state == PlanStepState.SKIPPED) AwakiTheme.extra.textMuted else MaterialTheme.colorScheme.onSurface,
      fontSize = 12.sp,
      lineHeight = 15.sp,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f)
    )
    Spacer(modifier = Modifier.width(8.dp))
    val suffix = when (step.state) {
      PlanStepState.RUNNING -> listOf(step.detail, formatDuration(max(elapsedSeconds, step.seconds)))
        .filter { it.isNotBlank() }.joinToString(" · ")
      else -> formatDuration(step.seconds)
    }
    Text(
      text = if (suffix.isBlank()) planStepLabel(step.state) else "${planStepLabel(step.state)} · $suffix",
      color = accent,
      fontSize = 10.sp,
      fontWeight = FontWeight.Medium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
  }
}

/** Filled disc once a step settles, hollow ring while it waits, dot while it runs. */
@Composable
private fun PlanStepMarker(state: PlanStepState) {
  val color = planStepColor(state)
  val settled = state == PlanStepState.DONE || state == PlanStepState.FAILED
  Box(
    modifier = Modifier
      .size(18.dp)
      .clip(CircleShape)
      .background(if (settled) color else Color.Transparent)
      .border(1.5.dp, color.copy(alpha = 0.55f), CircleShape),
    contentAlignment = Alignment.Center
  ) {
    when (state) {
      PlanStepState.DONE -> Icon(
        imageVector = Icons.Default.Check,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.background,
        modifier = Modifier.size(12.dp)
      )
      PlanStepState.FAILED -> Icon(
        imageVector = Icons.Default.Close,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.background,
        modifier = Modifier.size(12.dp)
      )
      PlanStepState.RUNNING -> Box(
        modifier = Modifier
          .size(8.dp)
          .clip(CircleShape)
          .background(color)
      )
      PlanStepState.SKIPPED -> Icon(
        imageVector = Icons.Default.Remove,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(12.dp)
      )
      PlanStepState.PENDING -> Unit
    }
  }
}

@Composable
private fun planStepColor(state: PlanStepState): Color = when (state) {
  PlanStepState.DONE -> AwakiTheme.extra.success
  PlanStepState.RUNNING -> MaterialTheme.colorScheme.primary
  PlanStepState.FAILED -> MaterialTheme.colorScheme.error
  PlanStepState.SKIPPED, PlanStepState.PENDING -> AwakiTheme.extra.textMuted
}

private fun planStepLabel(state: PlanStepState): String = when (state) {
  PlanStepState.DONE -> "Completed"
  PlanStepState.RUNNING -> "In progress"
  PlanStepState.FAILED -> "Failed"
  PlanStepState.SKIPPED -> "Skipped"
  PlanStepState.PENDING -> "Pending"
}

private fun elapsedSince(startedAt: Long, nowMs: Long): Double =
  if (startedAt <= 0L) 0.0 else max(0.0, (nowMs - startedAt) / 1000.0)

/** "12.4s" up close, "2m 05s" beyond a minute, nothing when there is no time to show. */
private fun formatDuration(seconds: Double): String = when {
  seconds <= 0.0 -> ""
  seconds < 60.0 -> String.format(Locale.US, "%.1fs", seconds)
  else -> {
    val whole = seconds.toLong()
    "%dm %02ds".format(Locale.US, whole / 60, whole % 60)
  }
}
