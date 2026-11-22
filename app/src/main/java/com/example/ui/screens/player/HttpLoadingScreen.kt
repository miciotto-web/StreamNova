package com.example.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.data.model.MediaItem
import com.example.data.prefs.StreamingEngineMode

/**
 * Decisione di visibilità della schermata di caricamento cinematografica del percorso
 * **HTTP** (stessa scena del percorso Debrid: immagine del titolo a tutto schermo +
 * logo originale pulsante + dicitura di caricamento).
 *
 * Il filtro sul motore basta da solo, senza controlli sulla sorgente: in
 * [StreamingEngineMode.HTTP_WEB] il resolver esegue SOLAMENTE i provider HTTP integrati
 * (StreamManager esclude Debrid/TorBox/Real-Debrid/Premiumize e gli addon Stremio sono
 * interrogati esclusivamente in DEBRID_TORBOX), quindi ogni sorgente della sessione
 * appartiene per costruzione al percorso HTTP.
 *
 * Perché non basta `isBuffering`: ExoPlayer entra in `STATE_BUFFERING` anche dopo che la
 * riproduzione è già partita (cambio rendition, seek, stallo di rete). In quei casi deve
 * restare l'overlay "Caricamento" compatto con isteresi ([PlayerBufferingOverlay]), non
 * una schermata a tutto schermo. La scena cinematografica è quindi legata al **primo**
 * caricamento del flusso: da quando il player viene preparato (inclusa l'attesa di
 * risoluzione della sorgente che precede il prepare) fino a `STATE_READY`
 * ([isPlayerReady]).
 *
 * Regole:
 *  - solo modalità [StreamingEngineMode.HTTP_WEB] (l'avvio Debrid ha la propria scena e
 *    resta identico a prima);
 *  - visibile solo durante il caricamento INIZIALE: buffering e player non ancora READY;
 *  - errore di riproduzione o di risoluzione → la schermata sparisce e vale il normale
 *    stato di errore già previsto;
 *  - scrubbing → fuori schermo (l'unico elemento a video è l'anteprima della posizione).
 *
 * Funzione PURA, verificabile in test JVM senza Compose né ExoPlayer.
 */
internal object HttpLoadingScreen {

  /**
   * `true` se la sessione corrente appartiene al percorso di avvio HTTP.
   *
   * Nessun controllo sulla [com.example.data.streaming.StreamSource]: in HTTP_WEB ogni
   * sorgente risolta è di un provider HTTP integrato, incluse le riaperture dalla cache
   * "ultimo flusso funzionante" e i canali LIVE.
   */
  fun isHttpStartup(engineMode: StreamingEngineMode): Boolean =
    engineMode == StreamingEngineMode.HTTP_WEB

  /**
   * `true` se mostrare la schermata cinematografica in questo istante.
   *
   * [isPlayerReady] diventa `false` all'apertura del player e a ogni nuovo caricamento
   * (episodio, sorgente, URL) e torna `true` al primo `Player.STATE_READY`: è quindi il
   * segnale che distingue il caricamento iniziale dai successivi stalli della
   * riproduzione, senza toccare il flag `isBuffering` usato dal resto del player.
   */
  fun shouldShow(
    isHttpEngine: Boolean,
    isBuffering: Boolean,
    isPlayerReady: Boolean,
    hasPlaybackError: Boolean,
    isScrubbing: Boolean
  ): Boolean = isHttpEngine && isBuffering && !isPlayerReady && !hasPlaybackError && !isScrubbing

  /**
   * Costruisce l'artwork della schermata dai metadati del titolo, senza richieste.
   * Risoluzione condivisa con il percorso Debrid ([playerLoadingArtworkOf]): backdrop
   * con fallback poster, logo originale, titolo come unico fallback esplicito.
   */
  fun artworkOf(media: MediaItem): PlayerLoadingArtwork = playerLoadingArtworkOf(media)
}

/** Tag di semantica della scena di caricamento HTTP (verifiche UI nei test). */
internal object HttpLoadingTestTags {
  const val ROOT = "http_loading_root"
  const val BACKGROUND = "http_loading_background"
  const val LOGO = "http_loading_logo"
  const val TITLE_FALLBACK = "http_loading_title_fallback"
  const val MESSAGE = "http_loading_message"
}

/** Tag della scena HTTP come argomento del contenuto condiviso. */
private val HttpLoadingTags = CinematicLoadingTags(
  root = HttpLoadingTestTags.ROOT,
  background = HttpLoadingTestTags.BACKGROUND,
  logo = HttpLoadingTestTags.LOGO,
  titleFallback = HttpLoadingTestTags.TITLE_FALLBACK,
  message = HttpLoadingTestTags.MESSAGE
)

/**
 * Schermata di caricamento del percorso HTTP: immagine del titolo a tutto schermo,
 * logo originale pulsante e dicitura localizzata "Caricamento flusso video…".
 *
 * La decisione "quando" mostrarla vive in [HttpLoadingScreen]; la resa è il contenuto
 * cinematografico condiviso [CinematicLoadingContent], identico a quello del percorso
 * Debrid. Come lì, `statusText` sostituisce il messaggio fisso quando l'impostazione
 * "Mostra stato di caricamento" è attiva, e `animationsEnabled = false` serve solo ai
 * test Robolectric.
 */
@Composable
internal fun HttpLoadingOverlay(
  artwork: PlayerLoadingArtwork,
  statusText: String? = null,
  modifier: Modifier = Modifier,
  animationsEnabled: Boolean = true
) {
  CinematicLoadingContent(
    artwork = artwork,
    statusText = statusText,
    fallbackMessage = stringResource(R.string.player_http_loading_stream),
    tags = HttpLoadingTags,
    modifier = modifier,
    animationsEnabled = animationsEnabled
  )
}
