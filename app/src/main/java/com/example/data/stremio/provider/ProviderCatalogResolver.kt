package com.example.data.stremio.provider

import android.content.Context
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
