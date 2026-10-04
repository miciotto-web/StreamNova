package com.example.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Risoluzione preferita persistente. [targetHeight] è l'altezza nominale richiesta
 * (0 = Auto, che mantiene il comportamento attuale del player).
 */
enum class PreferredResolution(val label: String, val targetHeight: Int) {
  AUTO("Auto", 0),
  UHD_4K("4K", 2160),
  FULL_HD_1080P("1080p", 1080),
  HD_720P("720p", 720)
}

/**
 * Dimensione del testo dei sottotitoli. [textSizeFraction] è la frazione dell'altezza
 * della vista video (PlayerView) usata da Media3 come dimensione del testo:
 * 0.0533f coincide con il default di Media3 (SubtitleView.DEFAULT_TEXT_SIZE_FRACTION).
 */
enum class SubtitleSize(val label: String, val textSizeFraction: Float) {
  SMALL("Piccola", 0.038f),
  MEDIUM("Media", 0.0533f),
  LARGE("Grande", 0.075f),
  VERY_LARGE("Molto grande", 0.10f)
}

/**
 * Sfondo del testo dei sottotitoli nel player. [backgroundColor] è il colore ARGB
 * applicato via SubtitleView.setStyle(CaptionStyleCompat) di Media3: con alpha 0
 * (Nessuno) SubtitlePainter non disegna alcun background dietro il testo.
 */
enum class SubtitleBackground(val label: String, val backgroundColor: Int) {
  NONE("Nessuno", 0x00000000),
  BLACK("Nero", 0xFF000000.toInt()),
  SEMI_TRANSPARENT("Nero semi-trasparente", 0x80000000.toInt())
}

/**
 * Posizione verticale dei sottotitoli nel player. [bottomPaddingFraction] è il
 * parametro nativo Media3 1.5.1 (SubtitleView.setBottomPaddingFraction): distanza del
 * cue dal bordo inferiore, come frazione dell'altezza della vista, applicata ai cue
 * senza posizione esplicita (Cue.line == Cue.DIMEN_UNSET, caso standard).
 */
enum class SubtitlePosition(val label: String, val bottomPaddingFraction: Float) {
  BOTTOM("Basso", 0.08f),
  CENTER("Centro", 0.5f),
  TOP("Alto", 0.9f)
}

/** Impostazioni della sezione RIPRODUZIONE, con i default richiesti alla prima apertura. */
data class PlaybackSettings(
  val preferredResolution: PreferredResolution = PreferredResolution.AUTO,
  val autoPlayNextEpisode: Boolean = true,
  val autoResume: Boolean = true,
  /**
   * Lingua preferita dell'audio (codice ISO, default "it").
   * "" = "Originale / Qualsiasi": nessuna preferenza linguistica verso Media3.
   */
  val preferredAudioLanguage: String = "it",
  /** Sottotitoli automatici: default OFF (nessuna attivazione forzata nel player). */
  val subtitlesEnabled: Boolean = false,
  /** Sottotitoli forced (es. dialoghi in lingua straniera con audio IT): default ON. */
  val forcedSubtitlesEnabled: Boolean = true,
  /**
   * Lingua preferita dei sottotitoli (codice ISO, default "it").
   * "" = "Originale / Qualsiasi": nessun vincolo di lingua verso Media3.
   */
  val preferredSubtitleLanguage: String = "it",
  /** Dimensione del testo dei sottotitoli nel player (default MEDIUM). */
  val subtitleSize: SubtitleSize = SubtitleSize.MEDIUM,
  /** Sfondo dietro il testo dei sottotitoli nel player (default SEMI_TRANSPARENT). */
  val subtitleBackground: SubtitleBackground = SubtitleBackground.SEMI_TRANSPARENT,
  /** Posizione verticale dei sottotitoli nel player (default BOTTOM). */
  val subtitlePosition: SubtitlePosition = SubtitlePosition.BOTTOM
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "streamnova_settings"
)

/**
 * Persistenza delle impostazioni RIPRODUZIONE su DataStore Preferences.
 *
 * Singleton inizializzato una sola volta dall'Application (stesso pattern di
 * [com.example.data.repository.MediaRepository.init]). Le scritture sono
 * asincrone e non toccano in alcun modo i dati utente (progressi/catalogo).
 */
object SettingsRepository {

  private val KEY_PREFERRED_RESOLUTION = stringPreferencesKey("preferred_resolution")
  private val KEY_AUTO_PLAY_NEXT = booleanPreferencesKey("auto_play_next_episode")
  private val KEY_AUTO_RESUME = booleanPreferencesKey("auto_resume")
  private val KEY_PREFERRED_AUDIO_LANGUAGE = stringPreferencesKey("preferred_audio_language")
  private val KEY_SUBTITLES_ENABLED = booleanPreferencesKey("subtitles_enabled")
  private val KEY_FORCED_SUBTITLES_ENABLED = booleanPreferencesKey("forced_subtitles_enabled")
  private val KEY_PREFERRED_SUBTITLE_LANGUAGE = stringPreferencesKey("preferred_subtitle_language")
  private val KEY_SUBTITLE_SIZE = stringPreferencesKey("subtitle_size")
  private val KEY_SUBTITLE_BACKGROUND = stringPreferencesKey("subtitle_background")
  private val KEY_SUBTITLE_POSITION = stringPreferencesKey("subtitle_position")

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
          autoPlayNextEpisode = prefs[KEY_AUTO_PLAY_NEXT] ?: true,
          autoResume = prefs[KEY_AUTO_RESUME] ?: true,
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
            ?: SubtitlePosition.BOTTOM
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
}
