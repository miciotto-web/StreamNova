package com.example.data.stremio

import com.example.domain.model.Subtitle

/**
 * Adapter che trasforma l'uscita di [StremioSubtitleBridge] nel modello interno dei
 * sottotitoli gia' usato da StreamNova ([Subtitle]), che il player andra' a leggere.
 *
 * NON crea un secondo sistema di gestione sottotitoli: riusa lo stesso tipo che
 * [com.example.domain.model.Stream.subtitles] espone gia' al runtime.
 *
 * CODICE PURO: nessuna rete, nessun client HTTP, nessun riferimento a OkHttp/Retrofit/
 * Ktor e nessun accesso ad Android. Il bridge e' gia' stato popolato dal chiamante,
 * quindi l'adapter e' utilizzabile dal runtime senza effettuare alcuna richiesta.
 *
 * MAPPATURA
 *  - `id`   -> [Subtitle.id]
 *  - `url`  -> [Subtitle.url]
 *  - `lang` -> [Subtitle.lang]
 *  - `label`-> [Subtitle.label]
 *  - provenienza `STREAM`   -> [Subtitle.isStreamProvided] = `true`
 *  - provenienza `ENDPOINT` -> [Subtitle.isStreamProvided] = `false`
 *
 * La provenienza e' conservata cosi' com'e': `ENDPOINT` non viene marcato come
 * "fornito dallo stream", perche' arriva dalla risposta `/subtitles/{type}/{id}.json`
 * e non dal campo `stream.subtitles[]`.
 */
object StremioSubtitleAdapter {

  /**
   * Converte una singola voce del bridge nel modello interno.
   *
   * @param entry voce unificata prodotta da [StremioSubtitleBridge].
   * @param addonName nome visualizzato della fonte che ha fornito il sottotitolo.
   * @param addonLogo logo della stessa fonte, se disponibile.
   * @param headers intestazioni da usare per il download, se la fonte leRichiede.
   *
   * @return il [Subtitle] corrispondente, oppure `null` se la voce non e' utilizzabile
   *         (id o URL vuoti dopo la normalizzazione).
   */
  fun toSubtitle(
    entry: StremioSubtitleEntry,
    addonName: String,
    addonLogo: String? = null,
    headers: Map<String, String>? = null
  ): Subtitle? {
    val id = entry.id.trim()
    val url = entry.url.trim()
    // Difensivo: il bridge gia' scarta gli elementi incompleti, ma l'adapter puo'
    // essere chiamato anche con voci costruite altrove e deve non propagare url vuoti.
    if (id.isEmpty() || url.isEmpty()) return null
    return Subtitle(
      id = id,
      url = url,
      lang = entry.lang,
      addonName = addonName,
      addonLogo = addonLogo,
      isStreamProvided = entry.isFromStream,
      headers = headers,
      label = entry.label?.takeIf { it.isNotBlank() }
    )
  }

  /**
   * Converte l'intera lista unificata del bridge, conservando l'ordine di arrivo e
   * scartando (senza interromperlo) le voci non utilizzabili.
   */
  fun toSubtitles(
    entries: List<StremioSubtitleEntry>,
    addonName: String,
    addonLogo: String? = null,
    headers: Map<String, String>? = null
  ): List<Subtitle> = entries.mapNotNull { toSubtitle(it, addonName, addonLogo, headers) }
}
