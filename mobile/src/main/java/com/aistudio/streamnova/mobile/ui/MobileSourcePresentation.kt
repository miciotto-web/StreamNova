package com.aistudio.streamnova.mobile.ui

import com.example.data.streaming.StreamSource
import com.example.data.streaming.TorBoxSourceOrdering
import java.util.Locale

/**
 * Dati mostrati per ogni sorgente nel selettore mobile.
 *
 * Regola: si espone SOLO ciò che il modello [StreamSource] dichiara davvero
 * (provider, qualità, risoluzione, release, lingue audio, dimensione, dettagli
 * residui). Nessun metadato viene inventato: un campo assente resta
 * semplicemente assente e non viene sostituito con un valore plausibile.
 */
object MobileSourcePresentation {

  /** Nome del provider: l'addon quando dichiarato, altrimenti il server. */
  fun providerLabel(source: StreamSource): String =
    source.addonName?.trim()?.takeIf { it.isNotEmpty() }
      ?: source.serverName.trim()

  /** Qualità dichiarata dalla sorgente (il modello ha sempre un valore). */
  fun qualityLabel(source: StreamSource): String =
    source.declaredQuality.takeIf { it.isNotBlank() } ?: source.quality

  /** Risoluzione effettiva ("4K", "1080p", ...); null quando non ricavabile. */
  fun resolutionLabel(source: StreamSource): String? =
    source.resolution.takeIf { it.isNotBlank() && !it.equals("Auto", ignoreCase = true) }

  /** Nome della release (file) dichiarato dalla sorgente. */
  fun releaseLabel(source: StreamSource): String? =
    source.releaseTitle?.trim()?.takeIf { it.isNotEmpty() }

  /** Lingue audio riconosciute nella release; null quando non deducibili. */
  fun languageLabel(source: StreamSource): String? =
    source.detectedAudioLanguages
      .takeIf { it.isNotEmpty() }
      ?.joinToString(" · ")

  /** Dimensione del file dichiarata dalla sorgente; null quando ignota. */
  fun sizeLabel(source: StreamSource): String? {
    val bytes = TorBoxSourceOrdering.sizeBytesOf(source)
    if (bytes <= 0L) return null
    return formatBytes(bytes)
  }

  /**
   * Dettagli residui della sorgente (peer, provider, ...) privi della
   * dimensione, che viene mostrata al suo posto. Null se non resta nulla.
   */
  fun detailsLabel(source: StreamSource): String? {
    val details = source.details?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val cleaned = SIZE_REGEX
      .replace(details, " ")
      .replace("|", " ")
      .replace(Regex("\\s+"), " ")
      .trim()
      .trim('·', '-', '•')
      .trim()
    return cleaned.takeIf { it.isNotEmpty() }
  }

  /** "2.18 GB" dalla dimensione in byte; null se il valore non è utilizzabile. */
  fun formatBytes(bytes: Long): String? {
    if (bytes <= 0L) return null
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
      value /= 1024.0
      index++
    }
    return if (index == 0) "$bytes B"
    else String.format(Locale.US, "%.2f %s", value, units[index])
  }

  private val SIZE_REGEX =
    Regex("(\\d+(?:[.,]\\d+)?)\\s*(TB|TiB|GB|GiB|MB|MiB|KB|KiB|B)\\b", RegexOption.IGNORE_CASE)
}
