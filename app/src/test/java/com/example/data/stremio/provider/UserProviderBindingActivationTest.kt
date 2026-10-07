package com.example.data.stremio.provider

import com.example.data.model.MediaType
import com.example.data.stremio.StremioCatalogDefinition
import com.example.data.stremio.StremioCatalogTarget
import com.example.data.stremio.StremioManifest
import com.example.data.stremio.TYPE_MOVIE
import com.example.data.stremio.TYPE_SERIES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test di attivazione di un provider a partire da un binding **utente**.
 *
 * Copre la giunzione C3.1 -> C3.2: i binding vengono prodotti dalla UI Addon e
 * persistiti da [UserProviderBindingStore], poi devono arrivare al resolver e da li'
 * al piano di caricamento che decide se ProviderScreen mostra Stremio o TMDB.
 *
 * Nessuna rete, nessun Android, nessun Compose: si verifica la decisione
 * `hasConfirmedBindings` (Stremio) contro piano vuoto (fallback TMDB) come
 * funzioni pure.
 */
class UserProviderBindingActivationTest {

  // ── Fixture ──────────────────────────────────────────────────────────────────

  private data class TestProvider(
    override val providerId: String,
    override val providerName: String,
    override val providerAliases: Set<String>
  ) : ProviderAliasProvider

  private val providers = listOf(
    TestProvider("disney", "Disney+", setOf("disney")),
    TestProvider("netflix", "Netflix", setOf("netflix"))
  )

  private fun target(addonId: String, type: String, catalogId: String) = StremioCatalogTarget(
    addonId = addonId,
    addonName = "Addon $addonId",
    baseUrl = "https://example.test/$addonId",
    catalog = StremioCatalogDefinition(
      type = type,
      id = catalogId,
      name = catalogId,
      extraSupported = listOf("skip")
    )
  )

  /** Due cataloghi dello stesso brand, uno per tipo: il caso reale di un addon. */
  private val disneyTargets = listOf(
    target("addon.disney", TYPE_MOVIE, "streaming_disney_movies"),
    target("addon.disney", TYPE_SERIES, "streaming_disney_series")
  )

  private fun userBinding(addonId: String, type: String, catalogId: String, providerId: String) =
    ProviderBinding(
      key = CatalogKey(addonId, type, catalogId),
      providerId = providerId,
      source = BindingSource.USER,
      confidence = BindingConfidence.CONFIRMED
    )

  private fun registryBinding(addonId: String, type: String, catalogId: String, providerId: String) =
    ProviderBinding(
      key = CatalogKey(addonId, type, catalogId),
      providerId = providerId,
      source = BindingSource.REGISTRY,
      confidence = BindingConfidence.CONFIRMED
    )

  /**
   * Riproduce il percorso reale del binding utente: la UI C3.1 chiama
   * [ProviderBindingConfirmation.confirm], la persistenza passa da
   * [UserProviderBindingStore.encode] e il resolver legge il JSON decodificato.
   */
  private fun resolverFromPersistedUserBindings(
    userBindings: List<ProviderBinding>,
    registry: List<ProviderBinding> = emptyList()
  ): DefaultProviderCatalogResolver {
    val persisted = UserProviderBindingStore.decode(UserProviderBindingStore.encode(userBindings))
    return DefaultProviderCatalogResolver(
      userBindings = InMemoryProviderBindingSource(persisted),
      registry = InMemoryProviderBindingSource(registry),
      proposer = HeuristicProviderCatalogProposer(),
      providers = { providers }
    )
  }

  private fun plan(resolver: DefaultProviderCatalogResolver, providerId: String, mediaType: MediaType?) =
    ProviderCatalogPlanner.plan(providerId, disneyTargets, resolver, mediaType)

  // ── A. Nessun binding -> fallback TMDB ──────────────────────────────────────

  @Test
  fun nessunBindingAttivaIlProviderEIlFallbackRestaTmdb() {
    val resolver = resolverFromPersistedUserBindings(emptyList())

    val movies = plan(resolver, "disney", MediaType.FILM)
    val series = plan(resolver, "disney", MediaType.SERIE_TV)
    val all = plan(resolver, "disney", null)

    // hasConfirmedBindings == false e' esattamente la condizione con cui
    // ProviderScreen mantiene il catalogo nativo TMDB.
    assertFalse(movies.hasConfirmedBindings)
    assertFalse(series.hasConfirmedBindings)
    assertFalse(all.hasConfirmedBindings)
    assertEquals(1, all.unmappedTargets)
  }

  // ── B. USER binding movie -> il provider carica i film da Stremio ────────────

  @Test
  fun userBindingMovieAttivaIlCatalogoStremioDeiFilm() {
    val resolver = resolverFromPersistedUserBindings(
      listOf(userBinding("addon.disney", TYPE_MOVIE, "streaming_disney_movies", "disney"))
    )

    val movies = plan(resolver, "disney", MediaType.FILM)

    assertTrue(movies.hasConfirmedBindings)
    assertEquals(1, movies.resolved.size)
    assertEquals("streaming_disney_movies", movies.resolved.single().target.catalogId)
    assertEquals(MediaType.FILM, movies.resolved.single().mediaType)
    assertEquals(BindingSource.USER, movies.resolved.single().binding.source)
    assertEquals(BindingConfidence.CONFIRMED, movies.resolved.single().binding.confidence)
  }

  // ── C. USER binding series -> il provider carica le serie da Stremio ─────────

  @Test
  fun userBindingSeriesAttivaIlCatalogoStremioDelleSerie() {
    val resolver = resolverFromPersistedUserBindings(
      listOf(userBinding("addon.disney", TYPE_SERIES, "streaming_disney_series", "disney"))
    )

    val series = plan(resolver, "disney", MediaType.SERIE_TV)

    assertTrue(series.hasConfirmedBindings)
    assertEquals(1, series.resolved.size)
    assertEquals("streaming_disney_series", series.resolved.single().target.catalogId)
    assertEquals(MediaType.SERIE_TV, series.resolved.single().mediaType)
    assertEquals(BindingSource.USER, series.resolved.single().binding.source)
  }

  @Test
  fun userBindingDiEntrambiITipiAttivaEntrambi() {
    val resolver = resolverFromPersistedUserBindings(
      listOf(
        userBinding("addon.disney", TYPE_MOVIE, "streaming_disney_movies", "disney"),
        userBinding("addon.disney", TYPE_SERIES, "streaming_disney_series", "disney")
      )
    )

    val all = plan(resolver, "disney", null)

    assertTrue(all.hasConfirmedBindings)
    assertEquals(2, all.resolved.size)
    assertEquals(setOf(MediaType.FILM, MediaType.SERIE_TV), all.resolved.map { it.mediaType }.toSet())
  }

  // ── D. Un binding movie NON attiva le serie (e viceversa) ────────────────────

  @Test
  fun userBindingMovieNonAttivaLeSerie() {
    val resolver = resolverFromPersistedUserBindings(
      listOf(userBinding("addon.disney", TYPE_MOVIE, "streaming_disney_movies", "disney"))
    )

    assertFalse("Il solo binding movie non deve attivare il catalogo series", plan(resolver, "disney", MediaType.SERIE_TV).hasConfirmedBindings)
    assertTrue(plan(resolver, "disney", MediaType.FILM).hasConfirmedBindings)
  }

  @Test
  fun userBindingSeriesNonAttivaIFilm() {
    val resolver = resolverFromPersistedUserBindings(
      listOf(userBinding("addon.disney", TYPE_SERIES, "streaming_disney_series", "disney"))
    )

    assertFalse("Il solo binding series non deve attivare il catalogo movie", plan(resolver, "disney", MediaType.FILM).hasConfirmedBindings)
    assertTrue(plan(resolver, "disney", MediaType.SERIE_TV).hasConfirmedBindings)
  }

  @Test
  fun stessoCatalogIdNonConsenteIlContaminamentoFraTipi() {
    // Stesso catalogId, due tipi: la chiave li tiene separati anche a parita' di id.
    val targets = listOf(
      target("addon.disney", TYPE_MOVIE, "disney_all"),
      target("addon.disney", TYPE_SERIES, "disney_all")
    )
    val resolver = resolverFromPersistedUserBindings(
      listOf(userBinding("addon.disney", TYPE_MOVIE, "disney_all", "disney"))
    )

    val series = ProviderCatalogPlanner.plan("disney", targets, resolver, MediaType.SERIE_TV)

    assertFalse(series.hasConfirmedBindings)
    assertNull(resolver.resolve(CatalogKey("addon.disney", TYPE_SERIES, "disney_all")))
    assertEquals(1, ProviderCatalogPlanner.plan("disney", targets, resolver, MediaType.FILM).resolved.size)
  }

  // ── E. USER prevale su REGISTRY ──────────────────────────────────────────────

  @Test
  fun userBindingPrevaleSulBindingDiRegistry() {
    val resolver = resolverFromPersistedUserBindings(
      userBindings = listOf(userBinding("addon.disney", TYPE_MOVIE, "streaming_disney_movies", "disney")),
      registry = listOf(registryBinding("addon.disney", TYPE_MOVIE, "streaming_disney_movies", "netflix"))
    )

    val resolved = resolver.resolve(CatalogKey("addon.disney", TYPE_MOVIE, "streaming_disney_movies"))

    assertEquals(BindingSource.USER, resolved?.source)
    assertEquals("disney", resolved?.providerId)

    val movies = plan(resolver, "disney", MediaType.FILM)
    assertEquals(BindingSource.USER, movies.resolved.single().binding.source)
    assertEquals("disney", movies.resolved.single().binding.providerId)
  }

  @Test
  fun registryNonDichiaraIlBindingUtenteDiUnAltroProvider() {
    // Con USER su disney e REGISTRY su netflix, il piano di netflix resta vuoto:
    // la precedenza non puo' "spostare" un binding verso un altro provider.
    val resolver = resolverFromPersistedUserBindings(
      userBindings = listOf(userBinding("addon.disney", TYPE_MOVIE, "streaming_disney_movies", "disney")),
      registry = listOf(registryBinding("addon.disney", TYPE_MOVIE, "streaming_disney_movies", "netflix"))
    )

    assertFalse(plan(resolver, "netflix", MediaType.FILM).hasConfirmedBindings)
  }

  @Test
  fun rimozioneDelBindingUtenteRiportaIlFallbackTmdb() {
    val confirmed = listOf(userBinding("addon.disney", TYPE_MOVIE, "streaming_disney_movies", "disney"))
    val removed = ProviderBindingConfirmation.remove(
      confirmed,
      CatalogKey("addon.disney", TYPE_MOVIE, "streaming_disney_movies"),
      "disney"
    )

    val resolver = resolverFromPersistedUserBindings(removed.bindings)

    assertFalse(plan(resolver, "disney", MediaType.FILM).hasConfirmedBindings)
    assertNull(
      resolver.resolve(CatalogKey("addon.disney", TYPE_MOVIE, "streaming_disney_movies"))
    )
  }

  @Test
  fun rimozioneDelBindingUtenteRiportaInGiocoIlBindingDiRegistry() {
    val confirmed = listOf(userBinding("addon.disney", TYPE_MOVIE, "streaming_disney_movies", "disney"))
    val removed = ProviderBindingConfirmation.remove(
      confirmed,
      CatalogKey("addon.disney", TYPE_MOVIE, "streaming_disney_movies"),
      "disney"
    ).bindings
    val resolver = resolverFromPersistedUserBindings(
      userBindings = removed,
      registry = listOf(registryBinding("addon.disney", TYPE_MOVIE, "streaming_disney_movies", "disney"))
    )

    val movies = plan(resolver, "disney", MediaType.FILM)

    assertTrue(movies.hasConfirmedBindings)
    assertEquals(BindingSource.REGISTRY, movies.resolved.single().binding.source)
  }

  // ── Le euristiche non entrano mai nel percorso di attivazione ─────────────────

  @Test
  fun leProposteEuristicheNonAttivanoIlProvider() {
    // Gli alias del provider nel catalogId fanno generare proposte, ma nessuna
    // diventa binding: senza una conferma esplicita il fallback resta TMDB.
    val resolver = resolverFromPersistedUserBindings(emptyList())

    val proposals = resolver.propose(
      StremioManifest(
        id = "addon.disney",
        name = "The Walt Disney Company",
        catalogs = listOf(
          StremioCatalogDefinition(type = TYPE_MOVIE, id = "streaming_disney_movies", name = "Disney+ Movies"),
          StremioCatalogDefinition(type = TYPE_SERIES, id = "streaming_disney_series", name = "Disney+ Series")
        )
      )
    )

    assertTrue("Le proposte ci sono", proposals.isNotEmpty())
    assertFalse(plan(resolver, "disney", MediaType.FILM).hasConfirmedBindings)
    assertFalse(plan(resolver, "disney", MediaType.SERIE_TV).hasConfirmedBindings)
  }
}
