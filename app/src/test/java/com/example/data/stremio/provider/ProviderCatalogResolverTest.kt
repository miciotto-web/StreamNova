package com.example.data.stremio.provider

import com.example.data.stremio.StremioCatalogDefinition
import com.example.data.stremio.StremioManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test del layer di associazione catalogo Stremio → provider StreamNova.
 *
 * Nessuna rete, nessun Android, nessun Compose: il resolver deve essere verificabile
 * in JVM puro. L'ordine di precedenza (USER → REGISTRY → null), l'isolamento tra addon
 * e tra tipi, e il fatto che l'euristica produca solo proposte sono gli invarianti
 * verificati qui.
 */
class ProviderCatalogResolverTest {

  // ── Test double: provider come pura vista dati, senza Compose ────────────────

  private data class TestProvider(
    override val providerId: String,
    override val providerName: String,
    override val providerAliases: Set<String>
  ) : ProviderAliasProvider

  private val disney = TestProvider("disney", "Disney+", setOf("disney", "disney plus"))
  private val hbo = TestProvider("hbo", "HBO Max", setOf("hbo", "hbo max", "max"))
  private val apple = TestProvider("apple", "Apple TV+", setOf("apple", "apple tv"))
  private val netflix = TestProvider("netflix", "Netflix", setOf("netflix"))
  private val allProviders = listOf(disney, hbo, apple, netflix)

  private val proposer = HeuristicProviderCatalogProposer()

  private fun catalog(
    type: String,
    id: String,
    name: String? = null,
    extraSupported: List<String>? = null,
    extraRequired: List<String>? = null
  ) = StremioCatalogDefinition(
    type = type,
    id = id,
    name = name,
    extraSupported = extraSupported,
    extraRequired = extraRequired
  )

  private fun manifestOf(addonId: String, addonName: String? = null, vararg catalogs: StremioCatalogDefinition) =
    StremioManifest(id = addonId, name = addonName, catalogs = catalogs.toList())

  private fun binding(
    addonId: String,
    type: String,
    catalogId: String,
    providerId: String,
    source: BindingSource
  ) = ProviderBinding(
    key = CatalogKey(addonId, type, catalogId),
    providerId = providerId,
    source = source
  )

  private fun resolver(
    user: List<ProviderBinding> = emptyList(),
    registry: List<ProviderBinding> = emptyList(),
    providers: List<ProviderAliasProvider> = allProviders
  ) = DefaultProviderCatalogResolver(
    userBindings = InMemoryProviderBindingSource(user),
    registry = InMemoryProviderBindingSource(registry),
    proposer = proposer,
    providers = { providers }
  )

  // ── 1. CatalogKey: equality e normalizzazione ────────────────────────────────

  @Test
  fun catalogKeyStessiValoriSonoUguali() {
    val a = CatalogKey("addon.a", "movie", "disney_movies")
    val b = CatalogKey("addon.a", "movie", "disney_movies")

    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
    assertEquals("addon.a|movie|disney_movies", a.stableId)
  }

  @Test
  fun catalogKeyDistingueAddonTypeECatalogo() {
    val base = CatalogKey("addon.a", "movie", "disney_movies")

    assertTrue("addon diverso → chiave diversa", base != CatalogKey("addon.b", "movie", "disney_movies"))
    assertTrue("tipo diverso → chiave diversa", base != CatalogKey("addon.a", "series", "disney_movies"))
    assertTrue("catalogo diverso → chiave diversa", base != CatalogKey("addon.a", "movie", "disney_series"))
  }

  @Test
  fun catalogKeyNormalizzaTrimEMaiuscolePerIlLookup() {
    val messy = CatalogKey("  Addon.A  ", " MOVIE ", " Disney_Movies ")

    assertEquals("addon.a", messy.normalizedAddonId)
    assertEquals("movie", messy.stremioType)
    assertEquals("disney_movies", messy.normalizedCatalogId)
    assertEquals("addon.a|movie|disney_movies", messy.stableId)
    assertTrue(messy.isMovie)
    assertTrue(!messy.isSeries)
  }

  @Test
  fun catalogKeyOfRifiutaCampiMancantiOVuoti() {
    assertNull(CatalogKey.of(null, "movie", "x"))
    assertNull(CatalogKey.of("addon.a", "  ", "x"))
    assertNull(CatalogKey.of("addon.a", "movie", null))
    assertNotNull(CatalogKey.of("addon.a", " movie ", " disney_movies "))
  }

  // ── 2. Precedenza USER → REGISTRY → null ─────────────────────────────────────

  @Test
  fun userHaPrecedenzaSuRegistry() {
    val key = CatalogKey("addon.a", "movie", "disney_movies")
    val r = resolver(
      user = listOf(binding("addon.a", "movie", "disney_movies", "disney", BindingSource.USER)),
      registry = listOf(binding("addon.a", "movie", "disney_movies", "netflix", BindingSource.REGISTRY))
    )

    val resolved = r.resolve(key)
    assertNotNull(resolved)
    assertEquals("disney", resolved!!.providerId)
    assertEquals(BindingSource.USER, resolved.source)
  }

  @Test
  fun registryHaPrecedenzaQuandoNonEsisteBindingUtente() {
    val key = CatalogKey("addon.a", "movie", "disney_movies")
    val r = resolver(
      registry = listOf(binding("addon.a", "movie", "disney_movies", "disney", BindingSource.REGISTRY))
    )

    val resolved = r.resolve(key)
    assertNotNull(resolved)
    assertEquals(BindingSource.REGISTRY, resolved!!.source)
    assertEquals("disney", resolved.providerId)
  }

  @Test
  fun catalogoNonMappatoRitornaNull() {
    val r = resolver(
      user = listOf(binding("addon.a", "movie", "disney_movies", "disney", BindingSource.USER))
    )

    assertNull(r.resolve(CatalogKey("addon.a", "movie", "top_100_movies")))
    assertNull(r.resolve(CatalogKey("addon.b", "movie", "disney_movies")))
    assertNull(r.resolve(CatalogKey("addon.a", "series", "disney_movies")))
  }

  @Test
  fun applicableBindingsPreferisceUserAGiustaChiave() {
    val r = resolver(
      user = listOf(binding("addon.a", "movie", "disney_movies", "paramount", BindingSource.USER)),
      registry = listOf(
        binding("addon.a", "movie", "disney_movies", "netflix", BindingSource.REGISTRY),
        binding("addon.a", "series", "disney_series", "netflix", BindingSource.REGISTRY)
      )
    )

    val all = r.applicableBindings().associateBy { it.key.stableId }
    assertEquals("paramount", all["addon.a|movie|disney_movies"]?.providerId)
    assertEquals("netflix", all["addon.a|series|disney_series"]?.providerId)
    assertEquals(2, all.size)
    assertTrue(r.hasBindingsFor("paramount"))
    assertTrue(r.hasBindingsFor("NETFLIX "))
  }

  // ── 3. Isolamento tra addon (stesso catalogId) ────────────────────────────────

  @Test
  fun dueAddonConStessoCatalogIdRestanoDistinti() {
    val addonA = CatalogKey("addon.a", "movie", "top_movies")
    val addonB = CatalogKey("addon.b", "movie", "top_movies")
    val r = resolver(
      user = listOf(binding("addon.a", "movie", "top_movies", "disney", BindingSource.USER)),
      registry = listOf(binding("addon.b", "movie", "top_movies", "netflix", BindingSource.REGISTRY))
    )

    assertEquals("disney", r.resolve(addonA)?.providerId)
    assertEquals("netflix", r.resolve(addonB)?.providerId)
    assertTrue("Le due chiavi non devono coincidere", addonA != addonB)
    assertTrue(addonA.stableId != addonB.stableId)
  }

  // ── 4. Isolamento movie / series ─────────────────────────────────────────────

  @Test
  fun bindingMovieNonValevaAutomaticamentePerSeries() {
    val r = resolver(
      user = listOf(binding("addon.a", "movie", "disney_movies", "disney", BindingSource.USER)),
      registry = listOf(binding("addon.a", "series", "disney_movies", "netflix", BindingSource.REGISTRY))
    )

    assertEquals("disney", r.resolve(CatalogKey("addon.a", "movie", "disney_movies"))?.providerId)
    assertEquals("netflix", r.resolve(CatalogKey("addon.a", "series", "disney_movies"))?.providerId)
  }

  @Test
  fun tipoDiversoProduceStabileIdDiverso() {
    assertTrue(
      CatalogKey("addon.a", "movie", "x").stableId != CatalogKey("addon.a", "series", "x").stableId
    )
  }

  // ── 5. Le proposte euristiche non vengono mai applicate ──────────────────────

  @Test
  fun proposteEuristicheNonVengonoApplicateAutomaticamente() {
    val manifest = manifestOf(
      "addon.a",
      "My Addon",
      catalog("movie", "streaming_disney_movies", "Disney+"),
      catalog("series", "streaming_disney_series", "Disney+")
    )
    val r = resolver() // nessun binding dichiarato

    val proposals = r.propose(manifest)
    assertTrue("Il proposer deve produrre proposte", proposals.isNotEmpty())

    // Le proposte non sono binding: resolve resta null su tutte le chiavi proposte.
    proposals.forEach { assertNull("Le proposte non devono essere applicate", r.resolve(it.key)) }
    assertTrue(r.applicableBindings().isEmpty())
  }

  @Test
  fun proposerNonProduceBindingDiNessunTipo() {
    val manifest = manifestOf("addon.a", null, catalog("movie", "streaming_disney_movies", "Disney+"))
    val proposals = proposer.propose(manifest, allProviders)

    assertTrue(proposals.isNotEmpty())
    proposals.forEach { proposal ->
      // Le proposte non sono binding: non hanno source né confidence,
      // quindi non possono finire in nessuna ProviderBindingSource.
      assertTrue(proposal.key.addonManifestId.isNotBlank())
      assertTrue(proposal.key.type.isNotBlank())
      assertTrue(proposal.key.catalogId.isNotBlank())
      assertTrue(proposal.score in 0.0..1.0)
    }
  }

  @Test
  fun proposteNonGenerateDalSoloNomeAddon() {
    // Solo il nome dell'addon contiene un alias: nessuna corrispondenza strutturale,
    // quindi nessuna proposta (il segnale da solo resta sotto soglia).
    val manifest = manifestOf("addon.a", "Max Cinema", catalog("movie", "xyz_001", null))
    val proposals = proposer.propose(manifest, allProviders)

    assertTrue("Il solo nome addon non deve generare proposte", proposals.isEmpty())
  }

  // ── 6. Alias del provider e confine di token ─────────────────────────────────

  @Test
  fun proposteRiconosconoLAliasDelProviderNelCatalogId() {
    val manifest = manifestOf("addon.a", null, catalog("movie", "streaming_disney_movies", "Disney+"))
    val proposals = proposer.propose(manifest, listOf(disney))

    assertEquals(1, proposals.size)
    assertEquals("disney", proposals.first().providerId)
    assertTrue(proposals.first().score >= HeuristicProviderCatalogProposer.MIN_SCORE)
  }

  @Test
  fun proposteNonMatchanoPerSottostringa() {
    val manifest = manifestOf(
      "addon.a",
      null,
      catalog("movie", "pineapple_movies", null),
      catalog("movie", "springfield_movies", null),
      catalog("series", "maxwell_series", null)
    )

    val proposals = proposer.propose(manifest, listOf(apple, disney, hbo))
    assertTrue("'pineapple' non è 'apple', 'springfield' non è 'disney', 'maxwell' non è 'max'", proposals.isEmpty())
  }

  @Test
  fun aliasMultiParolaRichiedeTuttiIToken() {
    val manifest = manifestOf("addon.a", null, catalog("movie", "hbo_max_top10_movies", null))
    val proposals = proposer.propose(manifest, listOf(hbo))

    assertEquals(1, proposals.size)
    assertEquals("hbo", proposals.first().providerId)
  }

  @Test
  fun aliasTrovatoNelTitoloDelCatalogo() {
    val manifest = manifestOf("addon.a", null, catalog("movie", "xq_7781", "Prime Video"))
    val proposals = proposer.propose(
      manifest,
      listOf(TestProvider("prime", "Prime Video", setOf("prime video")))
    )

    assertEquals(1, proposals.size)
    assertEquals("prime", proposals.first().providerId)
    assertTrue(proposals.first().reasons.any { it.contains("titolo") })
  }

  @Test
  fun coperturaMovieSeriesAggiungeIlBonusDiGruppo() {
    val soloMovie = manifestOf("addon.a", null, catalog("movie", "disney_movies", null))
    val movieESeries = manifestOf(
      "addon.a",
      null,
      catalog("movie", "disney_movies", null),
      catalog("series", "disney_series", null)
    )

    val scoreSoloMovie = proposer.propose(soloMovie, listOf(disney)).first().score
    val scoreConSerie = proposer.propose(movieESeries, listOf(disney))

    assertEquals(2, scoreConSerie.size)
    assertTrue("Il bonus movie+series deve alzare il punteggio", scoreConSerie.first().score > scoreSoloMovie)
    assertTrue(scoreConSerie.first().reasons.any { it.contains("movie sia series") })
  }

  @Test
  fun proposteOrdinateDallaPiuAltaConfidenza() {
    val manifest = manifestOf(
      "addon.a",
      null,
      catalog("movie", "streaming_disney_movies", "Disney+"),
      catalog("series", "netflix_series", "Netflix")
    )
    val proposals = proposer.propose(manifest, allProviders)

    assertTrue(proposals.size >= 2)
    assertEquals(
      proposals.map { it.score },
      proposals.map { it.score }.sortedDescending()
    )
  }

  @Test
  fun proposteDeduplicatePerCatalogoEProvider() {
    val manifest = manifestOf("addon.a", null, catalog("movie", "disney_movies", "Disney+"))
    val proposals = proposer.propose(manifest, listOf(disney, disney))

    assertEquals(1, proposals.size)
  }

  @Test
  fun catalogoSearchOnlyVieneSegnalatoMaRestaUnaProposta() {
    val manifest = manifestOf(
      "addon.a",
      null,
      catalog(
        "movie",
        "disney_search",
        "Disney+",
        extraSupported = listOf("search", "skip"),
        extraRequired = listOf("search")
      )
    )
    val proposals = proposer.propose(manifest, listOf(disney))

    assertEquals(1, proposals.size)
    assertTrue(proposals.first().reasons.any { it.contains("search") })
    assertTrue(proposals.first().score >= HeuristicProviderCatalogProposer.MIN_SCORE)
  }

  // ── 7. Registry basata su dati ──────────────────────────────────────────────

  @Test
  fun registryLeggeIlJsonEDichiaraSourceRegistry() {
    val json = """
      {
        "version": 1,
        "bindings": [
          { "addonManifestId": "addon.a", "type": "movie", "catalogId": "disney_movies", "providerId": "disney" }
        ]
      }
    """.trimIndent()

    val registry = ProviderBindingRegistry.parse(json)

    assertEquals(1, registry.all().size)
    val found = registry.find(CatalogKey("addon.a", "movie", "disney_movies"))
    assertNotNull(found)
    assertEquals(BindingSource.REGISTRY, found!!.source)
    assertEquals("disney", found.providerId)
    assertNull("Il tipo series non deve ereditare il binding movie", registry.find(CatalogKey("addon.a", "series", "disney_movies")))
  }

  @Test
  fun registryScartaLeVociIncomplete() {
    val json = """
      {
        "version": 1,
        "bindings": [
          { "addonManifestId": "addon.a", "type": "movie", "catalogId": "ok_movies", "providerId": "disney" },
          { "addonManifestId": "addon.a", "type": "movie", "providerId": "disney" },
          { "type": "movie", "catalogId": "no_addon", "providerId": "disney" },
          { "addonManifestId": "addon.a", "type": "movie", "catalogId": "no_provider", "providerId": "  " }
        ]
      }
    """.trimIndent()

    val registry = ProviderBindingRegistry.parse(json)

    assertEquals(1, registry.all().size)
    assertNotNull(registry.find(CatalogKey("addon.a", "movie", "ok_movies")))
    assertNull(registry.find(CatalogKey("addon.a", "movie", "no_addon")))
    assertNull(registry.find(CatalogKey("addon.a", "movie", "no_provider")))
  }

  @Test
  fun registryMalformataOAssenteRitornaVuotaSenzaEccezioni() {
    assertTrue(ProviderBindingRegistry.parse(null).all().isEmpty())
    assertTrue(ProviderBindingRegistry.parse("").all().isEmpty())
    assertTrue(ProviderBindingRegistry.parse("[]").all().isEmpty())
    assertTrue(ProviderBindingRegistry.parse("{ questo non è json").all().isEmpty())
    assertTrue(ProviderBindingRegistry.parse("""{"version": 99, "bindings": []}""").all().isEmpty())
  }

  @Test
  fun registrySupportaAncheLaListaPiatta() {
    val json = """
      [{ "addonManifestId": "addon.a", "type": "series", "catalogId": "disney_series", "providerId": "disney" }]
    """.trimIndent()

    assertEquals(1, ProviderBindingRegistry.parse(json).all().size)
  }

  @Test
  fun registryRisolveCaseInsensitive() {
    val registry = ProviderBindingRegistry.parse(
      """{"version": 1, "bindings": [
        { "addonManifestId": "Addon.A", "type": "Movie", "catalogId": "Disney_Movies", "providerId": "Disney" }
      ]}"""
    )

    assertNotNull(registry.find(CatalogKey("addon.a", "movie", "disney_movies")))
  }

  // ── 8. User binding store: serializzazione ───────────────────────────────────

  @Test
  fun userBindingStoreFaRoundTripPreservandoChiaveEProvider() {
    val original = listOf(
      binding("addon.a", "movie", "disney_movies", "disney", BindingSource.USER),
      binding("addon.b", "series", "top_series", "netflix", BindingSource.USER)
    )

    val decoded = UserProviderBindingStore.decode(UserProviderBindingStore.encode(original))

    assertEquals(2, decoded.size)
    assertEquals(original.map { it.key.stableId }.toSet(), decoded.map { it.key.stableId }.toSet())
    assertEquals("disney", decoded.first { it.key.stableId == "addon.a|movie|disney_movies" }.providerId)
    assertTrue(decoded.all { it.source == BindingSource.USER })
    assertTrue(decoded.all { it.isDeclarative })
  }

  @Test
  fun userBindingStoreSopportaJsonVuotoOCorrotto() {
    assertTrue(UserProviderBindingStore.decode(null).isEmpty())
    assertTrue(UserProviderBindingStore.decode("[]").isEmpty())
    assertTrue(UserProviderBindingStore.decode("non è json").isEmpty())
  }

  @Test
  fun userBindingStoreDeduplicaPerChiave() {
    val encoded = UserProviderBindingStore.encode(
      listOf(
        binding("addon.a", "movie", "x", "disney", BindingSource.USER),
        binding("addon.a", "movie", "x", "netflix", BindingSource.USER)
      )
    )

    assertEquals(1, UserProviderBindingStore.decode(encoded).size)
  }

  @Test
  fun ogniBindingEDichiarativoNonEsisteSourceEuristico() {
    // Invariante architetturale: le sorgenti sono solo USER e REGISTRY,
    // quindi nessun binding può essere stato prodotto da un euristica.
    assertEquals(listOf("USER", "REGISTRY"), BindingSource.values().map { it.name })
    assertTrue(binding("addon.a", "movie", "x", "disney", BindingSource.REGISTRY).isDeclarative)
    assertTrue(binding("addon.a", "movie", "x", "disney", BindingSource.USER).isDeclarative)
  }
}
