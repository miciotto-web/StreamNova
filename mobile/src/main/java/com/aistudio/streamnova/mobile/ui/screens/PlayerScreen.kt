package com.aistudio.streamnova.mobile.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.aistudio.streamnova.mobile.BuildConfig
import com.aistudio.streamnova.mobile.R
import com.aistudio.streamnova.mobile.ui.MobilePlayerUiState
import com.aistudio.streamnova.mobile.ui.MobilePlayerViewModel
import com.example.data.prefs.DecoderFallbackMode
import com.example.data.prefs.PlaybackSettings
import com.example.data.prefs.SettingsRepository
import com.example.data.repository.MediaRepository
import com.example.ui.screens.player.NuvioRenderersFactory
import com.example.ui.screens.player.PlayerLoadErrorHandlingPolicy
import com.example.ui.screens.player.PlayerSubtitleMediaItemBuilder
import com.example.ui.screens.player.buildPlayerLoadControl
import com.example.ui.screens.player.resolvePlaybackMediaSourceFactory
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Player mobile: PlayerView Media3 con controlli touch nativi e motore condiviso
 * (`:core:player`) per factory HLS/progressiva, policy errori, renderer e buffer.
 * Il lifecycle è gestito esplicitamente: pausa in ON_STOP, rilascio in dispose.
 */
@Composable
@UnstableApi
fun MobilePlayerScreen(
  mediaId: String,
  season: Int?,
  episode: Int?,
  onBack: () -> Unit
) {
  BackHandler(onBack = onBack)
  val viewModel: MobilePlayerViewModel = viewModel()
  val state by viewModel.state.collectAsStateWithLifecycle()
  val settings by SettingsRepository.settings.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val activity = context.findActivity()

  LaunchedEffect(mediaId, season, episode) {
    viewModel.prepare(mediaId, season, episode)
  }

  // Il player mobile è pensato per il video: landscape finché la schermata è attiva.
  DisposableEffect(activity) {
    val previous = activity?.requestedOrientation
    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    onDispose {
      activity?.requestedOrientation = previous ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
  }

  Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
    when {
      state.loading -> {
        Column(
          modifier = Modifier.fillMaxSize(),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center
        ) {
          CircularProgressIndicator(color = NovaCyan)
          Spacer(modifier = Modifier.height(12.dp))
          Text(
            text = stringResource(R.string.player_loading),
            color = NovaTextMuted,
            fontSize = 14.sp
          )
        }
      }

      state.error != null -> {
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
            onClick = { viewModel.prepare(mediaId, season, episode) },
            colors = ButtonDefaults.buttonColors(
              containerColor = NovaCyan,
              contentColor = Color.Black
            )
          ) {
            Text(stringResource(R.string.action_retry), fontWeight = FontWeight.Bold)
          }
        }
      }

      state.source != null -> {
        MobilePlayerSurface(
          state = state,
          settings = settings,
          onProgress = viewModel::saveProgress
        )
      }
    }

    PlayerOverlay(
      title = state.media?.title.orEmpty(),
      subtitle = state.episode?.let {
        "S${it.seasonNumber}E${it.episodeNumber} • ${it.title}"
      },
      onBack = onBack,
      modifier = Modifier.align(Alignment.TopStart)
    )
  }
}

@Composable
@UnstableApi
private fun MobilePlayerSurface(
  state: MobilePlayerUiState,
  settings: PlaybackSettings,
  onProgress: (Long, Long) -> Unit
) {
  val context = LocalContext.current
  val source = state.source ?: return
  val streamUrl = source.streamUrl ?: return
  val media = state.media
  val episode = state.episode

  val startPositionMs = remember(state.source) {
    if (episode != null && media != null) {
      MediaRepository.getEpisodeProgress(media.id, episode.seasonNumber, episode.episodeNumber)
    } else {
      media?.currentProgressMs ?: 0L
    }
  }

  val exoPlayer = remember(streamUrl, settings) {
    buildMobilePlayer(context, state, settings, startPositionMs)
  }

  var playbackError by remember { mutableStateOf<String?>(null) }

  DisposableEffect(exoPlayer) {
    val listener = object : Player.Listener {
      override fun onPlayerError(error: PlaybackException) {
        playbackError = error.message ?: error.errorCodeName
      }
    }
    exoPlayer.addListener(listener)
    onDispose { exoPlayer.removeListener(listener) }
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
      onProgress(exoPlayer.currentPosition.coerceAtLeast(0L), exoPlayer.duration.coerceAtLeast(0L))
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

  Box(modifier = Modifier.fillMaxSize()) {
    AndroidView(
      factory = { ctx ->
        PlayerView(ctx).apply {
          player = exoPlayer
          useController = true
          controllerAutoShow = true
          controllerShowTimeoutMs = 0
          setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
          resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
          keepScreenOn = true
          setBackgroundColor(AndroidColor.BLACK)
          layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
          )
          showController()
        }
      },
      update = { view ->
        view.player = exoPlayer
        view.showController()
      },
      modifier = Modifier.fillMaxSize()
    )

    playbackError?.let { message ->
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.Black.copy(alpha = 0.65f)),
        contentAlignment = Alignment.Center
      ) {
        Text(
          text = message,
          color = NovaTextPrimary,
          fontSize = 14.sp,
          modifier = Modifier.padding(24.dp)
        )
      }
    }
  }
}

@UnstableApi
private fun buildMobilePlayer(
  context: Context,
  state: MobilePlayerUiState,
  settings: PlaybackSettings,
  startPositionMs: Long
): ExoPlayer {
  val source = state.source ?: error("source mancante")
  val streamUrl = source.streamUrl ?: error("streamUrl mancante")

  val httpDataSourceFactory = DefaultHttpDataSource.Factory()
    .setDefaultRequestProperties(source.headers)
    .setConnectTimeoutMs(15_000)
    .setReadTimeoutMs(15_000)
    .setAllowCrossProtocolRedirects(true)
    .setUserAgent("StreamNova-Mobile/${BuildConfig.VERSION_NAME}")

  val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
  val progressiveFactory = ProgressiveMediaSource.Factory(
    dataSourceFactory,
    DefaultExtractorsFactory()
  )
  val loadErrorHandlingPolicy = PlayerLoadErrorHandlingPolicy()
  val mediaSourceFactory = resolvePlaybackMediaSourceFactory(
    isHlsStream = streamUrl.contains(".m3u8", ignoreCase = true),
    httpDataSourceFactory = dataSourceFactory,
    progressiveMediaSourceFactory = progressiveFactory,
    loadErrorHandlingPolicy = loadErrorHandlingPolicy
  )

  val renderersFactory = NuvioRenderersFactory(
    context = context,
    preferSoftwareAudioOnly = settings.decoderFallbackMode == DecoderFallbackMode.PREFER,
    audioPassthroughEnabled = settings.audioPassthroughEnabled,
    dolbyVisionFallbackEnabled = settings.dolbyVisionFallbackEnabled
  )

  val player = ExoPlayer.Builder(context)
    .setRenderersFactory(renderersFactory)
    .setLoadControl(buildPlayerLoadControl(settings))
    .build()

  val mediaItem = PlayerSubtitleMediaItemBuilder.buildMediaItem(streamUrl, source.subtitles)
  player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
  player.prepare()

  if (startPositionMs > 0L) {
    player.seekTo(startPositionMs)
  }
  player.playWhenReady = true
  return player
}

@Composable
private fun PlayerOverlay(
  title: String,
  subtitle: String?,
  onBack: () -> Unit,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
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
        imageVector = Icons.Default.ArrowBack,
        contentDescription = stringResource(R.string.player_back),
        tint = Color.White
      )
    }
    Column(modifier = Modifier.weight(1f)) {
      if (title.isNotBlank()) {
        Text(
          text = title,
          color = Color.White,
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1
        )
      }
      if (!subtitle.isNullOrBlank()) {
        Text(
          text = subtitle,
          color = Color.White.copy(alpha = 0.7f),
          fontSize = 12.sp,
          maxLines = 1
        )
      }
    }
  }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}
