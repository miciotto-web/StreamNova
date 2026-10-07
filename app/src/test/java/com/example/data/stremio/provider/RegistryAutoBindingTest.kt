package com.example.data.stremio.provider

import com.example.data.model.MediaType
import com.example.data.stremio.StremioCatalogDefinition
import com.example.data.stremio.StremioCatalogTarget
import com.example.data.stremio.StremioManifest
import com.example.data.stremio.TYPE_MOVIE
import com.example.data.stremio.TYPE_SERIES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test dell'auto-binding da REGISTRY.
 *
 * Il registry è un dato dichiarativo: se la sua chiave
 * `addonManifestId + type + catalogId` corrisponde a un catalogo di un addon attivo,
 * l'associazione con il provider è già confermata e non richiede alcuna azione utente.
 * Il resolver resta l'unica fonte di verità, con precedenza `USER > REGISTRY > null`.
 *
 * Il JSON qui sotto è una FIXTURE: il registry shipped in `res/raw` contiene solo
 * chiavi verificate sui manifest reali, quindi una voce si inserisce solo quando
 * `addonManifestId`, `type` e `catalogId` provengono da un manifest effettivamente
 * letto. Nessun test di rete.
 */
class RegistryAutoBindingTest {

  // ── Fixture ──────────────────────────────────────────────────────────────────

  /**
   * Identificativi sintetici: NON descrivono un addon reale. Servono solo a verificare
   * il comportamento del resolver sui dati di registry, isolati dalla rete.
   */
  private val addonId = "addon.registry.fixture"
  private val otherAddonId = "addon.registry.altro"

  private val registryJson = """
    {
      "version": 1,
      "bindings": [
        {
          "addonManifestId": "$addonId",
          "type": "movie",
          "catalogId": "brand_movies",
          "providerId": "disney"
        },
        {
          "addonManifestId": "$addonId",
          "type": "series",
          "catalogId": "brand_series",
          "providerId": "disney"
        },
        {
          "addonManifestId": "$addonId",
          "type": "movie",
          "catalogId": "brand_movies_netflix",
          "providerId": "netflix"
        }
      ]
    }
  """.trimIndent()

  private val movieKey = CatalogKey(addonId, TYPE_MOVIE, "brand_movies")
  private val seriesKey = CatalogKey(addonId, TYPE_SERIES, "brand_series")

  private data class TestProvider(
    override val providerId: String,
    override val providerName: String,
    override val providerAliases: Set<String>
  ) : ProviderAliasProvider

  private val providers = listOf(
    TestProvider("disney", "Disney+", setOf("disney")),
    TestProvider("netflix", "Netflix", setOf("netflix"))
  )

  private fun target(id: String, type: String, catalogId: String) = StremioCatalogTarget(
    addonId = id,
    addonName = "Addon $id",
    baseUrl = "https://example.test/$id",
    catalog = StremioCatalogDefinition(
      type = type,
      id = catalogId,
      name = catalogId,
      extraSupported = listOf("skip")
    )
  )

  private val addonTargets = listOf(
    target(addonId, TYPE_MOVIE, "brand_movies"),
    target(addonId, TYPE_SERIES, "brand_series"),
    target(addonId, TYPE_MOVIE, "brand_movies_netflix"),
    target(addonId, TYPE_MOVIE, "top_100_movies")
  )

  /** Registry letta con il parser di produzione: stessa semantica del file in res/raw. */
  private fun registry() = ProviderBindingRegistry.parse(registryJson)

  private fun resolverOf(user: List<ProviderBinding> = emptyList()) = DefaultProviderCatalogResolver(
    userBindings = InMemoryProviderBindingSource(user),
    registry = registry(),
    proposer = HeuristicProviderCatalogProposer(),
    providers = { providers }
  )

  private fun plan(resolver: DefaultProviderCatalogResolver, providerId: String, mediaType: MediaType?) =
    ProviderCatalogPlanner.plan(providerId, addonTargets, resolver, mediaType)

  private fun userBinding(key: CatalogKey, providerId: String) = ProviderBinding(
    key = key,
    providerId = providerId,
    source = BindingSource.USER,
    confidence = BindingConfidence.CONFIRMED
  )

  // ── 1. Il registry dichiara sempre REGISTRY / CONFIRMED ──────────────────────

  @Test
  fun bindingDiRegistryVieneRisoltoComeRegistryConfermato() {
    val resolved = resolverOf().resolve(movieKey)

    assertNotNull(resolved)
    assertEquals(BindingSource.REGISTRY, resolved!!.source)
    assertEquals(BindingConfidence.CONFIRMED, resolved.confidence)
    assertEquals("disney", resolved.providerId)
    assertTrue("Il registry e' una fonte dichiarativa", resolved.isDeclarative)
  }

  @Test
  fun ilRegistryEUnDatoNonUnEuristica() {
    val registry = registry()

    assertEquals(3, registry.all().size)
    assertEquals(BindingSource.REGISTRY, registry.find(movieKey)?.source)
    assertNull(registry.find(CatalogKey(addonId, TYPE_SERIES, "brand_movies")))
  }

  // ── 2 / 3. Separazione Film / Serie ──────────────────────────────────────────

  @Test
  fun registryMovieAttivaSoloIlCatalogoFilm() {
    val resolver = resolverOf()

    val movies = plan(resolver, "disney", MediaType.FILM)

    assertTrue(movies.hasConfirmedBindings)
    assertEquals(1, movies.resolved.size)
    assertEquals("brand_movies", movies.resolved.single().target.catalogId)
    assertEquals(MediaType.FILM, movies.resolved.single().mediaType)
    assertEquals(BindingSource.REGISTRY, movies.resolved.single().binding.source)
  }

  @Test
  fun registrySeriesAttivaSoloIlCatalogoSerie() {
    val resolver = resolverOf()

    val series = plan(resolver, "disney", MediaType.SERIE_TV)

    assertTrue(series.hasConfirmedBindings)
    assertEquals(1, series.resolved.size)
    assertEquals("brand_series", series.resolved.single().target.catalogId)
    assertEquals(MediaType.SERIE_TV, series.resolved.single().mediaType)
    assertEquals(BindingSource.REGISTRY, series.resolved.single().binding.source)
  }

  @Test
  fun registrySeriesNonAttivaIFilm() {
    val resolver = resolverOf()

    // Sul solo catalogo series dichiarato, il piano Film resta vuoto.
    val targets = listOf(target(addonId, TYPE_SERIES, "brand_series"))
    val movies = ProviderCatalogPlanner.plan("disney", targets, resolver, MediaType.FILM)

    assertFalse(movies.hasConfirmedBindings)
    assertEquals(1, movies.unmappedTargets)
  }

  // ── 4. Auto-binding: nessuna conferma richiesta ──────────────────────────────

  @Test
  fun ilRegistryAttivaIlProviderSenzaNessunaConfermaUtente() {
    // Nessun binding utente: se il piano e' gia' confermato, l'auto-binding e' avvenuto.
    val resolver = resolverOf()

    assertTrue(resolver.applicableBindings().isNotEmpty())
    assertTrue(resolver.hasBindingsFor("disney"))
    assertTrue(plan(resolver, "disney", null).hasConfirmedBindings)
    assertTrue(plan(resolver, "disney", null).resolved.all { it.binding.source == BindingSource.REGISTRY })
  }

  @Test
  fun unProviderSenzaDichiarazioniRestaInFallbackTmdb() {
    val resolver = resolverOf()

    assertFalse(plan(resolver, "hbo", MediaType.FILM).hasConfirmedBindings)
    assertTrue(plan(resolver, "hbo", MediaType.SERIE_TV).resolved.isEmpty())
  }

  @Test
  fun unBindingRegistryAttivaUnSoloProvider() {
    val resolver = resolverOf()

    assertTrue(plan(resolver, "disney", MediaType.FILM).hasConfirmedBindings)
    assertTrue(plan(resolver, "netflix", MediaType.FILM).hasConfirmedBindings)
    // 'brand_movies' e' dichiarato per disney: non puo' finire nel piano di netflix.
    assertTrue(plan(resolver, "netflix", MediaType.FILM).resolved.none { it.target.catalogId == "brand_movies" })
  }

  // ── 5. Identita' dell'addon ──────────────────────────────────────────────────

  @Test
  fun ilBindingDiRegistryNonRichiedeConfermaUtente() {
    val resolver = resolverOf()
    val views = ProviderBindingConfirmation.proposalsFor(
      manifest = StremioManifest(
        id = addonId,
        name = "Addon Fixture",
        catalogs = listOf(
          StremioCatalogDefinition(type = TYPE_MOVIE, id = "brand_movies", name = "Brand Movies")
        )
      ),
      providers = providers,
      existingBinding = { key -> resolver.resolve(key) }
    )

    val view = views.first { it.key.stableId == movieKey.stableId }
    assertEquals(ProviderBindingProposalStatus.REGISTRY, view.status)
    assertEquals("disney", view.boundProviderId)
    assertTrue("Gia' associato: non duplicabile", view.isAlreadyBoundToSameProvider)
    assertFalse("Il registry non richiede la conferma", view.isConfirmable)
    assertFalse("Il registry non e' rimovibile dalla UI", view.isRemovable)
  }

  @Test
  fun ilBindingDiRegistryNonInfluenzaUnAddonConManifestIdDiverso() {
    val resolver = resolverOf()
    val altroKey = CatalogKey(otherAddonId, TYPE_MOVIE, "brand_movies")

    assertNull("Stesso catalogId, addon diverso: nessun auto-binding", resolver.resolve(altroKey))

    val targets = listOf(target(otherAddonId, TYPE_MOVIE, "brand_movies"))
    val plan = ProviderCatalogPlanner.plan("disney", targets, resolver, MediaType.FILM)
    assertFalse(plan.hasConfirmedBindings)
    assertEquals(1, plan.unmappedTargets)
  }

  // ── 6. USER prevale su REGISTRY ──────────────────────────────────────────────

  @Test
  fun userBindingPrevaleSulBindingDiRegistry() {
    val resolver = resolverOf(user = listOf(userBinding(movieKey, "netflix")))

    val resolved = resolver.resolve(movieKey)

    assertEquals(BindingSource.USER, resolved?.source)
    assertEquals("netflix", resolved?.providerId)

    val netflix = plan(resolver, "netflix", MediaType.FILM)
    assertTrue(netflix.resolved.any { it.target.catalogId == "brand_movies" })
    assertEquals(BindingSource.USER, netflix.resolved.first { it.target.catalogId == "brand_movies" }.binding.source)

    // La voce di registry resta valida ma per il provider dichiarato nel registry.
    val disney = plan(resolver, "disney", MediaType.FILM)
    assertFalse(disney.resolved.any { it.target.catalogId == "brand_movies" })
    assertTrue(disney.resolved.any { it.binding.source == BindingSource.REGISTRY })
  }

  // ── 7. Rimozione USER -> torna il REGISTRY ───────────────────────────────────

  @Test
  fun rimuovendoIlBindingUtenteTornaQuelloDiRegistry() {
    val user = listOf(userBinding(movieKey, "netflix"))
    assertEquals(BindingSource.USER, resolverOf(user).resolve(movieKey)?.source)

    val removed = ProviderBindingConfirmation.remove(user, movieKey, "netflix")
    val resolver = resolverOf(removed.bindings)

    assertEquals(BindingEditOutcome.REMOVED, removed.outcome)
    assertEquals("disney", resolver.resolve(movieKey)?.providerId)
    assertEquals(BindingSource.REGISTRY, resolver.resolve(movieKey)?.source)
    assertTrue("Il provider torna attivo con il catalogo dichiarato", plan(resolver, "disney", MediaType.FILM).hasConfirmedBindings)
  }

  @Test
  fun laRimozioneUtenteNonModificaIlRegistry() {
    val user = listOf(userBinding(movieKey, "netflix"))
    ProviderBindingConfirmation.remove(user, movieKey, "netflix")

    val registry = registry()
    assertEquals(3, registry.all().size)
    assertEquals("disney", registry.find(movieKey)?.providerId)
    assertEquals(BindingSource.REGISTRY, registry.find(movieKey)?.source)
  }

  // ── 8. Cataloghi non registrati ──────────────────────────────────────────────

  @Test
  fun catalogoNonPresenteNelRegistryNonHaAutoBinding() {
    val resolver = resolverOf()
    val genericKey = CatalogKey(addonId, TYPE_MOVIE, "top_100_movies")

    assertNull(resolver.resolve(genericKey))

    val movies = plan(resolver, "disney", MediaType.FILM)
    // Resta un normale catalogo generico: nessun binding, nessun blocco.
    assertFalse(movies.resolved.any { it.target.catalogId == "top_100_movies" })
    assertEquals(1, movies.resolved.size)
    assertEquals(
      "Il catalogo generico continua a essere utilizzabile",
      4,
      StremioManifest(
        id = addonId,
        catalogs = addonTargets.map {
          StremioCatalogDefinition(type = it.type, id = it.catalogId, name = it.catalogId)
        }
      ).validCatalogs().size
    )
  }

  // ── 9. Il registry non viene duplicato come USER ─────────────────────────────

  @Test
  fun unBindingDiRegistryNonVieneDuplicatoComeUser() {
    val resolver = resolverOf()

    val result = ProviderBindingConfirmation.confirm(
      userBindings = emptyList(),
      key = movieKey,
      providerId = "disney",
      alreadyDeclared = { key -> resolver.resolve(key) }
    )

    assertEquals(BindingEditOutcome.ALREADY_PRESENT, result.outcome)
    assertFalse("Nessuna scrittura: il registry basta gia'", result.changed)
    assertTrue("Nessun binding USER duplicato", result.bindings.isEmpty())
    assertTrue(UserProviderBindingStore.decode(UserProviderBindingStore.encode(result.bindings)).isEmpty())
  }

  @Test
  fun sostituireUnBindingDiRegistryRichiedeUnBindingUtenteDiverso() {
    val resolver = resolverOf()

    val result = ProviderBindingConfirmation.confirm(
      userBindings = emptyList(),
      key = movieKey,
      providerId = "netflix",
      alreadyDeclared = { key -> resolver.resolve(key) }
    )

    assertEquals(BindingEditOutcome.ADDED, result.outcome)
    val resolverAfter = resolverOf(result.bindings)
    assertEquals("netflix", resolverAfter.resolve(movieKey)?.providerId)
    assertEquals(BindingSource.USER, resolverAfter.resolve(movieKey)?.source)
  }

  @Test
  fun nessunBindingDiRegistryDichiaraUnCatalogoGenerico() {
    // Invariante del dato: una voce di registry dichiara solo cataloghi di brand.
    val forbidden = listOf("top", "trending", "top_100", "for_you", "latest", "popular", "year", "imdbRating")

    registry().all().forEach { binding ->
      val catalogId = binding.key.normalizedCatalogId.lowercase()
      assertFalse(
        "Catalogo generico nel registry: ${binding.key.stableId}",
        forbidden.any { catalogId == it || catalogId == "${it}_movies" || catalogId == "${it}_series" }
      )
    }
  }
}
