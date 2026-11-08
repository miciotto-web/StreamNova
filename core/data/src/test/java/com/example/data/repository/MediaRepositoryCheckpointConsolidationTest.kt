package com.example.data.repository

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Equivalenza dei checkpoint Room.
 *
 * Il refresh TMDB persiste il catalogo a blocchi (checkpoint). Raggruppare più
 * blocchi in un unico checkpoint deve produrre ESATTAMENTE lo stesso catalogo di
 * una persistenza frazionata: stessi titoli, stesso ordine (rating decrescente),
 * nessun duplicato e stato utente preservato.
 *
 * La funzione di merge è pura: qui non serve Room né rete.
 */
class MediaRepositoryCheckpointConsolidationTest {

  private fun item(
    id: String,
    tmdbId: Int?,
    rating: Float,
    type: MediaType = MediaType.FILM,
    provider: String? = null,
    isFavorite: Boolean = false,
    progressMs: Long = 0L,
    synopsis: String = "Sinossi $id"
  ): MediaItem = MediaItem(
    id = id,
    tmdbId = tmdbId,
    title = id,
    synopsis = synopsis,
    videoUrl = "https://example.test/$id.mp4",
    type = type,
    rating = rating,
    provider = provider,
    isFavorite = isFavorite,
    currentProgressMs = progressMs
  )

  @Test
  fun `un solo checkpoint e checkpoint multipli producono lo stesso catalogo`() {
    val a = item("tmdb_m_1", 1, rating = 9.0f)
    val b = item("tmdb_m_2", 2, rating = 7.0f)
    val c = item("tmdb_tv_3", 3, rating = 8.0f, type = MediaType.SERIE_TV)

    val allFetched = linkedMapOf(a.id to a, b.id to b, c.id to c)
    val oneShot = MediaRepository.mergeCatalogForCheckpoint(
      currentList = emptyList(),
      fetchedItems = allFetched,
      initialItems = emptyList()
    )

    // Simulazione della persistenza a blocchi: il fetchedItems SI ACCUMULA, come
    // nel refresh reale, mentre currentList è lo snapshot letto all'inizio.
    val afterFirst = MediaRepository.mergeCatalogForCheckpoint(
      currentList = emptyList(),
      fetchedItems = linkedMapOf(a.id to a),
      initialItems = emptyList()
    )
    assertEquals(listOf(a.id), afterFirst.map { it.id })

    MediaRepository.mergeCatalogForCheckpoint(
      currentList = emptyList(),
      fetchedItems = linkedMapOf(a.id to a, b.id to b),
      initialItems = emptyList()
    )
    val afterAll = MediaRepository.mergeCatalogForCheckpoint(
      currentList = emptyList(),
      fetchedItems = allFetched,
      initialItems = emptyList()
    )

    assertEquals("stessi titoli, stessa deduplicazione, stesso ordine", oneShot, afterAll)
    assertEquals(listOf(a.id, c.id, b.id), afterAll.map { it.id })
  }

  @Test
  fun `film e serie con lo stesso tmdb id restano separati`() {
    val movie = item("tmdb_m_7", 7, rating = 8.0f, type = MediaType.FILM)
    val series = item("tmdb_tv_7", 7, rating = 8.5f, type = MediaType.SERIE_TV)

    val merged = MediaRepository.mergeCatalogForCheckpoint(
      currentList = emptyList(),
      fetchedItems = linkedMapOf(movie.id to movie, series.id to series),
      initialItems = emptyList()
    )

    assertEquals(2, merged.size)
    assertEquals(setOf("tmdb_m_7", "tmdb_tv_7"), merged.map { it.id }.toSet())
  }

  @Test
  fun `il checkpoint preserva preferiti e progressi e aggiorna i dati remoti`() {
    val previous = item(
      "tmdb_m_42",
      42,
      rating = 5.0f,
      isFavorite = true,
      progressMs = 120_000L,
      synopsis = "breve"
    )
    val remote = item(
      "tmdb_m_42",
      42,
      rating = 8.5f,
      provider = "netflix",
      synopsis = "sinossi remota molto più lunga"
    )

    val merged = MediaRepository.mergeCatalogForCheckpoint(
      currentList = listOf(previous),
      fetchedItems = linkedMapOf(remote.id to remote),
      initialItems = emptyList()
    ).single()

    assertEquals("tmdb_m_42", merged.id)
    assertTrue("il preferito non si perde", merged.isFavorite)
    assertEquals("il progresso non si perde", 120_000L, merged.currentProgressMs)
    assertEquals("i dati remoti aggiornano il catalogo", 8.5f, merged.rating, 0.001f)
    assertEquals("netflix", merged.provider)
    assertEquals("sinossi remota molto più lunga", merged.synopsis)
  }

  @Test
  fun `i titoli senza titolo non entrano nel catalogo persistito`() {
    val valid = item("tmdb_m_1", 1, rating = 8.0f).copy(title = "Titolo valido")
    val blank = item("tmdb_m_2", 2, rating = 7.0f).copy(title = "   ")

    val merged = MediaRepository.mergeCatalogForCheckpoint(
      currentList = emptyList(),
      fetchedItems = linkedMapOf(valid.id to valid, blank.id to blank),
      initialItems = emptyList()
    )

    assertEquals(listOf(valid.id), merged.map { it.id })
  }
}
