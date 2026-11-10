package com.aistudio.streamnova.mobile.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.aistudio.streamnova.mobile.R
import com.aistudio.streamnova.mobile.ui.MobilePlayerControlsPolicy
import com.aistudio.streamnova.mobile.ui.MobilePlayerPanel
import com.aistudio.streamnova.mobile.ui.MobileVideoScale
import com.aistudio.streamnova.mobile.ui.formatPlayerSpeed
import com.aistudio.streamnova.mobile.ui.formatPlayerTime
import com.aistudio.streamnova.mobile.ui.playerQualitySummary
import com.aistudio.streamnova.mobile.ui.playerTrackDetail
import com.aistudio.streamnova.mobile.ui.playerTrackLanguageLabel
import com.aistudio.streamnova.mobile.ui.shouldShowSkipIntro
import com.example.data.prefs.DecoderFallbackMode
import com.example.data.prefs.PlaybackSettings
import com.example.data.streaming.StreamSource
import com.example.domain.model.IntroWindow
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaDivider
import com.example.ui.theme.NovaGreen
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary

/** Traccia audio selezionabile, con l'indice globale usato da Media3. */
data class MobileAudioTrack(
  val trackIndex: Int,
  val language: String?,
  val label: String?,
  val codec: String?,
  val channels: String?,
  val isSelected: Boolean
) {
  val title: String get() = playerTrackLanguageLabel(language, label)
  val detail: String
    get() = playerTrackDetail(codec, channels).ifEmpty { label.orEmpty() }
}

/** Traccia sottotitolo selezionabile, con l'indice globale usato da Media3. */
data class MobileSubtitleTrack(
  val trackIndex: Int,
  val language: String?,
  val label: String?,
  val isForced: Boolean,
  val isSelected: Boolean
) {
  val title: String
    get() {
      val base = playerTrackLanguageLabel(language, label)
      val alreadyForced = base.contains("forz", ignoreCase = true) ||
        base.contains("forced", ignoreCase = true)
      return if (isForced && !alreadyForced) "$base (Forced)" else base
    }
}

/** Fotografia dello stato del player necessaria ai controlli. */
data class MobilePlayerControlsData(
  val title: String,
  val secondaryLine: String,
  val isPlaying: Boolean,
  val playbackEnded: Boolean,
  val isBuffering: Boolean,
  val positionMs: Long,
  val durationMs: Long,
  val isLocked: Boolean,
  val lockHintVisible: Boolean,
  val controlsVisible: Boolean,
  val panel: MobilePlayerPanel,
  val settings: PlaybackSettings,
  val videoHeights: List<Int>,
  val selectedVideoHeight: Int?,
  val autoQuality: Boolean,
  val audioTracks: List<MobileAudioTrack>,
  val selectedAudioTrack: MobileAudioTrack?,
  val subtitleTracks: List<MobileSubtitleTrack>,
  val selectedSubtitleTrack: MobileSubtitleTrack?,
  val subtitlesOff: Boolean,
  val speed: Float,
  val scale: MobileVideoScale,
  val currentSourceName: String,
  val sources: List<StreamSource>,
  val currentSourceKey: String?,
  val introWindow: IntroWindow?,
  val openSubtitlesConfigured: Boolean,
  val externalSubtitleCount: Int,
  val gesturesEnabled: Boolean,
  val keepScreenOnInPause: Boolean,
  val extraBufferingEnabled: Boolean,
  val decoderMode: DecoderFallbackMode,
  val immersive: Boolean,
  val hasPlaybackError: Boolean
)

/** Azioni dei controlli: tutte le modifiche passano dal player o dal ViewModel. */
class MobilePlayerActions(
  val onBack: () -> Unit,
  val onTogglePlayPause: () -> Unit,
  val onSeekBy: (Long) -> Unit,
  val onSeekTo: (Long) -> Unit,
  val onSkipIntro: () -> Unit,
  val onLock: () -> Unit,
  val onUnlock: () -> Unit,
  val onCycleScale: () -> Unit,
  val onToggleImmersive: () -> Unit,
  val onSelectQuality: (Int?) -> Unit,
  val onSelectAudio: (Int) -> Unit,
  val onSelectSubtitles: (Int?) -> Unit,
  val onSelectSpeed: (Float) -> Unit,
  val onSelectSource: (StreamSource) -> Unit,
  val onSelectDecoder: (DecoderFallbackMode) -> Unit,
  val onGesturesEnabledChange: (Boolean) -> Unit,
  val onKeepScreenOnInPauseChange: (Boolean) -> Unit,
  val onExtraBufferingChange: (Boolean) -> Unit,
  val onSelectScale: (MobileVideoScale) -> Unit,
  val onScrubbingChange: (Boolean) -> Unit,
  val onOpenPanel: (MobilePlayerPanel) -> Unit,
  val onInteraction: () -> Unit
)

/**
 * Overlay completo del player Mobile.
 *
 * La UI è divisa in tre fasce (alto, centro, basso) e scompare integralmente
 * quando [MobilePlayerControlsData.controlsVisible] è false. Il pannello
 * impostazioni è un overlay laterale che non interrompe la riproduzione.
 */
@Composable
@UnstableApi
fun MobilePlayerOverlay(
  data: MobilePlayerControlsData,
  actions: MobilePlayerActions,
  modifier: Modifier = Modifier
) {
  Box(modifier = modifier.fillMaxSize()) {
    if (data.isLocked) {
      if (data.lockHintVisible) {
        LockedHint(onUnlock = actions.onUnlock, modifier = Modifier.align(Alignment.Center))
      }
    } else if (data.controlsVisible) {
      TopBar(data = data, actions = actions, modifier = Modifier.align(Alignment.TopCenter))
      if (!data.hasPlaybackError) {
        CenterControls(data = data, actions = actions, modifier = Modifier.align(Alignment.Center))
        if (shouldShowSkipIntro(data.positionMs, data.introWindow)) {
          SkipIntroButton(
            onSkipIntro = actions.onSkipIntro,
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
              )
              .padding(end = 24.dp, bottom = 132.dp)
          )
        }
      }
      BottomBar(data = data, actions = actions, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (data.panel.isOpen && !data.isLocked) {
      PlayerSettingsPanel(data = data, actions = actions, modifier = Modifier.align(Alignment.CenterEnd))
    }
  }
}

@Composable
private fun TopBar(
  data: MobilePlayerControlsData,
  actions: MobilePlayerActions,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(
        Brush.verticalGradient(
          listOf(Color.Black.copy(alpha = 0.80f), Color.Transparent)
        )
      )
      .windowInsetsPadding(
        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
      )
      .padding(horizontal = 10.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    IconButton(onClick = { actions.onInteraction(); actions.onBack() }) {
      Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = stringResource(R.string.player_back),
        tint = Color.White,
        modifier = Modifier.size(26.dp)
      )
    }
    Spacer(modifier = Modifier.width(6.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = data.title,
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      if (data.secondaryLine.isNotBlank()) {
        Text(
          text = data.secondaryLine,
          color = Color.White.copy(alpha = 0.72f),
          fontSize = 13.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
    IconButton(onClick = { actions.onInteraction(); actions.onToggleImmersive() }) {
      Icon(
        imageVector = if (data.immersive) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
        contentDescription = stringResource(
          if (data.immersive) R.string.player_controls_exit_fullscreen
          else R.string.player_controls_fullscreen
        ),
        tint = Color.White,
        modifier = Modifier.size(24.dp)
      )
    }
  }
}

@Composable
private fun CenterControls(
  data: MobilePlayerControlsData,
  actions: MobilePlayerActions,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(28.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    CenterIconButton(
      icon = Icons.Default.Replay10,
      description = stringResource(R.string.player_controls_rewind),
      onClick = { actions.onInteraction(); actions.onSeekBy(-MobilePlayerControlsPolicy.SEEK_STEP_MS) }
    )
    Box(
      modifier = Modifier
        .size(68.dp)
        .background(Color.Black.copy(alpha = 0.42f), CircleShape)
        .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
        .clickable {
          actions.onInteraction()
          actions.onTogglePlayPause()
        },
      contentAlignment = Alignment.Center
    ) {
      Icon(
        imageVector = when {
          data.playbackEnded -> Icons.Default.Replay
          data.isPlaying -> Icons.Default.Pause
          else -> Icons.Default.PlayArrow
        },
        contentDescription = stringResource(
          when {
            data.playbackEnded -> R.string.player_controls_replay
            data.isPlaying -> R.string.player_controls_pause
            else -> R.string.player_controls_play
          }
        ),
        tint = Color.White,
        modifier = Modifier.size(38.dp)
      )
    }
    CenterIconButton(
      icon = Icons.Default.Forward10,
      description = stringResource(R.string.player_controls_forward),
      onClick = { actions.onInteraction(); actions.onSeekBy(MobilePlayerControlsPolicy.SEEK_STEP_MS) }
    )
  }
}

@Composable
private fun CenterIconButton(
  icon: ImageVector,
  description: String,
  onClick: () -> Unit
) {
  IconButton(
    onClick = onClick,
    modifier = Modifier
      .size(54.dp)
      .background(Color.Black.copy(alpha = 0.32f), CircleShape)
  ) {
    Icon(
      imageVector = icon,
      contentDescription = description,
      tint = Color.White,
      modifier = Modifier.size(30.dp)
    )
  }
}

@Composable
private fun BottomBar(
  data: MobilePlayerControlsData,
  actions: MobilePlayerActions,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(
        Brush.verticalGradient(
          listOf(Color.Transparent, Color.Black.copy(alpha = 0.86f))
        )
      )
      .windowInsetsPadding(
        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
      )
      .padding(horizontal = 18.dp, vertical = 8.dp)
  ) {
    if (data.durationMs > 0L) {
      SeekBar(data = data, actions = actions)
    } else {
      Spacer(modifier = Modifier.height(8.dp))
    }
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .height(52.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = "${formatPlayerTime(data.positionMs)} · ${formatPlayerTime(data.durationMs)}",
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium
      )
      Spacer(modifier = Modifier.weight(1f))
      BarIconButton(
        icon = Icons.Default.Lock,
        description = stringResource(R.string.player_controls_lock),
        onClick = { actions.onInteraction(); actions.onLock() }
      )
      BarIconButton(
        icon = Icons.Default.AspectRatio,
        description = stringResource(R.string.player_controls_manual_zoom),
        onClick = { actions.onInteraction(); actions.onCycleScale() }
      )
      BarIconButton(
        icon = Icons.Default.Settings,
        description = stringResource(R.string.settings_title),
        onClick = {
          actions.onInteraction()
          actions.onOpenPanel(MobilePlayerPanel.ROOT)
        }
      )
    }
  }
}

@Composable
private fun SeekBar(
  data: MobilePlayerControlsData,
  actions: MobilePlayerActions
) {
  var scrubbing by remember { mutableStateOf(false) }
  var previewMs by remember { mutableFloatStateOf(0f) }
  val duration = data.durationMs.coerceAtLeast(1L)
  val shownMs = if (scrubbing) previewMs.toLong() else data.positionMs
  val fraction = (shownMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

  BoxWithConstraints(
    modifier = Modifier
      .fillMaxWidth()
      .height(40.dp)
      .pointerInput(duration) {
        val touchSlop = viewConfiguration.touchSlop
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          val width = size.width.toFloat().coerceAtLeast(1f)
          var lastX = down.position.x
          var totalDrag = 0f
          var dragged = false
          var pressed = true
          while (pressed) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (change.pressed) {
              val dx = change.position.x - lastX
              lastX = change.position.x
              totalDrag += dx
              if (!dragged && kotlin.math.abs(totalDrag) > touchSlop) {
                dragged = true
                scrubbing = true
                previewMs = (down.position.x / width).coerceIn(0f, 1f) * duration
                actions.onScrubbingChange(true)
              }
              if (dragged) {
                change.consume()
                previewMs = (previewMs + (dx / width) * duration)
                  .coerceIn(0f, duration.toFloat())
                actions.onInteraction()
              }
            } else {
              pressed = false
              if (dragged) {
                val target = previewMs.toLong()
                scrubbing = false
                actions.onScrubbingChange(false)
                actions.onSeekTo(target)
              } else {
                // Tap secco sulla barra: salto diretto alla posizione toccata.
                val target = (change.position.x / width).coerceIn(0f, 1f) * duration
                actions.onSeekTo(target.toLong())
                actions.onInteraction()
              }
            }
          }
          if (pressed) {
            scrubbing = false
            actions.onScrubbingChange(false)
          }
        }
      }
  ) {
    val trackHeight = 3.dp
    val thumbSize = 14.dp
    val thumbOffset = (maxWidth * fraction - thumbSize / 2)
      .coerceIn(0.dp, (maxWidth - thumbSize).coerceAtLeast(0.dp))

    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(trackHeight)
        .align(Alignment.CenterStart)
        .background(Color.White.copy(alpha = 0.28f), RoundedCornerShape(2.dp))
    )
    Box(
      modifier = Modifier
        .fillMaxWidth(fraction)
        .height(trackHeight)
        .align(Alignment.CenterStart)
        .background(NovaCyanBright, RoundedCornerShape(2.dp))
    )
    Box(
      modifier = Modifier
        .offset(x = thumbOffset)
        .size(thumbSize)
        .align(Alignment.CenterStart)
        .background(Color.White, CircleShape)
    )
    if (scrubbing) {
      Text(
        text = formatPlayerTime(previewMs.toLong()),
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
          .align(Alignment.TopCenter)
          .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(6.dp))
          .padding(horizontal = 8.dp, vertical = 2.dp)
      )
    }
  }
}

@Composable
private fun BarIconButton(
  icon: ImageVector,
  description: String,
  onClick: () -> Unit
) {
  IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
    Icon(
      imageVector = icon,
      contentDescription = description,
      tint = Color.White,
      modifier = Modifier.size(24.dp)
    )
  }
}

@Composable
private fun SkipIntroButton(onSkipIntro: () -> Unit, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier
      .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(16.dp))
      .border(1.dp, Color.White.copy(alpha = 0.30f), RoundedCornerShape(16.dp))
      .clickable(onClick = onSkipIntro)
      .padding(horizontal = 20.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      imageVector = Icons.Default.Forward10,
      contentDescription = null,
      tint = Color.White,
      modifier = Modifier.size(20.dp)
    )
    Spacer(modifier = Modifier.width(8.dp))
    Text(
      text = stringResource(R.string.player_controls_skip_intro),
      color = Color.White,
      fontSize = 15.sp,
      fontWeight = FontWeight.SemiBold
    )
  }
}

@Composable
private fun LockedHint(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier
      .background(Color.Black.copy(alpha = 0.76f), RoundedCornerShape(18.dp))
      .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(18.dp))
      .clickable(onClick = onUnlock)
      .padding(horizontal = 22.dp, vertical = 16.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      imageVector = Icons.Default.LockOpen,
      contentDescription = null,
      tint = Color.White,
      modifier = Modifier.size(26.dp)
    )
    Spacer(modifier = Modifier.width(10.dp))
    Text(
      text = stringResource(R.string.player_controls_unlock),
      color = Color.White,
      fontSize = 15.sp,
      fontWeight = FontWeight.SemiBold
    )
  }
}

// ─── Pannello impostazioni ─────────────────────────────────────────────────

@Composable
private fun PlayerSettingsPanel(
  data: MobilePlayerControlsData,
  actions: MobilePlayerActions,
  modifier: Modifier = Modifier
) {
  Row(modifier = modifier.fillMaxSize()) {
    // Scrim: chiude il pannello, non tocca il player.
    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxHeight()
        .background(Color.Black.copy(alpha = 0.45f))
        .clickable {
          actions.onInteraction()
          actions.onOpenPanel(MobilePlayerPanel.NONE)
        }
    )
    // Larghezza adattiva: 380 dp sulle finestre normali, si restringe quando la
    // finestra disponibile è più stretta (split-screen, piccole finestre) invece
    // di sforare il bordo. Su telefoni in riproduzione (landscape) e sui tablet
    // in portrait il pannello resta com'è.
    BoxWithConstraints(modifier = Modifier.fillMaxHeight()) {
      val panelWidth = minOf(380.dp, maxWidth * 0.9f)
      Column(
        modifier = Modifier
          .width(panelWidth)
          .fillMaxHeight()
          .background(NovaBackground)
          .border(1.dp, NovaDivider)
      ) {
        PanelHeader(
          title = if (data.panel == MobilePlayerPanel.ROOT) {
            stringResource(R.string.settings_title)
          } else {
            panelTitle(data.panel)
          },
          showBack = data.panel != MobilePlayerPanel.ROOT,
          onBack = {
            actions.onInteraction()
            actions.onOpenPanel(MobilePlayerPanel.ROOT)
          },
          onClose = {
            actions.onInteraction()
            actions.onOpenPanel(MobilePlayerPanel.NONE)
          }
        )
        LazyColumn(
          modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(
              WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
            )
            .padding(horizontal = 18.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
          when (data.panel) {
            MobilePlayerPanel.ROOT -> rootPanelItems(data, actions)
            MobilePlayerPanel.QUALITY -> qualityItems(data, actions)
            MobilePlayerPanel.AUDIO -> audioItems(data, actions)
            MobilePlayerPanel.SUBTITLES -> subtitleItems(data, actions)
            MobilePlayerPanel.SPEED -> speedItems(data, actions)
            MobilePlayerPanel.SERVERS -> serverItems(data, actions)
            MobilePlayerPanel.BUFFERING -> bufferingItems(data, actions)
            MobilePlayerPanel.DECODER -> decoderItems(data, actions)
            MobilePlayerPanel.GESTURES -> gesturesItems(data, actions)
            MobilePlayerPanel.SCREEN_ON -> screenOnItems(data, actions)
            MobilePlayerPanel.ZOOM -> zoomItems(data, actions)
            MobilePlayerPanel.NONE -> Unit
          }
        }
      }
    }
  }
}

@Composable
private fun PanelHeader(
  title: String,
  showBack: Boolean,
  onBack: () -> Unit,
  onClose: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
      .padding(start = 18.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    if (showBack) {
      IconButton(onClick = onBack) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = stringResource(R.string.player_controls_back_panel),
          tint = NovaTextPrimary
        )
      }
      Spacer(modifier = Modifier.width(2.dp))
    }
    Text(
      text = title,
      color = NovaTextPrimary,
      fontSize = 24.sp,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.weight(1f)
    )
    IconButton(onClick = onClose) {
      Icon(
        imageVector = Icons.Default.Close,
        contentDescription = stringResource(R.string.player_controls_close_panel),
        tint = NovaTextPrimary
      )
    }
  }
}

@Composable
private fun panelTitle(panel: MobilePlayerPanel): String = stringResource(
  when (panel) {
    MobilePlayerPanel.QUALITY -> R.string.player_controls_quality
    MobilePlayerPanel.AUDIO -> R.string.player_controls_audio
    MobilePlayerPanel.SUBTITLES -> R.string.settings_sec_subtitles
    MobilePlayerPanel.SPEED -> R.string.player_controls_speed
    MobilePlayerPanel.SERVERS -> R.string.player_controls_servers
    MobilePlayerPanel.BUFFERING -> R.string.player_controls_extra_buffering
    MobilePlayerPanel.DECODER -> R.string.player_controls_decoder
    MobilePlayerPanel.GESTURES -> R.string.player_controls_gestures
    MobilePlayerPanel.SCREEN_ON -> R.string.player_controls_screen_on_pause
    MobilePlayerPanel.ZOOM -> R.string.player_controls_manual_zoom
    else -> R.string.settings_title
  }
)

private fun androidx.compose.foundation.lazy.LazyListScope.rootPanelItems(
  data: MobilePlayerControlsData,
  actions: MobilePlayerActions
) {
  item {
    PanelRow(
      icon = Icons.Default.HighQuality,
      title = stringResource(R.string.player_controls_quality),
      value = playerQualitySummary(data.autoQuality, data.selectedVideoHeight),
      onClick = {
        actions.onInteraction()
        actions.onOpenPanel(MobilePlayerPanel.QUALITY)
      }
    )
  }
  item {
    PanelRow(
      icon = Icons.Default.VolumeUp,
      title = stringResource(R.string.player_controls_audio),
      value = data.selectedAudioTrack?.title
        ?: stringResource(R.string.player_controls_no_track),
      onClick = {
        actions.onInteraction()
        actions.onOpenPanel(MobilePlayerPanel.AUDIO)
      }
    )
  }
  item {
    PanelRow(
      icon = Icons.Default.ClosedCaption,
      title = stringResource(R.string.settings_sec_subtitles),
      value = when {
        data.subtitlesOff -> stringResource(R.string.player_controls_off)
        data.selectedSubtitleTrack != null -> data.selectedSubtitleTrack.title
        else -> stringResource(R.string.player_controls_off)
      },
      onClick = {
        actions.onInteraction()
        actions.onOpenPanel(MobilePlayerPanel.SUBTITLES)
      }
    )
  }
  item {
    PanelRow(
      icon = Icons.Default.Speed,
      title = stringResource(R.string.player_controls_speed),
      value = formatPlayerSpeed(data.speed),
      onClick = {
        actions.onInteraction()
        actions.onOpenPanel(MobilePlayerPanel.SPEED)
      }
    )
  }
  item {
    PanelRow(
      icon = Icons.Default.Dns,
      title = stringResource(R.string.player_controls_servers),
      value = data.currentSourceName,
      onClick = {
        actions.onInteraction()
        actions.onOpenPanel(MobilePlayerPanel.SERVERS)
      }
    )
  }
  item {
    PanelRow(
      icon = Icons.Default.SwapVert,
      title = stringResource(R.string.player_controls_extra_buffering),
      value = booleanLabel(data.extraBufferingEnabled),
      onClick = {
        actions.onInteraction()
        actions.onOpenPanel(MobilePlayerPanel.BUFFERING)
      }
    )
  }
  item {
    PanelRow(
      icon = Icons.Default.Sync,
      title = stringResource(R.string.player_controls_decoder),
      value = decoderLabel(data.decoderMode),
      onClick = {
        actions.onInteraction()
        actions.onOpenPanel(MobilePlayerPanel.DECODER)
      }
    )
  }
  item {
    PanelRow(
      icon = Icons.Default.Gesture,
      title = stringResource(R.string.player_controls_gestures),
      value = enabledLabel(data.gesturesEnabled),
      onClick = {
        actions.onInteraction()
        actions.onOpenPanel(MobilePlayerPanel.GESTURES)
      }
    )
  }
  item {
    PanelRow(
      icon = Icons.Default.LightMode,
      title = stringResource(R.string.player_controls_screen_on_pause),
      value = enabledLabel(data.keepScreenOnInPause),
      onClick = {
        actions.onInteraction()
        actions.onOpenPanel(MobilePlayerPanel.SCREEN_ON)
      }
    )
  }
  item {
    PanelRow(
      icon = Icons.Default.AspectRatio,
      title = stringResource(R.string.player_controls_manual_zoom),
      value = scaleLabel(data.scale),
      onClick = {
        actions.onInteraction()
        actions.onOpenPanel(MobilePlayerPanel.ZOOM)
      }
    )
  }
  item { Spacer(modifier = Modifier.height(16.dp)) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.qualityItems(
  data: MobilePlayerControlsData,
  actions: MobilePlayerActions
) {
  item {
    PanelOptionRow(
      title = stringResource(R.string.res_auto),
      subtitle = stringResource(R.string.player_controls_quality_auto_desc),
      selected = data.autoQuality,
      onClick = {
        actions.onInteraction()
        actions.onSelectQuality(null)
        actions.onOpenPanel(MobilePlayerPanel.ROOT)
      }
    )
  }
  items(data.videoHeights) { height ->
    PanelOptionRow(
      title = com.aistudio.streamnova.mobile.ui.playerQualityLabel(height),
      subtitle = stringResource(R.string.player_controls_quality_track_desc),
      selected = !data.autoQuality && data.selectedVideoHeight == height,
      onClick = {
        actions.onInteraction()
        actions.onSelectQuality(height)
        actions.onOpenPanel(MobilePlayerPanel.ROOT)
      }
    )
  }
  if (data.videoHeights.isEmpty()) {
    item { PanelNote(stringResource(R.string.player_controls_no_video_tracks)) }
  }
  item { Spacer(modifier = Modifier.height(16.dp)) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.audioItems(
  data: MobilePlayerControlsData,
  actions: MobilePlayerActions
) {
  if (data.audioTracks.isEmpty()) {
    item { PanelNote(stringResource(R.string.player_controls_no_audio_tracks)) }
  }
  items(data.audioTracks) { track ->
    PanelOptionRow(
      title = track.title,
      subtitle = track.detail,
      selected = track.isSelected,
      onClick = {
        actions.onInteraction()
        actions.onSelectAudio(track.trackIndex)
      }
    )
  }
  item { Spacer(modifier = Modifier.height(16.dp)) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.subtitleItems(
  data: MobilePlayerControlsData,
  actions: MobilePlayerActions
) {
  item {
    PanelOptionRow(
      title = stringResource(R.string.player_controls_off),
      subtitle = stringResource(R.string.player_controls_subtitles_off_desc),
      selected = data.subtitlesOff,
      onClick = {
        actions.onInteraction()
        actions.onSelectSubtitles(null)
      }
    )
  }
  items(data.subtitleTracks) { track ->
    PanelOptionRow(
      title = track.title,
      subtitle = stringResource(R.string.player_controls_subtitles_track_desc),
      selected = track.isSelected,
      onClick = {
        actions.onInteraction()
        actions.onSelectSubtitles(track.trackIndex)
      }
    )
  }
  if (data.subtitleTracks.isEmpty()) {
    item { PanelNote(stringResource(R.string.player_controls_no_subtitles)) }
  }
  if (!data.openSubtitlesConfigured) {
    item { PanelNote(stringResource(R.string.player_controls_online_subtitles_missing_key)) }
  } else if (data.externalSubtitleCount == 0) {
    item { PanelNote(stringResource(R.string.player_controls_online_subtitles_none)) }
  } else {
    item {
      PanelNote(
        text = stringResource(
          R.string.player_controls_online_subtitles_count,
          data.externalSubtitleCount
        ),
        color = NovaGreen
      )
    }
  }
  item { Spacer(modifier = Modifier.height(16.dp)) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.speedItems(
  data: MobilePlayerControlsData,
  actions: MobilePlayerActions
) {
  items(MobilePlayerControlsPolicy.SPEED_OPTIONS) { speed ->
    PanelOptionRow(
      title = formatPlayerSpeed(speed),
      subtitle = if (speed == 1.0f) {
        stringResource(R.string.player_controls_normal_speed)
      } else {
        null
      },
      selected = kotlin.math.abs(data.speed - speed) < 0.01f,
      onClick = {
        actions.onInteraction()
        actions.onSelectSpeed(speed)
      }
    )
  }
  item { PanelNote(stringResource(R.string.player_controls_speed_desc)) }
  item { Spacer(modifier = Modifier.height(16.dp)) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.serverItems(
  data: MobilePlayerControlsData,
  actions: MobilePlayerActions
) {
  if (data.sources.isEmpty()) {
    item { PanelNote(stringResource(R.string.player_controls_no_servers)) }
  }
  items(data.sources) { source ->
    val name = source.serverName.ifBlank {
      source.addonName ?: stringResource(R.string.player_source_unknown)
    }
    val quality = source.declaredQuality ?: source.quality
    PanelOptionRow(
      title = name,
      subtitle = quality,
      selected = data.currentSourceKey != null &&
        com.example.ui.screens.player.PlayerSourceFallback.keyOf(source) == data.currentSourceKey,
      onClick = {
        actions.onInteraction()
        actions.onSelectSource(source)
      }
    )
  }
  item { PanelNote(stringResource(R.string.player_controls_servers_desc)) }
  item { Spacer(modifier = Modifier.height(16.dp)) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.bufferingItems(
  data: MobilePlayerControlsData,
  actions: MobilePlayerActions
) {
  item {
    PanelToggleRow(
      title = stringResource(R.string.player_controls_extra_buffering),
      subtitle = booleanLabel(data.extraBufferingEnabled),
      checked = data.extraBufferingEnabled,
      onCheckedChange = {
        actions.onInteraction()
        actions.onExtraBufferingChange(it)
      }
    )
  }
  item { PanelNote(stringResource(R.string.player_controls_buffering_desc)) }
  item { Spacer(modifier = Modifier.height(16.dp)) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.decoderItems(
  data: MobilePlayerControlsData,
  actions: MobilePlayerActions
) {
  items(DecoderFallbackMode.entries) { mode ->
    PanelOptionRow(
      title = decoderLabel(mode),
      subtitle = null,
      selected = data.decoderMode == mode,
      onClick = {
        actions.onInteraction()
        actions.onSelectDecoder(mode)
      }
    )
  }
  item { PanelNote(stringResource(R.string.player_controls_decoder_desc)) }
  item { Spacer(modifier = Modifier.height(16.dp)) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.gesturesItems(
  data: MobilePlayerControlsData,
  actions: MobilePlayerActions
) {
  item {
    PanelToggleRow(
      title = stringResource(R.string.player_controls_gestures),
      subtitle = enabledLabel(data.gesturesEnabled),
      checked = data.gesturesEnabled,
      onCheckedChange = {
        actions.onInteraction()
        actions.onGesturesEnabledChange(it)
      }
    )
  }
  item { PanelNote(stringResource(R.string.player_controls_gestures_desc)) }
  item { Spacer(modifier = Modifier.height(16.dp)) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.screenOnItems(
  data: MobilePlayerControlsData,
  actions: MobilePlayerActions
) {
  item {
    PanelToggleRow(
      title = stringResource(R.string.player_controls_screen_on_pause),
      subtitle = enabledLabel(data.keepScreenOnInPause),
      checked = data.keepScreenOnInPause,
      onCheckedChange = {
        actions.onInteraction()
        actions.onKeepScreenOnInPauseChange(it)
      }
    )
  }
  item { PanelNote(stringResource(R.string.player_controls_screen_on_desc)) }
  item { Spacer(modifier = Modifier.height(16.dp)) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.zoomItems(
  data: MobilePlayerControlsData,
  actions: MobilePlayerActions
) {
  items(MobileVideoScale.entries) { scale ->
    PanelOptionRow(
      title = scaleLabel(scale),
      subtitle = null,
      selected = data.scale == scale,
      onClick = {
        actions.onInteraction()
        actions.onSelectScale(scale)
      }
    )
  }
  item { PanelNote(stringResource(R.string.player_controls_zoom_desc)) }
  item { Spacer(modifier = Modifier.height(16.dp)) }
}

@Composable
private fun PanelRow(
  icon: ImageVector,
  title: String,
  value: String,
  onClick: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(64.dp)
      .clickable(onClick = onClick)
      .padding(vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = NovaTextPrimary,
      modifier = Modifier.size(24.dp)
    )
    Spacer(modifier = Modifier.width(18.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        color = NovaTextPrimary,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1
      )
      if (value.isNotBlank()) {
        Text(
          text = value,
          color = NovaTextMuted,
          fontSize = 13.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
    Icon(
      imageVector = Icons.Default.ChevronRight,
      contentDescription = null,
      tint = NovaTextMuted,
      modifier = Modifier.size(22.dp)
    )
  }
}

@Composable
private fun PanelOptionRow(
  title: String,
  subtitle: String?,
  selected: Boolean,
  onClick: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(58.dp)
      .clickable(onClick = onClick)
      .padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    RadioButton(
      selected = selected,
      onClick = onClick,
      colors = RadioButtonDefaults.colors(
        selectedColor = NovaCyanBright,
        unselectedColor = NovaTextMuted
      )
    )
    Spacer(modifier = Modifier.width(10.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        color = if (selected) NovaCyanBright else NovaTextPrimary,
        fontSize = 15.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1
      )
      if (!subtitle.isNullOrBlank()) {
        Text(
          text = subtitle,
          color = NovaTextMuted,
          fontSize = 12.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
    if (selected) {
      Icon(
        imageVector = Icons.Default.Check,
        contentDescription = null,
        tint = NovaCyanBright,
        modifier = Modifier.size(20.dp)
      )
    }
  }
}

@Composable
private fun PanelToggleRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(64.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column(modifier = Modifier.weight(1f)) {
      Text(text = title, color = NovaTextPrimary, fontSize = 15.sp)
      Text(text = subtitle, color = NovaTextMuted, fontSize = 12.sp)
    }
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      colors = SwitchDefaults.colors(
        checkedThumbColor = NovaCyanBright,
        checkedTrackColor = NovaCyan.copy(alpha = 0.45f)
      )
    )
  }
}

@Composable
private fun PanelNote(text: String, color: Color = NovaTextSecondary) {
  Text(
    text = text,
    color = color,
    fontSize = 12.sp,
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 8.dp)
  )
}

@Composable
private fun booleanLabel(value: Boolean): String = stringResource(
  if (value) R.string.settings_value_on else R.string.settings_value_off
)

@Composable
private fun enabledLabel(value: Boolean): String = stringResource(
  if (value) R.string.player_controls_enabled else R.string.player_controls_disabled
)

@Composable
private fun decoderLabel(mode: DecoderFallbackMode): String = stringResource(
  when (mode) {
    DecoderFallbackMode.OFF -> R.string.player_controls_decoder_hardware
    DecoderFallbackMode.ON -> R.string.player_controls_decoder_fallback
    DecoderFallbackMode.PREFER -> R.string.player_controls_decoder_software
  }
)

@Composable
private fun scaleLabel(scale: MobileVideoScale): String = stringResource(
  when (scale) {
    MobileVideoScale.FIT -> R.string.player_controls_zoom_fit
    MobileVideoScale.FILL -> R.string.player_controls_zoom_fill
    MobileVideoScale.ZOOM -> R.string.player_controls_zoom_crop
    MobileVideoScale.FIXED_WIDTH -> R.string.player_controls_zoom_fixed_width
    MobileVideoScale.FIXED_HEIGHT -> R.string.player_controls_zoom_fixed_height
  }
)
