package com.example.ui.screens

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.data.model.AudioTrack
import com.example.data.model.Episode
import com.example.data.model.MediaType
import com.example.data.model.SubtitleTrack
import com.example.data.model.VideoResolution
import com.example.data.repository.MediaRepository
import com.example.ui.components.TvActionButton
import com.example.ui.components.TvFocusableBox
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaCyanGlow
import com.example.ui.theme.NovaSurface
import com.example.ui.theme.NovaSurfaceVariant
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import com.example.ui.viewmodel.StreamNovaViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Auto-hide dei controlli del player dopo N ms di inattività con il telecomando. */
private const val CONTROLS_HIDE_DELAY_MS = 5_000L

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

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
  viewModel: StreamNovaViewModel,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val playbackState by viewModel.playbackState.collectAsState()
  val media = playbackState.media ?: return
  val episode = playbackState.currentEpisode

  val isTvShow = media.type == MediaType.SERIE_TV

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

  // Prossimo episodio: calcolato solo se Serie TV ed esiste un episodio successivo
  val nextEpisode: Episode? = remember(media, currentEp) {
    if (!isTvShow || currentEp == null) {
      null
    } else {
      val idx = media.episodes.indexOfFirst {
        it.id == currentEp.id || (it.seasonNumber == currentEp.seasonNumber && it.episodeNumber == currentEp.episodeNumber)
      }
      if (idx != -1 && idx + 1 < media.episodes.size) {
        media.episodes[idx + 1]
      } else {
        media.episodes.firstOrNull {
          (it.seasonNumber == currentEp.seasonNumber && it.episodeNumber == currentEp.episodeNumber + 1) ||
          (it.seasonNumber == currentEp.seasonNumber + 1 && it.episodeNumber == 1)
        }
      }
    }
  }

  var isBuffering by remember { mutableStateOf(true) }
  var currentPosition by remember { mutableLongStateOf(playbackState.currentPositionMs) }
  var totalDuration by remember { mutableLongStateOf(playbackState.durationMs.coerceAtLeast(1L)) }
  var isPlaying by remember { mutableStateOf(true) }
  var areControlsVisible by remember { mutableStateOf(true) }
  var playbackErrorMessage by remember { mutableStateOf<String?>(null) }
  var hasFallbackAttempted by remember { mutableStateOf(false) }

  // Risoluzione video rilevata in tempo reale da ExoPlayer (es. 1080p, 720p, 4K)
  var detectedResolution by remember { mutableStateOf("") }
  var selectedQualityLabel by remember { mutableStateOf("Auto") }

  // Aspect ratio / ResizeMode
  var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
  var resizeFeedbackText by remember { mutableStateOf<String?>(null) }

  // Velocità di riproduzione
  var currentSpeed by remember { mutableFloatStateOf(1.0f) }

  // Modali
  var showAudioModal by remember { mutableStateOf(false) }
  var showSubtitleModal by remember { mutableStateOf(false) }
  var showQualityModal by remember { mutableStateOf(false) }
  var showSpeedModal by remember { mutableStateOf(false) }

  val anyModalOpen = showAudioModal || showSubtitleModal || showQualityModal || showSpeedModal

  // ---------------------------------------------------------------------------
  // FOCUS D-PAD / TELECOMANDO TV
  // ---------------------------------------------------------------------------
  val playPauseFocusRequester = remember { FocusRequester() }
  val seekBarFocusRequester = remember { FocusRequester() }
  val skipIntroFocusRequester = remember { FocusRequester() }
  val idleFocusRequester = remember { FocusRequester() }
  val focusScope = rememberCoroutineScope()

  var hudHasFocus by remember { mutableStateOf(false) }
  var hideTimerTick by remember { mutableLongStateOf(0L) }

  // BACK gerarchico (livello 1): finché è aperto un modale, il tasto Back chiude SOLO il modale.
  BackHandler(enabled = anyModalOpen) {
    showAudioModal = false
    showSubtitleModal = false
    showQualityModal = false
    showSpeedModal = false
  }

  // Gestione Intro per le serie TV (attivo dai primi secondi fino a 90s, resettato al cambio episodio)
  var isIntroDismissed by remember { mutableStateOf(false) }
  LaunchedEffect(media.id, currentEp?.id) {
    isIntroDismissed = false
  }
  val introEndMs = 90_000L
  val isIntroActive = isTvShow && !isIntroDismissed && currentPosition in 1_000L..introEndMs

  val streamHeaders = playbackState.streamHeaders
  val videoUrl = playbackState.streamUrl ?: currentEp?.videoUrl ?: media.videoUrl

  val httpDataSourceFactory = remember(streamHeaders) {
    val factory = DefaultHttpDataSource.Factory()
      .setUserAgent(streamHeaders["User-Agent"] ?: "StreamNovaTV/1.0 (Linux; Android TV; Media3 ExoPlayer)")
      .setAllowCrossProtocolRedirects(true)
      .setConnectTimeoutMs(15000)
      .setReadTimeoutMs(20000)
    if (streamHeaders.isNotEmpty()) {
      factory.setDefaultRequestProperties(streamHeaders)
    }
    factory
  }

  val mediaSourceFactory = remember(httpDataSourceFactory) {
    DefaultMediaSourceFactory(context)
      .setDataSourceFactory(httpDataSourceFactory)
  }

  val isHlsStream = run {
    val path = videoUrl.substringBefore('?').substringBefore('#').lowercase()
    when {
      path.endsWith(".m3u8") -> true
      path.endsWith(".mp4") || path.endsWith(".mkv") ||
        path.endsWith(".webm") || path.endsWith(".avi") -> false
      playbackState.streamUrl != null -> true
      else -> false
    }
  }

  val playbackMediaSourceFactory = remember(mediaSourceFactory, isHlsStream) {
    if (isHlsStream) {
      HlsMediaSource.Factory(httpDataSourceFactory)
    } else {
      mediaSourceFactory
    }
  }

  // DefaultBandwidthMeter con stima iniziale a 35 Mbps per forzare l'avvio sul profilo a bitrate massimo (1080p FHD)
  val bandwidthMeter = remember {
    DefaultBandwidthMeter.Builder(context)
      .setInitialBitrateEstimate(35_000_000L) // 35 Mbps per forzare l'avvio sul profilo a bitrate massimo
      .build().also {
        Log.i("StreamNovaDiag", "BandwidthMeter inizializzato con stima iniziale: ${it.bitrateEstimate / 1_000_000} Mbps (${it.bitrateEstimate} bps)")
      }
  }

  // TrackSelector configurato per prediligere la risoluzione più alta disponibile senza blocchi sul viewport
  val trackSelector = remember {
    DefaultTrackSelector(context).apply {
      setParameters(
        buildUponParameters()
          .setViewportSize(Int.MAX_VALUE, Int.MAX_VALUE, false)
          .setForceHighestSupportedBitrate(true)
          .setExceedVideoConstraintsIfNecessary(true)
          .setExceedRendererCapabilitiesIfNecessary(true)
      )
    }
  }

  fun updateResolutionFromHeight(h: Int) {
    if (h > 0) {
      detectedResolution = when {
        h >= 2160 -> "4K"
        h >= 1080 -> "1080p"
        h >= 720  -> "720p"
        else      -> "SD"
      }
    }
  }

  // ExoPlayer instance initialization con DefaultTrackSelector e DefaultBandwidthMeter (35 Mbps)
  val exoPlayer = remember(playbackMediaSourceFactory, trackSelector, bandwidthMeter) {
    ExoPlayer.Builder(context)
      .setMediaSourceFactory(playbackMediaSourceFactory)
      .setTrackSelector(trackSelector)
      .setBandwidthMeter(bandwidthMeter)
      .build().apply {
        Log.i("PlayerScreen", "Avvio riproduzione: url=$videoUrl")
        val exoMediaItem = ExoMediaItem.fromUri(Uri.parse(videoUrl))
        setMediaItem(exoMediaItem)
        prepare()
        if (playbackState.currentPositionMs > 0) {
          seekTo(playbackState.currentPositionMs)
        }
        playWhenReady = true
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
          h >= 2160 -> "4K (UHD)"
          h >= 1080 -> "1080p (FHD)"
          h >= 720  -> "720p (HD)"
          h >= 480  -> "480p (SD)"
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

  // Se l'URL cambia dinamicamente (es. click su Prossimo Episodio o Upgrade di qualità a caldo)
  LaunchedEffect(videoUrl) {
    val currentUri = exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString()
    if (currentUri != null && currentUri != videoUrl) {
      Log.i("PlayerScreen", "Upgrade o cambio flusso stream: $videoUrl")
      val resumePos = exoPlayer.currentPosition.takeIf { it > 0 } ?: playbackState.currentPositionMs
      isBuffering = true
      val item = ExoMediaItem.fromUri(Uri.parse(videoUrl))
      exoPlayer.setMediaItem(item)
      exoPlayer.prepare()
      if (resumePos > 0) {
        exoPlayer.seekTo(resumePos)
      }
      exoPlayer.playWhenReady = true
    }
  }

  // Feedback formato video temporaneo
  LaunchedEffect(resizeFeedbackText) {
    if (resizeFeedbackText != null) {
      delay(2000L)
      resizeFeedbackText = null
    }
  }

  // Release player on dispose & handle events
  DisposableEffect(exoPlayer) {
    val listener = object : Player.Listener {
      override fun onPlaybackStateChanged(state: Int) {
        isBuffering = state == Player.STATE_BUFFERING
        if (state == Player.STATE_READY) {
          playbackErrorMessage = null
          totalDuration = exoPlayer.duration.coerceAtLeast(1L)
          val h = exoPlayer.videoFormat?.height ?: exoPlayer.videoSize.height
          updateResolutionFromHeight(h)
        }
      }

      override fun onVideoSizeChanged(videoSize: VideoSize) {
        updateResolutionFromHeight(videoSize.height)
      }

      override fun onTracksChanged(tracks: Tracks) {
        for (group in tracks.groups) {
          if (group.type == C.TRACK_TYPE_VIDEO) {
            Log.i("StreamNovaDiag", "=== TRACCE VIDEO DISPONIBILI (${group.length}) ===")
            for (i in 0 until group.length) {
              val format = group.getTrackFormat(i)
              val selected = group.isTrackSelected(i)
              val supported = group.isTrackSupported(i)
              val kbps = if (format.bitrate > 0) format.bitrate / 1000 else 0
              Log.i("StreamNovaDiag", "Traccia #$i: ${format.width}x${format.height} @ $kbps kbps | Selezionata: $selected | Supportata: $supported")
            }
          }
        }
        val h = exoPlayer.videoFormat?.height ?: exoPlayer.videoSize.height
        updateResolutionFromHeight(h)
      }

      override fun onIsPlayingChanged(playing: Boolean) {
        isPlaying = playing
      }

      override fun onPlayerError(error: PlaybackException) {
        Log.e("PlayerScreen", "ExoPlayer playback error: ${error.message}", error)
        if (!hasFallbackAttempted) {
          hasFallbackAttempted = true
          val fallbackItem = ExoMediaItem.fromUri(Uri.parse(MediaRepository.FALLBACK_VIDEO_URL))
          exoPlayer.setMediaItem(fallbackItem)
          exoPlayer.prepare()
          exoPlayer.playWhenReady = true
        } else {
          isBuffering = false
          playbackErrorMessage = "Impossibile caricare il flusso video (HTTP ${error.errorCodeName}). Verifica la connessione di rete."
        }
      }
    }
    exoPlayer.addListener(listener)

    onDispose {
      val finalPos = exoPlayer.currentPosition
      viewModel.updatePlaybackPosition(finalPos, exoPlayer.duration, exoPlayer.bufferedPosition)
      if (finalPos > 0) {
        MediaRepository.updateProgress(media.id, finalPos)
      }
      exoPlayer.removeListener(listener)
      exoPlayer.release()
    }
  }

  // Polling periodico per la posizione e aggiornamento risoluzione se non ancora agganciata
  LaunchedEffect(exoPlayer, isPlaying) {
    while (true) {
      if (exoPlayer.isPlaying) {
        currentPosition = exoPlayer.currentPosition
        totalDuration = exoPlayer.duration.coerceAtLeast(1L)
        viewModel.updatePlaybackPosition(currentPosition, totalDuration, exoPlayer.bufferedPosition)
        if (detectedResolution == "Auto") {
          val h = exoPlayer.videoFormat?.height ?: exoPlayer.videoSize.height
          updateResolutionFromHeight(h)
        }
      }
      delay(500)
    }
  }

  val hudShown = areControlsVisible || !isPlaying

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

  fun seekBy(deltaMs: Long) {
    val durationMs = exoPlayer.duration
    val target = if (durationMs > 0) {
      (exoPlayer.currentPosition + deltaMs).coerceIn(0L, durationMs)
    } else {
      (exoPlayer.currentPosition + deltaMs).coerceAtLeast(0L)
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

  // (1) Richiesta focus iniziale
  LaunchedEffect(Unit) {
    if (hudShown) {
      playPauseFocusRequester.requestFocusAfterFrame()
    } else {
      idleFocusRequester.requestFocusAfterFrame()
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
    delay(200)
    if (!hudHasFocus) playPauseFocusRequester.requestFocusAfterFrame()
  }

  // BACK su controlli
  BackHandler(enabled = !anyModalOpen) {
    if (hudShown) {
      viewModel.closePlayer()
    } else {
      showControls()
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black)
      .onKeyEvent { keyEvent ->
        val native = keyEvent.nativeKeyEvent
        val keyCode = native.keyCode

        // Gestione BACK telecomando
        if (keyCode == KeyEvent.KEYCODE_BACK) {
          if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) {
            if (anyModalOpen) {
              showAudioModal = false
              showSubtitleModal = false
              showQualityModal = false
              showSpeedModal = false
            } else if (hudShown) {
              viewModel.closePlayer()
            } else {
              showControls()
            }
          }
          return@onKeyEvent true
        }

        if (native.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
          native.repeatCount > 0
        ) {
          return@onKeyEvent false
        }

        // Qualsiasi pressione riavvia l'auto-hide
        when (keyCode) {
          KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
          KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
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
            seekBy(10_000L)
            showControls(focusPrimary = false)
            true
          }

          KeyEvent.KEYCODE_MEDIA_REWIND -> {
            seekBy(-10_000L)
            showControls(focusPrimary = false)
            true
          }

          // OK / Centro D-Pad
          KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> when {
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

          // Frecce Sinistra / Destra
          KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> when {
            !hudShown -> {
              showControls()
              true
            }
            hudHasFocus -> false
            else -> {
              seekBy(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -10_000L else 10_000L)
              requestFocusOn(seekBarFocusRequester)
              true
            }
          }

          // Frecce Su / Giù
          KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> when {
            !hudShown -> {
              showControls()
              true
            }
            hudHasFocus -> false
            else -> {
              showControls()
              true
            }
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
          text = "Formato: ${resizeFeedbackText ?: ""}",
          color = NovaCyanBright,
          fontSize = 14.sp,
          fontWeight = FontWeight.Bold
        )
      }
    }

    // Buffering indicator
    if (isBuffering && playbackErrorMessage == null) {
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
            text = "Caricamento Stream...",
            color = NovaTextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
          )
        }
      }
    }

    // Errore riproduzione
    if (playbackErrorMessage != null) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier
            .widthIn(max = 500.dp)
            .background(NovaSurface, RoundedCornerShape(16.dp))
            .border(1.dp, NovaCyanGlow, RoundedCornerShape(16.dp))
            .padding(32.dp)
        ) {
          Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = "Errore Riproduzione",
            tint = Color(0xFFFF5252),
            modifier = Modifier.size(54.dp)
          )
          Spacer(modifier = Modifier.height(16.dp))
          Text(
            text = "Errore di Riproduzione",
            color = NovaTextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = playbackErrorMessage ?: "",
            color = NovaTextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
          )
          Spacer(modifier = Modifier.height(24.dp))
          Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
          ) {
            TvActionButton(
              text = "Riprova",
              icon = Icons.Default.Refresh,
              isPrimary = true,
              onClick = {
                playbackErrorMessage = null
                isBuffering = true
                hasFallbackAttempted = false
                exoPlayer.setMediaItem(ExoMediaItem.fromUri(Uri.parse(videoUrl)))
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
              }
            )
            TvActionButton(
              text = "Torna al Catalogo",
              icon = Icons.AutoMirrored.Filled.ArrowBack,
              isPrimary = false,
              onClick = { viewModel.closePlayer() }
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
                      else -> false
                    }
                  },
                shape = RoundedCornerShape(50),
                onClick = {
                  kickAutoHide()
                  val target = (currentPosition + 85_000L).coerceAtMost(totalDuration)
                  exoPlayer.seekTo(target)
                  currentPosition = target
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
                    contentDescription = "Salta Intro",
                    tint = if (isFocused) Color.Black else NovaCyanBright,
                    modifier = Modifier.size(18.dp)
                  )
                  Text(
                    text = "SALTA INTRO",
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
                  when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                      seekBy(-10_000L)
                      true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                      seekBy(10_000L)
                      true
                    }
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
                    contentDescription = if (isPlaying) "Pausa" else "Riproduci",
                    tint = Color.Black,
                    modifier = Modifier.size(28.dp)
                  )
                }
              }

              // 2. Prossimo Episodio: icona Skip Next (Visibile SOLO se Serie TV e con episodio successivo)
              if (isTvShow && nextEpisode != null) {
                PlayerControlIconButton(
                  icon = Icons.Default.SkipNext,
                  contentDescription = "Prossimo Episodio",
                  onClick = {
                    kickAutoHide()
                    viewModel.loadStream(media, nextEpisode)
                  }
                )
              }

              // 3. Adatta (Aspect Ratio): icona schermo per commutare ResizeMode (Fit, Zoom, Fill)
              PlayerControlIconButton(
                icon = Icons.Default.AspectRatio,
                contentDescription = "Adatta Aspetto Video",
                onClick = {
                  kickAutoHide()
                  resizeMode = when (resizeMode) {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                  }
                  resizeFeedbackText = when (resizeMode) {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Adatta (Fit)"
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
                    AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Riempi (Fill)"
                    else -> "Adatta"
                  }
                }
              )

              // 4. Qualità Video (Risoluzione): icona HighQuality
              PlayerControlIconButton(
                icon = Icons.Default.HighQuality,
                contentDescription = "Qualità Video",
                onClick = {
                  kickAutoHide()
                  showQualityModal = true
                }
              )

              // 5. Velocità di riproduzione: icona tachimetro (0.75x, 1.0x, 1.25x, 1.5x)
              PlayerControlIconButton(
                icon = Icons.Default.Speed,
                contentDescription = "Velocità di Riproduzione",
                onClick = {
                  kickAutoHide()
                  showSpeedModal = true
                }
              )

              // 6. Sottotitoli: icona CC (Closed Captions)
              PlayerControlIconButton(
                icon = Icons.Default.ClosedCaption,
                contentDescription = "Sottotitoli",
                onClick = {
                  kickAutoHide()
                  showSubtitleModal = true
                }
              )

              // 7. Audio: icona altoparlante
              PlayerControlIconButton(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = "Tracce Audio",
                onClick = {
                  kickAutoHide()
                  showAudioModal = true
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

    // 3. Banner "Salta Intro" fluttuante quando l'HUD è nascosto (per serie TV)
    AnimatedVisibility(
      visible = isIntroActive && !hudShown,
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
        shape = RoundedCornerShape(50),
        onClick = {
          kickAutoHide()
          val target = (currentPosition + 85_000L).coerceAtMost(totalDuration)
          exoPlayer.seekTo(target)
          currentPosition = target
          isIntroDismissed = true
          showControls()
        }
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
            contentDescription = "Salta Intro",
            tint = if (isFocused) Color.Black else NovaCyanBright,
            modifier = Modifier.size(18.dp)
          )
          Text(
            text = "SALTA INTRO",
            color = if (isFocused) Color.Black else Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 0.5.sp
          )
        }
      }
    }

    // Modal Audio
    if (showAudioModal) {
      TrackSelectionDialog(
        title = "Seleziona Traccia Audio",
        options = MediaRepository.audioTracks.map { it.language + " (" + it.format + ")" },
        selectedIndex = MediaRepository.audioTracks.indexOf(playbackState.selectedAudio).coerceAtLeast(0),
        onSelect = { idx ->
          viewModel.setAudioTrack(MediaRepository.audioTracks[idx])
          showAudioModal = false
        },
        onDismiss = { showAudioModal = false }
      )
    }

    // Modal Sottotitoli
    if (showSubtitleModal) {
      TrackSelectionDialog(
        title = "Seleziona Sottotitoli",
        options = MediaRepository.subtitleTracks.map { it.language },
        selectedIndex = MediaRepository.subtitleTracks.indexOf(playbackState.selectedSubtitle).coerceAtLeast(0),
        onSelect = { idx ->
          viewModel.setSubtitleTrack(MediaRepository.subtitleTracks[idx])
          showSubtitleModal = false
        },
        onDismiss = { showSubtitleModal = false }
      )
    }

    // Modal Velocità di Riproduzione
    if (showSpeedModal) {
      val speedOptions = listOf("0.75x", "1.0x (Normale)", "1.25x", "1.5x")
      val speedValues = listOf(0.75f, 1.0f, 1.25f, 1.5f)
      TrackSelectionDialog(
        title = "Velocità di Riproduzione",
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
        title = "Seleziona Risoluzione Video",
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
            val targetWidth = when (targetHeight) {
              2160 -> 3840
              1080 -> 1920
              720 -> 1280
              480 -> 854
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
              targetHeight >= 2160 -> "4K"
              targetHeight >= 1080 -> "1080p"
              targetHeight >= 720  -> "720p"
              else                 -> "SD"
            }
          }
          showQualityModal = false
        },
        onDismiss = { showQualityModal = false }
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

@Composable
fun TrackSelectionDialog(
  title: String,
  options: List<String>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  onDismiss: () -> Unit
) {
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
          contentDescription = "Chiudi",
          tint = NovaTextSecondary,
          modifier = Modifier
            .size(22.dp)
            .clickable { onDismiss() }
        )
      }

      Spacer(modifier = Modifier.height(16.dp))

      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, option ->
          val isSelected = index == selectedIndex
          TvFocusableBox(
            shape = RoundedCornerShape(50),
            onClick = { onSelect(index) },
            modifier = Modifier.fillMaxWidth()
          ) { isFocused ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .background(
                  if (isSelected) NovaCyan.copy(alpha = 0.2f) else if (isFocused) NovaSurfaceVariant else Color.Transparent,
                  RoundedCornerShape(50)
                )
                .border(
                  width = 1.dp,
                  color = if (isFocused) NovaCyanBright else if (isSelected) NovaCyan else Color.Transparent,
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
                  contentDescription = "Selezionato",
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
