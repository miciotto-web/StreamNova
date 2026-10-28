package com.example.data.stremio.provider

import com.example.data.stremio.TYPE_MOVIE
import com.example.data.stremio.TYPE_SERIES

/**
 * Identità stabile di un catalogo Stremio, circoscritta al singolo addon che lo espone.
 *
 * PERCHÉ SERVONO TRE CAMPI
 * - `catalogId` da solo NON è univoco: due addon diversi possono esporre `movie/disney_movies`.
 *   È quindi `addonManifestId` a rendere la chiave univoca nel perimetro dell'app.
 * - `type` distingue `movie` da `series`: un binding dichiarato sui film NON vale
 *   automaticamente per le serie, nemmeno a parità di `catalogId`.
 *
 * NORMALIZZAZIONE
 * L'uguaglianza del data class è quella sui valori grezzi. Per i lookup case-insensitive
 * (mappe di binding, registry su disco, store utente) usare [stableId], che applica
 * trim + lowercase: così `" Netflix "` e `"netflix"` non generano due binding distinti.
 */
data class CatalogKey(
  val addonManifestId: String,
  val type: String,
  val catalogId: String
) {
  init {
    require(addonManifestId.isNotBlank()) { "addonManifestId non può essere vuoto" }
    require(type.isNotBlank()) { "type non può essere vuoto" }
    require(catalogId.isNotBlank()) { "catalogId non può essere vuoto" }
  }

  /** Manifest id dell'addon, normalizzato. */
  val normalizedAddonId: String get() = addonManifestId.trim().lowercase()

  /** Tipo Stremio normalizzato ("movie" / "series"). */
  val stremioType: String get() = type.trim().lowercase()

  /** Id del catalogo normalizzato. */
  val normalizedCatalogId: String get() = catalogId.trim().lowercase()

  /**
   * Chiave stabile e normalizzata `addon|type|catalogId`, usata come chiave delle mappe
   * di lookup di USER bindings e REGISTRY.
   */
  val stableId: String get() = "$normalizedAddonId|$stremioType|$normalizedCatalogId"

  /** true se la chiave identifica un catalogo di tipo film. */
  val isMovie: Boolean get() = stremioType == TYPE_MOVIE

  /** true se la chiave identifica un catalogo di tipo serie. */
  val isSeries: Boolean get() = stremioType == TYPE_SERIES

  override fun toString(): String = "CatalogKey($stableId)"

  companion object {
    /**
     * Costruisce una chiave a partire da valori potenzialmente nulli o sporchi,
     * normalizzando gli spazi. Restituisce `null` se un campo obbligatorio manca:
     * un manifest senza `id` o un catalogo senza `type` non è associabile.
     */
    fun of(addonManifestId: String?, type: String?, catalogId: String?): CatalogKey? {
      val addon = addonManifestId?.trim().orEmpty()
      val cleanType = type?.trim().orEmpty()
      val cleanCatalog = catalogId?.trim().orEmpty()
      if (addon.isEmpty() || cleanType.isEmpty() || cleanCatalog.isEmpty()) return null
      return CatalogKey(addon, cleanType, cleanCatalog)
    }
  }
}