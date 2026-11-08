package com.example.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.CatalogSection
import com.example.data.repository.HomeCatalogs
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guardia sulla STABILITÀ Compose dei modelli usati dalle card e dalle liste.
 *
 * Con lo "strong skipping" del compiler Compose (attivo in Kotlin 2.2.x) un parametro
 * dichiarato `unstable` viene confrontato per IDENTITÀ (`===`), uno `stable` per
 * `equals()`. Questi test fissano la conseguenza osservabile, l'unica che conta per
 * l'utente: un modello NUOVO ma con gli STESSI dati non deve ricomporre il consumatore,
 * mentre un modello con dati DIVERSI deve ricomporlo.
 *
 * Se si rimuove `@Immutable` da uno di questi tipi, il compiler torna a considerarlo
 * instabile e il test relativo fallisce (assenza di salto = ricomposizione evitabile
 * tornata a carico della UI).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class ModelStabilityRecompositionTest {

  @get:Rule
  val rule = createComposeRule()

  /** Contatore fuori dalla composizione: scriverlo qui non genera ricomposizioni. */
  private class CompositionCounter {
    var count = 0
  }

  @Test
  fun mediaItemUgualeMaNuovaIstanzaSaltaLaRicomposizione() {
    val counter = CompositionCounter()
    val media = sampleMedia()
    var revision = mutableIntStateOf(0)

    rule.setContent {
      val current = revision.intValue
      val mediaPerLaCard = when (current) {
        0 -> media
        1 -> media.copy()
        else -> media.copy(title = "Titolo diverso")
      }
      MediaItemProbe(media = mediaPerLaCard, counter = counter)
    }
    rule.waitForIdle()
    val baseline = counter.count
    assertEquals("Il consumatore deve essere composto almeno una volta", 1, baseline)

    // Nuova istanza, stesso contenuto: nessuna ricomposizione attesa.
    revision.intValue = 1
    rule.waitForIdle()
    assertEquals(
      "Un MediaItem nuovo ma uguale non deve ricomporre la card",
      baseline,
      counter.count
    )

    // Contenuto realmente diverso: la ricomposizione DEVE avvenire.
    revision.intValue = 2
    rule.waitForIdle()
    assertEquals(
      "Un MediaItem con dati diversi deve ricomporre la card",
      baseline + 1,
      counter.count
    )
  }

  @Test
  fun homeCatalogsUgualiMaNuovaIstanzaSaltanoLaRicomposizione() {
    val counter = CompositionCounter()
    val catalogs = sampleCatalogs()
    var revision = mutableIntStateOf(0)

    rule.setContent {
      val current = revision.intValue
      val catalogsPerLaHome = when (current) {
        0 -> catalogs
        // Liste ricostruite (istanze nuove, contenuto identico).
        1 -> catalogs.copy(
          heroItems = catalogs.heroItems.toList(),
          extraSections = catalogs.extraSections.map { it.copy(items = it.items.toList()) }
        )
        else -> catalogs.copy(heroItems = catalogs.heroItems.drop(1))
      }
      HomeCatalogsProbe(catalogs = catalogsPerLaHome, counter = counter)
    }
    rule.waitForIdle()
    val baseline = counter.count

    revision.intValue = 1
    rule.waitForIdle()
    assertEquals(
      "HomeCatalogs ricostruita con lo stesso contenuto non deve ricomporre la Home",
      baseline,
      counter.count
    )

    revision.intValue = 2
    rule.waitForIdle()
    assertEquals(
      "HomeCatalogs con contenuto diverso deve ricomporre la Home",
      baseline + 1,
      counter.count
    )
  }

  @Test
  fun catalogSectionUgualeMaNuovaIstanzaSaltaLaRicomposizione() {
    val counter = CompositionCounter()
    val section = sampleCatalogs().extraSections.first()
    var revision = mutableIntStateOf(0)

    rule.setContent {
      val current = revision.intValue
      val sectionPerLaRiga = when (current) {
        0 -> section
        1 -> section.copy(items = section.items.toList(), rawItems = section.rawItems.toList())
        else -> section.copy(title = "Titolo sezione diverso")
      }
      CatalogSectionProbe(section = sectionPerLaRiga, counter = counter)
    }
    rule.waitForIdle()
    val baseline = counter.count

    revision.intValue = 1
    rule.waitForIdle()
    assertEquals(baseline, counter.count)

    revision.intValue = 2
    rule.waitForIdle()
    assertEquals(baseline + 1, counter.count)
  }

  @Composable
  private fun MediaItemProbe(media: MediaItem, counter: CompositionCounter) {
    counter.count++
    Text(text = media.title)
  }

  @Composable
  private fun HomeCatalogsProbe(catalogs: HomeCatalogs, counter: CompositionCounter) {
    counter.count++
    Text(text = "hero=${catalogs.heroItems.size}")
  }

  @Composable
  private fun CatalogSectionProbe(section: CatalogSection, counter: CompositionCounter) {
    counter.count++
    Text(text = section.title)
  }

  private fun sampleMedia(title: String = "Dune") = MediaItem(
    id = "tmdb_movie_438631",
    title = title,
    synopsis = "Sinossi",
    videoUrl = "https://example.org/dune.m3u8",
    type = MediaType.FILM,
    tmdbId = 438631,
    genres = listOf("Fantascienza"),
    cast = listOf("Timothée Chalamet"),
    episodes = listOf(
      Episode(
        id = "tmdb_movie_438631_s1e1",
        seasonNumber = 1,
        episodeNumber = 1,
        title = "Episodio",
        synopsis = "",
        durationMinutes = 45,
        videoUrl = ""
      )
    )
  )

  private fun sampleCatalogs(): HomeCatalogs {
    val first = sampleMedia("Dune")
    val second = sampleMedia("Dune - Parte Due")
    return HomeCatalogs(
      heroItems = listOf(first, second),
      trendingMovies = listOf(first),
      extraSections = listOf(
        CatalogSection(
          id = "netflix_movies",
          title = "Novità Netflix",
          items = listOf(first, second)
        )
      ),
      allMovies = listOf(first, second)
    )
  }
}
