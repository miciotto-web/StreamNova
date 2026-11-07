package com.example.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.model.WatchedEpisodePoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Lettura del punto più avanzato della visione per titolo.
 *
 * È la base dell'opzione «Prossimo episodio dall'ultimo raggiunto»: deve scegliere la
 * coppia stagione/episodio più alta con un progresso registrato, ignorare le altre
 * serie e non inventare nulla quando non esiste alcun progresso.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MediaRepositoryFurthestEpisodeTest {

  /**
   * Il repository è un singleton condiviso dal sandbox Robolectric: il caricamento
   * asincrono dell'Application può arrivare in ritardo. Il reset (che incrementa la
   * generazione dello stato) impedisce a un load in volo di svuotare i progressi scritti
   * da questo test; l'@After non lascia dati alle classi successive.
   */
  @Before
  fun resetRepository() {
    MediaRepository.setDatabaseForTesting(null)
  }

  @After
  fun clearRepository() {
    MediaRepository.setDatabaseForTesting(null)
  }

  @Test
  fun sceglieLaCoppiaStagioneEpisodioPiuAlta() {
    MediaRepository.updateEpisodeProgress("furthest_media", 1, 9, 20_000L, 50_000L)
    MediaRepository.updateEpisodeProgress("furthest_media", 2, 1, 10_000L, 50_000L)
    MediaRepository.updateEpisodeProgress("furthest_media", 10, 2, 5_000L, 50_000L)
    MediaRepository.updateEpisodeProgress("altra_serie", 99, 99, 40_000L, 50_000L)

    assertEquals(
      WatchedEpisodePoint(10, 2, 5_000L, 50_000L),
      MediaRepository.furthestWatchedEpisode("furthest_media")
    )
  }

  @Test
  fun ignoraLePuntateSenzaProgresso() {
    MediaRepository.updateEpisodeProgress("zero_progress_media", 1, 1, 0L, 50_000L)
    assertNull(MediaRepository.furthestWatchedEpisode("zero_progress_media"))
  }

  @Test
  fun unTitoloSconosciutoNonHaPuntoDiRipresa() {
    assertNull(MediaRepository.furthestWatchedEpisode("titolo_mai_visto"))
    assertNull(MediaRepository.furthestWatchedEpisode(""))
  }
}
