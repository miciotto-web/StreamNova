package com.example.data.repository

import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.WatchedEpisodePoint
import com.example.data.prefs.ContinueWatchingLayout
import com.example.data.prefs.ContinueWatchingPreferences
import com.example.data.prefs.ContinueWatchingSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy unica della riga «Continua a guardare».
 *
 * Verifica che OGNI preferenza della sezione Impostazioni produca un effetto reale e
 * verificabile sulla lista composta, senza dipendenze Android:
 * visibilità, layout/immagine, miniature episodi, sfocatura dei non visti, episodio di
 * ripresa (recente vs più avanzato), episodi non ancora trasmessi e ordinamento.
 */
class ContinueWatchingRowPolicyTest {

  private val today = "2026-11-05"

  private fun episode(
    season: Int,
    number: Int,
    airDate: String? = "2020-01-01",
    thumbnailUrl: String? = "https://img.example/ep_s${season}e$number.jpg",
    durationMinutes: Int = 50
  ) = Episode(
    id = "ep_${season}_$number",
    seasonNumber = season,
    episodeNumber = number,
    title = "Episodio $number",
    synopsis = "",
    durationMinutes = durationMinutes,
    thumbnailUrl = thumbnailUrl,
    videoUrl = "",
    totalDurationMs = durationMinutes * 60_000L,
    airDate = airDate
  )

  private fun series(
    id: String = "serie_1",
    title: String = "Serie Uno",
    lastSeason: Int = 1,
    lastEpisode: Int = 2,
    progressMs: Long = 10 * 60_000L,
    episodes: List<Episode> = (1..5).map { episode(1, it) },
    seasonEpisodesCount: Map<Int, Int> = emptyMap(),
    nextEpisode: com.example.data.model.NextEpisodeInfo? = null,
    backdropUrl: String? = "https://img.example/backdrop.jpg",
    posterUrl: String? = "https://img.example/poster.jpg",
    rating: Float = 8.0f,
    lastWatchedAt: Long = 1_000L,
    tmdbId: Int = 100,
    // Le card di questi scenari hanno uno stato di ripresa reale: dalla v12 il default
    // del flag è `false` (una scheda TMDB fresca non è candidata), quindi va esplicitato.
    inContinueWatching: Boolean = true
  ) = MediaItem(
    id = id,
    title = title,
    synopsis = "",
    videoUrl = "",
    type = MediaType.SERIE_TV,
    tmdbId = tmdbId,
    currentProgressMs = progressMs,
    totalDurationMs = 50 * 60_000L,
    durationMinutes = 50,
    lastWatchedSeason = lastSeason,
    lastWatchedEpisode = lastEpisode,
    lastWatchedAt = lastWatchedAt,
    inContinueWatching = inContinueWatching,
    episodes = episodes,
    seasonEpisodesCount = seasonEpisodesCount,
    nextEpisode = nextEpisode,
    backdropUrl = backdropUrl,
    posterUrl = posterUrl,
    rating = rating
  )

  private fun movie(
    id: String = "film_1",
    title: String = "Film Uno",
    progressMs: Long = 20 * 60_000L,
    lastWatchedAt: Long = 2_000L,
    rating: Float = 7.0f,
    inContinueWatching: Boolean = true
  ) = MediaItem(
    id = id,
    title = title,
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    currentProgressMs = progressMs,
    totalDurationMs = 100 * 60_000L,
    lastWatchedAt = lastWatchedAt,
    inContinueWatching = inContinueWatching,
    backdropUrl = "https://img.example/film_backdrop.jpg",
    posterUrl = "https://img.example/film_poster.jpg",
    rating = rating
  )

  @Test
  fun laRigaSparisceQuandoLaPreferenzaLaNasconde() {
    val result = ContinueWatchingRowPolicy.build(
      items = listOf(series()),
      preferences = ContinueWatchingPreferences(showRow = false),
      todayIso = today
    )
    assertTrue(result.isEmpty())
  }

  @Test
  fun defaultRiprendeLEpisodioGuardatoPiuDiRecente() {
    val item = series(lastSeason = 1, lastEpisode = 2)
    val result = ContinueWatchingRowPolicy.build(
      items = listOf(item),
      preferences = ContinueWatchingPreferences(),
      furthestWatched = { WatchedEpisodePoint(3, 4, 30 * 60_000L, 50 * 60_000L) },
      todayIso = today
    )

    assertEquals(1, result.size)
    // OFF (default): rewatch supportato, nessun avanzamento all'episodio più avanti.
    assertEquals(1, result.single().media.lastWatchedSeason)
    assertEquals(2, result.single().media.lastWatchedEpisode)
    assertEquals(10 * 60_000L, result.single().media.currentProgressMs)
  }

  @Test
  fun conUltimoRaggiuntoONRiprendeLEpisodioPiuAvanzatoInCorso() {
    val result = ContinueWatchingRowPolicy.build(
      items = listOf(series(episodes = (1..5).map { episode(3, it) })),
      preferences = ContinueWatchingPreferences(nextEpisodeFromFurthest = true),
      furthestWatched = { WatchedEpisodePoint(3, 4, 30 * 60_000L, 50 * 60_000L) },
      todayIso = today
    )

    assertEquals(3, result.single().media.lastWatchedSeason)
    assertEquals(4, result.single().media.lastWatchedEpisode)
    assertEquals(30 * 60_000L, result.single().media.currentProgressMs)
    assertFalse("Un episodio in corso è già stato visto", result.single().targetEpisodeUnwatched)
  }

  @Test
  fun conUltimoRaggiuntoONPassaAllEpisodioSuccessivoSeQuelloPiuAvanzatoEConcluso() {
    val result = ContinueWatchingRowPolicy.build(
      items = listOf(series(episodes = (1..6).map { episode(3, it) })),
      preferences = ContinueWatchingPreferences(nextEpisodeFromFurthest = true),
      furthestWatched = { WatchedEpisodePoint(3, 4, 49 * 60_000L, 50 * 60_000L) },
      todayIso = today
    )

    assertEquals(3, result.single().media.lastWatchedSeason)
    assertEquals(5, result.single().media.lastWatchedEpisode)
    assertEquals(0L, result.single().media.currentProgressMs)
    assertTrue(result.single().targetEpisodeUnwatched)
  }

  @Test
  fun senzaEpisodioSuccessivoVerificabileRestaSullUltimoRaggiunto() {
    val result = ContinueWatchingRowPolicy.build(
      items = listOf(series(episodes = listOf(episode(3, 4)))),
      preferences = ContinueWatchingPreferences(nextEpisodeFromFurthest = true),
      furthestWatched = { WatchedEpisodePoint(3, 4, 49 * 60_000L, 50 * 60_000L) },
      todayIso = today
    )

    assertEquals(3, result.single().media.lastWatchedSeason)
    assertEquals(4, result.single().media.lastWatchedEpisode)
  }

  @Test
  fun gliEpisodiNonAncoraTrasmessiSonoEsclusiQuandoLaPreferenzaEOff() {
    val futureEpisode = episode(2, 3, airDate = "2099-01-01")
    val completed = episode(2, 2)
    val item = series(
      lastSeason = 2,
      lastEpisode = 2,
      progressMs = 49 * 60_000L,
      episodes = listOf(completed, futureEpisode)
    )
    val result = ContinueWatchingRowPolicy.build(
      items = listOf(item),
      preferences = ContinueWatchingPreferences(
        nextEpisodeFromFurthest = true,
        showUnairedEpisodes = false
      ),
      furthestWatched = { WatchedEpisodePoint(2, 2, 49 * 60_000L, 50 * 60_000L) },
      todayIso = today
    )

    // Il titolo resta (c'è una puntata vista da riprendere), ma non punta alla futura.
    assertEquals(1, result.size)
    assertEquals(2, result.single().media.lastWatchedEpisode)
    assertFalse(result.single().targetEpisodeUnwatched)
  }

  @Test
  fun senzaUnaPuntataGiaVistaIlTitoloConEpisodioFuturoVieneEscluso() {
    // Caso limite: la posizione salvata punta già alla puntata futura (nessun ripiego
    // su una puntata già vista disponibile). Con la preferenza OFF il titolo non entra.
    val item = series(
      lastSeason = 2,
      lastEpisode = 3,
      progressMs = 10 * 60_000L,
      episodes = listOf(episode(2, 3, airDate = "2099-01-01"))
    )
    val result = ContinueWatchingRowPolicy.build(
      items = listOf(item),
      preferences = ContinueWatchingPreferences(
        nextEpisodeFromFurthest = true,
        showUnairedEpisodes = false
      ),
      todayIso = today
    )
    assertTrue(result.isEmpty())
  }

  @Test
  fun unaDataAssenteNonNascondeNeSpostaLaPuntata() {
    val noDate = episode(1, 3, airDate = null)
    val item = series(
      lastSeason = 1,
      lastEpisode = 2,
      progressMs = 49 * 60_000L,
      episodes = listOf(episode(1, 2), noDate)
    )
    val result = ContinueWatchingRowPolicy.build(
      items = listOf(item),
      preferences = ContinueWatchingPreferences(
        nextEpisodeFromFurthest = true,
        showUnairedEpisodes = false
      ),
      furthestWatched = { WatchedEpisodePoint(1, 2, 49 * 60_000L, 50 * 60_000L) },
      todayIso = today
    )

    assertEquals(1, result.size)
    assertEquals(3, result.single().media.lastWatchedEpisode)
  }

  @Test
  fun laMiniaturaDellEpisodioUsaIlFallbackQuandoManca() {
    val item = series(
      lastSeason = 1,
      lastEpisode = 2,
      episodes = listOf(episode(1, 2, thumbnailUrl = null))
    )

    val on = ContinueWatchingRowPolicy.build(
      items = listOf(item),
      preferences = ContinueWatchingPreferences(useEpisodeThumbnails = true),
      todayIso = today
    ).single()
    assertEquals("https://img.example/backdrop.jpg", on.artworkUrl)

    val withThumb = ContinueWatchingRowPolicy.build(
      items = listOf(item.copy(episodes = listOf(episode(1, 2)))),
      preferences = ContinueWatchingPreferences(useEpisodeThumbnails = true),
      todayIso = today
    ).single()
    assertEquals("https://img.example/ep_s1e2.jpg", withThumb.artworkUrl)
  }

  @Test
  fun ilLayoutSceglieLImmagineDiBase() {
    val item = series()
    val card = ContinueWatchingRowPolicy.build(
      items = listOf(item),
      preferences = ContinueWatchingPreferences(layout = ContinueWatchingLayout.CARD),
      todayIso = today
    ).single()
    val wide = ContinueWatchingRowPolicy.build(
      items = listOf(item),
      preferences = ContinueWatchingPreferences(layout = ContinueWatchingLayout.WIDE),
      todayIso = today
    ).single()
    val poster = ContinueWatchingRowPolicy.build(
      items = listOf(item),
      preferences = ContinueWatchingPreferences(layout = ContinueWatchingLayout.POSTER),
      todayIso = today
    ).single()

    assertEquals("https://img.example/backdrop.jpg", card.artworkUrl)
    assertEquals("https://img.example/backdrop.jpg", wide.artworkUrl)
    assertEquals("https://img.example/poster.jpg", poster.artworkUrl)
  }

  @Test
  fun laSfocaturaSiApplicaSoloAgliEpisodiNonVisti() {
    val completed = series(
      lastSeason = 1,
      lastEpisode = 1,
      progressMs = 49 * 60_000L,
      episodes = (1..6).map { episode(1, it) }
    )
    val prefs = ContinueWatchingPreferences(
      nextEpisodeFromFurthest = true,
      blurUnwatched = true
    )

    val nextUnwatched = ContinueWatchingRowPolicy.build(
      items = listOf(completed),
      preferences = prefs,
      furthestWatched = { WatchedEpisodePoint(1, 1, 49 * 60_000L, 50 * 60_000L) },
      todayIso = today
    ).single()
    assertTrue(nextUnwatched.blurArtwork)

    val inProgress = ContinueWatchingRowPolicy.build(
      items = listOf(series(episodes = (1..6).map { episode(1, it) })),
      preferences = prefs,
      furthestWatched = { WatchedEpisodePoint(1, 2, 10 * 60_000L, 50 * 60_000L) },
      todayIso = today
    ).single()
    assertFalse("Un episodio già iniziato non è 'non visto'", inProgress.blurArtwork)
  }

  @Test
  fun iFilmNonVengonoMaiSfocati() {
    val result = ContinueWatchingRowPolicy.build(
      items = listOf(movie()),
      preferences = ContinueWatchingPreferences(blurUnwatched = true),
      todayIso = today
    ).single()
    assertFalse(result.blurArtwork)
  }

  @Test
  fun gliOrdinamentiModificanoDavveroLaRiga() {
    val a = movie(id = "a", title = "Alfa", lastWatchedAt = 1_000L, rating = 6f)
    val b = movie(id = "b", title = "Bravo", lastWatchedAt = 3_000L, rating = 9f)
    val c = movie(id = "c", title = "Charlie", lastWatchedAt = 2_000L, rating = 7.5f)
    val items = listOf(a, b, c)

    fun ids(sort: ContinueWatchingSort) = ContinueWatchingRowPolicy.build(
      items = items,
      preferences = ContinueWatchingPreferences(sort = sort),
      todayIso = today
    ).map { it.media.id }

    // La visione più recente vince SEMPRE: TITLE/RATING non possono scavalcare la
    // cronologia (riprendere un titolo lo porta in prima posizione).
    assertEquals(listOf("b", "c", "a"), ids(ContinueWatchingSort.DEFAULT))
    assertEquals(listOf("b", "c", "a"), ids(ContinueWatchingSort.TITLE))
    assertEquals(listOf("b", "c", "a"), ids(ContinueWatchingSort.RATING))
  }

  @Test
  fun titleERatingRestanoSoloComeSpareggio() {
    val a = movie(id = "a", title = "Alfa", lastWatchedAt = 1_000L, rating = 6f)
    val b = movie(id = "b", title = "Bravo", lastWatchedAt = 1_000L, rating = 9f)

    fun ids(sort: ContinueWatchingSort) = ContinueWatchingRowPolicy.build(
      items = listOf(b, a),
      preferences = ContinueWatchingPreferences(sort = sort),
      todayIso = today
    ).map { it.media.id }

    assertEquals("a parità di data vince il titolo", listOf("a", "b"), ids(ContinueWatchingSort.TITLE))
    assertEquals("a parità di data vince il voto", listOf("b", "a"), ids(ContinueWatchingSort.RATING))
  }

  @Test
  fun unaCardPerTitoloAncheConSchedeDuplicates() {
    val tmdb = movie(id = "tmdb_m_1", lastWatchedAt = 1_000L).copy(tmdbId = 1)
    val imdb = movie(id = "tt1", lastWatchedAt = 2_000L).copy(tmdbId = null, imdbId = "tt1")
    val result = ContinueWatchingRowPolicy.build(
      items = listOf(tmdb, imdb),
      preferences = ContinueWatchingPreferences(),
      todayIso = today
    )
    // Stesso titolo (stessa chiave a schermo titolo+anno): vince la scheda con lo stato
    // più recente, come nella deduplicazione storica.
    assertEquals(1, result.size)
    assertEquals("tt1", result.single().media.id)
  }
}
