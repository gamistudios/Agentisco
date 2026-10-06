package com.awaki.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.awaki.ui.theme.DarkBorder
import com.awaki.ui.theme.DarkBorderSubtle
import com.awaki.ui.theme.DarkSurface
import com.awaki.ui.theme.DarkSurfaceElevated
import com.awaki.ui.theme.DarkSurfaceHighlight
import com.awaki.ui.theme.DangerRed
import com.awaki.ui.theme.ElectricBlue
import com.awaki.ui.theme.ElectricBlueGlow
import com.awaki.ui.theme.TextMuted
import com.awaki.ui.theme.TextPrimary
import com.awaki.ui.theme.TextSecondary
import com.awaki.ui.theme.WarningAmber

/**
 * The compact settings vocabulary: one container per group, one dense row per
 * setting, and a hairline between neighbours instead of a card around each.
 *
 * The screen this replaces stacked fourteen cards, most of them holding a single
 * switch, so reaching a setting meant scrolling past all of them. A row is 52dp and
 * a group header is one line, which is what lets the whole of Awaki's
 * configuration sit behind a search box that has real room to work in.
 */

/** The switch colours, which every settings toggle shares. */
@Composable
fun AwakiSwitchColors() = SwitchDefaults.colors(
  checkedThumbColor = ElectricBlue,
  checkedTrackColor = ElectricBlue.copy(alpha = 0.35f),
  checkedBorderColor = ElectricBlue,
  uncheckedThumbColor = TextSecondary,
  uncheckedTrackColor = DarkSurfaceHighlight,
  uncheckedBorderColor = DarkBorder
)

@Composable
fun SettingsSection(group: SettingsGroup, items: List<SettingsItem>, modifier: Modifier = Modifier) {
  if (items.isEmpty()) return
  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 4.dp, top = 4.dp, bottom = 7.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(group.icon, contentDescription = null, tint = ElectricBlueGlow, modifier = Modifier.size(13.dp))
      Spacer(modifier = Modifier.width(7.dp))
      Text(
        text = group.label.uppercase(),
        color = TextSecondary,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp
      )
    }
    SettingsRowGroup(items)
  }
}

/** The bordered stack of rows a section is made of, without its header. */
@Composable
fun SettingsRowGroup(items: List<SettingsItem>, modifier: Modifier = Modifier) {
  if (items.isEmpty()) return
  Column(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(14.dp))
      .background(DarkSurface)
      .border(1.dp, DarkBorderSubtle, RoundedCornerShape(14.dp))
  ) {
    items.forEachIndexed { index, item ->
      SettingsRow(item)
      if (index < items.lastIndex) {
        HorizontalDivider(
          color = DarkBorderSubtle,
          thickness = 0.5.dp,
          modifier = Modifier.padding(start = 48.dp)
        )
      }
    }
  }
}

@Composable
fun SettingsRow(item: SettingsItem, modifier: Modifier = Modifier) {
  val onClick = item.onClick
  Row(
    modifier = modifier
      .fillMaxWidth()
      .heightIn(min = 52.dp)
      .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
      .testTag("row_${item.id}")
      .padding(horizontal = 10.dp, vertical = 7.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Box(
      modifier = Modifier
        .size(28.dp)
        .clip(RoundedCornerShape(9.dp))
        .background(DarkSurfaceElevated),
      contentAlignment = Alignment.Center
    ) {
      Icon(item.icon, contentDescription = null, tint = iconTint(item.end), modifier = Modifier.size(15.dp))
    }
    Spacer(modifier = Modifier.width(10.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = item.title,
        color = TextPrimary,
        fontSize = 13.5.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1
      )
      if (item.detail.isNotEmpty()) {
        Text(
          text = item.detail,
          color = TextMuted,
          fontSize = 11.sp,
          lineHeight = 13.sp,
          maxLines = 2
        )
      }
    }
    Spacer(modifier = Modifier.width(8.dp))
    when (val end = item.end) {
      is RowEnd.Switch -> Switch(
        checked = end.checked,
        onCheckedChange = end.onToggle,
        colors = AwakiSwitchColors(),
        modifier = Modifier.testTag(end.tag ?: "switch_${item.id}")
      )
      is RowEnd.Value -> {
        Text(
          text = end.text,
          color = valueColor(end.tone),
          fontSize = 12.sp,
          fontFamily = FontFamily.Monospace,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier
            .widthIn(max = 128.dp)
            .then(if (end.tag != null) Modifier.testTag(end.tag) else Modifier)
        )
        if (onClick != null) RowChevron()
      }
      is RowEnd.Stepper -> StepperEnd(end, end.tag ?: "stepper_${item.id}")
      RowEnd.Check -> Icon(
        Icons.Default.Check,
        contentDescription = "Selected",
        tint = ElectricBlueGlow,
        modifier = Modifier.size(17.dp)
      )
      RowEnd.Chevron -> if (onClick != null) RowChevron()
    }
  }
}

@Composable
private fun RowChevron() {
  Icon(
    Icons.Default.ChevronRight,
    contentDescription = null,
    tint = TextMuted,
    modifier = Modifier.size(16.dp)
  )
}

@Composable
private fun StepperEnd(end: RowEnd.Stepper, tag: String) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    StepBox("−", enabled = end.canDecrease, tag = "${tag}_decrease", onClick = end.onDecrease)
    Box(
      modifier = Modifier
        .padding(horizontal = 6.dp)
        .clip(RoundedCornerShape(6.dp))
        .background(DarkSurfaceElevated)
        .padding(horizontal = 8.dp, vertical = 5.dp)
        .testTag(tag),
      contentAlignment = Alignment.Center
    ) {
      Text(
        text = end.text,
        color = ElectricBlueGlow,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace
      )
    }
    StepBox("+", enabled = end.canIncrease, tag = "${tag}_increase", onClick = end.onIncrease)
  }
}

@Composable
private fun StepBox(label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
  Box(
    modifier = Modifier
      .size(26.dp)
      .clip(RoundedCornerShape(6.dp))
      .background(if (enabled) DarkSurfaceElevated else DarkSurface.copy(alpha = 0.5f))
      .border(1.dp, DarkBorderSubtle, RoundedCornerShape(6.dp))
      .clickable(enabled = enabled, onClick = onClick)
      .testTag(tag),
    contentAlignment = Alignment.Center
  ) {
    Text(label, color = if (enabled) TextPrimary else TextMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
  }
}

/** The settings search box: one line tall, filtering the rows already on screen. */
@Composable
fun SettingsSearchField(
  query: String,
  onQueryChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String = "Search settings",
  tag: String = "input_settings_search"
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .height(44.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(DarkSurface)
      .border(
        1.dp,
        if (query.isEmpty()) DarkBorderSubtle else ElectricBlue.copy(alpha = 0.5f),
        RoundedCornerShape(12.dp)
      )
      .padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(Icons.Outlined.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
    Spacer(modifier = Modifier.width(10.dp))
    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
      if (query.isEmpty()) {
        Text(placeholder, color = TextMuted, fontSize = 13.sp)
      }
      BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
        cursorBrush = SolidColor(ElectricBlue),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier
          .fillMaxWidth()
          .testTag(tag)
      )
    }
    if (query.isNotEmpty()) {
      IconButton(
        onClick = { onQueryChange("") },
        modifier = Modifier
          .size(28.dp)
          .testTag("btn_clear_settings_search")
      ) {
        Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextMuted, modifier = Modifier.size(15.dp))
      }
    }
  }
}

/** A search that matched nothing says so, rather than leaving a blank screen. */
@Composable
fun SettingsNoResults(query: String, modifier: Modifier = Modifier) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(14.dp))
      .background(DarkSurface)
      .border(1.dp, DarkBorderSubtle, RoundedCornerShape(14.dp))
      .padding(horizontal = 14.dp, vertical = 18.dp)
      .testTag("txt_no_settings_results")
  ) {
    Text("No setting matches \"$query\"", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      "Try a shorter word — model, battery, git, theme.",
      color = TextMuted,
      fontSize = 11.sp,
      lineHeight = 14.sp
    )
  }
}

@Composable
private fun iconTint(end: RowEnd): Color = when {
  end is RowEnd.Value && end.tone == ValueTone.Danger -> DangerRed
  end is RowEnd.Value && end.tone == ValueTone.Warning -> WarningAmber
  else -> ElectricBlueGlow
}

private fun valueColor(tone: ValueTone): Color = when (tone) {
  ValueTone.Neutral -> TextSecondary
  ValueTone.Accent -> ElectricBlueGlow
  ValueTone.Warning -> WarningAmber
  ValueTone.Danger -> DangerRed
}
