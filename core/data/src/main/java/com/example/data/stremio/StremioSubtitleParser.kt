package com.example.data.stremio

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Parser della risposta `subtitles` del protocollo Stremio.
 *
 * FUNZIONE PURA: nessuna rete, nessun singleton Android, quindi verificabile in test JVM.
 *
 * ROBUSTEZZA (il parser non deve mai far fallire il chiamante)
 * - `subtitles` assente, `null` o lista vuota → lista vuota;
 * - JSON non valido o `null` → lista vuota, senza eccezioni;
 * - elemento incompleto (nessun `id`, `url` o `lang`, valori vuoti, `null`) → scartato
 *   **solo** quell'elemento: gli altri sottotitoli restano utilizzabili.
 */
object StremioSubtitleParser {

  private val moshi: Moshi = Moshi.Builder()
    .add(KotlinJsonAdapterFactory())
    .build()

  private val adapter = moshi.adapter(StremioSubtitleResponse::class.java)

  /**
   * Parsing difensivo di un corpo di risposta `subtitles`.
   *
   * @return sottotitoli completi e utilizzabili; lista vuota se la risposta è assente,
   *         illeggibile o priva di elementi validi.
   */
  fun parse(json: String?): List<StremioSubtitle> {
    val body = json?.trim().orEmpty()
    if (body.isEmpty()) return emptyList()
    return try {
      usable(adapter.fromJson(body)?.subtitles)
    } catch (e: Exception) {
      // Risposta malformata: lista vuota invece di propagare l'errore.
      emptyList()
    }
  }

  /**
   * Filtro condiviso dalle due sorgenti di sottotitoli: scarta gli elementi `null`
   * o incompleti e restituisce solo quelli utilizzabili.
   */
  fun usable(items: List<StremioSubtitle?>?): List<StremioSubtitle> =
    items.orEmpty().filterNotNull().filter { it.isValid }

  /**
   * Parsing partendo dal body di una risposta HTTP riuscita.
   * Un body vuoto o `null` produce una lista vuota.
   */
  fun parseBody(body: String?): List<StremioSubtitle> = parse(body)
}
