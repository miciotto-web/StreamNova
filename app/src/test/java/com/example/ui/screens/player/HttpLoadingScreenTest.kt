package com.example.ui.screens.player

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.prefs.StreamingEngineMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test della logica **pura** della schermata di caricamento HTTP: quando appare, quando
 * sparisce e quale artwork (immagine/logo) usa.
 *
 * La scena è legata al caricamento INIZIALE del flusso: `isPlayerReady` distingue il
 * primo prepare (false) dal primo `Player.STATE_READY` (true), così gli stalli di una
 * riproduzione già avviata non la riattivano mai. Il filtro sul motore basta da solo:
 * in HTTP_WEB il resolver esegue solo provider HTTP integrati, quindi ogni sorgente
 * della sessione appartiene al percorso HTTP.
 */
class HttpLoadingScreenTest {

  private fun media(
    id: String = "tmdb_movie_1",
    title: String = "Titolo localizzato",
    originalTitle: String = "",
    logoUrl: String? = null,
    backdropUrl: String? = null,
    posterUrl: String? = null
  ): MediaItem = MediaItem(
    id = id,
    title = title,
    originalTitle = originalTitle,
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    logoUrl = logoUrl,
    backdropUrl = backdropUrl,
    posterUrl = posterUrl
  )

  private fun shouldShow(
    isHttpEngine: Boolean = true,
    isBuffering: Boolean = true,
    isPlayerReady: Boolean = false,
    hasPlaybackError: Boolean = false,
    isScrubbing: Boolean = false
  ): Boolean = HttpLoadingScreen.shouldShow(
    isHttpEngine = isHttpEngine,
    isBuffering = isBuffering,
    isPlayerReady = isPlayerReady,
    hasPlaybackError = hasPlaybackError,
    isScrubbing = isScrubbing
  )

  // ---------------------------------------------------------------------------
  // Identificazione dell'avvio HTTP
  // ---------------------------------------------------------------------------

  @Test
  fun `la modalita HTTP_WEB e un avvio HTTP senza controlli sulla sorgente`() {
    assertTrue(HttpLoadingScreen.isHttpStartup(StreamingEngineMode.HTTP_WEB))
  }

  @Test
  fun `la modalita Debrid non e mai un avvio HTTP`() {
    assertFalse(HttpLoadingScreen.isHttpStartup(StreamingEngineMode.DEBRID_TORBOX))
  }

  // ---------------------------------------------------------------------------
  // Visibilità durante il ciclo di caricamento
  // ---------------------------------------------------------------------------

  @Test
  fun `appare durante il caricamento iniziale HTTP`() {
    assertTrue(shouldShow(isBuffering = true, isPlayerReady = false))
  }

  @Test
  fun `appare anche prima che la sorgente sia risolta`() {
    // Fase PREPARING_SOURCE: nessun URL ancora, player in attesa. La scena copre sia
    // l'attesa di risoluzione sia il primo buffering, senza eccezioni sulla sorgente.
    assertTrue(shouldShow(isBuffering = true, isPlayerReady = false))
  }

  @Test
  fun `scompare appena il player diventa pronto`() {
    assertFalse(shouldShow(isBuffering = false, isPlayerReady = true))
    // Anche nel frame in cui READY e buffering convivessero, il player pronto vince.
    assertFalse(shouldShow(isBuffering = true, isPlayerReady = true))
  }

  @Test
  fun `non appare durante il buffering successivo alla prima riproduzione`() {
    assertFalse(shouldShow(isBuffering = true, isPlayerReady = true))
  }

  @Test
  fun `non appare a video in pausa dopo l avvio`() {
    assertFalse(shouldShow(isBuffering = false, isPlayerReady = true))
  }

  @Test
  fun `non appare nei flussi non HTTP`() {
    assertFalse(shouldShow(isHttpEngine = false))
  }

  @Test
  fun `l errore di avvio rimuove la schermata di loading`() {
    assertFalse(shouldShow(hasPlaybackError = true))
  }

  @Test
  fun `lo scrubbing lascia a video solo l anteprima della posizione`() {
    assertFalse(shouldShow(isScrubbing = true))
  }

  // ---------------------------------------------------------------------------
  // Artwork: immagine/logo del contenuto corretto e fallback
  // ---------------------------------------------------------------------------

  @Test
  fun `usa logo e immagine del contenuto della sessione`() {
    val artwork = HttpLoadingScreen.artworkOf(
      media(
        title = "Tulsa King",
        logoUrl = "https://image.tmdb.org/t/p/w500/tulsa_logo.png",
        backdropUrl = "https://image.tmdb.org/t/p/w1280/tulsa_backdrop.jpg",
        posterUrl = "https://image.tmdb.org/t/p/w500/tulsa_poster.jpg"
      )
    )
    assertEquals("https://image.tmdb.org/t/p/w1280/tulsa_backdrop.jpg", artwork.backgroundUrl)
    assertEquals("https://image.tmdb.org/t/p/w500/tulsa_logo.png", artwork.logoUrl)
    assertEquals("Tulsa King", artwork.fallbackTitle)
  }

  @Test
  fun `senza backdrop usa il poster come immagine di sfondo`() {
    val artwork = HttpLoadingScreen.artworkOf(
      media(
        backdropUrl = null,
        posterUrl = "https://image.tmdb.org/t/p/w500/poster.jpg",
        logoUrl = "https://image.tmdb.org/t/p/w500/logo.png"
      )
    )
    assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", artwork.backgroundUrl)
  }

  @Test
  fun `senza logo il fallback e il titolo originale`() {
    val artwork = HttpLoadingScreen.artworkOf(
      media(title = "Titolo localizzato", originalTitle = "Original Title", logoUrl = null)
    )
    assertNull(artwork.logoUrl)
    assertEquals("Original Title", artwork.fallbackTitle)
  }

  @Test
  fun `senza titolo originale il fallback usa il titolo localizzato`() {
    val artwork = HttpLoadingScreen.artworkOf(
      media(title = "Titolo localizzato", originalTitle = "   ", logoUrl = "")
    )
    assertNull(artwork.logoUrl)
    assertEquals("Titolo localizzato", artwork.fallbackTitle)
  }

  @Test
  fun `senza alcuna immagine la schermata resta senza sfondo ma con titolo`() {
    val artwork = HttpLoadingScreen.artworkOf(
      media(
        title = "Titolo",
        logoUrl = null,
        backdropUrl = null,
        posterUrl = null
      )
    )
    assertNull(artwork.backgroundUrl)
    assertNull(artwork.logoUrl)
    assertEquals("Titolo", artwork.fallbackTitle)
  }
}
