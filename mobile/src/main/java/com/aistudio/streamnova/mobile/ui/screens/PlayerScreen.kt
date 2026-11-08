package com.aistudio.streamnova.mobile.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.aistudio.streamnova.mobile.BuildConfig
import com.aistudio.streamnova.mobile.R
import com.aistudio.streamnova.mobile.ui.MobileOrientationController
import com.aistudio.streamnova.mobile.ui.MobilePlaybackErrorPolicy
import com.aistudio.streamnova.mobile.ui.MobilePlaybackSourcePolicy
import com.aistudio.streamnova.mobile.ui.MobilePlayerControlsPolicy
import com.aistudio.streamnova.mobile.ui.MobilePlayerPanel
import com.aistudio.streamnova.mobile.ui.MobilePlayerPreferences
import com.aistudio.streamnova.mobile.ui.MobilePlayerUiState
import com.aistudio.streamnova.mobile.ui.MobileSourcePresentation
import com.aistudio.streamnova.mobile.ui.MobilePlayerViewModel
import com.aistudio.streamnova.mobile.ui.MobileSettingsPolicy
import com.aistudio.streamnova.mobile.ui.MobileVideoScale
import com.aistudio.streamnova.mobile.ui.next
import com.aistudio.streamnova.mobile.ui.player.MobileAudioTrack
import com.aistudio.streamnova.mobile.ui.player.MobilePlayerActions
import com.aistudio.streamnova.mobile.ui.player.MobilePlayerControlsData
import com.aistudio.streamnova.mobile.ui.player.MobilePlayerOverlay
import com.aistudio.streamnova.mobile.ui.player.MobileSubtitleTrack
import com.aistudio.streamnova.mobile.ui.playerSeekTarget
import com.aistudio.streamnova.mobile.ui.shouldAutoHidePlayerControls
import com.aistudio.streamnova.mobile.ui.shouldAutoPlayNextEpisode
import com.aistudio.streamnova.mobile.ui.shouldKeepPlayerScreenOn
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.WatchProgress
import com.example.data.prefs.BackBufferOption
import com.example.data.prefs.DecoderFallbackMode
import com.example.data.prefs.InitialBufferOption
import com.example.data.prefs.PlaybackSettings
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.SettingsRepository
import com.example.data.prefs.SubtitleBackground
import com.example.data.prefs.TargetBufferOption
import com.example.data.repository.MediaRepository
import com.example.data.streaming.CacheState
import com.example.data.streaming.StreamSource
import com.example.ui.screens.player.CustomDefaultTrackNameProvider
import com.example.ui.screens.player.NuvioRenderersFactory
import com.example.data.prefs.StreamingEngineMode
import com.example.ui.screens.player.DebridLoadingScreen
import com.example.ui.screens.player.DebridLoadingOverlay
import com.example.ui.screens.player.NuvioTrackSelector
import com.example.ui.screens.player.PlayerHttpDataSource
import com.example.ui.screens.player.PlayerLoadErrorHandlingPolicy
import com.example.ui.screens.player.PlayerRuntimeControllerTrackSelection
import com.example.ui.screens.player.PlayerSourceFallback
import com.example.ui.screens.player.PlayerSubtitleMediaItemBuilder
import com.example.ui.screens.player.buildPlayerLoadControl
import com.example.ui.screens.player.resolvePlaybackMediaSourceFactory
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaDivider
import com.example.ui.theme.NovaGreen
import com.example.ui.theme.NovaOrange
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Player mobile: rendering Media3 con controlli Compose proprietari e motore
 * condiviso (`:core:player`) per factory HLS/progressiva, policy errori, renderer
 * e buffer.
 *
 * Orientamento: la schermata delega a [orientationController] (policy Fase 8.1).
 * Telefono: landscape durante la riproduzione, portrait in loading/errore/picker
 * e all'uscita. Tablet: landscape sempre, anche negli stati intermedi e al Back.
 *
 * UI: l'intero overlay (titolo, Back, controlli, seek bar, pulsanti) si nasconde
 * dopo un periodo di inattività mentre il video continua. Un tap lo riporta in
 * vista; Back del sistema resta sempre attivo.
 *
 * [manualSelection] = true quando la schermata è stata aperta dal pulsante
 * «Seleziona fonte» della scheda Dettaglio: il selettore delle sorgenti viene
 * mostrato comunque (anche con Autoplay attivo) e nessuna riproduzione parte
 * senza la conferma dell'utente.
 */
@Composable
@UnstableApi
fun MobilePlayerScreen(
  mediaId: String,
  season: Int?,
  episode: Int?,
  autoStart: Boolean = false,
  manualSelection: Boolean = false,
  orientationController: MobileOrientationController,
  onBack: () -> Unit,
  onPlayNextEpisode: (season: Int, episode: Int) -> Unit
) {
  BackHandler(onBack = onBack)
  val viewModel: MobilePlayerViewModel = viewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  val settings by SettingsRepository.settings.collectAsStateWithLifecycle()
  val context = LocalContext.current
  // Il LocalContext della composizione è il Context localizzato creato da
  // MainActivity: non incapsula l'Activity. La vista Compose invece nasce con
  // il Context dell'Activity, da cui si risale per orientamento e system bars.
  val activity = LocalView.current.context.findActivity() ?: context.findActivity()
  val scope = rememberCoroutineScope()

  LaunchedEffect(mediaId, season, episode, autoStart, manualSelection) {
    viewModel.prepare(mediaId, season, episode, autoStart, manualSelection)
  }

  // Sessione di riproduzione: landscape + sistema in modalità immersiva SOLO
  // quando una sorgente è effettivamente in riproduzione. La policy del device
  // decide l'orientamento anche negli stati intermedi (loading, errore, scelta
  // sorgente) e all'uscita: tablet sempre landscape, telefono portrait.
  val playbackActive = state.source != null ||
    // Avanzamento automatico: il nuovo episodio è già stato scelto dall'utente,
    // quindi il telefono resta in landscape anche durante il breve loading e non
    // ruota a portrait tra un episodio e il successivo. Gli avvii manuali e gli
    // stati di errore mantengono la policy della Fase 8.1.
    (autoStart && state.loading)
  LaunchedEffect(orientationController, activity, playbackActive) {
    orientationController.setPlayerScreen(visible = true, isPlaybackActive = playbackActive)
  }
  DisposableEffect(orientationController, activity) {
    onDispose {
      activity?.window?.let { window ->
        WindowCompat.getInsetsController(window, window.decorView)
          .show(WindowInsetsCompat.Type.systemBars())
      }
      // Uscita dal player: il controller riapplica la policy del device
      // (telefono portrait, tablet landscape, multi-window unspecified).
      orientationController.clearPlayerScreen()
    }
  }

  // Guardia contro i molteplici eventi STATE_ENDED dello stesso episodio: il
  // primo evento consuma l'avanzamento automatico, i successivi vengono ignorati.
  var nextEpisodeHandled by remember(
    state.media?.id,
    state.episode?.seasonNumber,
    state.episode?.episodeNumber
  ) { mutableStateOf(false) }

  Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
    when {
      state.loading -> {
        PlayerStatusScaffold(title = state.media?.title.orEmpty(), onBack = onBack) {
          val engineMode by AppSettingsRepository.streamingEngineMode.collectAsStateWithLifecycle()
          val loadingMedia = state.media
          // Percorso Debrid: schermata cinematografica (poster a tutto schermo + logo
          // pulsante), stessa esperienza della TV 1.0.5. Selezione manuale o percorso
          // HTTP: indicatore compatto come prima.
          val debridStartup = !manualSelection &&
            engineMode == StreamingEngineMode.DEBRID_TORBOX &&
            loadingMedia != null
          if (debridStartup && loadingMedia != null) {
            val artwork = remember(
              loadingMedia.id,
              loadingMedia.logoUrl,
              loadingMedia.backdropUrl,
              loadingMedia.posterUrl,
              loadingMedia.originalTitle,
              loadingMedia.title
            ) { DebridLoadingScreen.artworkOf(loadingMedia) }
            DebridLoadingOverlay(
              backgroundUrl = artwork.backgroundUrl,
              logoUrl = artwork.logoUrl,
              fallbackTitle = artwork.fallbackTitle,
              statusText = stringResource(
                if (state.source == null) R.string.player_sources_searching else R.string.player_loading
              )
            )
          } else {
            Column(
              modifier = Modifier.fillMaxSize(),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.Center
            ) {
              CircularProgressIndicator(color = NovaCyan)
              Spacer(modifier = Modifier.height(12.dp))
              Text(
                text = stringResource(
                  if (manualSelection) R.string.player_sources_searching else R.string.player_loading
                ),
                color = NovaTextMuted,
                fontSize = 14.sp
              )
            }
          }
        }
      }

      state.awaitingSourceSelection -> {
        MobileSourcePicker(
          sources = state.sources,
          media = state.media,
          episode = state.episode,
          error = state.error,
          onSelect = viewModel::selectSource,
          onClose = {
            // Chiusura senza avviare la riproduzione: il selettore viene
            // svuotato e l'utente torna alla scheda Dettaglio.
            viewModel.dismissSourceSelection()
            onBack()
          },
          onRetry = { viewModel.prepare(mediaId, season, episode, autoStart, manualSelection) }
        )
      }

      state.error != null -> {
        PlayerStatusScaffold(title = state.media?.title.orEmpty(), onBack = onBack) {
          Column(
            modifier = Modifier
                  .fillMaxSize()
                  .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
          ) {
            Text(
              text = stringResource(R.string.player_error_title),
              color = NovaTextPrimary,
              fontSize = 20.sp,
              fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = stringResource(R.string.player_error_body),
              color = NovaTextMuted,
              fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
              onClick = { viewModel.prepare(mediaId, season, episode, autoStart, manualSelection) },
              colors = ButtonDefaults.buttonColors(
                containerColor = NovaCyan,
                contentColor = Color.Black
              )
            ) {
              Text(stringResource(R.string.action_retry), fontWeight = FontWeight.Bold)
            }
          }
        }
      }

      state.source != null -> {
        MobilePlayerSurface(
          state = state,
          settings = settings,
          onProgress = viewModel::saveProgress,
          onPlaybackError = viewModel::handlePlaybackError,
          onSelectSource = viewModel::selectSource,
          onBack = onBack,
          onRetry = { viewModel.prepare(mediaId, season, episode, autoStart, manualSelection) },
          onEpisodeEnded = {
            if (nextEpisodeHandled) return@MobilePlayerSurface
            nextEpisodeHandled = true
            if (!settings.autoPlayNextEpisodeEnabled) return@MobilePlayerSurface
            scope.launch {
              val next = viewModel.resolveNextEpisode() ?: return@launch
              val current = state.episode
              val isDifferentEpisode = current == null ||
                next.seasonNumber != current.seasonNumber ||
                next.episodeNumber != current.episodeNumber
              if (shouldAutoPlayNextEpisode(
                  enabled = true,
                  isTvShow = true,
                  hasNextEpisode = true,
                  alreadyTriggered = false
                ) && isDifferentEpisode
              ) {
                onPlayNextEpisode(next.seasonNumber, next.episodeNumber)
              }
            }
          }
        )
      }
    }
  }
}

/** Top bar minimale degli stati non-player (loading/errore), con Back sempre visibile. */
@Composable
private fun PlayerStatusScaffold(
  title: String,
  onBack: () -> Unit,
  content: @Composable () -> Unit
) {
  Box(modifier = Modifier.fillMaxSize()) {
    content()
    Row(
      modifier = Modifier
        .align(Alignment.TopStart)
        .fillMaxWidth()
        .background(
          Brush.verticalGradient(
            listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)
          )
        )
        .statusBarsPadding()
        .padding(horizontal = 8.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      IconButton(onClick = onBack) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = stringResource(R.string.player_back),
          tint = Color.White
        )
      }
      Text(
        text = title,
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier.weight(1f)
      )
    }
  }
}

/**
 * Elenco sorgenti mostrato quando «Avvio automatico (Autoplay)» è OFF in modalità
 * Debrid TorBox e quando la ricerca parte dal pulsante «Seleziona fonte» della
 * scheda Dettaglio: stesso comportamento della TV, adattato al touch.
 *
 * Intestazione con titolo e contesto SxE (come il pannello media della TV),
 * elenco delle sorgenti con i dati realmente dichiarati dal modello, stato
 * vuoto con retry e banner d'errore: nessuno spinner infinito, nessuna
 * riproduzione avviata senza la conferma dell'utente.
 */
@Composable
private fun MobileSourcePicker(
  sources: List<StreamSource>,
  media: MediaItem?,
  episode: Episode?,
  error: String?,
  onSelect: (StreamSource) -> Unit,
  onClose: () -> Unit,
  onRetry: () -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(NovaCardBg)
      .statusBarsPadding()
      .padding(horizontal = 16.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.weight(1f).padding(vertical = 12.dp)) {
        Text(
          text = stringResource(R.string.player_sources_title),
          color = NovaTextPrimary,
          fontSize = 20.sp,
          fontWeight = FontWeight.Bold
        )
        // Contesto del contenuto: titolo e puntata, come sulla TV.
        val context = buildList {
          media?.title?.takeIf { it.isNotBlank() }?.let { add(it) }
          episode?.let { add("S${it.seasonNumber}E${it.episodeNumber}") }
        }.joinToString(" • ")
        Text(
          text = context.ifBlank { stringResource(R.string.player_sources_subtitle) },
          color = NovaTextMuted,
          fontSize = 13.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        if (sources.isNotEmpty()) {
          Text(
            text = stringResource(R.string.player_sources_count, sources.size),
            color = NovaTextMuted,
            fontSize = 12.sp
          )
        }
      }
      IconButton(onClick = onClose) {
        Icon(
          Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = stringResource(R.string.player_back),
          tint = NovaTextPrimary
        )
      }
    }

    if (error != null) {
      Text(
        text = error,
        color = NovaOrange,
        fontSize = 13.sp,
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 8.dp)
      )
    }

    if (sources.isEmpty()) {
      // Nessuna sorgente: l'utente può riprovare la ricerca o chiudere il
      // selettore tornando alla scheda. Nessuna riproduzione parte.
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = stringResource(R.string.player_sources_empty),
          color = NovaTextPrimary,
          fontSize = 16.sp,
          fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = stringResource(R.string.player_sources_empty_hint),
          color = NovaTextMuted,
          fontSize = 13.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
          onClick = onRetry,
          colors = ButtonDefaults.buttonColors(
            containerColor = NovaCyan,
            contentColor = Color.Black
          )
        ) {
          Text(stringResource(R.string.action_retry), fontWeight = FontWeight.Bold)
        }
      }
    } else {
      LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(sources, key = { "${it.serverName}-${it.streamUrl}-${it.infoHash}" }) { source ->
          SourceCard(source = source, onClick = { onSelect(source) })
        }
      }
    }
  }
}

/**
 * Riga di una sorgente.
 *
 * Mostra SOLO i dati realmente dichiarati dal modello: provider, risoluzione,
 * qualità, nome della release, lingue audio, dimensione e dettagli residui.
 * Un dato assente non viene sostituito con un valore inventato.
 */
@Composable
private fun SourceCard(source: StreamSource, onClick: () -> Unit) {
  val shape = RoundedCornerShape(12.dp)
  val provider = MobileSourcePresentation.providerLabel(source)
  val quality = MobileSourcePresentation.qualityLabel(source)
  val resolution = MobileSourcePresentation.resolutionLabel(source)
  val release = MobileSourcePresentation.releaseLabel(source)
  val languages = MobileSourcePresentation.languageLabel(source)
  val size = MobileSourcePresentation.sizeLabel(source)
  val details = MobileSourcePresentation.detailsLabel(source)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(NovaCardBg, shape)
      .border(1.dp, NovaDivider, shape)
      .clickable(onClick = onClick)
      .padding(horizontal = 14.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = provider.ifBlank { stringResource(R.string.player_source_unknown) },
        color = NovaTextPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      // Risoluzione e qualità dichiarate (una delle due può mancare).
      val qualityLine = listOfNotNull(
        resolution,
        quality.takeIf { it.isNotBlank() && !it.equals(resolution, ignoreCase = true) }
      ).joinToString(" • ")
      if (qualityLine.isNotBlank()) {
        Text(
          text = qualityLine,
          color = NovaTextSecondary,
          fontSize = 12.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
      if (!release.isNullOrBlank()) {
        Text(
          text = release,
          color = NovaTextSecondary,
          fontSize = 12.sp,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
      }
      // Lingue audio, dimensione e dettagli residui: solo se dichiarati.
      val metaLine = listOfNotNull(languages, size, details).joinToString(" • ")
      if (metaLine.isNotBlank()) {
        Text(
          text = metaLine,
          color = NovaTextMuted,
          fontSize = 11.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
    Spacer(modifier = Modifier.size(8.dp))
    val cached = source.cacheState == CacheState.Cached || source.isCached
    Icon(
      imageVector = if (cached) Icons.Default.CloudDone else Icons.Default.CloudOff,
      contentDescription = null,
      tint = if (cached) NovaGreen else NovaTextMuted,
      modifier = Modifier.size(18.dp)
    )
    Spacer(modifier = Modifier.size(4.dp))
    Text(
      text = stringResource(
        if (cached) R.string.player_source_cached else R.string.player_source_uncached
      ),
      color = if (cached) NovaGreen else NovaTextMuted,
      fontSize = 11.sp
    )
  }
}

@Composable
@UnstableApi
private fun MobilePlayerSurface(
  state: MobilePlayerUiState,
  settings: PlaybackSettings,
  onProgress: (Long, Long) -> Unit,
  onPlaybackError: (PlaybackException, String) -> Boolean,
  onSelectSource: (StreamSource) -> Unit,
  onBack: () -> Unit,
  onRetry: () -> Unit,
  onEpisodeEnded: () -> Unit
) {
  val context = LocalContext.current
  val activity = LocalView.current.context.findActivity()
  val source = state.source ?: return
  val streamUrl = source.streamUrl ?: return
  val media = state.media
  val episode = state.episode
  val currentOnEpisodeEnded by rememberUpdatedState(onEpisodeEnded)

  val startPositionMs = remember(state.source, episode, media, settings.autoResume) {
    val saved = if (episode != null && media != null) {
      MediaRepository.getEpisodeProgress(media.id, episode.seasonNumber, episode.episodeNumber)
    } else {
      // Lettura fresca dal repository: dopo un cambio sorgente automatico la copia
      // in `state.media` può essere precedente all'ultimo salvataggio di progresso.
      media?.let { MediaRepository.getMediaById(it.id)?.currentProgressMs ?: it.currentProgressMs } ?: 0L
    }
    // Stessa regola della TV: un episodio già concluso riparte da 0:00, altrimenti
    // il player finirebbe subito e sembrerebbe "non riprendere".
    val duration = episode?.totalDurationMs ?: 0L
    val alreadyFinished = duration > 0L && (
      saved >= (duration * 0.90f) ||
        WatchProgress.isAtEnd(saved, duration)
      )
    if (alreadyFinished) {
      0L
    } else {
      MobileSettingsPolicy.startPositionMs(saved, duration, settings.autoResume)
    }
  }

  // Posizione live riusata quando il player viene ricreato (decoder, buffer):
  // il cambio non deve far saltare l'utente all'inizio o all'ultimo salvataggio.
  var resumePositionMs by remember { mutableLongStateOf(startPositionMs) }

  val extraBuffering = remember { MobilePlayerPreferences.extraBufferingEnabled() }
  var extraBufferingEnabled by remember { mutableStateOf(extraBuffering) }
  var gesturesEnabled by remember { mutableStateOf(MobilePlayerPreferences.gesturesEnabled()) }
  var keepScreenOnInPause by remember {
    mutableStateOf(MobilePlayerPreferences.keepScreenOnInPause())
  }

  // Solo le impostazioni che cambiano davvero la costruzione del player
  // ricreano l'istanza: la lingua audio scelta nel pannello resta per la sessione
  // e non deve provocare un riavvio del flusso.
  val rebuildKey = remember(
    settings.decoderFallbackMode,
    settings.audioPassthroughEnabled,
    settings.dolbyVisionFallbackEnabled,
    settings.audioTunnelingEnabled,
    settings.preferredResolution,
    settings.autoFrameRateMatching,
    settings.targetBuffer,
    settings.initialBuffer,
    settings.backBuffer,
    extraBufferingEnabled
  ) {
    listOf(
      settings.decoderFallbackMode,
      settings.audioPassthroughEnabled,
      settings.dolbyVisionFallbackEnabled,
      settings.audioTunnelingEnabled,
      settings.preferredResolution,
      settings.autoFrameRateMatching,
      settings.targetBuffer,
      settings.initialBuffer,
      settings.backBuffer,
      extraBufferingEnabled
    )
  }

  val playerBundle = remember(streamUrl, rebuildKey) {
    buildMobilePlayer(
      context = context,
      state = state,
      settings = settings,
      startPositionMs = resumePositionMs,
      extraBuffering = extraBufferingEnabled
    )
  }
  val exoPlayer = playerBundle.player
  val trackSelector = playerBundle.trackSelector

  var positionMs by remember(exoPlayer) { mutableLongStateOf(exoPlayer.currentPosition) }
  var durationMs by remember(exoPlayer) { mutableLongStateOf(exoPlayer.duration.coerceAtLeast(0L)) }
  var isPlaying by remember(exoPlayer) { mutableStateOf(exoPlayer.isPlaying) }
  var isBuffering by remember(exoPlayer) {
    mutableStateOf(exoPlayer.playbackState == Player.STATE_BUFFERING)
  }
  var playbackEnded by remember(exoPlayer) {
    mutableStateOf(exoPlayer.playbackState == Player.STATE_ENDED)
  }
  var tracks by remember(exoPlayer) { mutableStateOf(exoPlayer.currentTracks) }
  var videoHeight by remember(exoPlayer) { mutableIntStateOf(exoPlayer.videoFormat?.height ?: 0) }
  var speed by remember(exoPlayer) { mutableStateOf(exoPlayer.playbackParameters.speed) }
  var manualQualityHeight by remember(exoPlayer) { mutableStateOf<Int?>(null) }
  var scale by remember(exoPlayer) { mutableStateOf(MobileVideoScale.FIT) }
  var immersive by remember(exoPlayer) { mutableStateOf(true) }
  var manualSubtitleTrackIndex by remember(exoPlayer) { mutableStateOf<Int?>(null) }
  var subtitlesExplicitlyOff by remember(exoPlayer) { mutableStateOf(false) }

  var controlsVisible by remember(exoPlayer) { mutableStateOf(true) }
  var interactionTick by remember(exoPlayer) { mutableIntStateOf(0) }
  var panel by remember(exoPlayer) { mutableStateOf(MobilePlayerPanel.NONE) }
  var isScrubbing by remember(exoPlayer) { mutableStateOf(false) }
  var isLocked by remember(exoPlayer) { mutableStateOf(false) }
  var lockHintVisible by remember(exoPlayer) { mutableStateOf(false) }
  var scrubPreviewMs by remember(exoPlayer) { mutableLongStateOf(0L) }

  // Messaggio localizzato dell'errore di playback; il dettaglio tecnico appare
  // sotto il messaggio solo quando l'overlay debug è attivo.
  var playbackError by remember(exoPlayer) { mutableStateOf<Int?>(null) }
  var playbackErrorDetail by remember(exoPlayer) { mutableStateOf<String?>(null) }

  fun noteInteraction() {
    controlsVisible = true
    interactionTick++
  }

  fun seekTo(targetMs: Long) {
    val duration = exoPlayer.duration.coerceAtLeast(0L)
    val target = if (duration > 0L) targetMs.coerceIn(0L, duration) else targetMs.coerceAtLeast(0L)
    exoPlayer.seekTo(target)
    resumePositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
    positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
  }

  fun seekBy(deltaMs: Long) {
    seekTo(
      playerSeekTarget(
        positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
        deltaMs = deltaMs,
        durationMs = exoPlayer.duration.coerceAtLeast(0L)
      )
    )
  }

  DisposableEffect(exoPlayer, trackSelector, settings) {
    val listener = object : Player.Listener {
      override fun onPlayerError(error: PlaybackException) {
        Log.w(PLAYER_ERROR_TAG, "Errore di riproduzione (${error.errorCodeName})", error)
        // Un errore definitivo con un'alternativa già risolta fa cambiare sorgente
        // senza mostrare alcun overlay: la nuova prova riparte immediatamente.
        val switched = onPlaybackError(error, PlayerSourceFallback.keyOf(source))
        if (!switched) {
          playbackError = MobilePlaybackErrorPolicy.messageResId(error)
          playbackErrorDetail = if (settings.debugOverlayEnabled) {
            "${error.errorCodeName}: ${error.message.orEmpty()}"
          } else {
            null
          }
          // L'errore ha una schermata dedicata con Riprova/Scegli sorgente/Esci:
          // i controlli normali non devono restare visibili sotto l'overlay.
          controlsVisible = false
          panel = MobilePlayerPanel.NONE
        }
      }

      override fun onTracksChanged(currentTracks: Tracks) {
        tracks = currentTracks
        // Forced / sottotitoli automatici / lingua preferita: stessa logica TV,
        // applicata quando le tracce della sorgente diventano disponibili. Una
        // scelta manuale dell'utente non viene mai sovrascritta dal listener.
        if (manualSubtitleTrackIndex == null && !subtitlesExplicitlyOff) {
          applyMobileSubtitleSelection(trackSelector, currentTracks, settings)
        }
      }

      override fun onIsPlayingChanged(playing: Boolean) {
        isPlaying = playing
      }

      override fun onPlaybackStateChanged(playbackState: Int) {
        isBuffering = playbackState == Player.STATE_BUFFERING
        playbackEnded = playbackState == Player.STATE_ENDED
        durationMs = exoPlayer.duration.coerceAtLeast(0L)
        if (playbackState == Player.STATE_ENDED) {
          positionMs = exoPlayer.duration.coerceAtLeast(0L)
          controlsVisible = true
          currentOnEpisodeEnded()
        }
      }

      override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
        videoHeight = videoSize.height.takeIf { it > 0 } ?: videoHeight
      }
    }
    exoPlayer.addListener(listener)
    if (manualSubtitleTrackIndex == null && !subtitlesExplicitlyOff) {
      applyMobileSubtitleSelection(trackSelector, exoPlayer.currentTracks, settings)
    }
    onDispose { exoPlayer.removeListener(listener) }
  }

  // Ticker unico della UI: 250 ms con controlli/pannello visibili, 1 s quando
  // l'overlay è nascosto. Alimenta posizione, durata e stato del player.
  LaunchedEffect(exoPlayer, controlsVisible, panel, isScrubbing) {
    while (isActive) {
      val current = exoPlayer.currentPosition.coerceAtLeast(0L)
      if (!isScrubbing) positionMs = current
      durationMs = exoPlayer.duration.coerceAtLeast(0L)
      isPlaying = exoPlayer.isPlaying
      resumePositionMs = current
      delay(if (controlsVisible || panel.isOpen || isScrubbing) 250L else 1_000L)
    }
  }

  // Auto-hide: una sola coroutine, riavviata a ogni interazione. Non nasconde
  // mai i controlli quando il video è in pausa, quando un pannello è aperto,
  // durante lo scrub o davanti a un errore.
  LaunchedEffect(
    controlsVisible,
    interactionTick,
    isPlaying,
    isLocked,
    panel,
    isScrubbing,
    playbackError
  ) {
    if (shouldAutoHidePlayerControls(
        controlsVisible = controlsVisible,
        isPlaying = isPlaying,
        isLocked = isLocked,
        panel = panel,
        isScrubbing = isScrubbing,
        hasPlaybackError = playbackError != null
      )
    ) {
      delay(MobilePlayerControlsPolicy.AUTO_HIDE_MS)
      // Ricontrolla lo stato dopo l'attesa: un tap arrivato nel frattempo ha
      // incrementato interactionTick e ha già cancellato questo effetto.
      if (shouldAutoHidePlayerControls(
          controlsVisible = controlsVisible,
          isPlaying = isPlaying,
          isLocked = isLocked,
          panel = panel,
          isScrubbing = isScrubbing,
          hasPlaybackError = playbackError != null
        )
      ) {
        controlsVisible = false
      }
    }
  }

  // L'avviso di sblocco sparisce da solo, senza mai bloccare il player.
  LaunchedEffect(isLocked, lockHintVisible, interactionTick) {
    if (isLocked && lockHintVisible) {
      delay(MobilePlayerControlsPolicy.LOCK_HINT_AUTO_HIDE_MS)
      lockHintVisible = false
    }
  }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, exoPlayer) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_STOP -> exoPlayer.pause()
        Lifecycle.Event.ON_DESTROY -> exoPlayer.release()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      // Salva solo se la riproduzione è realmente iniziata: uscire da un tentativo
      // fallito a 0:00 non deve azzerare la posizione di ripresa già salvata.
      val positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
      if (positionMs > 0L) {
        onProgress(positionMs, exoPlayer.duration.coerceAtLeast(0L))
      }
      exoPlayer.release()
    }
  }

  LaunchedEffect(exoPlayer) {
    while (isActive) {
      delay(5_000)
      if (exoPlayer.isPlaying) {
        onProgress(
          exoPlayer.currentPosition.coerceAtLeast(0L),
          exoPlayer.duration.coerceAtLeast(0L)
        )
      }
    }
  }

  // System bars: immersive durante la riproduzione, con possibilità di mostrare
  // le barre dal pulsante fullscreen. All'uscita la schermata le ripristina.
  LaunchedEffect(activity, immersive) {
    val window = activity?.window ?: return@LaunchedEffect
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    if (immersive) {
      controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
      controller.show(WindowInsetsCompat.Type.systemBars())
    }
  }
  DisposableEffect(activity) {
    onDispose {
      activity?.window?.let { window ->
        WindowCompat.getInsetsController(window, window.decorView)
          .show(WindowInsetsCompat.Type.systemBars())
      }
    }
  }

  // Schermo acceso: sempre durante la riproduzione; in pausa solo se richiesto.
  val keepScreenOn = shouldKeepPlayerScreenOn(isPlaying, keepScreenOnInPause)
  DisposableEffect(activity, keepScreenOn) {
    val window = activity?.window
    if (keepScreenOn) {
      window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
      window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    onDispose {
      window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
  }

  // Back: prima chiude il pannello, poi sblocca l'avviso, infine esce dal player
  // (il BackHandler del player resta attivo anche con la UI nascosta).
  BackHandler(enabled = panel.isOpen) { panel = MobilePlayerPanel.NONE }
  BackHandler(enabled = isLocked && lockHintVisible) { lockHintVisible = false }

  val audioTracks = remember(tracks) { collectAudioTracks(tracks) }
  val subtitleTracks = remember(tracks) { collectSubtitleTracks(tracks) }
  val videoHeights = remember(tracks) { collectVideoHeights(tracks) }
  val selectedAudioTrack = audioTracks.firstOrNull { it.isSelected }
  val selectedSubtitleTrack = subtitleTracks.firstOrNull { it.isSelected }
  val currentHeight = manualQualityHeight ?: playerHeight(exoPlayer, videoHeight)
  val sourceName = source.serverName.ifBlank {
    source.addonName ?: source.releaseTitle ?: stringResource(R.string.player_source_unknown)
  }
  val secondaryLine = buildList {
    media?.year?.takeIf { it > 0 }?.let { add(it.toString()) }
    episode?.let { ep ->
      add("S${ep.seasonNumber}E${ep.episodeNumber} • ${ep.title}")
    }
  }.joinToString(" • ")

  val actions = MobilePlayerActions(
    onBack = { onBack() },
    onTogglePlayPause = {
      if (exoPlayer.playbackState == Player.STATE_ENDED) {
        exoPlayer.seekTo(0L)
        exoPlayer.play()
      } else if (exoPlayer.isPlaying) {
        exoPlayer.pause()
      } else {
        exoPlayer.play()
      }
      noteInteraction()
    },
    onSeekBy = { delta -> seekBy(delta); noteInteraction() },
    onSeekTo = { target -> seekTo(target); noteInteraction() },
    onSkipIntro = {
      state.introWindow?.let { window ->
        seekTo(window.endMs)
      }
      noteInteraction()
    },
    onLock = {
      isLocked = true
      controlsVisible = false
      panel = MobilePlayerPanel.NONE
      lockHintVisible = true
      interactionTick++
    },
    onUnlock = {
      isLocked = false
      lockHintVisible = false
      noteInteraction()
    },
    onCycleScale = {
      scale = scale.next()
      noteInteraction()
    },
    onToggleImmersive = {
      immersive = !immersive
      noteInteraction()
    },
    onSelectQuality = { height ->
      manualQualityHeight = height
      applyVideoQuality(exoPlayer, height)
      resumePositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
      noteInteraction()
    },
    onSelectAudio = { index ->
      PlayerRuntimeControllerTrackSelection.selectAudioTrack(exoPlayer, index)
      audioTracks.firstOrNull { it.trackIndex == index }?.let { track ->
        persistAudioLanguage(track.language)
      }
      noteInteraction()
    },
    onSelectSubtitles = { index ->
      if (index == null) {
        subtitlesExplicitlyOff = true
        manualSubtitleTrackIndex = null
        PlayerRuntimeControllerTrackSelection.disableSubtitles(exoPlayer)
      } else {
        subtitlesExplicitlyOff = false
        manualSubtitleTrackIndex = index
        PlayerRuntimeControllerTrackSelection.selectSubtitleTrack(exoPlayer, index)
      }
      noteInteraction()
    },
    onSelectSpeed = { value ->
      speed = value
      exoPlayer.setPlaybackSpeed(value)
      noteInteraction()
    },
    onSelectSource = { candidate ->
      resumePositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
      onProgress(resumePositionMs, exoPlayer.duration.coerceAtLeast(0L))
      // La selezione passa dal ViewModel: risolve eventuali infoHash Debrid e
      // pubblica la nuova sorgente; il remember(streamUrl) ricrea il player.
      onSelectSource(candidate)
      noteInteraction()
    },
    onSelectDecoder = { mode ->
      resumePositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
      SettingsRepository.setDecoderFallbackMode(mode)
      noteInteraction()
    },
    onGesturesEnabledChange = { enabled ->
      gesturesEnabled = enabled
      MobilePlayerPreferences.setGesturesEnabled(enabled)
      noteInteraction()
    },
    onKeepScreenOnInPauseChange = { enabled ->
      keepScreenOnInPause = enabled
      MobilePlayerPreferences.setKeepScreenOnInPause(enabled)
      noteInteraction()
    },
    onExtraBufferingChange = { enabled ->
      resumePositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
      extraBufferingEnabled = enabled
      MobilePlayerPreferences.setExtraBufferingEnabled(enabled)
      noteInteraction()
    },
    onSelectScale = { value ->
      scale = value
      noteInteraction()
    },
    onScrubbingChange = { scrubbing ->
      isScrubbing = scrubbing
      if (scrubbing) controlsVisible = true
    },
    onOpenPanel = { next ->
      panel = next
      if (next.isOpen) controlsVisible = true
      noteInteraction()
    },
    onInteraction = { noteInteraction() }
  )

  Box(modifier = Modifier.fillMaxSize()) {
    AndroidView(
      factory = { ctx ->
        PlayerView(ctx).apply {
          player = exoPlayer
          useController = false
          controllerAutoShow = false
          setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
          resizeMode = scale.toResizeMode()
          this.keepScreenOn = true
          setBackgroundColor(AndroidColor.BLACK)
          layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
          )
          applyMobileSubtitleStyle(settings)
        }
      },
      update = { view ->
        view.player = exoPlayer
        view.resizeMode = scale.toResizeMode()
        view.applyMobileSubtitleStyle(settings)
      },
      modifier = Modifier.fillMaxSize()
    )

    // Livello gesti sotto i controlli: tap mostra/nasconde la UI, doppio tap
    // salta di 10 s, trascinamento orizzontale cerca. Disattivabile dal pannello.
    Box(
      modifier = Modifier
        .fillMaxSize()
        .pointerInput(gesturesEnabled, isLocked, panel) {
          detectTapGestures(
            onTap = {
              if (isLocked) {
                // Primo tap: mostra il comando di sblocco. Tap successivo (anche
                // fuori dal pulsante, per non dipendere da un'area precisa):
                // sblocca e riporta la UI completa.
                if (lockHintVisible) {
                  isLocked = false
                  lockHintVisible = false
                  noteInteraction()
                } else {
                  lockHintVisible = true
                  interactionTick++
                }
              } else if (panel.isOpen) {
                panel = MobilePlayerPanel.NONE
                noteInteraction()
              } else {
                controlsVisible = !controlsVisible
                if (controlsVisible) interactionTick++
              }
            },
            onDoubleTap = { offset ->
              if (!isLocked && !panel.isOpen && gesturesEnabled) {
                val delta = if (offset.x < size.width / 2f) {
                  -MobilePlayerControlsPolicy.SEEK_STEP_MS
                } else {
                  MobilePlayerControlsPolicy.SEEK_STEP_MS
                }
                seekBy(delta)
                noteInteraction()
              }
            }
          )
        }
        .pointerInput(gesturesEnabled, isLocked, panel, durationMs) {
          if (!gesturesEnabled || isLocked || panel.isOpen) return@pointerInput
          var startPosition = 0L
          var accumulatedMs = 0L
          detectHorizontalDragGestures(
            onDragStart = {
              startPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
              accumulatedMs = 0L
              scrubPreviewMs = startPosition
              isScrubbing = true
              controlsVisible = true
            },
            onDragEnd = {
              val target = scrubPreviewMs
              isScrubbing = false
              seekTo(target)
              noteInteraction()
            },
            onDragCancel = {
              isScrubbing = false
              noteInteraction()
            },
            onHorizontalDrag = { change, dragAmount ->
              change.consume()
              val duration = exoPlayer.duration.coerceAtLeast(0L)
              if (duration > 0L && size.width > 0) {
                accumulatedMs += ((dragAmount / size.width) * duration).toLong()
                scrubPreviewMs = playerSeekTarget(startPosition, accumulatedMs, duration)
                positionMs = scrubPreviewMs
              }
            }
          )
        }
    )

    if (settings.debugOverlayEnabled) {
      PlayerStatsOverlay(
        player = exoPlayer,
        modifier = Modifier
          .align(Alignment.BottomStart)
          .padding(12.dp)
      )
    }

    playbackError?.let { messageRes ->
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.Black.copy(alpha = 0.88f))
          .clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null
          ) { },
        contentAlignment = Alignment.Center
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.padding(24.dp)
        ) {
          Text(
            text = stringResource(R.string.player_error_title),
            color = NovaTextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = stringResource(messageRes),
            color = NovaTextMuted,
            fontSize = 14.sp
          )
          playbackErrorDetail?.let { detail ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = detail,
              color = NovaTextMuted,
              fontSize = 11.sp
            )
          }
          Spacer(modifier = Modifier.height(18.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
              onClick = onRetry,
              colors = ButtonDefaults.buttonColors(
                containerColor = NovaCyan,
                contentColor = Color.Black
              )
            ) {
              Text(stringResource(R.string.action_retry), fontWeight = FontWeight.Bold)
            }
            if (state.sources.size > 1) {
              Button(
                onClick = {
                  panel = MobilePlayerPanel.SERVERS
                  controlsVisible = false
                },
                colors = ButtonDefaults.buttonColors(
                  containerColor = Color.White.copy(alpha = 0.14f),
                  contentColor = Color.White
                )
              ) {
                Text(stringResource(R.string.player_sources_title))
              }
            }
          }
          Spacer(modifier = Modifier.height(8.dp))
          androidx.compose.material3.TextButton(onClick = onBack) {
            Text(stringResource(R.string.player_back), color = NovaTextSecondary)
          }
        }
      }
    }

    MobilePlayerOverlay(
      data = MobilePlayerControlsData(
        title = media?.title.orEmpty(),
        secondaryLine = secondaryLine,
        isPlaying = isPlaying,
        playbackEnded = playbackEnded,
        isBuffering = isBuffering,
        positionMs = if (isScrubbing) scrubPreviewMs else positionMs,
        durationMs = durationMs,
        isLocked = isLocked,
        lockHintVisible = lockHintVisible,
        controlsVisible = controlsVisible,
        panel = panel,
        settings = settings,
        videoHeights = videoHeights,
        selectedVideoHeight = currentHeight,
        autoQuality = manualQualityHeight == null,
        audioTracks = audioTracks,
        selectedAudioTrack = selectedAudioTrack,
        subtitleTracks = subtitleTracks,
        selectedSubtitleTrack = selectedSubtitleTrack,
        subtitlesOff = selectedSubtitleTrack == null || subtitlesExplicitlyOff,
        speed = speed,
        scale = scale,
        currentSourceName = sourceName,
        sources = state.sources,
        currentSourceKey = PlayerSourceFallback.keyOf(source),
        introWindow = state.introWindow,
        openSubtitlesConfigured = state.openSubtitlesConfigured,
        externalSubtitleCount = state.externalSubtitleCount,
        gesturesEnabled = gesturesEnabled,
        keepScreenOnInPause = keepScreenOnInPause,
        extraBufferingEnabled = extraBufferingEnabled,
        decoderMode = settings.decoderFallbackMode,
        immersive = immersive,
        hasPlaybackError = playbackError != null
      ),
      actions = actions,
      modifier = Modifier.fillMaxSize()
    )
  }
}

// ─── Helper di tracce e impostazioni ───────────────────────────────────────

private fun collectAudioTracks(tracks: Tracks): List<MobileAudioTrack> {
  val result = mutableListOf<MobileAudioTrack>()
  var index = 0
  for (group in tracks.groups) {
    if (group.type != C.TRACK_TYPE_AUDIO) continue
    for (trackIndex in 0 until group.length) {
      val format = group.getTrackFormat(trackIndex)
      result += MobileAudioTrack(
        trackIndex = index,
        language = format.language,
        label = format.label,
        codec = CustomDefaultTrackNameProvider.formatNameFromMime(format.sampleMimeType)
          ?: format.codecs,
        channels = CustomDefaultTrackNameProvider.getChannelLayoutName(format.channelCount),
        isSelected = group.isTrackSelected(trackIndex)
      )
      index++
    }
  }
  return result
}

private fun collectSubtitleTracks(tracks: Tracks): List<MobileSubtitleTrack> {
  val result = mutableListOf<MobileSubtitleTrack>()
  var index = 0
  for (group in tracks.groups) {
    if (group.type != C.TRACK_TYPE_TEXT) continue
    for (trackIndex in 0 until group.length) {
      val format = group.getTrackFormat(trackIndex)
      val label = format.label.orEmpty().lowercase()
      val id = format.id.orEmpty().lowercase()
      val isForced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0 ||
        label.contains("forced") || label.contains("forzat") ||
        id.contains("forced") || id.contains("forzat")
      result += MobileSubtitleTrack(
        trackIndex = index,
        language = format.language,
        label = format.label,
        isForced = isForced,
        isSelected = group.isTrackSelected(trackIndex)
      )
      index++
    }
  }
  return result
}

private fun collectVideoHeights(tracks: Tracks): List<Int> {
  val heights = mutableSetOf<Int>()
  for (group in tracks.groups) {
    if (group.type != C.TRACK_TYPE_VIDEO) continue
    for (trackIndex in 0 until group.length) {
      group.getTrackFormat(trackIndex).height.takeIf { it > 0 }?.let(heights::add)
    }
  }
  return heights.sortedDescending()
}

private fun playerHeight(player: ExoPlayer, fallback: Int): Int? =
  player.videoFormat?.height?.takeIf { it > 0 } ?: fallback.takeIf { it > 0 }

private fun applyVideoQuality(player: ExoPlayer, height: Int?) {
  val builder = player.trackSelectionParameters.buildUpon()
  if (height == null) {
    builder.clearVideoSizeConstraints()
  } else {
    builder.setMaxVideoSize(7_680, height)
    builder.setMinVideoSize(0, height)
  }
  player.trackSelectionParameters = builder.build()
}

/** Persistenza della lingua audio scelta: solo per codici supportati dalle Impostazioni. */
private fun persistAudioLanguage(language: String?) {
  val twoLetter = MobileSettingsPolicy.mapPreferredAudioLanguages(language ?: return)
    .firstOrNull()
    ?.take(2)
    ?: return
  if (twoLetter in MobileSettingsPolicy.AUDIO_LANGUAGE_CODES) {
    SettingsRepository.setPreferredAudioLanguage(twoLetter)
  }
}

private fun MobileVideoScale.toResizeMode(): Int = when (this) {
  MobileVideoScale.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
  MobileVideoScale.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
  MobileVideoScale.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
  MobileVideoScale.FIXED_WIDTH -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
  MobileVideoScale.FIXED_HEIGHT -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
}

private class MobilePlayerBundle(
  val player: ExoPlayer,
  val trackSelector: androidx.media3.exoplayer.trackselection.DefaultTrackSelector
)

@UnstableApi
private fun buildMobilePlayer(
  context: Context,
  state: MobilePlayerUiState,
  settings: PlaybackSettings,
  startPositionMs: Long,
  extraBuffering: Boolean
): MobilePlayerBundle {
  val source = state.source ?: error("source mancante")
  val streamUrl = source.streamUrl ?: error("streamUrl mancante")

  // Buffer extra per server instabili: solo per la sessione corrente, senza
  // modificare le preferenze globali delle Impostazioni.
  val effectiveSettings = if (extraBuffering) {
    settings.copy(
      targetBuffer = TargetBufferOption.EXTENDED,
      initialBuffer = InitialBufferOption.STABLE,
      backBuffer = BackBufferOption.EXTENDED
    )
  } else {
    settings
  }

  // Trasporto OkHttp, come la TV: DefaultHttpDataSource usa HttpURLConnection, il cui
  // canale interruptibile fa fallire la cancellazione del loader durante una seek
  // ("Unbalanced enter/exit" → runtime check). Gli header della sorgente restano
  // applicati a ogni richiesta.
  val httpDataSourceFactory = PlayerHttpDataSource.createFactory(
    userAgent = "StreamNova-Mobile/${BuildConfig.VERSION_NAME}",
    connectTimeoutMs = 15_000,
    readTimeoutMs = 15_000
  ).apply {
    setDefaultRequestProperties(source.headers)
  }

  val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
  val progressiveFactory = ProgressiveMediaSource.Factory(
    dataSourceFactory,
    DefaultExtractorsFactory()
  )
  val loadErrorHandlingPolicy = PlayerLoadErrorHandlingPolicy()
  val mediaSourceFactory = resolvePlaybackMediaSourceFactory(
    isHlsStream = MobilePlaybackSourcePolicy.isHlsStream(streamUrl, source.isProgressive),
    httpDataSourceFactory = dataSourceFactory,
    progressiveMediaSourceFactory = progressiveFactory,
    loadErrorHandlingPolicy = loadErrorHandlingPolicy
  )

  val extensionMode = when (effectiveSettings.decoderFallbackMode) {
    DecoderFallbackMode.OFF -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
    DecoderFallbackMode.ON -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
    DecoderFallbackMode.PREFER -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
  }
  val renderersFactory = NuvioRenderersFactory(
    context = context,
    audioPassthroughEnabled = effectiveSettings.audioPassthroughEnabled,
    extensionRendererModePreference = extensionMode,
    dolbyVisionFallbackEnabled = effectiveSettings.dolbyVisionFallbackEnabled
  )

  val preferredAudioLanguages =
    MobileSettingsPolicy.mapPreferredAudioLanguages(effectiveSettings.preferredAudioLanguage)
  val trackSelector = NuvioTrackSelector.create(
    context = context,
    streamNameProvider = { state.media?.title },
    streamUrlProvider = { streamUrl },
    tunnelingEnabled = effectiveSettings.audioTunnelingEnabled,
    preferredAudioLanguages = preferredAudioLanguages,
    subtitlesEnabled = effectiveSettings.subtitlesEnabled,
    forcedSubtitlesEnabled = effectiveSettings.forcedSubtitlesEnabled
  ).apply {
    // "Risoluzione preferita": Auto e 4K non impongono vincoli, 1080p/720p sì
    // (stessa regola della TV).
    MobileSettingsPolicy.resolutionConstraint(effectiveSettings.preferredResolution)
      ?.let { (width, height) ->
        setParameters(buildUponParameters().setMaxVideoSize(width, height))
      }
  }

  val player = ExoPlayer.Builder(context, renderersFactory)
    .setTrackSelector(trackSelector)
    .setLoadControl(buildPlayerLoadControl(effectiveSettings))
    .setVideoChangeFrameRateStrategy(
      if (effectiveSettings.autoFrameRateMatching) {
        C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
      } else {
        C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
      }
    )
    .build()

  val mediaItem = PlayerSubtitleMediaItemBuilder.buildMediaItem(streamUrl, source.subtitles)
  player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
  player.prepare()

  if (startPositionMs > 0L) {
    player.seekTo(startPositionMs)
  }
  player.playWhenReady = true
  return MobilePlayerBundle(player, trackSelector)
}

/**
 * Applica le preferenze dei sottotitoli al TrackSelector:
 * - sottotitoli automatici ON: renderer attivo, lingua preferita (o qualsiasi);
 * - ON solo forced (sottotitoli OFF): traccia forced italiana se presente, altrimenti OFF;
 * - entrambi OFF: renderer disabilitato.
 */
private fun applyMobileSubtitleSelection(
  trackSelector: androidx.media3.exoplayer.trackselection.DefaultTrackSelector,
  tracks: Tracks,
  settings: PlaybackSettings
) {
  val builder = trackSelector.buildUponParameters()
    .clearOverridesOfType(C.TRACK_TYPE_TEXT)

  if (settings.subtitlesEnabled) {
    builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
      .setSelectUndeterminedTextLanguage(true)
      .setIgnoredTextSelectionFlags(0)
    val language = MobileSettingsPolicy.subtitleLanguage(settings.preferredSubtitleLanguage)
    if (language != null) {
      builder.setPreferredTextLanguage(language)
    } else {
      builder.setPreferredTextLanguages()
    }
    trackSelector.setParameters(builder)
    return
  }

  if (settings.forcedSubtitlesEnabled) {
    val forcedTrack = findForcedItalianTrack(tracks)
    if (forcedTrack != null) {
      builder
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .setOverrideForType(
          TrackSelectionOverride(forcedTrack.first, forcedTrack.second)
        )
      trackSelector.setParameters(builder)
      return
    }
  }

  builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
  trackSelector.setParameters(builder)
}

/** Cerca una traccia sottotitoli forced in italiano (flag o etichetta), come la TV. */
private fun findForcedItalianTrack(
  tracks: Tracks
): Pair<androidx.media3.common.TrackGroup, Int>? {
  var labelCandidate: Pair<androidx.media3.common.TrackGroup, Int>? = null
  for (group in tracks.groups) {
    if (group.type != C.TRACK_TYPE_TEXT) continue
    for (index in 0 until group.length) {
      val format = group.getTrackFormat(index)
      val language = format.language?.trim()?.lowercase().orEmpty()
      val label = format.label?.trim()?.lowercase().orEmpty()
      val id = format.id?.trim()?.lowercase().orEmpty()
      val groupId = group.mediaTrackGroup.id.trim().lowercase()

      val isItalian = MobileSettingsPolicy.isItalianAudioLanguage(language) ||
        label.contains("italiano") || label.contains("italian")
      if (!isItalian && language.isNotBlank() && language != "und") continue

      val hasForcedFlag = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
      val hasForcedName = label.contains("forced") || label.contains("forzat") ||
        id.contains("forced") || id.contains("forzat") ||
        groupId.contains("forced") || groupId.contains("forzat")
      if (hasForcedFlag) {
        return group.mediaTrackGroup to index
      }
      if (hasForcedName && labelCandidate == null) {
        labelCandidate = group.mediaTrackGroup to index
      }
    }
  }
  return labelCandidate
}

/** Stile dei sottotitoli: dimensione, sfondo e posizione verticale dalle preferenze. */
@UnstableApi
private fun PlayerView.applyMobileSubtitleStyle(settings: PlaybackSettings) {
  subtitleView?.let { view ->
    view.setStyle(subtitleCaptionStyleFor(settings.subtitleBackground))
    view.setFractionalTextSize(settings.subtitleSize.textSizeFraction)
    view.setBottomPaddingFraction(settings.subtitlePosition.bottomPaddingFraction)
  }
}

@UnstableApi
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

/** Overlay "Stats for Nerds": dati reali letti dal player, aggiornati ogni secondo. */
@Composable
private fun PlayerStatsOverlay(player: ExoPlayer, modifier: Modifier = Modifier) {
  var tick by remember { mutableIntStateOf(0) }
  LaunchedEffect(player) {
    while (isActive) {
      delay(1_000)
      tick++
    }
  }
  // Legge lo stato del player a ogni tick (il valore non è usato, ma forza la lettura).
  @Suppress("UNUSED_EXPRESSION")
  tick

  val video = player.videoFormat
  val resolution = video?.let { "${it.width}x${it.height}" } ?: "—"
  val bitrate = video?.bitrate?.takeIf { it > 0 }?.let { "${it / 1000} kbps" } ?: "—"
  val bufferedMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
  val dropped = player.videoDecoderCounters?.droppedBufferCount ?: 0
  val decoder = video?.codecs?.takeIf { it.isNotBlank() } ?: "—"

  Column(
    modifier = modifier
      .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
      .border(1.dp, NovaCyan.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
      .padding(horizontal = 10.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp)
  ) {
    Text(
      text = stringResource(R.string.player_stats_title),
      color = NovaCyanBright,
      fontSize = 11.sp,
      fontWeight = FontWeight.Bold
    )
    StatsLine(stringResource(R.string.player_stats_resolution), resolution)
    StatsLine(stringResource(R.string.player_stats_bitrate), bitrate)
    StatsLine(stringResource(R.string.player_stats_buffer), "${bufferedMs / 1000}s")
    StatsLine(stringResource(R.string.player_stats_dropped), dropped.toString())
    StatsLine(stringResource(R.string.player_stats_decoder), decoder)
  }
}

@Composable
private fun StatsLine(label: String, value: String) {
  Row {
    Text(text = "$label: ", color = NovaTextMuted, fontSize = 10.sp)
    Text(text = value, color = NovaTextPrimary, fontSize = 10.sp, maxLines = 1)
  }
}

/** Tag logcat del player Mobile (diagnostica; nessun dato utente nei messaggi). */
private const val PLAYER_ERROR_TAG = "MobilePlayer"

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}
