package com.example.data.prefs
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "streamnova_settings"
)

/**
 * Persistenza delle impostazioni RIPRODUZIONE e AVANZATE su DataStore Preferences.
 *
 * Singleton inizializzato una sola volta dall'Application (stesso pattern di
 * [com.example.data.repository.MediaRepository.init]). Le scritture sono
 * asincrone e non toccano in alcun modo i dati utente (progressi/catalogo).
 */
object SettingsRepository {

  private val KEY_PREFERRED_RESOLUTION = stringPreferencesKey("preferred_resolution")
  private val KEY_AUTO_PLAY_NEXT = booleanPreferencesKey("auto_play_next_episode")
  private val KEY_AUTO_RESUME = booleanPreferencesKey("auto_resume")
  private val KEY_REUSE_LAST_WORKING_STREAM = booleanPreferencesKey("reuse_last_working_stream")
  private val KEY_LAST_STREAM_CACHE_HOURS = intPreferencesKey("last_working_stream_cache_hours")
  private val KEY_PRIORITIZE_ITALIAN_AUDIO = booleanPreferencesKey("prioritize_italian_audio")
  private val KEY_PREFERRED_AUDIO_LANGUAGE = stringPreferencesKey("preferred_audio_language")
  private val KEY_SUBTITLES_ENABLED = booleanPreferencesKey("subtitles_enabled")
  private val KEY_FORCED_SUBTITLES_ENABLED = booleanPreferencesKey("forced_subtitles_enabled")
  private val KEY_PREFERRED_SUBTITLE_LANGUAGE = stringPreferencesKey("preferred_subtitle_language")
  private val KEY_SUBTITLE_SIZE = stringPreferencesKey("subtitle_size")
  private val KEY_SUBTITLE_BACKGROUND = stringPreferencesKey("subtitle_background")
  private val KEY_SUBTITLE_POSITION = stringPreferencesKey("subtitle_position")

  // Avanzate
  private val KEY_TARGET_BUFFER = stringPreferencesKey("adv_target_buffer")
  private val KEY_INITIAL_BUFFER = stringPreferencesKey("adv_initial_buffer")
  private val KEY_BACK_BUFFER = stringPreferencesKey("adv_back_buffer")
  private val KEY_AUDIO_PASSTHROUGH = booleanPreferencesKey("adv_audio_passthrough")
  private val KEY_AUDIO_TUNNELING = booleanPreferencesKey("adv_audio_tunneling")
  private val KEY_DECODER_FALLBACK = stringPreferencesKey("adv_decoder_fallback")
  private val KEY_AUTO_FRAME_RATE = booleanPreferencesKey("adv_auto_frame_rate")
  private val KEY_DOLBY_VISION_FALLBACK = booleanPreferencesKey("adv_dv_fallback")
  private val KEY_DEBUG_OVERLAY = booleanPreferencesKey("adv_debug_overlay")

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private val _settings = MutableStateFlow(PlaybackSettings())
  val settings: StateFlow<PlaybackSettings> = _settings.asStateFlow()

  private var dataStore: DataStore<Preferences>? = null

  fun init(context: Context) {
    if (dataStore != null) return
    val store = context.applicationContext.settingsDataStore
    dataStore = store
    scope.launch {
      store.data.collect { prefs ->
        _settings.value = PlaybackSettings(
          preferredResolution = prefs[KEY_PREFERRED_RESOLUTION]
            ?.let { name -> PreferredResolution.entries.firstOrNull { it.name == name } }
            ?: PreferredResolution.AUTO,
          autoPlayNextEpisodeEnabled = prefs[KEY_AUTO_PLAY_NEXT] ?: true,
          autoResume = prefs[KEY_AUTO_RESUME] ?: true,
          reuseLastWorkingStreamEnabled = prefs[KEY_REUSE_LAST_WORKING_STREAM] ?: true,
          lastWorkingStreamCacheDurationHours = prefs[KEY_LAST_STREAM_CACHE_HOURS] ?: 24,
          prioritizeItalianAudio = prefs[KEY_PRIORITIZE_ITALIAN_AUDIO] ?: true,
          preferredAudioLanguage = prefs[KEY_PREFERRED_AUDIO_LANGUAGE] ?: "it",
          subtitlesEnabled = prefs[KEY_SUBTITLES_ENABLED] ?: false,
          forcedSubtitlesEnabled = prefs[KEY_FORCED_SUBTITLES_ENABLED] ?: true,
          preferredSubtitleLanguage = prefs[KEY_PREFERRED_SUBTITLE_LANGUAGE] ?: "it",
          subtitleSize = prefs[KEY_SUBTITLE_SIZE]
            ?.let { name -> SubtitleSize.entries.firstOrNull { it.name == name } }
            ?: SubtitleSize.MEDIUM,
          subtitleBackground = prefs[KEY_SUBTITLE_BACKGROUND]
            ?.let { name -> SubtitleBackground.entries.firstOrNull { it.name == name } }
            ?: SubtitleBackground.SEMI_TRANSPARENT,
          subtitlePosition = prefs[KEY_SUBTITLE_POSITION]
            ?.let { name -> SubtitlePosition.entries.firstOrNull { it.name == name } }
            ?: SubtitlePosition.BOTTOM,

          // Avanzate
          targetBuffer = prefs[KEY_TARGET_BUFFER]
            ?.let { name -> TargetBufferOption.entries.firstOrNull { it.name == name } }
            ?: TargetBufferOption.DEFAULT,
          initialBuffer = prefs[KEY_INITIAL_BUFFER]
            ?.let { name -> InitialBufferOption.entries.firstOrNull { it.name == name } }
            ?: InitialBufferOption.STANDARD,
          backBuffer = prefs[KEY_BACK_BUFFER]
            ?.let { name -> BackBufferOption.entries.firstOrNull { it.name == name } }
            ?: BackBufferOption.STANDARD,
          audioPassthroughEnabled = prefs[KEY_AUDIO_PASSTHROUGH] ?: false,
          audioTunnelingEnabled = prefs[KEY_AUDIO_TUNNELING] ?: false,
          decoderFallbackMode = prefs[KEY_DECODER_FALLBACK]
            ?.let { name -> DecoderFallbackMode.entries.firstOrNull { it.name == name } }
            ?: DecoderFallbackMode.ON,
          autoFrameRateMatching = prefs[KEY_AUTO_FRAME_RATE] ?: false,
          dolbyVisionFallbackEnabled = prefs[KEY_DOLBY_VISION_FALLBACK] ?: true,
          debugOverlayEnabled = prefs[KEY_DEBUG_OVERLAY] ?: false
        )
      }
    }
  }

  fun setPreferredResolution(value: PreferredResolution) {
    scope.launch { dataStore?.edit { it[KEY_PREFERRED_RESOLUTION] = value.name } }
  }

  fun setAutoPlayNextEpisode(value: Boolean) {
    scope.launch { dataStore?.edit { it[KEY_AUTO_PLAY_NEXT] = value } }
  }

  fun setAutoResume(value: Boolean) {
    scope.launch { dataStore?.edit { it[KEY_AUTO_RESUME] = value } }
  }

  fun setReuseLastWorkingStreamEnabled(value: Boolean) {
    scope.launch { dataStore?.edit { it[KEY_REUSE_LAST_WORKING_STREAM] = value } }
  }

  fun setLastWorkingStreamCacheDurationHours(value: Int) {
    scope.launch { dataStore?.edit { it[KEY_LAST_STREAM_CACHE_HOURS] = value } }
  }

  fun setPrioritizeItalianAudio(value: Boolean) {
    scope.launch { dataStore?.edit { it[KEY_PRIORITIZE_ITALIAN_AUDIO] = value } }
  }

  fun setPreferredAudioLanguage(value: String) {
    scope.launch { dataStore?.edit { it[KEY_PREFERRED_AUDIO_LANGUAGE] = value } }
  }

  fun setSubtitlesEnabled(value: Boolean) {
    scope.launch { dataStore?.edit { it[KEY_SUBTITLES_ENABLED] = value } }
  }

  fun setForcedSubtitlesEnabled(value: Boolean) {
    scope.launch { dataStore?.edit { it[KEY_FORCED_SUBTITLES_ENABLED] = value } }
  }

  fun setPreferredSubtitleLanguage(value: String) {
    scope.launch { dataStore?.edit { it[KEY_PREFERRED_SUBTITLE_LANGUAGE] = value } }
  }

  fun setSubtitleSize(value: SubtitleSize) {
    scope.launch { dataStore?.edit { it[KEY_SUBTITLE_SIZE] = value.name } }
  }

  fun setSubtitleBackground(value: SubtitleBackground) {
    scope.launch { dataStore?.edit { it[KEY_SUBTITLE_BACKGROUND] = value.name } }
  }

  fun setSubtitlePosition(value: SubtitlePosition) {
    scope.launch { dataStore?.edit { it[KEY_SUBTITLE_POSITION] = value.name } }
  }

  fun setTargetBuffer(value: TargetBufferOption) {
    scope.launch { dataStore?.edit { it[KEY_TARGET_BUFFER] = value.name } }
  }

  fun setInitialBuffer(value: InitialBufferOption) {
    scope.launch { dataStore?.edit { it[KEY_INITIAL_BUFFER] = value.name } }
  }

  fun setBackBuffer(value: BackBufferOption) {
    scope.launch { dataStore?.edit { it[KEY_BACK_BUFFER] = value.name } }
  }

  fun setAudioPassthroughEnabled(value: Boolean) {
    scope.launch { dataStore?.edit { it[KEY_AUDIO_PASSTHROUGH] = value } }
  }

  fun setAudioTunnelingEnabled(value: Boolean) {
    scope.launch { dataStore?.edit { it[KEY_AUDIO_TUNNELING] = value } }
  }

  fun setDecoderFallbackMode(value: DecoderFallbackMode) {
    scope.launch { dataStore?.edit { it[KEY_DECODER_FALLBACK] = value.name } }
  }

  fun setAutoFrameRateMatching(value: Boolean) {
    scope.launch { dataStore?.edit { it[KEY_AUTO_FRAME_RATE] = value } }
  }

  fun setDolbyVisionFallbackEnabled(value: Boolean) {
    scope.launch { dataStore?.edit { it[KEY_DOLBY_VISION_FALLBACK] = value } }
  }

  fun setDebugOverlayEnabled(value: Boolean) {
    scope.launch { dataStore?.edit { it[KEY_DEBUG_OVERLAY] = value } }
  }
}
