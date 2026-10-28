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
import kotlinx.coroutines.flow.stateIn

data class MobileSettingsUiState(
  val playback: PlaybackSettings = PlaybackSettings(),
  val autoplayEnabled: Boolean = true,
  val parentalEnabled: Boolean = false,
  val parentalLevel: String = "18",
  val engineMode: StreamingEngineMode = StreamingEngineMode.HTTP_WEB,
  val torBoxApiKey: String = ""
)

/**
 * ViewModel mobile delle impostazioni. Espone le stesse preferenze persistenti
 * della TV e applica le stesse API del core, senza duplicare la persistenza.
 */
class MobileSettingsViewModel : ViewModel() {

  private val playbackFlow = combine(
    SettingsRepository.settings,
    AppSettingsRepository.autoplayEnabled
  ) { playback, autoplay -> playback to autoplay }

  val uiState: StateFlow<MobileSettingsUiState> = combine(
    playbackFlow,
    AppSettingsRepository.parentalControlEnabled,
    AppSettingsRepository.parentalControlLevel,
    AppSettingsRepository.streamingEngineMode,
    AppSettingsRepository.torBoxApiKey
  ) { (playback, autoplay), parentalEnabled, parentalLevel, engineMode, torBoxKey ->
    MobileSettingsUiState(
      playback = playback,
      autoplayEnabled = autoplay,
      parentalEnabled = parentalEnabled,
      parentalLevel = parentalLevel,
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

  /**
   * Abilita/disabilita il controllo genitori. L'abilitazione richiede un PIN
   * già impostato: senza PIN la UI mostra un errore e non attiva il filtro.
   */
  fun setParentalEnabled(enabled: Boolean, pin: String): Boolean {
    if (enabled) {
      val existingPin = AppSettingsRepository.parentalControlPin.value
      val effectivePin = pin.trim().ifBlank { existingPin }
      if (effectivePin.length != 4 || effectivePin.any { !it.isDigit() }) return false
      if (pin.trim().isNotEmpty()) AppSettingsRepository.setParentalControlPin(effectivePin)
    }
    AppSettingsRepository.setParentalControlEnabled(enabled)
    return true
  }

  fun setParentalLevel(level: String) = AppSettingsRepository.setParentalControlLevel(level)

  /** Salva un PIN di 4 cifre. Restituisce false se il formato non è valido. */
  fun saveParentalPin(pin: String): Boolean {
    val clean = pin.trim()
    if (clean.length != 4 || clean.any { !it.isDigit() }) return false
    AppSettingsRepository.setParentalControlPin(clean)
    return true
  }
}
