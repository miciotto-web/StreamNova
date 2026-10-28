package com.example.data.stremio.provider

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.stremio.StremioCatalogDefinition
import com.example.data.stremio.StremioCatalogTarget
import com.example.data.stremio.StremioManifest
import com.example.data.stremio.StremioMetaItem
import com.example.data.stremio.TYPE_MOVIE
import com.example.data.stremio.TYPE_SERIES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test di integrazione fra [ProviderCatalogResolver] e il catalogo Stremio.
 *
 * Nessuna rete, nessun Android, nessun Compose: il piano di caricamento, la
 * separazione Film/Serie, l'isolamento tra addon, la provenienza e la paginazione
 * per catalogo sono verificati come funzioni pure.
 *
 * Invarianti verificati:
 * - solo binding USER/REGISTRY alimentano un piano (mai le proposte euristiche);
 * - `movie` e `series` non si contaminano mai;
 * - due addon con lo stesso `catalogId` restano entrambi utilizzabili e distinti;
 * - l'assenza di binding produce un piano vuoto, cioè il fallback TMDB;
 * - ogni catalingo mantiene il proprio offset `skip`.
 */
class ProviderCatalogIntegrationTest {

  // ── Fixture ──────────────────────────────────────────────────────────────────

  private fun catalog(
    type: String,
    id: String,
    name: String? = null,
    extraSupported: List<String>? = listOf("skip"),
    extraRequired: List<String>? = null,
    extra: List<Map<String, Any?>>? = null
  ) = StremioCatalogDefinition(
    type = type,
    id = id,
    name = name,
    extraSupported = extraSupported,
    extraRequired = extraRequired,
    extra = extra
  )

  private fun target(
    addonId: String,
    type: String,
    catalogId: String,
    extraSupported: List<String>? = listOf("skip"),
    extraRequired: List<String>? = null
  ) = StremioCatalogTarget(
    addonId = addonId,
    addonName = "Addon $addonId",
    baseUrl = "https://example.test/$addonId",
    catalog = StremioCatalogDefinition(
      type = type,
      id = catalogId,
      name = catalogId,
      extraSupported = extraSupported,
      extraRequired = extraRequired
    )
  )

  private data class TestProvider(
    override val providerId: String,
    override val providerName: String,
    override val providerAliases: Set<String>
  ) : ProviderAliasProvider

  private val testProviders = listOf(
    TestProvider("disney", "Disney+", setOf("disney", "disney+")),
    TestProvider("netflix", "Netflix", setOf("netflix"))
  )

  private fun binding(addonId: String, type: String, catalogId: String, providerId: String, source: BindingSource) =
    ProviderBinding(
      key = CatalogKey(addonId, type, catalogId),
      providerId = providerId,
      source = source
    )

  private fun resolverOf(
    user: List<ProviderBinding> = emptyList(),
    registry: List<ProviderBinding> = emptyList(),
    providers: List<ProviderAliasProvider> = testProviders
  ) =
    DefaultProviderCatalogResolver(
      userBindings = InMemoryProviderBindingSource(user),
      registry = InMemoryProviderBindingSource(registry),
      proposer = HeuristicProviderCatalogProposer(),
      providers = { providers }
    )

  private fun meta(id: String, name: String, isSeries: Boolean = false) = StremioMetaItem(
    id = id,
    type = if (isSeries) TYPE_SERIES else TYPE_MOVIE,
    name = name
  )

  // ── 1. Separazione movie / series ────────────────────────────────────────────

  @Test
  fun providerMovieCaricaSoloCataloghiMovie() {
    val targets = listOf(
      target("addon.a", TYPE_MOVIE, "brand_movies"),
      target("addon.a", TYPE_SERIES, "brand_series"),
      target("addon.a", TYPE_MOVIE, "other_movies")
    )
    val r = resolverOf(
      registry = listOf(
        binding("addon.a", TYPE_MOVIE, "brand_movies", "disney", BindingSource.REGISTRY),
        binding("addon.a", TYPE_SERIES, "brand_series", "disney", BindingSource.REGISTRY),
        binding("addon.a", TYPE_MOVIE, "other_movies", "netflix", BindingSource.REGISTRY)
      )
    )

    val plan = ProviderCatalogPlanner.plan("disney", targets, r, MediaType.FILM)

    assertEquals(1, plan.resolved.size)
    assertEquals("brand_movies", plan.resolved.first().target.catalogId)
    assertEquals(MediaType.FILM, plan.resolved.first().mediaType)
    assertTrue(plan.resolved.none { it.type == TYPE_SERIES })
  }

  @Test
  fun providerSeriesCaricaSoloCataloghiSeries() {
    val targets = listOf(
      target("addon.a", TYPE_MOVIE, "brand_movies"),
      target("addon.a", TYPE_SERIES, "brand_series"),
      target("addon.a", TYPE_SERIES, "brand_originals_series")
    )
    val r = resolverOf(
      registry = listOf(
        binding("addon.a", TYPE_MOVIE, "brand_movies", "disney", BindingSource.REGISTRY),
        binding("addon.a", TYPE_SERIES, "brand_series", "disney", BindingSource.REGISTRY),
        binding("addon.a", TYPE_SERIES, "brand_originals_series", "disney", BindingSource.REGISTRY)
      )
    )

    val plan = ProviderCatalogPlanner.plan("disney", targets, r, MediaType.SERIE_TV)

    assertEquals(2, plan.resolved.size)
    assertTrue(plan.resolved.all { it.mediaType == MediaType.SERIE_TV })
    assertEquals(
      setOf("brand_series", "brand_originals_series"),
      plan.resolved.map { it.target.catalogId }.toSet()
    )
  }

  @Test
  fun bindingMovieNonValutaPerLeSerie() {
    val targets = listOf(target("addon.a", TYPE_SERIES, "shared_id"))
    val r = resolverOf(
      user = listOf(binding("addon.a", TYPE_MOVIE, "shared_id", "disney", BindingSource.USER))
    )

    val planSeries = ProviderCatalogPlanner.plan("disney", targets, r, MediaType.SERIE_TV)
    assertTrue("Il binding movie non può valere per le serie", !planSeries.hasConfirmedBindings)

    val planMovies = ProviderCatalogPlanner.plan(
      "disney",
      listOf(target("addon.a", TYPE_MOVIE, "shared_id")),
      r,
      MediaType.FILM
    )
    assertTrue(planMovies.hasConfirmedBindings)
  }

  @Test
  fun pianoSenzaTipoIncludoEntrambiMaRestanoDistinti() {
    val targets = listOf(
      target("addon.a", TYPE_MOVIE, "brand_movies"),
      target("addon.a", TYPE_SERIES, "brand_series")
    )
    val r = resolverOf(
      registry = listOf(
        binding("addon.a", TYPE_MOVIE, "brand_movies", "disney", BindingSource.REGISTRY),
        binding("addon.a", TYPE_SERIES, "brand_series", "disney", BindingSource.REGISTRY)
      )
    )

    val plan = ProviderCatalogPlanner.plan("disney", targets, r, null)

    assertEquals(2, plan.resolved.size)
    assertEquals(1, plan.movieTargets.size)
    assertEquals(1, plan.seriesTargets.size)
  }

  // ── 2. Multi-addon ───────────────────────────────────────────────────────────

  @Test
  fun dueAddonConStessoCatalogIdSonoEntrambiUtilizzabili() {
    val targets = listOf(
      target("addon.a", TYPE_MOVIE, "shared_movies"),
      target("addon.b", TYPE_MOVIE, "shared_movies")
    )
    val r = resolverOf(
      user = listOf(binding("addon.a", TYPE_MOVIE, "shared_movies", "disney", BindingSource.USER)),
      registry = listOf(binding("addon.b", TYPE_MOVIE, "shared_movies", "disney", BindingSource.REGISTRY))
    )

    val plan = ProviderCatalogPlanner.plan("disney", targets, r, MediaType.FILM)

    assertEquals("Entrambi gli addon devono essere utilizzabili", 2, plan.resolved.size)
    assertEquals(setOf("addon.a", "addon.b"), plan.resolved.map { it.target.addonId }.toSet())
    assertEquals(2, plan.resolved.map { it.targetId }.toSet().size)
  }

  @Test
  fun bindingDiUnSoloAddonNonAttivaLAltro() {
    val targets = listOf(
      target("addon.a", TYPE_MOVIE, "shared_movies"),
      target("addon.b", TYPE_MOVIE, "shared_movies")
    )
    val r = resolverOf(user = listOf(binding("addon.a", TYPE_MOVIE, "shared_movies", "disney", BindingSource.USER)))

    val plan = ProviderCatalogPlanner.plan("disney", targets, r, MediaType.FILM)

    assertEquals(1, plan.resolved.size)
    assertEquals("addon.a", plan.resolved.first().target.addonId)
    assertEquals(1, plan.unmappedTargets)
  }

  @Test
  fun provenienzaConservaEntrambiGliAddonSenzaDuplicare() {
    val targets = listOf(
      target("addon.a", TYPE_MOVIE, "shared_movies"),
      target("addon.b", TYPE_MOVIE, "shared_movies")
    )
    val r = resolverOf(
      user = listOf(binding("addon.a", TYPE_MOVIE, "shared_movies", "disney", BindingSource.USER)),
      registry = listOf(binding("addon.b", TYPE_MOVIE, "shared_movies", "disney", BindingSource.REGISTRY))
    )
    val plan = ProviderCatalogPlanner.plan("disney", targets, r, MediaType.FILM)

    val assembled = ProviderCatalogAssembler.assemble(
      "Disney+",
      listOf(
        CatalogEntryResult(plan.resolved[0], listOf(meta("tt1", "Interstellar"), meta("tt2", "Inception"))),
        CatalogEntryResult(plan.resolved[1], listOf(meta("tt1", "Interstellar"), meta("tt3", "Dune")))
      )
    )

    assertEquals("Nessun poster duplicato", 3, assembled.items.size)
    val origins = assembled.provenance["tt1"].orEmpty()
    assertEquals("Il titolo deve mantenere entrambe le origini", 2, origins.size)
    assertEquals(setOf("addon.a", "addon.b"), origins.map { it.addonManifestId }.toSet())
    assertEquals(
      setOf("shared_movies"),
      origins.map { it.catalogId }.toSet()
    )
  }

  // ── 3. Più cataloghi dello stesso provider ───────────────────────────────────

  @Test
  fun providerUsaTuttiICataloghiConfermati() {
    val targets = listOf(
      target("addon.a", TYPE_MOVIE, "brand_movies"),
      target("addon.a", TYPE_MOVIE, "brand_top10_movies"),
      target("addon.a", TYPE_SERIES, "brand_series")
    )
    val r = resolverOf(
      registry = targets.map {
        binding("addon.a", it.type, it.catalogId, "disney", BindingSource.REGISTRY)
      }
    )

    val movies = ProviderCatalogPlanner.plan("disney", targets, r, MediaType.FILM)
    val series = ProviderCatalogPlanner.plan("disney", targets, r, MediaType.SERIE_TV)

    assertEquals(2, movies.resolved.size)
    assertEquals(1, series.resolved.size)
  }

  @Test
  fun ordinamentoDelPianoEStabile() {
    val targets = listOf(
      target("addon.b", TYPE_MOVIE, "zeta"),
      target("addon.a", TYPE_MOVIE, "alpha"),
      target("addon.a", TYPE_MOVIE, "beta")
    )
    val r = resolverOf(registry = targets.map { binding(it.addonId, it.type, it.catalogId, "disney", BindingSource.REGISTRY) })

    val first = ProviderCatalogPlanner.plan("disney", targets, r, MediaType.FILM).resolved.map { it.targetId }
    val second = ProviderCatalogPlanner.plan("disney", targets.shuffled(), r, MediaType.FILM).resolved.map { it.targetId }

    assertEquals(first, second)
    assertEquals(listOf("addon.a", "addon.a", "addon.b"), first.map { it.substringBefore(':') })
  }

  // ── 4. Binding USER e REGISTRY ───────────────────────────────────────────────

  @Test
  fun bindingUserAlimentaIlPiano() {
    val targets = listOf(target("addon.a", TYPE_MOVIE, "brand_movies"))
    val r = resolverOf(user = listOf(binding("addon.a", TYPE_MOVIE, "brand_movies", "disney", BindingSource.USER)))

    val plan = ProviderCatalogPlanner.plan("disney", targets, r, MediaType.FILM)

    assertTrue(plan.hasConfirmedBindings)
    assertEquals(BindingSource.USER, plan.resolved.first().binding.source)
  }

  @Test
  fun bindingRegistryAlimentaIlPiano() {
    val targets = listOf(target("addon.a", TYPE_MOVIE, "brand_movies"))
    val r = resolverOf(registry = listOf(binding("addon.a", TYPE_MOVIE, "brand_movies", "disney", BindingSource.REGISTRY)))

    val plan = ProviderCatalogPlanner.plan("disney", targets, r, MediaType.FILM)

    assertTrue(plan.hasConfirmedBindings)
    assertEquals(BindingSource.REGISTRY, plan.resolved.first().binding.source)
  }

  @Test
  fun userHaPrecedenzaSuRegistryNelPiano() {
    val targets = listOf(target("addon.a", TYPE_MOVIE, "brand_movies"))
    val r = resolverOf(
      user = listOf(binding("addon.a", TYPE_MOVIE, "brand_movies", "disney", BindingSource.USER)),
      registry = listOf(binding("addon.a", TYPE_MOVIE, "brand_movies", "netflix", BindingSource.REGISTRY))
    )

    val plan = ProviderCatalogPlanner.plan("disney", targets, r, MediaType.FILM)

    assertEquals("disney", plan.resolved.first().binding.providerId)
    assertEquals(BindingSource.USER, plan.resolved.first().binding.source)
  }

  @Test
  fun bindingDiUnAltroProviderVieneScartato() {
    val targets = listOf(target("addon.a", TYPE_MOVIE, "brand_movies"))
    val r = resolverOf(registry = listOf(binding("addon.a", TYPE_MOVIE, "brand_movies", "netflix", BindingSource.REGISTRY)))

    val plan = ProviderCatalogPlanner.plan("disney", targets, r, MediaType.FILM)

    assertFalse(plan.hasConfirmedBindings)
    assertTrue(plan.resolved.isEmpty())
  }

  // ── 5. Nessun binding → fallback TMDB ────────────────────────────────────────

  @Test
  fun nessunBindingProducePianoVuotoERimuoveIlFallback() {
    val targets = listOf(target("addon.a", TYPE_MOVIE, "brand_movies"))
    val plan = ProviderCatalogPlanner.plan("disney", targets, resolverOf(), MediaType.FILM)

    assertFalse("Senza binding il piano è vuoto: subentra il catalogo TMDB", plan.hasConfirmedBindings)
    assertTrue(plan.resolved.isEmpty())
    assertEquals(1, plan.unmappedTargets)
  }

  @Test
  fun nessunCatalogoStremioProducePianoVuoto() {
    val plan = ProviderCatalogPlanner.plan("disney", emptyList(), resolverOf(), MediaType.FILM)

    assertFalse(plan.hasConfirmedBindings)
    assertTrue(plan.resolved.isEmpty())
    assertEquals(0, plan.unmappedTargets)
  }

  @Test
  fun assemblatoreSuRisultatiVuotiNonProduceTitoli() {
    val assembled = ProviderCatalogAssembler.assemble("Disney+", emptyList())

    assertTrue(assembled.items.isEmpty())
    assertTrue(assembled.provenance.isEmpty())
  }

  // ── 6. Le proposte euristiche non alimentano mai un provider ────────────────

  @Test
  fun proposteEuristicheNonVengonoUsatePerCaricareIlProvider() {
    val manifest = StremioManifest(
      id = "addon.a",
      name = "My Addon",
      catalogs = listOf(
        catalog(TYPE_MOVIE, "streaming_disney_movies", "Disney+"),
        catalog(TYPE_SERIES, "streaming_disney_series", "Disney+")
      )
    )
    // Il resolver ha proposte ma NESSUN binding: il piano deve restare vuoto.
    val r = resolverOf()
    assertTrue(r.propose(manifest).isNotEmpty())

    val plan = ProviderCatalogPlanner.plan("disney", manifest.validCatalogs().map { targetOf("addon.a", it) }, r, MediaType.FILM)

    assertFalse("Le proposte non possono attivare un provider", plan.hasConfirmedBindings)
    assertTrue(plan.resolved.isEmpty())
  }

  private fun targetOf(addonId: String, definition: StremioCatalogDefinition) = StremioCatalogTarget(
    addonId = addonId,
    addonName = "Addon $addonId",
    baseUrl = "https://example.test/$addonId",
    catalog = definition
  )

  // ── 7. Paginazione indipendente per catalogo ─────────────────────────────────

  @Test
  fun ogniCatalogoMantieneIlProprioOffset() {
    val paging = ProviderCatalogPaging()
    val catalogA = "addon.a:movie:a_movies"
    val catalogB = "addon.a:movie:b_movies"

    assertEquals(0, paging.nextOffset(catalogA))
    assertEquals(0, paging.nextOffset(catalogB))

    paging.advance(catalogA, 100)
    assertEquals("A avanza da solo", 100, paging.nextOffset(catalogA))
    assertEquals("B non deve essere stato toccato", 0, paging.nextOffset(catalogB))

    paging.advance(catalogB, 50)
    assertEquals(100, paging.nextOffset(catalogA))
    assertEquals(50, paging.nextOffset(catalogB))
  }

  @Test
  fun catalogoCheNonRestituisceNullaNonAvanza() {
    val paging = ProviderCatalogPaging()

    paging.advance("x", 0)
    paging.advance("x", -5)
    assertEquals(0, paging.nextOffset("x"))

    paging.advance("x", 20)
    paging.advance("x", 0)
    assertEquals(20, paging.nextOffset("x"))
  }

  @Test
  fun resetAzzeraTuttiGliOffset() {
    val paging = ProviderCatalogPaging()
    paging.advance("a", 100)
    paging.advance("b", 100)
    assertFalse(paging.isAtFirstPage())

    paging.reset()

    assertTrue(paging.isAtFirstPage())
    assertEquals(0, paging.nextOffset("a"))
    assertEquals(0, paging.nextOffset("b"))
    assertTrue(paging.snapshot().isEmpty())
    assertEquals(0, paging.maxOffset())
  }

  @Test
  fun paginazioneSeparataPerTipoNonConfondeGliOffset() {
    val paging = ProviderCatalogPaging()
    val movieTarget = "addon.a:movie:brand_movies"
    val seriesTarget = "addon.a:series:brand_series"

    paging.advance(movieTarget, 100)
    paging.advance(seriesTarget, 25)

    assertEquals(100, paging.nextOffset(movieTarget))
    assertEquals(25, paging.nextOffset(seriesTarget))
    assertEquals(100, paging.maxOffset())
  }

  @Test
  fun soloICataloghiCheSupportanoSkipVengonoPaginati() {
    val conSkip = target("addon.a", TYPE_MOVIE, "brand_movies", extraSupported = listOf("skip"))
    val senzaSkip = target("addon.a", TYPE_MOVIE, "brand_no_skip", extraSupported = listOf("genre"))
    val r = resolverOf(
      registry = listOf(
        binding("addon.a", TYPE_MOVIE, "brand_movies", "disney", BindingSource.REGISTRY),
        binding("addon.a", TYPE_MOVIE, "brand_no_skip", "disney", BindingSource.REGISTRY)
      )
    )

    val plan = ProviderCatalogPlanner.plan("disney", listOf(conSkip, senzaSkip), r, MediaType.FILM)

    assertEquals(2, plan.resolved.size)
    assertEquals(1, plan.resolved.count { it.supportsSkip })
    assertEquals("brand_movies", plan.resolved.first { it.supportsSkip }.target.catalogId)
  }

  // ── 8. Cataloghi non interrogabili e parametri obbligatori ───────────────────

  @Test
  fun catalogoCheRichiedeSearchEsclusoDalPiano() {
    val searchOnly = target(
      "addon.a", TYPE_MOVIE, "brand_search",
      extraSupported = listOf("search", "skip"),
      extraRequired = listOf("search")
    )
    val r = resolverOf(registry = listOf(binding("addon.a", TYPE_MOVIE, "brand_search", "disney", BindingSource.REGISTRY)))

    val plan = ProviderCatalogPlanner.plan("disney", listOf(searchOnly), r, MediaType.FILM)

    assertFalse(plan.hasConfirmedBindings)
  }

  @Test
  fun catalogoConGenreObbligatorioUsaLaPrimaOpzioneDichiarata() {
    val withGenre = StremioCatalogTarget(
      addonId = "addon.a",
      addonName = "Addon",
      baseUrl = "https://example.test/a",
      catalog = StremioCatalogDefinition(
        type = TYPE_MOVIE,
        id = "brand_all_movies",
        name = "Movies",
        extra = listOf(
          mapOf(
            "name" to "genre",
            "isRequired" to true,
            "options" to listOf("None", "Action", "Comedy")
          )
        )
      )
    )
    val r = resolverOf(registry = listOf(binding("addon.a", TYPE_MOVIE, "brand_all_movies", "disney", BindingSource.REGISTRY)))

    val plan = ProviderCatalogPlanner.plan("disney", listOf(withGenre), r, MediaType.FILM)
    val extra = ProviderCatalogPlanner.extraFor(plan.resolved.first())

    assertEquals(mapOf("genre" to "None"), extra)
  }

  @Test
  fun catalogoSenzaObblighiNonRiceveParametriExtra() {
    val plain = target("addon.a", TYPE_MOVIE, "brand_movies")
    val r = resolverOf(registry = listOf(binding("addon.a", TYPE_MOVIE, "brand_movies", "disney", BindingSource.REGISTRY)))

    val plan = ProviderCatalogPlanner.plan("disney", listOf(plain), r, MediaType.FILM)

    assertTrue(ProviderCatalogPlanner.extraFor(plan.resolved.first()).isEmpty())
  }

  // ── 9. Deduplica ────────────────────────────────────────────────────────────

  @Test
  fun deduplicaPerIdMantenendoLOrdineDiArrivo() {
    val items = listOf(
      MediaItem(id = "tt1", title = "A", synopsis = "", videoUrl = "", year = 2020),
      MediaItem(id = "tt2", title = "B", synopsis = "", videoUrl = "", year = 2021),
      MediaItem(id = "tt1", title = "A duplicato", synopsis = "", videoUrl = "", year = 2020)
    )

    val deduped = ProviderCatalogAssembler.deduplicate(items)

    assertEquals(listOf("tt1", "tt2"), deduped.map { it.id })
    assertEquals("A", deduped.first().title)
  }

  @Test
  fun provenienzaCopreOgniTitoloMantenuto() {
    val targetA = target("addon.a", TYPE_MOVIE, "brand_movies")
    val targetB = target("addon.b", TYPE_MOVIE, "brand_movies")
    val r = resolverOf(
      registry = listOf(
        binding("addon.a", TYPE_MOVIE, "brand_movies", "disney", BindingSource.REGISTRY),
        binding("addon.b", TYPE_MOVIE, "brand_movies", "disney", BindingSource.REGISTRY)
      )
    )
    val plan = ProviderCatalogPlanner.plan("disney", listOf(targetA, targetB), r, MediaType.FILM)

    val assembled = ProviderCatalogAssembler.assemble(
      "Disney+",
      listOf(
        CatalogEntryResult(plan.resolved[0], listOf(meta("tt1", "A"), meta("tt1", "A duplicato"))),
        CatalogEntryResult(plan.resolved[1], listOf(meta("tt2", "B")))
      )
    )

    assertNotNull(assembled.provenance["tt1"])
    assertEquals(1, assembled.provenance["tt1"]!!.size)
    assertEquals("addon.a", assembled.provenance["tt1"]!!.first().addonManifestId)
    assertEquals(TYPE_MOVIE, assembled.provenance["tt2"]!!.first().type)
    assertNull("Nessuna voce per un id assente", assembled.provenance["tt9"])
  }
}