package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaCyanGlow
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.viewmodel.SidebarSection

data class NavigationMenuItem(
  val section: SidebarSection,
  val icon: ImageVector,
  val label: String
)

@Composable
fun SidebarNavigation(
  currentSection: SidebarSection,
  onSectionSelected: (SidebarSection) -> Unit,
  modifier: Modifier = Modifier
) {
  var isSidebarFocused by remember { mutableStateOf(false) }

  val sidebarWidth by animateDpAsState(
    targetValue = if (isSidebarFocused) 220.dp else 76.dp,
    animationSpec = tween(durationMillis = 220),
    label = "SidebarWidth"
  )

  val menuItems = listOf(
    NavigationMenuItem(SidebarSection.HOME, Icons.Default.Home, "Home"),
    NavigationMenuItem(SidebarSection.FILM, Icons.Default.Movie, "Film"),
    NavigationMenuItem(SidebarSection.SERIE_TV, Icons.Default.Tv, "Serie TV"),
    NavigationMenuItem(SidebarSection.I_MIEI_CONTENUTI, Icons.Default.Bookmark, "I miei contenuti"),
    NavigationMenuItem(SidebarSection.CERCA, Icons.Default.Search, "Cerca"),
    NavigationMenuItem(SidebarSection.IMPOSTAZIONI, Icons.Default.Settings, "Impostazioni"),
  )

  Column(
    modifier = modifier
      .fillMaxHeight()
      .width(sidebarWidth)
      .background(
        Brush.horizontalGradient(
          colors = listOf(
            NovaSurface,
            NovaSurface.copy(alpha = 0.95f),
            Color.Transparent
          )
        )
      )
      .padding(vertical = 24.dp, horizontal = 10.dp),
    verticalArrangement = Arrangement.SpaceBetween,
    horizontalAlignment = Alignment.Start
  ) {
    // Logo & App Name
    Row(
      modifier = Modifier
        .padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier
          .size(42.dp)
          .clip(CircleShape)
          .background(Color(0xFF0F172A)),
        contentAlignment = Alignment.Center
      ) {
        Image(
          painter = painterResource(id = R.drawable.ic_streamnova_logo),
          contentDescription = "StreamNova Logo",
          modifier = Modifier.size(38.dp)
        )
      }

      AnimatedVisibility(
        visible = isSidebarFocused,
        enter = fadeIn(),
        exit = fadeOut()
      ) {
        Row(modifier = Modifier.padding(start = 12.dp)) {
          Text(
            text = "Stream",
            color = NovaTextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Nova",
            color = NovaCyanBright,
            fontSize = 18.sp,
            fontWeight = FontWeight.Black
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Navigation Items
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      menuItems.forEach { item ->
        val isSelected = currentSection == item.section

        TvFocusableBox(
          modifier = Modifier.height(48.dp),
          shape = RoundedCornerShape(10.dp),
          focusedScale = 1.04f,
          onClick = {
            onSectionSelected(item.section)
          }
        ) { isFocused ->
          // Track whether any item inside sidebar is focused to expand sidebar
          if (isFocused && !isSidebarFocused) {
            isSidebarFocused = true
          }

          val bgColor by animateColorAsState(
            targetValue = when {
              isFocused -> NovaCyan.copy(alpha = 0.25f)
              isSelected -> Color(0x3300A3FF)
              else -> Color.Transparent
            },
            label = "SidebarItemBg"
          )

          val contentColor by animateColorAsState(
            targetValue = when {
              isFocused -> NovaCyanBright
              isSelected -> NovaCyan
              else -> NovaTextSecondary
            },
            label = "SidebarItemColor"
          )

          Row(
            modifier = Modifier
              .background(bgColor, RoundedCornerShape(10.dp))
              .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = item.icon,
              contentDescription = item.label,
              tint = contentColor,
              modifier = Modifier.size(24.dp)
            )

            AnimatedVisibility(
              visible = isSidebarFocused,
              enter = fadeIn(),
              exit = fadeOut()
            ) {
              Text(
                text = item.label,
                color = if (isSelected || isFocused) NovaTextPrimary else NovaTextMuted,
                fontSize = 14.sp,
                fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Medium,
                modifier = Modifier.padding(start = 14.dp)
              )
            }
          }
        }
      }
    }

    // Profile / Status Avatar at bottom
    Row(
      modifier = Modifier
        .padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier
          .size(32.dp)
          .clip(CircleShape)
          .background(NovaCyan),
        contentAlignment = Alignment.Center
      ) {
        Text(
          text = "SN",
          color = Color.Black,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold
        )
      }
      AnimatedVisibility(
        visible = isSidebarFocused,
        enter = fadeIn(),
        exit = fadeOut()
      ) {
        Column(modifier = Modifier.padding(start = 10.dp)) {
          Text(
            text = "Profilo TV",
            color = NovaTextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
          )
          Text(
            text = "4K HDR Attivo",
            color = NovaCyanBright,
            fontSize = 10.sp
          )
        }
      }
    }
  }
}
