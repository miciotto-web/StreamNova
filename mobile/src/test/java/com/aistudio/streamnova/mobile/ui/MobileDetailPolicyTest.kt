package com.aistudio.streamnova.mobile.ui

import com.aistudio.streamnova.mobile.R
import com.example.data.model.Episode
import com.example.data.model.EpisodeItem
import com.example.data.model.ExtendedMediaDetails
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.model.SeasonItem
import com.example.data.model.CastMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileDetailPolicyTest {

  private fun movie(
    id: String = "tmdb_m_1",
    year: Int = 0,
    durationMinutes: Int = 0,
    rating: Float = 0f,
    tmdbRating: Float? = null,
    ageRating: Int? = null,
    provider: String? = null,
    isFavorite: Boolean = false,
    currentProgressMs: Long = 0L,
    totalDurationMs: Long = 0L,
    lastWatchedSeason: Int? = null,
    lastWatchedEpisode: Int? = null,
    logoUrl: String? = null,
    director: String = "",
    cast: List<String> = emptyList(),
    genres: List<String> = emptyList()
  ) = MediaItem(
    id = id,
    title = "Titolo",
    synopsis = "Sinossi base",
    videoUrl = "",
    type = MediaType.FILM,
    year = year,
    durationMinutes = durationMinutes,
    rating = rating,
    tmdbRating = tmdbRating,
    ageRating = ageRating,
    provider = provider,
    isFavorite = isFavorite,
    currentProgressMs = currentProgressMs,
    totalDurationMs = totalDurationMs,
    lastWatchedSeason = lastWatchedSeason,
    lastWatchedEpisode = lastWatchedEpisode,
    logoUrl = logoUrl,
    director = director,
    cast = cast,
    genres = genres
  )

  private fun series(
    id: String = "tmdb_tv_1",
    seasonsCount: Int? = null,
    seasonEpisodesCount: Map<Int, Int> = emptyMap(),
    episodes: List<Episode> = emptyList(),
    lastWatchedSeason: Int? = null,
    lastWatchedEpisode: Int? = null,
    currentProgressMs: Long = 0L,
    totalDurationMs: Long = 0L,
    cast: List<String> = emptyList(),
    director: String = ""
  ) = MediaItem(
    id = id,
    title = "Serie",
    synopsis = "Sinossi serie",
    videoUrl = "",
    type = MediaType.SERIE_TV,
    seasonsCount = seasonsCount,
    seasonEpisodesCount = seasonEpisodesCount,
    episodes = episodes,
    lastWatchedSeason = lastWatchedSeason,
    lastWatchedEpisode = lastWatchedEpisode,
    currentProgressMs = currentProgressMs,
    totalDurationMs = totalDurationMs,
    cast = cast,
    director = director
  )

  private fun details(
    isTv: Boolean = false,
    logoUrl: String? = null,
    seasonsCount: Int? = null,
    episodesCount: Int? = null,
    seasonEpisodesCount: Map<Int, Int> = emptyMap(),
    rating: Float = 0f,
    releaseYear: Int = 0,
    durationMinutes: Int = 0,
    ageRating: Int? = null,
    genres: List<String> = emptyList(),
    tagline: String? = null,
    cast: List<CastMember> = emptyList(),
    directorMember: CastMember? = null,
    productionCompanies: List<com.example.data.model.ProductionCompany> = emptyList()
  ) = ExtendedMediaDetails(
    tmdbId = 1,
    title = "Titolo",
    originalTitle = "",
    overview = "Overview TMDB",
    backdropUrl = null,
    posterUrl = null,
    logoUrl = logoUrl,
    releaseYear = releaseYear,
    rating = rating,
    durationMinutes = durationMinutes,
    seasonsCount = seasonsCount,
    episodesCount = episodesCount,
    genres = genres,
    tagline = tagline,
    cast = cast,
    directorMember = directorMember,
    isTv = isTv,
    seasonEpisodesCount = seasonEpisodesCount,
    ageRating = ageRating,
    productionCompanies = productionCompanies
  )

  @Test
  fun ilLogoDelDettaglioHaPrioritaEPoiSiUsaQuelloDellaScheda() {
    val base = movie(logoUrl = "https://img/base.png")
    val extended = details(logoUrl = "https://img/detail.png")

    assertEquals(
      "https://img/detail.png",
      MobileDetailPolicy.logoUrl(base, extended)
    )
    assertEquals(
      "https://img/base.png",
      MobileDetailPolicy.logoUrl(base, details(logoUrl = "  "))
    )
    assertNull(MobileDetailPolicy.logoUrl(movie(logoUrl = null), null))
  }

  @Test
  fun iMetadatiMancantiRestanoNulliESenzaGeneri() {
    val metadata = MobileDetailPolicy.metadata(movie(), null)

    assertNull(metadata.year)
    assertNull(metadata.durationMinutes)
    assertNull(metadata.rating)
    assertNull(metadata.ageRating)
    assertTrue(metadata.genres.isEmpty())
    assertNull(metadata.tagline)
  }

  @Test
  fun iMetadatiDellaSerieMostranoStagioniEdEpisodiReali() {
    val metadata = MobileDetailPolicy.metadata(
      series(seasonsCount = 2),
      details(isTv = true, seasonsCount = 4, episodesCount = 41)
    )

    assertEquals(4, metadata.seasonsCount)
    assertEquals(41, metadata.episodesCount)
    assertNull(metadata.durationMinutes)
  }

  @Test
  fun ilFilmUsaLaDurataRealeELaClassificazione() {
    val metadata = MobileDetailPolicy.metadata(
      movie(year = 2024, durationMinutes = 120, ageRating = 14, tmdbRating = 8.2f),
      details(durationMinutes = 136, rating = 8.7f, releaseYear = 2024, ageRating = 16)
    )

    assertEquals(2024, metadata.year)
    assertEquals(136, metadata.durationMinutes)
    assertEquals(16, metadata.ageRating)
    assertEquals(8.7f, metadata.rating)
  }

  @Test
  fun ilCastMetteIlRegistaInTestaSenzaDuplicati() {
    val cast = MobileDetailPolicy.cast(
      movie(director = "Regista Base", cast = listOf("Attore A", "Attore B")),
      null
    )

    assertEquals(listOf("Regista Base", "Attore A", "Attore B"), cast.map { it.name })
    assertEquals("Regista", cast.first().character)
  }

  @Test
  fun ilCreatoreDellaSerieHaIlRuoloCreatore() {
    val cast = MobileDetailPolicy.cast(
      series(director = "Autore"),
      null
    )

    assertEquals("Autore", cast.first().name)
    assertEquals("Creatore", cast.first().character)
  }

  @Test
  fun ilCastEstesoNonDuplicaIlRegista() {
    val director = CastMember(id = 7, name = "Regista", character = "Regista")
    val cast = MobileDetailPolicy.cast(
      movie(director = "Regista"),
      details(
        directorMember = director,
        cast = listOf(
          director,
          CastMember(id = 8, name = "Attrice", character = "Ruolo")
        )
      )
    )

    assertEquals(listOf("Regista", "Attrice"), cast.map { it.name })
  }

  @Test
  fun senzaConteggioTvLeStagioniNonVengonoInventate() {
    val seasons = MobileDetailPolicy.seasons(
      media = series(seasonsCount = null, seasonEpisodesCount = mapOf(2 to 8)),
      details = null
    )

    assertEquals(listOf(2), seasons)
  }

  @Test
  fun ilConteggioTvRealeProduceLeStagioniNumerate() {
    val seasons = MobileDetailPolicy.seasons(
      media = series(seasonsCount = null),
      details = details(isTv = true, seasonsCount = 3, seasonEpisodesCount = mapOf(0 to 2))
    )

    assertEquals(listOf(0, 1, 2, 3), seasons)
  }

  @Test
  fun laStagioneZeroCompareSoloSeRealeOSelezionata() {
    assertEquals(
      listOf(0, 1, 2),
      MobileDetailPolicy.seasons(
        media = series(seasonsCount = 2, lastWatchedSeason = 0),
        details = null
      )
    )
    assertEquals(
      listOf(0, 1, 2),
      MobileDetailPolicy.seasons(
        media = series(seasonsCount = 2),
        details = null,
        selectedSeason = 0
      )
    )
  }

  @Test
  fun laStagioneInizialeRiprendeLUltimaVista() {
    val media = series(lastWatchedSeason = 3)
    assertEquals(3, MobileDetailPolicy.initialSeason(media, listOf(1, 2, 3)))
    assertEquals(1, MobileDetailPolicy.initialSeason(series(), listOf(0, 1, 2)))
    assertNull(MobileDetailPolicy.initialSeason(series(), emptyList()))
  }

  @Test
  fun ilTargetGuardatoSegueLaLogicaTv() {
    val notWatched = movie(totalDurationMs = 7_200_000L, currentProgressMs = 0L)
    val watched = movie(totalDurationMs = 7_200_000L, currentProgressMs = 7_000_000L)

    assertEquals(7_200_000L, MobileDetailPolicy.watchedTargetProgress(notWatched))
    assertEquals(0L, MobileDetailPolicy.watchedTargetProgress(watched))
  }

  @Test
  fun selezionareUnaStagioneSvuotaSubitoGliEpisodiPrecedenti() {
    val one = Episode(
      id = "ep1",
      seasonNumber = 1,
      episodeNumber = 1,
      title = "Episodio",
      synopsis = "",
      durationMinutes = 50,
      videoUrl = ""
    )
    val state = MobileDetailUiState(
      media = series(),
      selectedSeason = 1,
      season = SeasonItem(id = 1, seasonNumber = 1, name = "Stagione 1", episodes = emptyList()),
      episodes = listOf(one),
      episodesLoading = false,
      episodesError = R.string.detail_episodes_error
    )

    val next = MobileDetailPolicy.selectSeason(state, 2)

    assertEquals(2, next.selectedSeason)
    assertTrue(next.episodes.isEmpty())
    assertNull(next.season)
    assertTrue(next.episodesLoading)
    assertNull(next.episodesError)
  }

  @Test
  fun ilMergeDelRepositoryAggiornaStatoUtenteSenzaPerdereIMetadatiEstesi() {
    val media = movie(
      id = "m1",
      currentProgressMs = 0L,
      totalDurationMs = 1_000L
    ).copy(synopsis = "Sinossi dettagliata", isFavorite = false)
    val episode = Episode(
      id = "ep1",
      seasonNumber = 1,
      episodeNumber = 1,
      title = "Episodio",
      synopsis = "",
      durationMinutes = 50,
      videoUrl = "",
      currentProgressMs = 0L
    )
    val state = MobileDetailUiState(
      media = media,
      isFavorite = false,
      episodes = listOf(episode),
      selectedSeason = 1
    )
    val updated = media.copy(isFavorite = true, currentProgressMs = 500L)

    val merged = MobileDetailPolicy.mergeRepositoryMedia(
      state = state,
      updated = updated,
      progressOf = { _, _ -> 120_000L },
      durationOf = { _, _ -> 3_000_000L }
    )

    assertTrue(merged.isFavorite)
    assertTrue(merged.media?.isFavorite == true)
    assertEquals("Sinossi dettagliata", merged.media?.synopsis)
    assertEquals(500L, merged.media?.currentProgressMs)
    assertEquals(120_000L, merged.episodes.first().currentProgressMs)
    assertEquals(3_000_000L, merged.episodes.first().totalDurationMs)
  }

  @Test
  fun laSerieHaUnTargetRiproducibileSoloConEpisodiOPosizioneSalvata() {
    val empty = MobileDetailUiState(media = series())
    assertFalse(MobileDetailPolicy.hasResolvableSeriesTarget(empty))

    val withPosition = MobileDetailUiState(
      media = series(lastWatchedSeason = 5, lastWatchedEpisode = 2)
    )
    assertTrue(MobileDetailPolicy.hasResolvableSeriesTarget(withPosition))

    val withEpisode = MobileDetailUiState(
      media = series(),
      episodes = listOf(
        Episode(
          id = "ep",
          seasonNumber = 1,
          episodeNumber = 1,
          title = "Episodio",
          synopsis = "",
          durationMinutes = 45,
          videoUrl = ""
        )
      )
    )
    assertTrue(MobileDetailPolicy.hasResolvableSeriesTarget(withEpisode))
    assertFalse(MobileDetailPolicy.hasResolvableSeriesTarget(MobileDetailUiState(media = movie())))
  }

  @Test
  fun ilConteggioEpisodiUsaPrimaLeMappeRealiPoiLaStagioneCaricata() {
    assertEquals(
      8,
      MobileDetailPolicy.seasonEpisodeCount(
        media = series(seasonEpisodesCount = mapOf(1 to 8)),
        details = null,
        season = 1
      )
    )
    assertEquals(
      0,
      MobileDetailPolicy.seasonEpisodeCount(
        media = series(),
        details = details(isTv = true, seasonEpisodesCount = mapOf(1 to 0)),
        season = 1
      )
    )
    assertEquals(
      1,
      MobileDetailPolicy.seasonEpisodeCount(
        media = series(),
        details = null,
        season = 1,
        loadedEpisodes = listOf(
          Episode(
            id = "ep",
            seasonNumber = 1,
            episodeNumber = 1,
            title = "Episodio",
            synopsis = "",
            durationMinutes = 45,
            videoUrl = ""
          )
        ),
        loadedSeason = 1,
        episodesLoaded = true
      )
    )
  }

  @Test
  fun ilMappingEpisodiUsaProgressoEDurataReali() {
    val media = series()
    val season = SeasonItem(
      id = 1,
      seasonNumber = 1,
      name = "Stagione 1",
      episodes = listOf(
        EpisodeItem(
          id = "tmdb_tv_1_s1e1",
          tmdbId = 11,
          episodeNumber = 1,
          seasonNumber = 1,
          title = "Episodio reale",
          overview = "Sinossi reale",
          durationMinutes = 0,
          rating = 0f
        )
      )
    )

    val mapped = MobileDetailPolicy.mapEpisodes(
      media = media,
      seasonItem = season,
      progressOf = { _, _ -> 100_000L },
      durationOf = { _, _ -> 2_700_000L }
    )

    assertEquals(1, mapped.size)
    assertEquals("Episodio reale", mapped.first().title)
    assertEquals(100_000L, mapped.first().currentProgressMs)
    assertEquals(2_700_000L, mapped.first().totalDurationMs)
    assertEquals(0, mapped.first().durationMinutes)
  }

  @Test
  fun ilTrailerDisponibileApreIlPlayerSoloConUnaChiaveReale() {
    assertEquals(
      MobileTrailerState.Playing("abc123"),
      MobileDetailPolicy.trailerStateForKey("abc123")
    )
    assertEquals(MobileTrailerState.Error, MobileDetailPolicy.trailerStateForKey(null))
    assertEquals(MobileTrailerState.Error, MobileDetailPolicy.trailerStateForKey("   "))
  }

  private fun episode(
    progressMs: Long = 0L,
    totalDurationMs: Long = 2_700_000L
  ) = Episode(
    id = "tmdb_tv_1_s1e1",
    seasonNumber = 1,
    episodeNumber = 1,
    title = "Episodio",
    synopsis = "",
    durationMinutes = 45,
    videoUrl = "",
    currentProgressMs = progressMs,
    totalDurationMs = totalDurationMs
  )

  @Test
  fun laSfocaturaDellaMiniaturaEpisodioSegueLaPreferenza() {
    // Preferenza OFF: la miniatura resta sempre nitida, anche per una puntata mai vista.
    assertFalse(
      MobileDetailPolicy.shouldBlurEpisodeThumbnail(
        episode = episode(),
        blurUnwatched = false,
        isSimklWatched = false
      )
    )
    // Preferenza ON: si sfoca solo la puntata non ancora vista.
    assertTrue(
      MobileDetailPolicy.shouldBlurEpisodeThumbnail(
        episode = episode(),
        blurUnwatched = true,
        isSimklWatched = false
      )
    )
  }

  @Test
  fun unEpisodioInCorsoOConclusoNonVieneSfocato() {
    // Puntata iniziata ma non conclusa: nascondere la miniatura confonderebbe il
    // punto di ripresa, quindi resta nitida.
    assertFalse(
      MobileDetailPolicy.shouldBlurEpisodeThumbnail(
        episode = episode(progressMs = 60_000L),
        blurUnwatched = true,
        isSimklWatched = false
      )
    )
    // Puntata conclusa (>= 90% della durata): è vista, nessuna sfocatura.
    assertFalse(
      MobileDetailPolicy.shouldBlurEpisodeThumbnail(
        episode = episode(progressMs = 2_600_000L),
        blurUnwatched = true,
        isSimklWatched = false
      )
    )
  }

  @Test
  fun unEpisodioVistoSuSimklNonVieneSfocatoSenzaProgressoLocale() {
    // SIMKL può marcare la puntata come vista prima che il progresso locale sia
    // scritto: lo stato remoto ha la precedenza, altrimenti si sfocherebbe una
    // puntata già guardata.
    assertFalse(
      MobileDetailPolicy.shouldBlurEpisodeThumbnail(
        episode = episode(),
        blurUnwatched = true,
        isSimklWatched = true
      )
    )
  }

  @Test
  fun senzaEpisodioNonSiApplicaNessunaSfocatura() {
    assertFalse(
      MobileDetailPolicy.shouldBlurEpisodeThumbnail(
        episode = null,
        blurUnwatched = true,
        isSimklWatched = false
      )
    )
  }
}
