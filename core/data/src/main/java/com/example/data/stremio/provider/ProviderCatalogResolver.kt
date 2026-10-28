package com.example.data.stremio.provider

import android.content.Context
import com.example.data.stremio.CinemetaAddon
import com.example.data.stremio.EXTRA_GENRE
import com.example.data.stremio.StremioCatalogTarget
import com.example.data.stremio.StremioManifest
import com.example.ui.components.ProviderConstants
import com.example.ui.components.StreamingProvider

/**
 * Punto d'ingresso unico per associare un catalogo Stremio a un provider StreamNova.
 *
 * CONTRATTO
 * - [resolve] è DETERMINISTICO e può restituire solo un binding dichiarato
 *   ([BindingSource.USER] o [BindingSource.REGISTRY]). Non deduce, non indovina,
 *   non applica euristiche: catalogo non dichiarato → `null`.
 * - [propose] restituisce solo [ProviderBindingProposal], quindi nulla di ciò che
 *   produce può essere applicato dallo stesso resolver.
 */
interface ProviderCatalogResolver {

  /** Binding dichiarato per la chiave, con precedenza USER → REGISTRY → `null`. */
  fun resolve(key: CatalogKey): ProviderBinding?

  /** Candidate euristiche per un manifest: suggerimenti, mai applicati. */
  fun propose(manifest: StremioManifest): List<ProviderBindingProposal>
}

/**
 * Producer di proposte. Implementato dal proposer euristico; il resolver lo tratta
 * come una black box che non può produrre binding.
 */
interface ProviderBindingProposer {
  fun propose(
    manifest: StremioManifest,
    providers: List<ProviderAliasProvider>
  ): List<ProviderBindingProposal>
}

/**
 * Implementazione con precedenza esplicita USER → REGISTRY → `null`.
 *
 * @param userBindings associazioni dichiarate dall'utente (DataStore).
 * @param registry dati dichiarativi bundled (`res/raw/stremio_provider_bindings.json`).
 * @param proposer produttore di proposte (non può mai produrre binding).
 * @param providers catalogo dei provider con i loro alias; overridibile nei test.
 */
class DefaultProviderCatalogResolver(
  private val userBindings: ProviderBindingSource,
  private val registry: ProviderBindingSource,
  private val proposer: ProviderBindingProposer = HeuristicProviderCatalogProposer(),
  private val providers: () -> List<ProviderAliasProvider> = { defaultAliasProviders() }
) : ProviderCatalogResolver {

  override fun resolve(key: CatalogKey): ProviderBinding? =
    userBindings.find(key) ?: registry.find(key)

  override fun propose(manifest: StremioManifest): List<ProviderBindingProposal> =
    proposer.propose(manifest, providers())

  /**
   * Tutti i binding applicabili di un provider (USER + REGISTRY), deduplicati per
   * chiave con precedenza USER. Le proposte euristiche non compaiono mai.
   */
  fun bindingsFor(providerId: String): List<ProviderBinding> =
    applicableBindings().filter { it.normalizedProviderId == providerId.trim().lowercase() }

  /** tutti i binding applicabili, indicizzati per provider normalizzato. */
  fun bindingsByProvider(): Map<String, List<ProviderBinding>> =
    applicableBindings().groupBy { it.normalizedProviderId }

  /** Unione delle due sorgenti, con User che vince sul Registry a parità di chiave. */
  fun applicableBindings(): List<ProviderBinding> {
    val byKey = LinkedHashMap<String, ProviderBinding>()
    registry.all().forEach { byKey[it.key.stableId] = it }
    userBindings.all().forEach { byKey[it.key.stableId] = it }
    return byKey.values.toList()
  }

  /** true se esiste almeno un binding dichiarato per il provider. */
  fun hasBindingsFor(providerId: String): Boolean = bindingsFor(providerId).isNotEmpty()

  /**
   * true se [addonManifestId] identifica un addon di SISTEMA autorizzato come
   * catalogo nativo di default (Cinemeta). Serve a non richiedere alcuna conferma
   * utente per la sorgente predefinita della Home: il default è già autorizzato.
   */
  fun isSystemDefaultAddon(addonManifestId: String?): Boolean =
    CinemetaAddon.isSystemAddon(addonManifestId)

  companion object {
    /** I provider dell'app, esposti come vista dati con i loro alias dichiarativi. */
    fun defaultAliasProviders(): List<ProviderAliasProvider> =
      ProviderConstants.ALL.map { it.asAliasProvider() }

    /** Come [defaultAliasProviders], ma su una lista arbitraria (test, override). */
    fun aliasProvidersOf(providers: List<StreamingProvider>): List<ProviderAliasProvider> =
      providers.map { it.asAliasProvider() }
  }
}

/**
 * Punto di accesso condiviso al resolver.
 *
 * Deliberatamente non usato dalla UI esistente: `StremioCatalogRepository`
 * (`getProviderKeywords` / `findTargetsForProvider`) continua a funzionare invariato.
 * L'integrazione avverrà nella fase successiva.
 */
object ProviderCatalogResolvers {

  @Volatile private var instance: ProviderCatalogResolver? = null

  @Volatile private var applicationContext: Context? = null

  /** Fornisce il contesto dell'applicazione per il caricamento della registry da `res/raw`. */
  fun init(context: Context) {
    applicationContext = context.applicationContext
  }

  /** Sostituzione del resolver (solo test). */
  fun setForTesting(resolver: ProviderCatalogResolver?) {
    instance = resolver
  }

  /** Resolver condiviso, creato al primo accesso. */
  fun get(context: Context? = null): ProviderCatalogResolver {
    instance?.let { return it }
    synchronized(this) {
      instance?.let { return it }
      val ctx = context?.applicationContext ?: applicationContext
      val registry = if (ctx != null) {
        ProviderBindingRegistry.load(ctx)
      } else {
        ProviderBindingRegistry.empty()
      }
      val created = DefaultProviderCatalogResolver(
        userBindings = UserProviderBindingStore.source(),
        registry = registry
      )
      instance = created
      return created
    }
  }
}

/**
 * Politica di SORGENTE dei cataloghi della schermata **Provider**.
 *
 * REGOLE (in ordine)
 * 1. **Addon di catalogo personalizzati attivi** (Xperience / Easy Catalog / ...,
 *    cioè `hasCustomCatalogAddons() == true`):
 *     - i cataloghi di **Cinemeta non entrano MAI** nel piano del provider:
 *       [selectTargets] li esclude a monte e [matchesProvider] rifiuta ogni
 *       target Cinemeta anche se qualcuno arrivasse a valle;
 *     - i cataloghi degli addon attivi vengono associati al provider con un match
 *       DICHIARATIVO sul manifest dell'addon: `catalog.id`, `catalog.name`, il nome
 *       dell'addon e i valori dichiarati per gli extra `genre` / `provider` /
 *       `platform` ([matchesProvider]). Nessun contenuto Cinemeta può sopravvivere
 *       in nessuna schermata provider.
 * 2. **Nessun addon di catalogo personalizzato** (`hasCustomCatalogAddons() == false`):
 *    Cinemeta, addon di sistema, resta ammesso come FALLBACK ed è l'unica sorgente
 *    di default autorizzata ([systemDefaultBinding]).
 * 3. Il confronto è su **confine di token** (mai sottostringa grezza: "max" non
 *    matcha "maxwell", "apple" non matcha "pineapple") e copre i token ESATTI di
 *    ogni piattaforma (`prime` / `amazon`, `paramount`, `crunchyroll`, `disney`,
 *    `apple`, `hbo` / `max`, `netflix`) oltre alle varianti dichiarate dai
 *    provider: `amazon prime` / `prime video`, `paramount+` / `paramount plus`,
 *    ecc. Un catalogo NON viene scartato se non dichiara gli extra `genre` /
 *    `provider`: nella maggior parte dei manifest è la piattaforma stessa,
 *    stampata nell'ID del catalogo, a identificarla.
 *
 * IMPORTANTE: questo oggetto NON tocca [ProviderCatalogResolver.resolve]: la
 * sorgente dichiarata (USER → REGISTRY) resta intatta. I binding prodotti qui
 * vengono iniettati nel piano dal [ProviderCatalogPlanner] e portano sorgenti
 * distinte ([BindingSource.CUSTOM_ADDON] / [BindingSource.SYSTEM_DEFAULT]).
 */
object ProviderCatalogSourcePolicy {

  /** Extra i cui VALORI dichiarati possono nominare una piattaforma. */
  private val PLATFORM_EXTRA_NAMES = setOf(EXTRA_GENRE, "provider", "platform")

  /**
   * Lunghezza minima dell'alias compattato per ammettere il match senza confine
   * di token (copre id senza separatori come `paramountplus_movies`): protegge
   * gli alias corti ("prime", "apple", "hbo") dai falsi positivi su sottostringhe.
   */
  private const val MIN_COMPACT_ALIAS_LENGTH = 6

  private const val CINEMETA_TOKEN = "cinemeta"

  private const val CRUNCHYROLL_ID = "crunchyroll"

  private const val ANIME_TOKEN = "anime"

  /**
   * Token ESATTI che identificano una piattaforma nell'ID o nel nome del catalogo
   * (manifest Xperience / Easy Catalog: `streaming_prime_movies`,
   * `tmdb.movie.amazon`, `snoak_paramount_top10_movies`, `streaming_crunchyroll_movies`, ...).
   */
  private val CATALOG_ID_TOKENS: Map<String, Set<String>> = mapOf(
    "prime" to setOf("prime", "amazon"),
    "paramount" to setOf("paramount"),
    CRUNCHYROLL_ID to setOf(CRUNCHYROLL_ID),
    "disney" to setOf("disney"),
    "apple" to setOf("apple"),
    "hbo" to setOf("hbo", "max"),
    "netflix" to setOf("netflix")
  )

  private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
  private val REPEATED_SPACES = Regex("\\s+")

  /** true se [addonId]/[baseUrl] identificano l'addon di sistema Cinemeta. */
  fun isCinemeta(addonId: String?, baseUrl: String? = null): Boolean {
    val id = addonId?.trim()?.lowercase().orEmpty()
    val url = baseUrl?.trim()?.lowercase().orEmpty()
    if (id.isEmpty() && url.isEmpty()) return false
    return id.contains(CINEMETA_TOKEN) ||
      url.contains(CINEMETA_TOKEN) ||
      id == CinemetaAddon.MANIFEST_ID.lowercase() ||
      url == CinemetaAddon.BASE_URL.lowercase()
  }

  /** true se il target appartiene a Cinemeta (id manifest o URL base). */
  fun isCinemetaTarget(target: StremioCatalogTarget): Boolean =
    isCinemeta(target.addonId, target.baseUrl)

  /**
   * Target ammessi nella schermata del provider.
   *
   * Con addon di catalogo personalizzati attivi i target Cinemeta vengono rimossi
   * per sempre: i cataloghi dell'addon DEVONO sostituire al 100% quelli di Cinemeta.
   * Senza addon personalizzati Cinemeta resta disponibile come fallback.
   */
  fun selectTargets(
    targets: List<StremioCatalogTarget>,
    hasCustomCatalogAddons: Boolean
  ): List<StremioCatalogTarget> =
    if (hasCustomCatalogAddons) targets.filterNot { isCinemetaTarget(it) } else targets

  /** Alias effettivi del provider (alias dichiarativi + slug + nome), normalizzati. */
  fun aliasesOf(provider: ProviderAliasProvider): Set<String> =
    (provider.providerAliases + provider.providerId + provider.providerName)
      .asSequence()
      .map { normalize(it) }
      .filter { it.isNotEmpty() }
      .toSet()

  /**
   * true se [target] è un catalogo di un ADDON PERSONALIZZATO che corrisponde a
   * [provider].
   *
   * Il target appartiene al provider quando l'ID del catalogo o il nome del
   * catalogo contiene uno dei token esatti del provider in [CATALOG_ID_TOKENS]
   * (`prime` / `amazon`, `paramount`, `crunchyroll`, `disney`, `apple`,
   * `hbo` / `max`, `netflix`) oppure uno degli alias dichiarati, il nome
   * dell'addon o un'opzione dichiarata per `genre` / `provider` / `platform`.
   *
   * NESSUN extra è obbligatorio: un catalogo già specifico per la piattaforma
   * (es. `streaming_prime_movies`, `streaming_crunchyroll_movies`) non viene
   * mai scartato perché non dichiara `genre` / `provider`.
   *
   * [addonCatalogIds] è l'elenco dei cataloghi dello stesso addon e serve al
   * fallback Crunchyroll: il token `anime` vale SOLO se l'addon non espone
   * cataloghi Crunchyroll espliciti. I target Cinemeta non corrispondono MAI.
   */
  fun matchesProvider(
    provider: ProviderAliasProvider,
    target: StremioCatalogTarget,
    addonCatalogIds: Collection<String> = emptyList()
  ): Boolean {
    if (isCinemetaTarget(target)) return false
    val providerId = provider.providerId.trim().lowercase()
    val aliases = aliasesOf(provider) + CATALOG_ID_TOKENS[providerId].orEmpty()
    if (aliases.isEmpty()) return false

    val catalogDeclared = listOf(
      normalize(target.catalogId),
      normalize(target.title),
      normalize(target.catalog.name.orEmpty())
    ).filter { it.isNotEmpty() }
    val addonName = normalize(target.addonName)
    val declared = if (addonName.isNotEmpty()) catalogDeclared + addonName else catalogDeclared

    val platformOptions = platformOptionValues(target)
      .map { normalize(it) }
      .filter { it.isNotEmpty() }

    val matched = aliases.any { alias ->
      declared.any { matchesAlias(alias, it) } || platformOptions.any { matchesAlias(alias, it) }
    }
    if (matched) return true

    if (providerId != CRUNCHYROLL_ID || addonCatalogIds.isEmpty()) return false
    val crunchyrollTokens = CATALOG_ID_TOKENS[CRUNCHYROLL_ID].orEmpty()
    val hasExplicitCatalog = addonCatalogIds.any { catalogId ->
      val normalized = normalize(catalogId)
      crunchyrollTokens.any { matchesAlias(it, normalized) }
    }
    if (hasExplicitCatalog) return false
    return catalogDeclared.any { matchesAlias(normalize(ANIME_TOKEN), it) }
  }

  /**
   * Binding derivato dal match sui cataloghi dell'addon personalizzato attivo.
   * Restituisce `null` se il target non corrisponde al provider o non è associabile.
   * [addonCatalogIds] ha lo stesso significato di [matchesProvider].
   */
  fun customAddonBinding(
    provider: ProviderAliasProvider,
    target: StremioCatalogTarget,
    addonCatalogIds: Collection<String> = emptyList()
  ): ProviderBinding? =
    if (matchesProvider(provider, target, addonCatalogIds)) {
      bindingFor(provider, target, BindingSource.CUSTOM_ADDON)
    } else {
      null
    }

  /**
   * Binding di FALLBACK sui cataloghi di sistema Cinemeta: usato SOLO quando non
   * è attivo nessun addon di catalogo personalizzato e nessun binding dichiarato
   * copre il provider. Cinemeta non è specifico del provider, quindi il piano che
   * lo contiene va marcato `fallbackToDefault` ([ProviderCatalogPlan.fallbackToDefault])
   * e non può mai valere copertura `FULL`.
   */
  fun systemDefaultBinding(
    provider: ProviderAliasProvider,
    target: StremioCatalogTarget
  ): ProviderBinding? =
    if (isCinemetaTarget(target)) {
      bindingFor(provider, target, BindingSource.SYSTEM_DEFAULT)
    } else {
      null
    }

  /**
   * Extra di PIATTAFORMA dichiarati dal manifest da inviare per [target]
   * (es. `provider=Prime Video`, `platform=Paramount+`, `genre=...`).
   *
   * Per ogni extra `genre` / `provider` / `platform` del catalogo, se un'opzione
   * realmente dichiarata coincide con un alias del provider viene restituita come
   * `nomeExtra → opzione`: senza inviarla il catalogo restituirebbe contenuti di
   * TUTTE le piattaforme etichettati come del provider.
   */
  fun platformExtraFor(
    provider: ProviderAliasProvider,
    target: StremioCatalogTarget
  ): Map<String, String> {
    if (isCinemetaTarget(target)) return emptyMap()
    val aliases = aliasesOf(provider)
    if (aliases.isEmpty()) return emptyMap()

    val result = LinkedHashMap<String, String>()
    for (extra in target.catalog.extras) {
      val name = extra.name?.trim()?.takeIf { it.isNotEmpty() } ?: continue
      if (name.lowercase() !in PLATFORM_EXTRA_NAMES) continue
      val option = extra.options.orEmpty().firstOrNull { value ->
        val normalized = normalize(value)
        normalized.isNotEmpty() && aliases.any { matchesAlias(it, normalized) }
      }
      if (option != null) result[name] = option
    }
    return result
  }

  private fun bindingFor(
    provider: ProviderAliasProvider,
    target: StremioCatalogTarget,
    source: BindingSource
  ): ProviderBinding? {
    val providerId = provider.providerId.trim().lowercase()
    if (providerId.isEmpty()) return null
    val key = CatalogKey.of(target.addonId, target.type, target.catalogId) ?: return null
    return ProviderBinding(
      key = key,
      providerId = providerId,
      source = source,
      confidence = BindingConfidence.CONFIRMED
    )
  }

  /** Valori dichiarati per gli extra di piattaforma (`genre` / `provider` / `platform`). */
  private fun platformOptionValues(target: StremioCatalogTarget): List<String> =
    target.catalog.extras
      .filter { extra ->
        val name = extra.name?.trim()?.lowercase()
        name != null && name in PLATFORM_EXTRA_NAMES
      }
      .flatMap { extra -> extra.options.orEmpty() }

  /**
   * Match con confine di token su testo già normalizzato + fallback compattato
   * per gli alias sufficientemente lunghi (id senza separatori).
   */
  private fun matchesAlias(alias: String, candidate: String): Boolean {
    if (alias.isEmpty() || candidate.isEmpty()) return false
    if (" $candidate ".contains(" $alias ")) return true
    val compactAlias = alias.replace(" ", "")
    if (compactAlias.length < MIN_COMPACT_ALIAS_LENGTH) return false
    return candidate.replace(" ", "").contains(compactAlias)
  }

  /** Minuscole, sequenze non alfanumeriche ridotte a un singolo spazio, spazi trimmed. */
  private fun normalize(value: String): String =
    value.lowercase()
      .replace(NON_ALPHANUMERIC, " ")
      .replace(REPEATED_SPACES, " ")
      .trim()
}
