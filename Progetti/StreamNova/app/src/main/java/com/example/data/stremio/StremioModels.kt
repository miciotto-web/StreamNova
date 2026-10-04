package com.example.data.stremio

import com.squareup.moshi.Json

/**
 * Modelli del protocollo Stremio (addon / manifest / stream).
 *
 * Tutti i campi sono nullable con default: i manifest e le risposte degli addon
 * sono scritti da terzi e possono omettere campi opzionali senza far fallire il
 * parsing (Moshi + KotlinJsonAdapterFactory rispetta i default del costruttore).
 */

/**
 * Manifest di uno addon Stremio (`GET $baseUrl/manifest.json`).
 *
 * @param resources risorse dichiarate dall'addon ("stream", "meta", "catalog"...).
 *        Nel protocollo Stremio ogni elemento può essere una stringa **oppure** un
 *        oggetto `{"name": "stream"}`: per questo è tipizzato come [Any] e va
 *        letto tramite [resourceNames].
 * @param idPrefixes prefissi degli id supportati ("tt" = IMDb, "tmdb"...).
 */
data class StremioManifest(
  val id: String? = null,
  val name: String? = null,
  val version: String? = null,
  val description: String? = null,
  val resources: List<Any?>? = null,
  val types: List<String>? = null,
  val idPrefixes: List<String>? = null
) {
  /** Nome mostrato all'utente, con fallback su id. */
  val displayTitle: String
    get() = name?.takeIf { it.isNotBlank() } ?: id?.takeIf { it.isNotBlank() } ?: "Addon"

  /** Risorse dichiarate normalizzate a stringhe (`["stream", "meta", ...]`). */
  fun resourceNames(): List<String> = resources.orEmpty().mapNotNull { entry ->
    when (entry) {
      is String -> entry
      is Map<*, *> -> entry["name"] as? String
      else -> null
    }
  }

  /** true se l'addon espone la risorsa "stream" (necessaria per gli stream). */
  fun supportsStream(): Boolean {
    val names = resourceNames()
    return names.isEmpty() || names.any { it.equals("stream", ignoreCase = true) }
  }

  /** true se l'addon supporta il tipo (movie/series). Vuota = dichiarazione assente → assume supportato. */
  fun supportsType(type: String): Boolean =
    types.isNullOrEmpty() || types.any { it.equals(type, ignoreCase = true) }
}

/** Risposta di `$baseUrl/stream/$type/$id.json`. */
data class StremioStreamResponse(
  val streams: List<StremioStreamItem>? = null
)

/**
 * Singolo stream restituito da uno addon.
 *
 * Gli addon possono restituire:
 *  - `url` diretto (addon già configurato con un debrid/CDN),
 *  - `infoHash` + `fileIdx` (torrent "grezzo") da risolvere con TorBox.
 */
data class StremioStreamItem(
  val name: String? = null,
  val title: String? = null,
  val url: String? = null,
  val infoHash: String? = null,
  @Json(name = "fileIdx") val fileIdx: Int? = null
) {
  /** Etichetta combinata usata per estrarre la qualità e mostrare l'origine. */
  val label: String
    get() = listOfNotNull(name, title).filter { it.isNotBlank() }.joinToString(" • ")
}

/**
 * Addon installato e persistito dall'utente.
 *
 * @param baseUrl URL normalizzato della base dell'addon (senza `/manifest.json`).
 * @param manifest manifest scaricato e validato all'installazione.
 * @param isEnabled stato dello switch nella schermata Addon.
 */
data class InstalledAddon(
  val baseUrl: String,
  val manifest: StremioManifest,
  val isEnabled: Boolean = true
) {
  /** Chiave stabile per la rimozione/identità (manifest.id, con fallback sull'URL). */
  val id: String
    get() = manifest.id?.takeIf { it.isNotBlank() } ?: baseUrl
}
