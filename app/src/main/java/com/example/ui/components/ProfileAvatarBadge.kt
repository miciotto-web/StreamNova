package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.example.data.profiles.ProfileAvatar
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright

/**
 * Cerchio-avatar condiviso da picker/gestione profili e sidebar.
 *
 * Mostra l'emoji scelta oppure, se assente, l'iniziale del nome; il colore è sempre
 * quello del profilo. Estratto in `ui.components` per evitare due rendering diversi
 * dello stesso avatar tra la gestione profili e la sidebar.
 */
@Composable
fun ProfileAvatarBadge(
  emoji: String?,
  initial: String,
  avatar: ProfileAvatar,
  size: Dp,
  fontSize: TextUnit,
  modifier: Modifier = Modifier
) {
  Box(
    modifier = modifier
      .size(size)
      .background(Brush.linearGradient(avatar.gradientColors()), CircleShape)
      .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape),
    contentAlignment = Alignment.Center
  ) {
    Text(
      text = emoji ?: initial,
      color = if (emoji == null) Color.White else Color.Unspecified,
      // Le emoji occupano meno altezza rispetto alla loro dimensione nominale:
      // un leggero aumento le rende leggibili a distanza sul televisore.
      fontSize = if (emoji == null) fontSize else fontSize * 1.15f,
      fontWeight = if (emoji == null) FontWeight.Bold else FontWeight.Normal
    )
  }
}

/** Gradiente del colore-avatar, condiviso da cerchi e selettore colore. */
fun ProfileAvatar.gradientColors(): List<Color> = when (this) {
  ProfileAvatar.CYAN -> listOf(NovaCyan, NovaCyanBright)
  ProfileAvatar.VIOLET -> listOf(Color(0xFF7B2FF7), Color(0xFFB388FF))
  ProfileAvatar.AMBER -> listOf(Color(0xFFFF8A00), Color(0xFFFFC837))
  ProfileAvatar.GREEN -> listOf(Color(0xFF00A86B), Color(0xFF57E389))
  ProfileAvatar.ROSE -> listOf(Color(0xFFD81B60), Color(0xFFFF6F91))
  ProfileAvatar.BLUE -> listOf(Color(0xFF2962FF), Color(0xFF64B5F6))
}
