package com.example.data.opensubtitles

import com.example.domain.model.Subtitle

/**
 * Adapter che converte le risposte di OpenSubtitles API v3 nel modello di dominio [Subtitle].
 *
 * Mantiene la piena compatibilità con l'architettura Subtitle esistente:
 * - `id`: prefissato con "os-" per garantire unicità e tracciabilità;
 * - `url`: URL di download del FILE subtitle valido e verificato (scarta risultati
 *   null/blank/non-http e le pagine web OpenSubtitles);
 * - `lang`: lingua normalizzata (ISO 639-1 / 639-2lowercase);
 * - `addonName`: "OpenSubtitles v3";
 * - `isStreamProvided`: false (provider esterno, non embedded nello stream Stremio);
 * - `label`: dettagli su rilascio/file/SDH se presenti.
 */
object OpenSubtitlesSubtitleAdapter {

  const val PROVIDER_NAME = "OpenSubtitles v3"

  /**
   * Converte un singolo elemento OpenSubtitles in [Subtitle].
   *
   * Vengono accettati SOLO URL HTTP/HTTPS realmente destinati al download del file subtitle
   * (`download_url`, `files[].download_url`, `files[].link` o l'override esplicito).
   * `attributes.url` è l'URL della PAGINA WEB OpenSubtitles e non viene mai usato: caricare
   * quell'HTML come se fosse un file SubRip fa fallire la decodifica del sottotitolo.
   *
   * Se non esiste un URL di download diretto valido l'elemento viene scartato (`null`),
   * così il provider può comunque risolverlo tramite `file_id` tramite l'endpoint di
   * download v3, senza generare Subtitle inutilizzabili.
   */
  fun toSubtitle(
    item: OpenSubtitlesItemDto,
    downloadUrlOverride: String? = null,
    addonName: String = PROVIDER_NAME,
    addonLogo: String? = null
  ): Subtitle? {
    val attr = item.attributes ?: return null
    val rawId = (item.id ?: attr.subtitleId ?: attr.files?.firstOrNull()?.fileId?.toString())
      ?.trim()
      ?.takeIf { it.isNotBlank() } ?: return null

    val rawUrl = downloadUrlOverride
      ?: attr.downloadUrl
      ?: attr.files?.firstOrNull()?.downloadUrl
      ?: attr.files?.firstOrNull()?.link

    val validUrl = rawUrl?.trim()?.takeIf { it.isNotBlank() && isValidDownloadUrl(it) } ?: return null

    val lang = normalizeLanguage(attr.language)
    val label = buildLabel(attr)

    return Subtitle(
      id = "os-$rawId",
      url = validUrl,
      lang = lang,
      addonName = addonName,
      addonLogo = addonLogo,
      isStreamProvided = false,
      headers = null,
      label = label
    )
  }

  /**
   * Converte un'intera risposta di ricerca OpenSubtitles in una lista di [Subtitle],
   * scartando automaticamente gli elementi privi di URL di download valido.
   */
  fun toSubtitles(
    response: OpenSubtitlesSearchResponse?,
    addonName: String = PROVIDER_NAME,
    addonLogo: String? = null
  ): List<Subtitle> {
    val items = response?.data ?: return emptyList()
    return items.mapNotNull { toSubtitle(it, addonName = addonName, addonLogo = addonLogo) }
  }

  /**
   * Verifica che la stringa sia un URL HTTP o HTTPS realmente destinato al download del
   * file sottotitolo: esclude gli URL non http(s) e le pagine web OpenSubtitles
   * (che restituiscono HTML, non un file subtitle).
   */
  fun isValidDownloadUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return false
    val lower = trimmed.lowercase()
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
    return !isWebsitePageUrl(trimmed)
  }

  /**
   * Host del sito OpenSubtitles da cui non arriva mai un file subtitle (solo pagine HTML).
   * I link di download effettivi usano altri host (es. `dl.opensubtitles.com`).
   */
  private val WEBSITE_HOSTS = setOf(
    "opensubtitles.org",
    "www.opensubtitles.org",
    "opensubtitles.com",
    "www.opensubtitles.com",
    "opensubtitles.net",
    "www.opensubtitles.net"
  )

  /** Estensioni tipiche dei file sottotitolo scaricabili. */
  private val SUBTITLE_FILE_EXTENSIONS = listOf(".srt", ".vtt", ".webvtt", ".ass", ".ssa", ".ttml", ".dfxp", ".sub", ".idx", ".txt")

  /**
   * Riconosce gli URL delle pagine web di un sottotitolo, es.
   * `https://www.opensubtitles.org/en/subtitles/4472195/the-matrix-en`.
   *
   * Un URL ospitato sul sito OpenSubtitles è una pagina HTML a meno che non indichi
   * esplicitamente un download (`/download`) o termini con l'estensione di un file
   * sottotitolo: quelle pagine non contengono alcun file decodificabile.
   */
  fun isWebsitePageUrl(url: String): Boolean {
    val afterScheme = url.substringAfter("://", "")
    if (afterScheme.isEmpty()) return false
    val host = afterScheme.substringBefore('/')
      .substringBefore('@')
      .substringBefore(':')
      .lowercase()
    if (host !in WEBSITE_HOSTS) return false
    val path = afterScheme.substringAfter('/', "").substringBefore('?').substringBefore('#').lowercase()
    if (path.isEmpty()) return true
    if (path.contains("download")) return false
    return SUBTITLE_FILE_EXTENSIONS.none { path.endsWith(it) }
  }

  /**
   * Normalizza il codice lingua restituito da OpenSubtitles per renderlo coerente
   * con il modello [Subtitle] (es. "ita" -> "it", "eng" -> "en", ecc.).
   */
  fun normalizeLanguage(rawLang: String?): String {
    val clean = rawLang?.trim()?.lowercase().orEmpty()
    if (clean.isEmpty()) return "und"
    return when (clean) {
      "ita", "it-it" -> "it"
      "eng", "en-us", "en-gb" -> "en"
      "spa", "es-es", "es-la" -> "es"
      "fra", "fre", "fr-fr" -> "fr"
      "deu", "ger", "de-de" -> "de"
      "por", "pt-pt", "pt-br" -> "pt"
      "jpn" -> "ja"
      "kor" -> "ko"
      "zho", "chi" -> "zh"
      "rus" -> "ru"
      "ara" -> "ar"
      "hin" -> "hi"
      "pol" -> "pl"
      "nld", "dut" -> "nl"
      "swe" -> "sv"
      "nor" -> "no"
      "dan" -> "da"
      "fin" -> "fi"
      "ell", "gre" -> "el"
      "tur" -> "tr"
      "heb" -> "he"
      "tha" -> "th"
      "vie" -> "vi"
      "ind" -> "id"
      "msa", "may" -> "ms"
      "ron", "rum" -> "ro"
      "hun" -> "hu"
      "ces", "cze" -> "cs"
      "slk", "slo" -> "sk"
      "ukr" -> "uk"
      "bul" -> "bg"
      "hrv" -> "hr"
      "srp" -> "sr"
      "slv" -> "sl"
      "est" -> "et"
      "lav" -> "lv"
      "lit" -> "lt"
      else -> if (clean.length == 3) clean.take(2) else clean
    }
  }

  private fun buildLabel(attr: OpenSubtitlesAttributesDto): String? {
    val release = attr.release?.trim()?.takeIf { it.isNotBlank() }
    val fileName = attr.files?.firstOrNull()?.fileName?.trim()?.takeIf { it.isNotBlank() }
    val comments = attr.comments?.trim()?.takeIf { it.isNotBlank() }
    val hi = if (attr.hearingImpaired == true) "[SDH]" else null

    val baseLabel = release ?: fileName ?: comments
    return if (baseLabel != null && hi != null) {
      "$baseLabel $hi"
    } else {
      baseLabel ?: hi
    }
  }
}
