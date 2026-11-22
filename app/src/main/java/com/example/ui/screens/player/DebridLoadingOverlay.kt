package com.example.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.R

/** Tag di semantica della scena di caricamento Debrid (verifiche UI nei test). */
internal object DebridLoadingTestTags {
  const val ROOT = "debrid_loading_root"
  const val BACKGROUND = "debrid_loading_background"
  const val LOGO = "debrid_loading_logo"
  const val TITLE_FALLBACK = "debrid_loading_title_fallback"
  const val MESSAGE = "debrid_loading_message"
}

/** Tag della scena Debrid come argomento del contenuto condiviso. */
private val DebridLoadingTags = CinematicLoadingTags(
  root = DebridLoadingTestTags.ROOT,
  background = DebridLoadingTestTags.BACKGROUND,
  logo = DebridLoadingTestTags.LOGO,
  titleFallback = DebridLoadingTestTags.TITLE_FALLBACK,
  message = DebridLoadingTestTags.MESSAGE
)

/**
 * Schermata di caricamento del percorso Debrid: poster/immagine del titolo a tutto
 * schermo, scrim scuro per la leggibilità, logo originale pulsante al centro e dicitura
 * localizzata del caricamento.
 *
 * La decisione "quando" mostrare la scena vive in [DebridLoadingScreen]; la resa è il
 * contenuto cinematografico condiviso [CinematicLoadingContent], identico a quello del
 * percorso HTTP. `statusText` e `animationsEnabled` conservano il contratto storico.
 */
@Composable
internal fun DebridLoadingOverlay(
  artwork: PlayerLoadingArtwork,
  statusText: String? = null,
  modifier: Modifier = Modifier,
  animationsEnabled: Boolean = true
) {
  CinematicLoadingContent(
    artwork = artwork,
    statusText = statusText,
    fallbackMessage = stringResource(R.string.player_debrid_loading_stream),
    tags = DebridLoadingTags,
    modifier = modifier,
    animationsEnabled = animationsEnabled
  )
}
