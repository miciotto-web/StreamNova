package com.example.ui.components

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaCyanGlow
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextPrimary


@Composable
fun FocusableBox(
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(12.dp),
  focusedScale: Float = 1.05f,
  onClick: () -> Unit = {},
  content: @Composable BoxScope.(isFocused: Boolean) -> Unit
) {
  var isFocused by remember { mutableStateOf(false) }
  val scale by animateFloatAsState(
    targetValue = if (isFocused) focusedScale else 1.0f,
    animationSpec = tween(durationMillis = 180),
    label = "FocusScale"
  )

  Box(
    modifier = modifier
      .scale(scale)
      .onFocusChanged { isFocused = it.isFocused }
      .focusable()
      .clickable(onClick = onClick)
      .clip(shape)
  ) {
    content(isFocused)
  }
}

@Composable
fun TvFocusableBox(
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(12.dp),
  focusedScale: Float = 1.05f,
  focusedBorderColor: Color = NovaCyanBright,
  unfocusedBorderColor: Color = Color.Transparent,
  borderWidth: Dp = 2.5.dp,
  onClick: () -> Unit = {},
  content: @Composable BoxScope.(isFocused: Boolean) -> Unit
) {
  var isFocused by remember { mutableStateOf(false) }
  val scale by animateFloatAsState(
    targetValue = if (isFocused) focusedScale else 1.0f,
    animationSpec = tween(durationMillis = 180),
    label = "TvFocusScale"
  )
  val interactionSource = remember { MutableInteractionSource() }

   Box(
     modifier = modifier
       .zIndex(if (isFocused) 10f else 1f)
       .scale(scale)
       .onFocusChanged { isFocused = it.isFocused }
       .focusable(interactionSource = interactionSource)
       .clickable(
         interactionSource = interactionSource,
         indication = null,
         onClick = onClick
       )
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
    shape = RoundedCornerShape(10.dp),
    focusedScale = 1.06f,
    onClick = onClick
  ) { isFocused ->
    val bgBrush = when {
      isFocused && isPrimary -> Brush.horizontalGradient(listOf(NovaCyan, NovaCyanBright))
      isFocused && !isPrimary -> Brush.horizontalGradient(listOf(NovaSurfaceVariant, NovaCyan.copy(alpha = 0.3f)))
      isPrimary -> Brush.horizontalGradient(listOf(NovaCyan.copy(alpha = 0.9f), NovaCyan))
      else -> Brush.horizontalGradient(listOf(Color(0xFF1E2638), Color(0xFF161E30)))
    }

    val contentColor = when {
      isPrimary && isFocused -> Color.Black
      isPrimary -> Color.Black
      isFocused -> NovaCyanBright
      else -> NovaTextPrimary
    }

    Row(
      modifier = Modifier
        .background(bgBrush)
        .padding(horizontal = 20.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      if (icon != null) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = contentColor,
          modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
      }
      Text(
        text = text,
        color = contentColor,
        fontSize = 15.sp,
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
