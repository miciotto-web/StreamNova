package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StreamNovaColorScheme = darkColorScheme(
  primary = NovaCyan,
  onPrimary = Color.Black,
  primaryContainer = NovaCyanDark,
  onPrimaryContainer = NovaCyanBright,
  secondary = NovaCyanBright,
  onSecondary = Color.Black,
  secondaryContainer = NovaSurfaceVariant,
  onSecondaryContainer = NovaTextPrimary,
  tertiary = NovaGold,
  onTertiary = Color.Black,
  background = NovaBackground,
  onBackground = NovaTextPrimary,
  surface = NovaSurface,
  onSurface = NovaTextPrimary,
  surfaceVariant = NovaSurfaceVariant,
  onSurfaceVariant = NovaTextSecondary,
  outline = NovaDivider,
  outlineVariant = NovaCyanSubtle,
)

@Composable
fun MyApplicationTheme(
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = StreamNovaColorScheme,
    typography = Typography,
    content = content,
  )
}
