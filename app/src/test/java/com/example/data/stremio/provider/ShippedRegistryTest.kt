package com.example.data.stremio.provider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.stremio.TYPE_MOVIE
import com.example.data.stremio.TYPE_SERIES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Test del parsing della registry effettivamente bundlata in
 * `res/raw/stremio_provider_bindings.json`.
 *
 * A differenza di [RegistryAutoBindingTest], che lavora su una fixture sintetica,
 * qui il file letto è quello che l'app installa davvero: se il JSON reale dovesse
 * diventare illeggibile o contenere voci incomplete, questi test falliscono.
 *
 * La chiave dichiarata è SEMPRE `addonManifestId + type + catalogId`; nessuna voce
 * usa il solo `catalogId`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ShippedRegistryTest {

  private fun shipped(): ProviderBindingRegistryData =
    ProviderBindingRegistry.load(ApplicationProvider.getApplicationContext<Context>())

  private fun resolverOf(user: List<ProviderBinding> = emptyList()) = DefaultProviderCatalogResolver(
    userBindings = InMemoryProviderBindingSource(user),
    registry = shipped(),
    proposer = HeuristicProviderCatalogProposer()
  )

  // ── Parsing del file reale ──────────────────────────────────────────────────

  @Test
  fun laRegistryBundlataSiCaricaCon17Voci() {
    val registry = shipped()

    assertEquals(ProviderBindingRegistry.CURRENT_VERSION, registry.version)
    assertEquals(17, registry.all().size)
  }

  @Test
  fun ogniVoceHaChaveCompletaEDuSorgenteAttese() {
    shipped().all().forEach { binding ->
      assertEquals("Sorgente non REGISTRY", BindingSource.REGISTRY, binding.source)
      assertEquals("Confidence non CONFIRMED", BindingConfidence.CONFIRMED, binding.confidence)
      assertTrue(binding.isDeclarative)

      val key = binding.key
      assertEquals(
        "La chiave deve portare l'intero manifest id",
        "app.xperience.60613fcd-0780-420d-a736-59c46cfa7ff9",
        key.normalizedAddonId
      )
      assertFalse("type non puo' essere vuoto", key.stremioType.isBlank())
      assertFalse("catalogId non puo' essere vuoto", key.normalizedCatalogId.isBlank())
      assertTrue(
        "type deve essere movie o series, era '${key.stremioType}'",
        key.stremioType == TYPE_MOVIE || key.stremioType == TYPE_SERIES
      )
      assertEquals(
        "stableId deve essere addon|type|catalogId",
        "${key.normalizedAddonId}|${key.stremioType}|${key.normalizedCatalogId}",
        key.stableId
      )
    }
  }

  @Test
  fun leChiaviSonoUnivoche() {
    val ids = shipped().all().map { it.key.stableId }

    assertEquals("Chiave duplicata", ids.size, ids.toSet().size)
  }

  // ── Copertura dichiarata ────────────────────────────────────────────────────

  @Test
  fun laRegistryDichiaraSoloIProviderReali() {
    val allowed = setOf("apple", "disney", "hbo", "netflix")

    shipped().all().forEach { binding ->
      assertTrue(
        "providerId inatteso: ${binding.providerId}",
        allowed.contains(binding.normalizedProviderId)
      )
    }
  }

  @Test
  fun iCataloghiProviderSpecificiSonoAssociati() {
    val resolver = resolverOf()

    assertNotNull(resolver.resolve(CatalogKey(XPERIENCE, TYPE_MOVIE, "streaming_apple_movies")))
    assertNotNull(resolver.resolve(CatalogKey(XPERIENCE, TYPE_SERIES, "streaming_apple_series")))
    assertNotNull(resolver.resolve(CatalogKey(XPERIENCE, TYPE_SERIES, "streaming_apple_originals_series")))
    assertNotNull(resolver.resolve(CatalogKey(XPERIENCE, TYPE_MOVIE, "snoak_apple_top10_movies")))
    assertNotNull(resolver.resolve(CatalogKey(XPERIENCE, TYPE_SERIES, "snoak_apple_top10_series")))

    assertNotNull(resolver.resolve(CatalogKey(XPERIENCE, TYPE_MOVIE, "streaming_disney_movies")))
    assertNotNull(resolver.resolve(CatalogKey(XPERIENCE, TYPE_SERIES, "streaming_disney_series")))
    assertNotNull(resolver.resolve(CatalogKey(XPERIENCE, TYPE_SERIES, "streaming_disney_originals_series")))
    assertNotNull(resolver.resolve(CatalogKey(XPERIENCE, TYPE_MOVIE, "snoak_disney_top10_movies")))
    assertNotNull(resolver.resolve(CatalogKey(XPERIENCE, TYPE_SERIES, "snoak_disney_top10_series")))

    assertNotNull(resolver.resolve(CatalogKey(XPERIENCE, TYPE_MOVIE, "streaming_hbo_movies")))
    assertNotNull(resolver.resolve(CatalogKey(XPERIENCE, TYPE_SERIES, "streaming_hbo_series")))
    assertNotNull(resolver.resolve(CatalogKey(XPERIENCE, TYPE_SERIES, "streaming_hbo_originals_series")))
    assertNotNull(resolver.resolve(CatalogKey(XPERIENCE, TYPE_MOVIE, "snoak_hbo_top10_movies")))
    assertNotNull(resolver.resolve(CatalogKey(XPERIENCE, TYPE_SERIES, "snoak_hbo_top10_series")))

    assertNotNull(resolver.resolve(CatalogKey(XPERIENCE, TYPE_MOVIE, "snoak_netflix_top10_movies")))
    assertNotNull(resolver.resolve(CatalogKey(XPERIENCE, TYPE_SERIES, "snoak_netflix_top10_series")))
  }

  @Test
  fun ilTypeSeparaFilmESerie() {
    val resolver = resolverOf()

    // movie/streaming_apple_movies e' dichiarato: l'omologo series non esiste nel
    // manifest, quindi la chiave series non puo' essere risolta dal solo binding movie.
    assertNotNull(resolver.resolve(CatalogKey(XPERIENCE, TYPE_MOVIE, "streaming_apple_movies")))
    assertNull(resolver.resolve(CatalogKey(XPERIENCE, TYPE_SERIES, "streaming_apple_movies")))
  }

  // ── Cataloghi che NON devono essere associati ───────────────────────────────

  @Test
  fun nessunCatalogoGenericoENelRegistry() {
    val resolver = resolverOf()

    val generic = listOf(
      "recs_movies_for_you" to TYPE_MOVIE,
      "recs_series_for_you" to TYPE_SERIES,
      "trending_movies" to TYPE_MOVIE,
      "trending_most_popular_top20_movies" to TYPE_MOVIE,
      "snoak_top100_movies" to TYPE_MOVIE,
      "now_playing_movies" to TYPE_MOVIE,
      "upcoming_movies" to TYPE_MOVIE,
      "new_latest_releases_movies" to TYPE_MOVIE,
      "new_latest_digital_movies" to TYPE_MOVIE,
      "on_the_air_series" to TYPE_SERIES,
      "airing_today_series" to TYPE_SERIES,
      "world_it_latest_movies" to TYPE_MOVIE,
      "world_it_latest_series" to TYPE_SERIES,
      "anime_toprated_movies" to TYPE_MOVIE,
      "anime_toprated_series" to TYPE_SERIES,
      "discover_all_movies" to TYPE_MOVIE,
      "discover_all_series" to TYPE_SERIES,
      "themed_superhero" to TYPE_MOVIE,
      "studio_a24_movies" to TYPE_MOVIE,
      "studio_marvel_movies" to TYPE_MOVIE,
      "genre_action_movies" to TYPE_MOVIE,
      "genre_scifi_movies" to TYPE_MOVIE,
      "xperience.search" to TYPE_MOVIE,
      "xperience.search.ai" to TYPE_MOVIE,
      "xperience.search.anime" to TYPE_SERIES
    )

    generic.forEach { (catalogId, type) ->
      assertNull(
        "Catalogo generico/ambiguo non deve essere nel registry: $type/$catalogId",
        resolver.resolve(CatalogKey(XPERIENCE, type, catalogId))
      )
    }
  }

  @Test
  fun leCataloghiNonPresentiNelManifestNonHannoBinding() {
    val resolver = resolverOf()

    // Prime Video, Paramount+ e Crunchyroll non hanno alcun catalogo nel manifest:
    // nessuna voce di registry li puo' dichiarare.
    shipped().all().forEach { binding ->
      val provider = binding.normalizedProviderId
      assertFalse(
        "Catalogo mai verificato: ${binding.key.stableId}",
        provider == "prime" || provider == "paramount" || provider == "crunchyroll"
      )
    }

    assertNull(resolver.resolve(CatalogKey(XPERIENCE, TYPE_MOVIE, "streaming_prime_movies")))
    assertNull(resolver.resolve(CatalogKey(XPERIENCE, TYPE_SERIES, "streaming_paramount_series")))
    assertNull(resolver.resolve(CatalogKey(XPERIENCE, TYPE_SERIES, "crunchyroll_series")))
  }

  @Test
  fun unManifestIdDiversoNonEreditaNessunBinding() {
    val resolver = resolverOf()

    assertNull(resolver.resolve(CatalogKey("altro.addon", TYPE_MOVIE, "streaming_apple_movies")))
  }

  // ── Precedenza USER e non-mutazione del registry ────────────────────────────

  @Test
  fun userBindingPrevaleSuQuellaDiRegistry() {
    val key = CatalogKey(XPERIENCE, TYPE_MOVIE, "streaming_apple_movies")
    val user = ProviderBinding(
      key = key,
      providerId = "netflix",
      source = BindingSource.USER,
      confidence = BindingConfidence.CONFIRMED
    )

    val resolved = resolverOf(listOf(user)).resolve(key)

    assertNotNull(resolved)
    assertEquals(BindingSource.USER, resolved!!.source)
    assertEquals("netflix", resolved.providerId)
  }

  @Test
  fun senzaUserBindingRestaQuellaDiRegistry() {
    val key = CatalogKey(XPERIENCE, TYPE_MOVIE, "streaming_apple_movies")

    val resolved = resolverOf().resolve(key)

    assertNotNull(resolved)
    assertEquals(BindingSource.REGISTRY, resolved!!.source)
    assertEquals(BindingConfidence.CONFIRMED, resolved.confidence)
    assertEquals("apple", resolved.providerId)
  }

  @Test
  fun rimuovereUnaUserBindingNonModificaLaRegistryBundlata() {
    val key = CatalogKey(XPERIENCE, TYPE_MOVIE, "streaming_apple_movies")
    val user = ProviderBinding(
      key = key,
      providerId = "netflix",
      source = BindingSource.USER,
      confidence = BindingConfidence.CONFIRMED
    )

    val outcome = ProviderBindingConfirmation.remove(listOf(user), key, "netflix")
    val afterResolver = resolverOf(outcome.bindings)

    assertEquals(BindingEditOutcome.REMOVED, outcome.outcome)
    assertTrue("Il registry non viene toccato dalla rimozione", outcome.bindings.isEmpty())

    // Il registry resta immutato: stessa voce, stesso provider, stessa sorgente.
    val fromRegistry = afterResolver.resolve(key)
    assertEquals(BindingSource.REGISTRY, fromRegistry?.source)
    assertEquals("apple", fromRegistry?.providerId)
    assertEquals(17, shipped().all().size)
  }

  companion object {
    /** Manifest id dell'addon Xperience verificato sul manifest reale. */
    private const val XPERIENCE = "app.xperience.60613fcd-0780-420d-a736-59c46cfa7ff9"
  }
}
