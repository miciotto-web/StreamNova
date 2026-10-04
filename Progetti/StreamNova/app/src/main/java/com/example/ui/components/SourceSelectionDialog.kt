package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.R
import com.example.data.streaming.StreamSource
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.theme.NovaGreen
import com.example.ui.theme.NovaOrange
import com.example.ui.theme.NovaBackground

data class SourceItem(
  val source: StreamSource,
  val isItalian: Boolean,
  val resolutionBadge: String,
  val sourceName: String,
  val codecBadge: String? = null,
  val addonName: String? = null,
  val instantTag: String? = null,
  val releaseTitle: String? = null,
  val details: String? = null,
  val releaseType: String? = null,
)

@Composable
fun SourceSelectionDialog(
  sources: List<SourceItem>,
  onSelect: (SourceItem) -> Unit,
  onDismiss: () -> Unit
) {
  val listState = rememberLazyListState()
  var selectedIndex by remember { mutableIntStateOf(0) }
  var selectedFilter by remember { mutableStateOf<String?>(null) }

  val filteredSources = remember(sources, selectedFilter) {
    if (selectedFilter == null) sources
    else sources.filter { it.addonName == selectedFilter }
  }

  val addonNames = remember(sources) {
    sources.mapNotNull { it.addonName }.distinct().sorted()
  }

  LaunchedEffect(selectedIndex) {
    if (selectedIndex >= 0 && selectedIndex < filteredSources.size) {
      listState.animateScrollToItem(selectedIndex)
    }
  }

  Dialog(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(NovaSurface, RoundedCornerShape(20.dp))
        .border(1.5.dp, NovaCyan, RoundedCornerShape(20.dp))
        .padding(20.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = stringResource(R.string.source_dialog_title, filteredSources.size),
          color = NovaTextPrimary,
          fontSize = 18.sp,
          fontWeight = FontWeight.Bold
        )
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = stringResource(R.string.action_close),
          tint = NovaTextSecondary,
          modifier = Modifier
            .size(22.dp)
            .clickable { onDismiss() }
        )
      }

      Spacer(modifier = Modifier.height(8.dp))

      Text(
        text = stringResource(R.string.source_dialog_hints),
        color = NovaTextMuted,
        fontSize = 11.sp
      )

      Spacer(modifier = Modifier.height(12.dp))

      if (addonNames.size > 1) {
        FilterPills(
          addonNames = addonNames,
          selectedFilter = selectedFilter,
          onFilterSelected = { selectedFilter = it }
        )
        Spacer(modifier = Modifier.height(12.dp))
      }

      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .height(380.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        itemsIndexed(filteredSources) { index, item ->
          SourceCard(
            item = item,
            isSelected = index == selectedIndex,
            onSelect = { onSelect(item) },
            onFocus = { if (it) selectedIndex = index }
          )
        }
      }

      Spacer(modifier = Modifier.height(16.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
      ) {
        TvFocusableBox(
          shape = RoundedCornerShape(50),
          focusedScale = 1.05f,
          onClick = onDismiss
        ) { isFocused ->
          Text(
            text = stringResource(R.string.action_cancel),
            color = if (isFocused) NovaCyanBright else NovaTextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
              .background(
                if (isFocused) NovaCyan.copy(alpha = 0.18f) else NovaSurfaceVariant,
                RoundedCornerShape(50)
              )
              .padding(horizontal = 24.dp, vertical = 10.dp)
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPills(
  addonNames: List<String>,
  selectedFilter: String?,
  onFilterSelected: (String?) -> Unit
) {
  LazyRow(
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    item {
      FilterPill(
        label = stringResource(R.string.source_dialog_filter_all),
        isSelected = selectedFilter == null,
        onClick = { onFilterSelected(null) }
      )
    }
    itemsIndexed(addonNames) { _, name ->
      FilterPill(
        label = name,
        isSelected = selectedFilter == name,
        onClick = { onFilterSelected(name) }
      )
    }
  }
}

@Composable
private fun FilterPill(
  label: String,
  isSelected: Boolean,
  onClick: () -> Unit
) {
  TvFocusableBox(
    shape = RoundedCornerShape(50),
    focusedScale = 1.05f,
    onClick = onClick
  ) { isFocused ->
    Box(
      modifier = Modifier
        .background(
          when {
            isFocused -> NovaCyan.copy(alpha = 0.3f)
            isSelected -> NovaCyan.copy(alpha = 0.18f)
            else -> NovaSurfaceVariant
          },
          RoundedCornerShape(50)
        )
        .border(
          width = if (isFocused || isSelected) 1.5.dp else 1.dp,
          color = when {
            isFocused -> NovaCyanBright
            isSelected -> NovaCyan
            else -> NovaTextMuted.copy(alpha = 0.3f)
          },
          shape = RoundedCornerShape(50)
        )
        .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
      Text(
        text = label,
        color = when {
          isFocused -> NovaCyanBright
          isSelected -> NovaCyan
          else -> NovaTextSecondary
        },
        fontSize = 12.sp,
        fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceCard(
  item: SourceItem,
  isSelected: Boolean,
  onSelect: () -> Unit,
  onFocus: (Boolean) -> Unit
) {
  val borderColor = if (isSelected) NovaCyanBright else NovaCyan.copy(alpha = 0.3f)
  val backgroundColor = if (isSelected) NovaCyan.copy(alpha = 0.1f) else NovaSurfaceVariant

  TvFocusableBox(
    shape = RoundedCornerShape(12.dp),
    onClick = onSelect,
    modifier = Modifier
      .fillMaxWidth()
      .onFocusChanged { onFocus(it.hasFocus) }
  ) { isFocused ->
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          if (isFocused) NovaCyan.copy(alpha = 0.08f) else backgroundColor,
          RoundedCornerShape(12.dp)
        )
        .border(
          width = if (isFocused) 2.dp else 1.dp,
          color = if (isFocused) NovaCyanBright else borderColor,
          shape = RoundedCornerShape(12.dp)
        )
        .padding(14.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            InstantBadge(item.instantTag ?: item.resolutionBadge)
            if (item.releaseType != null) {
              ReleaseTypeBadge(item.releaseType)
            }
            if (item.codecBadge != null) {
              CodecBadge(item.codecBadge)
            }
            if (item.isItalian) {
              ItalianBadge()
            }
            if (item.source.isCached) {
              CachedBadge()
            }
          }

          Spacer(modifier = Modifier.height(8.dp))

          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Description,
              contentDescription = null,
              tint = NovaTextMuted,
              modifier = Modifier.size(16.dp)
            )
            Text(
              text = item.releaseTitle ?: item.sourceName,
              color = NovaTextPrimary,
              fontSize = 14.sp,
              fontWeight = FontWeight.Medium,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis
            )
          }

          if (!item.details.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = item.details,
              color = NovaTextMuted,
              fontSize = 12.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(
          horizontalAlignment = Alignment.End
        ) {
          if (item.addonName != null) {
            Box(
              modifier = Modifier
                .background(NovaBackground.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
              Text(
                text = item.addonName,
                color = NovaCyanBright,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
          }
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = item.source.resolution,
            color = NovaCyanBright,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
          )
        }
      }
    }
  }
}

@Composable
private fun InstantBadge(text: String) {
  Box(
    modifier = Modifier
      .background(NovaCyan.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
      .padding(horizontal = 6.dp, vertical = 2.dp)
  ) {
    Text(
      text = text,
      color = NovaCyanBright,
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold
    )
  }
}

@Composable
private fun ReleaseTypeBadge(type: String) {
  val backgroundColor = when {
    type.contains("WEB") -> NovaGreen.copy(alpha = 0.2f)
    type.contains("BluRay") -> NovaOrange.copy(alpha = 0.2f)
    type.contains("HDR") -> NovaOrange.copy(alpha = 0.25f)
    else -> NovaSurfaceVariant
  }
  val textColor = when {
    type.contains("WEB") -> NovaGreen
    type.contains("BluRay") -> NovaOrange
    type.contains("HDR") -> NovaOrange
    else -> NovaTextSecondary
  }
  Box(
    modifier = Modifier
      .background(backgroundColor, RoundedCornerShape(4.dp))
      .padding(horizontal = 6.dp, vertical = 2.dp)
  ) {
    Text(
      text = type,
      color = textColor,
      fontSize = 10.sp,
      fontWeight = FontWeight.Bold
    )
  }
}

@Composable
private fun CodecBadge(codec: String) {
  val isHevc = codec.contains("HEVC") || codec.contains("x265") || codec.contains("hvc1")
  val backgroundColor = if (isHevc) NovaOrange.copy(alpha = 0.2f) else NovaSurfaceVariant
  val textColor = if (isHevc) NovaOrange else NovaTextSecondary

  Box(
    modifier = Modifier
      .background(backgroundColor, RoundedCornerShape(4.dp))
      .padding(horizontal = 6.dp, vertical = 2.dp)
  ) {
    Text(
      text = codec,
      color = textColor,
      fontSize = 10.sp,
      fontWeight = FontWeight.Bold
    )
  }
}

@Composable
private fun ItalianBadge() {
  Box(
    modifier = Modifier
      .background(NovaGreen.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
      .padding(horizontal = 6.dp, vertical = 2.dp)
  ) {
    Text(
      text = "🇮🇹 IT",
      color = NovaGreen,
      fontSize = 10.sp,
      fontWeight = FontWeight.Bold
    )
  }
}

@Composable
private fun CachedBadge() {
  Box(
    modifier = Modifier
      .background(NovaGreen.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
      .padding(horizontal = 6.dp, vertical = 2.dp)
  ) {
    Text(
      text = "🧲 CACHED",
      color = NovaGreen,
      fontSize = 10.sp,
      fontWeight = FontWeight.Bold
    )
  }
}

@Composable
private fun ResolutionBadge(resolution: String) {
  val backgroundColor = when {
    resolution.contains("4K") -> NovaCyan.copy(alpha = 0.2f)
    resolution.contains("1080") -> NovaCyan.copy(alpha = 0.15f)
    else -> NovaSurfaceVariant
  }
  val textColor = when {
    resolution.contains("4K") -> NovaCyanBright
    resolution.contains("1080") -> NovaCyan
    else -> NovaTextSecondary
  }
  Box(
    modifier = Modifier
      .background(backgroundColor, RoundedCornerShape(4.dp))
      .padding(horizontal = 6.dp, vertical = 2.dp)
  ) {
    Text(
      text = resolution,
      color = textColor,
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold
    )
  }
}
