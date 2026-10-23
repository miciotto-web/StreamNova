package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.util.Log
import com.example.data.prefs.DecoderFallbackMode
import com.example.ui.screens.player.*
import androidx.media3.datasource.HttpDataSource
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import com.example.R
import androidx.media3.common.C
import androidx.media3.common.PlaybackException as ExoPlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.example.ui.screens.player.PlayerIntroUtils
import com.example.ui.screens.player.PlayerIntroUtils.IntroWindow
import com.example.data.repository.IntroSkipRepository
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.example.data.model.AudioTrack
import com.example.data.model.Episode
import com.example.data.model.MediaType
import com.example.data.model.SubtitleTrack
import com.example.data.model.VideoResolution
import com.example.data.prefs.PreferredResolution
import com.example.data.prefs.SubtitleBackground
import com.example.data.repository.MediaRepository
import com.example.data.repository.SimklEpisodePayload
import com.example.data.repository.SimklMediaPayload
import com.example.data.streaming.diagnostics.StreamDiagnostics
import com.example.ui.screens.player.SimklScrobbler
import com.example.ui.components.TvFocusableBox
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaCyanGlow
import com.example.ui.theme.NovaGreen
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.util.streamUnavailableMessage
import java.util.Locale
import com.example.ui.viewmodel.StreamNovaViewModel
import com.example.ui.viewmodel.StreamResolutionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Auto-hide dei controlli del player dopo N ms di inattività con il telecomando. */
private const val CONTROLS_HIDE_DELAY_MS = 4_000L

/**
 * User-Agent HTTP di default per il player.
 *
 * Restato identico a quello usato in precedenza: nessun timeout, nessuna
 * policy di buffering e nessun header di rete viene modificato.
 */
private const val DEFAULT_HTTP_USER_AGENT = "StreamNovaTV/1.0 (Linux; Android TV; Media3 ExoPlayer)"

private fun simklProgressPercent(positionMs: Long, durationMs: Long): Double =
  if (durationMs > 0L) (positionMs.toDouble() / durationMs.toDouble() * 100.0).coerceIn(0.0, 100.0) else 0.0

/**
 * Tentativi massimi di ripresa dopo un errore di una traccia sottotitoli esterna.
 * Un errore sottotitoli non è mai un errore video: si riprende senza quella traccia,
 * senza toccare `maxVideoSize` e senza escludere la traccia video.
 */
private const val MAX_SUBTITLE_ERROR_RECOVERIES = 2

/**
 * FASE 3.4 "Sfondo dei sottotitoli": costruisce il CaptionStyleCompat da applicare al
 * SubtitleView Media3 (API disponibile in 1.5.1: SubtitleView.setStyle). Le altre
 * proprietà restano quelle del default Media3 (testo bianco, nessun bordo, window
 * trasparente): varia solo il colore di sfondo dietro al testo, così che:
 * - NESSUNO: alpha 0 → SubtitlePainter non disegna alcun background;
 * - NERO: nero opaco; NERO SEMI-TRASPARENTE: nero con trasparenza (50%).
 */
private fun subtitleCaptionStyleFor(background: SubtitleBackground): CaptionStyleCompat {
  val base = CaptionStyleCompat.DEFAULT
  return CaptionStyleCompat(
    base.foregroundColor,
    background.backgroundColor,
    base.windowColor,
    base.edgeType,
    base.edgeColor,
    base.typeface
  )
}

/**
 * Richiede il focus sul frame successivo (dopo compose + layout) e riprova qualche
 * volta: al momento della chiamata l'elemento può non essere ancora agganciato al
 * sistema di focus (HUD che sta entrando, modale che sta chiudendo, ...).
 */
private suspend fun FocusRequester.requestFocusAfterFrame(attempts: Int = 5) {
  repeat(attempts) {
    withFrameNanos { }
    try {
      requestFocus()
      return
    } catch (e: IllegalStateException) {
      Log.w("PlayerScreen", "FocusRequester non ancora pronto: ${e.message}")
      delay(50)
    }
  }
}

/** Superficie del player che deve possedere il focus quando il video è "pulito" (HUD nascosto). */
internal enum class VideoFocusTarget { NONE, INTRO_BANNER, IDLE }

/**
 * Seleziona il target di focus per lo stato corrente. Funzione PURA, testabile senza Compose.
 *
 * Regole:
 * - scrubbing, modali o overlay "Prossimo Episodio" -> NONE (il focus non va toccato);
 * - HUD a schermo -> NONE (gestito dagli effetti HUD esistenti);
 * - video pulito con intro attiva -> INTRO_BANNER;
 * - video pulito senza intro -> IDLE.
 */
internal fun resolveVideoFocusTarget(
  hudShown: Boolean,
  isIntroActive: Boolean,
  isScrubbing: Boolean,
  anyModalOpen: Boolean,
  isNextEpisodePromptVisible: Boolean
): VideoFocusTarget {
  if (anyModalOpen || isNextEpisodePromptVisible || isScrubbing) return VideoFocusTarget.NONE
  if (hudShown) return VideoFocusTarget.NONE
  return if (isIntroActive) VideoFocusTarget.INTRO_BANNER else VideoFocusTarget.IDLE
}

/**
 * Risale alla [Activity] ospite anche quando il [Context] è un wrapper
 * (ContextThemeWrapper/ContextWrapper), così da poter gestire i flag della
 * finestra (es. [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]).
 */
internal fun Context.findActivity(): Activity? {
  var ctx: Context? = this
  while (ctx is ContextWrapper) {
    if (ctx is Activity) return ctx
    ctx = ctx.baseContext
  }
  return ctx as? Activity
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
  viewModel: StreamNovaViewModel,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val playbackState by viewModel.playbackState.collectAsState()
  val playbackSettings by viewModel.playbackSettings.collectAsState()

  /**
   * Lingua di riferimento per preselezionare i sottotitoli esterni con
   * C.SELECTION_FLAG_DEFAULT nel MediaItem: la preferita dell'utente, oppure
   * la lingua di sistema quando l'utente ha scelto "Originale / Qualsiasi".
   * Dichiarata prima dell'istanziazione del player perché usata da buildMediaItem.
   */
  val defaultSubtitleSelectionLanguage: String =
    playbackSettings.preferredSubtitleLanguage.trim().ifEmpty {
      java.util.Locale.getDefault().language
    }

  val showSourceDialog by viewModel.showSourceDialog.collectAsState()
  val streamResolutionState by viewModel.streamResolutionState.collectAsState()
  val onlineSubtitles by viewModel.onlineSubtitles.collectAsState()
  val isOnlineSubtitlesLoading by viewModel.isOnlineSubtitlesLoading.collectAsState()

  // ── Selettore sorgenti interno al player ───────────────────────────────
  // Sorgenti valide disponibili per il titolo corrente (aggiornate in modo
  // progressivo man mano che i provider rispondono) e visibilita' del
  // selettore. Il comando e' disponibile durante la riproduzione, il buffering
  // e lo stato di errore.
  val sourceCandidates by viewModel.sourceCandidates.collectAsState()
  val showSourceSelector by viewModel.showSourceSelector.collectAsState()
  // Token di cambio sorgente: permette di ricaricare il flusso sulla stessa
  // istanza di ExoPlayer anche quando l'URL non cambia.
  val manualSourceSelectionToken by viewModel.manualSourceSelectionToken.collectAsState()
  val sourceSelectorEntries = remember(sourceCandidates, playbackState.currentSource) {
    PlayerSourceSelector.buildEntries(sourceCandidates, playbackState.currentSource)
  }

  val media = playbackState.media ?: return
  val episode = playbackState.currentEpisode
  val simklCoroutineScope = rememberCoroutineScope()
  val simklMedia = remember(media.id, media.title, media.year, media.tmdbId, media.effectiveImdbId, episode?.seasonNumber, episode?.episodeNumber) {
    SimklMediaPayload(
      title = media.title,
      year = media.year.takeIf { it > 0 },
      tmdbId = media.tmdbId,
      imdbId = episode?.effectiveImdbId ?: media.effectiveImdbId,
      episode = episode?.let { SimklEpisodePayload(it.seasonNumber, it.episodeNumber) }
    )
  }
  val simklScrobbler = remember(simklMedia) { SimklScrobbler(simklCoroutineScope, simklMedia) }

  // Selezione sorgente attiva: il flag del dialog basta da solo (anche con lista
  // vuota). Durante il fallback manuale dell'auto-pick del prossimo episodio la
  // sessione NON deve mai marcarsi come fallita ne' mostrare la schermata nera.
  val isSourceSelectionActive = showSourceDialog

  val isTvShow = PlayerIntroUtils.isTvShow(
    isTvShow = media.type == MediaType.SERIE_TV,
    seasonNumber = episode?.seasonNumber ?: media.lastWatchedSeason,
    episodeNumber = episode?.episodeNumber ?: media.lastWatchedEpisode,
    mediaType = media.type.name
  ) || media.episodes.isNotEmpty() || media.type.labelItalian.contains("serie", ignoreCase = true)

  // Episodio attivo (se TV Show ma episode è null, cerca il primo o l'ultimo guardato)
  val currentEp = episode ?: if (isTvShow) {
    media.episodes.firstOrNull {
      it.seasonNumber == (media.lastWatchedSeason ?: 1) &&
      it.episodeNumber == (media.lastWatchedEpisode ?: 1)
    } ?: media.episodes.firstOrNull()
  } else null

  // Sottotitolo episodio: visibile SOLO per Serie TV ("S1E1 • Titolo episodio")
  val episodeSubtitle = if (isTvShow && currentEp != null) {
    "S${currentEp.seasonNumber}E${currentEp.episodeNumber} • ${currentEp.title}"
  } else null

  // Prossimo episodio: risolto dal ViewModel (stessa stagione E+1, poi stagione
  // successiva E1). Il ViewModel carica in background gli episodi da TMDB quando
  // media.episodes è vuota (item da catalogo/Cinemeta), garantendo che il valore
  // sia disponibile anche riproducendo da "Continua a guardare" o da link riusato.
  val nextEpisode by viewModel.nextEpisode.collectAsState()

  var isBuffering by remember { mutableStateOf(true) }
  var currentPosition by remember { mutableLongStateOf(playbackState.currentPositionMs) }
  var totalDuration by remember { mutableLongStateOf(playbackState.durationMs.coerceAtLeast(1L)) }
  var isPlaying by remember { mutableStateOf(true) }
  var areControlsVisible by remember { mutableStateOf(true) }
  // Scrubbing a schermo intero (◀ / ▶ con i controlli nascosti): stato di anteprima
  // pubblicato dal controller, null quando lo scrubbing non è in corso. Durante lo
  // scrubbing l'HUD resta fuori schermo e l'unico elemento a video è l'overlay di anteprima.
  var scrubState by remember { mutableStateOf<PlayerScrubUiState?>(null) }
  val isScrubbing = scrubState != null
  var playbackErrorMessage by remember { mutableStateOf<String?>(null) }

  // FLAG CRITICO: Indica se siamo in fase di transizione verso il prossimo episodio.
  // Durante questa fase, videoUrl può essere temporaneamente null mentre la nuova
  // sorgente viene risolta. Non dobbiamo mostrare "Nessuna sorgente disponibile"
  // ma invece attendere il completamento della risoluzione.
  // FIX CRITICO: il flag è raccolto DIRETTAMENTE dal StateFlow del ViewModel.
  // In precedenza era una variabile locale `remember` mai aggiornata (sempre false):
  // durante il cambio episodio il player non entrava mai in modalità "attesa
  // risoluzione" e la transizione restava bloccata sul caricamento.
  val isSwitchingEpisode by viewModel.isSwitchingEpisode.collectAsState()

  // Risoluzione video rilevata in tempo reale da ExoPlayer (es. 1080p, 720p, 4K)
  var detectedResolution by remember { mutableStateOf("") }
  var selectedQualityLabel by remember { mutableStateOf("Auto") }

  // Stato per l'applicazione della "Risoluzione preferita" persistente (una volta per episodio)
  var playerReady by remember { mutableStateOf(false) }
  var initialQualityApplied by remember { mutableStateOf(false) }
  // Stato per il passaggio automatico all'episodio successivo a fine riproduzione
  var playbackEnded by remember { mutableStateOf(false) }
  // Sessione fallita/non conclusa: errore ExoPlayer, streamUrl assente/vuoto o errore di
  // risoluzione. Un errore NON deve mai causare il passaggio all'episodio successivo.
  var playbackFailed by remember { mutableStateOf(false) }
  // Stato overlay "Prossimo Episodio": chiusura esplicita (BACK, nascosto fino alla fine
  // del video per i titoli di coda) e secondi rimanenti del countdown automatico.
  var nextEpisodeDismissed by remember { mutableStateOf(false) }
  var nextEpisodeCountdownSeconds by remember { mutableIntStateOf(0) }

  // Overlay "Prossimo Episodio": attivo negli ultimi 35 secondi o a STATE_ENDED.
  // Durante lo scrubbing resta fuori schermo: nessun overlay e nessun countdown di
  // auto-avanzamento mentre l'utente sta solo cercando nel contenuto.
  val isNextEpisodePromptVisible = !isScrubbing && NextEpisodePromptUtils.shouldShow(
    positionMs = currentPosition,
    durationMs = totalDuration,
    playbackEnded = playbackEnded,
    isTvShow = isTvShow,
    hasNextEpisode = nextEpisode != null,
    dismissed = nextEpisodeDismissed,
    playbackFailed = playbackFailed
  )

  // Aspect ratio / ResizeMode
  var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
  var resizeFeedbackText by remember { mutableStateOf<String?>(null) }

  // Messaggio discreto del cambio automatico di sorgente (fallback su errore
  // definitivo): nessun dialog, nessuna pausa della riproduzione.
  var sourceSwitchNotice by remember { mutableStateOf<String?>(null) }

  // Velocità di riproduzione
  var currentSpeed by remember { mutableFloatStateOf(1.0f) }

  // Modali
  var showTrackQuickPanel by remember { mutableStateOf(false) }
  var showSubtitleModal by remember { mutableStateOf(false) }
  var showQualityModal by remember { mutableStateOf(false) }
  var showSpeedModal by remember { mutableStateOf(false) }

  // Traccia manuale selezionata dall'utente: quando non null, indica che l'utente ha
  // fatto una scelta esplicita dal menu Player. Questo previene la ri-applicazione
  // automatica della preferenza Settings dopo una selezione manuale.
  var manualSubtitleSelection by remember { mutableStateOf<ManualSubtitleSelection?>(null) }
  var manualAudioSelection by remember { mutableStateOf<ManualAudioSelection?>(null) }

  val anyModalOpen = showTrackQuickPanel || showSubtitleModal || showQualityModal || showSpeedModal ||
    showSourceSelector

  LaunchedEffect(showSubtitleModal) {
    if (showSubtitleModal) {
      viewModel.loadOnlineSubtitles(media, currentEp)
    }
  }

  // ---------------------------------------------------------------------------
  // FOCUS D-PAD / TELECOMANDO TV
  // ---------------------------------------------------------------------------
  val playPauseFocusRequester = remember { FocusRequester() }
  val seekBarFocusRequester = remember { FocusRequester() }
  val skipIntroFocusRequester = remember { FocusRequester() }
  // Requester DEDICATO al banner flottante: NON deve essere condiviso con il pulsante
  // "Salta intro" dell'HUD. Un FocusRequester agganciato a due nodi (che coesistono durante
  // le transizioni di AnimatedVisibility) fa finire il focus sul primo agganciato, non
  // necessariamente sul banner visibile.
  val introBannerFocusRequester = remember { FocusRequester() }
  val nextEpisodeFocusRequester = remember { FocusRequester() }
  val idleFocusRequester = remember { FocusRequester() }
  // Requester DEDICATO al comando "Sorgente video" dell'HUD: il focus va ripristinato
  // qui alla chiusura del selettore (D-pad su Android TV).
  val sourceSelectorFocusRequester = remember { FocusRequester() }
  // Requester del comando "Sorgente video" mostrato negli overlay di buffering/errore.
  val sourceSelectorOverlayFocusRequester = remember { FocusRequester() }
  // Nodo a cui restituire il focus alla chiusura del selettore.
  var sourceSelectorReturnRequester by remember { mutableStateOf<FocusRequester?>(null) }
  val focusScope = rememberCoroutineScope()

  var hudHasFocus by remember { mutableStateOf(false) }
  // Il focus è dentro la schermata player (HUD, barra o nodo di riposo). Serve a garantire
  // che il telecomando resti del player: se il focus finisce sui controlli coperti del
  // catalogo, ◀ / ▶ non arrivano più allo scrubbing.
  var playerHasFocus by remember { mutableStateOf(false) }
  // Rilascio del tasto OK che ha confermato lo scrubbing: va consumato in preview, altrimenti
  // il clickable dello sfondo (tocco per mostrare/nascondere i controlli) aprirebbe l'HUD
  // subito dopo la conferma.
  val scrubOkReleasePending = remember { mutableStateOf(false) }
  val skipIntroOkReleasePending = remember { mutableStateOf(false) }
  var hideTimerTick by remember { mutableLongStateOf(0L) }

  // BACK gerarchico (livello 1): finché è aperto un modale, il tasto Back chiude SOLO il modale.
  BackHandler(enabled = anyModalOpen) {
    Log.d("BACK_TRACE", "PLAYERSCREEN: ingresso del BackHandler (modale)")
    Log.d("BACK_TRACE", "PLAYERSCREEN: valore di anyModalOpen: $anyModalOpen")
    showTrackQuickPanel = false
    showSubtitleModal = false
    showQualityModal = false
    showSpeedModal = false
  }

  val streamHeaders = playbackState.streamHeaders
  // FASE 3.3: frazione altezza per la dimensione testo sottotitoli (lettura tracciata in
  // composizione: qualsiasi cambio preferenza ri-esegue l'update del PlayerView).
  val subtitleTextSizeFraction = playbackSettings.subtitleSize.textSizeFraction
  // FASE 3.4: stile caption per lo sfondo dei sottotitoli (lettura tracciata in
  // composizione: qualsiasi cambio preferenza ri-esegue l'update del PlayerView).
  val subtitleCaptionStyle = subtitleCaptionStyleFor(playbackSettings.subtitleBackground)
  // FASE 3.5: frazione per la posizione verticale dei sottotitoli (lettura tracciata in
  // composizione: qualsiasi cambio preferenza ri-esegue l'update del PlayerView).
  val subtitleBottomPaddingFraction = playbackSettings.subtitlePosition.bottomPaddingFraction
  // Sorgente riproducibile SOLO se reale. Per un titolo TMDB la mancanza di stream NON
  // deve mai ripiegare su un video demo di un altro contenuto: in quel caso videoUrl = null.
  // Il fallback al video seed è consentito esclusivamente per gli elementi demo reali.
  //
  // IMPORTANTE durante il passaggio all'episodio successivo (isSwitchingEpisode=true):
  // videoUrl può essere temporaneamente null mentre la nuova sorgente viene risolta
  // in openStreamForMedia. In questo caso NON mostrare "Nessuna sorgente disponibile"
  // ma attendere che la risoluzione completi e videoUrl diventi disponibile.
  // FIX CRITICO: `isSwitchingEpisode` è ora il flag reattivo del ViewModel (raccolto
  // sopra). L'indirection locale `isCurrentlySwitching` (aggiornata da un
  // LaunchedEffect con un frame di ritardo) è stata rimossa: durante la transizione
  // il player deve entrare subito in modalità "attesa risoluzione".
  val isCurrentlySwitching = isSwitchingEpisode
  val videoUrl: String? = if (isCurrentlySwitching) {
    // Durante il cambio episodio: restituisci l'URL corrente se c'è, altrimenti null
    // per forzare il player ad attendere la risoluzione invece di fallire subito.
    playbackState.streamUrl?.takeIf { it.isNotBlank() }
  } else {
    playbackState.streamUrl?.takeIf { it.isNotBlank() }
      ?: if (MediaRepository.isDemoSeedItem(media.id)) {
        currentEp?.videoUrl ?: media.videoUrl
      } else {
        null
      }
  }

  if (media.tmdbId == 318508) {
    Log.d("THE_PITT_PLAYER", "PlayerScreen: media.tmdbId=318508, videoUrl=$videoUrl, streamUrl=${playbackState.streamUrl}, isDemoSeed=${MediaRepository.isDemoSeedItem(media.id)}")
  }

  // Factory HTTP STABILE: le intestazioni della sorgente attiva possono cambiare
  // (cambio manuale di sorgente dal player) senza dover ricreare l'ExoPlayer.
  //
  // `DefaultHttpDataSource.Factory` applica le "default request properties" al
  // momento della creazione del DataSource (cioe' al prepare()): aggiornarle in
  // place mantiene la STESSA istanza di player e evita duplicazioni/ricreazioni.
  // Con la ricreazione il player ripartirebbe da zero (nuovo buffering, decoder
  // da riavviare, posizione persa).
  val httpDataSourceFactory = remember {
    val factory = DefaultHttpDataSource.Factory()
      .setUserAgent(DEFAULT_HTTP_USER_AGENT)
      .setAllowCrossProtocolRedirects(true)
      .setConnectTimeoutMs(15000)
      .setReadTimeoutMs(20000)
    factory
  }
  // Le intestazioni vengono aggiornate DURANTE la composizione (non in un
  // SideEffect): quando il player viene ricreato (es. passaggio HLS <-> link
  // progressivo) il nuovo player carica il flusso subito, nello stesso ciclo di
  // composizione, e deve quindi trovare gia' le intestazioni aggiornate.
  // Il blocco `remember` e' dichiarato PRIMA del `remember` che crea il player.
  remember(streamHeaders) {
    httpDataSourceFactory.setUserAgent(streamHeaders["User-Agent"] ?: DEFAULT_HTTP_USER_AGENT)
    httpDataSourceFactory.setDefaultRequestProperties(streamHeaders)
  }

  // Sottotitoli esterni della sorgente attiva: vengono solo allegati al Media3
  // MediaItem, senza toccare la factory video (che resta l'unica DataSource.Factory
  // e quindi serve anche alle sidecar, come già avviene per le altre app Media3).
  val stremioSubtitles = playbackState.subtitles

  val mediaSourceFactory = remember(httpDataSourceFactory) {
    DefaultMediaSourceFactory(context)
      .setDataSourceFactory(httpDataSourceFactory)
      .setLoadErrorHandlingPolicy(PlayerLoadErrorHandlingPolicy())
  }

  // Tipo dell'ultimo URL valido (HLS vs progressivo). Durante il passaggio al
  // prossimo episodio `videoUrl` diventa temporaneamente null mentre la nuova
  // sorgente viene risolta: il tipo NON deve tornare a `false`, altrimenti il
  // `remember` che crea ExoPlayer verrebbe invalidato (nuova istanza del player =>
  // onDispose => reset della risoluzione in corso) e la transizione fallirebbe
  // con "Stream non disponibile". Manteniamo quindi l'ultimo tipo noto.
  val lastIsHlsStream = remember { mutableStateOf(false) }
  fun computeIsHlsStream(url: String): Boolean {
    val lower = url.lowercase()
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    val hint = playbackState.streamProgressive
    return when {
      path.endsWith(".m3u8") || lower.contains(".m3u8") -> true
      path.endsWith(".mp4") || path.endsWith(".mkv") ||
        path.endsWith(".webm") || path.endsWith(".avi") ||
        // Link Debrid CDN (TorBox) con estensione anche solo in query string:
        lower.contains(".mp4") || lower.contains(".mkv") || lower.contains(".m4v") -> false
      // Hint esplicito della sorgente: i link Debrid sbloccati (requestdl) sono
      // file progressivi anche quando l'URL non ha estensione.
      hint != null -> !hint
      playbackState.streamUrl != null -> true
      else -> false
    }
  }
  val isHlsStream = videoUrl?.let { computeIsHlsStream(it) } ?: lastIsHlsStream.value
  SideEffect {
    if (videoUrl != null) lastIsHlsStream.value = computeIsHlsStream(videoUrl)
  }

  val playbackMediaSourceFactory = remember(mediaSourceFactory, isHlsStream) {
    if (isHlsStream) {
      HlsMediaSource.Factory(httpDataSourceFactory)
    } else {
      mediaSourceFactory
    }
  }

  // Nuvio BandwidthMeter (stima iniziale a 50 Mbps per avvio al profilo a bitrate massimo)
  val bandwidthMeter = remember {
    NuvioExoPlayerPerformanceHelper.buildBandwidthMeter(context).also {
      Log.i("StreamNovaDiag", "NuvioBandwidthMeter inizializzato con stima: ${it.bitrateEstimate / 1_000_000} Mbps (${it.bitrateEstimate} bps)")
    }
  }

  /**
   * Caricamento stream UNIFORME su ExoPlayer: usato sia al primo avvio (S1E1, dentro il
   * `remember` che crea il player) sia al cambio episodio (S1E2..., nel
   * `LaunchedEffect(currentEpKey, videoUrl, exoPlayer)`).
   *
   * Stessa identica sequenza in entrambi i percorsi:
   * stop() + clearMediaItems() → PlayerSubtitleMediaItemBuilder (headers HTTP e
   * sottotitoli arrivano dalla DataSource.Factory / MediaSourceFactory del player,
   * quindi restano quelli della composizione corrente) → setMediaItem() → prepare() →
   * seekTo() → playWhenReady = true.
   * Nessun percorso può più divergere dall'altro (né per ordine delle chiamate né per
   * configurazione della sorgente).
   */
  fun loadStreamOnPlayer(player: ExoPlayer, url: String, positionMs: Long, source: String) {
    Log.d("[NEXT_EP_DEBUG]", "loadStreamOnPlayer($source): stop()+clearMediaItems() su $player")
    player.stop()
    player.clearMediaItems()
    val item = PlayerSubtitleMediaItemBuilder.buildMediaItem(url, stremioSubtitles)
    Log.d("[NEXT_EP_DEBUG]", "Esecuzione exoPlayer.setMediaItem con: $url")
    player.setMediaItem(item)
    player.prepare()
    player.seekTo(positionMs.coerceAtLeast(0L))
    player.playWhenReady = true
    Log.d(
      "[NEXT_EP_DEBUG]",
      "Riproduzione avviata: prepare()+playWhenReady=true eseguiti su $player (pos=$positionMs, source=$source)"
    )
  }

  // Nuvio TrackSelector e ExoPlayer configurati con NuvioRenderersFactory, BitrateAwareLoadControl e Decoder Fallback
  //
  // INVARIANTE CRITICO (cambio episodio): le chiavi di questo `remember` NON dipendono
  // da `videoUrl`, `currentEpKey` o `isSwitchingEpisode`. Durante il passaggio al
  // prossimo episodio `videoUrl` è temporaneamente null per ~1s: il player deve
  // restare la STESSA istanza viva e la struttura sottostante (DisposableEffect del
  // player + AndroidView) deve restare SEMPRE montata, senza alcun `return` anticipato.
  val (trackSelector, exoPlayer) = remember(
    playbackMediaSourceFactory,
    bandwidthMeter,
    playbackSettings.preferredResolution,
    playbackSettings.targetBuffer,
    playbackSettings.backBuffer,
    playbackSettings.audioPassthroughEnabled,
    playbackSettings.audioTunnelingEnabled,
    playbackSettings.decoderFallbackMode,
    playbackSettings.dolbyVisionFallbackEnabled,
    playbackSettings.autoFrameRateMatching
  ) {
    val isBluetooth = AudioOutputRouteDetector.isBluetoothMediaOutput(context)
    val extMode = when (playbackSettings.decoderFallbackMode) {
      DecoderFallbackMode.OFF -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
      DecoderFallbackMode.ON -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
      DecoderFallbackMode.PREFER -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
    }
    val renderersFactory = NuvioRenderersFactory(
      context = context,
      bluetoothForcePcm = isBluetooth,
      playbackSpeedProvider = { currentSpeed },
      audioPassthroughEnabled = playbackSettings.audioPassthroughEnabled,
      extensionRendererModePreference = extMode,
      dolbyVisionFallbackEnabled = playbackSettings.dolbyVisionFallbackEnabled,
      subtitleOffsetMsProvider = { viewModel.playbackState.value.subtitleOffsetMs }
    )
    val maxHeight = playbackSettings.preferredResolution.targetHeight
    val preferredAudioLangs = mapPreferredAudioLanguages(playbackSettings.preferredAudioLanguage)
    val trackSelector = NuvioTrackSelector.create(
      context = context,
      streamUrlProvider = { videoUrl },
      streamNameProvider = { media.title },
      tunnelingEnabled = playbackSettings.audioTunnelingEnabled,
      safeAudioMode = false,
      preferredAudioLanguages = preferredAudioLangs,
      subtitlesEnabled = playbackSettings.subtitlesEnabled,
      forcedSubtitlesEnabled = playbackSettings.forcedSubtitlesEnabled
    ).apply {
      val preferredRes = playbackSettings.preferredResolution
      val maxHeight = preferredRes.targetHeight
      val maxWidth = when {
        maxHeight >= 2160 -> 3840
        maxHeight >= 1080 -> 1920
        maxHeight >= 720 -> 1280
        maxHeight >= 480 -> 854
        else -> (maxHeight * 16) / 9
      }
      // Non applicare maxVideoSize per UHD_4K: lasciamo che ExoPlayer selecti le tracce 4K senza restrizioni
      if (maxHeight > 0 && preferredRes != PreferredResolution.UHD_4K) {
        setParameters(buildUponParameters().setMaxVideoSize(maxWidth, maxHeight))
        StreamNova4KDiag.line(
          "VINCOLO INIZIALE maxVideoSize=${maxWidth}x$maxHeight " +
            "(preferredResolution=${preferredRes.label})"
        )
      } else {
        StreamNova4KDiag.line(
          "VINCOLO INIZIALE maxVideoSize=nessuno " +
            "(preferredResolution=${preferredRes.label})"
        )
      }
    }
    val loadControl = NuvioExoPlayerPerformanceHelper.buildLoadControl(
      context = context,
      targetBufferSizeMbOverride = playbackSettings.targetBuffer.sizeMb,
      backBufferMsOverride = playbackSettings.backBuffer.durationMs
    )
    val frameRateStrategy = if (playbackSettings.autoFrameRateMatching) {
      C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
    } else {
      C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
    }
    val player = ExoPlayer.Builder(context, renderersFactory)
      .setTrackSelector(trackSelector)
      .setMediaSourceFactory(playbackMediaSourceFactory)
      .setBandwidthMeter(bandwidthMeter)
      .setLoadControl(loadControl)
      .setVideoChangeFrameRateStrategy(frameRateStrategy)
      .build().apply {
        // Punto 1 – Selezione automatica lingua preferita (allineamento NuvioTV):
        // parametri di selezione predefiniti del player che privilegiano l'audio
        // preferito (default italiano "it"/"ita") e impediscono la selezione
        // automatica di sottotitoli con lingua indeterminata: i sottotitoli
        // vengono scelti automaticamente solo se forzati o se la lingua audio
        // preferita non è disponibile (logica fine in applySubtitlesConfiguration).
        trackSelectionParameters = trackSelectionParameters.buildUpon()
          .setPreferredAudioLanguages(*preferredAudioLangs.toTypedArray())
          .setSelectUndeterminedTextLanguage(false)
          .build()
        Log.i(
          "PlayerScreen",
          "TrackSelectionParameters predefiniti: audioPreferred=${preferredAudioLangs.ifEmpty { listOf("nessuna") }}, " +
            "selectUndeterminedTextLanguage=false"
        )
        if (videoUrl != null) {
          Log.i("PlayerScreen", if (isCurrentlySwitching) "Avvio riproduzione NuvioEngine (passaggio episodio): url=$videoUrl, attesa risoluzione stream" else "Avvio riproduzione NuvioEngine: url=$videoUrl")
          // Percorso IDENTICO a quello del cambio episodio (LaunchedEffect qui sotto):
          // stessa sequenza, stesso MediaItem builder, stessi headers/sottotitoli.
          loadStreamOnPlayer(
            player = this,
            url = videoUrl,
            positionMs = playbackState.currentPositionMs,
            source = "creazione-player"
          )
        } else if (isCurrentlySwitching) {
          // Durante il cambio episodio con videoUrl ancora null: mostra stato loading
          // ma NON mostrare "Nessuna sorgente disponibile". La risoluzione stream è in corso.
          Log.i("PlayerScreen", "Passaggio episodio: attesa risoluzione stream (videoUrl null in attesa)")
          // Mostra l'indicatore di caricamento ma non errori
          playbackErrorMessage = null
          // Non chiamiamo playWhenReady = true qui perché videoUrl è ancora null;
          // il player sarà avviato quando videoUrl diventerà disponibile tramite
          // l'aggiornamento dello stato dal ViewModel (quando la risoluzione stream completa)
        } else {
          Log.i("PlayerScreen", "Nessuna sorgente disponibile per '${media.title}': nessun fallback demo.")
        }
      }
    trackSelector to player
  }

  // Log di tracciamento [NEXT_EP_DEBUG] #4: quale URL e quale istanza di player
  // arrivano a PlayerScreen ad ogni cambio di stato (episodio/stream/ricreazione player).
  Log.d(
    "[NEXT_EP_DEBUG]",
    "PlayerScreen riceve videoUrl=$videoUrl, player=$exoPlayer, " +
      "isSwitching=$isSwitchingEpisode, streamUrl=${playbackState.streamUrl}"
  )

  var currentTracksState by remember(exoPlayer) { mutableStateOf(exoPlayer.currentTracks) }

  // ---------------------------------------------------------------------------
  // INTRO / "SALTA INTRO"
  // Gestione finestra intro: extras reali del media oppure IntroDB
  // ---------------------------------------------------------------------------
  var isIntroDismissed by remember { mutableStateOf(false) }
  var introDbWindow by remember { mutableStateOf<IntroWindow?>(null) }
  var introWindow by remember { mutableStateOf<IntroWindow?>(null) }

  fun updateIntroWindow() {
    val resolved = PlayerIntroUtils.resolveIntroWindow(
      mediaItem = exoPlayer.currentMediaItem,
      introDbWindow = introDbWindow
    )
    if (resolved != introWindow) {
      introWindow = resolved
    }
    Log.d(
      "StreamNovaIntro",
      "Stato intro: isTvShow=$isTvShow, introWindow=$resolved, currentPosition=$currentPosition, playbackState=${exoPlayer.playbackState}"
    )
  }

  fun formatAudioTrackDisplayName(
    formatLanguage: String?,
    formatLabel: String?,
    roleFlags: Int
  ): String {
    val cleanLang = formatLanguage?.trim()?.lowercase() ?: ""
    val isNonUdenti = (roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0) ||
        (roleFlags and C.ROLE_FLAG_TRANSCRIBES_DIALOG != 0) ||
        (formatLabel?.contains("non udenti", ignoreCase = true) == true) ||
        (formatLabel?.contains("hearing impaired", ignoreCase = true) == true) ||
        (formatLabel?.contains("sdh", ignoreCase = true) == true)

    val isAudioDesc = (roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0) ||
        (formatLabel?.contains("audiodescrizione", ignoreCase = true) == true) ||
        (formatLabel?.contains("audio description", ignoreCase = true) == true)

    val isCommentary = (roleFlags and C.ROLE_FLAG_COMMENTARY != 0) ||
        (formatLabel?.contains("commento", ignoreCase = true) == true) ||
        (formatLabel?.contains("commentary", ignoreCase = true) == true)

    val baseLang = when {
      cleanLang.startsWith("it") -> context.getString(R.string.lang_italian)
      cleanLang.startsWith("en") -> context.getString(R.string.lang_english)
      cleanLang.startsWith("es") || cleanLang == "spa" -> context.getString(R.string.lang_spanish)
      cleanLang.startsWith("fr") || cleanLang == "fra" || cleanLang == "fre" -> context.getString(R.string.lang_french)
      cleanLang.startsWith("de") || cleanLang == "deu" || cleanLang == "ger" -> context.getString(R.string.lang_german)
      cleanLang.startsWith("pt") || cleanLang == "por" -> context.getString(R.string.track_lang_portuguese)
      cleanLang.startsWith("ja") || cleanLang == "jpn" -> context.getString(R.string.track_lang_japanese)
      cleanLang.startsWith("ko") || cleanLang == "kor" -> context.getString(R.string.track_lang_korean)
      cleanLang.startsWith("zh") || cleanLang == "zho" || cleanLang == "chi" -> context.getString(R.string.track_lang_chinese)
      cleanLang.startsWith("ru") || cleanLang == "rus" -> context.getString(R.string.track_lang_russian)
      cleanLang.startsWith("ar") || cleanLang == "ara" -> context.getString(R.string.track_lang_arabic)
      cleanLang.startsWith("hi") || cleanLang == "hin" -> context.getString(R.string.track_lang_hindi)
      cleanLang.startsWith("pl") || cleanLang == "pol" -> context.getString(R.string.track_lang_polish)
      formatLabel?.isNotBlank() == true -> formatLabel.trim()
      cleanLang.isNotBlank() && cleanLang != "und" -> {
        val currentLocale = context.resources.configuration.locales[0] ?: java.util.Locale.getDefault()
        val locName = try {
          java.util.Locale.forLanguageTag(cleanLang).getDisplayLanguage(currentLocale)
        } catch (_: Exception) { "" }
        if (locName.isNotBlank() && !locName.equals(cleanLang, ignoreCase = true)) {
          locName.replaceFirstChar { it.uppercase() }
        } else {
          cleanLang.uppercase()
        }
      }
      else -> context.getString(R.string.player_audio_fallback)
    }

    val extra = mutableListOf<String>()
    if (isNonUdenti) {
      extra.add(context.getString(R.string.track_flag_sdh))
    } else if (isAudioDesc) {
      extra.add(context.getString(R.string.track_flag_audio_desc))
    } else if (isCommentary) {
      extra.add(context.getString(R.string.track_flag_commentary))
    }

    val labelClean = formatLabel?.trim() ?: ""
    val isRedundantLabel = labelClean.isBlank() ||
        labelClean.equals(baseLang, ignoreCase = true) ||
        labelClean.equals("und", ignoreCase = true) ||
        labelClean.equals("default", ignoreCase = true) ||
        labelClean.equals("main", ignoreCase = true) ||
        labelClean.contains("forced", ignoreCase = true) ||
        labelClean.contains("forzat", ignoreCase = true) ||
        labelClean.contains("hearing impaired", ignoreCase = true) ||
        labelClean.contains("non udenti", ignoreCase = true) ||
        labelClean.contains("sdh", ignoreCase = true) ||
        labelClean.contains("closed caption", ignoreCase = true)

    return when {
      extra.isNotEmpty() -> "$baseLang (${extra.joinToString(", ")})"
      !isRedundantLabel -> "$baseLang - $labelClean"
      else -> baseLang
    }
  }

  fun getAvailableAudioOptions(tracks: Tracks = currentTracksState): List<MediaAudioTrackOption> {
    val options = mutableListOf<MediaAudioTrackOption>()
    for (group in tracks.groups) {
      if (group.type == C.TRACK_TYPE_AUDIO) {
        for (i in 0 until group.length) {
          val format = group.getTrackFormat(i)
          val lang = format.language ?: ""
          val label = format.label ?: ""
          val roleFlags = format.roleFlags
          val isSelected = group.isTrackSelected(i)
          val name = formatAudioTrackDisplayName(lang, label, roleFlags)
          val codec = CustomDefaultTrackNameProvider.formatNameFromMime(format.sampleMimeType)
            ?: CustomDefaultTrackNameProvider.formatNameFromMime(format.codecs)
            ?: format.codecs
            ?: format.sampleMimeType
            ?: "Codec unavailable"
          val channels = CustomDefaultTrackNameProvider.getChannelLayoutName(format.channelCount)
            ?: "Channel count unavailable"
          options.add(
            MediaAudioTrackOption(
              group = group,
              trackIndex = i,
              language = lang,
              label = label,
              roleFlags = roleFlags,
              codec = codec,
              channels = channels,
              displayName = name,
              isSelected = isSelected
            )
          )
        }
      }
    }

    val counts = options.groupingBy { it.displayName }.eachCount()
    return if (counts.values.any { it > 1 }) {
      val tracker = mutableMapOf<String, Int>()
      options.map { opt ->
        if ((counts[opt.displayName] ?: 0) > 1) {
          val count = (tracker[opt.displayName] ?: 0) + 1
          tracker[opt.displayName] = count
          opt.copy(displayName = "${opt.displayName} #$count")
        } else {
          opt
        }
      }
    } else {
      options
    }
  }

  fun formatSubtitleTrackDisplayName(
    formatLanguage: String?,
    formatLabel: String?,
    roleFlags: Int,
    selectionFlags: Int = 0
  ): String {
    val cleanLang = formatLanguage?.trim()?.lowercase() ?: ""
    val labelLower = formatLabel?.lowercase() ?: ""
    val hasForcedFlag = (selectionFlags and C.SELECTION_FLAG_FORCED) != 0
    val isForced = hasForcedFlag ||
        labelLower.contains("forced") ||
        labelLower.contains("forzato") ||
        labelLower.contains("forzati")
    val isCC = (roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0) ||
        (roleFlags and C.ROLE_FLAG_TRANSCRIBES_DIALOG != 0) ||
        labelLower.contains("non udenti") ||
        labelLower.contains("hearing impaired") ||
        labelLower.contains("cc") ||
        labelLower.contains("sdh")

    val baseLang = when {
      cleanLang.startsWith("it") -> context.getString(R.string.lang_italian)
      cleanLang.startsWith("en") -> context.getString(R.string.lang_english)
      cleanLang.startsWith("es") || cleanLang == "spa" -> context.getString(R.string.lang_spanish)
      cleanLang.startsWith("fr") || cleanLang == "fra" || cleanLang == "fre" -> context.getString(R.string.lang_french)
      cleanLang.startsWith("de") || cleanLang == "deu" || cleanLang == "ger" -> context.getString(R.string.lang_german)
      cleanLang.startsWith("pt") || cleanLang == "por" -> context.getString(R.string.track_lang_portuguese)
      cleanLang.startsWith("ja") || cleanLang == "jpn" -> context.getString(R.string.track_lang_japanese)
      cleanLang.startsWith("ko") || cleanLang == "kor" -> context.getString(R.string.track_lang_korean)
      cleanLang.startsWith("zh") || cleanLang == "zho" || cleanLang == "chi" -> context.getString(R.string.track_lang_chinese)
      cleanLang.startsWith("ru") || cleanLang == "rus" -> context.getString(R.string.track_lang_russian)
      cleanLang.startsWith("ar") || cleanLang == "ara" -> context.getString(R.string.track_lang_arabic)
      cleanLang.startsWith("hi") || cleanLang == "hin" -> context.getString(R.string.track_lang_hindi)
      cleanLang.startsWith("pl") || cleanLang == "pol" -> context.getString(R.string.track_lang_polish)
      formatLabel?.isNotBlank() == true -> formatLabel.trim()
      cleanLang.isNotBlank() && cleanLang != "und" -> {
        val currentLocale = context.resources.configuration.locales[0] ?: java.util.Locale.getDefault()
        val locName = try {
          java.util.Locale.forLanguageTag(cleanLang).getDisplayLanguage(currentLocale)
        } catch (_: Exception) { "" }
        if (locName.isNotBlank() && !locName.equals(cleanLang, ignoreCase = true)) {
          locName.replaceFirstChar { it.uppercase() }
        } else {
          cleanLang.uppercase()
        }
      }
      else -> context.getString(R.string.player_subtitles_unknown)
    }

    val labelClean = formatLabel?.trim() ?: ""
    val isRedundantLabel = labelClean.isBlank() ||
        labelClean.equals(baseLang, ignoreCase = true) ||
        labelClean.equals("und", ignoreCase = true) ||
        labelClean.equals("default", ignoreCase = true) ||
        labelClean.equals("main", ignoreCase = true) ||
        labelClean.contains("forced", ignoreCase = true) ||
        labelClean.contains("forzat", ignoreCase = true) ||
        labelClean.contains("hearing impaired", ignoreCase = true) ||
        labelClean.contains("non udenti", ignoreCase = true) ||
        labelClean.contains("sdh", ignoreCase = true) ||
        labelClean.contains("closed caption", ignoreCase = true)

    val forcedText = context.getString(R.string.track_flag_forced)
    val sdhText = context.getString(R.string.track_flag_sdh)
    val flags = buildList {
      if (isForced) add(forcedText)
      if (isCC) add(sdhText)
    }
    val labelSuffix = if (!isRedundantLabel) " - $labelClean" else ""
    return baseLang + labelSuffix + if (flags.isNotEmpty()) " (${flags.joinToString(", ")})" else ""
  }

  fun getAvailableSubtitleOptions(tracks: Tracks = currentTracksState): List<MediaSubtitleTrackOption> {
    val options = mutableListOf<MediaSubtitleTrackOption>()
    for (group in tracks.groups) {
      if (group.type == C.TRACK_TYPE_TEXT) {
        for (i in 0 until group.length) {
          val format = group.getTrackFormat(i)
          val lang = format.language ?: ""
          val label = format.label ?: ""
          val roleFlags = format.roleFlags
          val selectionFlags = format.selectionFlags
          val isSelected = group.isTrackSelected(i)
          val name = formatSubtitleTrackDisplayName(lang, label, roleFlags, selectionFlags)
          val codec = CustomDefaultTrackNameProvider.formatNameFromMime(format.sampleMimeType)
            ?: CustomDefaultTrackNameProvider.formatNameFromMime(format.codecs)
            ?: format.codecs
            ?: format.sampleMimeType
            ?: "Codec unavailable"
          val isForced = (selectionFlags and C.SELECTION_FLAG_FORCED) != 0 ||
              label.contains("forced", ignoreCase = true) ||
              label.contains("forzat", ignoreCase = true)
          options.add(
            MediaSubtitleTrackOption(
              group = group,
              trackIndex = i,
              language = lang,
              label = label,
              roleFlags = roleFlags,
              selectionFlags = selectionFlags,
              isForced = isForced,
              codec = codec,
              displayName = name,
              isSelected = isSelected
            )
          )
        }
      }
    }
    return options
  }

  fun updateResolutionFromHeight(h: Int, w: Int = -1) {
    if (h > 0 || w > 0) {
      val prev = detectedResolution
      detectedResolution = when {
        h >= 1400 || w >= 2500 -> "4K"
        h >= 750 || w >= 1400  -> "1080p"
        h >= 500 || w >= 900   -> "720p"
        else                   -> "SD"
      }
      Log.i("[StreamNova-Video-Debug]", "updateResolutionFromHeight: h=$h, w=$w -> detectedResolution='$detectedResolution' (era='$prev')")
    }
  }

  fun getAvailableQualityOptions(): List<Pair<String, Int>> {
    val options = mutableListOf<Pair<String, Int>>()
    options.add("Auto" to 0)

    val heights = mutableSetOf<Int>()
    for (group in exoPlayer.currentTracks.groups) {
      if (group.type == C.TRACK_TYPE_VIDEO) {
        for (i in 0 until group.length) {
          val format = group.getTrackFormat(i)
          if (format.height > 0) {
            heights.add(format.height)
          }
        }
      }
    }

    if (heights.isNotEmpty()) {
      heights.sortedDescending().forEach { h ->
        val label = when {
          h >= 1400 -> "4K (UHD)"
          h >= 750  -> "1080p (FHD)"
          h >= 500  -> "720p (HD)"
          h >= 400  -> "480p (SD)"
          else      -> "${h}p"
        }
        options.add(label to h)
      }
    } else {
      options.add("1080p (FHD)" to 1080)
      options.add("720p (HD)" to 720)
      options.add("480p (SD)" to 480)
    }
    return options
  }

  fun videoTrackHeights(): List<Int> {
    val heights = mutableSetOf<Int>()
    for (group in exoPlayer.currentTracks.groups) {
      if (group.type == C.TRACK_TYPE_VIDEO) {
        for (i in 0 until group.length) {
          val h = group.getTrackFormat(i).height
          if (h > 0) heights.add(h)
        }
      }
    }
    return heights.sortedDescending()
  }

  /**
   * Applica la "Risoluzione preferita" persistente scegliendo la migliore qualità
   * REALMENTE disponibile compatibile con la preferenza. Se la risoluzione richiesta
   * non è disponibile usa la più vicina realmente presente (mai inventata/forzata).
   * "Auto" non tocca nulla: mantiene il comportamento attuale del player.
   */
  fun applyPreferredResolution(pref: PreferredResolution) {
    if (pref == PreferredResolution.AUTO) return
    val heights = videoTrackHeights()
    
    // Per UHD_4K: non impostare vincoli che blocchino le tracce 4K
    if (pref == PreferredResolution.UHD_4K) {
      Log.i("[StreamNova-Video-Debug]", "applyPreferredResolution: pref=${pref.label} -> nessun vincolo maxVideoSize per 4K")
      trackSelector.setParameters(
        trackSelector.buildUponParameters()
          .clearVideoSizeConstraints()
          .setViewportSize(Int.MAX_VALUE, Int.MAX_VALUE, false)
          .setForceHighestSupportedBitrate(true)
          .setExceedVideoConstraintsIfNecessary(true)
          .setExceedRendererCapabilitiesIfNecessary(true)
      )
      selectedQualityLabel = "4K (UHD)"
      return
    }
    
    val target = heights.filter { it <= pref.targetHeight }.maxOrNull()
      ?: heights.minOrNull()
      ?: pref.targetHeight
    val targetWidth = when {
      target >= 1400 -> 3840
      target >= 750  -> 1920
      target >= 500  -> 1280
      target >= 400  -> 854
      else -> (target * 16) / 9
    }
    Log.i("[StreamNova-Video-Debug]", "applyPreferredResolution: pref=${pref.label} (${pref.targetHeight}p), tracce video disponibili=$heights -> target=${target}p")
    Log.i("PlayerScreen", "Risoluzione preferita ${pref.label}: tracce disponibili=$heights -> target=${target}p")
    StreamNova4KDiag.line(
      "VINCOLO applyPreferredResolution pref=${pref.label} -> minVideoSize=${targetWidth}x$target " +
        "maxVideoSize=${targetWidth}x$target viewport=MAXxMAX forceHighestSupportedBitrate=true " +
        "exceedVideoConstraints=true exceedRendererCapabilities=true | tracce=$heights"
    )
    trackSelector.setParameters(
      trackSelector.buildUponParameters()
        .setMinVideoSize(targetWidth, target)
        .setMaxVideoSize(targetWidth, target)
        .setViewportSize(Int.MAX_VALUE, Int.MAX_VALUE, false)
        .setForceHighestSupportedBitrate(true)
        .setExceedVideoConstraintsIfNecessary(true)
        .setExceedRendererCapabilitiesIfNecessary(true)
    )
    updateResolutionFromHeight(target, targetWidth)
    selectedQualityLabel = when {
      target >= 1400 -> "4K (UHD)"
      target >= 750  -> "1080p (FHD)"
      target >= 500  -> "720p (HD)"
      target >= 400  -> "480p (SD)"
      else -> "${target}p"
    }
  }

  fun isCurrentAudioItalian(tracks: Tracks): Boolean {
    for (group in tracks.groups) {
      if (group.type == C.TRACK_TYPE_AUDIO && group.isSelected) {
        for (i in 0 until group.length) {
          if (group.isTrackSelected(i)) {
            val format = group.getTrackFormat(i)
            val lang = format.language?.trim()?.lowercase() ?: ""
            val label = format.label?.trim()?.lowercase() ?: ""
            if (lang.startsWith("it") || lang == "ita" || label.contains("italiano") || label.contains("italian")) {
              return true
            }
            if (lang.isNotBlank() && lang != "und") {
              return false
            }
          }
        }
      }
    }
    val pref = playbackSettings.preferredAudioLanguage.trim().lowercase()
    return pref.startsWith("it") || pref == "ita"
  }

  /**
   * Applica al TrackSelector Media3 le preferenze dei sottotitoli (Forced e/o Completi):
   * 1. Se Sottotitoli Forced è abilitato:
   *    - Imposta i parametri richiesti da ExoPlayer (preferredTextLanguage "it", preferredTextRoleFlags,
   *      ignoredTextSelectionFlags = 0, selectUndeterminedTextLanguage = false).
   *    - Logica di selezione intelligente se audio in riproduzione è "it":
   *      - Seleziona traccia con flag C.SELECTION_FLAG_FORCED != 0 (priorità 1).
   *      - Altrimenti cerca traccia etichettata "forced" o "forzati" nel label o ID (priorità 2).
   *      - Se nessuna traccia forced presente e sottotitoli completi OFF, disabilita il renderer testo.
   * 2. Se Sottotitoli Forced è disabilitato:
   *    - Se sottotitoli completi ON, applica selezione automatica standard per la lingua preferita.
   *    - Se sottotitoli completi OFF, disabilita il renderer testo (setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)).
   * 3. Rispetta sempre la selezione manuale dell'utente (manualSubtitleSelection) se attiva.
   */
  fun applySubtitlesConfiguration(
    forcedEnabled: Boolean,
    fullSubtitlesEnabled: Boolean,
    preferredLanguage: String,
    tracks: Tracks,
    manualSelection: ManualSubtitleSelection?
  ) {
    if (manualSelection != null) {
      if (manualSelection.isNone) {
        val builder = trackSelector.buildUponParameters()
          .clearOverridesOfType(C.TRACK_TYPE_TEXT)
          .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        trackSelector.setParameters(builder)
        Log.i("PlayerScreen", "Sottotitoli: selezione manuale 'Nessuno' attiva, renderer disabilitato")
      } else if (manualSelection.group != null && manualSelection.trackIndex >= 0) {
        val override = TrackSelectionOverride(manualSelection.group.mediaTrackGroup, manualSelection.trackIndex)
        val builder = trackSelector.buildUponParameters()
          .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
          .clearOverridesOfType(C.TRACK_TYPE_TEXT)
          .setOverrideForType(override)
        trackSelector.setParameters(builder)
        Log.i("PlayerScreen", "Sottotitoli: selezione manuale traccia applicata (index=${manualSelection.trackIndex})")
      }
      return
    }

    if (forcedEnabled) {
      val baseBuilder = trackSelector.buildUponParameters()
        .setPreferredTextLanguage("it")
        .setPreferredTextRoleFlags(C.ROLE_FLAG_SUBTITLE)
        .setIgnoredTextSelectionFlags(0)
        .setSelectUndeterminedTextLanguage(false)

      val isAudioIt = isCurrentAudioItalian(tracks)
      Log.i("PlayerScreen", "Sottotitoli Forced ON: lingua audio italiana=$isAudioIt")

      if (isAudioIt) {
        var explicitCandidate: Pair<Tracks.Group, Int>? = null
        var labelCandidate: Pair<Tracks.Group, Int>? = null

        for (group in tracks.groups) {
          if (group.type != C.TRACK_TYPE_TEXT) continue
          for (i in 0 until group.length) {
            val format = group.getTrackFormat(i)
            val lang = format.language?.trim()?.lowercase() ?: ""
            val label = format.label?.trim()?.lowercase() ?: ""
            val id = format.id?.trim()?.lowercase() ?: ""
            val groupId = group.mediaTrackGroup.id.trim().lowercase()

            val isItalian = lang.startsWith("it") || lang == "ita" ||
                label.contains("italiano") || label.contains("italian")

            if (!isItalian && lang.isNotBlank() && lang != "und") {
              continue
            }

            val hasForcedFlag = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
            val hasForcedName = label.contains("forced") || label.contains("forzat") ||
                id.contains("forced") || id.contains("forzat") ||
                groupId.contains("forced") || groupId.contains("forzat")

            if (hasForcedFlag) {
              explicitCandidate = group to i
              break
            } else if (hasForcedName && labelCandidate == null) {
              labelCandidate = group to i
            }
          }
          if (explicitCandidate != null) break
        }

        val forcedTrack = explicitCandidate ?: labelCandidate
        if (forcedTrack != null) {
          val (group, trackIndex) = forcedTrack
          val override = TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
          baseBuilder
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setOverrideForType(override)
          trackSelector.setParameters(baseBuilder)
          Log.i("PlayerScreen", "Traccia sottotitoli forced (IT) selezionata: gruppo=${group.mediaTrackGroup.id}, traccia=$trackIndex, flag=${explicitCandidate != null}")
        } else {
          if (fullSubtitlesEnabled) {
            baseBuilder
              .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
              .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            trackSelector.setParameters(baseBuilder)
            Log.i("PlayerScreen", "Nessuna traccia forced trovata: abilitati sottotitoli standard (fullSubtitles=ON)")
          } else {
            baseBuilder
              .clearOverridesOfType(C.TRACK_TYPE_TEXT)
              .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            trackSelector.setParameters(baseBuilder)
            Log.i("PlayerScreen", "Nessuna traccia forced trovata e fullSubtitles=OFF: renderer testo disabilitato")
          }
        }
      } else {
        if (fullSubtitlesEnabled) {
          baseBuilder
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
          if (preferredLanguage.isNotBlank()) {
            baseBuilder.setPreferredTextLanguage(preferredLanguage)
          }
          trackSelector.setParameters(baseBuilder)
        } else {
          baseBuilder
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
          trackSelector.setParameters(baseBuilder)
        }
      }
    } else {
      Log.i("PlayerScreen", "Sottotitoli Forced OFF: fullSubtitlesEnabled=$fullSubtitlesEnabled")
      val builder = trackSelector.buildUponParameters()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setSelectUndeterminedTextLanguage(false)
      if (fullSubtitlesEnabled) {
        builder
          .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
          .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_FORCED)
        if (preferredLanguage.isBlank()) {
          builder.setPreferredTextLanguages()
        } else {
          builder.setPreferredTextLanguage(preferredLanguage)
        }
      } else {
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
      }
      trackSelector.setParameters(builder)
    }
  }

  /**
   * Applica al TrackSelector Media3 le preferenze persistenti "Lingua audio preferita":
   * mapping su codici ISO 639-1 e 639-2.
   * Rimuove eventuali override manuali per consentire la corretta selezione automatica.
   */
  fun applyAudioAutoSelection(preferredLanguage: String) {
    val languages = mapPreferredAudioLanguages(preferredLanguage)
    val languageDesc = if (languages.isEmpty()) "nessuna preferenza" else languages.joinToString(", ")
    Log.i("PlayerScreen", "Audio preferito automatico: $languageDesc")
    val builder = trackSelector.buildUponParameters()
      .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
    if (languages.isEmpty()) {
      builder.setPreferredAudioLanguages()
    } else {
      builder.setPreferredAudioLanguages(*languages.toTypedArray())
    }
    trackSelector.setParameters(builder)
  }

  val currentEpKey = "${currentEp?.seasonNumber}_${currentEp?.episodeNumber}"
  var lastLoadedEpKey by remember { mutableStateOf(currentEpKey) }
  // Token dell'ultimo cambio sorgente manuale gia' applicato al player.
  // Inizializzato al token corrente del ViewModel: alla creazione del player
  // non deve quindi scattare alcun ricaricamento (il flusso e' gia' caricato).
  var lastLoadedSourceToken by remember { mutableStateOf(manualSourceSelectionToken) }

  // Sorgente assente per un titolo TMDB: mostra lo stato di errore, SENZA avviare video demo.
  // La sessione viene marcata come fallita: nessun auto-next da errore di risoluzione.
  // Ma se siamo in modalita' selezione sorgente o durante la risoluzione streaming, NON e' un errore:
  // il player rimarra' in attesa o mostrera' il dialog di selezione sorgente.
  LaunchedEffect(videoUrl, streamResolutionState, isSourceSelectionActive, isSwitchingEpisode) {
    if (videoUrl == null) {
      // Risoluzione in corso ("Caricamento episodio successivo..."), selezione
      // sorgente attiva o transizione al prossimo episodio: mai errore/schermata nera.
      if (streamResolutionState is StreamResolutionState.Loading ||
        isSourceSelectionActive ||
        isSwitchingEpisode
      ) {
        isBuffering = true
        playbackFailed = false
        playbackErrorMessage = null
      } else if (streamResolutionState is StreamResolutionState.Error) {
        isBuffering = false
        playbackEnded = false
        playbackFailed = true
        playbackErrorMessage = (streamResolutionState as StreamResolutionState.Error).message
      } else {
        isBuffering = false
        playbackEnded = false
        playbackFailed = true
        playbackErrorMessage = streamUnavailableMessage(isTvShow, currentEp)
      }
    } else {
      playbackErrorMessage = null
      playbackFailed = false
    }
  }

  // Passaggio al prossimo episodio: mette in pausa il video precedente (nessun
  // azzeramento brusco di ExoPlayer) mentre le nuove sorgenti vengono risolte.
  LaunchedEffect(isSwitchingEpisode) {
    if (isSwitchingEpisode) {
      runCatching { exoPlayer.pause() }
      isBuffering = true
      playbackFailed = false
      playbackErrorMessage = null
    }
  }

  // Fire TV / Android TV: durante la riproduzione attiva il salvaschermo di
  // sistema NON deve partire. Il flag FLAG_KEEP_SCREEN_ON viene applicato alla
  // finestra dell'Activity finché il video è attivo (in riproduzione, in
  // buffering o durante il passaggio al prossimo episodio) e rimosso quando il
  // player è in pausa o esce dalla composizione.
  val keepScreenOn = isPlaying || isBuffering || isSwitchingEpisode
  DisposableEffect(keepScreenOn) {
    val window = context.findActivity()?.window
    if (window != null) {
      if (keepScreenOn) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      }
    }
    onDispose {
      window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
  }

  // Se l'URL o l'episodio cambiano dinamicamente (es. click su Prossimo Episodio o Upgrade di qualita' a caldo)
  // oppure quando l'URL diventa disponibile dopo la selezione della sorgente TorBox.
  // FIX CRITICO: `exoPlayer` è ora parte delle chiavi dell'effetto. Senza di esso, una
  // ricreazione del player (cambio HLS<->progressivo, headers, impostazioni di
  // riproduzione) lasciava il NUOVO player senza media item: la vista restava ferma
  // su "Caricamento" con nessun setMediaItem() mai eseguito sull'istanza corrente.
  //
  // `manualSourceSelectionToken` copre il cambio sorgente MANUALE dal player: il
  // token cambia a ogni scelta, quindi il flusso viene ricaricato anche quando
  // l'URL coincide (cambiano comunque header e metadati della sorgente).
  LaunchedEffect(currentEpKey, videoUrl, exoPlayer, manualSourceSelectionToken) {
    val url = videoUrl ?: return@LaunchedEffect
    val isEpisodeChange = currentEpKey != lastLoadedEpKey
    val currentUri = exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString()
    val isUrlChange = currentUri == null || currentUri != url
    // Cambio di sorgente manuale: l'utente ha scelto un altro flusso, quindi il
    // player DEVE ricaricarlo (nessun avvio concorrente: l'istanza di ExoPlayer
    // resta la stessa, cambia solo il contenuto).
    val isManualSourceChange = manualSourceSelectionToken != lastLoadedSourceToken
    if (isManualSourceChange) {
      lastLoadedSourceToken = manualSourceSelectionToken
    }

    if (isEpisodeChange || isUrlChange || isManualSourceChange) {
      val targetPos = if (isEpisodeChange) {
        lastLoadedEpKey = currentEpKey
        playbackState.currentPositionMs
      } else {
        exoPlayer.currentPosition.takeIf { it > 0 } ?: playbackState.currentPositionMs
      }

      Log.i(
        "PlayerScreen",
        "Caricamento stream: url=$url, isEpisodeChange=$isEpisodeChange, " +
          "isManualSourceChange=$isManualSourceChange, pos=$targetPos"
      )
      Log.d(
        "[NEXT_EP_DEBUG]",
        "Ricarico ExoPlayer con nuovo URL: $url (isEpisodeChange=$isEpisodeChange, " +
          "isManualSourceChange=$isManualSourceChange, pos=$targetPos, player=$exoPlayer)"
      )
      isBuffering = true
      playbackFailed = false
      currentPosition = targetPos

      // Ferma e ripulisce la vecchia istanza di ExoPlayer per evitare STATE_BUFFERING fantasma:
      // stessa sequenza IDENTICA usata al primo avvio (S1E1), così headers HTTP,
      // MediaSourceFactory e gestione sottotitoli non possono divergere.
      loadStreamOnPlayer(
        player = exoPlayer,
        url = url,
        positionMs = targetPos,
        source = when {
          isEpisodeChange -> "cambio-episodio"
          isManualSourceChange -> "cambio-sorgente-manuale"
          else -> "cambio-url"
        }
      )
    } else {
      Log.d(
        "[NEXT_EP_DEBUG]",
        "Nessun ricaricamento necessario: uri corrente del player ($currentUri) già uguale a $url"
      )
    }
  }

  // Reset degli stati di sessione quando cambia l'episodio (nuova applicazione qualità,
  // nuovo eventuale auto-advance). La chiave exoPlayer copre anche la ricreazione del
  // player a parità di episodio (es. upgrade sorgente): con il nuovo TrackSelector le
  // preferenze qualità vengono riapplicate dal momento READY successivo, come accadeva
  // con il selettore persistente precedente.
  LaunchedEffect(currentEpKey, exoPlayer) {
    playbackEnded = false
    playerReady = false
    initialQualityApplied = false
    manualSubtitleSelection = null
    manualAudioSelection = null
    nextEpisodeDismissed = false
    nextEpisodeCountdownSeconds = 0
  }

  // Applica la "Risoluzione preferita" una sola volta, quando il player è pronto
  LaunchedEffect(playerReady, currentEpKey) {
    if (playerReady && !initialQualityApplied) {
      initialQualityApplied = true
      applyPreferredResolution(playbackSettings.preferredResolution)
    }
  }

  // "Sottotitoli Forced" + "Abilita automaticamente i sottotitoli" + "Lingua preferita":
  // applica/riapplica le preferenze persistenti al TrackSelector quando cambiano,
  // all'arrivo/aggiornamento delle tracce della sorgente o al cambio episodio.
  LaunchedEffect(
    exoPlayer,
    playbackSettings.forcedSubtitlesEnabled,
    playbackSettings.subtitlesEnabled,
    playbackSettings.preferredSubtitleLanguage,
    currentEpKey,
    currentTracksState,
    manualSubtitleSelection
  ) {
    applySubtitlesConfiguration(
      forcedEnabled = playbackSettings.forcedSubtitlesEnabled,
      fullSubtitlesEnabled = playbackSettings.subtitlesEnabled,
      preferredLanguage = playbackSettings.preferredSubtitleLanguage,
      tracks = currentTracksState,
      manualSelection = manualSubtitleSelection
    )
  }

  // "Lingua audio preferita": applica/riapplica le preferenze persistenti al TrackSelector
  // quando cambiano o al cambio episodio/ricreazione player.
  // Se l'utente ha fatto una selezione manuale, non sovrascriviamo la scelta.
  LaunchedEffect(exoPlayer, playbackSettings.preferredAudioLanguage, currentEpKey, manualAudioSelection) {
    if (manualAudioSelection == null) {
      applyAudioAutoSelection(playbackSettings.preferredAudioLanguage)
    }
  }

  // "Prossimo Episodio": countdown automatico quando l'overlay è visibile e l'opzione
  // "Riproduci automaticamente il prossimo episodio" è ON. Allo scadere del countdown si
  // passa al prossimo episodio (risoluzione stream inclusa, senza uscire dal player);
  // con l'opzione OFF resta solo il pulsante manuale "Guarda ora" senza countdown.
  // Mai auto-next da sessione fallita: playbackFailed blocca sempre il passaggio.
  LaunchedEffect(isNextEpisodePromptVisible, playbackSettings.autoPlayNextEpisodeEnabled, currentEpKey) {
    if (!isNextEpisodePromptVisible || !playbackSettings.autoPlayNextEpisodeEnabled) {
      nextEpisodeCountdownSeconds = 0
      return@LaunchedEffect
    }
    nextEpisodeCountdownSeconds = NextEpisodePromptUtils.COUNTDOWN_SECONDS
    while (nextEpisodeCountdownSeconds > 0) {
      delay(1_000L)
      nextEpisodeCountdownSeconds--
    }
    val target = nextEpisode
    if (target != null && !playbackFailed) {
      Log.i("NextEpisode", "Transizione automatica a S${target.seasonNumber}E${target.episodeNumber}")
      viewModel.saveCurrentPlaybackProgress(exoPlayer.duration.coerceAtLeast(exoPlayer.currentPosition))
      viewModel.playNextEpisode(target)
    }
  }

  // Feedback formato video temporaneo
  LaunchedEffect(resizeFeedbackText) {
    if (resizeFeedbackText != null) {
      delay(2000L)
      resizeFeedbackText = null
    }
  }

  // Il messaggio di cambio automatico della sorgente resta visibile pochi
  // secondi: informa senza interrompere la riproduzione.
  LaunchedEffect(sourceSwitchNotice) {
    if (sourceSwitchNotice != null) {
      delay(4000L)
      sourceSwitchNotice = null
    }
  }

  // Sessione Simkl: il SIMKL scrobbler dipende dall'episodio (le chiavi di `simklMedia`
  // sono seasonNumber/episodeNumber), quindi al cambio episodio ne viene creato uno NUOVO.
  // Questo effetto (e SOLO questo) chiude la sessione del precedente payload: è separato
  // dal DisposableEffect del player per non rilasciare mai ExoPlayer durante la transizione.
  DisposableEffect(simklScrobbler) {
    val previousScrobbler = simklScrobbler
    onDispose {
      // `currentPosition`/`totalDuration` sono MutableState: la chiusura legge i valori
      // correnti (ultima posizione pollata dell'episodio appena abbandonato) senza
      // accedere al player, che potrebbe già essere stato rilasciato.
      Log.d(
        "[NEXT_EP_DEBUG]",
        "SimklScrobbler onDispose: stop sessione precedente " +
          "(${simklMedia.episode?.let { "S${it.season}E${it.number}" } ?: "film"}) pos=$currentPosition"
      )
      previousScrobbler.onStop(simklProgressPercent(currentPosition, totalDuration))
    }
  }

  // Release player on dispose & handle events.
  //
  // FIX CRITICO (cambio episodio): l'UNICA chiave di questo effetto è `exoPlayer`,
  // cioè esattamente l'istanza prodotta dal `remember` qui sopra, così `onDispose`
  // (che chiama exoPlayer.release()) scatta SOLO quando il player viene davvero
  // ricreato o quando PlayerScreen esce dalla composizione.
  //
  // In precedenza la chiave era `(exoPlayer, simklScrobbler)`: al passaggio a S1E2
  // `simklScrobbler` veniva ricreato → l'effetto veniva DISPOSTO → onDispose
  // rilanciava exoPlayer.release() su un player che il `remember` continuava a
  // fornire come STESSA istanza. ~1s dopo l'arrivo del nuovo videoUrl,
  // setMediaItem()/prepare()/playWhenReady venivano chiamati su un ExoPlayer già
  // rilasciato: le chiamate svanivano silenziosamente (nessun onPlayerError, nessun
  // onPlaybackStateChanged, polling fermo) e la UI restava bloccata sullo spinner.
  //
  // `simklScrobbler` resta sempre aggiornato grazie a rememberUpdatedState, senza
  // mai invalidare l'effetto. ATTENZIONE: non aggiungere altre chiavi qui e non
  // inserire `return` anticipati sopra, altrimenti AndroidView + questo effetto
  // escono dalla composizione durante la transizione.
  val currentSimklScrobbler by rememberUpdatedState(simklScrobbler)
  DisposableEffect(exoPlayer) {
    Log.d("[NEXT_EP_DEBUG]", "DisposableEffect player: listener agganciati a $exoPlayer")
    var subtitleErrorRecoveries = 0
    val listener = object : Player.Listener {
      override fun onPlaybackStateChanged(state: Int) {
        Log.d(
          "[NEXT_EP_DEBUG]",
          "onPlaybackStateChanged: state=$state (1=IDLE, 2=BUFFERING, 3=READY, 4=ENDED) playWhenReady=${exoPlayer.playWhenReady}"
        )
        isBuffering = state == Player.STATE_BUFFERING
        if (state == Player.STATE_READY) {
          // RESET esplicito a fine caricamento: l'overlay "Caricamento episodio
          // successivo..." deve sparire appena ExoPlayer è READY e sta riproducendo,
          // anche quando il cambio episodio ha forzato isBuffering = true.
          isBuffering = false
          playbackErrorMessage = null
          playbackFailed = false
          // Flusso riprodotto con successo: salvalo come "ultimo funzionante" per il titolo
          // (funzione "Riusa l'ultimo link"). Nessun salvataggio su READY di contenuti demo.
          viewModel.onStreamPlaybackReady()
          totalDuration = exoPlayer.duration.coerceAtLeast(1L)
          val vf = exoPlayer.videoFormat
          val vs = exoPlayer.videoSize
          val activeTrack = exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { (0 until it.length).map { i -> it to i } }
            .firstOrNull { (g, i) -> g.isTrackSelected(i) }
            ?.let { (g, i) -> g.getTrackFormat(i) }
          Log.i(
            "[StreamNova-Video-Debug]",
            "Player STATE_READY -> activeTrack: ${activeTrack?.width}x${activeTrack?.height} @ ${activeTrack?.bitrate}bps mime=${activeTrack?.sampleMimeType} codecs=${activeTrack?.codecs} fps=${activeTrack?.frameRate} | videoFormat: ${vf?.width}x${vf?.height} @ ${vf?.bitrate}bps mime=${vf?.sampleMimeType} codecs=${vf?.codecs} fps=${vf?.frameRate} | videoSize: ${vs.width}x${vs.height} par=${vs.pixelWidthHeightRatio}"
          )
          // --- DIAGNOSTICA 4K (solo log) ---
          StreamNova4KDiag.logPlayerState(
            videoFormat = vf,
            audioFormat = exoPlayer.audioFormat,
            tracks = exoPlayer.currentTracks,
            params = trackSelector.parameters
          )
          val h = vf?.height ?: vs.height
          val w = vf?.width ?: vs.width
          updateResolutionFromHeight(h, w)
          if (playbackSettings.autoFrameRateMatching && vf != null && vf.frameRate > 0f) {
            val act = context as? Activity
            FrameRateUtils.switchDisplayModeForFrameRate(act, vf.frameRate)
          }
          playerReady = true
          updateIntroWindow()
          if (exoPlayer.isPlaying) {
            currentSimklScrobbler.onPlay(simklProgressPercent(exoPlayer.currentPosition, exoPlayer.duration))
          }
        } else if (state == Player.STATE_ENDED) {
          isBuffering = false
          // Conclusione normale SOLO se la sessione non è fallita e la riproduzione
          // ha effettivamente consumato contenuto (no ENDED istantaneo da stream vuoto/rotto).
          if (!playbackFailed && exoPlayer.currentPosition > 0L && exoPlayer.duration > 0L) {
            currentSimklScrobbler.onEnded()
            playbackEnded = true
            // A STATE_ENDED l'overlay "Prossimo Episodio" si attiva (con countdown a
            // video fermo se auto-play ON, solo pulsante se OFF) e transizione non lascia
            // il player bloccato su schermo nero/ultimo fotogramma quando esiste un next.
            Log.i(
              "NextEpisode",
              "STATE_ENDED; next=${nextEpisode?.let { "S${it.seasonNumber}E${it.episodeNumber} ${it.title}" } ?: "NON TROVATO"}, autoPlay=${playbackSettings.autoPlayNextEpisodeEnabled}"
            )
          }
        }
      }

      override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (exoPlayer.duration > 0L) {
          totalDuration = exoPlayer.duration.coerceAtLeast(1L)
          updateIntroWindow()
        }
      }

      override fun onVideoSizeChanged(videoSize: VideoSize) {
        Log.i(
          "[StreamNova-Video-Debug]",
          "onVideoSizeChanged -> width=${videoSize.width}, height=${videoSize.height}, pixelWidthHeightRatio=${videoSize.pixelWidthHeightRatio}"
        )
        updateResolutionFromHeight(videoSize.height, videoSize.width)
      }

      override fun onTracksChanged(tracks: Tracks) {
        var videoTrackIndex = 0
        for (group in tracks.groups) {
          if (group.type == C.TRACK_TYPE_VIDEO) {
            Log.i("StreamNovaDiag", "=== TRACCE VIDEO DISPONIBILI (${group.length}) ===")
            for (i in 0 until group.length) {
              val format = group.getTrackFormat(i)
              val selected = group.isTrackSelected(i)
              val supported = group.isTrackSupported(i)
              val kbps = if (format.bitrate > 0) format.bitrate / 1000 else 0
              Log.i(
                "[StreamNova-Video-Debug]",
                "onTracksChanged -> video track index=$videoTrackIndex (groupIndex=$i), width=${format.width}, height=${format.height}, bitrate=${format.bitrate}, mimeType=${format.sampleMimeType}, codecs=${format.codecs}, frameRate=${format.frameRate}, isSelected=$selected"
              )
              Log.i("StreamNovaDiag", "Traccia #$i: ${format.width}x${format.height} @ $kbps kbps | Selezionata: $selected | Supportata: $supported")
              // --- DIAGNOSTICA 4K (solo log): format reale + supporto di questa traccia ---
              val diagSupport = group.getTrackSupport(i)
              StreamNova4KDiag.line(
                "TRACKS video #$videoTrackIndex groupIndex=$i trackIndex=$i selected=$selected supported=$supported " +
                  "raw=$diagSupport ${StreamNova4KDiag.supportLabel(diagSupport)} " +
                  "selezionabile=${StreamNova4KDiag.isTrackSelectable(diagSupport)} | " +
                  StreamNova4KDiag.formatDetails(format)
              )
              videoTrackIndex++
            }
          } else if (group.type == C.TRACK_TYPE_TEXT) {
            Log.i("StreamNovaDiag", "=== TRACCE SOTTOTITOLI (${group.length}) ===")
            for (i in 0 until group.length) {
              val format = group.getTrackFormat(i)
              Log.i(
                "StreamNovaDiag",
                "Sottotitolo #$i: lang=${format.language ?: "und"} label=${format.label ?: "-"} | Selezionata: ${group.isTrackSelected(i)}"
              )
            }
          } else if (group.type == C.TRACK_TYPE_AUDIO) {
            Log.i("StreamNovaDiag", "=== TRACCE AUDIO DISPONIBILI (${group.length}) ===")
            for (i in 0 until group.length) {
              val format = group.getTrackFormat(i)
              val sampleRate = if (format.sampleRate > 0) "${format.sampleRate}" else "N/A"
              val bitrate = if (format.bitrate > 0) "${format.bitrate}" else "N/A"
              val codecs = format.codecs ?: "N/A"
              val mimeType = format.sampleMimeType ?: "N/A"
              Log.i(
                "StreamNovaDiag",
                "Audio #$i: trackIndex=$i isSelected=${group.isTrackSelected(i)} lang=${format.language ?: "und"} label=${format.label ?: "-"} roleFlags=${format.roleFlags} mimeType=$mimeType sampleRate=$sampleRate channels=${format.channelCount} bitrate=$bitrate codecs=$codecs"
              )
            }
          }
        }
        if (videoTrackIndex == 0) {
          Log.i("[StreamNova-Video-Debug]", "onTracksChanged -> nessuna traccia video presente in Tracks")
        }
        currentTracksState = tracks
        val h = exoPlayer.videoFormat?.height ?: exoPlayer.videoSize.height
        updateResolutionFromHeight(h)
      }

      override fun onIsPlayingChanged(playing: Boolean) {
        isPlaying = playing
        Log.d(
          "[NEXT_EP_DEBUG]",
          "onIsPlayingChanged: playing=$playing, state=${exoPlayer.playbackState}, pos=${exoPlayer.currentPosition}"
        )
        if (exoPlayer.playbackState == Player.STATE_READY || exoPlayer.playbackState == Player.STATE_ENDED) {
          val progress = simklProgressPercent(exoPlayer.currentPosition, exoPlayer.duration)
          if (playing) currentSimklScrobbler.onPlay(progress) else currentSimklScrobbler.onPause(progress)
        }
      }

      override fun onPlayerError(error: ExoPlaybackException) {
        Log.e("[NEXT_EP_DEBUG]", "onPlayerError: ${error.errorCodeName} - ${error.message}", error)
        val displayMsg = PlayerRuntimeControllerErrorRecovery.toDisplayMessage(error, context)
        Log.e("THE_PITT_PLAYER", "Playback Error: ${error.errorCodeName} (${error.errorCode}) - $displayMsg", error)
        // --- DIAGNOSTICA 4K (solo log): errore decoder/renderer + catena cause ---
        StreamNova4KDiag.logPlaybackError(error)
        Log.e("THE_PITT_PLAYER", "Error cause: ${error.cause?.javaClass?.simpleName ?: "null"} - ${error.cause?.message ?: "no cause"}")
        Log.d("THE_PITT_PLAYER", "ExoPlayer attempting to play URL: ${playbackState.streamUrl}")
        Log.d("THE_PITT_PLAYER", "Player state: isPlaying=$isPlaying, isBuffering=$isBuffering, position=${exoPlayer.currentPosition}, duration=${exoPlayer.duration}")
        val currentAudioFormat = exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.firstOrNull { it.isTrackSelected(0) }?.getTrackFormat(0)
        Log.e("THE_PITT_PLAYER", "Current audio track - mimeType=${currentAudioFormat?.sampleMimeType ?: "N/A"} sampleRate=${currentAudioFormat?.sampleRate ?: "N/A"} channels=${currentAudioFormat?.channelCount ?: "N/A"} bitrate=${currentAudioFormat?.bitrate ?: "N/A"} codecs=${currentAudioFormat?.codecs ?: "N/A"}")

        val httpException = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<HttpDataSource.HttpDataSourceException>()
            .firstOrNull()
        val failingUri = httpException?.dataSpec?.uri?.toString()
        // Diagnostica sanificata della richiesta fallita: tipo sorgente (HLS o
        // progressivo), SOLO host, status HTTP (se disponibile) e nome del codice
        // di errore Media3. Nessun URL completo, path, query o token viene registrato:
        // l'host e' l'identificativo minimo per attribuire il fallimento alla richiesta.
        val failedRequestUrl = failingUri ?: playbackState.streamUrl
        val failedHttpStatus = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
            ?.responseCode
        Log.w(
          "STREAM_HTTP_FAIL",
          "source=${if (isHlsStream) "hls" else "progressive"} " +
            "host=${StreamDiagnostics.hostId(failedRequestUrl) ?: "unknown"} " +
            "status=${failedHttpStatus ?: "n/a"} code=${error.errorCodeName}"
        )
        val failedSubtitle = stremioSubtitles.firstOrNull { sub ->
            sub.url.isNotBlank() && (
                sub.url == failingUri ||
                error.message?.contains(sub.url) == true ||
                error.cause?.message?.contains(sub.url) == true
            )
        }
        // Un errore di una traccia sottotitoli esterna (di caricamento O di decodifica)
        // non deve mai interrompere il video: si riprende senza quella traccia e senza
        // modificare maxVideoSize (che altrimenti escluderebbe il video 1080p/4K).
        val isSubtitleError = PlayerCodecErrorClassifier.isSubtitleTrackError(error)
        if (!playbackFailed && (failedSubtitle != null || isSubtitleError)) {
          val remaining = if (failedSubtitle != null) {
            stremioSubtitles.filter { it.url != failedSubtitle.url }
          } else {
            // Errore sottotitoli senza URL identificabile: si escludono solo le tracce
            // esterne, mentre gli sottotitoli embedded Stremio restano attivi.
            stremioSubtitles.filter { it.isStreamProvided }
          }
          val canDropSubtitleTrack = remaining.size < stremioSubtitles.size
          if (!canDropSubtitleTrack && subtitleErrorRecoveries >= MAX_SUBTITLE_ERROR_RECOVERIES) {
            Log.w("THE_PITT_PLAYER", "Errore sottotitoli senza traccia rimovibile (${error.errorCodeName}): nessun ulteriore tentativo di ripresa.")
          } else {
            subtitleErrorRecoveries++
            Log.w("THE_PITT_PLAYER", "Errore traccia sottotitoli (${failedSubtitle?.url ?: error.errorCodeName}): ripresa riproduzione senza la traccia problematica (maxVideoSize invariato).")
            val currentPos = exoPlayer.currentPosition
            val wasPlaying = exoPlayer.playWhenReady
            val newItem = PlayerSubtitleMediaItemBuilder.buildMediaItem(videoUrl ?: "", remaining)
            exoPlayer.setMediaItem(newItem)
            if (currentPos > 0) exoPlayer.seekTo(currentPos)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = wasPlaying
            return
          }
        }

        // Fallback difensivo "Riusa l'ultimo link": se lo stream corrente proviene dalla
        // cache e non è riproducibile (link Debrid scaduto, 403/404 o errore di caricamento),
        // il ViewModel invalida la voce e riapre la risoluzione/selezione classica.
        if (viewModel.handleReusedStreamFailure()) {
          Log.w("StreamNovaReuse", "Flusso riutilizzato non riproducibile (${error.errorCodeName}): fallback alle sorgenti")
          isBuffering = true
          return
        }

        // Fallback codec 720p: attivato SOLO per errori codec effettivamente associati al
        // renderer VIDEO. Un errore sottotitoli/text non modifica mai maxVideoSize.
        val fallbackMaxVideoSize = PlayerCodecErrorClassifier.fallbackMaxVideoSize(error, media.title)

        if (fallbackMaxVideoSize != null && !playbackFailed) {
          Log.w("THE_PITT_PLAYER", "Codec/Format error detected ($displayMsg). Attempting 720p fallback...")
          try {
            trackSelector.setParameters(
              trackSelector.buildUponParameters()
                .setMaxVideoSize(fallbackMaxVideoSize.first, fallbackMaxVideoSize.second)
                .setExceedRendererCapabilitiesIfNecessary(false)
            )
            isBuffering = true
            playbackFailed = false
            playbackErrorMessage = null
            exoPlayer.prepare()
            exoPlayer.play()
            Log.i("THE_PITT_PLAYER", "720p fallback: riproduzione riavviata")
            return
          } catch (e: Exception) {
            Log.e("THE_PITT_PLAYER", "Fallback 720p fallito: ${e.message}")
          }
        }

        // Fallback automatico della sorgente: un errore DEFINITIVO della sorgente
        // attiva (link scaduto o protetto -> 403/404, contenuto non video,
        // manifest/container illeggibile) non deve lasciare l'utente sulla
        // schermata di errore se in lista esiste gia' un'altra sorgente risolta.
        // Il ViewModel esclude la sorgente fallita e quelle gia' tentate (nessun
        // ciclo, nessun ping-pong) e avvia l'alternativa con lo stesso meccanismo
        // della scelta manuale: stessa istanza di ExoPlayer, posizione preservata.
        // Buffering e seek non passano di qui: non generano `onPlayerError`.
        if (viewModel.handleSourcePlaybackFailure(
            failedStreamUrl = playbackState.streamUrl,
            verdict = PlayerSourceFallback.classify(error)
          )
        ) {
          isBuffering = true
          playbackFailed = false
          playbackErrorMessage = null
          val switched = viewModel.playbackState.value
          sourceSwitchNotice = switched.currentSource
            ?.let { PlayerSourceSelector.labelOf(it).takeIf { label -> label.isNotBlank() } }
            ?: switched.streamServer
          return
        }

        isBuffering = false
        playbackEnded = false
        playbackFailed = true
        playbackErrorMessage = context.getString(R.string.player_playback_error_message, displayMsg)
      }
    }
    exoPlayer.addListener(listener)
    // --- DIAGNOSTICA 4K (solo log): decoder video, input format, codec error, primo frame ---
    val diagAnalyticsListener = StreamNova4KDiag.createAnalyticsListener(isHls = isHlsStream)
    exoPlayer.addAnalyticsListener(diagAnalyticsListener)

    onDispose {
      if (playbackSettings.autoFrameRateMatching) {
        val act = context as? Activity
        FrameRateUtils.resetDisplayMode(act)
      }
      val finalPos = exoPlayer.currentPosition
      currentSimklScrobbler.onStop(simklProgressPercent(finalPos, exoPlayer.duration))
      viewModel.updatePlaybackPosition(finalPos, exoPlayer.duration, exoPlayer.bufferedPosition)
      if (finalPos > 0) {
        viewModel.saveCurrentPlaybackProgress(finalPos)
      }
      // NON chiamare resetStreamState qui: onDispose scatta anche quando il
      // player viene RICREATO (es. cambio tipo HLS/progressivo tra episodi o
      // cambio impostazioni a sessione in corso). Un reset cancellerebbe la
      // risoluzione delle sorgenti in volo e porterebbe al falso errore
      // "Stream non disponibile". La pulizia dello stato è già garantita da
      // closePlayer()/backToBrowsing()/backFromDetail() quando si esce davvero.
      exoPlayer.removeListener(listener)
      exoPlayer.removeAnalyticsListener(diagAnalyticsListener)
      exoPlayer.stop()
      exoPlayer.clearMediaItems()
      // [NEXT_EP_DEBUG] diagnostica rilascio: deve comparire SOLO alla chiusura del
      // player o alla sua vera ricreazione, MAI durante il cambio episodio.
      Log.d("[NEXT_EP_DEBUG]", "onDispose: exoPlayer.release() chiamato su $exoPlayer")
      exoPlayer.release()
    }
  }

  // Auto Frame Rate Matching (AFR): probing del container video se il formato non espone fps
  LaunchedEffect(exoPlayer, videoUrl, playbackSettings.autoFrameRateMatching) {
    if (playbackSettings.autoFrameRateMatching && videoUrl != null) {
      val detection = FrameRateUtils.probeVideoFrameRate(context, videoUrl, filename = media.title)
      if (detection != null && detection.snapped > 0f) {
        val act = context as? Activity
        FrameRateUtils.switchDisplayModeForFrameRate(act, detection.snapped)
      }
    }
  }

  // Polling periodico per la posizione e aggiornamento risoluzione se non ancora agganciata
  val currentPositionRef by rememberUpdatedState(currentPosition)
  val totalDurationRef by rememberUpdatedState(totalDuration)
  val selectedQualityLabelRef by rememberUpdatedState(selectedQualityLabel)
  val detectedResolutionRef by rememberUpdatedState(detectedResolution)

  // Bucket (posizione/5s) dell'ultimo log [NextEpisode] negli ultimi 40s: throttling
  // a ~5 secondi per non spammare il logcat durante la finestra di fine episodio.
  var lastNextEpLogBucket by remember { mutableLongStateOf(-1L) }

  LaunchedEffect(exoPlayer, isPlaying) {
    while (true) {
      if (exoPlayer.isPlaying) {
        currentPosition = exoPlayer.currentPosition
        // player.duration può essere C.TIME_UNSET (-1) o non ancora pronto: in tal
        // caso si mantengono i valori noti, senza basare overlay/countdown su durate fittizie.
        val rawDuration = exoPlayer.duration
        if (rawDuration > 0L && rawDuration != C.TIME_UNSET) {
          totalDuration = rawDuration
        }
        viewModel.updatePlaybackPosition(currentPosition, totalDuration, exoPlayer.bufferedPosition)
        // [NextEpisode] diagnostica negli ultimi 40 secondi (log throttled a ~5s).
        val remaining = totalDuration - currentPosition
        if (isTvShow && nextEpisode != null && remaining in 0L..40_000L) {
          val bucket = currentPosition / 5_000L
          if (bucket != lastNextEpLogBucket) {
            lastNextEpLogBucket = bucket
            Log.i(
              "NextEpisode",
              "pos=$currentPosition / dur=$totalDuration, remaining=${remaining}ms, shouldShowPrompt=$isNextEpisodePromptVisible"
            )
          }
        }
        if (selectedQualityLabelRef == "Auto" && detectedResolutionRef.isEmpty()) {
          val h = exoPlayer.videoFormat?.height ?: exoPlayer.videoSize.height
          val w = exoPlayer.videoFormat?.width ?: exoPlayer.videoSize.width
          updateResolutionFromHeight(h, w)
        }
        if (introWindow == null && introDbWindow != null) {
          updateIntroWindow()
        }
        introWindow?.let { win ->
          if (currentPosition in (win.startMs - 15_000L)..(win.endMs)) {
            val visible = !isIntroDismissed && PlayerIntroUtils.isButtonVisible(currentPosition, win)
            Log.d(
              "StreamNovaIntro",
              "Posizione intro: currentPosition=$currentPosition, startMs=${win.startMs}, endMs=${win.endMs}, isButtonVisible=$visible, isIntroDismissed=$isIntroDismissed, isTvShow=$isTvShow"
            )
          }
        }
      }
      delay(500)
    }
  }

  // ---------------------------------------------------------------------------
  // INTRO / "SALTA INTRO" (IntroDB e Metadati Reali)
  // ---------------------------------------------------------------------------
  LaunchedEffect(media.id, currentEp?.id) {
    isIntroDismissed = false
    introDbWindow = null
    updateIntroWindow()

    if (isTvShow) {
      val season = currentEp?.seasonNumber ?: episode?.seasonNumber ?: media.lastWatchedSeason ?: 1
      val epNum = currentEp?.episodeNumber ?: episode?.episodeNumber ?: media.lastWatchedEpisode ?: 1
      val imdbId = if (media.id.startsWith("tt", ignoreCase = true)) {
        media.id.substringBefore(":")
      } else null

      val fetched = IntroSkipRepository.getIntroWindow(
        imdbId = imdbId,
        tmdbId = media.tmdbId,
        season = season,
        episode = epNum
      )
      if (fetched != null) {
        introDbWindow = fetched
        updateIntroWindow()
        Log.d("StreamNovaIntro", "IntroDB timestamps applicati per S${season}E${epNum}: $fetched")
      } else {
        Log.d("StreamNovaIntro", "Nessun timestamp intro trovato su IntroDB per S${season}E${epNum}")
      }
    }
  }

  LaunchedEffect(exoPlayer.currentMediaItem, introDbWindow) {
    updateIntroWindow()
  }

  val isIntroActive = !isIntroDismissed &&
    PlayerIntroUtils.isButtonVisible(currentPosition, introWindow)

  // Durante lo scrubbing l'HUD resta nascosto anche a video in pausa: l'unico elemento a
  // video è l'overlay di anteprima della posizione.
  val hudShown = (areControlsVisible || !isPlaying) && !isScrubbing

  val showSkipIntro = isIntroActive && !hudShown && !isScrubbing

  fun kickAutoHide() {
    hideTimerTick++
  }

  fun requestFocusOn(target: FocusRequester) {
    focusScope.launch { target.requestFocusAfterFrame() }
  }

  fun togglePlayPause() {
    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
    hideTimerTick++
  }

  // "Guarda ora": passaggio manuale all'episodio successivo dall'overlay (interrompe il countdown).
  fun confirmNextEpisode() {
    val target = nextEpisode ?: return
    if (playbackFailed) return
    nextEpisodeCountdownSeconds = 0
    Log.i("NextEpisode", "Transizione manuale (Guarda ora) a S${target.seasonNumber}E${target.episodeNumber}")
    viewModel.saveCurrentPlaybackProgress(exoPlayer.duration.coerceAtLeast(exoPlayer.currentPosition))
    viewModel.playNextEpisode(target)
  }

  fun seekBy(deltaMs: Long, repeatCount: Int = 0) {
    val repeatMultiplier = when {
      repeatCount >= 8 -> 5L
      repeatCount >= 5 -> 3L
      repeatCount >= 2 -> 2L
      else -> 1L
    }
    val seekDelta = deltaMs * repeatMultiplier
    val durationMs = exoPlayer.duration
    val target = if (durationMs > 0) {
      (exoPlayer.currentPosition + seekDelta).coerceIn(0L, durationMs)
    } else {
      (exoPlayer.currentPosition + seekDelta).coerceAtLeast(0L)
    }
    exoPlayer.seekTo(target)
    currentPosition = target
    hideTimerTick++
  }

  fun showControls(focusPrimary: Boolean = true) {
    hideTimerTick++
    areControlsVisible = true
    if (focusPrimary) requestFocusOn(playPauseFocusRequester)
  }

  fun hideControls() {
    if (hudHasFocus) {
      try {
        idleFocusRequester.requestFocus()
      } catch (e: IllegalStateException) {
        Log.w("PlayerScreen", "Nodo di riposo non pronto: ${e.message}")
      }
    }
    areControlsVisible = false
  }

  /**
   * Apre il selettore delle sorgenti video interno al player.
   *
   * @param returnTo nodo a cui il focus viene ripristinato alla chiusura
   *   (null = nessun ripristino). Su Android TV e' il pulsante che ha aperto il
   *   selettore, cosi' la navigazione D-pad riparte da dove l'utente era.
   */
  fun openSourceSelector(returnTo: FocusRequester?) {
    sourceSelectorReturnRequester = returnTo
    viewModel.openSourceSelector()
  }

  /**
   * Chiude il selettore delle sorgenti e ripristina il focus sul nodo di apertura.
   *
   * La chiusura NON avvia alcun cambio di sorgente: solo la scelta esplicita
   * di una riga lo fa.
   */
  fun closeSourceSelector() {
    viewModel.dismissSourceSelector()
    val target = sourceSelectorReturnRequester
    sourceSelectorReturnRequester = null
    if (target != null) {
      focusScope.launch {
        runCatching { target.requestFocusAfterFrame() }
      }
    }
  }

  /**
   * Salta la sigla iniziale: seek reale a fine finestra, banner dismesso.
   * Non mostra l'HUD e non mette in pausa il player (riproduzione fluida).
   */
  fun skipIntroNow() {
    kickAutoHide()
    introWindow?.let { window ->
      Log.d(
        "StreamNovaIntro",
        "Salta Intro: seekTo=${window.endMs}, currentPosition=$currentPosition, isTvShow=$isTvShow"
      )
      exoPlayer.seekTo(window.endMs)
      currentPosition = window.endMs
    }
    isIntroDismissed = true
    try {
      idleFocusRequester.requestFocus()
    } catch (_: Exception) {}
  }

  // ---------------------------------------------------------------------------
  // SCRUBBING A SCHERMO INTERO CON IL D-PAD (◀ / ▶)
  //
  // Con l'HUD a schermo le frecce non vengono consumate da nessuno: restano a Compose,
  // che sposta il focus fra i controlli (Play, Tracce, Sottotitoli, ...). A video pulito
  // le frecce aprono lo scrubbing: il video va in pausa, la posizione di anteprima si
  // sposta di 10 s per pressione e diventa una seek reale solo alla conferma
  // (COMMIT_DELAY_MS di silenzio oppure OK). BACK annulla senza seek.
  // ---------------------------------------------------------------------------
  val scrubScope = rememberCoroutineScope()
  val scrub = remember(exoPlayer) {
    PlayerScrubController(scrubScope, exoPlayer) { state -> scrubState = state }
  }

  /** Pressione (o ripetizione) di ◀ / ▶ a controlli nascosti: apre o prosegue lo scrubbing. */
  fun onScrubArrowPressed(direction: Int) {
    scrub.onArrowPressed(direction)
  }

  /** Conclusione con seek reale (timer di conferma scaduto o OK premuto). */
  fun commitScrub() {
    scrub.commit()
  }

  /** BACK durante lo scrubbing: nessuna seek, si riprende dal punto di partenza. */
  fun cancelScrub() {
    scrub.cancel()
  }

  // Nessun job e nessuna sessione residui quando il player viene ricreato o la schermata esce.
  DisposableEffect(exoPlayer) {
    onDispose { scrub.release() }
  }

  // (1) Richiesta focus iniziale
  LaunchedEffect(Unit) {
    if (hudShown) {
      playPauseFocusRequester.requestFocusAfterFrame()
    } else {
      idleFocusRequester.requestFocusAfterFrame()
    }
  }

  // (1a) Richiesta focus immediata sul banner "Salta Intro" non appena diventa visibile
  LaunchedEffect(showSkipIntro) {
    if (showSkipIntro) {
      introBannerFocusRequester.requestFocusAfterFrame()
    }
  }

  // (1b) Sincronizzazione del focus del banner "Salta intro" flottante.
  // Non può essere una richiesta one-shot: `isIntroActive` diventa vero DOPO l'ingresso del
  // player (posizione 0 -> sigla risolta in modo asincrono). Con video pulito il banner è il
  // target di focus; quando sparisce si torna al nodo di riposo. Durante scrubbing, modali o
  // overlay "Prossimo Episodio" il focus NON viene toccato. `requestFocusAfterFrame` gestisce
  // il caso "nodo non ancora montato" senza invocare requester non agganciati.
  LaunchedEffect(hudShown, isIntroActive, isScrubbing, anyModalOpen, isNextEpisodePromptVisible) {
    when (
      resolveVideoFocusTarget(
        hudShown = hudShown,
        isIntroActive = isIntroActive,
        isScrubbing = isScrubbing,
        anyModalOpen = anyModalOpen,
        isNextEpisodePromptVisible = isNextEpisodePromptVisible
      )
    ) {
      VideoFocusTarget.INTRO_BANNER -> introBannerFocusRequester.requestFocusAfterFrame()
      VideoFocusTarget.IDLE -> if (!playerHasFocus) idleFocusRequester.requestFocusAfterFrame()
      VideoFocusTarget.NONE -> Unit
    }
  }

  // (2) Auto-hide dei controlli dopo 5 secondi di inattività
  LaunchedEffect(hideTimerTick, areControlsVisible, isPlaying, anyModalOpen) {
    if (areControlsVisible && isPlaying && !anyModalOpen) {
      delay(CONTROLS_HIDE_DELAY_MS)
      if (hudHasFocus) {
        try {
          idleFocusRequester.requestFocus()
        } catch (e: IllegalStateException) {
          Log.w("PlayerScreen", "Nodo di riposo non pronto: ${e.message}")
        }
      }
      areControlsVisible = false
    }
  }

  // (3) Ripristino focus dopo chiusura modali
  LaunchedEffect(anyModalOpen, areControlsVisible) {
    if (anyModalOpen || !areControlsVisible) return@LaunchedEffect
    // Con l'overlay "Prossimo Episodio" visibile il focus resta sul pulsante
    // "Guarda ora": nessun furto del focus verso i controlli di riproduzione.
    if (isNextEpisodePromptVisible) return@LaunchedEffect
    delay(200)
    if (!hudHasFocus) playPauseFocusRequester.requestFocusAfterFrame()
  }

  // (4) Il telecomando resta del player. Con i controlli a schermo ◀ / ▶ non vengono
  // consumate: le muove Compose fra Play, Tracce, Sottotitoli, ... Ai bordi della barra,
  // però, la ricerca geometrica del focus uscirebbe dalla schermata player verso i
  // controlli del catalogo coperti dal video, e lì resterebbe: le frecce non arriverebbero
  // più allo scrubbing. Qui il focus viene riportato sulla superficie visibile del player:
  // i controlli quando l'HUD è a schermo, il nodo di riposo quando il video è pulito.
  LaunchedEffect(
    hudShown,
    hudHasFocus,
    playerHasFocus,
    isScrubbing,
    anyModalOpen,
    isNextEpisodePromptVisible
  ) {
    if (anyModalOpen || isNextEpisodePromptVisible) return@LaunchedEffect
    if (hudShown) {
      if (!hudHasFocus) playPauseFocusRequester.requestFocusAfterFrame()
    } else if (!isScrubbing && !playerHasFocus) {
      idleFocusRequester.requestFocusAfterFrame()
    }
  }

  // BACK su controlli: se l'overlay "Prossimo Episodio" è visibile, il primo BACK lo
  // nasconde (consente di vedere i titoli di coda senza auto-next); il BACK successivo
  // esce dal player.
  BackHandler(enabled = !anyModalOpen) {
    Log.d("BACK_TRACE", "PLAYERSCREEN: ingresso del BackHandler")
    Log.d("BACK_TRACE", "PLAYERSCREEN: valore di anyModalOpen: $anyModalOpen")
    if (showSkipIntro) {
      Log.d("BACK_TRACE", "PLAYERSCREEN: BACK con banner Salta Intro visibile: chiusura banner")
      isIntroDismissed = true
      try {
        idleFocusRequester.requestFocus()
      } catch (_: Exception) {}
    } else if (isNextEpisodePromptVisible) {
      Log.d("BACK_TRACE", "PLAYERSCREEN: BACK su overlay Prossimo Episodio: chiusura overlay")
      nextEpisodeDismissed = true
      nextEpisodeCountdownSeconds = 0
    } else {
      Log.d("BACK_TRACE", "PLAYERSCREEN: chiamata a closePlayer()")
      viewModel.resetStreamState()
      viewModel.closePlayer()
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .onFocusChanged { state -> playerHasFocus = state.hasFocus }
      .background(Color.Black)
      // Lo scrubbing è gestito in PREVIEW, prima di ogni altro gestore del tasto: il
      // clickable dello sfondo (tocco per mostrare/nascondere l'HUD) consuma OK/ENTER nel
      // bubble e intercetterebbe la conferma, e le frecce devono restare allo scrubbing
      // anche quando il focus è su un controllo coperto dell'HUD.
      .onPreviewKeyEvent { keyEvent ->
        val native = keyEvent.nativeKeyEvent
        val keyCode = native.keyCode
        val isKeyDown = native.action == KeyEvent.ACTION_DOWN
        val scrubDirection = PlayerScrub.directionForKeyCode(keyCode)

        // Rilascio dell'OK che ha confermato lo skip intro: consumato qui, altrimenti il
        // clickable dello sfondo interpreterebbe il click e aprirebbe i controlli.
        if (skipIntroOkReleasePending.value) {
          skipIntroOkReleasePending.value = false
          if (!isKeyDown) return@onPreviewKeyEvent true
        }

        // GUARDIA PRIORITARIA BANNER "SALTA INTRO":
        // Se il banner Salta Intro è visibile e l'utente preme KEYCODE_DPAD_CENTER o KEYCODE_ENTER,
        // invoca direttamente l'azione di skip intro (exoPlayer.seekTo(introEndTime)) e consuma
        // l'evento (return true), impedendo al player di andare in pausa o mostrare l'HUD.
        if (showSkipIntro && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
          if (isKeyDown && native.repeatCount == 0) {
            skipIntroOkReleasePending.value = true
            skipIntroNow()
          }
          return@onPreviewKeyEvent true
        }

        // Rilascio dell'OK che ha confermato lo scrubbing: consumato qui, altrimenti il
        // clickable dello sfondo interpreterebbe il click e aprirebbe i controlli.
        if (scrubOkReleasePending.value) {
          scrubOkReleasePending.value = false
          if (!isKeyDown) return@onPreviewKeyEvent true
        }

        if (scrub.isScrubbing) {
          // Con l'anteprima aperta il telecomando appartiene allo scrubbing: ogni pressione
          // (comprese le ripetizioni del sistema) vale un passo, OK conferma, BACK annulla.
          when {
            keyCode == KeyEvent.KEYCODE_BACK -> {
              if (isKeyDown && native.repeatCount == 0) cancelScrub()
              return@onPreviewKeyEvent true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER -> {
              if (isKeyDown) {
                commitScrub()
                scrubOkReleasePending.value = true
              }
              return@onPreviewKeyEvent true
            }
            scrubDirection != 0 -> {
              if (isKeyDown) onScrubArrowPressed(scrubDirection)
              return@onPreviewKeyEvent true
            }
          }
          // Qualsiasi altro tasto prosegue e conclude lo scrubbing nel gestore del bubble,
          // confermandone la posizione (l'utente non deve perdere il punto raggiunto).
          return@onPreviewKeyEvent false
        }

        // ◀ / ▶ a video pulito: aprono lo scrubbing. Con l'HUD a schermo (o una modale
        // aperta) l'evento NON viene toccato: resta a Compose, che sposta il focus fra i
        // controlli a video (Play, Tracce audio, Sottotitoli, ...).
        if (scrubDirection != 0) {
          if (hudShown || anyModalOpen) return@onPreviewKeyEvent false
          if (isKeyDown) onScrubArrowPressed(scrubDirection)
          return@onPreviewKeyEvent true
        }

        false
      }
      .onKeyEvent { keyEvent ->
        val native = keyEvent.nativeKeyEvent
        val keyCode = native.keyCode
        val isKeyDown = native.action == KeyEvent.ACTION_DOWN

        // Gestione BACK telecomando
        if (keyCode == KeyEvent.KEYCODE_BACK) {
          if (isKeyDown && native.repeatCount == 0) {
            Log.d("BACK_TRACE", "PLAYERSCREEN: onKeyEvent KEYCODE_BACK down, anyModalOpen=$anyModalOpen")
            if (anyModalOpen) {
              showTrackQuickPanel = false
              showSubtitleModal = false
              showQualityModal = false
              showSpeedModal = false
            } else if (showSkipIntro) {
              Log.d("BACK_TRACE", "PLAYERSCREEN: onKeyEvent BACK su Salta Intro: chiusura banner")
              isIntroDismissed = true
              try {
                idleFocusRequester.requestFocus()
              } catch (_: Exception) {}
            } else if (isNextEpisodePromptVisible) {
              // Primo BACK con overlay "Prossimo Episodio": nasconde solo l'overlay
              // (niente auto-next, restano i titoli di coda); serve un secondo BACK per uscire.
              Log.d("BACK_TRACE", "PLAYERSCREEN: BACK su overlay Prossimo Episodio: chiusura overlay")
              nextEpisodeDismissed = true
              nextEpisodeCountdownSeconds = 0
            } else {
              Log.d("BACK_TRACE", "PLAYERSCREEN: onKeyEvent chiamata a closePlayer()")
              viewModel.resetStreamState()
              viewModel.closePlayer()
            }
          }
          return@onKeyEvent true
        }

        if (!isKeyDown) return@onKeyEvent false

        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
          native.repeatCount > 0
        ) {
          return@onKeyEvent false
        }

        // Qualsiasi altro tasto è un comando diverso: uno scrubbing ancora aperto si
        // conclude confermandone la posizione, perché l'utente non deve perdere il punto
        // raggiunto con ◀ / ▶.
        if (scrub.isScrubbing) commitScrub()

        // Qualsiasi pressione riavvia l'auto-hide (le frecce della ricerca sono escluse:
        // non devono mostrare né prolungare la visibilità dei controlli)
        when (keyCode) {
          KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
          KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
          KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
          KeyEvent.KEYCODE_MEDIA_REWIND -> kickAutoHide()
        }

        when (keyCode) {
          KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
            togglePlayPause()
            showControls()
            true
          }

          KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
            seekBy(10_000L, native.repeatCount)
            showControls(focusPrimary = false)
            true
          }

          KeyEvent.KEYCODE_MEDIA_REWIND -> {
            seekBy(-10_000L, native.repeatCount)
            showControls(focusPrimary = false)
            true
          }

          // OK / Centro D-Pad
          KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> when {
            // Overlay "Prossimo Episodio" visibile (e focus non sul badge, gia'
            // gestito da TvFocusableBox): OK/ENTER avvia SUBITO la transizione.
            isNextEpisodePromptVisible -> {
              confirmNextEpisode()
              true
            }
            // Banner "Salta intro" visibile a video pulito: OK salta la sigla. Il banner,
            // quando ha il focus, consuma già OK per conto proprio; questo ramo copre il
            // caso in cui l'evento arrivi comunque alla root. NON deve aprire l'HUD.
            showSkipIntro -> {
              skipIntroNow()
              true
            }
            !hudShown -> {
              showControls()
              true
            }
            !hudHasFocus -> {
              togglePlayPause()
              true
            }
            else -> false
          }

          // Frecce Sinistra / Destra: gestite nell'handler di PREVIEW (focus fra i controlli
          // con l'HUD a schermo, scrubbing a video pulito). Qui restano solo Su / Giù.

// Frecce Su / Giù
          KeyEvent.KEYCODE_DPAD_UP -> {
            // A HUD visibile la barra di scorrimento esiste ed è il target naturale. A video
            // pulito NON si consuma il tasto (la barra non è montata): così la ricerca di focus
            // può raggiungere il banner "Salta intro" invece di essere bloccata a vuoto.
            if (hudShown) {
              requestFocusOn(seekBarFocusRequester)
              true
            } else {
              false
            }
          }
          KeyEvent.KEYCODE_DPAD_DOWN -> when {
            !hudShown -> {
              showControls(focusPrimary = false)
              true
            }
            hudHasFocus -> {
              requestFocusOn(playPauseFocusRequester)
              true
            }
            else -> false
          }

          else -> false
        }
      }
      .clickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null
      ) {
        if (areControlsVisible) hideControls() else showControls()
      }
  ) {
    // Nodo di riposo 1dp invisibile: mantiene il focus quando l'HUD si nasconde
    Box(
      modifier = Modifier
        .size(1.dp)
        .focusRequester(idleFocusRequester)
        .focusable()
    )

    // 1. Vista Video ExoPlayer
    AndroidView(
      factory = { ctx ->
        PlayerView(ctx).apply {
          this.player = exoPlayer
          useController = false
          isFocusable = false
          isFocusableInTouchMode = false
          descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
          isClickable = false
          // Il player tiene acceso lo schermo mentre il video è attivo.
          this.keepScreenOn = true
          this.resizeMode = resizeMode
          layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
          )
        }
      },
      update = { view ->
        view.player = exoPlayer
        view.resizeMode = resizeMode
        // Fire TV: nessun salvaschermo di sistema mentre il video è attivo.
        view.keepScreenOn = keepScreenOn
        // FASE 3.3 "Dimensione sottotitoli": applica la dimensione persistente al rendering
        // dei sottotitoli Media3 (SubtitleView di PlayerView). Frazione dell'altezza vista;
        // senza tracce sottotitoli non produce alcun effetto o errore.
        view.subtitleView?.setFractionalTextSize(subtitleTextSizeFraction)
        // FASE 3.4 "Sfondo dei sottotitoli": applica lo sfondo persistente via
        // SubtitleView.setStyle(CaptionStyleCompat) (API disponibile in Media3 1.5.1).
        // Nei cue senza background incorporato, SubtitlePainter disegna
        // CaptionStyleCompat.backgroundColor dietro al testo solo se alpha > 0, quindi
        // con "Nessuno" (alpha 0) nessuno sfondo è disegnato. Senza tracce sottotitoli
        // non produce alcun effetto o errore.
        view.subtitleView?.setStyle(subtitleCaptionStyle)
        // FASE 3.5 "Posizione dei sottotitoli": applica la posizione verticale persistente
        // via SubtitleView.setBottomPaddingFraction (API nativa disponibile in Media3 1.5.1).
        // È la distanza dal bordo inferiore come frazione dell'altezza vista, usata da
        // SubtitlePainter per i cue senza posizione esplicita (Cue.line == DIMEN_UNSET,
        // caso standard); cue con posizione propria e cue bitmap restano nativi Media3.
        // Senza tracce sottotitoli non produce alcun effetto o errore.
        view.subtitleView?.setBottomPaddingFraction(subtitleBottomPaddingFraction)
        Log.i(
          "PlayerScreen",
          "Dimensione sottotitoli: ${playbackSettings.subtitleSize.label} (fraction=$subtitleTextSizeFraction); " +
            "sfondo: ${playbackSettings.subtitleBackground.label} (${subtitleCaptionStyle.backgroundColor}); " +
            "posizione: ${playbackSettings.subtitlePosition.label} (bottomFraction=$subtitleBottomPaddingFraction)"
        )
      },
      modifier = Modifier.fillMaxSize()
    )

    // Pill feedback formato video (Adatta, Zoom, Riempi)
    AnimatedVisibility(
      visible = resizeFeedbackText != null,
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier
        .align(Alignment.TopCenter)
        .padding(top = 40.dp)
    ) {
      Box(
        modifier = Modifier
          .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(20.dp))
          .border(1.dp, NovaCyanBright, RoundedCornerShape(20.dp))
          .padding(horizontal = 20.dp, vertical = 8.dp)
      ) {
        Text(
          text = stringResource(R.string.player_format_label, resizeFeedbackText ?: ""),
          color = NovaCyanBright,
          fontSize = 14.sp,
          fontWeight = FontWeight.Bold
        )
      }
    }

    // Messaggio discreto del cambio automatico di sorgente (fallback): pill in
    // alto, nessun dialog e nessuna interruzione della riproduzione.
    AnimatedVisibility(
      visible = sourceSwitchNotice != null,
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier
        .align(Alignment.TopCenter)
        .padding(top = 96.dp)
    ) {
      Box(
        modifier = Modifier
          .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(20.dp))
          .border(1.dp, NovaCyan.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
          .padding(horizontal = 20.dp, vertical = 8.dp)
      ) {
        Text(
          text = stringResource(R.string.player_source_fallback_switched, sourceSwitchNotice ?: ""),
          color = NovaTextPrimary,
          fontSize = 13.sp,
          fontWeight = FontWeight.Medium
        )
      }
    }

    // Buffering indicator: durante lo scrubbing resta fuori schermo
    // (l'unico elemento a video è l'overlay di anteprima della posizione).
    if (isBuffering && playbackErrorMessage == null && !isScrubbing) {
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(24.dp)
        ) {
          CircularProgressIndicator(
            color = NovaCyanBright,
            modifier = Modifier.size(48.dp)
          )
          Spacer(modifier = Modifier.height(12.dp))
          Text(
            text = if (isSwitchingEpisode) {
              stringResource(R.string.player_loading_next_episode)
            } else {
              stringResource(R.string.player_loading_stream)
            },
            color = NovaTextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
          )
          // Durante il buffering il comando "Sorgente video" resta raggiungibile:
          // l'utente non e' mai costretto ad aspettare la fine del caricamento.
          if (sourceCandidates.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            PlayerSourceOverlayButton(
              modifier = Modifier.focusRequester(sourceSelectorOverlayFocusRequester),
              onClick = { openSourceSelector(sourceSelectorOverlayFocusRequester) }
            )
          }
        }
      }
    }

    // Errore compatto: mantiene visibili video e controlli per non bloccare il player.
    if (playbackErrorMessage != null) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .align(Alignment.TopCenter)
          .padding(horizontal = 24.dp, vertical = 24.dp)
          .zIndex(20f),
        contentAlignment = Alignment.TopCenter
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
          modifier = Modifier
            .widthIn(max = 820.dp)
            .background(NovaSurface, RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFFFF5252).copy(alpha = 0.8f), RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
          Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = stringResource(R.string.player_cd_playback_error),
            tint = Color(0xFFFF5252),
            modifier = Modifier.size(24.dp)
          )
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
          ) {
            Text(
              text = stringResource(R.string.player_playback_error_title),
              color = NovaTextPrimary,
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = playbackErrorMessage.orEmpty(),
              color = NovaTextSecondary,
              fontSize = 12.sp,
              maxLines = 3,
              overflow = TextOverflow.Ellipsis
            )
          }
          if (videoUrl != null) {
            TvFocusableBox(
              shape = RoundedCornerShape(10.dp),
              focusedScale = 1.04f,
              onClick = {
                playbackErrorMessage = null
                playbackFailed = false
                isBuffering = true
                exoPlayer.setMediaItem(
                  PlayerSubtitleMediaItemBuilder.buildMediaItem(videoUrl, stremioSubtitles)
                )
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
              }
            ) { isFocused ->
              Row(
                modifier = Modifier
                  .background(
                    if (isFocused) NovaCyan else NovaSurfaceVariant,
                    RoundedCornerShape(10.dp)
                  )
                  .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(
                  imageVector = Icons.Default.Refresh,
                  contentDescription = null,
                  tint = if (isFocused) Color.Black else NovaCyanBright,
                  modifier = Modifier.size(18.dp)
                )
                Text(
                  text = stringResource(R.string.action_retry),
                  color = if (isFocused) Color.Black else NovaTextPrimary,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold
                )
              }
            }
          }
          // In errore il selettore sorgenti resta raggiungibile: l'utente puo'
          // ripartire subito con un'altra sorgente senza riavviare la ricerca.
          if (sourceCandidates.isNotEmpty()) {
            PlayerSourceOverlayButton(
              modifier = Modifier.focusRequester(sourceSelectorOverlayFocusRequester),
              onClick = { openSourceSelector(sourceSelectorOverlayFocusRequester) }
            )
          }
        }
      }
    }

    // 2. Overlay Controlli HUD Player TV
    AnimatedVisibility(
      visible = hudShown,
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier.fillMaxSize()
    ) {
      Box(
        modifier = Modifier
          .onFocusChanged { state -> hudHasFocus = state.hasFocus }
          .fillMaxSize()
          .onPreviewKeyEvent {
            kickAutoHide()
            false
          }
          .background(
            Brush.verticalGradient(
              colors = listOf(
                Color.Transparent,
                Color.Transparent,
                Color(0x99040814),
                Color(0xE6040814),
                Color.Black.copy(alpha = 0.95f)
              )
            )
          )
      ) {
        // Contenitore unico inferiore (Sezione Info + Barra di Scorrimento + Barra Controlli)
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .padding(bottom = 24.dp)
        ) {
          // -------------------------------------------------------------------
          // 1. SEZIONE INFORMATIVA & PULSANTE SALTA INTRO (Sopra la barra di scorrimento)
          // -------------------------------------------------------------------
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 36.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
          ) {
            // Lato Sinistro: Titolo + Sottotitolo + Risoluzione
            Column(modifier = Modifier.weight(1f, fill = false)) {
              Text(
                text = media.title,
                color = Color.White,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )

              if (episodeSubtitle != null) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                  text = episodeSubtitle,
                  color = Color(0xFFD1D5DB),
                  fontSize = 15.sp,
                  fontWeight = FontWeight.Normal,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }

              Spacer(modifier = Modifier.height(3.dp))
              Text(
                text = detectedResolution,
                color = Color(0xFF9CA3AF),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
              )

              // Badge sorgente: evidenzia i flussi Debrid istantanei
              // (es. [TorBox Instant 4K] / [TorBox Instant 1080p]).
              val serverBadge = playbackState.streamServer
              if (!serverBadge.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                  text = serverBadge,
                  color = NovaCyanBright,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.SemiBold,
                  modifier = Modifier
                    .background(NovaCyan.copy(alpha = 0.16f), RoundedCornerShape(50))
                    .border(1.dp, NovaCyan.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 3.dp)
                )
              }
            }

            // Lato Destro: Pulsante a pillola "SALTA INTRO" visibile sopra la timeline
            AnimatedVisibility(
              visible = isIntroActive,
              enter = fadeIn() + slideInHorizontally(initialOffsetX = { it / 2 }),
              exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it / 2 })
            ) {
              TvFocusableBox(
                modifier = Modifier
                  .focusRequester(skipIntroFocusRequester)
                  .onKeyEvent { keyEvent ->
                    val native = keyEvent.nativeKeyEvent
                    if (native.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                    when (native.keyCode) {
                      KeyEvent.KEYCODE_DPAD_DOWN -> {
                        requestFocusOn(seekBarFocusRequester)
                        true
                      }
                      KeyEvent.KEYCODE_DPAD_UP -> {
                        if (!hudShown) false
                        else {
                          hideControls()
                          true
                        }
                      }
                      else -> false
                    }
                  },
                shape = RoundedCornerShape(50),
                onClick = {
                  kickAutoHide()
                  introWindow?.let { window ->
                    Log.d(
                      "StreamNovaIntro",
                      "Click Salta Intro (HUD): seekTo=${window.endMs}, currentPosition=$currentPosition, isTvShow=$isTvShow"
                    )
                    exoPlayer.seekTo(window.endMs)
                    currentPosition = window.endMs
                  }
                  isIntroDismissed = true
                  requestFocusOn(playPauseFocusRequester)
                }
              ) { isFocused ->
                Row(
                  modifier = Modifier
                    .shadow(
                      elevation = if (isFocused) 16.dp else 4.dp,
                      shape = RoundedCornerShape(50),
                      spotColor = NovaCyanBright
                    )
                    .background(
                      color = if (isFocused) NovaCyanBright else Color(0xCC0B111E),
                      shape = RoundedCornerShape(50)
                    )
                    .border(
                      width = if (isFocused) 2.dp else 1.5.dp,
                      color = if (isFocused) Color.White else NovaCyan.copy(alpha = 0.8f),
                      shape = RoundedCornerShape(50)
                    )
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  Icon(
                    imageVector = Icons.Default.FastForward,
                    contentDescription = stringResource(R.string.player_cd_skip_intro),
                    tint = if (isFocused) Color.Black else NovaCyanBright,
                    modifier = Modifier.size(18.dp)
                  )
                  Text(
                    text = stringResource(R.string.player_skip_intro),
                    color = if (isFocused) Color.Black else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    letterSpacing = 0.5.sp
                  )
                }
              }
            }
          }

          Spacer(modifier = Modifier.height(12.dp))

          // -------------------------------------------------------------------
          // 2. BARRA DI SCORRIMENTO (Timeline)
          // -------------------------------------------------------------------
          val progressFraction = if (totalDuration > 0) {
            (currentPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
          } else 0f

          val bufferFraction = if (totalDuration > 0) {
            (exoPlayer.bufferedPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
          } else 0f

          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 26.dp)
          ) {
            TvFocusableBox(
              modifier = Modifier
                .fillMaxWidth()
                .focusRequester(seekBarFocusRequester)
                .onKeyEvent { keyEvent ->
                  val native = keyEvent.nativeKeyEvent
                  if (native.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                  // Le frecce ◀ / ▶ non sono gestite qui: la barra non deve intercettare
                  // la ricerca veloce continua (né consumarla con salti di 10 secondi).
                  // UP/DOWN spostano il focus fra barra, Salta Intro e pulsanti.
                  when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                      if (isIntroActive) {
                        requestFocusOn(skipIntroFocusRequester)
                        true
                      } else {
                        false
                      }
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                      requestFocusOn(playPauseFocusRequester)
                      true
                    }
                    else -> false
                  }
                },
              shape = RoundedCornerShape(8.dp),
              focusedScale = 1.0f,
              focusedBorderColor = NovaCyanBright,
              borderWidth = 2.dp,
              onClick = { togglePlayPause() }
            ) { isFocused ->
              BoxWithConstraints(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.CenterStart
              ) {
                val widthPx = maxWidth

                // Track Base della Timeline
                Box(
                  modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isFocused) 6.dp else 4.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.22f))
                ) {
                  // Buffer Indicator
                  Box(
                    modifier = Modifier
                      .fillMaxWidth(bufferFraction)
                      .fillMaxHeight()
                      .background(Color.White.copy(alpha = 0.38f))
                  )

                  // Progress Indicator
                  Box(
                    modifier = Modifier
                      .fillMaxWidth(progressFraction)
                      .fillMaxHeight()
                      .background(
                        if (isFocused) Brush.horizontalGradient(listOf(NovaCyan, NovaCyanBright))
                        else Brush.horizontalGradient(listOf(Color(0xFFE2E8F0), Color.White))
                      )
                  )
                }

                // Thumb / Indicatore di avanzamento
                val thumbOffset = (widthPx - 14.dp) * progressFraction
                Box(
                  modifier = Modifier
                    .padding(start = thumbOffset.coerceAtLeast(0.dp))
                    .size(if (isFocused) 14.dp else 9.dp)
                    .shadow(
                      elevation = if (isFocused) 10.dp else 2.dp,
                      shape = CircleShape,
                      spotColor = NovaCyanBright
                    )
                    .background(
                      if (isFocused) NovaCyanBright else Color.White,
                      CircleShape
                    )
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(10.dp))

          // -------------------------------------------------------------------
          // 3. BARRA CONTROLLI INFERIORE (Sotto la timeline)
          // -------------------------------------------------------------------
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 36.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            // LATO SINISTRO: Sequenza pulsanti azione
            Row(
              horizontalArrangement = Arrangement.spacedBy(16.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              // 1. Play / Pausa: pulsante circolare bianco ad alto contrasto
              TvFocusableBox(
                modifier = Modifier.focusRequester(playPauseFocusRequester),
                shape = CircleShape,
                focusedScale = 1.15f,
                focusedBorderColor = NovaCyanBright,
                borderWidth = 2.5.dp,
                onClick = { togglePlayPause() }
              ) { isFocused ->
                Box(
                  modifier = Modifier
                    .size(48.dp)
                    .shadow(
                      elevation = if (isFocused) 16.dp else 4.dp,
                      shape = CircleShape,
                      spotColor = NovaCyanBright
                    )
                    .background(
                      if (isFocused) NovaCyanBright else Color.White,
                      CircleShape
                    ),
                  contentAlignment = Alignment.Center
                ) {
                  Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) stringResource(R.string.player_cd_pause) else stringResource(R.string.player_cd_play),
                    tint = Color.Black,
                    modifier = Modifier.size(28.dp)
                  )
                }
              }

              // 2. Prossimo Episodio: icona Skip Next (Visibile SOLO se Serie TV e con episodio successivo)
              if (isTvShow && nextEpisode != null) {
                val skipTarget = nextEpisode
                PlayerControlIconButton(
                  icon = Icons.Default.SkipNext,
                  contentDescription = stringResource(R.string.player_cd_next_episode),
                  onClick = {
                    kickAutoHide()
                    viewModel.saveCurrentPlaybackProgress(exoPlayer.currentPosition)
                    if (skipTarget != null) viewModel.playNextEpisode(skipTarget)
                  }
                )
              }

              // 3. Adatta (Aspect Ratio): icona schermo per commutare ResizeMode
              PlayerControlIconButton(
                icon = Icons.Default.AspectRatio,
                contentDescription = stringResource(R.string.player_cd_aspect_ratio),
                onClick = {
                  kickAutoHide()
                  resizeMode = PlayerDisplayModeUtils.nextResizeMode(resizeMode)
                  resizeFeedbackText = PlayerDisplayModeUtils.resizeModeLabel(resizeMode, context)
                }
              )

              // 4. Qualità Video (Risoluzione): icona HighQuality
              PlayerControlIconButton(
                icon = Icons.Default.HighQuality,
                contentDescription = stringResource(R.string.player_cd_video_quality),
                onClick = {
                  kickAutoHide()
                  showQualityModal = true
                }
              )

              // 5. Velocità di riproduzione: icona tachimetro (0.75x, 1.0x, 1.25x, 1.5x)
              PlayerControlIconButton(
                icon = Icons.Default.Speed,
                contentDescription = stringResource(R.string.player_cd_playback_speed),
                onClick = {
                  kickAutoHide()
                  showSpeedModal = true
                }
              )

              // 6. Sottotitoli: icona CC (Closed Captions)
              PlayerControlIconButton(
                icon = Icons.Default.ClosedCaption,
                contentDescription = stringResource(R.string.player_cd_subtitles),
                onClick = {
                  kickAutoHide()
                  showSubtitleModal = true
                }
              )

// 7. Audio: icona altoparlante
               PlayerControlIconButton(
                 icon = Icons.Filled.Speaker,
                 contentDescription = stringResource(R.string.player_cd_audio_tracks),
                 onClick = {
                   kickAutoHide()
                   showTrackQuickPanel = true
                 }
               )

              // 8. Sorgente video: selettore manuale delle sorgenti del titolo.
              // Disponibile in ogni momento della sessione (riproduzione,
              // buffering, errore): il comando e' sempre raggiungibile e il
              // focus viene ripristinato sul pulsante alla chiusura.
              PlayerControlIconButton(
                icon = Icons.Default.SwapHoriz,
                contentDescription = stringResource(R.string.player_cd_video_source),
                modifier = Modifier.focusRequester(sourceSelectorFocusRequester),
                onClick = {
                  kickAutoHide()
                  openSourceSelector(sourceSelectorFocusRequester)
                }
              )
            }

            // LATO DESTRO: Minutaggio formattato (es. "00:02 / 41:54")
            Text(
              text = formatTimePair(currentPosition, totalDuration),
              color = Color.White,
              fontSize = 16.sp,
              fontWeight = FontWeight.SemiBold
            )
          }
        }
      }
    }

    // 3. Banner "Salta Intro" fluttuante quando l'HUD è nascosto (per serie TV).
    // Durante lo scrubbing resta fuori schermo: mentre l'utente cerca nel contenuto
    // l'unico elemento a video è l'overlay di anteprima.
    AnimatedVisibility(
      visible = showSkipIntro,
      enter = fadeIn() + slideInHorizontally(initialOffsetX = { it }),
      exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it }),
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(
          end = 40.dp,
          bottom = 40.dp
        )
    ) {
      TvFocusableBox(
        modifier = Modifier
          // Requester dedicato: NON condiviso con il pulsante "Salta intro" dell'HUD.
          .focusRequester(introBannerFocusRequester)
          .onKeyEvent { keyEvent ->
            val native = keyEvent.nativeKeyEvent
            if (native.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
            when (native.keyCode) {
              KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Focus lascia il banner: se l'utente preferisce non saltare l'intro,
                // il banner viene dismesso e il focus torna al nodo di riposo.
                isIntroDismissed = true
                try {
                  idleFocusRequester.requestFocus()
                } catch (_: Exception) {}
                true
              }
              KeyEvent.KEYCODE_BACK -> {
                // BACK sul banner: dismiss del banner senza chiudere il player
                isIntroDismissed = true
                try {
                  idleFocusRequester.requestFocus()
                } catch (_: Exception) {}
                true
              }
              KeyEvent.KEYCODE_DPAD_UP -> {
                true
              }
              else -> false
            }
          },
        shape = RoundedCornerShape(50),
        onClick = { skipIntroNow() }
      ) { isFocused ->
        Row(
          modifier = Modifier
            .shadow(
              elevation = if (isFocused) 16.dp else 6.dp,
              shape = RoundedCornerShape(50),
              spotColor = NovaCyanBright
            )
            .background(
              color = if (isFocused) NovaCyanBright else Color(0xCC0B111E),
              shape = RoundedCornerShape(50)
            )
            .border(
              width = if (isFocused) 2.dp else 1.5.dp,
              color = if (isFocused) Color.White else NovaCyan.copy(alpha = 0.8f),
              shape = RoundedCornerShape(50)
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Icon(
            imageVector = Icons.Default.FastForward,
            contentDescription = stringResource(R.string.player_cd_skip_intro),
            tint = if (isFocused) Color.Black else NovaCyanBright,
            modifier = Modifier.size(18.dp)
          )
          Text(
            text = stringResource(R.string.player_skip_intro),
            color = if (isFocused) Color.Black else Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 0.5.sp
          )
        }
      }
    }

    // Overlay di scrubbing: anteprima della posizione scelta con ◀ / ▶ mentre i controlli
    // sono nascosti. Visibile solo durante lo scrubbing, senza dissolvenza: la barra deve
    // comparire e sparire nello stesso frame della pressione, anche a pressioni ravvicinate.
    val scrubPreview = scrubState
    if (scrubPreview != null) {
      Box(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .zIndex(120f)
          .padding(bottom = 64.dp),
        contentAlignment = Alignment.Center
      ) {
        PlayerScrubOverlay(state = scrubPreview)
      }
    }

    // Overlay "Prossimo Episodio": card in basso a destra con countdown automatico
    // (opzione "Riproduci automaticamente il prossimo episodio" ON) oppure solo
    // pulsante manuale "Guarda ora" quando l'opzione è OFF. Esterno a
    // `if (areControlsVisible)`: si mostra anche (e soprattutto) con OSD nascosto.
    AnimatedVisibility(
      visible = isNextEpisodePromptVisible && nextEpisode != null,
      enter = fadeIn() + slideInHorizontally(initialOffsetX = { it }),
      exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it }),
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .zIndex(100f)
        .padding(end = 40.dp, bottom = 40.dp)
    ) {
      NextEpisodePromptCard(
        seasonNumber = nextEpisode?.seasonNumber,
        episodeNumber = nextEpisode?.episodeNumber,
        episodeTitle = nextEpisode?.title,
        countdownSeconds = if (playbackSettings.autoPlayNextEpisodeEnabled) {
          nextEpisodeCountdownSeconds
        } else {
          null
        },
        focusRequester = nextEpisodeFocusRequester,
        onWatchNow = {
          kickAutoHide()
          confirmNextEpisode()
        }
      )
    }

if (showTrackQuickPanel) {
      val audioOptions = getAvailableAudioOptions(currentTracksState)
      PlayerTrackQuickPanel(
        audioOptions = audioOptions.mapIndexed { index, option ->
          PlayerQuickTrackOption(
            index = index,
            title = option.displayName,
            details = "${option.codec} • ${option.channels}",
            selected = option.isSelected
          )
        },
        onAudioSelected = { index ->
          val selectedOption = audioOptions.getOrNull(index)
          if (selectedOption != null) {
            manualAudioSelection = ManualAudioSelection(selectedOption.group, selectedOption.trackIndex)
            PlayerRuntimeControllerTrackSelection.selectAudioTrack(exoPlayer, index)
            selectedOption.language.trim().lowercase().takeIf { it.isNotBlank() && it != "und" }
              ?.let(viewModel::setPreferredAudioLanguage)
          }
          showTrackQuickPanel = false
        },
        onDismiss = { showTrackQuickPanel = false }
      )
    }

    // Modal Sottotitoli
    if (showSubtitleModal) {
      val subtitleOptions = getAvailableSubtitleOptions(currentTracksState)
      val noneOption = stringResource(R.string.player_subtitles_none)
      val allOptions = listOf(noneOption) + subtitleOptions.map { it.displayName }
      val selectedIndex = if (manualSubtitleSelection?.isNone == true) {
        0
      } else if (subtitleOptions.any { it.isSelected }) {
        subtitleOptions.indexOfFirst { it.isSelected } + 1
      } else {
        0
      }
      PlayerSubtitleSelectionDialog(
        title = stringResource(R.string.player_subtitles_dialog_title),
        localOptions = allOptions,
        selectedLocalIndex = selectedIndex,
        onSelectLocal = { idx ->
          if (idx == 0) {
            manualSubtitleSelection = ManualSubtitleSelection(null, -1)
            PlayerRuntimeControllerTrackSelection.disableSubtitles(exoPlayer)
            Log.i("PlayerScreen", "Sottotitoli disabilitati manualmente (Nessuno)")
          } else {
            val selectedOption = subtitleOptions.getOrNull(idx - 1)
            if (selectedOption != null) {
              manualSubtitleSelection = ManualSubtitleSelection(selectedOption.group, selectedOption.trackIndex)
              PlayerRuntimeControllerTrackSelection.selectSubtitleTrack(exoPlayer, idx - 1)
              selectedOption.language.trim().lowercase().takeIf { it.isNotBlank() && it != "und" }
                ?.let(viewModel::setPreferredSubtitleLanguage)
              Log.i("PlayerScreen", "Selezione traccia sottotitolo Media3: ${selectedOption.displayName} (trackIndex=${selectedOption.trackIndex}, lang=${selectedOption.language})")
            }
          }
          showSubtitleModal = false
        },
        onlineSubtitles = onlineSubtitles,
        isOnlineLoading = isOnlineSubtitlesLoading,
        onSelectOnline = { sub ->
          val currentPos = exoPlayer.currentPosition
          val wasPlaying = exoPlayer.playWhenReady
          val config = PlayerSubtitleMediaItemBuilder.toConfiguration(sub)
          if (config != null) {
            viewModel.injectOnlineSubtitle(sub)
            val currentItem = exoPlayer.currentMediaItem
            if (currentItem != null) {
              val existing = currentItem.localConfiguration?.subtitleConfigurations.orEmpty()
              val updatedConfigs = (existing.filter { it.uri != config.uri && it.id != config.id } + config)
              val newItem = currentItem.buildUpon()
                .setSubtitleConfigurations(updatedConfigs)
                .build()

              exoPlayer.setMediaItem(newItem, /* resetPosition = */ false)
              if (currentPos > 0) {
                exoPlayer.seekTo(currentPos)
              }
              exoPlayer.prepare()
              exoPlayer.playWhenReady = wasPlaying
            }
            val normLang = PlayerSubtitleUtils.normalizeLanguageCode(sub.lang)
            if (normLang.isNotBlank() && normLang != "und") {
              viewModel.setPreferredSubtitleLanguage(normLang)
            }
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
              .buildUpon()
              .setPreferredTextLanguage(normLang)
              .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
              .build()
            manualSubtitleSelection = null
          }
          showSubtitleModal = false
        },
        offsetMs = playbackState.subtitleOffsetMs,
        onAdjustOffset = { delta -> viewModel.adjustSubtitleOffsetMs(delta) },
        onResetOffset = { viewModel.resetSubtitleOffsetMs() },
        headerSwitchLabel = stringResource(R.string.settings_forced_subtitles),
        headerSwitchChecked = playbackSettings.forcedSubtitlesEnabled,
        onHeaderSwitchToggle = { enabled ->
          viewModel.setForcedSubtitlesEnabled(enabled)
          manualSubtitleSelection = null
        },
        onDismiss = { showSubtitleModal = false }
      )
    }

    // Modal Velocità di Riproduzione
    if (showSpeedModal) {
      val speedOptions = listOf("0.75x", stringResource(R.string.player_speed_normal), "1.25x", "1.5x")
      val speedValues = listOf(0.75f, 1.0f, 1.25f, 1.5f)
      TrackSelectionDialog(
        title = stringResource(R.string.player_speed_dialog_title),
        options = speedOptions,
        selectedIndex = speedValues.indexOf(currentSpeed).coerceAtLeast(1),
        onSelect = { idx ->
          val speed = speedValues[idx]
          currentSpeed = speed
          exoPlayer.setPlaybackSpeed(speed)
          showSpeedModal = false
        },
        onDismiss = { showSpeedModal = false }
      )
    }

    // Modal Qualità Video (Risoluzione)
    if (showQualityModal) {
      val qualityOptions = getAvailableQualityOptions()
      TrackSelectionDialog(
        title = stringResource(R.string.player_quality_dialog_title),
        options = qualityOptions.map { it.first },
        selectedIndex = qualityOptions.indexOfFirst { it.first == selectedQualityLabel }.coerceAtLeast(0),
        onSelect = { idx ->
          val (label, targetHeight) = qualityOptions[idx]
          selectedQualityLabel = label
          if (targetHeight == 0) {
            // Auto: preferenza massima risoluzione e bitrate più alto senza vincoli fissi
            trackSelector.setParameters(
              trackSelector.buildUponParameters()
                .clearVideoSizeConstraints()
                .setViewportSize(Int.MAX_VALUE, Int.MAX_VALUE, false)
                .setForceHighestSupportedBitrate(true)
                .setExceedVideoConstraintsIfNecessary(true)
                .setExceedRendererCapabilitiesIfNecessary(true)
            )
          } else {
            // Forzatura vincoli risoluzione esatti per la traccia selezionata (es. 1920x1080 per 1080p)
            val targetWidth = when {
              targetHeight >= 1400 -> 3840
              targetHeight >= 750  -> 1920
              targetHeight >= 500  -> 1280
              targetHeight >= 400  -> 854
              else -> (targetHeight * 16) / 9
            }
            trackSelector.setParameters(
              trackSelector.buildUponParameters()
                .setMinVideoSize(targetWidth, targetHeight)
                .setMaxVideoSize(targetWidth, targetHeight)
                .setViewportSize(Int.MAX_VALUE, Int.MAX_VALUE, false)
                .setForceHighestSupportedBitrate(true)
                .setExceedVideoConstraintsIfNecessary(true)
                .setExceedRendererCapabilitiesIfNecessary(true)
            )
            detectedResolution = when {
              targetHeight >= 1400 -> "4K"
              targetHeight >= 750  -> "1080p"
              targetHeight >= 500  -> "720p"
              else                 -> "SD"
            }
          }
          showQualityModal = false
        },
        onDismiss = { showQualityModal = false }
      )
    }

    // Selettore delle sorgenti video (comando "Sorgente video" del player).
    // La scelta di una riga avvia la sorgente scelta dall'utente; la chiusura
    // (BACK o tap fuori) NON cambia nulla e ripristina il focus sul pulsante
    // di apertura.
    if (showSourceSelector) {
      PlayerSourceSelectorDialog(
        entries = sourceSelectorEntries,
        onSelect = { source ->
          closeSourceSelector()
          viewModel.selectSource(source)
        },
        onDismiss = { closeSourceSelector() }
      )
    }

    // Stats for Nerds / Debug Info Overlay (non focalizzabile da D-pad)
    if (playbackSettings.debugOverlayEnabled) {
      StatsForNerdsOverlay(
        player = exoPlayer,
        modifier = Modifier.align(Alignment.TopStart)
      )
    }
  }
}

/**
 * Overlay diagnostico avanzato (Stats for Nerds) con metriche in tempo reale:
 * Risoluzione, Bitrate, Codec Audio/Video, Buffer rimanente e Frame persi.
 */
@Composable
fun StatsForNerdsOverlay(
  player: ExoPlayer,
  modifier: Modifier = Modifier
) {
  var tick by remember { mutableIntStateOf(0) }
  LaunchedEffect(player) {
    while (true) {
      kotlinx.coroutines.delay(500)
      tick++
    }
  }

  val videoFormat = player.videoFormat
  val audioFormat = player.audioFormat
  val videoSize = player.videoSize
  val width = if ((videoFormat?.width ?: 0) > 0) videoFormat?.width else videoSize.width.takeIf { it > 0 }
  val height = if ((videoFormat?.height ?: 0) > 0) videoFormat?.height else videoSize.height.takeIf { it > 0 }
  val fps = videoFormat?.frameRate?.takeIf { it > 0f }
  val videoBitrate = videoFormat?.bitrate?.takeIf { it > 0 }
    ?: player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
      .flatMap { (0 until it.length).map { i -> it to i } }
      .firstOrNull { (g, i) -> g.isTrackSelected(i) }
      ?.let { (g, i) -> g.getTrackFormat(i).bitrate.takeIf { it > 0 } }
  val videoCodec = videoFormat?.sampleMimeType ?: "N/A"
  val videoCodecsDetail = videoFormat?.codecs ?: ""
  val audioCodec = audioFormat?.sampleMimeType ?: "N/A"
  val audioChannels = audioFormat?.channelCount ?: -1
  val audioSampleRate = audioFormat?.sampleRate ?: -1
  val droppedFrames = player.videoDecoderCounters?.droppedBufferCount ?: 0
  val bufferRemainingSec = ((player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)) / 1000f

  Box(
    modifier = modifier
      .padding(start = 28.dp, top = 28.dp)
      .clip(RoundedCornerShape(8.dp))
      .background(Color(0xE60D131F))
      .border(1.dp, NovaCyanBright.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
      .padding(horizontal = 14.dp, vertical = 10.dp)
  ) {
    Column(
      verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier
            .size(8.dp)
            .background(NovaCyanBright, CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = "STATS FOR NERDS",
          color = NovaCyanBright,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 0.8.sp
        )
      }
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = "Risoluzione: ${width ?: "N/A"}x${height ?: "N/A"} ${if (fps != null) "@ ${String.format(java.util.Locale.ROOT, "%.2f", fps)} fps" else ""}",
        color = Color.White,
        fontSize = 11.sp,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
      )
      Text(
        text = "Bitrate Video: ${if (videoBitrate != null && videoBitrate > 0) "${videoBitrate / 1000} kbps" else "N/A"}",
        color = Color(0xFFDDDDDD),
        fontSize = 11.sp,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
      )
      Text(
        text = "Codec Video: $videoCodec ${if (videoCodecsDetail.isNotBlank()) "($videoCodecsDetail)" else ""}",
        color = Color(0xFFB0B0B0),
        fontSize = 11.sp,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
      )
      Text(
        text = "Codec Audio: $audioCodec ${if (audioChannels > 0) "($audioChannels ch, ${audioSampleRate}Hz)" else ""}",
        color = Color(0xFFB0B0B0),
        fontSize = 11.sp,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
      )
      Text(
        text = "Buffer: ${String.format(java.util.Locale.ROOT, "%.1f", bufferRemainingSec)}s | Frame persi: $droppedFrames",
        color = if (droppedFrames > 0) Color(0xFFFFB74D) else NovaGreen,
        fontSize = 11.sp,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
      )
    }
  }
}

/**
 * Pulsante azione icona per la barra controlli inferiore TV con feedback focus NovaCyan
 */
@Composable
fun PlayerControlIconButton(
  icon: ImageVector,
  contentDescription: String,
  modifier: Modifier = Modifier,
  onClick: () -> Unit
) {
  TvFocusableBox(
    modifier = modifier,
    shape = CircleShape,
    focusedScale = 1.15f,
    focusedBorderColor = NovaCyanBright,
    borderWidth = 2.dp,
    onClick = onClick
  ) { isFocused ->
    Box(
      modifier = Modifier
        .size(44.dp)
        .background(
          if (isFocused) NovaCyan.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
          CircleShape
        ),
      contentAlignment = Alignment.Center
    ) {
      Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = if (isFocused) NovaCyanBright else Color.White,
        modifier = Modifier.size(24.dp)
      )
    }
  }
}

/**
 * Pulsante compatto "Sorgente video" per gli overlay del player (buffering ed
 * errore).
 *
 * A differenza di [PlayerControlIconButton] mostra anche l'etichetta e
 * risponde al TOUCH (mobile): e' quindi sia `focusable()` (D-pad su Android TV)
 * sia `clickable`.
 */
@Composable
private fun PlayerSourceOverlayButton(
  modifier: Modifier = Modifier,
  onClick: () -> Unit
) {
  var isFocused by remember { mutableStateOf(false) }
  Row(
    modifier = modifier
      .background(
        if (isFocused) NovaCyan else NovaSurfaceVariant,
        RoundedCornerShape(10.dp)
      )
      .border(
        1.dp,
        if (isFocused) NovaCyanBright else Color.Transparent,
        RoundedCornerShape(10.dp)
      )
      .onFocusChanged { isFocused = it.isFocused }
      .focusable()
      .clickable { onClick() }
      .padding(horizontal = 12.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(7.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      imageVector = Icons.Default.SwapHoriz,
      contentDescription = null,
      tint = if (isFocused) Color.Black else NovaCyanBright,
      modifier = Modifier.size(18.dp)
    )
    Text(
      text = stringResource(R.string.player_cd_video_source),
      color = if (isFocused) Color.Black else NovaTextPrimary,
      fontSize = 12.sp,
      fontWeight = FontWeight.Bold
    )
  }
}

@Composable
fun PlayerSubtitleSelectionDialog(
  title: String,
  localOptions: List<String>,
  selectedLocalIndex: Int,
  onSelectLocal: (Int) -> Unit,
  onlineSubtitles: List<com.example.domain.model.Subtitle>,
  isOnlineLoading: Boolean,
  onSelectOnline: (com.example.domain.model.Subtitle) -> Unit,
  offsetMs: Long,
  onAdjustOffset: (Long) -> Unit,
  onResetOffset: () -> Unit,
  headerSwitchLabel: String? = null,
  headerSwitchChecked: Boolean = false,
  onHeaderSwitchToggle: ((Boolean) -> Unit)? = null,
  onDismiss: () -> Unit
) {
  val initialFocusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) {
    try {
      initialFocusRequester.requestFocus()
    } catch (_: Exception) {
    }
  }

  val formattedOffset = remember(offsetMs) {
    when {
      offsetMs > 0 -> "+$offsetMs ms"
      offsetMs < 0 -> "$offsetMs ms"
      else -> "0 ms"
    }
  }

  androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .width(440.dp)
        .background(NovaSurface, RoundedCornerShape(20.dp))
        .border(1.5.dp, NovaCyan, RoundedCornerShape(20.dp))
        .padding(22.dp)
    ) {
      // 1. Titolo e pulsante chiusura
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Icon(
            imageVector = Icons.Default.ClosedCaption,
            contentDescription = null,
            tint = NovaCyanBright,
            modifier = Modifier.size(22.dp)
          )
          Text(
            text = title,
            color = NovaTextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
          )
        }
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = stringResource(R.string.action_close),
          tint = NovaTextSecondary,
          modifier = Modifier
            .size(22.dp)
            .clickable { onDismiss() }
        )
      }

      // 2. Interruttore Sottotitoli Forzati
      if (headerSwitchLabel != null && onHeaderSwitchToggle != null) {
        Spacer(modifier = Modifier.height(12.dp))
        TvFocusableBox(
          shape = RoundedCornerShape(12.dp),
          onClick = { onHeaderSwitchToggle(!headerSwitchChecked) },
          modifier = Modifier
            .fillMaxWidth()
            .focusRequester(initialFocusRequester)
        ) { isFocused ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .background(trackHeaderRowFillColor(isFocused), RoundedCornerShape(12.dp))
              .border(
                width = 1.dp,
                color = trackHeaderRowBorderColor(isFocused),
                shape = RoundedCornerShape(12.dp)
              )
              .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = headerSwitchLabel,
              color = if (isFocused) NovaCyanBright else NovaTextPrimary,
              fontSize = 13.sp,
              fontWeight = FontWeight.SemiBold
            )
            Text(
              text = if (headerSwitchChecked) stringResource(R.string.settings_value_on) else stringResource(R.string.settings_value_off),
              color = if (headerSwitchChecked) NovaGreen else NovaTextMuted,
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      // 3. Controller Sincronizzazione Offset (+/- 250 ms)
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(NovaSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
          .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
          .padding(12.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Sync,
              contentDescription = null,
              tint = NovaCyan,
              modifier = Modifier.size(16.dp)
            )
            Text(
              text = stringResource(R.string.player_subtitles_sync_title),
              color = NovaTextSecondary,
              fontSize = 12.sp,
              fontWeight = FontWeight.SemiBold
            )
          }
          // Azione rapida "Ripristina (0 ms)"
          TvFocusableBox(
            shape = RoundedCornerShape(8.dp),
            focusedScale = 1.05f,
            onClick = onResetOffset
          ) { isFocused ->
            Text(
              text = stringResource(R.string.player_subtitles_reset_offset),
              color = if (isFocused) NovaCyanBright else if (offsetMs != 0L) NovaCyan else NovaTextMuted,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              modifier = Modifier
                .background(
                  if (isFocused) NovaCyan.copy(alpha = 0.2f) else Color.Transparent,
                  RoundedCornerShape(8.dp)
                )
                .border(
                  width = 1.dp,
                  color = if (isFocused) NovaCyanBright else Color.Transparent,
                  shape = RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 8.dp, vertical = 4.dp)
            )
          }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Bottone -250 ms
          TvFocusableBox(
            shape = RoundedCornerShape(10.dp),
            focusedScale = 1.06f,
            onClick = { onAdjustOffset(-250L) },
            modifier = Modifier.weight(1f)
          ) { isFocused ->
            Box(
              contentAlignment = Alignment.Center,
              modifier = Modifier
                .fillMaxWidth()
                .background(
                  if (isFocused) NovaCyan.copy(alpha = 0.25f) else NovaSurface,
                  RoundedCornerShape(10.dp)
                )
                .border(
                  width = 1.dp,
                  color = if (isFocused) NovaCyanBright else Color.White.copy(alpha = 0.15f),
                  shape = RoundedCornerShape(10.dp)
                )
                .padding(vertical = 8.dp)
            ) {
              Text(
                text = "-250 ms",
                color = if (isFocused) NovaCyanBright else NovaTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }

          // Valore corrente formattato
          Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
              .weight(1.2f)
              .padding(horizontal = 8.dp)
          ) {
            Text(
              text = formattedOffset,
              color = if (offsetMs != 0L) NovaCyanBright else NovaTextSecondary,
              fontSize = 16.sp,
              fontWeight = FontWeight.ExtraBold
            )
          }

          // Bottone +250 ms
          TvFocusableBox(
            shape = RoundedCornerShape(10.dp),
            focusedScale = 1.06f,
            onClick = { onAdjustOffset(250L) },
            modifier = Modifier.weight(1f)
          ) { isFocused ->
            Box(
              contentAlignment = Alignment.Center,
              modifier = Modifier
                .fillMaxWidth()
                .background(
                  if (isFocused) NovaCyan.copy(alpha = 0.25f) else NovaSurface,
                  RoundedCornerShape(10.dp)
                )
                .border(
                  width = 1.dp,
                  color = if (isFocused) NovaCyanBright else Color.White.copy(alpha = 0.15f),
                  shape = RoundedCornerShape(10.dp)
                )
                .padding(vertical = 8.dp)
            ) {
              Text(
                text = "+250 ms",
                color = if (isFocused) NovaCyanBright else NovaTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      // 4. Sezione Tracce Scrollabili
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 340.dp)
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        // Header Tracce Integrate
        Text(
          text = stringResource(R.string.player_subtitles_local_section),
          color = NovaTextMuted,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp)
        )

        localOptions.forEachIndexed { index, option ->
          val isSelected = index == selectedLocalIndex
          TvFocusableBox(
            shape = RoundedCornerShape(10.dp),
            focusedScale = 1.02f,
            onClick = { onSelectLocal(index) },
            modifier = Modifier.fillMaxWidth()
          ) { isFocused ->
            val rowAppearance = trackOptionRowAppearance(isSelected, isFocused)
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .background(rowAppearance.background, RoundedCornerShape(10.dp))
                .border(
                  width = 1.dp,
                  color = rowAppearance.borderColor,
                  shape = RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 14.dp, vertical = 9.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = option,
                color = if (isSelected || isFocused) NovaCyanBright else NovaTextPrimary,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
              )
              if (isSelected) {
                Icon(
                  imageVector = Icons.Default.Check,
                  contentDescription = stringResource(R.string.player_cd_selected),
                  tint = NovaCyanBright,
                  modifier = Modifier.size(16.dp)
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.08f))
        )
        Spacer(modifier = Modifier.height(4.dp))

        // Header Sottotitoli Online (OpenSubtitles v3)
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = stringResource(R.string.player_subtitles_online_section),
            color = NovaTextMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
          )
          if (isOnlineLoading) {
            CircularProgressIndicator(
              modifier = Modifier.size(14.dp),
              color = NovaCyanBright,
              strokeWidth = 2.dp
            )
          }
        }

        if (isOnlineLoading && onlineSubtitles.isEmpty()) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = stringResource(R.string.player_subtitles_online_loading),
              color = NovaTextMuted,
              fontSize = 12.sp
            )
          }
        } else if (!isOnlineLoading && onlineSubtitles.isEmpty()) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = stringResource(R.string.player_subtitles_online_empty),
              color = NovaTextMuted,
              fontSize = 12.sp
            )
          }
        } else {
          onlineSubtitles.forEach { sub ->
            val langDisplay = sub.getDisplayLanguage()
            val formatTag = if (sub.url.contains(".vtt", ignoreCase = true)) "VTT" else "SRT"
            val subLabel = sub.label?.takeIf { it.isNotBlank() } ?: "OpenSubtitles"

            TvFocusableBox(
              shape = RoundedCornerShape(10.dp),
              focusedScale = 1.02f,
              onClick = { onSelectOnline(sub) },
              modifier = Modifier.fillMaxWidth()
            ) { isFocused ->
              val rowAppearance = trackOptionRowAppearance(isSelected = false, isFocused = isFocused)
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(rowAppearance.background, RoundedCornerShape(10.dp))
                  .border(
                    width = 1.dp,
                    color = rowAppearance.borderColor,
                    shape = RoundedCornerShape(10.dp)
                  )
                  .padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Column(
                  modifier = Modifier.weight(1f, fill = false),
                  verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                  ) {
                    Text(
                      text = langDisplay,
                      color = if (isFocused) NovaCyanBright else NovaTextPrimary,
                      fontSize = 13.sp,
                      fontWeight = FontWeight.Bold
                    )
                    Text(
                      text = "[$formatTag]",
                      color = NovaCyan,
                      fontSize = 10.sp,
                      fontWeight = FontWeight.SemiBold
                    )
                  }
                  Text(
                    text = subLabel,
                    color = NovaTextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1
                  )
                }
                Icon(
                  imageVector = Icons.Default.CloudDownload,
                  contentDescription = null,
                  tint = if (isFocused) NovaCyanBright else NovaTextMuted,
                  modifier = Modifier.size(16.dp)
                )
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Indice dell'opzione che riceve il focus all'apertura del dialog di selezione.
 *
 * Il focus deve posarsi sulla voce **già attiva** (`selectedIndex`), non sulla prima della
 * lista: con il telecomando un OK immediato confermava la prima voce (es. "Debrid TorBox")
 * anche quando la spunta era su un'altra, cambiando l'impostazione per sbaglio.
 * Un indice fuori range o una lista vuota ricadono su 0, così la richiesta di focus resta
 * sempre valida.
 */
internal fun trackDialogInitialFocusIndex(options: List<String>, selectedIndex: Int): Int =
  if (options.isEmpty()) 0 else selectedIndex.coerceIn(0, options.lastIndex)

/**
 * Aspetto (sfondo + bordo interno) di una riga opzione nei dialog delle tracce.
 *
 * Regressione della "banda blu scura interna al focus": le righe disegnavano DUE strati
 * sovrapposti — il ring di focus 2.5dp di `TvFocusableBox` (componente condiviso, resta
 * l'indicazione di focus primaria) e, dentro, un proprio sfondo [NovaSurfaceVariant]
 * (navy scuro) più un secondo bordo ciano. Il risultato era un anello scuro tra i due
 * bordi che sporca la voce evidenziata.
 *
 * Da qui la regola testabile:
 * - a fuoco → fill ciano CHIARO (convenzione dell'app, cf. `TvContextMenu`) e NESSUN
 *   bordo interno trasparente: l'anello esterno resta l'unica evidenza di focus;
 * - seleziona e non a fuoco → fill ciano 0.2 + bordo ciano (iniziale "chip" attivo);
 * - seleziona e a fuoco → fill ciano 0.2, bordo interno spento (niente doppio ring);
 * - neutra → trasparente.
 */
internal data class TrackOptionRowAppearance(
  val background: Color,
  val borderColor: Color
)

internal fun trackOptionRowAppearance(isSelected: Boolean, isFocused: Boolean): TrackOptionRowAppearance =
  when {
    isSelected && isFocused -> TrackOptionRowAppearance(
      background = NovaCyan.copy(alpha = 0.2f),
      borderColor = Color.Transparent
    )
    isSelected -> TrackOptionRowAppearance(
      background = NovaCyan.copy(alpha = 0.2f),
      borderColor = NovaCyan
    )
    isFocused -> TrackOptionRowAppearance(
      background = NovaCyan.copy(alpha = 0.15f),
      borderColor = Color.Transparent
    )
    else -> TrackOptionRowAppearance(
      background = Color.Transparent,
      borderColor = Color.Transparent
    )
  }

/** Colore del container di una riga toggle di testa (es. "Sottotitoli Forzati") a fuoco. */
internal fun trackHeaderRowFillColor(isFocused: Boolean): Color =
  if (isFocused) NovaCyan.copy(alpha = 0.15f) else NovaSurface.copy(alpha = 0.6f)

/** Bordo interno del container di testa: a fuoco è SPENTO per non duplicare il ring. */
internal fun trackHeaderRowBorderColor(isFocused: Boolean): Color =
  if (isFocused) Color.Transparent else Color.White.copy(alpha = 0.12f)

@Composable
fun TrackSelectionDialog(
  title: String,
  options: List<String>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  onDismiss: () -> Unit,
  headerSwitchLabel: String? = null,
  headerSwitchChecked: Boolean = false,
  onHeaderSwitchToggle: ((Boolean) -> Unit)? = null
) {
  // Focus iniziale esplicito: senza questa richiesta il telecomando (D-Pad) non avrebbe
  // alcun elemento focale all'apertura del dialog. Il bersaglio è il toggle di testa quando
  // è presente, altrimenti la voce già attiva.
  val initialFocusIndex = trackDialogInitialFocusIndex(options, selectedIndex)
  val initialFocusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) {
    try {
      initialFocusRequester.requestFocus()
    } catch (_: Exception) {
      // Layout non ancora attivo: il focus resterà gestito dalla navigazione standard.
    }
  }

  Dialog(onDismissRequest = onDismiss) {
    Column(
      modifier = Modifier
        .width(380.dp)
        .background(NovaSurface, RoundedCornerShape(20.dp))
        .border(1.5.dp, NovaCyan, RoundedCornerShape(20.dp))
        .padding(24.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = title,
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

      if (headerSwitchLabel != null && onHeaderSwitchToggle != null) {
        Spacer(modifier = Modifier.height(14.dp))
        TvFocusableBox(
          shape = RoundedCornerShape(12.dp),
          onClick = { onHeaderSwitchToggle(!headerSwitchChecked) },
          modifier = Modifier
            .fillMaxWidth()
            .focusRequester(initialFocusRequester)
        ) { isFocused ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .background(trackHeaderRowFillColor(isFocused), RoundedCornerShape(12.dp))
              .border(
                width = 1.dp,
                color = trackHeaderRowBorderColor(isFocused),
                shape = RoundedCornerShape(12.dp)
              )
              .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.weight(1f, fill = false)
            ) {
              Icon(
                imageVector = Icons.Default.ClosedCaption,
                contentDescription = null,
                tint = if (headerSwitchChecked) NovaCyanBright else NovaTextSecondary,
                modifier = Modifier.size(18.dp)
              )
              Text(
                text = headerSwitchLabel,
                color = if (isFocused) NovaCyanBright else NovaTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
              )
            }
            Text(
              text = if (headerSwitchChecked) stringResource(R.string.settings_value_on) else stringResource(R.string.settings_value_off),
              color = if (headerSwitchChecked) NovaGreen else NovaTextMuted,
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.08f))
        )
        Spacer(modifier = Modifier.height(10.dp))
      } else {
        Spacer(modifier = Modifier.height(16.dp))
      }

      // Colonna scrollabile: con molte tracce (es. Stremio + OpenSubtitles) le
      // opzioni devono restare raggiungibili col D-Pad anche fuori viewport.
      Column(
        modifier = Modifier
          .heightIn(max = 360.dp)
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        options.forEachIndexed { index, option ->
          val isSelected = index == selectedIndex
          TvFocusableBox(
            shape = RoundedCornerShape(50),
            onClick = { onSelect(index) },
            modifier = Modifier
              .fillMaxWidth()
              .then(
                // Senza header switch la voce già attiva riceve il focus iniziale.
                if (index == initialFocusIndex && headerSwitchLabel == null) {
                  Modifier.focusRequester(initialFocusRequester)
                } else {
                  Modifier
                }
              )
          ) { isFocused ->
            val rowAppearance = trackOptionRowAppearance(isSelected, isFocused)
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .background(rowAppearance.background, RoundedCornerShape(50))
                .border(
                  width = 1.dp,
                  color = rowAppearance.borderColor,
                  shape = RoundedCornerShape(50)
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = option,
                color = if (isSelected || isFocused) NovaCyanBright else NovaTextPrimary,
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
              )
              if (isSelected) {
                Icon(
                  imageVector = Icons.Default.Check,
                  contentDescription = stringResource(R.string.player_cd_selected),
                  tint = NovaCyanBright,
                  modifier = Modifier.size(18.dp)
                )
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Overlay di anteprima dello scrubbing: mostra dove porta la posizione scelta con ◀ / ▶ e
 * di quanto si è spostata rispetto al punto di partenza. Nessun pulsante e nessun focus:
 * gli unici comandi sono OK (conferma) e BACK (annulla), quindi i controlli restano
 * fuori schermo per tutta la durata dello scrubbing.
 */
@Composable
private fun PlayerScrubOverlay(state: PlayerScrubUiState) {
  val fraction = if (state.durationMs > 0L) {
    (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
  } else 0f

  Column(
    modifier = Modifier
      .widthIn(min = 420.dp, max = 760.dp)
      .shadow(elevation = 18.dp, shape = RoundedCornerShape(18.dp), spotColor = NovaCyanBright)
      .background(color = Color(0xE60B111E), shape = RoundedCornerShape(18.dp))
      .border(width = 1.5.dp, color = NovaCyan.copy(alpha = 0.85f), shape = RoundedCornerShape(18.dp))
      .padding(horizontal = 26.dp, vertical = 20.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Icon(
          imageVector = if (state.deltaMs >= 0L) Icons.Default.FastForward else Icons.Default.FastRewind,
          contentDescription = null,
          tint = NovaCyanBright,
          modifier = Modifier.size(22.dp)
        )
        Text(
          text = formatScrubDelta(state.deltaMs),
          color = NovaCyanBright,
          fontSize = 20.sp,
          fontWeight = FontWeight.Bold
        )
      }
      Text(
        text = formatTimePair(state.positionMs, state.durationMs),
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold
      )
    }

    // Barra di anteprima: stessa forma della timeline dell'HUD, ma senza focus e senza
    // pulsanti (nessun elemento dello scrubbing è raggiungibile dal D-pad).
    BoxWithConstraints(
      modifier = Modifier.fillMaxWidth(),
      contentAlignment = Alignment.CenterStart
    ) {
      val widthPx = maxWidth
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(8.dp)
          .clip(RoundedCornerShape(4.dp))
          .background(Color.White.copy(alpha = 0.22f))
      ) {
        Box(
          modifier = Modifier
            .fillMaxWidth(fraction)
            .fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(NovaCyan, NovaCyanBright)))
        )
      }
      Box(
        modifier = Modifier
          .padding(start = ((widthPx - 16.dp) * fraction).coerceAtLeast(0.dp))
          .size(16.dp)
          .shadow(elevation = 10.dp, shape = CircleShape, spotColor = NovaCyanBright)
          .background(NovaCyanBright, CircleShape)
      )
    }

    Text(
      text = stringResource(R.string.player_scrub_hint),
      color = NovaTextSecondary,
      fontSize = 12.sp
    )
  }
}

/**
 * Delta di anteprima formattato con segno rispetto al punto di partenza:
 * "+00:30", "-10:00", "+01:05:00" (con le ore solo quando servono).
 */
private fun formatScrubDelta(deltaMs: Long): String {
  val sign = if (deltaMs < 0L) "-" else "+"
  val magnitudeMs = if (deltaMs < 0L) -deltaMs else deltaMs
  val sec = magnitudeMs / 1000
  val h = sec / 3600
  val m = (sec % 3600) / 60
  val s = sec % 60
  return if (h > 0) {
    "%s%02d:%02d:%02d".format(Locale.US, sign, h, m, s)
  } else {
    "%s%02d:%02d".format(Locale.US, sign, m, s)
  }
}

/**
 * Formatta la coppia tempo corrente / durata totale
 * Es: "00:02 / 41:54" oppure "01:05:20 / 02:15:30"
 */
private fun formatTimePair(currentMs: Long, totalMs: Long): String {
  val curSec = (currentMs / 1000).coerceAtLeast(0)
  val totSec = (totalMs / 1000).coerceAtLeast(0)
  val hasHours = totSec >= 3600
  fun fmt(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (hasHours) {
      "%02d:%02d:%02d".format(h, m, s)
    } else {
      "%02d:%02d".format(m, s)
    }
  }
  return "${fmt(curSec)} / ${fmt(totSec)}"
}

data class MediaAudioTrackOption(
  val group: Tracks.Group,
  val trackIndex: Int,
  val language: String,
  val label: String,
  val roleFlags: Int,
  val codec: String,
  val channels: String,
  val displayName: String,
  val isSelected: Boolean
)

data class MediaSubtitleTrackOption(
  val group: Tracks.Group,
  val trackIndex: Int,
  val language: String,
  val label: String,
  val roleFlags: Int,
  val selectionFlags: Int = 0,
  val isForced: Boolean = false,
  val codec: String,
  val displayName: String,
  val isSelected: Boolean
)

data class ManualSubtitleSelection(
  val group: Tracks.Group?,
  val trackIndex: Int
) {
  val isNone: Boolean get() = group == null || trackIndex < 0
}

data class ManualAudioSelection(
  val group: Tracks.Group,
  val trackIndex: Int
)

private fun mapPreferredAudioLanguages(code: String): List<String> {
  val normalized = code.trim().lowercase()
  return when (normalized) {
    "it", "ita" -> listOf("it", "ita")
    "en", "eng" -> listOf("en", "eng")
    "es", "spa" -> listOf("es", "spa")
    "fr", "fra", "fre" -> listOf("fr", "fra", "fre")
    "de", "deu", "ger" -> listOf("de", "deu", "ger")
    "", "und" -> emptyList()
    else -> listOf(normalized, normalized.substringBefore('-')).distinct()
  }
}

/**
 * Card dell'overlay "Prossimo Episodio": numero/stagione (es. "S02E03"), titolo
 * dell'episodio successivo, countdown decrescente (visibile con auto-play ON) e
 * pulsante "Guarda ora" focalizzabile da D-Pad.
 *
 * Ancoraggio in basso a destra, sfondo scuro semitrasparente e bordo/glow ciano
 * coerenti con il tema Nova (stesso linguaggio visivo di "Salta Intro"). Quando
 * la card compare il pulsante "Guarda ora" richiede subito il focus D-Pad, cosi'
 * OK/ENTER lo attiva immediatamente senza attendere lo scadere del timer.
 */
@Composable
private fun NextEpisodePromptCard(
  seasonNumber: Int?,
  episodeNumber: Int?,
  episodeTitle: String?,
  countdownSeconds: Int?,
  focusRequester: FocusRequester,
  onWatchNow: () -> Unit
) {
  // Focus D-Pad immediato sul pulsante d'azione al primo frame disponibile
  // (riprova finche' la card non e' agganciata al sistema di focus).
  LaunchedEffect(Unit) {
    focusRequester.requestFocusAfterFrame()
  }

  val epLabel = if (seasonNumber != null && episodeNumber != null) {
    "S${seasonNumber.toString().padStart(2, '0')}E${episodeNumber.toString().padStart(2, '0')}"
  } else {
    null
  }
  Column(
    modifier = Modifier
      .widthIn(max = 340.dp)
      .shadow(
        elevation = 12.dp,
        shape = RoundedCornerShape(16.dp),
        spotColor = NovaCyanBright
      )
      .background(
        color = Color(0xF20B111E),
        shape = RoundedCornerShape(16.dp)
      )
      .border(
        width = 1.5.dp,
        color = NovaCyan.copy(alpha = 0.85f),
        shape = RoundedCornerShape(16.dp)
      )
      .padding(horizontal = 20.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Icon(
        imageVector = Icons.Default.SkipNext,
        contentDescription = stringResource(R.string.player_cd_next_episode),
        tint = NovaCyanBright,
        modifier = Modifier.size(18.dp)
      )
      Text(
        text = stringResource(R.string.player_next_episode_title),
        color = NovaCyanBright,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        letterSpacing = 1.sp
      )
    }
    if (epLabel != null) {
      Text(
        text = epLabel,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp
      )
    }
    if (!episodeTitle.isNullOrBlank()) {
      Text(
        text = episodeTitle,
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 14.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
    }
    if (countdownSeconds != null && countdownSeconds > 0) {
      Text(
        text = stringResource(R.string.player_next_episode_countdown, countdownSeconds),
        color = NovaTextSecondary,
        fontSize = 12.sp
      )
    }
    TvFocusableBox(
      modifier = Modifier.focusRequester(focusRequester),
      shape = RoundedCornerShape(50),
      onClick = onWatchNow
    ) { isFocused ->
      Row(
        modifier = Modifier
          .shadow(
            elevation = if (isFocused) 16.dp else 4.dp,
            shape = RoundedCornerShape(50),
            spotColor = NovaCyanBright
          )
          .background(
            color = if (isFocused) NovaCyanBright else Color(0xCC0B111E),
            shape = RoundedCornerShape(50)
          )
          .border(
            width = if (isFocused) 2.dp else 1.5.dp,
            color = if (isFocused) Color.White else NovaCyan.copy(alpha = 0.8f),
            shape = RoundedCornerShape(50)
          )
          .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Icon(
          imageVector = Icons.Default.PlayArrow,
          contentDescription = null,
          tint = if (isFocused) Color.Black else NovaCyanBright,
          modifier = Modifier.size(18.dp)
        )
        Text(
          text = stringResource(R.string.player_next_episode_watch_now),
          color = if (isFocused) Color.Black else Color.White,
          fontWeight = FontWeight.Bold,
          fontSize = 13.sp,
          letterSpacing = 0.5.sp
        )
      }
    }
  }
}
