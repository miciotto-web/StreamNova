package com.aistudio.streamnova.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.example.data.prefs.SubtitlePosition
import com.example.data.prefs.SubtitleSize
import com.example.data.prefs.TargetBufferOption
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class MobileSettingsUiState(
  val playback: PlaybackSettings = PlaybackSettings(),
  val autoplayEnabled: Boolean = true,
  val parentalEnabled: Boolean = false,
  val parentalLevel: String = "18",
  val hasParentalPin: Boolean = false,
  val engineMode: StreamingEngineMode = StreamingEngineMode.HTTP_WEB,
  val torBoxApiKey: String = ""
)

/**
 * ViewModel mobile delle impostazioni. Espone le stesse preferenze persistenti
 * della TV e applica le stesse API del core, senza duplicare la persistenza.
 */
class MobileSettingsViewModel : ViewModel() {

  private data class ParentalState(
    val enabled: Boolean,
    val level: String,
    val hasPin: Boolean
  )

  private val playbackFlow = combine(
    SettingsRepository.settings,
    AppSettingsRepository.autoplayEnabled
  ) { playback, autoplay -> playback to autoplay }

  private val parentalFlow = combine(
    AppSettingsRepository.parentalControlEnabled,
    AppSettingsRepository.parentalControlLevel,
    AppSettingsRepository.parentalControlPin.map { it.isNotBlank() }
  ) { enabled, level, hasPin -> ParentalState(enabled, level, hasPin) }

  val uiState: StateFlow<MobileSettingsUiState> = combine(
    playbackFlow,
    parentalFlow,
    AppSettingsRepository.streamingEngineMode,
    AppSettingsRepository.torBoxApiKey
  ) { (playback, autoplay), parental, engineMode, torBoxKey ->
    MobileSettingsUiState(
      playback = playback,
      autoplayEnabled = autoplay,
      parentalEnabled = parental.enabled,
      parentalLevel = parental.level,
      hasParentalPin = parental.hasPin,
      engineMode = engineMode,
      torBoxApiKey = torBoxKey.orEmpty()
    )
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5_000),
    initialValue = MobileSettingsUiState()
  )

  fun setAutoResume(enabled: Boolean) = SettingsRepository.setAutoResume(enabled)

  fun setAutoplayNext(enabled: Boolean) {
    SettingsRepository.setAutoPlayNextEpisode(enabled)
    AppSettingsRepository.setAutoplayEnabled(enabled)
  }

  fun setPrioritizeItalianAudio(enabled: Boolean) =
    SettingsRepository.setPrioritizeItalianAudio(enabled)

  fun setSubtitlesEnabled(enabled: Boolean) =
    SettingsRepository.setSubtitlesEnabled(enabled)

  fun setPreferredResolution(value: PreferredResolution) =
    SettingsRepository.setPreferredResolution(value)

  fun setSubtitleSize(value: SubtitleSize) = SettingsRepository.setSubtitleSize(value)

  fun setSubtitleBackground(value: SubtitleBackground) =
    SettingsRepository.setSubtitleBackground(value)

  fun setSubtitlePosition(value: SubtitlePosition) =
    SettingsRepository.setSubtitlePosition(value)

  fun setTargetBuffer(value: TargetBufferOption) = SettingsRepository.setTargetBuffer(value)

  fun setInitialBuffer(value: InitialBufferOption) = SettingsRepository.setInitialBuffer(value)

  fun setBackBuffer(value: BackBufferOption) = SettingsRepository.setBackBuffer(value)

  fun setDecoderFallback(value: DecoderFallbackMode) =
    SettingsRepository.setDecoderFallbackMode(value)

  fun setDolbyVisionFallback(enabled: Boolean) =
    SettingsRepository.setDolbyVisionFallbackEnabled(enabled)

  fun setAudioPassthrough(enabled: Boolean) =
    SettingsRepository.setAudioPassthroughEnabled(enabled)

  fun setEngineMode(mode: StreamingEngineMode) =
    AppSettingsRepository.setStreamingEngineMode(mode)

  fun setTorBoxApiKey(value: String) {
    AppSettingsRepository.setTorBoxApiKey(value.trim().takeIf { it.isNotBlank() })
  }

  fun setLastStreamCacheDuration(duration: LastStreamCacheDuration) {
    SettingsRepository.setLastWorkingStreamCacheDurationHours(duration.hours)
  }

  /** Attivazione e disattivazione richiedono il PIN; il PIN viene persistito solo come hash. */
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
}
