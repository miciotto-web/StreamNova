package com.example.ui.components

import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaCyanGlow
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.theme.NovaTextMuted
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TvFocusableBox(
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(12.dp),
  focusedScale: Float = 1.08f,
  focusedBorderColor: Color = NovaCyanBright,
  unfocusedBorderColor: Color = Color.Transparent,
  borderWidth: Dp = 2.5.dp,
  onClick: () -> Unit = {},
  onLongPress: (() -> Unit)? = null,
  content: @Composable BoxScope.(isFocused: Boolean) -> Unit
) {
  var isFocused by remember { mutableStateOf(false) }
  val scale by animateFloatAsState(
    targetValue = if (isFocused) focusedScale else 1f,
    animationSpec = tween(durationMillis = 180),
    label = "TvFocusScale"
  )
  val coroutineScope = rememberCoroutineScope()
  var longPressJob by remember { mutableStateOf<Job?>(null) }
  var longPressTriggered by remember { mutableStateOf(false) }
  var isKeyDownActive by remember { mutableStateOf(false) }

  val isConfirmKey = remember {
    { keyCode: Int ->
      keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
      keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
      keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
    }
  }

  Box(
    modifier = modifier
      .zIndex(if (isFocused) 10f else 1f)
      // Punto 2 – Zoom via graphicsLayer: l'animazione della scala non ricomposizione
      // la UI (la lambda legge lo stato direttamente in fase di draw/rendering).
      .graphicsLayer {
        scaleX = scale
        scaleY = scale
      }
      .onFocusChanged {
        isFocused = it.isFocused
        if (!it.isFocused) {
          longPressJob?.cancel()
          longPressJob = null
          longPressTriggered = false
          isKeyDownActive = false
        }
      }
      .onKeyEvent { keyEvent ->
        val keyCode = keyEvent.nativeKeyEvent.keyCode
        if (!isConfirmKey(keyCode)) return@onKeyEvent false

        when (keyEvent.type) {
          KeyEventType.KeyDown -> {
            if (longPressTriggered) {
              return@onKeyEvent true
            }

            if (!isKeyDownActive) {
              isKeyDownActive = true
              longPressTriggered = false
              longPressJob?.cancel()

              if (onLongPress != null) {
                longPressJob = coroutineScope.launch {
                  delay(400L)
                  longPressTriggered = true
                  Log.d("TvFocusableBox", "LONG PRESS TRIGGERED!")
                  onLongPress.invoke()
                }
              }
            }
            true
          }

          KeyEventType.KeyUp -> {
            val wasLongPress = longPressTriggered
            longPressJob?.cancel()
            longPressJob = null
            isKeyDownActive = false

            if (wasLongPress) {
              longPressTriggered = false
              Log.d("TvFocusableBox", "KeyUp: consumed after long press")
              true
            } else {
              Log.d("TvFocusableBox", "KeyUp: invoking normal onClick")
              onClick()
              true
            }
          }

          else -> false
        }
      }
      .focusable()
      .then(
        if (isFocused) {
          Modifier
            .shadow(
              elevation = 16.dp,
              shape = shape,
              spotColor = NovaCyan,
              ambientColor = NovaCyanGlow
            )
            .border(
              BorderStroke(
                width = borderWidth,
                brush = Brush.horizontalGradient(
                  listOf(NovaCyan, NovaCyanBright)
                )
              ),
              shape = shape
            )
        } else if (unfocusedBorderColor != Color.Transparent) {
          Modifier.border(borderWidth, unfocusedBorderColor, shape)
        } else {
          Modifier
        }
      )
      .clip(shape)
  ) {
    content(isFocused)
  }
}

@Composable
fun TvActionButton(
  text: String,
  icon: ImageVector? = null,
  isPrimary: Boolean = true,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  TvFocusableBox(
    modifier = modifier,
    shape = RoundedCornerShape(50),
    focusedScale = 1.05f,
    onClick = onClick
  ) { isFocused ->
    val bgBrush = when {
      isFocused && isPrimary -> Brush.horizontalGradient(listOf(NovaCyan, NovaCyanBright))
      isFocused && !isPrimary -> Brush.horizontalGradient(listOf(NovaSurfaceVariant, NovaCyan.copy(alpha = 0.35f)))
      isPrimary -> Brush.horizontalGradient(listOf(NovaCyan.copy(alpha = 0.95f), NovaCyan))
      else -> Brush.horizontalGradient(listOf(Color(0xFF1E2638).copy(alpha = 0.9f), Color(0xFF161E30).copy(alpha = 0.9f)))
    }

    val contentColor = when {
      isPrimary -> Color.Black
      isFocused -> NovaCyanBright
      else -> NovaTextPrimary
    }

    Row(
      modifier = Modifier
        .background(bgBrush, RoundedCornerShape(50))
        .border(
          width = if (isFocused) 1.5.dp else 1.dp,
          color = when {
            isFocused -> NovaCyanBright
            isPrimary -> Color.Transparent
            else -> Color(0x33475569)
          },
          shape = RoundedCornerShape(50)
        )
        .padding(horizontal = 16.dp, vertical = 9.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center
    ) {
      if (icon != null) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = contentColor,
          modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
      }
      Text(
        text = text,
        color = contentColor,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold
      )
    }
  }
}

@Composable
fun QualityBadge(
  text: String,
  modifier: Modifier = Modifier,
  isHighlighted: Boolean = false
) {
  Box(
    modifier = modifier
      .background(
        color = if (isHighlighted) NovaCyan.copy(alpha = 0.2f) else Color(0x3320293A),
        shape = RoundedCornerShape(4.dp)
      )
      .border(
        width = 1.dp,
        color = if (isHighlighted) NovaCyan.copy(alpha = 0.7f) else Color(0x4464748B),
        shape = RoundedCornerShape(4.dp)
      )
      .padding(horizontal = 6.dp, vertical = 3.dp)
  ) {
    Text(
      text = text,
      color = if (isHighlighted) NovaCyanBright else NovaTextPrimary,
      fontSize = 11.sp,
      fontWeight = FontWeight.SemiBold,
      letterSpacing = 0.5.sp
    )
  }
}

data class ContextMenuItem(
  val label: String,
  val icon: ImageVector? = null,
  val onClick: () -> Unit
)

@Composable
fun TvContextMenu(
  items: List<ContextMenuItem>,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier
) {
  BackHandler(enabled = true) {
    onDismiss()
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black.copy(alpha = 0.4f))
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onDismiss
      ),
    contentAlignment = Alignment.Center
  ) {
    Column(
      modifier = Modifier
        .background(NovaSurfaceVariant, RoundedCornerShape(16.dp))
        .border(1.5.dp, NovaCyan, RoundedCornerShape(16.dp))
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      items.forEach { item ->
        TvFocusableBox(
          modifier = Modifier
            .fillMaxSize()
            .width(240.dp),
          shape = RoundedCornerShape(8.dp),
          focusedScale = 1.02f,
          onClick = {
            item.onClick()
            onDismiss()
          }
        ) { isFocused ->
          Row(
            modifier = Modifier
              .background(
                if (isFocused) NovaCyan.copy(alpha = 0.15f) else Color.Transparent,
                RoundedCornerShape(8.dp)
              )
              .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
          ) {
            if (item.icon != null) {
              Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = if (isFocused) NovaCyanBright else NovaTextPrimary,
                modifier = Modifier.size(20.dp)
              )
              Spacer(modifier = Modifier.width(12.dp))
            }
            Text(
              text = item.label,
              color = if (isFocused) NovaCyanBright else NovaTextPrimary,
              fontSize = 14.sp,
              fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium
            )
          }
        }
      }
    }
  }
}

@Composable
fun MediaContextMenu(
  media: MediaItem,
  onPlay: () -> Unit,
  onDetails: () -> Unit,
  onRemoveFromContinueWatching: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier
) {
  val firstItemFocusRequester = remember { FocusRequester() }
  var canClick by remember { mutableStateOf(false) }

  val isConfirmKey = remember {
    { keyCode: Int ->
      keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
      keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
      keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
    }
  }

  LaunchedEffect(Unit) {
    firstItemFocusRequester.requestFocus()
    delay(250L)
    canClick = true
  }

  BackHandler(enabled = true) {
    onDismiss()
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black.copy(alpha = 0.7f))
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onDismiss
      )
      .onKeyEvent { keyEvent ->
        if (!canClick && keyEvent.type == KeyEventType.KeyUp && isConfirmKey(keyEvent.nativeKeyEvent.keyCode)) {
          true
        } else {
          false
        }
      },
    contentAlignment = Alignment.Center
  ) {
    Column(
      modifier = Modifier
        .width(320.dp)
        .shadow(elevation = 24.dp, shape = RoundedCornerShape(16.dp), spotColor = NovaCyan, ambientColor = NovaCyanGlow)
        .background(Color(0xFF1E1E24), RoundedCornerShape(16.dp))
        .border(1.dp, NovaCyan.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(120.dp)
      ) {
        CardMediaImage(
          primaryUrl = media.backdropUrl,
          secondaryUrl = media.posterUrl,
          fallbackRes = media.backdropRes ?: media.posterRes ?: R.drawable.banner_dune,
          contentDescription = media.title,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop
        )
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(
              Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color(0xFF1E1E24)),
                startY = 40f
              )
            )
        )
        Column(
          modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(16.dp)
        ) {
          Text(
            text = media.title,
            color = NovaTextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          if (media.type == MediaType.SERIE_TV && media.lastWatchedEpisode != null) {
            Text(
              text = "S${media.lastWatchedSeason ?: 1}:E${media.lastWatchedEpisode}",
              color = NovaTextSecondary,
              fontSize = 12.sp
            )
          }
        }
      }

      Column(
        modifier = Modifier
          .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
      ) {
        val menuItems = listOf(
          ContextMenuItem(
            label = if (media.currentProgressMs > 0) stringResource(R.string.context_resume) else stringResource(R.string.action_play),
            icon = Icons.Default.PlayArrow,
            onClick = onPlay
          ),
          ContextMenuItem(
            label = stringResource(R.string.context_details),
            icon = Icons.Default.Info,
            onClick = onDetails
          ),
          ContextMenuItem(
            label = stringResource(R.string.context_remove_continue_watching),
            icon = Icons.Default.Close,
            onClick = onRemoveFromContinueWatching
          )
        )

        menuItems.forEachIndexed { index, item ->
          TvFocusableBox(
            modifier = Modifier
              .fillMaxWidth()
              .height(48.dp)
              .then(
                if (index == 0) Modifier.focusRequester(firstItemFocusRequester)
                else Modifier
              ),
            shape = RoundedCornerShape(8.dp),
            focusedScale = 1.02f,
            onClick = {
              if (canClick) {
                item.onClick()
                onDismiss()
              }
            }
          ) { isFocused ->
            Row(
              modifier = Modifier
                .fillMaxSize()
                .background(
                  if (isFocused) NovaCyan.copy(alpha = 0.15f) else Color.Transparent,
                  RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 16.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              item.icon?.let { icon ->
                Icon(
                  imageVector = icon,
                  contentDescription = null,
                  tint = if (isFocused) NovaCyanBright else NovaTextSecondary,
                  modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(14.dp))
              }
              Text(
                text = item.label,
                color = if (isFocused) NovaCyanBright else NovaTextPrimary,
                fontSize = 14.sp,
                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium
              )
            }
          }
        }
      }

      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp)
      ) {
        TvFocusableBox(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(8.dp),
          focusedScale = 1.02f,
          onClick = {
            if (canClick) {
              onDismiss()
            }
          }
        ) { isFocused ->
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .background(
                if (isFocused) NovaCyan.copy(alpha = 0.1f) else Color.Transparent,
                RoundedCornerShape(8.dp)
              )
              .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = stringResource(R.string.action_cancel),
              color = if (isFocused) NovaCyanBright else NovaTextMuted,
              fontSize = 14.sp,
              fontWeight = FontWeight.Medium
            )
          }
        }
      }
    }
  }
}
