package com.example.data.stremio

/**
 * Provenienza di un sottotitolo Stremio, cioè da quale delle due fonti ufficiali arriva.
 *
 * L'ordine di priorita' del bridge e' [STREAM] prima di [ENDPOINT]: i sottotitoli
 * dichiarati dentro `stream.subtitles[]` sono gia' allineati alla release riprodotta,
 * quindi prevalgono sulla stessa URL richiesta all'endpoint `/subtitles/...`.
 */
enum class StremioSubtitleSource {
  /** Campo `subtitles[]` dichiarato dallo stream (`stream.subtitles[]`). */
  STREAM,

  /** Risposta dell'endpoint `/subtitles/{type}/{id}.json`. */
  ENDPOINT
}

/**
 * Sottotitolo esterno unificato: il tipo che il Player consumera' in una fase successiva.
 *
 * E' una vista interna e **immutabile** costruita dal bridge: contiene tutto quello che
 * serve per mostrare e scaricare il sottotitolo, piu' la provenienza.
 *
 * @param id identificativo dichiarato dall'addon.
 * @param url URL assoluto del file, come dichiarato (solo spazi esterni ripuliti).
 * @param lang lingua normalizzata (lowercase).
 * @param label etichetta opzionale mostrata all'utente.
 * @param source fonte da cui proviene.
 */
data class StremioSubtitleEntry(
  val id: String,
  val url: String,
  val lang: String,
  val label: String? = null,
  val source: StremioSubtitleSource
) {
  /** Chiave di deduplicazione: URL normalizzato. */
  val dedupeKey: String get() = StremioSubtitleBridge.normalizeUrl(url)

  /** true se il sottotitolo arriva da `stream.subtitles[]`. */
  val isFromStream: Boolean get() = source == StremioSubtitleSource.STREAM

  /** Etichetta mostrata: quella dichiarata, altrimenti la lingua. */
  val displayLabel: String get() = label?.takeIf { it.isNotBlank() } ?: lang
}

/**
 * Bridge unico fra le due fonti di sottotitoli Stremio.
 *
 * RESPONSABILITA'
 *  1. validare con la stessa regola dei parser ([StremioSubtitleParser.usable] ->
 *     `StremioSubtitle.isValid`), scartando `null` e elementi incompleti;
 *  2. unire le due fonti in una lista unica, nell'ordine STREAM -> ENDPOINT;
 *  3. deduplicare per URL normalizzato, senza perdere informazioni e senza eliminare
 *     sottotitoli diversi che condividono la sola lingua.
 *
 * CODICE PURO: nessuna rete, nessun client HTTP, nessun riferimento a OkHttp/Retrofit/
 * Ktor e nessun accesso ad Android. Il bridge non esegue richieste: chiama il
 * repository se serve e gli passa il risultato.
 */
object StremioSubtitleBridge {

  /**
   * Unisce le due fonti nell'unica lista finale.
   *
   * @param stream sottotitoli da `stream.subtitles[]` (hanno la precedenza).
   * @param endpoint sottotitoli da `/subtitles/{type}/{id}.json`.
   *
   * @return lista ordinata: prima tutti gli elementi della fonte STREAM, poi quelli
   *         della fonte ENDPOINT; a parità di URL resta una sola voce con provenienza
   *         STREAM. Entrambi i parametri sono opzionali e `null` viene trattato come
   *         lista vuota.
   */
  fun merge(
    stream: List<StremioSubtitle?>? = null,
    endpoint: List<StremioSubtitle?>? = null
  ): List<StremioSubtitleEntry> {
    val merged = LinkedHashMap<String, StremioSubtitleEntry>()
    collect(stream, StremioSubtitleSource.STREAM, merged)
    collect(endpoint, StremioSubtitleSource.ENDPOINT, merged)
    return merged.values.toList()
  }

  /**
   * Sottotitoli dichiarati da uno stream. Accetta direttamente lo stream così il
   * chiamante non deve estrarre il campo.
   */
  fun fromStream(stream: StremioStreamItem?): List<StremioSubtitleEntry> =
    merge(stream = stream?.subtitles)

  /** Sottotitoli dell'endpoint `/subtitles/{type}/{id}.json`. */
  fun fromEndpoint(subtitles: List<StremioSubtitle?>?): List<StremioSubtitleEntry> =
    merge(endpoint = subtitles)

  /**
   * Normalizzazione dell'URL usata come chiave di deduplicazione: trim degli spazi
   * esterni e sequenze di spazi interni trasformate in `%20`, cosi'
   * `https://a.test/x y.srt` e `https://a.test/x%20y.srt` sono la stessa risorsa.
   * Non viene fatto lower-casing: il percorso di un URL case-sensitive lo distingue.
   */
  fun normalizeUrl(rawUrl: String): String =
    rawUrl.trim().replace(WHITESPACE_RUN, "%20")

  /**
   * Aggiunge gli elementi utilizzabili di una fonte al risultato unificato,
   * conservando l'ordine di arrivo e la prima provenienza incontrata.
   */
  private fun collect(
    subtitles: List<StremioSubtitle?>?,
    source: StremioSubtitleSource,
    sink: MutableMap<String, StremioSubtitleEntry>
  ) {
    StremioSubtitleParser.usable(subtitles).forEach { subtitle ->
      val rawUrl = subtitle.effectiveUrl ?: return@forEach
      val key = normalizeUrl(rawUrl)
      val entry = StremioSubtitleEntry(
        id = subtitle.effectiveId.orEmpty(),
        url = rawUrl.trim(),
        lang = subtitle.normalizedLang,
        label = subtitle.label?.takeIf { it.isNotBlank() },
        source = source
      )
      val existing = sink[key]
      if (existing == null) {
        sink[key] = entry
      } else if (existing.label.isNullOrBlank() && entry.label != null) {
        // Stesso file dichiarato due volte: si tiene la voce a priorita' maggiore e si
        // recupera l'etichetta che mancava, cosi' nessuna informazione viene persa.
        sink[key] = existing.copy(label = entry.label)
      }
    }
  }

  private val WHITESPACE_RUN = Regex("\\s+")
}
