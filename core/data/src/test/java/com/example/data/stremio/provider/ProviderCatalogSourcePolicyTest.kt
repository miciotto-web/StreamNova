package com.example.data.stremio.provider

import com.example.data.stremio.CinemetaAddon
import com.example.data.stremio.StremioCatalogDefinition
import com.example.data.stremio.StremioCatalogTarget
import com.example.data.stremio.TYPE_MOVIE
import com.example.data.stremio.TYPE_SERIES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test della politica di SORGENTE dei cataloghi provider.
 *
 * Copre i tre vincoli richiesti:
 *  1. i cataloghi degli addon personalizzati (Xperience / Easy Catalog / ...)
 *     vengono associati al provider tramite id, nome ed extra dichiarati;
 *  2. Cinemeta non entra MAI nel piano quando gli addon personalizzati sono attivi;
 *  3. il fallback Cinemeta è ammesso SOLO quando `hasCustomCatalogAddons()` è false.
 */
class ProviderCatalogSourcePolicyTest {

  private fun provider(id: String, name: String, aliases: Set<String>): ProviderAliasProvider =
    object : ProviderAliasProvider {
      override val providerId: String = id
      override val providerName: String = name
      override val providerAliases: Set<String> = aliases
    }

  private val prime = provider(
    "prime",
    "Prime Video",
    setOf("prime", "prime video", "amazon", "amazon prime")
  )

  private val paramount = provider(
    "paramount",
    "Paramount+",
    setOf("paramount", "paramount+", "paramount plus")
  )

  private val crunchyroll = provider("crunchyroll", "Crunchyroll", setOf("crunchyroll"))

  private val apple = provider("apple", "Apple TV+", setOf("apple", "apple tv", "apple tv+", "appletv"))

  private val hbo = provider("hbo", "HBO Max", setOf("hbo", "hbo max", "max"))

  private fun target(
    catalogId: String,
    name: String? = null,
    type: String = TYPE_MOVIE,
    addonId: String = "app.xperience.60613fcd-0780-420d-a736-59c46cfa7ff9",
    addonName: String = "Xperience",
    baseUrl: String = "https://addon.example.org",
    extra: List<Any?>? = null
  ): StremioCatalogTarget = StremioCatalogTarget(
    addonId = addonId,
    addonName = addonName,
    baseUrl = baseUrl,
    catalog = StremioCatalogDefinition(type = type, id = catalogId, name = name, extra = extra)
  )

  private fun cinemetaTarget(catalogId: String = "top", name: String? = "Popular"): StremioCatalogTarget =
    target(
      catalogId = catalogId,
      name = name,
      addonId = CinemetaAddon.MANIFEST_ID,
      addonName = CinemetaAddon.DISPLAY_NAME,
      baseUrl = CinemetaAddon.BASE_URL
    )

  private fun matches(provider: ProviderAliasProvider, target: StremioCatalogTarget): Boolean =
    ProviderCatalogSourcePolicy.matchesProvider(provider, target)

  // ── Riconoscimento Cinemeta ────────────────────────────────────────────────

  @Test
  fun cinemetaVieneRiconosciutoDaIdManifestEBaseUrl() {
    assertTrue(ProviderCatalogSourcePolicy.isCinemeta("com.linvo.cinemeta"))
    assertTrue(ProviderCatalogSourcePolicy.isCinemeta("COM.LINVO.CINEMETA"))
    assertTrue(ProviderCatalogSourcePolicy.isCinemeta("qualcosa.cinemeta.altro"))
    assertTrue(ProviderCatalogSourcePolicy.isCinemeta(null, "https://v3-cinemeta.strem.io"))
    assertTrue(ProviderCatalogSourcePolicy.isCinemeta(null, CinemetaAddon.BASE_URL))

    assertFalse(ProviderCatalogSourcePolicy.isCinemeta("app.xperience.60613fcd"))
    assertFalse(ProviderCatalogSourcePolicy.isCinemeta(null, "https://addon.example.org"))
    assertFalse(ProviderCatalogSourcePolicy.isCinemeta(null, null))
  }

  // ── Requisito 2/3: esclusione Cinemeta condizionale ───────────────────────

  @Test
  fun selectTargetsEscludeCinemetaSoloConAddonPersonalizzatiAttivi() {
    val cinemeta = cinemetaTarget()
    val addon = target(catalogId = "streaming_prime_movies", name = "Prime Video")
    val targets = listOf(cinemeta, addon)

    assertEquals(
      "Con addon personalizzati Cinemeta deve sparire dai target provider",
      listOf(addon),
      ProviderCatalogSourcePolicy.selectTargets(targets, hasCustomCatalogAddons = true)
    )
    assertEquals(
      "Senza addon personalizzati Cinemeta resta disponibile come fallback",
      targets,
      ProviderCatalogSourcePolicy.selectTargets(targets, hasCustomCatalogAddons = false)
    )
  }

  @Test
  fun nessunTargetCinemetaCorrispondeMaiAUnProvider() {
    // Anche un catalogo Cinemeta il cui id contenesse un alias non può essere
    // agganciato: Cinemeta non è un addon di catalogo personalizzato.
    assertFalse(matches(prime, cinemetaTarget(catalogId = "top", name = "Prime Video Popular")))
    assertNull(ProviderCatalogSourcePolicy.customAddonBinding(prime, cinemetaTarget()))
  }

  // ── Requisito 1: mappatura dei cataloghi addon sul provider ───────────────

  @Test
  fun primeVideoGestisceTutteLeVariantiDiNome() {
    assertTrue(matches(prime, target(catalogId = "streaming_prime_movies")))
    assertTrue(matches(prime, target(catalogId = "streaming_movies", name = "Prime Video")))
    assertTrue(matches(prime, target(catalogId = "streaming_movies", name = "Amazon Prime")))
    assertTrue(matches(prime, target(catalogId = "amazonprime_movies")))
    assertTrue(
      matches(
        prime,
        target(
          catalogId = "streaming_movies",
          extra = listOf(
            mapOf("name" to "provider", "options" to listOf("Netflix", "Prime Video"))
          )
        )
      )
    )
    assertFalse(matches(prime, target(catalogId = "streaming_netflix_movies", name = "Netflix")))
  }

  @Test
  fun paramountGestisceIlSegnoPlusELeVarianti() {
    assertTrue(matches(paramount, target(catalogId = "streaming_paramount_movies")))
    assertTrue(matches(paramount, target(catalogId = "paramountplus_movies")))
    assertTrue(matches(paramount, target(catalogId = "streaming_movies", name = "Paramount+")))
    assertTrue(matches(paramount, target(catalogId = "streaming_movies", name = "Paramount Plus")))
    assertFalse(matches(paramount, target(catalogId = "streaming_prime_movies", name = "Prime Video")))
  }

  @Test
  fun crunchyrollGestisceIdENomeCatalogo() {
    assertTrue(matches(crunchyroll, target(catalogId = "crunchyroll_anime_series", type = TYPE_SERIES)))
    assertTrue(matches(crunchyroll, target(catalogId = "anime_series", name = "Crunchyroll")))
    assertFalse(matches(crunchyroll, target(catalogId = "anime_series", name = "Anime")))
  }

  @Test
  fun ilMatchNonUsaSottostringheGrezzo() {
    assertTrue(matches(apple, target(catalogId = "streaming_apple_movies", name = "Apple TV+")))
    assertFalse("'apple' non deve matchare 'pineapple'", matches(apple, target(catalogId = "pineapple_movies")))
    assertTrue(matches(hbo, target(catalogId = "streaming_hbo_movies", name = "HBO Max")))
    assertFalse("'max' non deve matchare 'maxwell'", matches(hbo, target(catalogId = "maxwell_movies")))
  }

  @Test
  fun soloGliExtraDiPiattaformaAlimentanoIlMatch() {
    val genreOptions = listOf(
      mapOf("name" to "genre", "options" to listOf("Drama", "Prime Video"))
    )
    assertTrue("Le opzioni dichiarate per 'genre' contano", matches(prime, target(catalogId = "streaming_movies", extra = genreOptions)))

    val searchOptions = listOf(
      mapOf("name" to "search", "options" to listOf("Prime Video"))
    )
    assertFalse(
      "Gli extra che non descrivono una piattaforma non contano",
      matches(prime, target(catalogId = "streaming_movies", extra = searchOptions))
    )
  }

  // ── Binding prodotti dalla politica ────────────────────────────────────────

  @Test
  fun customAddonBindingUsaLaSorgenteCustomAddonSullaGiustaChiave() {
    val catalog = target(catalogId = "streaming_prime_movies", name = "Prime Video")
    val binding = requireNotNull(ProviderCatalogSourcePolicy.customAddonBinding(prime, catalog))

    assertEquals(BindingSource.CUSTOM_ADDON, binding.source)
    assertEquals("prime", binding.providerId)
    assertEquals(
      CatalogKey.of(catalog.addonId, catalog.type, catalog.catalogId),
      binding.key
    )
    assertTrue(binding.isDeclarative)
    assertNull(
      "Un catalogo di un altro provider non deve essere agganciato",
      ProviderCatalogSourcePolicy.customAddonBinding(netflix(), catalog)
    )
  }

  @Test
  fun systemDefaultBindingValeSoloPerICataloghiCinemeta() {
    val cinemeta = cinemetaTarget()
    val binding = requireNotNull(ProviderCatalogSourcePolicy.systemDefaultBinding(prime, cinemeta))

    assertEquals(BindingSource.SYSTEM_DEFAULT, binding.source)
    assertEquals("prime", binding.providerId)
    assertEquals(
      CatalogKey.of(CinemetaAddon.MANIFEST_ID, TYPE_MOVIE, "top"),
      binding.key
    )

    assertNull(
      "L'addon personalizzato non riceve il fallback di sistema",
      ProviderCatalogSourcePolicy.systemDefaultBinding(prime, target(catalogId = "streaming_prime_movies"))
    )
  }

  @Test
  fun platformExtraForInviaSoloILValoreDichiaratoCheCoincideColProvider() {
    val withProviderExtra = target(
      catalogId = "streaming_movies",
      name = "Streaming",
      extra = listOf(
        mapOf(
          "name" to "provider",
          "isRequired" to false,
          "options" to listOf("Netflix", "Prime Video")
        )
      )
    )

    assertEquals(
      mapOf("provider" to "Prime Video"),
      ProviderCatalogSourcePolicy.platformExtraFor(prime, withProviderExtra)
    )
    assertEquals(
      "Nessuna opzione coincide con Crunchyroll: niente extra",
      emptyMap<String, String>(),
      ProviderCatalogSourcePolicy.platformExtraFor(crunchyroll, withProviderExtra)
    )
    assertEquals(
      "Cinemeta non riceve mai extra di piattaforma",
      emptyMap<String, String>(),
      ProviderCatalogSourcePolicy.platformExtraFor(prime, cinemetaTarget())
    )
  }

  @Test
  fun extraForUnisceExtraDiPiattaformaEGenreObbligatorio() {
    val catalogTarget = target(
      catalogId = "streaming_movies",
      name = "Streaming",
      extra = listOf(
        mapOf("name" to "provider", "options" to listOf("Prime Video")),
        mapOf("name" to "genre", "isRequired" to true, "options" to listOf("None", "Action"))
      )
    )
    val key = requireNotNull(CatalogKey.of(catalogTarget.addonId, catalogTarget.type, catalogTarget.catalogId))
    val resolved = ResolvedProviderTarget(
      target = catalogTarget,
      binding = ProviderBinding(key = key, providerId = "prime", source = BindingSource.CUSTOM_ADDON),
      platformExtra = mapOf("provider" to "Prime Video")
    )

    assertEquals(
      mapOf("provider" to "Prime Video", "genre" to "None"),
      ProviderCatalogPlanner.extraFor(resolved)
    )
  }

  private fun netflix(): ProviderAliasProvider = provider("netflix", "Netflix", setOf("netflix"))

  private val disney: ProviderAliasProvider = provider("disney", "Disney+", setOf("disney", "disney+"))

  // ── Mapping ID esatti dei manifest Xperience / Easy Catalog ────────────────

  @Test
  fun mappingIdEsattiPrimeVideoEParamount() {
    listOf(
      "streaming_prime_movies",
      "streaming_prime_series",
      "streaming_prime_originals_series",
      "streaming_prime_toprated_movies",
      "streaming_prime_toprated_series",
      "tmdb.movie.amazon",
      "tmdb.series.amazon"
    ).forEach { assertTrue("Prime deve riconoscere $it", matches(prime, target(catalogId = it, type = if (it.contains("series")) TYPE_SERIES else TYPE_MOVIE))) }

    listOf(
      "streaming_paramount_movies",
      "streaming_paramount_series",
      "streaming_paramount_originals_series",
      "streaming_paramount_toprated_movies",
      "streaming_paramount_toprated_series",
      "snoak_paramount_top10_movies",
      "tmdb.movie.paramount",
      "tmdb.series.paramount"
    ).forEach { assertTrue("Paramount+ deve riconoscere $it", matches(paramount, target(catalogId = it, type = if (it.contains("series")) TYPE_SERIES else TYPE_MOVIE))) }
  }

  @Test
  fun mappingIdEsattiCrunchyrollDisneyAppleHboENetflix() {
    listOf(
      "streaming_crunchyroll_movies",
      "streaming_crunchyroll_series",
      "tmdb.movie.crunchyroll_catalog",
      "tmdb.series.crunchyroll_catalog"
    ).forEach { assertTrue("Crunchyroll deve riconoscere $it", matches(crunchyroll, target(catalogId = it, type = if (it.contains("series")) TYPE_SERIES else TYPE_MOVIE))) }

    listOf("streaming_disney_movies", "snoak_disney_top10_movies", "tmdb.movie.disney", "tmdb.series.disney")
      .forEach { assertTrue("Disney+ deve riconoscere $it", matches(disney, target(catalogId = it))) }

    listOf("streaming_apple_movies", "snoak_apple_top10_movies", "tmdb.movie.apple", "tmdb.series.apple")
      .forEach { assertTrue("Apple TV+ deve riconoscere $it", matches(apple, target(catalogId = it))) }

    listOf("streaming_hbo_movies", "snoak_hbo_top10_movies", "tmdb.movie.hbo", "tmdb.series.hbo")
      .forEach { assertTrue("HBO Max deve riconoscere $it", matches(hbo, target(catalogId = it))) }

    listOf("snoak_netflix_movies", "snoak_netflix_top10_movies", "tmdb.movie.netflix", "tmdb.series.netflix")
      .forEach { assertTrue("Netflix deve riconoscere $it", matches(netflix(), target(catalogId = it))) }
  }

  @Test
  fun cataloghiSenzaExtraGenreOProviderRestanoAmmessi() {
    listOf(
      prime to "streaming_prime_movies",
      paramount to "streaming_paramount_series",
      crunchyroll to "streaming_crunchyroll_movies",
      disney to "streaming_disney_series",
      apple to "streaming_apple_movies",
      hbo to "streaming_hbo_series",
      netflix() to "snoak_netflix_movies"
    ).forEach { (provider, catalogId) ->
      val plain = target(catalogId = catalogId, extra = null)
      assertTrue(
        "$catalogId è già specifico per la piattaforma: nessun extra obbligatorio",
        matches(provider, plain)
      )
      assertTrue(
        "Senza extra dichiarati non ci sono extra di piattaforma da inviare",
        ProviderCatalogSourcePolicy.platformExtraFor(provider, plain).isEmpty()
      )
    }
  }

  // ── Fallback "anime" di Crunchyroll ────────────────────────────────────────

  @Test
  fun crunchyrollUsaIlTokenAnimeSoloQuandoMancanoCataloghiEspliciti() {
    val animeOnly = listOf("anime_movies", "anime_series")

    assertTrue(
      "Senza cataloghi Crunchyroll espliciti l'addon anime va a Crunchyroll",
      ProviderCatalogSourcePolicy.matchesProvider(crunchyroll, target(catalogId = "anime_movies"), animeOnly)
    )

    val withExplicit = animeOnly + "streaming_crunchyroll_movies"
    assertFalse(
      "Con un catalogo Crunchyroll esplicito il catalogo generico anime resta fuori",
      ProviderCatalogSourcePolicy.matchesProvider(crunchyroll, target(catalogId = "anime_movies"), withExplicit)
    )
    assertTrue(
      "Il catalogo Crunchyroll esplicito resta agganciato",
      ProviderCatalogSourcePolicy.matchesProvider(crunchyroll, target(catalogId = "streaming_crunchyroll_movies"), withExplicit)
    )
  }
}