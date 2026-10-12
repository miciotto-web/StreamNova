package com.example.data.stremio

/**
 * Filtro centralizzato dei cataloghi/addon "rumorosi" che non devono mai comparire
 * nella Home o nelle altre sezioni (es. righe "Top Film Nova" / "Top serie Nova"
 * esposte dall'addon di terze parti "Nova stream addon").
 *
 * I pattern sono confrontati in modo **case-insensitive** sia con l'id che con il
 * nome del catalogo/addon: un elemento viene escluso se contiene uno qualsiasi dei
 * pattern. Il filtro è data-driven (nessuna stringa hardcoded sparsa) e viene
 * applicato ai punti di ingresso — manifest, engine, repository — così tutti i
 * percorsi a valle (Home, Film/Serie TV, Provider, categorie, ricerca) lo ereditano.
 */
object StremioCatalogFilters {

  /** Pattern (minuscoli) che identificano cataloghi/addon da ignorare sempre. */
  val ignoredCatalogPatterns = listOf(
    "top film nova",
    "top serie nova",
    "nova stream addon"
  )

  /** true se [value] contiene uno dei [ignoredCatalogPatterns]. */
  private fun matches(value: String?): Boolean {
    val normalized = value?.trim()?.lowercase()
    if (normalized.isNullOrEmpty()) return false
    return ignoredCatalogPatterns.any { normalized.contains(it) }
  }

  /** true se il catalogo (id o nome) deve essere ignorato. */
  fun isIgnoredCatalog(catalogId: String?, catalogName: String?): Boolean =
    matches(catalogId) || matches(catalogName)

  /** true se la definizione di catalogo (id o nome) deve essere ignorata. */
  fun isIgnoredCatalog(catalog: StremioCatalogDefinition?): Boolean =
    catalog != null && isIgnoredCatalog(catalog.id, catalog.name)

  /** true se l'addon (id, nome o URL base) deve essere ignorato. */
  fun isIgnoredAddon(
    addonId: String?,
    addonName: String? = null,
    baseUrl: String? = null
  ): Boolean = matches(addonId) || matches(addonName) || matches(baseUrl)

  /** true se l'addon installato deve essere ignorato. */
  fun isIgnoredAddon(addon: InstalledAddon?): Boolean =
    addon != null && isIgnoredAddon(addon.id, addon.manifest.name, addon.baseUrl)

  /** true se il target (addon o catalogo) deve essere ignorato. */
  fun isIgnored(target: StremioCatalogTarget?): Boolean {
    if (target == null) return false
    return isIgnoredAddon(target.addonId, target.addonName, target.baseUrl) ||
      isIgnoredCatalog(target.catalog)
  }
}
