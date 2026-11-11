package com.aistudio.streamnova.mobile.ui

import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.prefs.ContinueWatchingLayout
import com.example.data.prefs.ContinueWatchingPreferences
import com.example.data.repository.CatalogSection
import com.example.data.repository.ContinueWatchingRowItem
import com.example.data.repository.HomeCatalogs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileCatalogUiStateTest {

  private fun movie(id: String, ageRating: Int? = null) = MediaItem(
    id = id,
    title = id,
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = 2024,
    ageRating = ageRating
  )

  private fun series(id: String, ageRating: Int? = null) = MediaItem(
    id = id,
    title = id,
    synopsis = "",
    videoUrl = "",
    type = MediaType.SERIE_TV,
    year = 2024,
    ageRating = ageRating
  )

  private fun home(
    hero: List<MediaItem> = emptyList(),
    popularMovies: List<MediaItem> = emptyList(),
    popularSeries: List<MediaItem> = emptyList(),
    top10Movies: List<MediaItem> = emptyList(),
    top10Series: List<MediaItem> = emptyList(),
    trendingMovies: List<MediaItem> = emptyList(),
    trendingSeries: List<MediaItem> = emptyList(),
    forYouMovies: List<MediaItem> = emptyList(),
    forYouSeries: List<MediaItem> = emptyList(),
    allMovies: List<MediaItem> = emptyList(),
    allSeries: List<MediaItem> = emptyList(),
    extraSections: List<CatalogSection> = emptyList()
  ) = HomeCatalogs(
    heroItems = hero,
    popularMovies = popularMovies,
    popularSeries = popularSeries,
    top10Movies = top10Movies,
    top10Series = top10Series,
    trendingMovies = trendingMovies,
    trendingSeries = trendingSeries,
    forYouMovies = forYouMovies,
    forYouSeries = forYouSeries,
    allMovies = allMovies,
    allSeries = allSeries,
    extraSections = extraSections
  )

  private fun stateOf(
    home: HomeCatalogs,
    continueWatching: List<MediaItem> = emptyList(),
    favorites: List<MediaItem> = emptyList(),
    isLoading: Boolean = false,
    isRefreshing: Boolean = false,
    error: MobileCatalogError? = null,
    parentalEnabled: Boolean = false,
    parentalLevel: String = "18"
  ) = buildMobileCatalogUiState(
    home = home,
    continueWatching = continueWatching,
    favorites = favorites,
    isLoading = isLoading,
    isRefreshing = isRefreshing,
    error = error,
    parentalEnabled = parentalEnabled,
    parentalLevel = parentalLevel
  )

  @Test
  fun leRigheHomeRispettanoOrdineESeparazioneDellaTv() {
    val hero1 = movie("hero-1")
    val hero2 = series("hero-2")
    val film = movie("film")
    val serie = series("serie")
    val extra = CatalogSection(
      id = "tmdb_netflix_movies_new",
      title = "Novità Netflix Film",
      addonName = "Netflix",
      items = listOf(movie("extra"))
    )

    val state = stateOf(
      home = home(
        hero = listOf(hero1, hero2),
        popularMovies = listOf(film),
        popularSeries = listOf(serie),
        top10Movies = listOf(film),
        top10Series = listOf(serie),
        trendingMovies = listOf(film),
        trendingSeries = listOf(serie),
        forYouMovies = listOf(film),
        forYouSeries = listOf(serie),
        allMovies = listOf(film),
        allSeries = listOf(serie),
        extraSections = listOf(extra)
      ),
      continueWatching = listOf(serie)
    )

    assertEquals(listOf(hero1, hero2), state.heroItems)
    assertEquals(listOf(film), state.popularMovies)
    assertEquals(listOf(serie), state.popularSeries)
    assertEquals(listOf(film), state.top10Movies)
    assertEquals(listOf(serie), state.top10Series)
    assertEquals(listOf(serie), state.continueWatching)
    assertEquals(listOf(film), state.movies)
    assertEquals(listOf(serie), state.series)

    // Ordine TV 1.0.4: Hero, Continua a guardare, Provider, Top 10 F/S,
    // Novità F/S, For You F/S, Popolari F/S, sezioni extra.
    assertEquals(
      listOf(
        "hero",
        "continue_watching",
        "providers",
        "top_10_film",
        "top_10_serie",
        "trending_film",
        "trending_serie",
        "for_you_film",
        "for_you_serie",
        "popolari_film",
        "popolari_serie",
        "extra_tmdb_netflix_movies_new"
      ),
      state.homeSections().map { it.key }
    )

    // Nessuna riga unica "Popolari"/"Top 10": Film e Serie restano separati.
    assertEquals(film, state.homeSections().first { it.key == "popolari_film" }.items.single())
    assertEquals(serie, state.homeSections().first { it.key == "popolari_serie" }.items.single())
  }

  @Test
  fun heroConservaTuttiITitoliDelCarosello() {
    val items = listOf(movie("a"), series("b"), movie("c"))

    val state = stateOf(home = home(hero = items))

    assertEquals(items, state.heroItems)
    assertEquals(items, state.homeSections().first().items)
  }

  @Test
  fun ilFiltroGenitoriNascondeISoloTitoliClassificati() {
    val restricted = movie("restricted", ageRating = 18)
    val allowed = series("allowed", ageRating = 12)
    val extra = CatalogSection(
      id = "extra",
      title = "Extra",
      items = listOf(restricted)
    )

    val state = stateOf(
      home = home(
        hero = listOf(restricted),
        popularMovies = listOf(restricted),
        popularSeries = listOf(allowed),
        top10Movies = listOf(restricted),
        extraSections = listOf(extra),
        allMovies = listOf(restricted),
        allSeries = listOf(allowed)
      ),
      continueWatching = listOf(restricted),
      favorites = listOf(restricted),
      parentalEnabled = true,
      parentalLevel = "18"
    )

    assertTrue(state.heroItems.isEmpty())
    assertTrue(state.popularMovies.isEmpty())
    assertEquals(listOf(allowed), state.popularSeries)
    assertTrue(state.top10Movies.isEmpty())
    assertTrue(state.extraSections.isEmpty())
    assertEquals(listOf(allowed), state.series)
    assertTrue(state.movies.isEmpty())
    assertTrue(state.continueWatching.isEmpty())
    assertTrue(state.favorites.isEmpty())
    assertTrue(state.parentalControlActive)
    // La serie consentita tiene viva la riga Popolari Serie: la Home non è vuota.
    assertTrue(state.hasHomeContent)
  }

  @Test
  fun laRigaContinuaAGuardareUsaLeCardDeciseDalPolicy() {
    val media = series("serie").copy(
      inContinueWatching = true,
      lastWatchedSeason = 2,
      lastWatchedEpisode = 4,
      currentProgressMs = 120_000L
    )
    val card = ContinueWatchingRowItem(
      media = media,
      artworkUrl = "https://image.tmdb.org/t/p/w780/still.jpg",
      blurArtwork = true,
      targetEpisodeUnwatched = true
    )
    val preferences = ContinueWatchingPreferences(
      layout = ContinueWatchingLayout.WIDE,
      useEpisodeThumbnails = true,
      blurUnwatched = true
    )

    val state = buildMobileCatalogUiState(
      home = home(),
      continueWatching = listOf(media),
      continueWatchingCards = listOf(card),
      continueWatchingPreferences = preferences,
      favorites = emptyList(),
      isLoading = false,
      isRefreshing = false,
      error = null,
      parentalEnabled = false,
      parentalLevel = "18"
    )

    assertEquals(listOf(card), state.continueWatchingCards)
    assertEquals(listOf(media), state.continueWatching)
    assertEquals(ContinueWatchingLayout.WIDE, state.continueWatchingPreferences.layout)
    val section = state.homeSections().first { it.key == "continue_watching" }
    assertEquals(listOf(card), section.continueWatchingCards)
    assertEquals(listOf(media), section.items)
  }

  @Test
  fun ilFiltroGenitoriRimuoveAncheLaCardDellaRiga() {
    val restricted = movie("restricted", ageRating = 18)
    val card = ContinueWatchingRowItem(
      media = restricted,
      artworkUrl = null,
      blurArtwork = false,
      targetEpisodeUnwatched = false
    )

    val state = buildMobileCatalogUiState(
      home = home(),
      continueWatching = listOf(restricted),
      continueWatchingCards = listOf(card),
      favorites = emptyList(),
      isLoading = false,
      isRefreshing = false,
      error = null,
      parentalEnabled = true,
      parentalLevel = "18"
    )

    assertTrue(state.continueWatching.isEmpty())
    assertTrue(state.continueWatchingCards.isEmpty())
    assertTrue(state.homeSections().none { it.key == "continue_watching" })
  }

  @Test
  fun loStatoConservaIlLogoOriginaleELeStringheVuote() {
    val withLogo = movie("logo").copy(logoUrl = "https://image.tmdb.org/t/p/w500/logo.png")
    val withoutLogo = movie("no-logo").copy(logoUrl = null)
    val blankLogo = movie("blank").copy(logoUrl = "   ")

    val state = stateOf(home = home(hero = listOf(withLogo, withoutLogo, blankLogo)))

    assertEquals(listOf(withLogo, withoutLogo, blankLogo), state.heroItems)
    assertEquals("https://image.tmdb.org/t/p/w500/logo.png", state.heroItems[0].logoUrl)
    assertNull(state.heroItems[1].logoUrl)
    assertEquals("   ", state.heroItems[2].logoUrl)
  }

  @Test
  fun senzaContenutiLoStatoEVuotoEpuoMostrareUnErrore() {
    val state = stateOf(
      home = HomeCatalogs(),
      error = MobileCatalogError.LOAD_FAILED
    )

    assertTrue(state.isEmpty)
    assertFalse(state.hasHomeContent)
    assertEquals(MobileCatalogError.LOAD_FAILED, state.error)
  }

  @Test
  fun duranteIlPrimoCaricamentoLoStatoNonEMaiPieno() {
    val state = stateOf(
      home = HomeCatalogs(),
      isLoading = true,
      isRefreshing = true
    )

    assertTrue(state.isLoading)
    assertTrue(state.isRefreshing)
    assertTrue(state.isEmpty)
    assertFalse(state.hasHomeContent)
  }
}
