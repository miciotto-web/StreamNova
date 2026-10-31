package com.example.ui.util

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.NextEpisodeInfo
import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regole del badge "Prossima puntata" (Fase 5): il badge esiste SOLO quando TMDB fornisce
 * dati completi e coerenti. Ogni caso della specifica ha qui la sua regressione, così
 * nessuna modifica futura può reintrodurre date stimate o mostrate per serie concluse.
 */
class NextEpisodeBadgeRulesTest {

  private fun date(value: String): java.util.Date =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }.parse(value)!!

  private fun series(
    status: String? = NextEpisodeBadgeRules.TV_STATUS_RETURNING,
    next: NextEpisodeInfo? = NextEpisodeInfo(seasonNumber = 2, episodeNumber = 5, airDate = "2026-11-12"),
    type: MediaType = MediaType.SERIE_TV,
    today: String = "2026-10-30",
    resumeSeason: Int? = 2
  ) = NextEpisodeBadgeRules.resolve(
    isTv = type == MediaType.SERIE_TV,
    status = status,
    nextEpisode = next,
    resumeSeason = resumeSeason,
    today = date(today)
  )

  // 1. Returning Series con prossima puntata valida -> badge visibile.
  @Test
  fun `returning series with valid next episode shows badge`() {
    val badge = series()

    assertNotNull(badge)
    assertEquals("12/11/2026", badge!!.airDateText)
    assertEquals("S02E05", badge.seasonEpisodeText)
  }

  // 2. Serie Ended -> badge assente.
  @Test
  fun `ended series hides badge`() {
    assertNull(series(status = "Ended"))
  }

  // 3. Serie Canceled -> badge assente.
  @Test
  fun `canceled series hides badge`() {
    assertNull(series(status = "Canceled"))
  }

  // 4. Film -> badge assente, anche se status e data fossero valorizzati.
  @Test
  fun `movie hides badge`() {
    assertNull(series(type = MediaType.FILM))
  }

  // 5. Nessuna prossima puntata restituita da TMDB -> badge assente.
  @Test
  fun `missing next episode hides badge`() {
    assertNull(series(next = null))
  }

  // 6a. Data nulla/vuota/non valida -> badge assente.
  @Test
  fun `blank air date hides badge`() {
    assertNull(series(next = NextEpisodeInfo(2, 5, "")))
    assertNull(series(next = NextEpisodeInfo(2, 5, "   ")))
  }

  @Test
  fun `unparseable air date hides badge`() {
    assertNull(series(next = NextEpisodeInfo(2, 5, "not-a-date")))
    assertNull(series(next = NextEpisodeInfo(2, 5, "12/11/2026")))
    assertNull(series(next = NextEpisodeInfo(2, 5, "2026-02-30")))
    assertNull(series(next = NextEpisodeInfo(2, 5, "2026-13-45")))
    assertNull(series(next = NextEpisodeInfo(2, 5, "2026-11-12T10:00:00Z")))
  }

  // 6b. Stagione/episodio incoerenti -> badge assente (dato incompleto).
  @Test
  fun `incoherent season or episode hides badge`() {
    assertNull(series(next = NextEpisodeInfo(seasonNumber = 2, episodeNumber = 0, airDate = "2026-11-12")))
    assertNull(series(next = NextEpisodeInfo(seasonNumber = -1, episodeNumber = 5, airDate = "2026-11-12")))
  }

  // 7. Data già trascorsa -> badge assente; oggi (non ancora trascorsa) -> visibile.
  @Test
  fun `past air date hides badge`() {
    assertNull(series(next = NextEpisodeInfo(2, 5, "2026-10-29")))
    assertNull(series(next = NextEpisodeInfo(2, 5, "2025-01-01")))
  }

  @Test
  fun `air date today is still shown`() {
    val badge = series(next = NextEpisodeInfo(2, 5, "2026-10-30"))
    assertEquals("30/10/2026", badge?.airDateText)
  }

  // 9. Serie con più stagioni: stagione ed episodio corretti, anche con due cifre.
  @Test
  fun `multi season series keeps the exact season and episode`() {
    val badge = series(
      next = NextEpisodeInfo(seasonNumber = 10, episodeNumber = 3, airDate = "2026-11-12"),
      resumeSeason = 10
    )

    assertEquals("S10E03", badge?.seasonEpisodeText)
  }

  @Test
  fun `special season zero is still shown`() {
    val badge = series(
      next = NextEpisodeInfo(seasonNumber = 0, episodeNumber = 7, airDate = "2026-11-12"),
      resumeSeason = 0
    )

    assertEquals("S00E07", badge?.seasonEpisodeText)
  }

  // Stato mancante o diverso da "Returning Series" -> nessun badge.
  @Test
  fun `unknown or not airing status hides badge`() {
    assertNull(series(status = null))
    assertNull(series(status = "In Production"))
    assertNull(series(status = "Planned"))
    assertNull(series(status = "Pilot"))
  }

  @Test
  fun `status match tolerates case and surrounding spaces`() {
    val badge = series(status = "  returning series ")

    assertNotNull(badge)
  }

  // 2. Regola stagione: il badge informa solo della stagione realmente in visione.
  //
  // L'episodio in ripresa arriva da "Continua a guardare" (lastWatchedSeason); la prossima
  // puntata TMDB appartiene alla S04. Solo S04 deve mostrare il badge.

  @Test
  fun `watching season 1 hides season 4 next episode`() {
    assertNull(series(next = NextEpisodeInfo(4, 4, "2026-11-12"), resumeSeason = 1))
  }

  @Test
  fun `watching season 2 hides season 4 next episode`() {
    assertNull(series(next = NextEpisodeInfo(4, 4, "2026-11-12"), resumeSeason = 2))
  }

  @Test
  fun `watching season 3 hides season 4 next episode`() {
    assertNull(series(next = NextEpisodeInfo(4, 4, "2026-11-12"), resumeSeason = 3))
  }

  @Test
  fun `watching season 4 shows season 4 next episode`() {
    val badge = series(next = NextEpisodeInfo(4, 4, "2026-11-12"), resumeSeason = 4)

    assertEquals("12/11/2026", badge?.airDateText)
    assertEquals("S04E04", badge?.seasonEpisodeText)
  }

  @Test
  fun `watching season 5 hides season 4 next episode`() {
    assertNull(series(next = NextEpisodeInfo(4, 4, "2026-11-12"), resumeSeason = 5))
  }

  // Stagione in ripresa mancante o non valida: nessun confronto attendibile -> badge nascosto.
  @Test
  fun `missing resume season hides badge`() {
    assertNull(series(next = NextEpisodeInfo(4, 4, "2026-11-12"), resumeSeason = null))
  }

  @Test
  fun `invalid resume season hides badge`() {
    assertNull(series(next = NextEpisodeInfo(4, 4, "2026-11-12"), resumeSeason = -1))
  }

  // 10. Cambio di stagione durante la visione: con lo stesso dato TMDB il badge passa da
  // nascosto a visibile quando la stagione salvata diventa quella della prossima puntata.
  @Test
  fun `season change during playback updates badge`() {
    val next = NextEpisodeInfo(4, 4, "2026-11-12")

    assertNull(series(next = next, resumeSeason = 2))
    assertNotNull(series(next = next, resumeSeason = 4))
    // Nuovo cambio stagione: la prossima puntata resta in S04, quindi torna nascosto.
    assertNull(series(next = next, resumeSeason = 5))
  }

  // Nessuna regola già implementata deve essere bypassata dal controllo di stagione.
  @Test
  fun `matching season does not bypass existing rules`() {
    assertNull(series(status = "Ended", next = NextEpisodeInfo(4, 4, "2026-11-12"), resumeSeason = 4))
    assertNull(series(status = "Canceled", next = NextEpisodeInfo(4, 4, "2026-11-12"), resumeSeason = 4))
    assertNull(series(next = null, resumeSeason = 4))
    assertNull(series(next = NextEpisodeInfo(4, 4, ""), resumeSeason = 4))
    assertNull(series(next = NextEpisodeInfo(4, 4, "not-a-date"), resumeSeason = 4))
    assertNull(series(next = NextEpisodeInfo(4, 4, "2026-10-29"), resumeSeason = 4))
    assertNull(series(type = MediaType.FILM, next = NextEpisodeInfo(4, 4, "2026-11-12"), resumeSeason = 4))
  }

  // Il badge sulla card reale passa da MediaItem: la regola deve leggere i campi del modello.
  @Test
  fun `media item resolver uses item type status and next episode`() {
    val tv = MediaItem(
      id = "tmdb_tv_1399",
      title = "Il Trono di Spade",
      synopsis = "",
      videoUrl = "",
      type = MediaType.SERIE_TV,
      tmdbId = 1399,
      lastWatchedSeason = 2,
      lastWatchedEpisode = 4,
      tvStatus = NextEpisodeBadgeRules.TV_STATUS_RETURNING,
      nextEpisode = NextEpisodeInfo(2, 5, "2026-11-12")
    )
    val film = tv.copy(id = "tmdb_m_1399", type = MediaType.FILM)

    assertEquals("12/11/2026", NextEpisodeBadgeRules.resolve(tv, date("2026-10-30"))?.airDateText)
    assertNull(NextEpisodeBadgeRules.resolve(film, date("2026-10-30")))
  }

  // 9. Ripresa Debrid: la stagione è quella effettivamente salvata con il progresso, non
  // quella deducibile da altri dati della serie (episodi caricati, ultimo episodio TMDB,
  // stagione 1 come fallback).
  @Test
  fun `debrid resume compares the saved season not derived data`() {
    val item = MediaItem(
      id = "tt0944947:4:4",
      title = "Il Trono di Spade",
      synopsis = "",
      videoUrl = "https://store-001.torbox.app/debrid/stream/abc",
      type = MediaType.SERIE_TV,
      tmdbId = 1399,
      lastWatchedSeason = 4,
      lastWatchedEpisode = 2,
      tvStatus = NextEpisodeBadgeRules.TV_STATUS_RETURNING,
      nextEpisode = NextEpisodeInfo(4, 4, "2026-11-12"),
      // Dati non autorevoli per la ripresa: non devono influenzare il confronto.
      seasonsCount = 8,
      seasonEpisodesCount = mapOf(1 to 10, 2 to 10, 3 to 10, 4 to 10)
    )

    val badge = NextEpisodeBadgeRules.resolve(item, date("2026-10-30"))

    assertEquals("S04E04", badge?.seasonEpisodeText)
  }

  @Test
  fun `debrid resume with a different saved season hides badge`() {
    val item = MediaItem(
      id = "tt0944947:1:3",
      title = "Il Trono di Spade",
      synopsis = "",
      videoUrl = "https://store-001.torbox.app/debrid/stream/abc",
      type = MediaType.SERIE_TV,
      tmdbId = 1399,
      lastWatchedSeason = 1,
      lastWatchedEpisode = 3,
      tvStatus = NextEpisodeBadgeRules.TV_STATUS_RETURNING,
      nextEpisode = NextEpisodeInfo(4, 4, "2026-11-12"),
      seasonsCount = 8,
      seasonEpisodesCount = mapOf(1 to 10, 2 to 10, 3 to 10, 4 to 10)
    )

    assertNull(NextEpisodeBadgeRules.resolve(item, date("2026-10-30")))
  }

  @Test
  fun `missing saved season hides badge even with loaded episodes`() {
    val item = MediaItem(
      id = "tmdb_tv_1399",
      title = "Il Trono di Spade",
      synopsis = "",
      videoUrl = "",
      type = MediaType.SERIE_TV,
      tmdbId = 1399,
      lastWatchedSeason = null,
      lastWatchedEpisode = 3,
      tvStatus = NextEpisodeBadgeRules.TV_STATUS_RETURNING,
      nextEpisode = NextEpisodeInfo(4, 4, "2026-11-12"),
      seasonsCount = 8,
      seasonEpisodesCount = mapOf(4 to 10)
    )

    assertNull(NextEpisodeBadgeRules.resolve(item, date("2026-10-30")))
  }

  @Test
  fun `tmdb air date parser accepts only strict iso dates`() {
    assertNotNull(NextEpisodeBadgeRules.parseAirDate("2026-11-12"))
    // Spazi accidentali attorno al valore: tollerati perché il contenuto resta la data ISO.
    assertNotNull(NextEpisodeBadgeRules.parseAirDate(" 2026-11-12 "))
    assertNull(NextEpisodeBadgeRules.parseAirDate(null))
    // Non zero-padded: TMDB non lo produce, quindi non è considerata una data verificabile.
    assertNull(NextEpisodeBadgeRules.parseAirDate("2026-1-2"))
    assertNull(NextEpisodeBadgeRules.parseAirDate("2026-11-31"))
  }
}
