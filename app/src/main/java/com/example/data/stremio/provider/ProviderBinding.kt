package com.example.data.stremio.provider

/**
 * Origine dichiarativa di un [ProviderBinding]: chi ha stabilito l'associazione
 * catalogo → provider. Non esiste (e non deve esistere) un valore "euristica":
 * le proposte dell'euristica vivono in [ProviderBindingProposal] e non entrano mai
 * in [ProviderCatalogResolver.resolve].
 */
enum class BindingSource {
  /** Scelta esplicita dell'utente. Precedenza massima. */
  USER,

  /** Dati dichiarativi bundled nell'app (`res/raw/stremio_provider_bindings.json`). */
  REGISTRY,

  /**
   * Match sui cataloghi di un ADDON DI CATALOGO PERSONALIZZATO attivo
   * (Xperience / Easy Catalog / ...): alias del provider confrontati con
   * `catalog.id`, `catalog.name`, nome addon ed extra (`genre` / `provider` /
   * `platform`) dichiarati dal manifest. Generato SOLO quando
   * `hasCustomCatalogAddons()` è `true` e MAI per i target Cinemeta.
   */
  CUSTOM_ADDON,

  /**
   * Fallback di sistema sui cataloghi Cinemeta, generato SOLO quando
   * `hasCustomCatalogAddons()` è `false` e nessun binding dichiarato copre
   * il provider.
   */
  SYSTEM_DEFAULT
}

/** Grado di conferma del binding. */
enum class BindingConfidence {
  /** Dato dichiarato: associazione accettata. */
  CONFIRMED,

  /** Riscontro parziale, non ancora accettato (solo proposte). */
  SUGGESTED
}

/**
 * Associazione dichiarata fra un catalogo Stremio e un provider StreamNova.
 *
 * Un binding è SEMPRE derivato da DATI DETERMINISTICI, mai da un punteggio:
 * - [BindingSource.USER]: scelta esplicita dell'utente;
 * - [BindingSource.REGISTRY]: dati dichiarativi bundled (`res/raw`).
 * - [BindingSource.CUSTOM_ADDON]: match sui cataloghi di un addon di catalogo
 *   personalizzato attivo (Xperience / Easy Catalog / ...), calcolato sugli alias
 *   del provider confrontati con id, nome e extra dichiarati dal manifest.
 *   Usato SOLO quando `hasCustomCatalogAddons()` è `true`.
 * - [BindingSource.SYSTEM_DEFAULT]: fallback sui cataloghi di sistema Cinemeta,
 *   usato SOLO quando `hasCustomCatalogAddons()` è `false` e nessun binding
 *   dichiarato copre il provider.
 *
 * Il resolver non può mai sintetizzare un binding a partire da un nome o da un id:
 * [ProviderCatalogResolver.resolve] restituisce solo USER o REGISTRY; le sorgenti
 * CUSTOM_ADDON e SYSTEM_DEFAULT vengono iniettate esclusivamente dal
 * [ProviderCatalogPlanner] nel piano di caricamento del provider.
 */
data class ProviderBinding(
  val key: CatalogKey,
  val providerId: String,
  val source: BindingSource,
  val confidence: BindingConfidence = BindingConfidence.CONFIRMED
) {
  init {
    require(providerId.isNotBlank()) { "providerId non può essere vuoto" }
  }

  /** Provider normalizzato (slug interno, es. "disney"). */
  val normalizedProviderId: String get() = providerId.trim().lowercase()

  /**
   * true se il binding è utilizzabile: tutti i [BindingSource] sono DATI
   * deterministici (scelta utente, registry, match su manifest, fallback di
   * sistema), mai un punteggio soglia. Resta esplicito per documentare
   * l'invariante "nulla euristico entra in resolve".
   */
  val isDeclarative: Boolean
    get() = true
}

/**
 * Sorgente di binding consultabile in modo sincrono.
 * Implementazioni: UserProviderBindingSource (DataStore) e ProviderBindingRegistryData.
 */
interface ProviderBindingSource {
  /** Binding per chiave, `null` se il catalogo non è dichiarato in questa sorgente. */
  fun find(key: CatalogKey): ProviderBinding?

  /** Tutti i binding dichiarati in questa sorgente. */
  fun all(): List<ProviderBinding>
}

/**
 * Candidata prodotta dal proposer euristico.
 *
 * NON è un [ProviderBinding] ed è intenzionalmente un tipo diverso: non può essere
 * memorizzata, non può essere passata a [ProviderBindingSource] e non viene mai
 * restituita da [ProviderCatalogResolver.resolve]. Va solo proposta all'utente.
 *
 * @param key catalogo candidato all'associazione.
 * @param providerId provider suggerito.
 * @param score confidenza del suggerimento, normalizzata 0.0..1.0.
 * @param reasons motivazioni leggibili (id, titolo, alias, copertura movie+series).
 */
data class ProviderBindingProposal(
  val key: CatalogKey,
  val providerId: String,
  val score: Double,
  val reasons: List<String> = emptyList()
) {
  init {
    require(score in 0.0..1.0) { "score deve essere compreso tra 0.0 e 1.0, era $score" }
    require(providerId.isNotBlank()) { "providerId non può essere vuoto" }
  }

  /** Provider normalizzato (slug interno). */
  val normalizedProviderId: String get() = providerId.trim().lowercase()

  companion object {
    /** Ordina le proposte dalla più alla meno confidente, a parità di provider in ordine stabile. */
    fun sortedByConfidence(proposals: List<ProviderBindingProposal>): List<ProviderBindingProposal> =
      proposals.sortedWith(
        compareByDescending<ProviderBindingProposal> { it.score }
          .thenBy { it.normalizedProviderId }
          .thenBy { it.key.stableId }
      )
  }
}

/** Sorgente vuota: nessun binding dichiarato. Usata come fallback sicuro. */
object EmptyProviderBindingSource : ProviderBindingSource {
  override fun find(key: CatalogKey): ProviderBinding? = null
  override fun all(): List<ProviderBinding> = emptyList()
}

/**
 * Sorgente in memoria, usata dai test e come composizione di più sorgenti.
 * Indicizza per [CatalogKey.stableId], quindi i lookup sono case-insensitive.
 */
class InMemoryProviderBindingSource(
  bindings: List<ProviderBinding> = emptyList()
) : ProviderBindingSource {

  private val byStableId: Map<String, ProviderBinding> =
    bindings.associateBy { it.key.stableId }

  override fun find(key: CatalogKey): ProviderBinding? = byStableId[key.stableId]

  override fun all(): List<ProviderBinding> = byStableId.values.toList()
}
