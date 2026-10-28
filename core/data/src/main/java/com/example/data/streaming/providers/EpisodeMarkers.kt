package com.example.data.streaming.providers

/**
 * Riconoscimento e segmentazione dei marker di episodio nei cataloghi HTML.
 *
 * Formati accettati:
 *  - `S01E02`, `S1E2`, `S01.E02`, `S01-E02`, `S01 E02` (case-insensitive);
 *  - `1x02`, `1X02`, `1×02` (con o senza spazi);
 *  - `Stagione 1 Episodio 2`, `Season 1 Episode 2` (anche con separatore).
 *
 * Le risoluzioni video (`1920x1080`, `1280x720`, `720x480`, ...) non sono
 * marker di episodio: vengono escluse prima di qualunque confronto.
 */
internal object EpisodeMarkers {

  /** Normalizza solo le entità che rappresentano il segno di moltiplicazione. */
  fun normalize(html: String): String = html
    .replace("&#215;", "×")
    .replace("&#x00D7;", "×", ignoreCase = true)
    .replace("&#10005;", "×")
    .replace("&#x2715;", "×", ignoreCase = true)
    .replace("&times;", "×", ignoreCase = true)

  /** Marker richiesto, con stagione ed episodio esatti. */
  fun requestedRegex(season: Int, episode: Int): Regex = Regex(
    """(?:\bS0*$season[\s._-]*E0*$episode\b|\b0*$season\s*[x×]\s*0*$episode\b|\b(?:stagione|season)\s*0*$season\s*[-–—:]*\s*(?:episodio|episode|ep\.?)\s*0*$episode\b)""",
    setOf(RegexOption.IGNORE_CASE)
  )

  /** Qualsiasi marker di episodio, indipendentemente dal formato. */
  private val ANY_MARKER = Regex(
    """(?:\bS\d{1,3}[\s._-]*E\d{1,4}\b|\b\d{1,3}\s*[x×]\s*\d{1,4}\b|\b(?:stagione|season)\s*\d{1,3}\s*[-–—:]*\s*(?:episodio|episode|ep\.?)\s*\d{1,4}\b)""",
    setOf(RegexOption.IGNORE_CASE)
  )

  /**
   * Segmento dell'episodio richiesto: dal marker iniziale fino al marker
   * successivo più vicino (qualunque formato), oppure fino a fine documento.
   *
   * @return `null` se il marker richiesto non compare.
   */
  fun segment(rawHtml: String, season: Int, episode: Int): String? {
    val content = normalize(rawHtml)
    val start = findRequested(content, season, episode) ?: return null
    val next = findNextMarker(content, start.range.last + 1)
    val end = next?.range?.first ?: content.length
    return content.substring(start.range.first, end)
  }

  /** Primo marker valido che corrisponde esattamente a stagione/episodio. */
  fun findRequested(content: String, season: Int, episode: Int): MatchResult? =
    requestedRegex(season, episode).findAll(content).firstOrNull { !isResolution(it.value) }

  /** Primo marker valido dopo [from], di qualunque formato. */
  fun findNextMarker(content: String, from: Int): MatchResult? =
    ANY_MARKER.findAll(content, from).firstOrNull { !isResolution(it.value) }

  /**
   * Vero se il testo ha la forma di una risoluzione video. Copre le coppie
   * esplicite più note e, per sicurezza, qualunque `LARGHEZZAxALTEZZA` con
   * larghezza plausibile e maggiore dell'altezza (i marker stagione×episodio
   * non hanno questa forma).
   */
  fun isResolution(value: String): Boolean {
    val compact = value.replace(Regex("""\s+"""), "").lowercase()
    if (EXPLICIT_RESOLUTIONS.any { it.equals(compact, ignoreCase = true) }) return true
    val parts = compact.split('x', '×')
    if (parts.size != 2) return false
    val width = parts[0].toIntOrNull() ?: return false
    val height = parts[1].toIntOrNull() ?: return false
    return width >= 640 && height >= 360 && width > height
  }

  private val EXPLICIT_RESOLUTIONS = setOf(
    "3840x2160", "2560x1440", "1920x1080", "1280x720", "1024x576",
    "854x480", "800x480", "768x432", "720x480", "720x576", "640x480", "640x360"
  )
}
