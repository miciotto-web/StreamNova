package com.example.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.core.designsystem.R

/**
 * Asset e trattamento cromatico ufficiali delle piattaforme.
 *
 * La Home TV 1.0.4 mostra il wordmark orizzontale trasparente del brand, mai
 * l'icona quadrata dell'app. Gli stessi asset sono qui resi disponibili al
 * design system condiviso: TV e Mobile possono disegnare la card provider con
 * lo stesso logo, lo stesso gradiente e le stesse proporzioni, senza ricomporre
 * il marchio con testo.
 *
 * Gli id sono quelli TMDB verificati per l'Italia; `logoUrl` resta il fallback
 * per un provider non mappato (nessun logo inventato).
 */
object ProviderBranding {

  @DrawableRes
  fun logoRes(tmdbProviderId: Int): Int? = when (tmdbProviderId) {
    8 -> R.drawable.provider_netflix
    9, 119 -> R.drawable.provider_prime
    337 -> R.drawable.provider_disney
    384, 49, 1899 -> R.drawable.provider_hbomax
    350 -> R.drawable.provider_appletv
    531 -> R.drawable.provider_paramount
    39 -> R.drawable.provider_nowtv
    283 -> R.drawable.provider_crunchyroll
    else -> null
  }

  /**
   * Altezza del riquadro logo per una card da 84dp. Riprende le proporzioni
   * della card TV da 112dp: i wordmark larghi (Netflix/Prime) restano bassi,
   * quelli compatti (Disney+, HBO Max) più alti, sempre con `ContentScale.Fit`.
   */
  fun logoHeight(tmdbProviderId: Int): Dp = when (tmdbProviderId) {
    337 -> 42.dp
    384, 49, 1899 -> 38.dp
    350 -> 34.dp
    else -> 30.dp
  }

  /** Sfondo del brand, identico ai gradienti della card provider TV. */
  fun background(tmdbProviderId: Int): Brush = when (tmdbProviderId) {
    // Netflix: radiale scuro carbone
    8 -> Brush.radialGradient(
      listOf(Color(0xFF1A1A1A), Color(0xFF050505))
    )
    // Prime Video: radiale blu centrale
    9, 119 -> Brush.radialGradient(
      listOf(Color(0xFF003A70), Color(0xFF051426), Color(0xFF020710))
    )
    // Disney+: radiale blu navy
    337 -> Brush.radialGradient(
      listOf(Color(0xFF0C244D), Color(0xFF041021), Color(0xFF01060E))
    )
    // HBO Max: radiale violaceo
    384, 49, 1899 -> Brush.radialGradient(
      listOf(Color(0xFF320C57), Color(0xFF150526), Color(0xFF08020F))
    )
    // Apple TV: lineare lilla/violaceo
    350 -> Brush.linearGradient(
      listOf(Color(0xFF4A4066), Color(0xFF251F33), Color(0xFF181421))
    )
    // Paramount+: radiale blu
    531 -> Brush.radialGradient(
      listOf(Color(0xFF0055B8), Color(0xFF00224D), Color(0xFF030A14))
    )
    // Crunchyroll: radiale arancione
    283 -> Brush.radialGradient(
      listOf(Color(0xFFF47521), Color(0xFF5E2B06), Color(0xFF140A04))
    )
    else -> Brush.radialGradient(
      listOf(Color(0xFF2B2D30), Color(0xFF141517))
    )
  }
}
