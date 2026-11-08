package com.example.data.repository

import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.prefs.ContinueWatchingLayout
import com.example.data.prefs.ContinueWatchingPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Miniature degli episodi in «Continua a guardare».
 *
 * Con la preferenza `useEpisodeThumbnails` attiva la card deve mostrare la **still**
 * (`still_path`) dell'episodio di ripresa (stagione/episodio), non il poster/backdrop
 * della serie, con il fallback previsto quando la still non è disponibile. Le altre
 * regole della riga (progresso, blur, episodio più avanzato) restano invariate.
 */
class ContinueWatchingRowPolicyEpisodeThumbnailTest {

  private companion object {
    const val SERIES_ID = "tmdb_tv_1668" // Friends
    const val SERIES_BACKDROP = "https://image.tmdb.org/t/p/w1280/backdrop_serie.jpg"
    const val SERIES_POSTER = "https://image.tmdb.org/t/p/w500/poster_serie.jpg"
    const val STILL_S01E01 = "https://image.tmdb.org/t/p/w300/still_s1e1.jpg"
    const val STILL_S02E03 = "https://image.tmdb.org/t/p/w300/still_s2e3.jpg"

    /** Serie con episodi del generatore locale: thumbnail = backdrop/poster della serie. */
    fun baseSeries(): MediaItem {
      val episodes = MediaRepository.generateEpisodesForTv(
        tvId = 1668,
        tvTitle = "Friends",
        backdropUrl = SERIES_BACKDROP,
        posterUrl = SERIES_POSTER,
        seasons = 2
      )
      return MediaItem(
        id = SERIES_ID,
        title = "Friends",
        synopsis = "",
        videoUrl = "",
        backdropUrl = SERIES_BACKDROP,
        posterUrl = SERIES_POSTER,
        type = MediaType.SERIE_TV,
        year = 1994,
        durationMinutes = 22,
        lastWatchedSeason = 1,
        lastWatchedEpisode = 1,
        currentProgressMs = 180_000L,
        totalDurationMs = 22 * 60 * 1_000L,
        episodes = episodes
      )
    }

    /** Episodio con la still TMDB, come esce da `SeasonItem` + `EpisodeItem.toEpisode`. */
    fun episode(season: Int, ep: Int, stillUrl: String?): Episode = Episode(
      id = "${SERIES_ID}_s${season}e$ep",
      seasonNumber = season,
      episodeNumber = ep,
      title = "Episodio $ep",
      synopsis = "",
      durationMinutes = 22,
      thumbnailUrl = stillUrl,
      videoUrl = ""
    )

    /**
     * Sostituisce (o aggiunge) l'episodio [season]/[ep] con la versione che ha la still:
     * `findEpisode` usa il PRIMO episodio con quella numerazione, quindi un duplicato
     * accodato verrebbe ignorato.
     */
    fun MediaItem.withEpisode(season: Int, ep: Int, stillUrl: String?): MediaItem = copy(
      episodes = episodes.filterNot { it.seasonNumber == season && it.episodeNumber == ep } +
        episode(season, ep, stillUrl)
    )

    fun prefs(
      layout: ContinueWatchingLayout = ContinueWatchingLayout.CARD,
      useEpisodeThumbnails: Boolean = true,
      blurUnwatched: Boolean = false
    ) = ContinueWatchingPreferences(
      showRow = true,
      layout = layout,
      useEpisodeThumbnails = useEpisodeThumbnails,
      blurUnwatched = blurUnwatched
    )
  }

  // ── Preferenza ON + still disponibile ───────────────────────────────────────

  @Test
  fun `ON + still dell'episodio → la card usa la still, non il poster della serie`() {
    val series = baseSeries()
      .copy(lastWatchedSeason = 1, lastWatchedEpisode = 1)
      .withEpisode(1, 1, STILL_S01E01)

    val result = ContinueWatchingRowPolicy.build(
      items = listOf(series),
      preferences = prefs(layout = ContinueWatchingLayout.CARD)
    )

    assertEquals("una card", 1, result.size)
    assertEquals("still S1E1 (non backdrop/poster)", STILL_S01E01, result[0].artworkUrl)
  }

  @Test
  fun `ON + still dell'episodio → rispettata dai layout CARD, WIDE e POSTER`() {
    val series = baseSeries()
      .copy(lastWatchedSeason = 1, lastWatchedEpisode = 1)
      .withEpisode(1, 1, STILL_S01E01)

    ContinueWatchingLayout.values().forEach { layout ->
      val result = ContinueWatchingRowPolicy.build(
        items = listOf(series),
        preferences = prefs(layout = layout)
      )
      assertEquals(
        "layout ${layout.name}: still dell'episodio",
        STILL_S01E01,
        result[0].artworkUrl
      )
    }
  }

  @Test
  fun `OFF → viene usata l'immagine di base del layout`() {
    val series = baseSeries()
      .copy(lastWatchedSeason = 1, lastWatchedEpisode = 1)
      .withEpisode(1, 1, STILL_S01E01)

    val card = ContinueWatchingRowPolicy.build(
      items = listOf(series),
      preferences = prefs(layout = ContinueWatchingLayout.CARD, useEpisodeThumbnails = false)
    )
    assertEquals("OFF CARD: backdrop", SERIES_BACKDROP, card[0].artworkUrl)

    val wide = ContinueWatchingRowPolicy.build(
      items = listOf(series),
      preferences = prefs(layout = ContinueWatchingLayout.WIDE, useEpisodeThumbnails = false)
    )
    assertEquals("OFF WIDE: backdrop", SERIES_BACKDROP, wide[0].artworkUrl)

    val poster = ContinueWatchingRowPolicy.build(
      items = listOf(series),
      preferences = prefs(layout = ContinueWatchingLayout.POSTER, useEpisodeThumbnails = false)
    )
    assertEquals("OFF POSTER: poster", SERIES_POSTER, poster[0].artworkUrl)
  }

  // ── Still assente → fallback ────────────────────────────────────────────────

  @Test
  fun `ON + still assente → fallback al backdrop della serie`() {
    val series = baseSeries()
      .copy(lastWatchedSeason = 1, lastWatchedEpisode = 1)
      .withEpisode(1, 1, null)

    val result = ContinueWatchingRowPolicy.build(
      items = listOf(series),
      preferences = prefs(layout = ContinueWatchingLayout.CARD)
    )

    assertEquals("fallback backdrop", SERIES_BACKDROP, result[0].artworkUrl)
  }

  @Test
  fun `ON + still assente e senza backdrop → fallback al poster`() {
    val series = baseSeries()
      .copy(backdropUrl = null, lastWatchedSeason = 1, lastWatchedEpisode = 1)
      .withEpisode(1, 1, null)

    val result = ContinueWatchingRowPolicy.build(
      items = listOf(series),
      preferences = prefs(layout = ContinueWatchingLayout.POSTER)
    )

    assertEquals("fallback poster", SERIES_POSTER, result[0].artworkUrl)
  }

  @Test
  fun `ON + episodio di ripresa non presente in elenco → fallback senza inventare immagini`() {
    val series = baseSeries().copy(
      lastWatchedSeason = 99,
      lastWatchedEpisode = 99
    )

    val result = ContinueWatchingRowPolicy.build(
      items = listOf(series),
      preferences = prefs(layout = ContinueWatchingLayout.CARD)
    )

    assertTrue("card presente", result.isNotEmpty())
    assertEquals("fallback backdrop", SERIES_BACKDROP, result[0].artworkUrl)
    assertEquals("ripresa preservata", 99, result[0].media.lastWatchedSeason)
    assertEquals("episodio preservato", 99, result[0].media.lastWatchedEpisode)
  }

  // ── Episodi diversi → thumbnail distinte ────────────────────────────────────

  @Test
  fun `episodi diversi della stessa serie → still distinte per ciascun episodio`() {
    val s2e3 = baseSeries()
      .copy(lastWatchedSeason = 2, lastWatchedEpisode = 3)
      .withEpisode(2, 3, STILL_S02E03)
    val s2e6 = baseSeries()
      .copy(lastWatchedSeason = 2, lastWatchedEpisode = 6)
      .withEpisode(2, 6, STILL_S01E01)

    val result = ContinueWatchingRowPolicy.build(
      items = listOf(s2e3, s2e6),
      preferences = prefs(layout = ContinueWatchingLayout.CARD)
    )

    assertEquals("due card", 2, result.size)
    val byEpisode = result.associateBy { it.media.lastWatchedEpisode }
    assertEquals("S2E3 → sua still", STILL_S02E03, byEpisode[3]?.artworkUrl)
    assertEquals("S2E6 → sua still", STILL_S01E01, byEpisode[6]?.artworkUrl)
  }

  // ── Cambio episodio di ripresa → aggiornamento immagine ────────────────────

  @Test
  fun `cambio episodio di ripresa → la thumbnail segue il nuovo episodio`() {
    val before = baseSeries()
      .copy(lastWatchedSeason = 1, lastWatchedEpisode = 1, currentProgressMs = 500_000L)
      .withEpisode(1, 1, STILL_S01E01)

    val beforeResult = ContinueWatchingRowPolicy.build(
      items = listOf(before),
      preferences = prefs()
    )
    assertEquals("prima: still S1E1", STILL_S01E01, beforeResult[0].artworkUrl)

    // Nuovo punto di ripresa salvato: S2E3 con still diversa.
    val after = before
      .copy(lastWatchedSeason = 2, lastWatchedEpisode = 3, currentProgressMs = 100_000L)
      .withEpisode(2, 3, STILL_S02E03)

    val afterResult = ContinueWatchingRowPolicy.build(
      items = listOf(after),
      preferences = prefs()
    )
    assertEquals("dopo: still S2E3", STILL_S02E03, afterResult[0].artworkUrl)
    assertEquals("dopo: stagione 2", 2, afterResult[0].media.lastWatchedSeason)
    assertEquals("dopo: episodio 3", 3, afterResult[0].media.lastWatchedEpisode)
  }

  // ── Merge con il modello locale (still TMDB contro fallback) ───────────────

  @Test
  fun `mergeEpisodes → la still importata prevale sul fallback locale e preserva il progresso`() {
    val local = episode(1, 1, SERIES_BACKDROP).copy(currentProgressMs = 120_000L)
    val fromTmdb = episode(1, 1, STILL_S01E01)

    val merged = MediaRepository.mergeEpisodes(listOf(local), listOf(fromTmdb))

    assertEquals("un episodio", 1, merged.size)
    assertEquals("still TMDB vince", STILL_S01E01, merged[0].thumbnailUrl)
    assertEquals("progresso locale preservato", 120_000L, merged[0].currentProgressMs)
  }

  @Test
  fun `mergeEpisodes → senza still in arrivo il fallback locale resta`() {
    val local = episode(1, 1, SERIES_BACKDROP).copy(currentProgressMs = 120_000L)
    val fromTmdb = episode(1, 1, null)

    val merged = MediaRepository.mergeEpisodes(listOf(local), listOf(fromTmdb))

    assertEquals("fallback conservato", SERIES_BACKDROP, merged[0].thumbnailUrl)
    assertEquals("progresso locale preservato", 120_000L, merged[0].currentProgressMs)
  }

  @Test
  fun `mergeEpisodes → episodi nuovi di stagioni diverse vengono aggiunti con la loro still`() {
    val s1 = episode(1, 1, STILL_S01E01)
    val s2 = episode(2, 3, STILL_S02E03)

    val merged = MediaRepository.mergeEpisodes(listOf(s1), listOf(s2))

    assertEquals("due episodi", 2, merged.size)
    assertEquals("S2E3 con still", STILL_S02E03, merged.first { it.seasonNumber == 2 }.thumbnailUrl)
  }

  // ── Film: comportamento attuale ─────────────────────────────────────────────

  @Test
  fun `film → la preferenza ON non introduce miniature episodiche`() {
    val film = MediaItem(
      id = "tmdb_m_27205",
      title = "Inception",
      synopsis = "",
      videoUrl = "",
      backdropUrl = SERIES_BACKDROP,
      posterUrl = SERIES_POSTER,
      type = MediaType.FILM,
      year = 2010,
      durationMinutes = 148,
      currentProgressMs = 3_600_000L,
      totalDurationMs = 148 * 60 * 1_000L
    )

    val card = ContinueWatchingRowPolicy.build(
      items = listOf(film),
      preferences = prefs(layout = ContinueWatchingLayout.CARD)
    )
    assertEquals("film CARD: backdrop", SERIES_BACKDROP, card[0].artworkUrl)

    val poster = ContinueWatchingRowPolicy.build(
      items = listOf(film),
      preferences = prefs(layout = ContinueWatchingLayout.POSTER)
    )
    assertEquals("film POSTER: poster", SERIES_POSTER, poster[0].artworkUrl)
    assertTrue("nessuna lista episodi", poster[0].media.episodes.isEmpty())
  }

  // ── Fallback TMDB = backdrop/poster: non è una miniatura ────────────────────

  @Test
  fun `ON + thumbnail uguale al backdrop (fallback TMDB) → Locandina usa la locandina`() {
    // `getSeasonDetails` ripiega sul backdrop della serie quando TMDB non espone lo still:
    // quel valore non è la miniatura della puntata e non deve sostituire l'immagine del
    // layout verticale.
    val series = baseSeries()
      .copy(lastWatchedSeason = 1, lastWatchedEpisode = 1)
      .withEpisode(1, 1, SERIES_BACKDROP)

    val poster = ContinueWatchingRowPolicy.build(
      items = listOf(series),
      preferences = prefs(layout = ContinueWatchingLayout.POSTER)
    )
    assertEquals("Locandina: poster della serie", SERIES_POSTER, poster[0].artworkUrl)

    val card = ContinueWatchingRowPolicy.build(
      items = listOf(series),
      preferences = prefs(layout = ContinueWatchingLayout.CARD)
    )
    assertEquals("Scheda: backdrop", SERIES_BACKDROP, card[0].artworkUrl)
  }

  @Test
  fun `ON + thumbnail uguale alla locandina → la Scheda usa il backdrop`() {
    val series = baseSeries()
      .copy(lastWatchedSeason = 1, lastWatchedEpisode = 1)
      .withEpisode(1, 1, SERIES_POSTER)

    val card = ContinueWatchingRowPolicy.build(
      items = listOf(series),
      preferences = prefs(layout = ContinueWatchingLayout.CARD)
    )
    assertEquals("Scheda: backdrop della serie", SERIES_BACKDROP, card[0].artworkUrl)

    val poster = ContinueWatchingRowPolicy.build(
      items = listOf(series),
      preferences = prefs(layout = ContinueWatchingLayout.POSTER)
    )
    assertEquals("Locandina: poster", SERIES_POSTER, poster[0].artworkUrl)
  }

  // ── Nessuna regressione sulle altre preferenze ──────────────────────────────

  @Test
  fun `ON non altera la sfocatura degli episodi non visti`() {
    val seen = baseSeries()
      .copy(lastWatchedSeason = 1, lastWatchedEpisode = 1, currentProgressMs = 22 * 60 * 1_000L)
      .withEpisode(1, 1, STILL_S01E01)
    val unwatched = baseSeries()
      .copy(lastWatchedSeason = 1, lastWatchedEpisode = 2, currentProgressMs = 0L)
      .withEpisode(1, 2, STILL_S02E03)

    val result = ContinueWatchingRowPolicy.build(
      items = listOf(seen, unwatched),
      preferences = prefs(layout = ContinueWatchingLayout.CARD, blurUnwatched = true)
    )

    val seenCard = result.first { it.media.lastWatchedEpisode == 1 }
    val unwatchedCard = result.first { it.media.lastWatchedEpisode == 2 }
    assertEquals("visto: still S1E1", STILL_S01E01, seenCard.artworkUrl)
    assertEquals("non visto: still S1E2", STILL_S02E03, unwatchedCard.artworkUrl)
    assertFalse("visto: nitido", seenCard.blurArtwork)
    assertTrue("non visto: sfocato", unwatchedCard.blurArtwork)
  }
}
