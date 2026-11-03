package com.aistudio.streamnova.mobile.ui

import com.example.data.prefs.PreferredResolution
import com.example.data.prefs.StreamingEngineMode

/**
 * Regole pure delle Impostazioni Mobile, allineate alla TV 1.0.4.
 *
 * Nessuna dipendenza da Compose o dal Context: le decisioni che la UI applica
 * (lingue, vincoli di risoluzione, selezione sorgente, ripresa) sono verificabili
 * nei test JVM e restano identiche a quelle del player TV.
 */
object MobileSettingsPolicy {

  /** Lingue dell'app: stesse cinque della TV. */
  val APP_LANGUAGE_CODES = listOf("it", "en", "es", "fr", "de")

  /** Lingue audio selezionabili; "" = Originale / Qualsiasi. */
  val AUDIO_LANGUAGE_CODES = listOf("it", "en", "es", "fr", "de", "")

  /** Lingue sottotitoli selezionabili; "" = Originale / Qualsiasi. */
  val SUBTITLE_LANGUAGE_CODES = listOf("it", "en", "es", "fr", "de", "pt", "")

  /** Soglie del controllo genitori, come la TV. */
  val PARENTAL_LEVELS = listOf("14", "16", "18")

  /**
   * Mapping dei codici lingua preferiti sui codici ISO 639-1 e 639-2 accettati
   * dalla selezione automatica delle tracce Media3 (stessa tabella della TV).
   */
  fun mapPreferredAudioLanguages(code: String): List<String> {
    val normalized = code.trim().lowercase()
    return when (normalized) {
      "it", "ita" -> listOf("it", "ita")
      "en", "eng" -> listOf("en", "eng")
      "es", "spa" -> listOf("es", "spa")
      "fr", "fra", "fre" -> listOf("fr", "fra", "fre")
      "de", "deu", "ger" -> listOf("de", "deu", "ger")
      "", "und" -> emptyList()
      else -> listOf(normalized, normalized.substringBefore('-')).distinct()
    }
  }

  /** Lingua preferita dei sottotitoli; null = nessun vincolo (Originale / Qualsiasi). */
  fun subtitleLanguage(code: String): String? =
    code.trim().lowercase().takeIf { it.isNotEmpty() && it != "und" }

  /**
   * Vincolo massimo della traccia video per la risoluzione preferita.
   *
   * La TV non applica alcun vincolo per Auto e per 4K (lascia che ExoPlayer
   * selezioni la traccia migliore); per 1080p/720p impone la larghezza 16:9
   * corrispondente. `null` = nessun vincolo.
   */
  fun resolutionConstraint(resolution: PreferredResolution): Pair<Int, Int>? =
    when (resolution) {
      PreferredResolution.AUTO, PreferredResolution.UHD_4K -> null
      PreferredResolution.FULL_HD_1080P -> 1920 to 1080
      PreferredResolution.HD_720P -> 1280 to 720
    }

  /**
   * L'autoplay OFF apre la selezione sorgente solo in modalità Debrid, come la TV.
   * In HTTP lo scraper ha già scelto la sorgente migliore e non c'è nulla da
   * negoziare.
   *
   * Con [manualSelection] = true (pulsante «Seleziona fonte» della scheda
   * Dettaglio) il selettore viene mostrato SEMPRE: la scelta manuale non
   * avvia alcuna riproduzione automatica, indipendentemente dall'impostazione
   * Autoplay.
   */
  fun shouldShowSourcePicker(
    autoplayEnabled: Boolean,
    engineMode: StreamingEngineMode,
    sourceCount: Int,
    manualSelection: Boolean = false
  ): Boolean = MobileManualSourcePolicy.shouldShowSourcePicker(
    manualSelection = manualSelection,
    autoplayEnabled = autoplayEnabled,
    engineMode = engineMode,
    sourceCount = sourceCount
  )

  /**
   * Posizione iniziale del player:
   * - "Riprendi automaticamente" OFF riparte sempre da 0:00;
   * - un episodio/film già concluso riparte da 0:00 (stessa regola TV);
   * - altrimenti riprende dalla posizione salvata.
   */
  fun startPositionMs(
    savedPositionMs: Long,
    durationMs: Long,
    autoResume: Boolean
  ): Long {
    if (!autoResume) return 0L
    val saved = savedPositionMs.coerceAtLeast(0L)
    val finished = durationMs > 0L && saved >= (durationMs * 0.90f)
    return if (finished) 0L else saved
  }

  /** Maschera le chiavi API nella UI: `abcd1234...wxyz` → `abcd••••••••wxyz`. */
  fun maskApiKey(key: String?): String {
    val clean = key?.trim().orEmpty()
    return if (clean.length <= 8) "••••••••" else clean.take(4) + "••••••••" + clean.takeLast(4)
  }

  /** True quando la lingua audio corrente è italiana (per i sottotitoli forced). */
  fun isItalianAudioLanguage(language: String?): Boolean {
    val normalized = language?.trim()?.lowercase().orEmpty()
    return normalized == "it" || normalized == "ita" || normalized.startsWith("it-")
  }
}
