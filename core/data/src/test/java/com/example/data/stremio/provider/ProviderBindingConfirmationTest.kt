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
 * Test della CONFERMA delle proposte euristiche (UI Addon).
 *
 * Nessuna rete, nessun Android, nessun Compose: [ProviderBindingConfirmation] e' una
 * funzione pura, quindi ogni regola della UI e' verificabile in JVM.
 *
 * Invarianti verificati:
 * - una proposta euristica non crea mai un binding da sola;
 * - la conferma esplicita crea `USER` / `CONFIRMED` con la persistenza delegata al
 *   chiamante ([UserProviderBindingStore]);
 * - la stessa associazione non e' duplicabile;
 * - la rimozione elimina SOLO il binding utente;
 * - `USER` prevale su `REGISTRY`;
 * - un catalogo senza proposta resta un catalogo utilizzabile e non viene bloccato.
 */
class ProviderBindingConfirmationTest {

  // ── Fixture ──────────────────────────────────────────────────────────────────

  private data class TestProvider(
    override val providerId: String,
    override val providerName: String,
    override val providerAliases: Set<String>
  ) : ProviderAliasProvider

  private val disney = TestProvider("disney", "Disney+", setOf("disney", "disney plus"))
  private val apple = TestProvider("apple", "Apple TV+", setOf("apple", "apple tv"))
  private val providers = listOf(disney, apple)

  private fun catalog(
    type: String,
    id: String,
    name: String? = null,
    extraSupported: List<String>? = listOf("skip"),
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

  /** Manifest con due cataloghi Disney+ (film e serie), come nell'esempio della UI. */
  private val disneyManifest = manifestOf(
    "addon.disney",
    "The Walt Disney Company",
    catalog(TYPE_MOVIE, "streaming_disney_movies", "Disney+ Movies"),
    catalog(TYPE_SERIES, "streaming_disney_series", "Disney+ Series")
  )

  private val disneyMovieKey = CatalogKey("addon.disney", TYPE_MOVIE, "streaming_disney_movies")
  private val disneySeriesKey = CatalogKey("addon.disney", TYPE_SERIES, "streaming_disney_series")

  private fun binding(
    addonId: String,
    type: String,
    catalogId: String,
    providerId: String,
    source: BindingSource
  ) = ProviderBinding(
    key = CatalogKey(addonId, type, catalogId),
    providerId = providerId,
    source = source,
    confidence = BindingConfidence.CONFIRMED
  )

  private fun resolverOf(user: List<ProviderBinding> = emptyList(), registry: List<ProviderBinding> = emptyList()) =
    DefaultProviderCatalogResolver(
      userBindings = InMemoryProviderBindingSource(user),
      registry = InMemoryProviderBindingSource(registry),
      proposer = HeuristicProviderCatalogProposer(),
      providers = { providers }
    )

  private fun proposalsFor(
    manifest: StremioManifest,
    user: List<ProviderBinding> = emptyList(),
    registry: List<ProviderBinding> = emptyList()
  ): List<ProviderBindingProposalView> {
    val r = resolverOf(user, registry)
    return ProviderBindingConfirmation.proposalsFor(
      manifest = manifest,
      providers = providers,
      existingBinding = { key -> r.resolve(key) }
    )
  }

  // ── 1. La proposta non crea associazioni ──────────────────────────────────────

  @Test
  fun proposteEuristicheNonCreanoBinding() {
    val userBindings = emptyList<ProviderBinding>()

    val views = proposalsFor(disneyManifest, user = userBindings)

    assertTrue("Il proposer deve produrre proposte", views.isNotEmpty())
    assertTrue(views.all { it.status == ProviderBindingProposalStatus.PROPOSED })
    assertTrue("Nessuna proposta deve generare un binding", userBindings.isEmpty())

    // La prova che conta: dopo aver SOLO valutato le proposte, il resolver non
    // risolve nessuna delle chiavi proposte, quindi nessun provider viene attivato.
    val r = resolverOf(user = userBindings)
    views.forEach { assertNull("Le proposte non devono diventare binding", r.resolve(it.key)) }
  }

  @Test
  fun proposteMostranoProviderCatalogoIdTipoEAddon() {
    val views = proposalsFor(disneyManifest)
    val movie = views.first { it.key.stableId == disneyMovieKey.stableId }

    assertEquals("disney", movie.providerId)
    assertEquals("Disney+ Movies", movie.catalogTitle)
    assertEquals("streaming_disney_movies", movie.key.normalizedCatalogId)
    assertEquals(TYPE_MOVIE, movie.key.stremioType)
    assertEquals("addon.disney", movie.key.normalizedAddonId)
    assertTrue("Il tipo deve arrivare al modello", movie.key.isMovie)
    assertTrue(movie.proposal.reasons.isNotEmpty())
    assertTrue(movie.isConfirmable)
    assertFalse(movie.isRemovable)
  }

  // ── 2. Conferma -> USER / CONFIRMED ──────────────────────────────────────────

  @Test
  fun confermaCreaBindingUserConfermato() {
    val result = ProviderBindingConfirmation.confirm(emptyList(), disneyMovieKey, "disney")

    assertTrue(result.changed)
    assertEquals(BindingEditOutcome.ADDED, result.outcome)
    val created = result.bindings.single()
    assertEquals(BindingSource.USER, created.source)
    assertEquals(BindingConfidence.CONFIRMED, created.confidence)
    assertEquals("disney", created.normalizedProviderId)
    assertEquals(disneyMovieKey, created.key)
    assertTrue("Un binding utente resta dichiarativo", created.isDeclarative)
  }

  @Test
  fun confermaDiFilmNonCreaIlBindingDelleSerie() {
    val films = ProviderBindingConfirmation.confirm(emptyList(), disneyMovieKey, "disney")

    assertEquals(1, films.bindings.size)
    assertNull("Il binding movie non deve valere per le serie", resolverOf(user = films.bindings).resolve(disneySeriesKey))
  }

  // ── 3. Nessun duplicato ──────────────────────────────────────────────────────

  @Test
  fun stessoBindingNonEduplicabile() {
    val first = ProviderBindingConfirmation.confirm(emptyList(), disneyMovieKey, "disney")
    val second = ProviderBindingConfirmation.confirm(first.bindings, disneyMovieKey, "disney")

    assertEquals(BindingEditOutcome.ALREADY_PRESENT, second.outcome)
    assertFalse("Nessuna scrittura se il binding esiste gia'", second.changed)
    assertEquals(1, second.bindings.size)
  }

  @Test
  fun providerIdDiversoPerCasoNonGeneraDuplicati() {
    val first = ProviderBindingConfirmation.confirm(emptyList(), disneyMovieKey, "disney")
    val second = ProviderBindingConfirmation.confirm(first.bindings, disneyMovieKey, " DISNEY ")

    assertEquals(BindingEditOutcome.ALREADY_PRESENT, second.outcome)
    assertEquals(1, second.bindings.size)
  }

  @Test
  fun chiaveGiaAssociataMostraAssociatoENonPermetteDuplicati() {
    val views = proposalsFor(disneyManifest, user = listOf(
      binding("addon.disney", TYPE_MOVIE, "streaming_disney_movies", "disney", BindingSource.USER)
    ))

    val movie = views.first { it.key.stableId == disneyMovieKey.stableId }
    assertEquals(ProviderBindingProposalStatus.USER, movie.status)
    assertEquals("disney", movie.boundProviderId)
    assertTrue(movie.isAlreadyBoundToSameProvider)
    assertFalse("Un binding identico non deve essere riconfermabile", movie.isConfirmable)
    assertTrue(movie.isRemovable)

    val series = views.first { it.key.stableId == disneySeriesKey.stableId }
    assertEquals(ProviderBindingProposalStatus.PROPOSED, series.status)
  }

  @Test
  fun dueAddonConStessoCatalogIdRestanoAssociazioniSeparate() {
    val otherManifest = manifestOf("addon.altro", "Altro", catalog(TYPE_MOVIE, "streaming_disney_movies", "Disney+ Movies"))

    val result = ProviderBindingConfirmation.confirm(emptyList(), disneyMovieKey, "disney")
    val resultOther = ProviderBindingConfirmation.confirm(result.bindings, CatalogKey("addon.altro", TYPE_MOVIE, "streaming_disney_movies"), "disney")

    assertEquals(2, resultOther.bindings.size)
    assertEquals(setOf("addon.disney", "addon.altro"), resultOther.bindings.map { it.key.normalizedAddonId }.toSet())
    assertTrue(proposalsFor(otherManifest).isNotEmpty())
  }

  // ── 4. Rimozione del solo binding utente ─────────────────────────────────────

  @Test
  fun rimozioneEliminaIlBindingUtente() {
    val confirmed = ProviderBindingConfirmation.confirm(emptyList(), disneyMovieKey, "disney").bindings
    val alsoSeries = ProviderBindingConfirmation.confirm(confirmed, disneySeriesKey, "disney").bindings

    val removed = ProviderBindingConfirmation.remove(alsoSeries, disneyMovieKey, "disney")

    assertEquals(BindingEditOutcome.REMOVED, removed.outcome)
    assertTrue(removed.changed)
    assertEquals(1, removed.bindings.size)
    assertEquals(disneySeriesKey, removed.bindings.single().key)
    assertNull(resolverOf(user = removed.bindings).resolve(disneyMovieKey))
    assertNotNull("Le altre associazioni restano", resolverOf(user = removed.bindings).resolve(disneySeriesKey))
  }

  @Test
  fun rimozioneDiBindingAssenteNonScriveNulla() {
    val confirmed = ProviderBindingConfirmation.confirm(emptyList(), disneyMovieKey, "disney").bindings

    val removed = ProviderBindingConfirmation.remove(confirmed, disneySeriesKey, "disney")
    assertEquals(BindingEditOutcome.NOT_PRESENT, removed.outcome)
    assertFalse(removed.changed)

    val wrongProvider = ProviderBindingConfirmation.remove(confirmed, disneyMovieKey, "netflix")
    assertEquals(BindingEditOutcome.NOT_PRESENT, wrongProvider.outcome)
    assertEquals(1, wrongProvider.bindings.size)
  }

  @Test
  fun rimozioneNonCoinvolgeLaRegistry() {
    val registry = binding("addon.disney", TYPE_MOVIE, "streaming_disney_movies", "netflix", BindingSource.REGISTRY)
    val user = listOf(
      binding("addon.disney", TYPE_MOVIE, "streaming_disney_movies", "disney", BindingSource.USER)
    )

    val removed = ProviderBindingConfirmation.remove(user, disneyMovieKey, "disney")

    assertTrue("La registry vive nel resolver, non nella lista utente", user.isNotEmpty())
    assertTrue(removed.bindings.isEmpty())
    assertTrue(registry.isDeclarative)
    assertEquals(BindingSource.REGISTRY, registry.source)
    assertEquals("netflix", registry.providerId)
  }

  // ── 5. USER prevale su REGISTRY ──────────────────────────────────────────────

  @Test
  fun userPrevaleSuRegistryDopoLaConferma() {
    val registry = listOf(binding("addon.disney", TYPE_MOVIE, "streaming_disney_movies", "netflix", BindingSource.REGISTRY))
    val user = ProviderBindingConfirmation.confirm(emptyList(), disneyMovieKey, "disney").bindings
    val r = resolverOf(user = user, registry = registry)

    val resolved = r.resolve(disneyMovieKey)

    assertNotNull(resolved)
    assertEquals(BindingSource.USER, resolved!!.source)
    assertEquals("disney", resolved.providerId)
  }

  @Test
  fun chiaveInRegistryNonEAssociataMaRestaSostituibile() {
    val registry = listOf(binding("addon.disney", TYPE_MOVIE, "streaming_disney_movies", "netflix", BindingSource.REGISTRY))

    val view = proposalsFor(disneyManifest, registry = registry).first { it.key.stableId == disneyMovieKey.stableId }

    assertEquals(ProviderBindingProposalStatus.REGISTRY, view.status)
    assertEquals("netflix", view.boundProviderId)
    assertFalse("Gia' associata allo stesso provider: niente duplicato", view.isAlreadyBoundToSameProvider)
    assertTrue(view.isConfirmable)
    assertFalse("La registry non e' rimovibile dalla UI", view.isRemovable)
  }

  // ── 6. I cataloghi senza proposta restano utilizzabili ───────────────────────

  @Test
  fun catalogoSenzaPropostaRestaUtilizzabile() {
    val genericManifest = manifestOf(
      "addon.generico",
      "Addon Generico",
      catalog(TYPE_MOVIE, "top_100_movies", "Top 100 Film"),
      catalog(TYPE_SERIES, "top_100_series", "Top 100 Serie")
    )

    val views = proposalsFor(genericManifest)

    assertTrue("Nessuna proposta attesa", views.isEmpty())
    assertEquals(
      "I cataloghi senza proposta non vengono nascosti",
      listOf("top_100_movies", "top_100_series"),
      genericManifest.validCatalogs().map { it.effectiveId }
    )
  }

  @Test
  fun catalogoSenzaPropostaContinuaAFunzionareNelPiano() {
    val generic = StremioCatalogTarget(
      addonId = "addon.generico",
      addonName = "Addon Generico",
      baseUrl = "https://example.test/generico",
      catalog = catalog(TYPE_MOVIE, "top_100_movies", "Top 100 Film")
    )
    val key = CatalogKey("addon.generico", TYPE_MOVIE, "top_100_movies")

    // Senza binding il catalogo resta semplicemente un catalogo: subentra il fallback
    // nativo, ma nessun piano lo blocca e nessun binding viene dedotto.
    val senzaBinding = ProviderCatalogPlanner.plan("disney", listOf(generic), resolverOf(), MediaType.FILM)
    assertFalse(senzaBinding.hasConfirmedBindings)

    // Con un binding dichiarato continua a essere caricabile normalmente.
    val conBinding = ProviderCatalogPlanner.plan(
      "disney",
      listOf(generic),
      resolverOf(registry = listOf(binding("addon.generico", TYPE_MOVIE, "top_100_movies", "disney", BindingSource.REGISTRY))),
      MediaType.FILM
    )
    assertTrue(conBinding.hasConfirmedBindings)
    assertEquals(key, conBinding.resolved.single().catalogKey)
  }
}
