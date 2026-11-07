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
 * Durata di validità della cache dell'ultimo flusso funzionante ("Riusa l'ultimo link").
 * Oltre la finestra la voce salvata viene ignorata e la risoluzione riparte dalla lista.
 */
enum class LastStreamCacheDuration(val label: String, val hours: Int) {
  SIX_HOURS("6 ore", 6),
  TWELVE_HOURS("12 ore", 12),
  ONE_DAY("1 giorno", 24),
  THREE_DAYS("3 giorni", 72),
  SEVEN_DAYS("7 giorni", 168)
}

/**
 * Dimensione del testo dei sottotitoli. [textSizeFraction] è la frazione dell'altezza
 * della vista video (PlayerView) usata da Media3 come dimensione del testo:
 * 0.0533f coincide con il default di Media3 (SubtitleView.DEFAULT_TEXT_SIZE_FRACTION).
 *
 * I cinque livelli sono ordinati dal più piccolo al più grande. I nomi storici
 * (SMALL..VERY_LARGE) restano invariati perché sono persistiti su DataStore per nome:
 * rinominarli farebbe perdere la scelta dell'utente dopo un aggiornamento.
 */
enum class SubtitleSize(val label: String, val textSizeFraction: Float) {
  EXTRA_SMALL("Piccolissima", 0.030f),
  SMALL("Piccola", 0.038f),
  MEDIUM("Media", 0.0533f),
  LARGE("Grande", 0.075f),
  VERY_LARGE("Molto grande", 0.10f)
}

/**
 * Sfondo del testo dei sottotitoli nel player.
 *
 * [backgroundColor] è il colore ARGB del riquadro dietro al testo, applicato via
 * SubtitleView.setStyle(CaptionStyleCompat) di Media3; l'alpha definitivo viene
 * calcolato in fase di applicazione moltiplicandolo per [SubtitleOpacity].
 * [textColor] mantiene leggibile il testo: bianco su sfondi scuri/assenti, nero su
 * sfondo bianco.
 *
 * NONE resta il caso "nessun riquadro": alpha 0, SubtitlePainter non disegna nulla.
 */
enum class SubtitleBackground(
  val label: String,
  val backgroundColor: Int,
  val textColor: Int
) {
  NONE("Nessuno", 0x00000000, 0xFFFFFFFF.toInt()),
  BLACK("Nero", 0xFF000000.toInt(), 0xFFFFFFFF.toInt()),
  WHITE("Bianco", 0xFFFFFFFF.toInt(), 0xFF000000.toInt()),
  BLUE("Blu", 0xFF0D47A1.toInt(), 0xFFFFFFFF.toInt())
}

/**
 * Opacità del riquadro di sfondo dei sottotitoli (25%..100%).
 *
 * [percent] è il valore mostrato all'utente; [alpha] è il corrispondente canale alpha
 * ARGB applicato al colore di [SubtitleBackground]. Il testo resta sempre pienamente
 * opaco: l'opacità regola solo lo sfondo, così i sottotitoli non perdono leggibilità.
 */
enum class SubtitleOpacity(val label: String, val percent: Int) {
  FULL("100%", 100),
  SEVENTY_FIVE("75%", 75),
  FIFTY("50%", 50),
  TWENTY_FIVE("25%", 25);

  /** Canale alpha ARGB (0..255) corrispondente alla percentuale (arrotondato). */
  val alpha: Int get() = (255 * percent + 50) / 100
}

/**
 * Colore ARGB effettivo del riquadro, con l'opacità scelta applicata al colore base.
 *
 * NONE resta sempre completamente trasparente (alpha 0) qualunque sia l'opacità: è il
 * caso "nessun riquadro" e non deve trasformarsi in un box nero al 100%.
 * Il testo non è interessato: [SubtitleBackground.textColor] resta opaco.
 */
fun SubtitleBackground.withOpacity(opacity: SubtitleOpacity): Int =
  if (this == SubtitleBackground.NONE) {
    0
  } else {
    (backgroundColor and 0x00FFFFFF) or (opacity.alpha shl 24)
  }

/**
 * Risolve le preferenze di aspetto dei sottotitoli lette da DataStore, applicando la
 * migrazione dal vecchio modello (tre sfondi, nessuna opacità).
 *
 * Regole di migrazione, necessarie per non cambiare l'aspetto già scelto:
 * - "SEMI_TRANSPARENT" (vecchio default) → nero con opacità 50%;
 * - nessun valore salvato → nero al 50% (identico al vecchio default);
 * - "BLACK" o "NONE" salvati esplicitamente → opacità 100%;
 * - valori nuovi (WHITE/BLUE) → opacità 100% se manca la chiave.
 *
 * Se la chiave dell'opacità esiste già, ha sempre la precedenza sulla migrazione.
 */
internal fun resolveSubtitleAppearance(
  storedBackground: String?,
  storedOpacity: String?
): Pair<SubtitleBackground, SubtitleOpacity> {
  val background = when (storedBackground) {
    SubtitleBackground.NONE.name -> SubtitleBackground.NONE
    SubtitleBackground.WHITE.name -> SubtitleBackground.WHITE
    SubtitleBackground.BLUE.name -> SubtitleBackground.BLUE
    // "SEMI_TRANSPARENT" è il nome storico del nero traslucido, oggi rappresentato
    // da nero + opacità 50%.
    else -> SubtitleBackground.BLACK
  }
  val opacity = storedOpacity
    ?.let { name -> SubtitleOpacity.entries.firstOrNull { it.name == name } }
    ?: when (storedBackground) {
      SubtitleBackground.BLACK.name,
      SubtitleBackground.NONE.name,
      SubtitleBackground.WHITE.name,
      SubtitleBackground.BLUE.name -> SubtitleOpacity.FULL
      else -> SubtitleOpacity.FIFTY
    }
  return background to opacity
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

/** Dimensione target del buffer memoria ExoPlayer. */
enum class TargetBufferOption(val label: String, val sizeMb: Int) {
  DEFAULT("Predefinito (175 MB)", 175),
  MINIMUM("Minimo (150 MB)", 150),
  BALANCED("Bilanciato (300 MB)", 300),
  EXTENDED("Esteso (500 MB)", 500)
}

/** Buffer iniziale prima di avviare la riproduzione (Avvio rapido vs Stabilità). */
enum class InitialBufferOption(val label: String, val durationMs: Int) {
  FAST("Avvio Rapido (1.5s)", 1500),
  STANDARD("Standard (2.5s)", 2500),
  STABLE("Stabile (3.5s)", 3500)
}

/** Durata del back buffer mantenuto per seek istantaneo all'indietro. */
enum class BackBufferOption(val label: String, val durationMs: Int) {
  OFF("Disattivato", 0),
  SHORT("10 secondi", 10_000),
  STANDARD("15 secondi (Default)", 15_000),
  EXTENDED("30 secondi", 30_000),
  MAXIMUM("60 secondi", 60_000)
}

/** Modalità del fallback software per i codec (FFmpeg). */
enum class DecoderFallbackMode(val label: String, val mode: Int) {
  OFF("Disattivato (Solo Hardware)", 0),
  ON("Hardware con Fallback Software", 1),
  PREFER("Preferisci Software (FFmpeg)", 2)
}

/** Impostazioni della sezione RIPRODUZIONE e AVANZATE. */
data class PlaybackSettings(
  val preferredResolution: PreferredResolution = PreferredResolution.AUTO,
  val autoPlayNextEpisodeEnabled: Boolean = true,
  val autoResume: Boolean = true,
  /**
   * "Riusa l'ultimo link": riproduce automaticamente l'ultimo flusso funzionante
   * salvato per lo stesso film/episodio, se la cache è ancora valida.
   */
  val reuseLastWorkingStreamEnabled: Boolean = true,
  /** Validità in ore della cache dell'ultimo flusso riutilizzabile (default 24h = 1 giorno). */
  val lastWorkingStreamCacheDurationHours: Int = 24,
  val prioritizeItalianAudio: Boolean = true,
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
  /** Sfondo dietro il testo dei sottotitoli nel player (default BLACK). */
  val subtitleBackground: SubtitleBackground = SubtitleBackground.BLACK,
  /** Opacità del riquadro di sfondo (default 50%: stesso aspetto del vecchio default). */
  val subtitleOpacity: SubtitleOpacity = SubtitleOpacity.FIFTY,
  /** Posizione verticale dei sottotitoli nel player (default BOTTOM). */
  val subtitlePosition: SubtitlePosition = SubtitlePosition.BOTTOM,

  // ── OVERLAY E AVVISI DI RIPRODUZIONE ────────────────────────────────
  /**
   * Mostra la schermata di caricamento cinematografica (poster + logo) durante l'avvio
   * Debrid (default ON). OFF = comportamento alternativo già previsto: indicatore
   * compatto con spinner e stato di caricamento.
   */
  val loadingOverlayEnabled: Boolean = true,
  /**
   * Mostra lo stato REALE del caricamento durante avvio e preparazione (default ON):
   * fasi derivate solo dagli eventi di ExoPlayer e del resolver delle sorgenti,
   * nessuna percentuale o tempo simulato.
   */
  val loadingStatusEnabled: Boolean = true,
  /**
   * Overlay informativo in pausa (default OFF): dopo 5 secondi di pausa mostra poster,
   * logo, titolo, stagione/episodio e sinossi. Nascondo immediato alla ripresa.
   */
  val pauseOverlayEnabled: Boolean = false,
  /**
   * Avvisi sui contenuti / Parental Guide (default OFF): all'avvio di un contenuto
   * mostra per una breve durata volgarità, violenza, paura e alcol/droghe (dati Tiffara).
   */
  val parentalGuideEnabled: Boolean = false,

  // ── IMPOSTAZIONI AVANZATE ───────────────────────────────────────────
  val targetBuffer: TargetBufferOption = TargetBufferOption.DEFAULT,
  val initialBuffer: InitialBufferOption = InitialBufferOption.STANDARD,
  val backBuffer: BackBufferOption = BackBufferOption.STANDARD,
  val audioPassthroughEnabled: Boolean = false,
  val audioTunnelingEnabled: Boolean = false,
  val decoderFallbackMode: DecoderFallbackMode = DecoderFallbackMode.ON,
  val autoFrameRateMatching: Boolean = false,
  val dolbyVisionFallbackEnabled: Boolean = true,
  val debugOverlayEnabled: Boolean = false
)

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
  private val KEY_SUBTITLE_OPACITY = stringPreferencesKey("subtitle_opacity")
  private val KEY_SUBTITLE_POSITION = stringPreferencesKey("subtitle_position")

  // Overlay e avvisi di riproduzione
  private val KEY_LOADING_OVERLAY_ENABLED = booleanPreferencesKey("loading_overlay_enabled")
  private val KEY_LOADING_STATUS_ENABLED = booleanPreferencesKey("loading_status_enabled")
  private val KEY_PAUSE_OVERLAY_ENABLED = booleanPreferencesKey("pause_overlay_enabled")
  private val KEY_PARENTAL_GUIDE_ENABLED = booleanPreferencesKey("parental_guide_enabled")

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

  /**
   * Migrazione una-tantum dell'aspetto sottotitoli.
   *
   * Il vecchio modello salvava solo lo sfondo (con "Nero semi-trasparente" come default);
   * il nuovo separa colore e opacità. Se l'opacità non è ancora persistita, il valore
   * derivato da [resolveSubtitleAppearance] viene scritto al primo caricamento: così una
   * successiva scelta di sfondo non può più cambiare implicitamente anche l'opacità
   * (il valore derivato dipende dallo sfondo storico finché la chiave manca).
   */
  private var subtitleAppearanceMigrated = false

  fun init(context: Context) {
    if (dataStore != null) return
    val store = context.applicationContext.settingsDataStore
    dataStore = store
    scope.launch {
      store.data.collect { prefs ->
        // Aspetto dei sottotitoli: la coppia sfondo/opacità viene risolta insieme per
        // applicare la migrazione dal vecchio "Nero semi-trasparente" (vedi
        // [resolveSubtitleAppearance]) senza alterare l'aspetto già scelto.
        val storedBackground = prefs[KEY_SUBTITLE_BACKGROUND]
        val storedOpacity = prefs[KEY_SUBTITLE_OPACITY]
        val (subtitleBackground, subtitleOpacity) = resolveSubtitleAppearance(
          storedBackground = storedBackground,
          storedOpacity = storedOpacity
        )
        if (!subtitleAppearanceMigrated) {
          subtitleAppearanceMigrated = true
          // Persistenza una-tantum della migrazione: da qui in poi le due chiavi sono
          // indipendenti e cambiare sfondo non tocca l'opacità (e viceversa).
          if (storedBackground == null || storedOpacity == null) {
            scope.launch {
              dataStore?.edit { prefsToWrite ->
                if (prefsToWrite[KEY_SUBTITLE_BACKGROUND] == null) {
                  prefsToWrite[KEY_SUBTITLE_BACKGROUND] = subtitleBackground.name
                }
                if (prefsToWrite[KEY_SUBTITLE_OPACITY] == null) {
                  prefsToWrite[KEY_SUBTITLE_OPACITY] = subtitleOpacity.name
                }
              }
            }
          }
        }
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
          subtitleBackground = subtitleBackground,
          subtitleOpacity = subtitleOpacity,
          subtitlePosition = prefs[KEY_SUBTITLE_POSITION]
            ?.let { name -> SubtitlePosition.entries.firstOrNull { it.name == name } }
            ?: SubtitlePosition.BOTTOM,

          // Overlay e avvisi di riproduzione
          loadingOverlayEnabled = prefs[KEY_LOADING_OVERLAY_ENABLED] ?: true,
          loadingStatusEnabled = prefs[KEY_LOADING_STATUS_ENABLED] ?: true,
          pauseOverlayEnabled = prefs[KEY_PAUSE_OVERLAY_ENABLED] ?: false,
          parentalGuideEnabled = prefs[KEY_PARENTAL_GUIDE_ENABLED] ?: false,

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

  fun setSubtitleOpacity(value: SubtitleOpacity) {
    scope.launch { dataStore?.edit { it[KEY_SUBTITLE_OPACITY] = value.name } }
  }

  fun setSubtitlePosition(value: SubtitlePosition) {
    scope.launch { dataStore?.edit { it[KEY_SUBTITLE_POSITION] = value.name } }
  }

  fun setLoadingOverlayEnabled(value: Boolean) {
    scope.launch { dataStore?.edit { it[KEY_LOADING_OVERLAY_ENABLED] = value } }
  }

  fun setLoadingStatusEnabled(value: Boolean) {
    scope.launch { dataStore?.edit { it[KEY_LOADING_STATUS_ENABLED] = value } }
  }

  fun setPauseOverlayEnabled(value: Boolean) {
    scope.launch { dataStore?.edit { it[KEY_PAUSE_OVERLAY_ENABLED] = value } }
  }

  fun setParentalGuideEnabled(value: Boolean) {
    scope.launch { dataStore?.edit { it[KEY_PARENTAL_GUIDE_ENABLED] = value } }
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
