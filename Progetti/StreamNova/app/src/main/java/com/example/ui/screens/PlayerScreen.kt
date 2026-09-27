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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.example.data.model.AudioTrack
import com.example.data.model.SubtitleTrack
import com.example.data.model.VideoResolution
import com.example.data.repository.MediaRepository
import com.example.ui.components.QualityBadge
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

  var isBuffering by remember { mutableStateOf(true) }
  var currentPosition by remember { mutableLongStateOf(playbackState.currentPositionMs) }
  var totalDuration by remember { mutableLongStateOf(playbackState.durationMs.coerceAtLeast(1L)) }
  var isPlaying by remember { mutableStateOf(true) }
  var areControlsVisible by remember { mutableStateOf(true) }
  var playbackErrorMessage by remember { mutableStateOf<String?>(null) }
  var hasFallbackAttempted by remember { mutableStateOf(false) }

  // Modal dialog states
  var showAudioModal by remember { mutableStateOf(false) }
  var showSubtitleModal by remember { mutableStateOf(false) }
  var showQualityModal by remember { mutableStateOf(false) }

  // ---------------------------------------------------------------------------
  // FOCUS D-PAD / TELECOMANDO TV
  // ---------------------------------------------------------------------------
  // Controllo primario (Play/Pause): il focus viene richiesto esplicitamente
  // all'avvio del player, così il D-Pad interagisce SEMPRE con l'HUD e mai con
  // Detail/Home rimaste composte sotto la schermata del player.
  val playPauseFocusRequester = remember { FocusRequester() }
  // Seek bar: su di essa atterra il focus quando una freccia ◀▶ sposta la
  // posizione di riproduzione (poi ◀▶ continuano a fare seek senza perdere focus).
  val seekBarFocusRequester = remember { FocusRequester() }
  // Nodo di "riposo" sempre composto: mantiene un owner di focus valido anche
  // quando l'HUD è nascosto, così ogni tasto del telecomando continua ad
  // arrivare agli handler di PlayerScreen invece di perdersi nel vuoto.
  val idleFocusRequester = remember { FocusRequester() }
  val focusScope = rememberCoroutineScope()

  // true quando il focus vive all'interno dell'HUD: in quel caso le frecce
  // navigano tra i controlli, altrimenti (nodo di riposo) gestiscono seek e
  // risveglio dei controlli.
  var hudHasFocus by remember { mutableStateOf(false) }

  // Contatore: ogni interazione col telecomando lo incrementa e riavvia
  // il countdown di auto-hide dei controlli.
  var hideTimerTick by remember { mutableLongStateOf(0L) }

  // BACK gerarchico (livello 1): finché è aperto un pannello modale (audio, sottotitoli,
  // qualità) il tasto Back chiude SOLO il modale. Il player viene chiuso dal gestore
  // di MainActivity solo quando nessun modale è più aperto.
  BackHandler(enabled = showAudioModal || showSubtitleModal || showQualityModal) {
    showAudioModal = false
    showSubtitleModal = false
    showQualityModal = false
  }

  // Gestione Intro per le serie TV
  // Mostra "Salta Intro" nei primi 90 secondi dell'episodio se è presente una sigla
  val introEndMs = 90_000L
  val isIntroActive = episode != null && currentPosition in 3_000L..introEndMs

  // HttpDataSource configured with standard UserAgent and cross-protocol redirects
  val httpDataSourceFactory = remember {
    DefaultHttpDataSource.Factory()
      .setUserAgent("StreamNovaTV/1.0 (Linux; Android TV; Media3 ExoPlayer)")
      .setAllowCrossProtocolRedirects(true)
      .setConnectTimeoutMs(15000)
      .setReadTimeoutMs(20000)
  }

  val mediaSourceFactory = remember(httpDataSourceFactory) {
    DefaultMediaSourceFactory(context)
      .setDataSourceFactory(httpDataSourceFactory)
  }

  // ExoPlayer instance initialization
  val exoPlayer = remember {
    ExoPlayer.Builder(context)
      .setMediaSourceFactory(mediaSourceFactory)
      .build().apply {
        val videoUrl = episode?.videoUrl ?: media.videoUrl
        val exoMediaItem = ExoMediaItem.fromUri(Uri.parse(videoUrl))
        setMediaItem(exoMediaItem)
        prepare()
        if (playbackState.currentPositionMs > 0) {
          seekTo(playbackState.currentPositionMs)
        }
        playWhenReady = true
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
        }
      }

      override fun onIsPlayingChanged(playing: Boolean) {
        isPlaying = playing
      }

      override fun onPlayerError(error: PlaybackException) {
        Log.e("PlayerScreen", "ExoPlayer playback error: ${error.message}", error)
        if (!hasFallbackAttempted) {
          hasFallbackAttempted = true
          // Failover automatico a stream di backup ad alta disponibilità
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
      // Persista l'ultima posizione in Room in ogni percorso di uscita (Back del telecomando incluso),
      // così il ripristino del progresso non dipende solo da closePlayer().
      if (finalPos > 0) {
        MediaRepository.updateProgress(media.id, finalPos)
      }
      exoPlayer.removeListener(listener)
      exoPlayer.release()
    }
  }

  // Periodic polling for playback position
  LaunchedEffect(exoPlayer, isPlaying) {
    while (true) {
      if (exoPlayer.isPlaying) {
        currentPosition = exoPlayer.currentPosition
        totalDuration = exoPlayer.duration.coerceAtLeast(1L)
        viewModel.updatePlaybackPosition(currentPosition, totalDuration, exoPlayer.bufferedPosition)
      }
      delay(500)
    }
  }

  // L'HUD è a schermo se i controlli sono esplicitamente visibili oppure se la
  // riproduzione è in pausa (in pausa i controlli restano sempre mostrati).
  val hudShown = areControlsVisible || !isPlaying

  // --- Helpers focus / telecomando -----------------------------------------

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

  /**
   * Mostra i controlli (risveglio HUD) e, se [focusPrimary], riaggancia il
   * telecomando al Play/Pause: usato da ogni pressione D-Pad quando l'HUD è nascosto.
   */
  fun showControls(focusPrimary: Boolean = true) {
    hideTimerTick++
    areControlsVisible = true
    if (focusPrimary) requestFocusOn(playPauseFocusRequester)
  }

  /** Nasconde i controlli spostando PRIMA il focus sul nodo di riposo. */
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

  // (1) RICHIESTA FOCUS INIZIALE: appena l'HUD è composto e misurato, il D-Pad
  // viene agganciato saldamente al controllo primario (Play/Pause).
  LaunchedEffect(Unit) {
    if (hudShown) {
      playPauseFocusRequester.requestFocusAfterFrame()
    } else {
      idleFocusRequester.requestFocusAfterFrame()
    }
  }

  // (2) AUTO-HIDE dei controlli dopo 5 secondi di inattività. Ogni pressione di
  // un tasto incrementa hideTimerTick e riavvia il countdown. Il focus viene
  // trasferito sul nodo di riposo PRIMA di smontare l'HUD, così nessun tasto va
  // perso quando i controlli spariscono.
  LaunchedEffect(hideTimerTick, areControlsVisible, isPlaying, showAudioModal, showSubtitleModal, showQualityModal) {
    if (areControlsVisible && isPlaying && !showAudioModal && !showSubtitleModal && !showQualityModal) {
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

  // (3) RIPRISTINO DOPO I MODALI: le tracce Audio/Sottotitoli/Qualità vivono in
  // una finestra Dialog separata; alla chiusura, se il focus è andato perso,
  // viene riagganciato al Play/Pause per non bloccare il telecomando.
  val anyTrackModalOpen = showAudioModal || showSubtitleModal || showQualityModal
  LaunchedEffect(anyTrackModalOpen, areControlsVisible) {
    if (anyTrackModalOpen || !areControlsVisible) return@LaunchedEffect
    delay(250)
    if (!hudHasFocus) playPauseFocusRequester.requestFocusAfterFrame()
  }

  // BACK SUI CONTROLLI (livello 1 di questo schermo, sotto il livello modali):
  // con l'HUD nascosto il tasto riapre i controlli e aggancia il focus al
  // Play/Pause; con l'HUD visibile chiude il player. È la STESSA logica del
  // onKeyEvent del Box radice, così il comportamento non dipende dal percorso di
  // dispatch del tasto (KeyEvent in Compose oppure OnBackPressedDispatcher).
  // Compuesto DOPO il BackHandler di MainActivity: mentre il player è aperto ha
  // priorità su di esso, come il livello modali qui sopra.
  BackHandler(enabled = !showAudioModal && !showSubtitleModal && !showQualityModal) {
    if (hudShown) {
      viewModel.closePlayer()
    } else {
      showControls()
    }
  }

  val displayTitle = if (episode != null) {
    "${media.title} • S${episode.seasonNumber}:E${episode.episodeNumber} - ${episode.title}"
  } else {
    media.title
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black)
      // --- INTERCETTAZIONE TASTI TELECOMANDO ---------------------------------
      // Bubbling: arriva qui solo ciò che il controllo focalizzato NON ha
      // consumato (OK sui pulsanti è già gestito dal TvFocusableBox).
      .onKeyEvent { keyEvent ->
        val native = keyEvent.nativeKeyEvent
        val keyCode = native.keyCode

        // BACK viene intercettato su ENTRA le azioni: se il rilascio non venisse
        // consumato, il framework chiamerebbe onBackPressed() e la BackHandler di
        // MainActivity chiuderebbe il player saltando la logica gerarchica.
        if (keyCode == KeyEvent.KEYCODE_BACK) {
          if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) {
            if (showAudioModal || showSubtitleModal || showQualityModal) {
              showAudioModal = false
              showSubtitleModal = false
              showQualityModal = false
            } else if (hudShown) {
              viewModel.closePlayer()
            } else {
              showControls()
            }
          }
          return@onKeyEvent true
        }

        // Le altre azioni scatenano SOLO sulla pressione iniziale (come in
        // TvFocusableBox): altrimenti il rilascio del tasto rifarebbe seek o
        // toggle una seconda volta.
        if (native.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
          native.repeatCount > 0
        ) {
          return@onKeyEvent false
        }

        // Qualsiasi pressione riavvia il timer di auto-hide (5 s di inattività).
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

          // OK / CENTRO
          KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> when {
            // HUD nascosto: risveglia i controlli e aggancia il focus al Play/Pause
            !hudShown -> {
              showControls()
              true
            }
            // Nessun controllo focalizzato: OK funziona come play/pause globale
            !hudHasFocus -> {
              togglePlayPause()
              true
            }
            // Il controllo focalizzato (TvFocusableBox) gestisce già OK
            else -> false
          }

          // Frecce: con il focus dentro l'HUD navigano tra i controlli (non
          // vengono consumate qui, lasciate al sistema di focus); con il focus
          // fuori fanno seek rapido e spostano il focus sulla seek bar.
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
        // Tap sullo schermo: alterna i controlli (quando ricompaiono, il focus
        // viene riagganciato al Play/Pause)
        if (areControlsVisible) hideControls() else showControls()
      }
  ) {
    // NODO DI RIPOSO: 1dp invisibile e sempre composto. Diventa l'owner di focus
    // quando l'HUD si nasconde, così ogni tasto D-Pad continua ad arrivare ai
    // gestori di questo Box (bubbling) invece di perdersi nel vuoto.
    Box(
      modifier = Modifier
        .size(1.dp)
        .focusRequester(idleFocusRequester)
        .focusable()
    )

    // 1. AndroidView holding PlayerView
    AndroidView(
      factory = { ctx ->
        PlayerView(ctx).apply {
          player = exoPlayer
          useController = false
          // La vista video pura NON deve sottrarre il focus ai controlli Compose:
          // né la superficie né i figli devono intercettare i tasti del D-Pad.
          isFocusable = false
          isFocusableInTouchMode = false
          descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
          isClickable = false
          layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
          )
        }
      },
      modifier = Modifier.fillMaxSize()
    )

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
            text = "Buffering Stream 4K HDR...",
            color = NovaTextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
          )
        }
      }
    }

    // Playback Error Overlay with TV retry affordance
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
                val videoUrl = episode?.videoUrl ?: media.videoUrl
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

    // 2. Overlay Controlli Cinematografici
    AnimatedVisibility(
      visible = areControlsVisible || !isPlaying,
      enter = fadeIn(),
      exit = fadeOut(),
      modifier = Modifier.fillMaxSize()
    ) {
      Box(
        modifier = Modifier
          // Traccia se il focus vive all'interno dell'HUD (anche sui discendenti):
          // da qui dipende la mappa delle frecce (navigazione vs. seek/risveglio).
          .onFocusChanged { state -> hudHasFocus = state.hasFocus }
          .fillMaxSize()
          // Ogni tasto premuto mentre l'HUD è aperto riavvia l'auto-hide.
          // L'evento NON viene consumato, per non interferire con il controllo
          // focalizzato che lo gestirà dopo (bubbling verso il Box radice).
          .onPreviewKeyEvent {
            kickAutoHide()
            false
          }
          .background(
            Brush.verticalGradient(
              colors = listOf(
                Color.Black.copy(alpha = 0.85f),
                Color.Transparent,
                Color.Black.copy(alpha = 0.92f)
              )
            )
          )
      ) {
        // TOP BAR: Pulsante Indietro + Titolo in alto a sinistra
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.TopCenter)
            .padding(horizontal = 32.dp, vertical = 24.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          TvFocusableBox(
            shape = CircleShape,
            onClick = { viewModel.closePlayer() }
          ) { isFocused ->
            Box(
              modifier = Modifier
                .size(44.dp)
                .background(
                  if (isFocused) NovaCyan else Color.Black.copy(alpha = 0.6f),
                  CircleShape
                ),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Esci dal player",
                tint = if (isFocused) Color.Black else Color.White,
                modifier = Modifier.size(24.dp)
              )
            }
          }

          Spacer(modifier = Modifier.width(16.dp))

          Column {
            Text(
              text = displayTitle,
              color = NovaTextPrimary,
              fontSize = 18.sp,
              fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              QualityBadge(text = playbackState.selectedResolution.badge, isHighlighted = true)
              Text(
                text = "• ${playbackState.selectedAudio.language} (${playbackState.selectedAudio.format})",
                color = NovaCyanBright,
                fontSize = 12.sp
              )
              if (playbackState.selectedSubtitle.id != "off") {
                Text(
                  text = "• Sub: ${playbackState.selectedSubtitle.language}",
                  color = NovaTextSecondary,
                  fontSize = 12.sp
                )
              }
            }
          }
        }

        // CONTROLLI CENTRALI: -10s, PAUSA/PLAY GRANDE, +10s
        Row(
          modifier = Modifier.align(Alignment.Center),
          horizontalArrangement = Arrangement.spacedBy(36.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          // -10s
          TvFocusableBox(
            shape = CircleShape,
            onClick = { seekBy(-10_000L) }
          ) { isFocused ->
            Box(
              modifier = Modifier
                .size(54.dp)
                .background(
                  if (isFocused) NovaCyan else Color.Black.copy(alpha = 0.6f),
                  CircleShape
                ),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Default.Replay10,
                contentDescription = "Riavvolgi 10 secondi",
                tint = if (isFocused) Color.Black else Color.White,
                modifier = Modifier.size(32.dp)
              )
            }
          }

          // PLAY / PAUSE (Centrale Grande) — controllo primario del player:
          // su questo elemento viene agganciato il focus D-Pad all'avvio e a ogni
          // risveglio dell'HUD.
          TvFocusableBox(
            modifier = Modifier.focusRequester(playPauseFocusRequester),
            shape = CircleShape,
            focusedScale = 1.15f,
            onClick = { togglePlayPause() }
          ) { isFocused ->
            Box(
              modifier = Modifier
                .size(80.dp)
                .shadow(
                  elevation = if (isFocused) 20.dp else 8.dp,
                  shape = CircleShape,
                  spotColor = NovaCyanBright
                )
                .background(
                  brush = if (isFocused) {
                    Brush.radialGradient(listOf(NovaCyanBright, NovaCyan))
                  } else {
                    Brush.radialGradient(listOf(Color(0xFF202A3C), Color(0xFF0F1522)))
                  },
                  shape = CircleShape
                )
                .border(
                  width = 2.dp,
                  color = if (isFocused) NovaCyanBright else NovaCyan.copy(alpha = 0.5f),
                  shape = CircleShape
                ),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pausa" else "Riproduci",
                tint = if (isFocused) Color.Black else NovaCyanBright,
                modifier = Modifier.size(46.dp)
              )
            }
          }

          // +10s
          TvFocusableBox(
            shape = CircleShape,
            onClick = { seekBy(10_000L) }
          ) { isFocused ->
            Box(
              modifier = Modifier
                .size(54.dp)
                .background(
                  if (isFocused) NovaCyan else Color.Black.copy(alpha = 0.6f),
                  CircleShape
                ),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Default.Forward10,
                contentDescription = "Avanza 10 secondi",
                tint = if (isFocused) Color.Black else Color.White,
                modifier = Modifier.size(32.dp)
              )
            }
          }
        }

        // BOTTOM BAR: Barra di riproduzione azzurra + tempo + selettori Audio/Sub/Qualità
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .padding(horizontal = 32.dp, vertical = 20.dp)
        ) {
          // Progress Bar azzurra e minutaggio
          val progressFraction = if (totalDuration > 0) {
            (currentPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
          } else 0f

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = formatTimeMs(currentPosition),
              color = NovaCyanBright,
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = formatTimeMs(totalDuration),
              color = NovaTextSecondary,
              fontSize = 13.sp,
              fontWeight = FontWeight.Medium
            )
          }

          Spacer(modifier = Modifier.height(6.dp))

          // SEEK BAR — componente focusabile dell'HUD: quando il focus è qui le
          // frecce ◀▶ spostano la posizione di 10 secondi; quando il focus è
          // altrove (nodo di riposo) le stesse frecce fanno seek rapido e
          // portano il focus proprio su questa barra.
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .onKeyEvent { keyEvent ->
                val native = keyEvent.nativeKeyEvent
                // Solo sulla pressione: altrimenti il rilascio rifarebbe il seek
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
                  else -> false
                }
              }
          ) {
            TvFocusableBox(
              modifier = Modifier
                .fillMaxWidth()
                .focusRequester(seekBarFocusRequester),
              shape = RoundedCornerShape(6.dp),
              focusedScale = 1.0f
            ) { _ ->
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(vertical = 4.dp)
              ) {
                // Barra azzurra personalizzata
                Box(
                  modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x551E293B))
                ) {
                  // Buffer indicator
                  val bufferFraction = if (totalDuration > 0) {
                    (exoPlayer.bufferedPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
                  } else 0f
                  Box(
                    modifier = Modifier
                      .fillMaxWidth(bufferFraction)
                      .height(8.dp)
                      .background(Color(0x3300A3FF))
                  )
                  // Playback progress indicator (Azzurro / Ciano neon)
                  Box(
                    modifier = Modifier
                      .fillMaxWidth(progressFraction)
                      .height(8.dp)
                      .background(
                        Brush.horizontalGradient(
                          listOf(NovaCyan, NovaCyanBright)
                        )
                      )
                  )
                }
              }
            }
          }

          Spacer(modifier = Modifier.height(16.dp))

          // Action buttons row: Audio, Sottotitoli, Qualità, Impostazioni
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
              PlayerBottomAction(
                label = "Audio: ${playbackState.selectedAudio.language}",
                icon = Icons.Default.Audiotrack,
                onClick = {
                  kickAutoHide()
                  showAudioModal = true
                }
              )
              PlayerBottomAction(
                label = "Sottotitoli: ${playbackState.selectedSubtitle.language}",
                icon = Icons.Default.Subtitles,
                onClick = {
                  kickAutoHide()
                  showSubtitleModal = true
                }
              )
              PlayerBottomAction(
                label = "Qualità: ${playbackState.selectedResolution.badge}",
                icon = Icons.Default.HighQuality,
                onClick = {
                  kickAutoHide()
                  showQualityModal = true
                }
              )
            }

            Text(
              text = "Frecce ◀ ▶ per spostarti tra i controlli • OK per Pausa",
              color = NovaTextMuted,
              fontSize = 11.sp
            )
          }
        }
      }
    }

    // 3. Banner "Salta Intro" posizionato in basso a destra dello schermo (per serie TV)
    AnimatedVisibility(
      visible = isIntroActive,
      enter = fadeIn() + slideInHorizontally(initialOffsetX = { it }),
      exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it }),
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(
          end = 40.dp,
          bottom = if (areControlsVisible) 110.dp else 40.dp
        )
    ) {
      TvFocusableBox(
        shape = RoundedCornerShape(50),
        onClick = {
          // Salta direttamente alla fine della sigla
          kickAutoHide()
          exoPlayer.seekTo(introEndMs)
          currentPosition = introEndMs
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

    // Modal Qualità
    if (showQualityModal) {
      val resolutions = VideoResolution.values()
      TrackSelectionDialog(
        title = "Seleziona Risoluzione Video",
        options = resolutions.map { it.label },
        selectedIndex = resolutions.indexOf(playbackState.selectedResolution).coerceAtLeast(0),
        onSelect = { idx ->
          viewModel.setResolution(resolutions[idx])
          showQualityModal = false
        },
        onDismiss = { showQualityModal = false }
      )
    }
  }
}

@Composable
fun PlayerBottomAction(
  label: String,
  icon: ImageVector,
  onClick: () -> Unit
) {
  TvFocusableBox(
    shape = RoundedCornerShape(50),
    focusedScale = 1.05f,
    onClick = onClick
  ) { isFocused ->
    Row(
      modifier = Modifier
        .background(
          if (isFocused) Brush.horizontalGradient(listOf(NovaCyan, NovaCyanBright))
          else Brush.horizontalGradient(listOf(Color(0xFF1E2638).copy(alpha = 0.9f), Color(0xFF161E30).copy(alpha = 0.9f))),
          RoundedCornerShape(50)
        )
        .border(
          width = if (isFocused) 1.5.dp else 1.dp,
          color = if (isFocused) NovaCyanBright else Color(0x44475569),
          shape = RoundedCornerShape(50)
        )
        .padding(horizontal = 16.dp, vertical = 9.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (isFocused) Color.Black else NovaCyanBright,
        modifier = Modifier.size(16.dp)
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        text = label,
        color = if (isFocused) Color.Black else NovaTextPrimary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold
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

private fun formatTimeMs(ms: Long): String {
  val totalSeconds = (ms / 1000).toInt()
  val hours = totalSeconds / 3600
  val minutes = (totalSeconds % 3600) / 60
  val seconds = totalSeconds % 60
  return if (hours > 0) {
    "%02d:%02d:%02d".format(hours, minutes, seconds)
  } else {
    "%02d:%02d".format(minutes, seconds)
  }
}
