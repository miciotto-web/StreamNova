package com.example.data.util

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.NextEpisodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deduplicazione delle richieste TMDB per il badge "Prossima puntata" (Fase 5).
 *
 * [NextEpisodeEnricher] è il cancello che impedisce a ogni ricomposizione della Home di
 * riavviare il recupero: pendingTmdbIds restituisce una serie al massimo una volta per
 * intervallo, e il registro riapplica il dato alle ri-emissioni della lista.
 */
class NextEpisodeEnricherTest {

  private var now = 1_000_000L
  private val interval = NextEpisodeEnricher.DEFAULT_REFRESH_INTERVAL_MS

  private fun enricher() = NextEpisodeEnricher(refreshIntervalMs = interval, clock = { now })

  private fun series(tmdbId: Int, id: String = "tmdb_tv_$tmdbId") = MediaItem(
    id = id,
    title = "Serie $tmdbId",
    synopsis = "",
    videoUrl = "",
    type = MediaType.SERIE_TV,
    tmdbId = tmdbId,
    currentProgressMs = 60_000L
  )

  // 10. Nessuna richiesta duplicata durante le ricomposizioni.
  @Test
  fun `series is requested once across repeated home recompositions`() {
    val enricher = enricher()
    val cards = listOf(series(100), series(200))

    val first = enricher.pendingTmdbIds(cards)
    first.forEach { enricher.markRequested(it) }

    // La Home si ricompone più volte con la stessa lista.
    repeat(5) {
      assertTrue(enricher.pendingTmdbIds(cards).isEmpty())
    }
    assertEquals(listOf(100, 200), first)
  }

  @Test
  fun `same series repeated in the row produces a single request`() {
    val enricher = enricher()
    val duplicate = series(100, id = "tmdb_tv_100")

    val pending = enricher.pendingTmdbIds(listOf(duplicate, duplicate.copy(id = "other_100")))

    assertEquals(listOf(100), pending)
  }

  @Test
  fun `films and items without tmdb id are never requested`() {
    val enricher = enricher()
    val movie = series(100).copy(type = MediaType.FILM)
    val noTmdb = series(100).copy(tmdbId = null)
    val invalidTmdb = series(0)

    assertTrue(enricher.pendingTmdbIds(listOf(movie, noTmdb, invalidTmdb)).isEmpty())
  }

  @Test
  fun `remembered result is applied to the card`() {
    val enricher = enricher()
    val card = series(100)

    enricher.remember(100, "Returning Series", NextEpisodeInfo(2, 5, "2026-11-12"))

    val enriched = enricher.applyTo(card)
    assertEquals("Returning Series", enriched.tvStatus)
    assertEquals(NextEpisodeInfo(2, 5, "2026-11-12"), enriched.nextEpisode)
  }

  @Test
  fun `absent next episode is remembered without inventing a date`() {
    val enricher = enricher()
    val card = series(100)

    enricher.remember(100, "Returning Series", null)

    assertNull(enricher.applyTo(card).nextEpisode)
    // La series non viene richiesta di nuovo a ogni ricomposizione.
    assertTrue(enricher.pendingTmdbIds(listOf(card)).isEmpty())
  }

  // 8. Quando TMDB aggiorna la prossima puntata, il badge mostra la NUOVA data.
  @Test
  fun `updated tmdb date replaces the previous one after the refresh interval`() {
    val enricher = enricher()
    val card = series(100)

    enricher.remember(100, "Returning Series", NextEpisodeInfo(2, 5, "2026-11-12"))
    assertEquals("2026-11-12", enricher.applyTo(card).nextEpisode?.airDate)

    // Cache dettagli TMDB ancora fresca: nessuna richiesta.
    assertTrue(enricher.pendingTmdbIds(listOf(card)).isEmpty())

    // Trascorso il TTL, la serie viene richiesta di nuovo e il nuovo dato sostituisce il vecchio.
    now += interval
    assertEquals(listOf(100), enricher.pendingTmdbIds(listOf(card)))

    enricher.remember(100, "Returning Series", NextEpisodeInfo(2, 6, "2026-11-19"))
    val updated = enricher.applyTo(card)
    assertEquals(NextEpisodeInfo(2, 6, "2026-11-19"), updated.nextEpisode)
  }

  @Test
  fun `status change to ended removes the badge on next refresh`() {
    val enricher = enricher()
    val card = series(100)

    enricher.remember(100, "Returning Series", NextEpisodeInfo(2, 5, "2026-11-12"))
    assertTrue(enricher.applyTo(card).nextEpisode != null)

    now += interval
    enricher.remember(100, "Ended", null)

    val updated = enricher.applyTo(card)
    assertEquals("Ended", updated.tvStatus)
    assertNull(updated.nextEpisode)
  }

  @Test
  fun `failed request can be retried`() {
    val enricher = enricher()
    val card = series(100)

    enricher.markRequested(100)
    assertTrue(enricher.pendingTmdbIds(listOf(card)).isEmpty())

    // Rete assente: la marca viene annullata e la serie è di nuovo richiedibile.
    enricher.forgetRequest(100)
    assertEquals(listOf(100), enricher.pendingTmdbIds(listOf(card)))
  }

  @Test
  fun `in memory data survives room re-emission via applyTo list`() {
    val enricher = enricher()
    val cards = listOf(series(100), series(200))
    enricher.remember(100, "Returning Series", NextEpisodeInfo(1, 1, "2026-12-01"))

    // Il Flow Room ricostruisce le card dalle entità: il dato viene riapplicato.
    val remapped = cards.map { it.copy(tvStatus = null, nextEpisode = null) }
    val enriched = enricher.applyTo(remapped)

    assertEquals(NextEpisodeInfo(1, 1, "2026-12-01"), enriched[0].nextEpisode)
    assertNull(enriched[1].nextEpisode)
  }

  @Test
  fun `clear drops every remembered value`() {
    val enricher = enricher()
    val card = series(100)
    enricher.remember(100, "Returning Series", NextEpisodeInfo(2, 5, "2026-11-12"))

    enricher.clear()

    assertNull(enricher.applyTo(card).nextEpisode)
    assertEquals(listOf(100), enricher.pendingTmdbIds(listOf(card)))
  }
}
