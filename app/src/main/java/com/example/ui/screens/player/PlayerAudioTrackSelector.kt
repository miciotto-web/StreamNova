package com.example.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks

/**
 * Scelta deterministica della traccia audio preferita a partire dalle tracce
 * esposte da Media3 ([Tracks]).
 *
 * Serve a coprire i casi in cui `DefaultTrackSelector` non riesce a riconoscere
 * la lingua italiana:
 *  - la traccia ha il codice lingua assente/`und` ma l'etichetta "Italiano";
 *  - il contenitore usa il nome completo della lingua invece del codice ISO;
 *  - la selezione automatica ha scelto un'altra lingua pur essendo disponibile
 *    una traccia preferita.
 *
 * La logica è pura (nessun accesso al player) ed è usata da [com.example.ui.screens.PlayerScreen]
 * solo quando l'utente NON ha scelto manualmente una traccia per la sessione corrente.
 */
internal object PlayerAudioTrackSelector {

  /** Riferimento a una traccia audio dentro il risultato di `player.currentTracks`. */
  data class AudioTrackRef(
    val group: Tracks.Group,
    val trackIndex: Int,
    val format: Format
  )

  /**
   * Alias ISO 639-1/639-2 e nomi completi usati nei file: normalizza tutto al
   * codice a due lettere. "und" e le stringhe vuote restano indeterminate.
   */
  private val LANGUAGE_ALIASES = mapOf(
    "ita" to "it", "italian" to "it", "italiano" to "it", "italiana" to "it",
    "eng" to "en", "english" to "en", "inglese" to "en",
    "spa" to "es", "esp" to "es", "spanish" to "es", "espanol" to "es", "español" to "es",
    "spagnolo" to "es",
    "fre" to "fr", "fra" to "fr", "french" to "fr", "francese" to "fr",
    "ger" to "de", "deu" to "de", "german" to "de", "tedesco" to "de",
    "por" to "pt", "portuguese" to "pt", "portoghese" to "pt"
  )

  /** Parole riconoscibili nell'etichetta o nell'id della traccia. */
  private val LABEL_TOKENS = mapOf(
    "it" to listOf("it", "ita", "italian", "italiano", "italiana"),
    "en" to listOf("en", "eng", "english", "inglese"),
    "es" to listOf("es", "spa", "esp", "spanish", "espanol", "español", "spagnolo"),
    "fr" to listOf("fr", "fre", "fra", "french", "francese"),
    "de" to listOf("de", "ger", "deu", "german", "tedesco"),
    "pt" to listOf("pt", "por", "portuguese", "portoghese")
  )

  /** Flag che indicano una traccia non principale (commento/audiodescrizione). */
  private val NON_MAIN_ROLE_FLAGS =
    C.ROLE_FLAG_COMMENTARY or
      C.ROLE_FLAG_DESCRIBES_VIDEO or
      C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND

  /** Normalizza un codice/etichetta linguistica al codice a due lettere. */
  fun normalizeLanguage(value: String?): String {
    val raw = value?.trim()?.lowercase() ?: return ""
    if (raw.isEmpty() || raw == "und" || raw == "unknown" || raw == "undetermined") return ""
    val base = raw
      .replace('_', '-')
      .substringBefore('-')
      .substringBefore('.')
      .substringBefore('/')
      .trim()
    return LANGUAGE_ALIASES[raw] ?: LANGUAGE_ALIASES[base] ?: base
  }

  /** True se il codice lingua della traccia corrisponde a una lingua preferita. */
  fun matchesLanguageCode(language: String?, preferredLanguages: List<String>): Boolean {
    val normalized = normalizeLanguage(language)
    if (normalized.isEmpty()) return false
    return preferredLanguages.any { preferred ->
      val target = normalizeLanguage(preferred)
      target.isNotEmpty() && normalized == target
    }
  }

  /** True se etichetta o id della traccia contengono il nome di una lingua preferita. */
  fun matchesLanguageLabel(format: Format, preferredLanguages: List<String>): Boolean {
    // `label` è il nome predefinito; `labels` contiene le varianti localizzate
    // (es. "Italiano" per label.language = "it") usate da alcuni container.
    val labelValues = buildList {
      format.label?.takeIf { it.isNotBlank() }?.let(::add)
      format.labels?.forEach { label -> label.value?.takeIf { it.isNotBlank() }?.let(::add) }
      format.id?.takeIf { it.isNotBlank() }?.let(::add)
    }
    val haystack = labelValues.joinToString(" ").lowercase()
    if (haystack.isBlank()) return false
    return preferredLanguages.any { preferred ->
      val target = normalizeLanguage(preferred)
      LABEL_TOKENS[target].orEmpty().any { token -> containsWord(haystack, token) }
    }
  }

  /**
   * Ricerca della parola con confini: evita che "ita" corrisponda a "digital" o
   * che "eng" corrisponda a una parola più lunga.
   */
  private fun containsWord(haystack: String, word: String): Boolean {
    var index = haystack.indexOf(word)
    while (index >= 0) {
      val startsAtBoundary = index == 0 || !haystack[index - 1].isLetterOrDigit()
      val afterIndex = index + word.length
      val endsAtBoundary = afterIndex >= haystack.length || !haystack[afterIndex].isLetterOrDigit()
      if (startsAtBoundary && endsAtBoundary) return true
      index = haystack.indexOf(word, index + 1)
    }
    return false
  }

  /** Traccia audio attualmente selezionata dal player, se presente. */
  fun findSelectedAudioTrack(tracks: Tracks): AudioTrackRef? {
    for (group in tracks.groups) {
      if (group.type != C.TRACK_TYPE_AUDIO) continue
      for (index in 0 until group.length) {
        if (group.isTrackSelected(index)) {
          return AudioTrackRef(group, index, group.getTrackFormat(index))
        }
      }
    }
    return null
  }

  /** True se la traccia corrisponde alla lingua preferita per codice o etichetta. */
  fun isPreferredAudioTrack(track: AudioTrackRef, preferredLanguages: List<String>): Boolean =
    matchesLanguageCode(track.format.language, preferredLanguages) ||
      matchesLanguageLabel(track.format, preferredLanguages)

  /**
   * Restituisce la traccia audio da preferire, oppure null se nessuna traccia
   * corrisponde: in quel caso il player resta sulla selezione di Media3.
   *
   * Priorità: codice lingua > etichetta; a parità, traccia principale (non
   * commento/audiodescrizione); a ulteriore parità, primo in ordine.
   */
  fun findPreferredAudioTrack(tracks: Tracks, preferredLanguages: List<String>): AudioTrackRef? {
    if (preferredLanguages.isEmpty()) return null
    var best: AudioTrackRef? = null
    var bestScore = 0
    for (group in tracks.groups) {
      if (group.type != C.TRACK_TYPE_AUDIO) continue
      for (index in 0 until group.length) {
        if (!group.isTrackSupported(index)) continue
        val format = group.getTrackFormat(index)
        val score = matchScore(format, preferredLanguages)
        if (score > bestScore) {
          bestScore = score
          best = AudioTrackRef(group, index, format)
        }
      }
    }
    return best
  }

  private fun matchScore(format: Format, preferredLanguages: List<String>): Int {
    val languageMatch = matchesLanguageCode(format.language, preferredLanguages)
    val labelMatch = matchesLanguageLabel(format, preferredLanguages)
    if (!languageMatch && !labelMatch) return 0
    var score = if (languageMatch) 2 else 1
    if ((format.roleFlags and NON_MAIN_ROLE_FLAGS) == 0) score += 10
    return score
  }
}
