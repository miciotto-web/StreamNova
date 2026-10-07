package com.example.data.stremio.provider

import com.example.data.model.MediaItem
import com.example.data.stremio.StremioCatalogMapper
import com.example.data.stremio.StremioMetaItem

/**
 * Risultato grezzo di un singolo target di catalogo interrogato.
 */
data class CatalogEntryResult(
  val target: ResolvedProviderTarget,
  val rawItems: List<StremioMetaItem>
)

/**
 * Catalogo Provider assemblato: titoli deduplicati con la loro provenienza.
 */
data class AssembledCatalog(
  val items: List<MediaItem> = emptyList(),
  val provenance: Map<String, List<CatalogProvenance>> = emptyMap()
)

/**
 * Assembla i risultati grezzi di più cataloghi in un'unica lista per la UI.
 *
 * FUNZIONE PURA: nessuna rete, nessun singleton, quindi verificabile in test JVM.
 *
 * PROVENIENZA
 * Un titolo può arrivare da più addon e più cataloghi. La deduplicazione per id
 * COLLAPSHA il titolo ma CONSERVA tutte le origini nella mappa di provenienza:
 * due addon che espongono lo stesso `catalogId` restano distinguibili senza
 * duplicare i poster a schermo.
 *
 * `MediaItem` non viene modificato: `provider` continua a contenere il nome visuale,
 * come previsto dalla fase C2 (semantica `providerSlug` rinviata a una fase successiva).
 */
object ProviderCatalogAssembler {

  /** Deduplica stabile per id, con fallback su titolo+anno per id vuoti. */
  fun deduplicate(items: List<MediaItem>): List<MediaItem> {
    val seen = mutableSetOf<String>()
    return items.filter { item ->
      val key = item.id.trim().lowercase().ifBlank { "${item.title.trim().lowercase()}_${item.year}" }
      seen.add(key)
    }
  }

  /**
   * Accorpa i risultati di più cataloghi di un provider.
   *
   * @param providerName nome visuale usato per `MediaItem.provider` (semantica invariata).
   * @param results risultati grezzi, uno per catalogo interrogato: più addon e più tipi
   *        sono ammessi nello stesso batch.
   */
  fun assemble(providerName: String, results: List<CatalogEntryResult>): AssembledCatalog {
    if (results.isEmpty()) return AssembledCatalog()

    val provenance = LinkedHashMap<String, MutableList<CatalogProvenance>>()
    val items = results.flatMap { result ->
      val origin = CatalogProvenance(
        addonManifestId = result.target.target.addonId,
        catalogId = result.target.target.catalogId,
        type = result.target.type
      )
      StremioCatalogMapper.toMediaItemList(result.rawItems, providerName).map { item ->
        val bucket = provenance.getOrPut(item.id) { mutableListOf() }
        if (origin !in bucket) bucket += origin
        item
      }
    }

    val deduped = deduplicate(items)
    // La provenienza dei titoli collassati confluisce sulla voce mantenuta.
    val kept = deduped.mapTo(mutableSetOf()) { it.id }
    provenance.keys.retainAll(kept)

    val immutableProvenance = LinkedHashMap<String, List<CatalogProvenance>>()
    provenance.forEach { entry -> immutableProvenance[entry.key] = entry.value.toList() }

    return AssembledCatalog(items = deduped, provenance = immutableProvenance)
  }
}