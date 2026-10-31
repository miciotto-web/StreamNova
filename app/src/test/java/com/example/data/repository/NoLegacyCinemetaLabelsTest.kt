package com.example.data.repository

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guardia contro la RICOMPARSA delle diciture legacy "Cinemeta".
 *
 * Causa radice della regressione: i badge delle righe native TMDB erano stringhe
 * hardcoded in `strings.xml` ("Popolari su Cinemeta", "In evidenza su Cinemeta")
 * ereditate dalla migrazione. Questo test blocca il ritorno della dicitura in
 * QUALSIASI risorsa XML (tutte le locale) e nelle etichette generate dal provider
 * nativo TMDB.
 *
 * Le sezioni che provengono DAVVERO dall'addon Cinemeta possono continuare a
 * mostrare il nome dall'addon (manifest), non da una risorsa: l'attribuzione è
 * governata da [CatalogSection.attributionAddonName], coperta da
 * [HomeCatalogTmdbSourceTest].
 */
class NoLegacyCinemetaLabelsTest {

  private fun resourceDir(): File {
    val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
    return candidates.firstOrNull { it.isDirectory }
      ?: error("Directory res non trovata. CWD=${File(".").absolutePath}")
  }

  @Test
  fun nessunaRisorsaXmlMenzionaCinemeta() {
    val resDir = resourceDir()
    val xmlFiles = resDir.walkTopDown()
      .filter { it.isFile && it.extension.equals("xml", ignoreCase = true) }
      .toList()
    assertTrue("Nessun file XML trovato in ${resDir.absolutePath}", xmlFiles.isNotEmpty())

    val offenders = xmlFiles
      .filter { file -> file.readText().contains("cinemeta", ignoreCase = true) }
      .map { it.relativeTo(resDir).path }
      .toList()

    assertTrue(
      "Diciture legacy 'Cinemeta' trovate nelle risorse: $offenders",
      offenders.isEmpty()
    )
  }

  @Test
  fun leEtichetteDelProviderNativoNonMenzionanoCinemeta() {
    val labelsByLanguage = listOf("it-IT", "en-US", "es-ES", "fr-FR", "de-DE")
      .associateWith { TmdbHomeCatalogProvider.labelsFor(it) }

    labelsByLanguage.forEach { (language, labels) ->
      val visibleStrings = with(labels) {
        listOf(
          popularMovies, topRatedMovies, newMovies,
          popularSeries, topRatedSeries, newSeries,
          onAirSeries, digitalNew, hboNew,
          netflixMoviesNew, netflixSeriesNew,
          disneyMoviesNew, disneySeriesNew,
          primeMoviesNew, primeSeriesNew,
          appleMoviesNew, appleSeriesNew,
          paramountMoviesNew, paramountSeriesNew
        )
      }
      assertFalse(
        "Etichetta nativa con 'Cinemeta' per $language: $visibleStrings",
        visibleStrings.any { it.contains("cinemeta", ignoreCase = true) }
      )
    }
  }
}
