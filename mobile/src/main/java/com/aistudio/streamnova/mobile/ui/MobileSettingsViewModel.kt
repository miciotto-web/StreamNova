package com.aistudio.streamnova.mobile.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.StreamNovaDatabase
import com.example.data.opensubtitles.OpenSubtitlesConnectionCheck
import com.example.data.opensubtitles.OpenSubtitlesConnectionVerifier
import com.example.data.opensubtitles.OpenSubtitlesVerifyError
import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.BackBufferOption
import com.example.data.prefs.DecoderFallbackMode
import com.example.data.prefs.InitialBufferOption
import com.example.data.prefs.LastStreamCacheDuration
import com.example.data.prefs.PlaybackSettings
import com.example.data.prefs.PreferredResolution
import com.example.data.prefs.SettingsRepository
import com.example.data.prefs.StreamingEngineMode
import com.example.data.prefs.SubtitleBackground
import com.example.data.prefs.SubtitleOpacity
import com.example.data.prefs.SubtitlePosition
import com.example.data.prefs.SubtitleSize
import com.example.data.prefs.TargetBufferOption
import com.example.data.remote.simkl.SimklAccountState
import com.example.data.remote.simkl.SimklSyncResult
import com.example.data.remote.simkl.SimklSyncStatus
import com.example.data.repository.SimklRepository
import com.example.data.repository.SimklSyncManager
import com.example.data.torbox.TorBoxRepository
import com.example.data.update.UpdateCheckState
import com.example.data.update.UpdateDownloadState
import com.example.data.update.UpdateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Esito della verifica dell'account TorBox (`GET /user/me`). */
sealed interface MobileTorBoxAccountState {
  data object Unknown : MobileTorBoxAccountState
  data object Checking : MobileTorBoxAccountState
  data class Connected(val email: String?, val plan: String?) : MobileTorBoxAccountState
  data object Error : MobileTorBoxAccountState
  data object NotConfigured : MobileTorBoxAccountState
}

/** Esito della verifica della chiave OpenSubtitles. */
sealed interface MobileOpenSubtitlesState {
  data object Unknown : MobileOpenSubtitlesState
  data object Checking : MobileOpenSubtitlesState
  data object Connected : MobileOpenSubtitlesState
  data object NotConfigured : MobileOpenSubtitlesState
  data class Error(val reason: OpenSubtitlesVerifyError) : MobileOpenSubtitlesState
}

/** Avviso SIMKL mostrato sotto la sezione account (testo risolto dalla UI). */
sealed interface MobileSimklNotice {
  data class Connected(val username: String?) : MobileSimklNotice
  data object Disconnected : MobileSimklNotice
  data class Sync(val result: SimklSyncResult) : MobileSimklNotice
}

data class MobileSettingsUiState(
  val playback: PlaybackSettings = PlaybackSettings(),
  val autoplayEnabled: Boolean = false,
  val tmdbGenreFallbackEnabled: Boolean = true,
  val parentalEnabled: Boolean = false,
  val parentalLevel: String = "18",
  val hasParentalPin: Boolean = false,
  val engineMode: StreamingEngineMode = StreamingEngineMode.HTTP_WEB,
  val torBoxApiKey: String? = null,
  val torBoxInstantDebridEnabled: Boolean = true,
  val torBoxAccount: MobileTorBoxAccountState = MobileTorBoxAccountState.Unknown,
  val openSubtitlesApiKey: String? = null,
  val openSubtitlesState: MobileOpenSubtitlesState = MobileOpenSubtitlesState.Unknown,
  val appLanguage: String = "it",
  val simklAccount: SimklAccountState = SimklAccountState(),
  val simklSyncing: Boolean = false,
  val simklLastSyncAt: Long? = null,
  val simklLastResult: SimklSyncResult? = null,
  val simklNotice: MobileSimklNotice? = null
)

/**
 * ViewModel mobile delle impostazioni.
 *
 * Usa le stesse preferenze persistenti e gli stessi repository della TV: nessuna
 * duplicazione della persistenza. Le sole differenze sono di interfaccia (touch) e
 * nei comportamenti che il player mobile non supporta ancora, documentati nel
 * report di Fase 5 e mostrati come non disponibili nella UI.
 */
class MobileSettingsViewModel : ViewModel() {

  private data class PlaybackBundle(
    val playback: PlaybackSettings,
    val autoplay: Boolean,
    val tmdbGenreFallback: Boolean
  )

  private data class ParentalState(
    val enabled: Boolean,
    val level: String,
    val hasPin: Boolean
  )

  private data class EngineState(
    val mode: StreamingEngineMode,
    val torBoxKey: String?,
    val instantDebrid: Boolean,
    val torBoxAccount: MobileTorBoxAccountState
  )

  private data class OpenSubtitlesState(
    val key: String?,
    val state: MobileOpenSubtitlesState,
    val language: String
  )

  private data class SimklState(
    val account: SimklAccountState,
    val syncing: Boolean,
    val lastSyncAt: Long?,
    val lastResult: SimklSyncResult?,
    val notice: MobileSimklNotice?
  )

  private val _torBoxAccount = MutableStateFlow<MobileTorBoxAccountState>(
    MobileTorBoxAccountState.Unknown
  )
  private val _openSubtitlesState = MutableStateFlow<MobileOpenSubtitlesState>(
    MobileOpenSubtitlesState.Unknown
  )
  private val _simklLastSyncAt = MutableStateFlow(SimklRepository.lastSyncAtEpochMs())
  private val _simklNotice = MutableStateFlow<MobileSimklNotice?>(null)
  private val _isClearingCache = MutableStateFlow(false)

  /** true mentre la cache viene svuotata (riga Avanzato → Cancella cache). */
  val isClearingCache: StateFlow<Boolean> = _isClearingCache.asStateFlow()

  private val openSubtitlesVerifier = OpenSubtitlesConnectionVerifier()

  /**
   * Login SIMKL: stesso ciclo TV (device flow con QR/codice), ma con errori
   * tipizzati e messaggi localizzati.
   */
  val simklLoginController = MobileSimklLoginController(
    requestPrompt = { SimklRepository.startLogin() },
    awaitAuthorization = { prompt -> SimklRepository.awaitLogin(prompt) },
    onAuthorized = {
      SimklSyncManager.sync(force = true)
      _simklLastSyncAt.value = SimklRepository.lastSyncAtEpochMs()
    },
    scope = viewModelScope
  )

  val simklLoginState: StateFlow<MobileSimklLoginUiState> = simklLoginController.state

  private val playbackFlow = combine(
    SettingsRepository.settings,
    AppSettingsRepository.autoplayEnabled,
    AppSettingsRepository.tmdbGenreFallbackEnabled
  ) { playback, autoplay, tmdbGenreFallback ->
    PlaybackBundle(playback, autoplay, tmdbGenreFallback)
  }

  private val parentalFlow = combine(
    AppSettingsRepository.parentalControlEnabled,
    AppSettingsRepository.parentalControlLevel,
    AppSettingsRepository.parentalControlPin.map { it.isNotBlank() }
  ) { enabled, level, hasPin -> ParentalState(enabled, level, hasPin) }

  private val engineFlow = combine(
    AppSettingsRepository.streamingEngineMode,
    AppSettingsRepository.torBoxApiKey,
    AppSettingsRepository.torBoxInstantDebridEnabled,
    _torBoxAccount
  ) { mode, key, instant, account -> EngineState(mode, key, instant, account) }

  private val openSubtitlesFlow = combine(
    AppSettingsRepository.openSubtitlesApiKey,
    _openSubtitlesState,
    AppSettingsRepository.appLanguage
  ) { key, state, language -> OpenSubtitlesState(key, state, language) }

  private val simklFlow = combine(
    SimklRepository.accountState,
    SimklRepository.isSyncing,
    SimklRepository.lastSyncResult,
    _simklNotice
  ) { account, syncing, lastResult, notice ->
    SimklState(
      account = account,
      syncing = syncing,
      lastSyncAt = lastResult?.finishedAtEpochMs?.takeIf { it > 0L } ?: _simklLastSyncAt.value,
      lastResult = lastResult,
      notice = notice
    )
  }

  val uiState: StateFlow<MobileSettingsUiState> = combine(
    playbackFlow,
    parentalFlow,
    engineFlow,
    openSubtitlesFlow,
    simklFlow
  ) { playback, parental, engine, openSubtitles, simkl ->
    MobileSettingsUiState(
      playback = playback.playback,
      autoplayEnabled = playback.autoplay,
      tmdbGenreFallbackEnabled = playback.tmdbGenreFallback,
      parentalEnabled = parental.enabled,
      parentalLevel = parental.level,
      hasParentalPin = parental.hasPin,
      engineMode = engine.mode,
      torBoxApiKey = engine.torBoxKey,
      torBoxInstantDebridEnabled = engine.instantDebrid,
      torBoxAccount = engine.torBoxAccount,
      openSubtitlesApiKey = openSubtitles.key,
      openSubtitlesState = openSubtitles.state,
      appLanguage = openSubtitles.language,
      simklAccount = simkl.account,
      simklSyncing = simkl.syncing,
      simklLastSyncAt = simkl.lastSyncAt,
      simklLastResult = simkl.lastResult,
      simklNotice = simkl.notice
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5_000),
    // Seed dai repository già in memoria: evita di mostrare per un frame i default
    // quando l'utente ha già preferenze diverse (es. lingua inglese, parental ON).
    initialValue = MobileSettingsUiState(
      playback = SettingsRepository.settings.value,
      autoplayEnabled = AppSettingsRepository.autoplayEnabled.value,
      tmdbGenreFallbackEnabled = AppSettingsRepository.tmdbGenreFallbackEnabled.value,
      parentalEnabled = AppSettingsRepository.parentalControlEnabled.value,
      parentalLevel = AppSettingsRepository.parentalControlLevel.value,
      hasParentalPin = AppSettingsRepository.hasParentalControlPin(),
      engineMode = AppSettingsRepository.streamingEngineMode.value,
      torBoxApiKey = AppSettingsRepository.torBoxApiKey.value,
      torBoxInstantDebridEnabled = AppSettingsRepository.torBoxInstantDebridEnabled.value,
      openSubtitlesApiKey = AppSettingsRepository.openSubtitlesApiKey.value,
      appLanguage = AppSettingsRepository.appLanguage.value,
      simklAccount = SimklRepository.accountState.value,
      simklSyncing = SimklRepository.isSyncing.value,
      simklLastSyncAt = SimklRepository.lastSyncAtEpochMs()
    )
  )

  override fun onCleared() {
    simklLoginController.cancel()
    super.onCleared()
  }

  // ── RIPRODUZIONE ─────────────────────────────────────────────────────

  fun setAutoResume(enabled: Boolean) = SettingsRepository.setAutoResume(enabled)

  // Overlay e avvisi di riproduzione: stesse preferenze e stessa persistenza della TV.
  fun setLoadingOverlay(enabled: Boolean) = SettingsRepository.setLoadingOverlayEnabled(enabled)

  fun setLoadingStatus(enabled: Boolean) = SettingsRepository.setLoadingStatusEnabled(enabled)

  fun setPauseOverlay(enabled: Boolean) = SettingsRepository.setPauseOverlayEnabled(enabled)

  fun setParentalGuide(enabled: Boolean) = SettingsRepository.setParentalGuideEnabled(enabled)

  /** «Avvio automatico (Autoplay)»: controllo del dialog di selezione sorgente. */
  fun setAutoplayEnabled(enabled: Boolean) = AppSettingsRepository.setAutoplayEnabled(enabled)

  fun setAutoPlayNextEpisode(enabled: Boolean) =
    SettingsRepository.setAutoPlayNextEpisode(enabled)

  fun setPrioritizeItalianAudio(enabled: Boolean) =
    SettingsRepository.setPrioritizeItalianAudio(enabled)

  fun setPreferredResolution(value: PreferredResolution) =
    SettingsRepository.setPreferredResolution(value)

  fun setPreferredAudioLanguage(code: String) =
    SettingsRepository.setPreferredAudioLanguage(code)

  fun setReuseLastWorkingStreamEnabled(enabled: Boolean) =
    SettingsRepository.setReuseLastWorkingStreamEnabled(enabled)

  fun setLastStreamCacheDuration(duration: LastStreamCacheDuration) =
    SettingsRepository.setLastWorkingStreamCacheDurationHours(duration.hours)

  fun setLastStreamCacheDurationHours(hours: Int) =
    SettingsRepository.setLastWorkingStreamCacheDurationHours(hours)

  // ── SOTTOTITOLI ──────────────────────────────────────────────────────

  fun setSubtitlesEnabled(enabled: Boolean) =
    SettingsRepository.setSubtitlesEnabled(enabled)

  fun setForcedSubtitlesEnabled(enabled: Boolean) =
    SettingsRepository.setForcedSubtitlesEnabled(enabled)

  fun setPreferredSubtitleLanguage(code: String) =
    SettingsRepository.setPreferredSubtitleLanguage(code)

  fun setSubtitleSize(value: SubtitleSize) = SettingsRepository.setSubtitleSize(value)

  fun setSubtitleBackground(value: SubtitleBackground) =
    SettingsRepository.setSubtitleBackground(value)

  fun setSubtitleOpacity(value: SubtitleOpacity) =
    SettingsRepository.setSubtitleOpacity(value)

  fun setSubtitlePosition(value: SubtitlePosition) =
    SettingsRepository.setSubtitlePosition(value)

  // ── AVANZATE ─────────────────────────────────────────────────────────

  fun setTargetBuffer(value: TargetBufferOption) = SettingsRepository.setTargetBuffer(value)

  fun setInitialBuffer(value: InitialBufferOption) = SettingsRepository.setInitialBuffer(value)

  fun setBackBuffer(value: BackBufferOption) = SettingsRepository.setBackBuffer(value)

  fun setAudioPassthrough(enabled: Boolean) =
    SettingsRepository.setAudioPassthroughEnabled(enabled)

  fun setAudioTunneling(enabled: Boolean) =
    SettingsRepository.setAudioTunnelingEnabled(enabled)

  fun setDecoderFallback(value: DecoderFallbackMode) =
    SettingsRepository.setDecoderFallbackMode(value)

  fun setAutoFrameRateMatching(enabled: Boolean) =
    SettingsRepository.setAutoFrameRateMatching(enabled)

  fun setDolbyVisionFallback(enabled: Boolean) =
    SettingsRepository.setDolbyVisionFallbackEnabled(enabled)

  fun setDebugOverlay(enabled: Boolean) =
    SettingsRepository.setDebugOverlayEnabled(enabled)

  // ── MOTORE / TORBOX ──────────────────────────────────────────────────

  fun setEngineMode(mode: StreamingEngineMode) =
    AppSettingsRepository.setStreamingEngineMode(mode)

  fun setTorBoxApiKey(value: String?) {
    AppSettingsRepository.setTorBoxApiKey(value)
    _torBoxAccount.value = MobileTorBoxAccountState.Unknown
  }

  fun setTorBoxInstantDebridEnabled(enabled: Boolean) =
    AppSettingsRepository.setTorBoxInstantDebridEnabled(enabled)

  /** Interroga `GET https://api.torbox.app/v1/api/user/me` per verificare la chiave. */
  fun verifyTorBoxAccount() {
    if (_torBoxAccount.value is MobileTorBoxAccountState.Checking) return
    // Lo stato passa a Checking in modo sincrono: due richieste ravvicinate
    // (tap + verifica automatica della chiave salvata) non duplicano la chiamata.
    _torBoxAccount.value = MobileTorBoxAccountState.Checking
    viewModelScope.launch {
      _torBoxAccount.value = when (val result = TorBoxRepository.verifyAccount()) {
        is TorBoxRepository.AccountCheck.Valid -> MobileTorBoxAccountState.Connected(
          email = result.user.email,
          plan = result.user.plan
        )
        is TorBoxRepository.AccountCheck.Invalid -> MobileTorBoxAccountState.Error
        TorBoxRepository.AccountCheck.NotConfigured -> MobileTorBoxAccountState.NotConfigured
      }
    }
  }

  // ── OPENSUBTITLES ────────────────────────────────────────────────────

  fun setOpenSubtitlesApiKey(value: String?) {
    AppSettingsRepository.setOpenSubtitlesApiKey(value)
    _openSubtitlesState.value = MobileOpenSubtitlesState.Unknown
  }

  fun verifyOpenSubtitlesConnection() {
    if (_openSubtitlesState.value is MobileOpenSubtitlesState.Checking) return
    val key = AppSettingsRepository.openSubtitlesApiKey.value
    if (key.isNullOrBlank()) {
      _openSubtitlesState.value = MobileOpenSubtitlesState.NotConfigured
      return
    }
    _openSubtitlesState.value = MobileOpenSubtitlesState.Checking
    viewModelScope.launch {
      _openSubtitlesState.value = when (val result = openSubtitlesVerifier.check(key)) {
        is OpenSubtitlesConnectionCheck.Valid -> MobileOpenSubtitlesState.Connected
        is OpenSubtitlesConnectionCheck.NotConfigured -> MobileOpenSubtitlesState.NotConfigured
        is OpenSubtitlesConnectionCheck.Failed ->
          MobileOpenSubtitlesState.Error(result.error)
      }
    }
  }

  // ── SIMKL ────────────────────────────────────────────────────────────

  fun startSimklLogin() = simklLoginController.start()

  fun retrySimklLogin() = simklLoginController.retry()

  fun cancelSimklLogin() = simklLoginController.cancel()

  /** Consuma l'esito del login: l'avviso non si ripete a ogni ricomposizione. */
  fun consumeSimklLoginResult() {
    val account = simklLoginController.state.value.account ?: return
    _simklNotice.value = MobileSimklNotice.Connected(account.username)
    _simklLastSyncAt.value = SimklRepository.lastSyncAtEpochMs()
    simklLoginController.clearResult()
  }

  fun signOutSimkl() {
    viewModelScope.launch {
      SimklRepository.signOut()
      _simklNotice.value = MobileSimklNotice.Disconnected
      _simklLastSyncAt.value = null
    }
  }

  fun syncSimklNow() {
    if (SimklRepository.isSyncing.value) return
    viewModelScope.launch {
      val result = SimklSyncManager.sync(force = true)
      _simklLastSyncAt.value = result.finishedAtEpochMs
      _simklNotice.value = MobileSimklNotice.Sync(result)
    }
  }

  fun clearSimklNotice() {
    _simklNotice.value = null
  }

  // ── LINGUA ───────────────────────────────────────────────────────────

  fun setAppLanguage(language: String) {
    if (AppSettingsRepository.appLanguage.value != language) {
      AppSettingsRepository.setAppLanguage(language)
      MobileLanguageEvents.notifyChanged(language)
    }
  }

  fun setTmdbGenreFallbackEnabled(enabled: Boolean) =
    AppSettingsRepository.setTmdbGenreFallbackEnabled(enabled)

  // ── CONTROLLO GENITORI ───────────────────────────────────────────────

  /** Attivazione e disattivazione richiedono il PIN; il PIN è persistito solo come hash. */
  fun setParentalEnabled(enabled: Boolean, pin: String): Boolean {
    val clean = pin.trim()
    val hasSavedPin = AppSettingsRepository.hasParentalControlPin()
    val validPin = ParentalControlPolicy.isValidPin(clean)
    val pinMatches = validPin && AppSettingsRepository.verifyParentalControlPin(clean)
    if (!ParentalControlPolicy.canChangeParentalEnabled(enabled, hasSavedPin, validPin, pinMatches)) {
      return false
    }
    if (!hasSavedPin && enabled) AppSettingsRepository.setParentalControlPin(clean)
    AppSettingsRepository.setParentalControlEnabled(enabled)
    return true
  }

  fun setParentalLevel(level: String) = AppSettingsRepository.setParentalControlLevel(level)

  fun verifyParentalPin(pin: String): Boolean =
    AppSettingsRepository.verifyParentalControlPin(pin)

  fun disableParentalControl() = AppSettingsRepository.setParentalControlEnabled(false)

  /** Salva il primo PIN. Con un PIN già configurato serve [changeParentalPin]. */
  fun saveParentalPin(pin: String): Boolean {
    if (AppSettingsRepository.hasParentalControlPin()) return false
    val clean = pin.trim()
    if (!ParentalControlPolicy.isValidPin(clean)) return false
    AppSettingsRepository.setParentalControlPin(clean)
    return true
  }

  /** Cambia il PIN solo dopo aver verificato quello corrente. */
  fun changeParentalPin(currentPin: String, newPin: String): Boolean {
    val current = currentPin.trim()
    val next = newPin.trim()
    val hasSavedPin = AppSettingsRepository.hasParentalControlPin()
    val currentValid = ParentalControlPolicy.isValidPin(current)
    val currentMatches = currentValid && AppSettingsRepository.verifyParentalControlPin(current)
    if (!ParentalControlPolicy.canChangePin(
        hasSavedPin = hasSavedPin,
        currentPinValid = currentValid,
        currentPinMatches = currentMatches,
        newPinValid = ParentalControlPolicy.isValidPin(next)
      )
    ) {
      return false
    }
    AppSettingsRepository.setParentalControlPin(next)
    return true
  }

  /**
   * Sostituisce il PIN dopo che la schermata ha già verificato quello corrente
   * (flusso "Modifica PIN" del menu di gestione, come la TV).
   */
  fun replaceParentalPin(newPin: String): Boolean {
    val clean = newPin.trim()
    if (!ParentalControlPolicy.isValidPin(clean)) return false
    AppSettingsRepository.setParentalControlPin(clean)
    return true
  }

  // ── AGGIORNAMENTI ────────────────────────────────────────────────────
  //
  // Il controllo e il download vivono in UpdateRepository (core condiviso),
  // già usato dalla TV: gli stati sono gli stessi, il canale GitHub è quello
  // dedicato all'app Mobile impostato dalla configurazione del client.

  /** Esito del controllo aggiornamenti (riga «Controlla aggiornamenti»). */
  val updateCheckState: StateFlow<UpdateCheckState> = UpdateRepository.state

  /** Stato del download APK avviato dall'utente (progresso, completamento). */
  val updateDownloadState: StateFlow<UpdateDownloadState> = UpdateRepository.downloadState

  /** Popup dell'aggiornamento trovato dal controllo automatico di avvio. */
  val updateStartupPrompt: StateFlow<UpdateCheckState.UpdateAvailable?> =
    UpdateRepository.startupPrompt

  /** Controllo manuale: confronta la versione installata con l'ultima release. */
  fun checkForUpdates() {
    UpdateRepository.checkForUpdates()
  }

  /** Download dell'APK validato dal controllo (richiesta esplicita dell'utente). */
  fun downloadUpdate(context: Context) {
    UpdateRepository.downloadUpdate(context)
  }

  /** Chiude il feedback del controllo manuale (dialog e riga tornano a Idle). */
  fun dismissUpdateResult() {
    UpdateRepository.reset()
  }

  /** Annulla un download in corso senza perdere l'APK validato (riprova possibile). */
  fun cancelUpdateDownload() {
    UpdateRepository.resetDownload()
  }

  /** L'utente ha scelto «Più tardi» sul popup dell'aggiornamento di avvio. */
  fun dismissStartupUpdatePrompt() {
    UpdateRepository.dismissStartupPrompt()
  }

  // ── CACHE ────────────────────────────────────────────────────────────

  /** Svuota cache immagini, risposte TMDB e directory temporanea, preservando l'utente. */
  @OptIn(coil.annotation.ExperimentalCoilApi::class)
  fun clearCache(context: Context, onComplete: (Boolean) -> Unit) {
    if (_isClearingCache.value) return
    _isClearingCache.value = true
    viewModelScope.launch(Dispatchers.IO) {
      val success = runCatching {
        coil.Coil.imageLoader(context).apply {
          memoryCache?.clear()
          diskCache?.clear()
        }
        val db = StreamNovaDatabase.getInstance(context)
        db?.tmdbResponseCacheDao()?.clearAll()
        db?.cachedMediaDao()?.clearNonFavorites()
        db?.lastWorkingStreamDao()?.clearAll()
        context.cacheDir?.deleteRecursively()
        context.externalCacheDir?.deleteRecursively()
      }.isSuccess
      withContext(Dispatchers.Main) {
        _isClearingCache.value = false
        onComplete(success)
      }
    }
  }
}
