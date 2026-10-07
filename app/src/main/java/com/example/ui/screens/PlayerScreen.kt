package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.data.prefs.DecoderFallbackMode
import com.example.ui.screens.player.*
import androidx.media3.datasource.HttpDataSource
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
import androidx.compose.ui.window.Dialog
import com.example.R
import androidx.media3.common.C
import androidx.media3.common.PlaybackException as ExoPlaybackException
import androidx.media3.common.Player
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
import com.example.ui.components.TvActionButton
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Auto-hide dei controlli del player dopo N ms di inattività con il telecomando. */
private const val CONTROLS_HIDE_DELAY_MS = 5_000L

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

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
  viewModel: StreamNovaViewModel,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val playbackState by viewModel.playbackState.collectAsState()
  val playbackSettings by viewModel.playbackSettings.collectAsState()
  val showSourceDialog by viewModel.showSourceDialog.collectAsState()
  val availableSources by viewModel.availableSources.collectAsState()
  val media = playbackState.media ?: return
  val episode = playbackState.currentEpisode

  val isSourceSelectionActive = showSourceDialog && availableSources.isNotEmpty()

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

  // Traccia manuale selezionata dall'utente: quando non null, indica che l'utente ha
  // fatto una scelta esplicita dal menu Player. Questo previene la ri-applicazione
  // automatica della preferenza Settings dopo una selezione manuale.
  var manualSubtitleSelection by remember { mutableStateOf<ManualSubtitleSelection?>(null) }
  var manualAudioSelection by remember { mutableStateOf<ManualAudioSelection?>(null) }

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
    Log.d("BACK_TRACE", "PLAYERSCREEN: ingresso del BackHandler (modale)")
    Log.d("BACK_TRACE", "PLAYERSCREEN: valore di anyModalOpen: $anyModalOpen")
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
  val videoUrl: String? = playbackState.streamUrl?.takeIf { it.isNotBlank() }
    ?: if (MediaRepository.isDemoSeedItem(media.id)) {
      currentEp?.videoUrl ?: media.videoUrl
    } else {
      null
    }

  if (media.tmdbId == 318508) {
    Log.d("THE_PITT_PLAYER", "PlayerScreen: media.tmdbId=318508, videoUrl=$videoUrl, streamUrl=${playbackState.streamUrl}, isDemoSeed=${MediaRepository.isDemoSeedItem(media.id)}")
  }

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

  // Sottotitoli esterni della sorgente attiva: vengono solo allegati al Media3
  // MediaItem, senza toccare la factory video (che resta l'unica DataSource.Factory
  // e quindi serve anche alle sidecar, come già avviene per le altre app Media3).
  val stremioSubtitles = playbackState.subtitles

  val mediaSourceFactory = remember(httpDataSourceFactory) {
    DefaultMediaSourceFactory(context)
      .setDataSourceFactory(httpDataSourceFactory)
      .setLoadErrorHandlingPolicy(PlayerLoadErrorHandlingPolicy())
  }

  val isHlsStream = run {
    val url = videoUrl
    if (url == null) {
      false
    } else {
      val lower = url.lowercase()
      val path = url.substringBefore('?').substringBefore('#').lowercase()
      val hint = playbackState.streamProgressive
      when {
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

  // Nuvio TrackSelector e ExoPlayer configurati con NuvioRenderersFactory, BitrateAwareLoadControl e Decoder Fallback
  val (trackSelector, exoPlayer) = remember(
    playbackMediaSourceFactory,
    bandwidthMeter,
    playbackSettings.preferredResolution,
    playbackSettings.targetBuffer,
    playbackSettings.initialBuffer,
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
      dolbyVisionFallbackEnabled = playbackSettings.dolbyVisionFallbackEnabled
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
      if (maxHeight > 0) {
        val maxWidth = when {
          maxHeight >= 2160 -> 3840
          maxHeight >= 1080 -> 1920
          maxHeight >= 720 -> 1280
          maxHeight >= 480 -> 854
          else -> (maxHeight * 16) / 9
        }
        setParameters(buildUponParameters().setMaxVideoSize(maxWidth, maxHeight))
        StreamNova4KDiag.line(
          "VINCOLO INIZIALE maxVideoSize=${maxWidth}x$maxHeight " +
            "(preferredResolution=${playbackSettings.preferredResolution.label})"
        )
      } else {
        StreamNova4KDiag.line(
          "VINCOLO INIZIALE maxVideoSize=nessuno " +
            "(preferredResolution=${playbackSettings.preferredResolution.label})"
        )
      }
    }
    val loadControl = NuvioExoPlayerPerformanceHelper.buildLoadControl(
      context = context,
      targetBufferSizeMbOverride = playbackSettings.targetBuffer.sizeMb,
      bufferForPlaybackMsOverride = playbackSettings.initialBuffer.durationMs,
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
        if (videoUrl != null) {
          Log.i("PlayerScreen", "Avvio riproduzione NuvioEngine: url=$videoUrl")
          stop()
          clearMediaItems()
          val exoMediaItem = PlayerSubtitleMediaItemBuilder.buildMediaItem(videoUrl, stremioSubtitles)
          setMediaItem(exoMediaItem)
          prepare()
          if (playbackState.currentPositionMs > 0) {
            seekTo(playbackState.currentPositionMs)
          }
          playWhenReady = true
        } else {
          Log.i("PlayerScreen", "Nessuna sorgente disponibile per '${media.title}': nessun fallback demo.")
        }
      }
    trackSelector to player
  }

  var currentTracksState by remember(exoPlayer) { mutableStateOf(exoPlayer.currentTracks) }

  fun formatAudioTrackDisplayName(
    formatLanguage: String?,
    formatLabel: String?,
    roleFlags: Int,
    channels: Int = -1
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

    if (channels >= 6) {
      extra.add("5.1")
    }

    val labelClean = formatLabel?.trim() ?: ""
    val isRedundantLabel = labelClean.isBlank() ||
        labelClean.equals(baseLang, ignoreCase = true) ||
        labelClean.equals("und", ignoreCase = true) ||
        labelClean.equals("default", ignoreCase = true) ||
        labelClean.equals("main", ignoreCase = true)

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
          val name = formatAudioTrackDisplayName(lang, label, roleFlags, format.channelCount)
          options.add(
            MediaAudioTrackOption(
              group = group,
              trackIndex = i,
              language = lang,
              label = label,
              roleFlags = roleFlags,
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
        labelClean.equals("main", ignoreCase = true)

    val forcedText = context.getString(R.string.track_flag_forced)
    val sdhText = context.getString(R.string.track_flag_sdh)
    return when {
      isForced -> {
        if (baseLang.contains(forcedText, ignoreCase = true) ||
            baseLang.contains("forced", ignoreCase = true) ||
            baseLang.contains("forzat", ignoreCase = true)
        ) {
          baseLang
        } else {
          "$baseLang ($forcedText)"
        }
      }
      isCC -> "$baseLang ($sdhText)"
      !isRedundantLabel -> "$baseLang - $labelClean"
      else -> baseLang
    }
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

  // Sorgente assente per un titolo TMDB: mostra lo stato di errore, SENZA avviare video demo.
  // La sessione viene marcata come fallita: nessun auto-next da errore di risoluzione.
  // Ma se siamo in modalita' selezione sorgente (autoplay disattivato), NON e' un errore:
  // l'utente sta scegliendo la sorgente e il player aspettara' l'URL dopo la selezione.
  LaunchedEffect(videoUrl) {
    if (videoUrl == null) {
      if (!isSourceSelectionActive) {
        isBuffering = false
        playbackEnded = false
        playbackFailed = true
        playbackErrorMessage = streamUnavailableMessage(isTvShow, currentEp)
      }
    } else {
      playbackErrorMessage = null
    }
  }

  // Se l'URL o l'episodio cambiano dinamicamente (es. click su Prossimo Episodio o Upgrade di qualita' a caldo)
  // oppure quando l'URL diventa disponibile dopo la selezione della sorgente TorBox.
  LaunchedEffect(currentEpKey, videoUrl) {
    val url = videoUrl ?: return@LaunchedEffect
    val isEpisodeChange = currentEpKey != lastLoadedEpKey
    val currentUri = exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString()
    val isUrlChange = currentUri == null || currentUri != url

    if (isEpisodeChange || isUrlChange) {
      val targetPos = if (isEpisodeChange) {
        lastLoadedEpKey = currentEpKey
        playbackState.currentPositionMs
      } else {
        exoPlayer.currentPosition.takeIf { it > 0 } ?: playbackState.currentPositionMs
      }

      Log.i("PlayerScreen", "Caricamento stream: url=$url, isEpisodeChange=$isEpisodeChange, pos=$targetPos")
      isBuffering = true
      playbackFailed = false
      currentPosition = targetPos

      // Ferma e ripulisce la vecchia istanza di ExoPlayer per evitare STATE_BUFFERING fantasma
      exoPlayer.stop()
      exoPlayer.clearMediaItems()

      val item = PlayerSubtitleMediaItemBuilder.buildMediaItem(url, stremioSubtitles)
      exoPlayer.setMediaItem(item)
      exoPlayer.prepare()
      if (targetPos > 0) {
        exoPlayer.seekTo(targetPos)
      } else {
        exoPlayer.seekTo(0L)
      }
      exoPlayer.playWhenReady = true
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

  // "Riproduci automaticamente il prossimo episodio": a fine episodio, se l'opzione
  // è ON e esiste un episodio successivo, passa automaticamente al successivo.
  // Il nuovo episodio riparte dal proprio progresso (o da 0), mai da quello precedente.
  // Solo conclusione NORMALE (STATE_ENDED senza errori): una sessione fallita non
  // deve mai generare auto-next.
  LaunchedEffect(playbackEnded, currentEpKey) {
    if (playbackEnded && !playbackFailed && isTvShow && nextEpisode != null && playbackSettings.autoPlayNextEpisode) {
      playbackEnded = false
      Log.i("PlayerScreen", "Auto-advance episodio: fine E${currentEp?.episodeNumber}, prossimo E${nextEpisode.episodeNumber}")
      viewModel.saveCurrentPlaybackProgress(exoPlayer.duration.coerceAtLeast(exoPlayer.currentPosition))
      viewModel.playNextEpisode(nextEpisode)
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
    var subtitleErrorRecoveries = 0
    val listener = object : Player.Listener {
      override fun onPlaybackStateChanged(state: Int) {
        isBuffering = state == Player.STATE_BUFFERING
        if (state == Player.STATE_READY) {
          playbackErrorMessage = null
          playbackFailed = false
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
        } else if (state == Player.STATE_ENDED) {
          isBuffering = false
          // Conclusione normale SOLO se la sessione non è fallita e la riproduzione
          // ha effettivamente consumato contenuto (no ENDED istantaneo da stream vuoto/rotto).
          if (!playbackFailed && exoPlayer.currentPosition > 0L && exoPlayer.duration > 0L) {
            playbackEnded = true
          }
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
      }

      override fun onPlayerError(error: ExoPlaybackException) {
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

        isBuffering = false
        playbackEnded = false
        playbackFailed = true
        playbackErrorMessage = context.getString(R.string.player_playback_error_message, displayMsg)
      }
    }
    exoPlayer.addListener(listener)
    // --- DIAGNOSTICA 4K (solo log): decoder video, input format, codec error, primo frame ---
    val diagAnalyticsListener = StreamNova4KDiag.createAnalyticsListener()
    exoPlayer.addAnalyticsListener(diagAnalyticsListener)

    onDispose {
      if (playbackSettings.autoFrameRateMatching) {
        val act = context as? Activity
        FrameRateUtils.resetDisplayMode(act)
      }
      val finalPos = exoPlayer.currentPosition
      viewModel.updatePlaybackPosition(finalPos, exoPlayer.duration, exoPlayer.bufferedPosition)
      if (finalPos > 0) {
        viewModel.saveCurrentPlaybackProgress(finalPos)
      }
      viewModel.resetStreamState()
      exoPlayer.removeListener(listener)
      exoPlayer.removeAnalyticsListener(diagAnalyticsListener)
      exoPlayer.stop()
      exoPlayer.clearMediaItems()
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

  LaunchedEffect(exoPlayer, isPlaying) {
    while (true) {
      if (exoPlayer.isPlaying) {
        currentPosition = exoPlayer.currentPosition
        totalDuration = exoPlayer.duration.coerceAtLeast(1L)
        viewModel.updatePlaybackPosition(currentPosition, totalDuration, exoPlayer.bufferedPosition)
        if (selectedQualityLabelRef == "Auto" && detectedResolutionRef.isEmpty()) {
          val h = exoPlayer.videoFormat?.height ?: exoPlayer.videoSize.height
          val w = exoPlayer.videoFormat?.width ?: exoPlayer.videoSize.width
          updateResolutionFromHeight(h, w)
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
    Log.d("BACK_TRACE", "PLAYERSCREEN: ingresso del BackHandler")
    Log.d("BACK_TRACE", "PLAYERSCREEN: valore di anyModalOpen: $anyModalOpen")
    Log.d("BACK_TRACE", "PLAYERSCREEN: chiamata a closePlayer()")
    viewModel.resetStreamState()
    viewModel.closePlayer()
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
            Log.d("BACK_TRACE", "PLAYERSCREEN: onKeyEvent KEYCODE_BACK down, anyModalOpen=$anyModalOpen")
            if (anyModalOpen) {
              showAudioModal = false
              showSubtitleModal = false
              showQualityModal = false
              showSpeedModal = false
            } else {
              Log.d("BACK_TRACE", "PLAYERSCREEN: onKeyEvent chiamata a closePlayer()")
              viewModel.resetStreamState()
              viewModel.closePlayer()
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
            text = stringResource(R.string.player_loading_stream),
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
            contentDescription = stringResource(R.string.player_cd_playback_error),
            tint = Color(0xFFFF5252),
            modifier = Modifier.size(54.dp)
          )
          Spacer(modifier = Modifier.height(16.dp))
          Text(
            text = stringResource(R.string.player_playback_error_title),
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
            // "Riprova" solo se esiste una sorgente riproducibile (nessun fallback demo)
            if (videoUrl != null) {
              TvActionButton(
                text = stringResource(R.string.action_retry),
                icon = Icons.Default.Refresh,
                isPrimary = true,
                onClick = {
                  playbackErrorMessage = null
                  isBuffering = true
                  exoPlayer.setMediaItem(
                    PlayerSubtitleMediaItemBuilder.buildMediaItem(videoUrl, stremioSubtitles)
                  )
                  exoPlayer.prepare()
                  exoPlayer.playWhenReady = true
                }
              )
            }
            TvActionButton(
              text = stringResource(R.string.player_back_to_catalog),
              icon = Icons.AutoMirrored.Filled.ArrowBack,
              isPrimary = false,
              onClick = {
                viewModel.resetStreamState()
                viewModel.closePlayer()
              }
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
                    contentDescription = if (isPlaying) stringResource(R.string.player_cd_pause) else stringResource(R.string.player_cd_play),
                    tint = Color.Black,
                    modifier = Modifier.size(28.dp)
                  )
                }
              }

              // 2. Prossimo Episodio: icona Skip Next (Visibile SOLO se Serie TV e con episodio successivo)
              if (isTvShow && nextEpisode != null) {
                PlayerControlIconButton(
                  icon = Icons.Default.SkipNext,
                  contentDescription = stringResource(R.string.player_cd_next_episode),
                  onClick = {
                    kickAutoHide()
                    viewModel.saveCurrentPlaybackProgress(exoPlayer.currentPosition)
                    viewModel.playNextEpisode(nextEpisode)
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
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = stringResource(R.string.player_cd_audio_tracks),
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

    // Modal Audio dinamico con tracce Media3 reali
    if (showAudioModal) {
      val audioOptions = getAvailableAudioOptions(currentTracksState)
      if (audioOptions.isNotEmpty()) {
        val selectedIdx = audioOptions.indexOfFirst { it.isSelected }.takeIf { it >= 0 } ?: 0
        TrackSelectionDialog(
          title = stringResource(R.string.player_audio_dialog_title),
          options = audioOptions.map { it.displayName },
          selectedIndex = selectedIdx,
          onSelect = { idx ->
            val selectedOption = audioOptions.getOrNull(idx)
            if (selectedOption != null) {
              Log.i("PlayerScreen", "Selezione traccia audio Media3: ${selectedOption.displayName} (trackIndex=${selectedOption.trackIndex}, lang=${selectedOption.language})")
              manualAudioSelection = ManualAudioSelection(selectedOption.group, selectedOption.trackIndex)
              PlayerRuntimeControllerTrackSelection.selectAudioTrack(exoPlayer, idx)
            }
            showAudioModal = false
          },
          onDismiss = { showAudioModal = false }
        )
      } else {
        TrackSelectionDialog(
          title = stringResource(R.string.player_audio_dialog_title),
          options = listOf(stringResource(R.string.player_audio_default)),
          selectedIndex = 0,
          onSelect = { showAudioModal = false },
          onDismiss = { showAudioModal = false }
        )
      }
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
      TrackSelectionDialog(
        title = stringResource(R.string.player_subtitles_dialog_title),
        options = allOptions,
        selectedIndex = selectedIndex,
        headerSwitchLabel = stringResource(R.string.settings_forced_subtitles),
        headerSwitchChecked = playbackSettings.forcedSubtitlesEnabled,
        onHeaderSwitchToggle = { enabled ->
          viewModel.setForcedSubtitlesEnabled(enabled)
          manualSubtitleSelection = null
        },
        onSelect = { idx ->
          if (idx == 0) {
            manualSubtitleSelection = ManualSubtitleSelection(null, -1)
            PlayerRuntimeControllerTrackSelection.disableSubtitles(exoPlayer)
            Log.i("PlayerScreen", "Sottotitoli disabilitati manualmente (Nessuno)")
          } else {
            val selectedOption = subtitleOptions.getOrNull(idx - 1)
            if (selectedOption != null) {
              manualSubtitleSelection = ManualSubtitleSelection(selectedOption.group, selectedOption.trackIndex)
              PlayerRuntimeControllerTrackSelection.selectSubtitleTrack(exoPlayer, idx - 1)
              Log.i("PlayerScreen", "Selezione traccia sottotitolo Media3: ${selectedOption.displayName} (trackIndex=${selectedOption.trackIndex}, lang=${selectedOption.language})")
            }
          }
          showSubtitleModal = false
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
          modifier = Modifier.fillMaxWidth()
        ) { isFocused ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .background(
                if (isFocused) NovaSurfaceVariant else NovaSurface.copy(alpha = 0.6f),
                RoundedCornerShape(12.dp)
              )
              .border(
                width = 1.dp,
                color = if (isFocused) NovaCyanBright else Color.White.copy(alpha = 0.12f),
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

private fun mapPreferredAudioLanguages(code: String): List<String> = when (code.trim().lowercase()) {
  "it" -> listOf("it", "ita")
  "en" -> listOf("en", "eng")
  "es" -> listOf("es", "spa")
  "fr" -> listOf("fr", "fra")
  "de" -> listOf("de", "deu", "ger")
  else -> emptyList()
}
