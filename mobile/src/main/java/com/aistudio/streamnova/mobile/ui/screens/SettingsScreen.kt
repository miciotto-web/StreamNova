package com.aistudio.streamnova.mobile.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aistudio.streamnova.mobile.BuildConfig
import com.aistudio.streamnova.mobile.R
import com.aistudio.streamnova.mobile.ui.LocalWindowWidthExpanded
import com.aistudio.streamnova.mobile.ui.MobileOpenSubtitlesState
import com.aistudio.streamnova.mobile.ui.MobileSettingsPolicy
import com.aistudio.streamnova.mobile.ui.MobileSettingsUiState
import com.aistudio.streamnova.mobile.ui.MobileSettingsViewModel
import com.aistudio.streamnova.mobile.ui.MobileSimklLoginError
import com.aistudio.streamnova.mobile.ui.MobileSimklNotice
import com.aistudio.streamnova.mobile.ui.MobileTorBoxAccountState
import com.aistudio.streamnova.mobile.ui.components.MobileChoiceDialog
import com.aistudio.streamnova.mobile.ui.components.MobilePinDialog
import com.aistudio.streamnova.mobile.ui.components.MobileUpdateResultDialog
import com.aistudio.streamnova.mobile.ui.components.MobileSettingsExpandableSection
import com.aistudio.streamnova.mobile.ui.components.MobileSettingsNote
import com.aistudio.streamnova.mobile.ui.components.MobileSettingsRow
import com.aistudio.streamnova.mobile.ui.components.MobileSettingsSection
import com.aistudio.streamnova.mobile.ui.components.MobileSettingsSwitchRow
import com.aistudio.streamnova.mobile.ui.components.MobileSimklLoginDialog
import com.aistudio.streamnova.mobile.ui.components.MobileTextInputDialog
import com.example.data.prefs.BackBufferOption
import com.example.data.prefs.ContinueWatchingLayout
import com.example.data.prefs.ContinueWatchingSort
import com.example.data.prefs.DecoderFallbackMode
import com.example.data.prefs.InitialBufferOption
import com.example.data.prefs.LastStreamCacheDuration
import com.example.data.prefs.PreferredResolution
import com.example.data.prefs.StreamingEngineMode
import com.example.data.prefs.SubtitleBackground
import com.example.data.prefs.SubtitleOpacity
import com.example.data.prefs.SubtitlePosition
import com.example.data.prefs.SubtitleSize
import com.example.data.prefs.TargetBufferOption
import com.example.data.remote.simkl.SimklSyncResult
import com.example.data.remote.simkl.SimklSyncStatus
import com.example.data.opensubtitles.OpenSubtitlesVerifyError
import com.example.data.update.UpdateCheckState
import com.example.ui.theme.NovaBackground
import com.example.ui.theme.NovaCardBg
import com.example.ui.theme.NovaCardBgFocused
import com.example.ui.theme.NovaCyan
import com.example.ui.theme.NovaCyanBright
import com.example.ui.theme.NovaDivider
import com.example.ui.theme.NovaGreen
import com.example.ui.theme.NovaOrange
import com.example.ui.theme.NovaRed
import com.example.ui.theme.NovaTextMuted
import com.example.ui.theme.NovaTextPrimary
import com.example.ui.theme.NovaTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/** Passi del flusso PIN, identici alla TV (creazione, verifica, modifica). */
private sealed interface MobileParentalStep {
  data object CreateNew : MobileParentalStep
  data class ConfirmNew(val firstPin: String) : MobileParentalStep
  data object VerifyToManage : MobileParentalStep
  data object ChangeNewPin : MobileParentalStep
  data class ChangeConfirmPin(val firstPin: String) : MobileParentalStep
}

/**
 * Impostazioni Mobile: tutte le sezioni della TV 1.0.4, adattate al touch.
 *
 * L'ordine delle sezioni e delle righe è quello della TV; le opzioni che dipendono
 * da funzionalità non ancora disponibili sul client Mobile sono mostrate come non
 * disponibili con una nota esplicita, mai simulate. Vedi il report di Fase 5.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
  BackHandler(onBack = onBack)
  val viewModel: MobileSettingsViewModel = viewModel()
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val isClearingCache by viewModel.isClearingCache.collectAsStateWithLifecycle()
  val simklLoginState by viewModel.simklLoginState.collectAsStateWithLifecycle()
  val updateCheckState by viewModel.updateCheckState.collectAsStateWithLifecycle()
  val updateDownloadState by viewModel.updateDownloadState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val expanded = LocalWindowWidthExpanded.current

  var showAppLanguageDialog by remember { mutableStateOf(false) }
  var showResolutionDialog by remember { mutableStateOf(false) }
  var showAudioLanguageDialog by remember { mutableStateOf(false) }
  var showSubtitleLanguageDialog by remember { mutableStateOf(false) }
  var showSubtitleSizeDialog by remember { mutableStateOf(false) }
  var showSubtitleBackgroundDialog by remember { mutableStateOf(false) }
  var showSubtitleOpacityDialog by remember { mutableStateOf(false) }
  var showSubtitlePositionDialog by remember { mutableStateOf(false) }
  var showTargetBufferDialog by remember { mutableStateOf(false) }
  var showInitialBufferDialog by remember { mutableStateOf(false) }
  var showBackBufferDialog by remember { mutableStateOf(false) }
  var showDecoderDialog by remember { mutableStateOf(false) }
  var showEngineModeDialog by remember { mutableStateOf(false) }
  var showReuseCacheDialog by remember { mutableStateOf(false) }
  var showTorBoxKeyDialog by remember { mutableStateOf(false) }
  var showOpenSubtitlesKeyDialog by remember { mutableStateOf(false) }
  // Sezione «Continua a guardare»: aperta di default come la TV 1.0.7; lo stato
  // vive solo nella composizione, le singole preferenze sono persistite dal core.
  var continueWatchingExpanded by remember { mutableStateOf(true) }
  var showContinueWatchingSortDialog by remember { mutableStateOf(false) }

  var parentalStep by remember { mutableStateOf<MobileParentalStep?>(null) }
  var parentalError by remember { mutableStateOf<String?>(null) }
  var showParentalManageDialog by remember { mutableStateOf(false) }
  var showParentalLevelDialog by remember { mutableStateOf(false) }

  val pinMismatch = stringResource(R.string.parental_dialog_pin_mismatch)
  val pinWrong = stringResource(R.string.parental_dialog_wrong_pin)

  // Verifica automatica della chiave TorBox già salvata, come la TV.
  LaunchedEffect(state.torBoxApiKey) {
    if (!state.torBoxApiKey.isNullOrBlank() &&
      state.torBoxAccount is MobileTorBoxAccountState.Unknown
    ) {
      viewModel.verifyTorBoxAccount()
    }
  }

  // Login riuscito: avviso una sola volta, poi lo stato del controller viene consumato.
  LaunchedEffect(simklLoginState.account) {
    if (simklLoginState.account != null) viewModel.consumeSimklLoginResult()
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(NovaBackground)
  ) {
    LazyColumn(
      modifier = Modifier
        .align(Alignment.TopCenter)
        .widthIn(max = 1000.dp)
        .fillMaxSize(),
      contentPadding = PaddingValues(
        start = if (expanded) 32.dp else 12.dp,
        end = if (expanded) 32.dp else 12.dp,
        bottom = 48.dp
      ),
      verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
      item(key = "header") {
        SettingsHeader(onBack = onBack)
      }

      // ── ACCOUNT SIMKL ──────────────────────────────────────────────────
      item(key = "simkl") {
        SettingsSimklSection(
          state = state,
          onAccountClick = {
            if (state.simklAccount.isLoggedIn) {
              viewModel.signOutSimkl()
            } else {
              viewModel.startSimklLogin()
            }
          },
          onSyncClick = { viewModel.syncSimklNow() }
        )
      }

      // ── GENERALE ───────────────────────────────────────────────────────
      item(key = "general") {
        MobileSettingsSection(title = stringResource(R.string.settings_sec_general)) {
          MobileSettingsRow(
            icon = Icons.Default.Language,
            title = stringResource(R.string.settings_app_language),
            value = appLanguageLabel(state.appLanguage),
            onClick = { showAppLanguageDialog = true }
          )
          MobileSettingsRow(
            icon = Icons.Default.Lock,
            title = stringResource(R.string.settings_parental_control),
            value = if (state.parentalEnabled) {
              stringResource(R.string.parental_control_on_level, "${state.parentalLevel}+")
            } else {
              stringResource(R.string.parental_control_off)
            },
            onClick = {
              parentalError = null
              parentalStep = if (!state.parentalEnabled) {
                MobileParentalStep.CreateNew
              } else {
                MobileParentalStep.VerifyToManage
              }
            }
          )
          MobileSettingsRow(
            icon = Icons.Default.Refresh,
            title = stringResource(R.string.settings_check_updates),
            value = updateCheckStateValue(updateCheckState),
            valueColor = updateCheckStateValueColor(updateCheckState),
            onClick = { viewModel.checkForUpdates() }
          )
          MobileSettingsNote(
            text = stringResource(R.string.settings_updates_channel_desc),
            color = NovaTextMuted
          )
          MobileSettingsRow(
            icon = Icons.Default.Info,
            title = stringResource(R.string.settings_app_version),
            value = BuildConfig.VERSION_NAME
          )
        }
      }

      // ── MOTORE DI RIPRODUZIONE ─────────────────────────────────────────
      item(key = "engine") {
        MobileSettingsSection(title = stringResource(R.string.settings_sec_playback_engine)) {
          MobileSettingsRow(
            icon = Icons.Default.Bolt,
            title = stringResource(R.string.settings_playback_mode),
            value = when (state.engineMode) {
              StreamingEngineMode.DEBRID_TORBOX -> stringResource(R.string.settings_mode_debrid)
              StreamingEngineMode.HTTP_WEB -> stringResource(R.string.settings_mode_web_scraper)
            },
            onClick = { showEngineModeDialog = true }
          )
          if (state.engineMode == StreamingEngineMode.DEBRID_TORBOX) {
            MobileSettingsRow(
              icon = Icons.Default.VpnKey,
              title = stringResource(R.string.settings_torbox_api_key),
              value = state.torBoxApiKey
                ?.let { MobileSettingsPolicy.maskApiKey(it) }
                ?: stringResource(R.string.settings_not_configured),
              valueColor = if (!state.torBoxApiKey.isNullOrBlank()) NovaGreen else NovaTextMuted,
              onClick = { showTorBoxKeyDialog = true }
            )
            MobileSettingsRow(
              icon = Icons.Default.CloudDone,
              title = stringResource(R.string.settings_account_status),
              value = when (val account = state.torBoxAccount) {
                is MobileTorBoxAccountState.Checking -> stringResource(R.string.settings_status_checking)
                is MobileTorBoxAccountState.Connected -> stringResource(R.string.settings_status_connected)
                is MobileTorBoxAccountState.Error -> stringResource(R.string.settings_status_verify_account)
                is MobileTorBoxAccountState.Unknown ->
                  if (state.torBoxApiKey.isNullOrBlank()) {
                    stringResource(R.string.settings_key_not_configured)
                  } else {
                    stringResource(R.string.settings_status_verify_account)
                  }
                is MobileTorBoxAccountState.NotConfigured ->
                  stringResource(R.string.settings_key_not_configured)
              },
              valueColor = when (state.torBoxAccount) {
                is MobileTorBoxAccountState.Connected -> NovaGreen
                is MobileTorBoxAccountState.Error -> NovaRed
                else -> NovaCyanBright
              },
              onClick = { viewModel.verifyTorBoxAccount() }
            )
            MobileSettingsSwitchRow(
              icon = Icons.Default.Bolt,
              title = stringResource(R.string.settings_use_torbox_instant),
              checked = state.torBoxInstantDebridEnabled,
              onCheckedChange = viewModel::setTorBoxInstantDebridEnabled
            )
          } else {
            MobileSettingsNote(text = stringResource(R.string.settings_http_desc))
          }
        }
      }

      // ── CONTINUA A GUARDARE ────────────────────────────────────────────
      // Sezione espandibile dedicata alla riga omonima della Home, con le stesse
      // sette preferenze della TV 1.0.7. La UI scrive solo le preferenze del core:
      // visibilità, layout, immagine, sfocatura, ripresa, episodi futuri e
      // ordinamento sono applicati da ContinueWatchingRowPolicy nella Home.
      item(key = "continue_watching") {
        MobileSettingsExpandableSection(
          title = stringResource(R.string.settings_cw_section_title),
          subtitle = stringResource(R.string.settings_cw_section_desc),
          expanded = continueWatchingExpanded,
          expandedLabel = stringResource(R.string.settings_cw_expanded),
          collapsedLabel = stringResource(R.string.settings_cw_collapsed),
          onToggle = { continueWatchingExpanded = !continueWatchingExpanded }
        ) {
          MobileSettingsSwitchRow(
            icon = Icons.Default.PlayArrow,
            title = stringResource(R.string.settings_cw_show),
            checked = state.continueWatching.showRow,
            onCheckedChange = viewModel::setContinueWatchingShowRow
          )
          MobileSettingsNote(text = stringResource(R.string.settings_cw_show_desc))

          SettingsContinueWatchingLayoutSelector(
            title = stringResource(R.string.settings_cw_layout),
            selected = state.continueWatching.layout,
            onSelect = viewModel::setContinueWatchingLayout
          )

          MobileSettingsSwitchRow(
            icon = Icons.Default.Image,
            title = stringResource(R.string.settings_cw_episode_thumbnails),
            checked = state.continueWatching.useEpisodeThumbnails,
            onCheckedChange = viewModel::setContinueWatchingEpisodeThumbnails
          )
          MobileSettingsNote(text = stringResource(R.string.settings_cw_episode_thumbnails_desc))

          MobileSettingsSwitchRow(
            icon = Icons.Default.VisibilityOff,
            title = stringResource(R.string.settings_cw_blur_unwatched),
            checked = state.continueWatching.blurUnwatched,
            onCheckedChange = viewModel::setContinueWatchingBlurUnwatched
          )
          MobileSettingsNote(text = stringResource(R.string.settings_cw_blur_unwatched_desc))

          MobileSettingsSwitchRow(
            icon = Icons.Default.SkipNext,
            title = stringResource(R.string.settings_cw_next_from_furthest),
            checked = state.continueWatching.nextEpisodeFromFurthest,
            onCheckedChange = viewModel::setContinueWatchingNextEpisodeFromFurthest
          )
          MobileSettingsNote(text = stringResource(R.string.settings_cw_next_from_furthest_desc))

          MobileSettingsSwitchRow(
            icon = Icons.Default.PlayArrow,
            title = stringResource(R.string.settings_cw_show_unaired),
            checked = state.continueWatching.showUnairedEpisodes,
            onCheckedChange = viewModel::setContinueWatchingShowUnairedEpisodes
          )
          MobileSettingsNote(text = stringResource(R.string.settings_cw_show_unaired_desc))

          MobileSettingsRow(
            icon = Icons.AutoMirrored.Filled.Sort,
            title = stringResource(R.string.settings_cw_sort),
            value = continueWatchingSortLabel(state.continueWatching.sort),
            onClick = { showContinueWatchingSortDialog = true }
          )
          MobileSettingsNote(text = stringResource(R.string.settings_cw_sort_desc))
        }
      }

      // ── RICERCA E CATALOGHI ────────────────────────────────────────────
      item(key = "search") {
        MobileSettingsSection(title = stringResource(R.string.settings_search_section)) {
          MobileSettingsRow(
            icon = Icons.Default.Search,
            title = stringResource(R.string.settings_tmdb_genre_fallback),
            value = stringResource(
              if (state.tmdbGenreFallbackEnabled) R.string.settings_value_on
              else R.string.settings_value_off
            ),
            valueColor = if (state.tmdbGenreFallbackEnabled) NovaGreen else NovaTextMuted,
            onClick = {
              viewModel.setTmdbGenreFallbackEnabled(!state.tmdbGenreFallbackEnabled)
            }
          )
          MobileSettingsNote(text = stringResource(R.string.settings_tmdb_genre_fallback_desc))
          MobileSettingsNote(
            text = stringResource(R.string.settings_tmdb_fallback_unavailable_desc)
          )
        }
      }

      // ── RIPRODUZIONE ───────────────────────────────────────────────────
      item(key = "playback") {
        MobileSettingsSection(title = stringResource(R.string.settings_sec_playback)) {
          MobileSettingsSwitchRow(
            icon = Icons.Default.PlayArrow,
            title = stringResource(R.string.settings_autoplay),
            checked = state.autoplayEnabled,
            onCheckedChange = viewModel::setAutoplayEnabled
          )
          MobileSettingsNote(text = stringResource(R.string.settings_autoplay_desc))
          MobileSettingsRow(
            icon = Icons.Default.HighQuality,
            title = stringResource(R.string.settings_preferred_resolution),
            value = resolutionLabel(state.playback.preferredResolution),
            onClick = { showResolutionDialog = true }
          )
          MobileSettingsRow(
            icon = Icons.Default.Language,
            title = stringResource(R.string.settings_preferred_audio_language),
            value = audioLanguageLabel(state.playback.preferredAudioLanguage),
            onClick = { showAudioLanguageDialog = true }
          )
          MobileSettingsSwitchRow(
            icon = Icons.Default.Language,
            title = stringResource(R.string.settings_prioritize_italian_audio),
            checked = state.playback.prioritizeItalianAudio,
            onCheckedChange = viewModel::setPrioritizeItalianAudio
          )
          MobileSettingsSwitchRow(
            icon = Icons.Default.PlayArrow,
            title = stringResource(R.string.settings_autoplay_next_episode),
            checked = state.playback.autoPlayNextEpisodeEnabled,
            onCheckedChange = viewModel::setAutoPlayNextEpisode
          )
          MobileSettingsNote(
            text = stringResource(R.string.settings_autoplay_next_unavailable_desc)
          )
          MobileSettingsSwitchRow(
            icon = Icons.Default.Replay,
            title = stringResource(R.string.settings_auto_resume),
            checked = state.playback.autoResume,
            onCheckedChange = viewModel::setAutoResume
          )

          // Overlay e avvisi di riproduzione: stesse opzioni e stessi default della TV.
          MobileSettingsSwitchRow(
            icon = Icons.Default.Movie,
            title = stringResource(R.string.settings_loading_overlay),
            checked = state.playback.loadingOverlayEnabled,
            onCheckedChange = viewModel::setLoadingOverlay
          )
          MobileSettingsNote(text = stringResource(R.string.settings_loading_overlay_desc))
          MobileSettingsSwitchRow(
            icon = Icons.Default.HourglassTop,
            title = stringResource(R.string.settings_loading_status),
            checked = state.playback.loadingStatusEnabled,
            onCheckedChange = viewModel::setLoadingStatus
          )
          MobileSettingsNote(text = stringResource(R.string.settings_loading_status_desc))
          MobileSettingsSwitchRow(
            icon = Icons.Default.Pause,
            title = stringResource(R.string.settings_pause_overlay),
            checked = state.playback.pauseOverlayEnabled,
            onCheckedChange = viewModel::setPauseOverlay
          )
          MobileSettingsNote(text = stringResource(R.string.settings_pause_overlay_desc))
          MobileSettingsSwitchRow(
            icon = Icons.Default.FamilyRestroom,
            title = stringResource(R.string.settings_parental_guide),
            checked = state.playback.parentalGuideEnabled,
            onCheckedChange = viewModel::setParentalGuide
          )
          MobileSettingsNote(text = stringResource(R.string.settings_parental_guide_desc))
        }
      }

      // ── SELEZIONE FLUSSO ───────────────────────────────────────────────
      item(key = "stream") {
        MobileSettingsSection(title = stringResource(R.string.settings_sec_stream_selection)) {
          MobileSettingsSwitchRow(
            icon = Icons.Default.Restore,
            title = stringResource(R.string.settings_reuse_last_stream),
            checked = state.playback.reuseLastWorkingStreamEnabled,
            onCheckedChange = viewModel::setReuseLastWorkingStreamEnabled
          )
          MobileSettingsNote(text = stringResource(R.string.settings_reuse_last_stream_desc))
          MobileSettingsRow(
            icon = Icons.Default.Speed,
            title = stringResource(R.string.settings_reuse_last_stream_cache_duration),
            value = lastStreamCacheDurationLabel(state.playback.lastWorkingStreamCacheDurationHours),
            onClick = { showReuseCacheDialog = true }
          )
        }
      }

      // ── SOTTOTITOLI ────────────────────────────────────────────────────
      item(key = "subtitles") {
        MobileSettingsSection(title = stringResource(R.string.settings_sec_subtitles)) {
          MobileSettingsSwitchRow(
            icon = Icons.Default.Subtitles,
            title = stringResource(R.string.settings_auto_subtitles),
            checked = state.playback.subtitlesEnabled,
            onCheckedChange = viewModel::setSubtitlesEnabled
          )
          MobileSettingsRow(
            icon = Icons.Default.Translate,
            title = stringResource(R.string.settings_preferred_subtitle_lang),
            value = subtitleLanguageLabel(state.playback.preferredSubtitleLanguage),
            onClick = { showSubtitleLanguageDialog = true }
          )
          MobileSettingsRow(
            icon = Icons.Default.FormatSize,
            title = stringResource(R.string.settings_subtitle_size),
            value = subtitleSizeLabel(state.playback.subtitleSize),
            onClick = { showSubtitleSizeDialog = true }
          )
          MobileSettingsRow(
            icon = Icons.Default.Wallpaper,
            title = stringResource(R.string.settings_subtitle_background),
            value = subtitleBackgroundLabel(state.playback.subtitleBackground),
            onClick = { showSubtitleBackgroundDialog = true }
          )
          MobileSettingsRow(
            icon = Icons.Default.HdrOn,
            title = stringResource(R.string.sub_opacity),
            value = subtitleOpacityLabel(state.playback.subtitleOpacity),
            onClick = { showSubtitleOpacityDialog = true }
          )
          MobileSettingsRow(
            icon = Icons.Default.VerticalAlignBottom,
            title = stringResource(R.string.settings_subtitle_position),
            value = subtitlePositionLabel(state.playback.subtitlePosition),
            onClick = { showSubtitlePositionDialog = true }
          )
          MobileSettingsSwitchRow(
            icon = Icons.Default.ClosedCaption,
            title = stringResource(R.string.settings_forced_subtitles),
            checked = state.playback.forcedSubtitlesEnabled,
            onCheckedChange = viewModel::setForcedSubtitlesEnabled
          )
        }
      }

      // ── OPENSUBTITLES ──────────────────────────────────────────────────
      item(key = "opensubtitles") {
        MobileSettingsSection(title = "OpenSubtitles") {
          MobileSettingsRow(
            icon = Icons.Default.VpnKey,
            title = stringResource(R.string.settings_opensubtitles_api_key),
            value = state.openSubtitlesApiKey
              ?.let { MobileSettingsPolicy.maskApiKey(it) }
              ?: stringResource(R.string.settings_not_configured),
            valueColor = if (!state.openSubtitlesApiKey.isNullOrBlank()) NovaGreen else NovaTextMuted,
            onClick = { showOpenSubtitlesKeyDialog = true }
          )
          MobileSettingsRow(
            icon = Icons.Default.CloudDone,
            title = stringResource(R.string.settings_opensubtitles_verify_connection),
            value = when (val connection = state.openSubtitlesState) {
              is MobileOpenSubtitlesState.Checking -> stringResource(R.string.settings_status_checking)
              is MobileOpenSubtitlesState.Connected -> stringResource(R.string.settings_opensubtitles_connected)
              is MobileOpenSubtitlesState.NotConfigured -> stringResource(R.string.settings_not_configured)
              is MobileOpenSubtitlesState.Error -> stringResource(R.string.settings_update_error_short)
              is MobileOpenSubtitlesState.Unknown ->
                if (state.openSubtitlesApiKey.isNullOrBlank()) {
                  stringResource(R.string.settings_not_configured)
                } else {
                  null
                }
            },
            valueColor = when (state.openSubtitlesState) {
              is MobileOpenSubtitlesState.Connected -> NovaGreen
              is MobileOpenSubtitlesState.Error -> NovaRed
              else -> NovaCyanBright
            },
            onClick = { viewModel.verifyOpenSubtitlesConnection() }
          )
          val note = when {
            state.openSubtitlesApiKey.isNullOrBlank() ->
              stringResource(R.string.settings_opensubtitles_missing_desc)
            state.openSubtitlesState is MobileOpenSubtitlesState.Error &&
              (state.openSubtitlesState as MobileOpenSubtitlesState.Error).reason ==
              OpenSubtitlesVerifyError.INVALID_KEY ->
              stringResource(R.string.settings_opensubtitles_key_not_valid_desc)
            state.openSubtitlesState is MobileOpenSubtitlesState.Error &&
              (state.openSubtitlesState as MobileOpenSubtitlesState.Error).reason ==
              OpenSubtitlesVerifyError.NETWORK ->
              stringResource(R.string.settings_opensubtitles_network_error)
            state.openSubtitlesState is MobileOpenSubtitlesState.Error ->
              stringResource(R.string.settings_opensubtitles_http_error)
            else -> null
          }
          note?.let { MobileSettingsNote(text = it) }
          MobileSettingsNote(
            text = stringResource(R.string.settings_opensubtitles_mobile_note),
            color = NovaOrange
          )
        }
      }

      // ── AVANZATO ───────────────────────────────────────────────────────
      item(key = "advanced") {
        MobileSettingsSection(title = stringResource(R.string.settings_sec_advanced)) {
          MobileSettingsRow(
            icon = Icons.Default.Memory,
            title = stringResource(R.string.settings_adv_target_buffer),
            value = targetBufferLabel(state.playback.targetBuffer),
            onClick = { showTargetBufferDialog = true }
          )
          MobileSettingsRow(
            icon = Icons.Default.Speed,
            title = stringResource(R.string.settings_adv_initial_buffer),
            value = initialBufferLabel(state.playback.initialBuffer),
            onClick = { showInitialBufferDialog = true }
          )
          MobileSettingsRow(
            icon = Icons.Default.Replay,
            title = stringResource(R.string.settings_adv_back_buffer),
            value = backBufferLabel(state.playback.backBuffer),
            onClick = { showBackBufferDialog = true }
          )
          MobileSettingsSwitchRow(
            icon = Icons.Default.VolumeUp,
            title = stringResource(R.string.settings_adv_audio_passthrough),
            checked = state.playback.audioPassthroughEnabled,
            onCheckedChange = viewModel::setAudioPassthrough
          )
          MobileSettingsSwitchRow(
            icon = Icons.Default.SyncAlt,
            title = stringResource(R.string.settings_adv_audio_tunneling),
            checked = state.playback.audioTunnelingEnabled,
            onCheckedChange = viewModel::setAudioTunneling
          )
          MobileSettingsRow(
            icon = Icons.Default.Extension,
            title = stringResource(R.string.settings_adv_decoder_fallback),
            value = decoderFallbackLabel(state.playback.decoderFallbackMode),
            onClick = { showDecoderDialog = true }
          )
          MobileSettingsSwitchRow(
            icon = Icons.Default.Tv,
            title = stringResource(R.string.settings_adv_afr),
            checked = state.playback.autoFrameRateMatching,
            onCheckedChange = viewModel::setAutoFrameRateMatching
          )
          MobileSettingsSwitchRow(
            icon = Icons.Default.HdrOn,
            title = stringResource(R.string.settings_adv_dolby_vision_fallback),
            checked = state.playback.dolbyVisionFallbackEnabled,
            onCheckedChange = viewModel::setDolbyVisionFallback
          )
          MobileSettingsSwitchRow(
            icon = Icons.Default.Assessment,
            title = stringResource(R.string.settings_adv_stats_overlay),
            checked = state.playback.debugOverlayEnabled,
            onCheckedChange = viewModel::setDebugOverlay
          )
          MobileSettingsRow(
            icon = Icons.Default.DeleteSweep,
            title = stringResource(R.string.settings_adv_clear_cache_title),
            value = if (isClearingCache) stringResource(R.string.settings_in_progress) else null,
            onClick = {
              viewModel.clearCache(context) { success ->
                val message = if (success) {
                  context.getString(R.string.settings_adv_cache_cleared)
                } else {
                  context.getString(R.string.settings_adv_cache_clear_error)
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
              }
            }
          )
        }
      }
    }
  }

  // ── Dialog ───────────────────────────────────────────────────────────

  if (showAppLanguageDialog) {
    val options = MobileSettingsPolicy.APP_LANGUAGE_CODES
    MobileChoiceDialog(
      title = stringResource(R.string.settings_app_language),
      options = options.map { appLanguageLabel(it) },
      selectedIndex = options.indexOf(state.appLanguage).coerceAtLeast(0),
      onSelect = { index ->
        viewModel.setAppLanguage(options[index])
        showAppLanguageDialog = false
      },
      onDismiss = { showAppLanguageDialog = false }
    )
  }

  if (showEngineModeDialog) {
    val options = listOf(
      stringResource(R.string.settings_mode_debrid),
      stringResource(R.string.settings_mode_web_scraper)
    )
    MobileChoiceDialog(
      title = stringResource(R.string.settings_playback_mode),
      options = options,
      selectedIndex = if (state.engineMode == StreamingEngineMode.DEBRID_TORBOX) 0 else 1,
      onSelect = { index ->
        viewModel.setEngineMode(
          if (index == 0) StreamingEngineMode.DEBRID_TORBOX else StreamingEngineMode.HTTP_WEB
        )
        showEngineModeDialog = false
      },
      onDismiss = { showEngineModeDialog = false }
    )
  }

  if (showContinueWatchingSortDialog) {
    // Stesse voci dell'enum TV: ogni modalità è implementata dal policy della riga.
    val options = ContinueWatchingSort.entries
    MobileChoiceDialog(
      title = stringResource(R.string.settings_cw_sort),
      options = options.map { continueWatchingSortLabel(it) },
      selectedIndex = options.indexOf(state.continueWatching.sort).coerceAtLeast(0),
      onSelect = { index ->
        viewModel.setContinueWatchingSort(options[index])
        showContinueWatchingSortDialog = false
      },
      onDismiss = { showContinueWatchingSortDialog = false }
    )
  }

  if (showTorBoxKeyDialog) {
    MobileTextInputDialog(
      title = stringResource(R.string.settings_torbox_api_key),
      hint = stringResource(R.string.settings_torbox_key_hint),
      initialText = state.torBoxApiKey.orEmpty(),
      onConfirm = { key ->
        showTorBoxKeyDialog = false
        viewModel.setTorBoxApiKey(key)
        viewModel.verifyTorBoxAccount()
      },
      onDismiss = { showTorBoxKeyDialog = false }
    )
  }

  if (showOpenSubtitlesKeyDialog) {
    MobileTextInputDialog(
      title = stringResource(R.string.settings_opensubtitles_api_key),
      hint = stringResource(R.string.settings_opensubtitles_key_hint),
      initialText = state.openSubtitlesApiKey.orEmpty(),
      secure = true,
      onConfirm = { key ->
        showOpenSubtitlesKeyDialog = false
        viewModel.setOpenSubtitlesApiKey(key)
      },
      onDismiss = { showOpenSubtitlesKeyDialog = false }
    )
  }

  if (showResolutionDialog) {
    val options = PreferredResolution.entries
    MobileChoiceDialog(
      title = stringResource(R.string.settings_preferred_resolution),
      options = options.map { resolutionLabel(it) },
      selectedIndex = options.indexOf(state.playback.preferredResolution).coerceAtLeast(0),
      onSelect = { index ->
        viewModel.setPreferredResolution(options[index])
        showResolutionDialog = false
      },
      onDismiss = { showResolutionDialog = false }
    )
  }

  if (showAudioLanguageDialog) {
    val options = MobileSettingsPolicy.AUDIO_LANGUAGE_CODES
    MobileChoiceDialog(
      title = stringResource(R.string.settings_preferred_audio_language),
      options = options.map { audioLanguageLabel(it) },
      selectedIndex = options.indexOf(state.playback.preferredAudioLanguage).coerceAtLeast(0),
      onSelect = { index ->
        viewModel.setPreferredAudioLanguage(options[index])
        showAudioLanguageDialog = false
      },
      onDismiss = { showAudioLanguageDialog = false }
    )
  }

  if (showSubtitleLanguageDialog) {
    val options = MobileSettingsPolicy.SUBTITLE_LANGUAGE_CODES
    MobileChoiceDialog(
      title = stringResource(R.string.settings_preferred_subtitle_lang),
      options = options.map { subtitleLanguageLabel(it) },
      selectedIndex = options.indexOf(state.playback.preferredSubtitleLanguage).coerceAtLeast(0),
      onSelect = { index ->
        viewModel.setPreferredSubtitleLanguage(options[index])
        showSubtitleLanguageDialog = false
      },
      onDismiss = { showSubtitleLanguageDialog = false }
    )
  }

  if (showSubtitleSizeDialog) {
    val options = SubtitleSize.entries
    MobileChoiceDialog(
      title = stringResource(R.string.settings_subtitle_size),
      options = options.map { subtitleSizeLabel(it) },
      selectedIndex = options.indexOf(state.playback.subtitleSize).coerceAtLeast(0),
      onSelect = { index ->
        viewModel.setSubtitleSize(options[index])
        showSubtitleSizeDialog = false
      },
      onDismiss = { showSubtitleSizeDialog = false }
    )
  }

  if (showSubtitleOpacityDialog) {
    val options = SubtitleOpacity.entries
    MobileChoiceDialog(
      title = stringResource(R.string.sub_opacity),
      options = options.map { subtitleOpacityLabel(it) },
      selectedIndex = options.indexOf(state.playback.subtitleOpacity).coerceAtLeast(0),
      onSelect = { index ->
        viewModel.setSubtitleOpacity(options[index])
        showSubtitleOpacityDialog = false
      },
      onDismiss = { showSubtitleOpacityDialog = false }
    )
  }
  if (showSubtitleBackgroundDialog) {
    val options = SubtitleBackground.entries
    MobileChoiceDialog(
      title = stringResource(R.string.settings_subtitle_background),
      options = options.map { subtitleBackgroundLabel(it) },
      selectedIndex = options.indexOf(state.playback.subtitleBackground).coerceAtLeast(0),
      onSelect = { index ->
        viewModel.setSubtitleBackground(options[index])
        showSubtitleBackgroundDialog = false
      },
      onDismiss = { showSubtitleBackgroundDialog = false }
    )
  }

  if (showSubtitlePositionDialog) {
    val options = SubtitlePosition.entries
    MobileChoiceDialog(
      title = stringResource(R.string.settings_subtitle_position),
      options = options.map { subtitlePositionLabel(it) },
      selectedIndex = options.indexOf(state.playback.subtitlePosition).coerceAtLeast(0),
      onSelect = { index ->
        viewModel.setSubtitlePosition(options[index])
        showSubtitlePositionDialog = false
      },
      onDismiss = { showSubtitlePositionDialog = false }
    )
  }

  if (showReuseCacheDialog) {
    val options = LastStreamCacheDuration.entries
    MobileChoiceDialog(
      title = stringResource(R.string.settings_reuse_last_stream_cache_duration),
      options = options.map { lastStreamCacheDurationLabel(it.hours) },
      selectedIndex = options
        .indexOfFirst { it.hours == state.playback.lastWorkingStreamCacheDurationHours }
        .coerceAtLeast(0),
      onSelect = { index ->
        viewModel.setLastStreamCacheDuration(options[index])
        showReuseCacheDialog = false
      },
      onDismiss = { showReuseCacheDialog = false }
    )
  }

  if (showTargetBufferDialog) {
    val options = TargetBufferOption.entries
    MobileChoiceDialog(
      title = stringResource(R.string.settings_adv_target_buffer),
      options = options.map { targetBufferLabel(it) },
      selectedIndex = options.indexOf(state.playback.targetBuffer).coerceAtLeast(0),
      onSelect = { index ->
        viewModel.setTargetBuffer(options[index])
        showTargetBufferDialog = false
      },
      onDismiss = { showTargetBufferDialog = false }
    )
  }

  if (showInitialBufferDialog) {
    val options = InitialBufferOption.entries
    MobileChoiceDialog(
      title = stringResource(R.string.settings_adv_initial_buffer),
      options = options.map { initialBufferLabel(it) },
      selectedIndex = options.indexOf(state.playback.initialBuffer).coerceAtLeast(0),
      onSelect = { index ->
        viewModel.setInitialBuffer(options[index])
        showInitialBufferDialog = false
      },
      onDismiss = { showInitialBufferDialog = false }
    )
  }

  if (showBackBufferDialog) {
    val options = BackBufferOption.entries
    MobileChoiceDialog(
      title = stringResource(R.string.settings_adv_back_buffer),
      options = options.map { backBufferLabel(it) },
      selectedIndex = options.indexOf(state.playback.backBuffer).coerceAtLeast(0),
      onSelect = { index ->
        viewModel.setBackBuffer(options[index])
        showBackBufferDialog = false
      },
      onDismiss = { showBackBufferDialog = false }
    )
  }

  if (showDecoderDialog) {
    val options = DecoderFallbackMode.entries
    MobileChoiceDialog(
      title = stringResource(R.string.settings_adv_decoder_fallback),
      options = options.map { decoderFallbackLabel(it) },
      selectedIndex = options.indexOf(state.playback.decoderFallbackMode).coerceAtLeast(0),
      onSelect = { index ->
        viewModel.setDecoderFallback(options[index])
        showDecoderDialog = false
      },
      onDismiss = { showDecoderDialog = false }
    )
  }

  // ── Controllo genitori ────────────────────────────────────────────────

  parentalStep?.let { step ->
    val subtitle = when (step) {
      is MobileParentalStep.CreateNew, is MobileParentalStep.ChangeNewPin ->
        stringResource(R.string.parental_dialog_create_pin)
      is MobileParentalStep.ConfirmNew, is MobileParentalStep.ChangeConfirmPin ->
        stringResource(R.string.parental_dialog_confirm_pin)
      is MobileParentalStep.VerifyToManage ->
        stringResource(R.string.parental_dialog_enter_current_pin)
    }
    MobilePinDialog(
      title = stringResource(R.string.parental_dialog_enter_pin_title),
      subtitle = subtitle,
      errorMessage = parentalError,
      isNewPin = step is MobileParentalStep.CreateNew ||
        step is MobileParentalStep.ConfirmNew ||
        step is MobileParentalStep.ChangeNewPin ||
        step is MobileParentalStep.ChangeConfirmPin,
      onSubmit = { pin ->
        when (val current = step) {
          is MobileParentalStep.CreateNew -> {
            parentalError = null
            parentalStep = MobileParentalStep.ConfirmNew(pin)
          }
          is MobileParentalStep.ConfirmNew -> {
            if (pin == current.firstPin) {
              // La TV sovrascrive il PIN esistente quando il controllo è spento:
              // senza questo, dopo un "Disattiva" il nuovo PIN verrebbe rifiutato.
              val saved = viewModel.replaceParentalPin(pin)
              if (saved) {
                viewModel.setParentalEnabled(true, pin)
                parentalStep = null
                parentalError = null
              } else {
                parentalError = pinMismatch
              }
            } else {
              parentalError = pinMismatch
            }
          }
          is MobileParentalStep.VerifyToManage -> {
            if (viewModel.verifyParentalPin(pin)) {
              parentalStep = null
              parentalError = null
              showParentalManageDialog = true
            } else {
              parentalError = pinWrong
            }
          }
          is MobileParentalStep.ChangeNewPin -> {
            parentalError = null
            parentalStep = MobileParentalStep.ChangeConfirmPin(pin)
          }
          is MobileParentalStep.ChangeConfirmPin -> {
            if (pin == current.firstPin) {
              viewModel.replaceParentalPin(pin)
              parentalStep = null
              parentalError = null
            } else {
              parentalError = pinMismatch
            }
          }
        }
      },
      onDismiss = {
        parentalStep = null
        parentalError = null
      }
    )
  }

  if (showParentalManageDialog) {
    val options = listOf(
      stringResource(R.string.parental_option_level, "${state.parentalLevel}+"),
      stringResource(R.string.parental_option_change_pin),
      stringResource(R.string.parental_option_disable)
    )
    MobileChoiceDialog(
      title = stringResource(R.string.parental_dialog_manage_title),
      options = options,
      selectedIndex = 0,
      onSelect = { index ->
        showParentalManageDialog = false
        when (index) {
          0 -> showParentalLevelDialog = true
          1 -> {
            parentalError = null
            parentalStep = MobileParentalStep.ChangeNewPin
          }
          2 -> viewModel.disableParentalControl()
        }
      },
      onDismiss = { showParentalManageDialog = false }
    )
  }

  if (showParentalLevelDialog) {
    val options = MobileSettingsPolicy.PARENTAL_LEVELS
    MobileChoiceDialog(
      title = stringResource(R.string.parental_select_level_title),
      options = options.map { "$it+" },
      selectedIndex = options.indexOf(state.parentalLevel).coerceAtLeast(0),
      onSelect = { index ->
        viewModel.setParentalLevel(options[index])
        showParentalLevelDialog = false
      },
      onDismiss = { showParentalLevelDialog = false }
    )
  }

  // Dialog del controllo aggiornamenti MANUALE: in scena dal momento in cui il
  // controllo parte (Checking) fino alla chiusura dell'esito (torna Idle).
  if (updateCheckState != UpdateCheckState.Idle) {
    MobileUpdateResultDialog(
      state = updateCheckState,
      downloadState = updateDownloadState,
      onDownload = { viewModel.downloadUpdate(context) },
      onDismiss = { viewModel.dismissUpdateResult() }
    )
  }

  // ── Login SIMKL ───────────────────────────────────────────────────────

  if (simklLoginState.visible) {
    val prompt = simklLoginState.prompt
    var secondsLeft by remember(prompt) {
      mutableStateOf(prompt?.secondsLeft(System.currentTimeMillis()) ?: 0L)
    }
    LaunchedEffect(prompt) {
      val active = prompt ?: return@LaunchedEffect
      while (true) {
        val left = active.secondsLeft(System.currentTimeMillis())
        secondsLeft = left
        if (left <= 0L) break
        delay(1_000L)
      }
    }
    val errorMessage = simklLoginState.error?.let { simklLoginErrorLabel(it) }
    val status = when {
      simklLoginState.requesting -> stringResource(R.string.settings_simkl_requesting)
      simklLoginState.waiting -> stringResource(R.string.settings_simkl_waiting_approval)
      errorMessage != null -> null
      prompt != null && secondsLeft <= 0L -> stringResource(R.string.settings_simkl_expired)
      else -> null
    }
    MobileSimklLoginDialog(
      prompt = prompt,
      waiting = simklLoginState.waiting,
      requesting = simklLoginState.requesting,
      errorMessage = errorMessage,
      canRetry = simklLoginState.canRetry,
      countdownSeconds = secondsLeft,
      instruction = if (prompt != null) {
        if (prompt.qrPrefillsCode) {
          stringResource(R.string.settings_simkl_login_instruction_prefilled, prompt.verificationUri)
        } else {
          stringResource(R.string.settings_simkl_login_instruction_code, prompt.verificationUri)
        }
      } else "",
      statusMessage = status,
      onRetry = { viewModel.retrySimklLogin() },
      onOpenLink = {
        // Il pulsante apre sempre la pagina web base di verifica. Il qrPayload
        // puo' includere il codice completo e resta riservato al contenuto del QR.
        prompt?.let { openSimklLink(context, it.verificationUri) }
      },
      onDismiss = { viewModel.cancelSimklLogin() }
    )
  }
}

/** Valore della riga «Controlla aggiornamenti» per lo stato corrente del controllo. */
@Composable
private fun updateCheckStateValue(state: UpdateCheckState): String = when (state) {
  UpdateCheckState.Idle -> stringResource(R.string.settings_update_check_now)
  UpdateCheckState.Checking -> stringResource(R.string.settings_update_checking)
  is UpdateCheckState.UpToDate -> stringResource(R.string.settings_update_upto_date)
  is UpdateCheckState.UpdateAvailable ->
    stringResource(R.string.settings_update_available, state.newVersion)
  is UpdateCheckState.VersionMismatch -> stringResource(R.string.settings_update_error_short)
  is UpdateCheckState.Error -> stringResource(R.string.settings_update_error_short)
}

/** Colore del valore della riga «Controlla aggiornamenti». */
@Composable
private fun updateCheckStateValueColor(state: UpdateCheckState): Color = when (state) {
  UpdateCheckState.Idle -> NovaCyan
  UpdateCheckState.Checking -> NovaCyan
  is UpdateCheckState.UpToDate -> NovaGreen
  is UpdateCheckState.UpdateAvailable -> NovaGreen
  is UpdateCheckState.VersionMismatch -> NovaRed
  is UpdateCheckState.Error -> NovaRed
}

@Composable
private fun SettingsHeader(onBack: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = 8.dp, bottom = 2.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    IconButton(onClick = onBack) {
      Icon(
        Icons.Default.ArrowBack,
        contentDescription = stringResource(R.string.action_back),
        tint = NovaTextPrimary
      )
    }
    Column {
      Text(
        text = stringResource(R.string.settings_title),
        color = NovaTextPrimary,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold
      )
      Text(
        text = stringResource(R.string.settings_subtitle),
        color = NovaTextSecondary,
        fontSize = 12.sp
      )
    }
  }
}

/**
 * Sezione Account SIMKL: collegamento, sincronizzazione e avvisi.
 *
 * I testi di sincronizzazione arrivano dal core (condiviso con la TV) e vengono
 * affiancati da uno stato localizzato: nessun messaggio tecnico viene inventato.
 */
@Composable
private fun SettingsSimklSection(
  state: MobileSettingsUiState,
  onAccountClick: () -> Unit,
  onSyncClick: () -> Unit
) {
  MobileSettingsSection(title = stringResource(R.string.settings_simkl_account)) {
    MobileSettingsRow(
      icon = Icons.Default.CloudDone,
      title = if (state.simklAccount.isLoggedIn) {
        stringResource(R.string.settings_simkl_connected)
      } else {
        stringResource(R.string.settings_simkl_connect)
      },
      value = when {
        state.simklAccount.isLoggedIn ->
          state.simklAccount.username ?: stringResource(R.string.settings_simkl_linked)
        else -> stringResource(R.string.settings_simkl_not_connected)
      },
      valueColor = when {
        state.simklAccount.isLoggedIn && state.simklAccount.sessionExpired -> NovaOrange
        state.simklAccount.isLoggedIn -> NovaGreen
        else -> NovaCyanBright
      },
      onClick = onAccountClick
    )
    if (state.simklAccount.isLoggedIn) {
      MobileSettingsRow(
        icon = Icons.Default.Sync,
        title = stringResource(R.string.settings_simkl_sync_now),
        value = when {
          state.simklSyncing -> stringResource(R.string.settings_simkl_syncing)
          state.simklLastSyncAt != null ->
            stringResource(
              R.string.settings_simkl_last_sync,
              formatSyncTimestamp(state.simklLastSyncAt!!)
            )
          else -> stringResource(R.string.settings_simkl_never_synced)
        },
        valueColor = if (state.simklSyncing) NovaCyanBright else NovaTextSecondary,
        onClick = onSyncClick
      )
    }

    val warning = when {
      !state.simklAccount.isLoggedIn -> null
      state.simklAccount.sessionExpired -> stringResource(R.string.settings_simkl_session_expired_note)
      state.simklAccount.isReadOnly -> stringResource(R.string.settings_simkl_readonly_note)
      else -> null
    }
    warning?.let { MobileSettingsNote(text = it, color = NovaOrange) }

    val notice = state.simklNotice
    when (notice) {
      is MobileSimklNotice.Connected -> MobileSettingsNote(
        text = stringResource(
          R.string.settings_simkl_linked_notice,
          notice.username ?: state.simklAccount.username.orEmpty()
        ),
        color = NovaGreen
      )
      is MobileSimklNotice.Disconnected -> MobileSettingsNote(
        text = stringResource(R.string.settings_simkl_unlinked_notice),
        color = NovaOrange
      )
      is MobileSimklNotice.Sync -> SimklSyncNote(notice.result)
      null -> state.simklLastResult?.let { result ->
        if (result.status != SimklSyncStatus.UP_TO_DATE ||
          result.adoptedTotal > 0 ||
          result.watchlistImported > 0
        ) {
          SimklSyncNote(result)
        }
      }
    }
  }
}

@Composable
private fun SimklSyncNote(result: SimklSyncResult) {
  val statusText = when (result.status) {
    SimklSyncStatus.NOT_LOGGED_IN -> stringResource(R.string.settings_simkl_sync_not_logged_in)
    SimklSyncStatus.UP_TO_DATE -> stringResource(R.string.settings_simkl_sync_uptodate)
    SimklSyncStatus.SYNCED -> stringResource(R.string.settings_simkl_sync_done)
    SimklSyncStatus.PARTIAL -> stringResource(R.string.settings_simkl_sync_partial)
    SimklSyncStatus.FAILED -> stringResource(R.string.settings_simkl_sync_failed)
    SimklSyncStatus.SESSION_EXPIRED -> stringResource(R.string.settings_simkl_sync_session_expired)
  }
  val hasCounts = result.adoptedMovies > 0 ||
    result.adoptedEpisodes > 0 ||
    result.adoptedLibraryEpisodes > 0
  val counts = if (hasCounts) {
    stringResource(
      R.string.settings_simkl_sync_counts,
      result.adoptedMovies,
      result.adoptedEpisodes + result.adoptedLibraryEpisodes
    )
  } else null
  val warning = result.status == SimklSyncStatus.FAILED ||
    result.status == SimklSyncStatus.PARTIAL ||
    result.status == SimklSyncStatus.SESSION_EXPIRED
  MobileSettingsNote(
    text = listOfNotNull(statusText, counts).joinToString(" "),
    color = if (warning) NovaOrange else NovaTextSecondary
  )
}

@Composable
private fun simklLoginErrorLabel(error: MobileSimklLoginError): String = when (error) {
  MobileSimklLoginError.NOT_AVAILABLE -> stringResource(R.string.settings_simkl_error_not_available)
  MobileSimklLoginError.CLIENT_REJECTED -> stringResource(R.string.settings_simkl_error_client_rejected)
  MobileSimklLoginError.NETWORK -> stringResource(R.string.settings_simkl_error_network)
  MobileSimklLoginError.INVALID_RESPONSE -> stringResource(R.string.settings_simkl_error_invalid_response)
  MobileSimklLoginError.EXPIRED -> stringResource(R.string.settings_simkl_expired)
  MobileSimklLoginError.AUTH -> stringResource(R.string.settings_simkl_error_generic)
  MobileSimklLoginError.GENERIC -> stringResource(R.string.settings_simkl_error_generic)
}

private fun openSimklLink(context: Context, url: String) {
  val uri = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return
  if (uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank()) {
    Toast.makeText(context, "Indirizzo SIMKL non valido", Toast.LENGTH_LONG).show()
    return
  }

  val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
  // Il context della composizione è quello localizzato creato in MainActivity con
  // createConfigurationContext: avvolge l'Activity ma non è un'Activity, quindi
  // startActivity su di esso lancia AndroidRuntimeException ("outside of an Activity
  // context", crash osservato su telefono). Si parte dall'Activity host; solo se
  // mancante si ricade su un context qualsiasi con il flag NEW_TASK.
  val host = context.hostActivity()
  try {
    if (host != null) {
      host.startActivity(intent)
    } else {
      context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
  } catch (_: ActivityNotFoundException) {
    Toast.makeText(context, "Nessun browser disponibile per aprire SIMKL", Toast.LENGTH_LONG).show()
  } catch (_: SecurityException) {
    Toast.makeText(context, "Impossibile aprire il collegamento SIMKL", Toast.LENGTH_LONG).show()
  } catch (_: IllegalArgumentException) {
    Toast.makeText(context, "Indirizzo SIMKL non valido", Toast.LENGTH_LONG).show()
  }
}

/** Risale la catena di wrapper (localizzazione, tema) fino all'Activity, se c'è. */
private tailrec fun Context.hostActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext?.hostActivity()
  else -> null
}

/**
 * Selettore visuale del layout della riga «Continua a guardare»: tre card touch con
 * anteprima schematica, bordo di selezione e spunta, come la TV 1.0.7. L'intera card
 * è un unico target; nessuna immagine o colore viene preso dai riferimenti.
 */
@Composable
private fun SettingsContinueWatchingLayoutSelector(
  title: String,
  selected: ContinueWatchingLayout,
  onSelect: (ContinueWatchingLayout) -> Unit
) {
  val selectedLabel = stringResource(R.string.settings_cw_layout_selected)
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 8.dp, vertical = 4.dp)
  ) {
    Text(
      text = title,
      color = NovaTextPrimary,
      fontSize = 15.sp,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      ContinueWatchingLayout.entries.forEach { layout ->
        val isSelected = layout == selected
        Column(
          modifier = Modifier
            .weight(1f)
            .selectable(
              selected = isSelected,
              role = Role.RadioButton,
              onClick = { onSelect(layout) }
            )
            .semantics {
              // Stessa semantica della TV: la card selezionata espone lo stato
              // «Selezionato» oltre al segno di spunta visivo.
              if (isSelected) stateDescription = selectedLabel
            }
            .background(NovaCardBg.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
            .border(
              width = if (isSelected) 2.dp else 1.dp,
              color = if (isSelected) NovaCyan else NovaDivider,
              shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          ContinueWatchingLayoutPreview(layout = layout, highlighted = isSelected)
          Spacer(modifier = Modifier.height(8.dp))
          Row(verticalAlignment = Alignment.CenterVertically) {
            if (isSelected) {
              Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = NovaCyanBright,
                modifier = Modifier.size(14.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
              text = continueWatchingLayoutLabel(layout),
              color = if (isSelected) NovaCyanBright else NovaTextSecondary,
              fontSize = 11.sp,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      }
    }
  }
}

/** Anteprima schematica del layout: solo disposizione di immagine e testi. */
@Composable
private fun ContinueWatchingLayoutPreview(
  layout: ContinueWatchingLayout,
  highlighted: Boolean
) {
  val accent = if (highlighted) NovaCyanBright else NovaTextMuted
  val block = NovaTextMuted.copy(alpha = 0.55f)
  val shape = RoundedCornerShape(5.dp)
  val base = Modifier
    .background(NovaCardBgFocused, shape)
    .border(1.dp, accent.copy(alpha = 0.55f), shape)

  when (layout) {
    ContinueWatchingLayout.CARD -> Column(
      modifier = Modifier
        .width(64.dp)
        .height(38.dp)
        .then(base)
    ) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f)
          .background(block)
      )
      Column(modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp)) {
        Box(
          modifier = Modifier
            .width(34.dp)
            .height(3.dp)
            .background(accent, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.height(2.dp))
        Box(
          modifier = Modifier
            .width(24.dp)
            .height(2.dp)
            .background(NovaTextMuted, RoundedCornerShape(2.dp))
        )
      }
    }

    ContinueWatchingLayout.WIDE -> Row(
      modifier = Modifier
        .width(74.dp)
        .height(38.dp)
        .then(base)
    ) {
      Box(
        modifier = Modifier
          .width(24.dp)
          .fillMaxHeight()
          .background(block)
      )
      Column(
        modifier = Modifier
          .weight(1f)
          .fillMaxHeight()
          .padding(horizontal = 5.dp, vertical = 6.dp)
      ) {
        Box(
          modifier = Modifier
            .width(34.dp)
            .height(3.dp)
            .background(accent, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.height(3.dp))
        Box(
          modifier = Modifier
            .fillMaxWidth(0.8f)
            .height(2.dp)
            .background(NovaTextMuted, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.height(2.dp))
        Box(
          modifier = Modifier
            .fillMaxWidth(0.6f)
            .height(2.dp)
            .background(NovaTextMuted, RoundedCornerShape(2.dp))
        )
      }
    }

    ContinueWatchingLayout.POSTER -> Box(
      modifier = Modifier
        .width(26.dp)
        .height(38.dp)
        .then(base)
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(block)
      )
      Column(
        modifier = Modifier
          .align(Alignment.BottomStart)
          .padding(horizontal = 4.dp, vertical = 4.dp)
      ) {
        Box(
          modifier = Modifier
            .width(16.dp)
            .height(2.dp)
            .background(accent, RoundedCornerShape(1.dp))
        )
        Spacer(modifier = Modifier.height(3.dp))
        Box(
          modifier = Modifier
            .width(12.dp)
            .height(2.dp)
            .background(NovaTextMuted, RoundedCornerShape(1.dp))
        )
      }
    }
  }
}

@Composable
private fun continueWatchingLayoutLabel(layout: ContinueWatchingLayout): String = when (layout) {
  ContinueWatchingLayout.CARD -> stringResource(R.string.settings_cw_layout_card)
  ContinueWatchingLayout.WIDE -> stringResource(R.string.settings_cw_layout_wide)
  ContinueWatchingLayout.POSTER -> stringResource(R.string.settings_cw_layout_poster)
}

@Composable
private fun continueWatchingSortLabel(sort: ContinueWatchingSort): String = when (sort) {
  ContinueWatchingSort.DEFAULT -> stringResource(R.string.settings_cw_sort_default)
  ContinueWatchingSort.TITLE -> stringResource(R.string.settings_cw_sort_title)
  ContinueWatchingSort.RATING -> stringResource(R.string.settings_cw_sort_rating)
}

@Composable
private fun appLanguageLabel(code: String): String = when (code) {
  "en" -> stringResource(R.string.lang_english)
  "es" -> stringResource(R.string.lang_spanish)
  "fr" -> stringResource(R.string.lang_french)
  "de" -> stringResource(R.string.lang_german)
  else -> stringResource(R.string.lang_italian)
}

@Composable
private fun audioLanguageLabel(code: String): String = when (code) {
  "it" -> stringResource(R.string.lang_italian)
  "en" -> stringResource(R.string.lang_english)
  "es" -> stringResource(R.string.lang_spanish)
  "fr" -> stringResource(R.string.lang_french)
  "de" -> stringResource(R.string.lang_german)
  else -> stringResource(R.string.lang_original_any)
}

@Composable
private fun subtitleLanguageLabel(code: String): String = when (code) {
  "it" -> stringResource(R.string.lang_sub_italian)
  "en" -> stringResource(R.string.lang_sub_english)
  "es" -> stringResource(R.string.lang_sub_spanish)
  "fr" -> stringResource(R.string.lang_sub_french)
  "de" -> stringResource(R.string.lang_sub_german)
  "pt" -> stringResource(R.string.lang_sub_portuguese)
  else -> stringResource(R.string.lang_original_any)
}

@Composable
private fun resolutionLabel(resolution: PreferredResolution): String = when (resolution) {
  PreferredResolution.AUTO -> stringResource(R.string.res_auto)
  PreferredResolution.UHD_4K -> stringResource(R.string.res_4k)
  PreferredResolution.FULL_HD_1080P -> stringResource(R.string.res_1080p)
  PreferredResolution.HD_720P -> stringResource(R.string.res_720p)
}

@Composable
private fun subtitleSizeLabel(size: SubtitleSize): String = when (size) {
  SubtitleSize.EXTRA_SMALL -> stringResource(R.string.sub_size_extra_small)
  SubtitleSize.SMALL -> stringResource(R.string.sub_size_small)
  SubtitleSize.MEDIUM -> stringResource(R.string.sub_size_medium)
  SubtitleSize.LARGE -> stringResource(R.string.sub_size_large)
  SubtitleSize.VERY_LARGE -> stringResource(R.string.sub_size_very_large)
}

@Composable
private fun subtitleBackgroundLabel(background: SubtitleBackground): String = when (background) {
  SubtitleBackground.NONE -> stringResource(R.string.sub_bg_none)
  SubtitleBackground.BLACK -> stringResource(R.string.sub_bg_black)
  SubtitleBackground.WHITE -> stringResource(R.string.sub_bg_white)
  SubtitleBackground.BLUE -> stringResource(R.string.sub_bg_blue)
}

@Composable
private fun subtitleOpacityLabel(opacity: SubtitleOpacity): String =
  stringResource(R.string.sub_opacity) + ": " + stringResource(
    when (opacity) {
      SubtitleOpacity.FULL -> R.string.sub_opacity_100
      SubtitleOpacity.SEVENTY_FIVE -> R.string.sub_opacity_75
      SubtitleOpacity.FIFTY -> R.string.sub_opacity_50
      SubtitleOpacity.TWENTY_FIVE -> R.string.sub_opacity_25
    }
  )

@Composable
private fun subtitlePositionLabel(position: SubtitlePosition): String = when (position) {
  SubtitlePosition.BOTTOM -> stringResource(R.string.sub_pos_bottom)
  SubtitlePosition.CENTER -> stringResource(R.string.sub_pos_center)
  SubtitlePosition.TOP -> stringResource(R.string.sub_pos_top)
}

@Composable
private fun targetBufferLabel(option: TargetBufferOption): String = when (option) {
  TargetBufferOption.DEFAULT -> stringResource(R.string.buffer_target_default)
  TargetBufferOption.MINIMUM -> stringResource(R.string.buffer_target_min)
  TargetBufferOption.BALANCED -> stringResource(R.string.buffer_target_balanced)
  TargetBufferOption.EXTENDED -> stringResource(R.string.buffer_target_extended)
}

@Composable
private fun initialBufferLabel(option: InitialBufferOption): String = when (option) {
  InitialBufferOption.FAST -> stringResource(R.string.buffer_initial_fast)
  InitialBufferOption.STANDARD -> stringResource(R.string.buffer_initial_standard)
  InitialBufferOption.STABLE -> stringResource(R.string.buffer_initial_stable)
}

@Composable
private fun backBufferLabel(option: BackBufferOption): String = when (option) {
  BackBufferOption.OFF -> stringResource(R.string.buffer_back_off)
  BackBufferOption.SHORT -> stringResource(R.string.buffer_back_10s)
  BackBufferOption.STANDARD -> stringResource(R.string.buffer_back_15s)
  BackBufferOption.EXTENDED -> stringResource(R.string.buffer_back_30s)
  BackBufferOption.MAXIMUM -> stringResource(R.string.buffer_back_60s)
}

@Composable
private fun decoderFallbackLabel(mode: DecoderFallbackMode): String = when (mode) {
  DecoderFallbackMode.OFF -> stringResource(R.string.decoder_mode_off)
  DecoderFallbackMode.ON -> stringResource(R.string.decoder_mode_on)
  DecoderFallbackMode.PREFER -> stringResource(R.string.decoder_mode_prefer)
}

@Composable
private fun lastStreamCacheDurationLabel(hours: Int): String {
  val option = LastStreamCacheDuration.entries.firstOrNull { it.hours == hours }
    ?: LastStreamCacheDuration.ONE_DAY
  return when (option) {
    LastStreamCacheDuration.SIX_HOURS -> stringResource(R.string.reuse_cache_6h)
    LastStreamCacheDuration.TWELVE_HOURS -> stringResource(R.string.reuse_cache_12h)
    LastStreamCacheDuration.ONE_DAY -> stringResource(R.string.reuse_cache_1d)
    LastStreamCacheDuration.THREE_DAYS -> stringResource(R.string.reuse_cache_3d)
    LastStreamCacheDuration.SEVEN_DAYS -> stringResource(R.string.reuse_cache_7d)
  }
}

private fun formatSyncTimestamp(epochMs: Long): String {
  val format = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
  return format.format(Date(epochMs))
}
