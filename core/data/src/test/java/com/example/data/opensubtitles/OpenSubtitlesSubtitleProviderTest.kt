package com.example.data.opensubtitles

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class OpenSubtitlesSubtitleProviderTest {

  private class FakeOpenSubtitlesApi : OpenSubtitlesApi {
    var lastImdbId: Int? = null
    var lastTmdbId: Int? = null
    var lastType: String? = null
    var lastSeasonNumber: Int? = null
    var lastEpisodeNumber: Int? = null
    var lastLanguages: String? = null
    var lastYear: Int? = null
    var lastQuery: String? = null

    var responseToReturn: Response<OpenSubtitlesSearchResponse>? = null
    var downloadResponseToReturn: Response<OpenSubtitlesDownloadResponse>? = null
    var exceptionToThrow: Exception? = null
    var downloadRequestCount = 0

    override suspend fun searchSubtitles(
      imdbId: Int?,
      tmdbId: Int?,
      type: String?,
      seasonNumber: Int?,
      episodeNumber: Int?,
      languages: String?,
      year: Int?,
      query: String?
    ): Response<OpenSubtitlesSearchResponse> {
      exceptionToThrow?.let { throw it }
      lastImdbId = imdbId
      lastTmdbId = tmdbId
      lastType = type
      lastSeasonNumber = seasonNumber
      lastEpisodeNumber = episodeNumber
      lastLanguages = languages
      lastYear = year
      lastQuery = query
      return responseToReturn ?: Response.success(OpenSubtitlesSearchResponse(data = emptyList()))
    }

    override suspend fun requestDownloadLink(
      request: OpenSubtitlesDownloadRequest
    ): Response<OpenSubtitlesDownloadResponse> {
      downloadRequestCount++
      return downloadResponseToReturn
        ?: Response.success(OpenSubtitlesDownloadResponse(link = "https://sub.test/download.srt"))
    }
  }

  @Test
  fun ricercaFilmImpostaParametriCorrettiEMappaSottotitoli() = runBlocking {
    val fakeApi = FakeOpenSubtitlesApi()
    fakeApi.responseToReturn = Response.success(
      OpenSubtitlesSearchResponse(
        data = listOf(
          OpenSubtitlesItemDto(
            id = "movie-sub-1",
            attributes = OpenSubtitlesAttributesDto(
              language = "ita",
              downloadUrl = "https://sub.test/movie.srt",
              release = "1080p.BluRay"
            )
          )
        )
      )
    )

    val provider = OpenSubtitlesSubtitleProvider(api = fakeApi)
    val results = provider.fetchSubtitles(
      imdbId = "tt0111161",
      tmdbId = 278,
      isTv = false
    )

    assertEquals(111161, fakeApi.lastImdbId)
    assertEquals(278, fakeApi.lastTmdbId)
    assertEquals("movie", fakeApi.lastType)
    assertNull(fakeApi.lastSeasonNumber)
    assertNull(fakeApi.lastEpisodeNumber)

    assertEquals(1, results.size)
    val sub = results[0]
    assertEquals("os-movie-sub-1", sub.id)
    assertEquals("https://sub.test/movie.srt", sub.url)
    assertEquals("it", sub.lang)
    assertEquals("1080p.BluRay", sub.label)
    assertEquals("OpenSubtitles v3", sub.addonName)
  }

  @Test
  fun ricercaSerieEpisodioImpostaStagioneEpisodioEType() = runBlocking {
    val fakeApi = FakeOpenSubtitlesApi()
    fakeApi.responseToReturn = Response.success(
      OpenSubtitlesSearchResponse(
        data = listOf(
          OpenSubtitlesItemDto(
            id = "ep-sub-1",
            attributes = OpenSubtitlesAttributesDto(
              language = "eng",
              downloadUrl = "https://sub.test/s02e05.srt",
              release = "720p.HDTV"
            )
          )
        )
      )
    )

    val provider = OpenSubtitlesSubtitleProvider(api = fakeApi)
    val results = provider.fetchSubtitles(
      imdbId = "tt0903747",
      tmdbId = 1396,
      isTv = true,
      seasonNumber = 2,
      episodeNumber = 5
    )

    assertEquals(903747, fakeApi.lastImdbId)
    assertEquals(1396, fakeApi.lastTmdbId)
    assertEquals("episode", fakeApi.lastType)
    assertEquals(2, fakeApi.lastSeasonNumber)
    assertEquals(5, fakeApi.lastEpisodeNumber)

    assertEquals(1, results.size)
    assertEquals("os-ep-sub-1", results[0].id)
    assertEquals("en", results[0].lang)
    assertEquals("https://sub.test/s02e05.srt", results[0].url)
  }

  @Test
  fun filtroLinguaPassaParametriFormattatiEMappaCorrettamente() = runBlocking {
    val fakeApi = FakeOpenSubtitlesApi()
    fakeApi.responseToReturn = Response.success(
      OpenSubtitlesSearchResponse(
        data = listOf(
          OpenSubtitlesItemDto(
            id = "it-sub",
            attributes = OpenSubtitlesAttributesDto(
              language = "ita",
              downloadUrl = "https://sub.test/it.srt"
            )
          ),
          OpenSubtitlesItemDto(
            id = "en-sub",
            attributes = OpenSubtitlesAttributesDto(
              language = "eng",
              downloadUrl = "https://sub.test/en.srt"
            )
          )
        )
      )
    )

    val provider = OpenSubtitlesSubtitleProvider(api = fakeApi)
    val results = provider.fetchSubtitles(
      imdbId = "tt1234567",
      languages = listOf("it", "en")
    )

    assertEquals("it,en", fakeApi.lastLanguages)
    assertEquals(2, results.size)
    assertEquals("it", results[0].lang)
    assertEquals("en", results[1].lang)
  }

  @Test
  fun rispostaVuotaGestitaCorrettamente() = runBlocking {
    val fakeApi = FakeOpenSubtitlesApi()
    fakeApi.responseToReturn = Response.success(OpenSubtitlesSearchResponse(data = emptyList()))

    val provider = OpenSubtitlesSubtitleProvider(api = fakeApi)
    val results = provider.fetchSubtitles(imdbId = "tt1234567")

    assertTrue(results.isEmpty())
  }

  @Test
  fun erroreApiHttpGestitoSenzaEccezioni() = runBlocking {
    val fakeApi = FakeOpenSubtitlesApi()
    val errorBody = "{\"error\": \"Unauthorized\"}".toResponseBody("application/json".toMediaType())
    fakeApi.responseToReturn = Response.error(401, errorBody)

    val provider = OpenSubtitlesSubtitleProvider(api = fakeApi)
    val results = provider.fetchSubtitles(imdbId = "tt1234567")

    assertTrue(results.isEmpty())
  }

  @Test
  fun erroreReteOTimeoutGestitoSenzaEccezioni() = runBlocking {
    val fakeApi = FakeOpenSubtitlesApi()
    fakeApi.exceptionToThrow = java.io.IOException("Connection timeout")

    val provider = OpenSubtitlesSubtitleProvider(api = fakeApi)
    val results = provider.fetchSubtitles(imdbId = "tt1234567")

    assertTrue(results.isEmpty())
  }

  @Test
  fun risoluzioneSuRichiestaDownloadLinkQuandoMancaUrlDiretto() = runBlocking {
    val fakeApi = FakeOpenSubtitlesApi()
    fakeApi.responseToReturn = Response.success(
      OpenSubtitlesSearchResponse(
        data = listOf(
          OpenSubtitlesItemDto(
            id = "ondemand-1",
            attributes = OpenSubtitlesAttributesDto(
              language = "ita",
              downloadUrl = null,
              files = listOf(
                OpenSubtitlesFileDto(fileId = 4242L, fileName = "resolved.srt")
              )
            )
          )
        )
      )
    )
    fakeApi.downloadResponseToReturn = Response.success(
      OpenSubtitlesDownloadResponse(link = "https://sub.test/resolved-link.srt")
    )

    val provider = OpenSubtitlesSubtitleProvider(api = fakeApi)
    val results = provider.fetchSubtitles(imdbId = "tt1234567")

    assertEquals(1, results.size)
    assertEquals("os-ondemand-1", results[0].id)
    assertEquals("https://sub.test/resolved-link.srt", results[0].url)
    assertEquals("it", results[0].lang)
  }

  @Test
  fun solaPaginaWebNonCreaSubtitleEVieneRisoltaTramiteDownloadEndpoint() = runBlocking {
    // `attributes.url` è la pagina web (HTML): da sola non deve mai diventare un Subtitle.
    val pageUrl = "https://www.opensubtitles.org/en/subtitles/9999999/title-en"
    val fakeApi = FakeOpenSubtitlesApi()
    fakeApi.responseToReturn = Response.success(
      OpenSubtitlesSearchResponse(
        data = listOf(
          OpenSubtitlesItemDto(
            id = "page-1",
            attributes = OpenSubtitlesAttributesDto(
              language = "ita",
              url = pageUrl,
              files = listOf(OpenSubtitlesFileDto(fileId = 777L, fileName = "title.srt"))
            )
          )
        )
      )
    )
    fakeApi.downloadResponseToReturn = Response.success(
      OpenSubtitlesDownloadResponse(link = "https://sub.test/resolved-777.srt")
    )

    val provider = OpenSubtitlesSubtitleProvider(api = fakeApi)
    val results = provider.fetchSubtitles(imdbId = "tt0000001")

    assertEquals(1, fakeApi.downloadRequestCount)
    assertEquals(1, results.size)
    assertEquals("https://sub.test/resolved-777.srt", results[0].url)
    assertNotEquals(pageUrl, results[0].url)
  }

  @Test
  fun solaPaginaWebSenzaFileIdNonGeneraNessunSubtitle() = runBlocking {
    val fakeApi = FakeOpenSubtitlesApi()
    fakeApi.responseToReturn = Response.success(
      OpenSubtitlesSearchResponse(
        data = listOf(
          OpenSubtitlesItemDto(
            id = "page-2",
            attributes = OpenSubtitlesAttributesDto(
              language = "ita",
              url = "https://www.opensubtitles.org/en/subtitles/8888888/other-en"
            )
          )
        )
      )
    )

    val provider = OpenSubtitlesSubtitleProvider(api = fakeApi)
    val results = provider.fetchSubtitles(imdbId = "tt0000001")

    assertTrue(results.isEmpty())
    assertEquals(0, fakeApi.downloadRequestCount)
  }

  @Test
  fun parsingImdbIdRimuovePrefissoTT() {
    assertEquals(111161, OpenSubtitlesSubtitleProvider.parseImdbId("tt0111161"))
    assertEquals(111161, OpenSubtitlesSubtitleProvider.parseImdbId("TT0111161"))
    assertEquals(12345, OpenSubtitlesSubtitleProvider.parseImdbId("12345"))
    assertNull(OpenSubtitlesSubtitleProvider.parseImdbId(null))
    assertNull(OpenSubtitlesSubtitleProvider.parseImdbId(""))
    assertNull(OpenSubtitlesSubtitleProvider.parseImdbId("invalid"))
  }

  @Test
  fun annoInviatoSoloNellaRicercaTestualeSenzaIdentificativi() = runBlocking {
    val fakeApi = FakeOpenSubtitlesApi()
    fakeApi.responseToReturn = Response.success(OpenSubtitlesSearchResponse(data = emptyList()))

    val provider = OpenSubtitlesSubtitleProvider(api = fakeApi)

    // Solo titolo + anno: l'anno serve a disambiguare i titoli omonimi.
    provider.fetchSubtitles(title = "The Matrix", year = 1999)
    assertEquals("The Matrix", fakeApi.lastQuery)
    assertEquals(1999, fakeApi.lastYear)

    // Con IMDb l'anno non viene inviato: non deve filtrare via risultati corretti.
    provider.fetchSubtitles(imdbId = "tt0111161", year = 1999)
    assertEquals(111161, fakeApi.lastImdbId)
    assertNull(fakeApi.lastYear)
    assertNull(fakeApi.lastQuery)

    // Con TMDB l'anno non viene inviato.
    provider.fetchSubtitles(tmdbId = 603, year = 1999)
    assertEquals(603, fakeApi.lastTmdbId)
    assertNull(fakeApi.lastYear)
  }

  @Test
  fun resolveLinksOnDemandFalseRestituisceTuttiIFileIdSenzaConsumareQuota() = runBlocking {
    val fakeApi = FakeOpenSubtitlesApi()
    fakeApi.responseToReturn = Response.success(
      OpenSubtitlesSearchResponse(
        data = listOf(
          OpenSubtitlesItemDto(
            id = "direct-1",
            attributes = OpenSubtitlesAttributesDto(
              language = "ita",
              downloadUrl = "https://sub.test/direct.srt"
            )
          ),
          OpenSubtitlesItemDto(
            id = "pending-1",
            attributes = OpenSubtitlesAttributesDto(
              language = "eng",
              url = "https://www.opensubtitles.org/en/subtitles/111/a-en",
              files = listOf(OpenSubtitlesFileDto(fileId = 101L, fileName = "pending-one.srt"))
            )
          ),
          OpenSubtitlesItemDto(
            id = "pending-2",
            attributes = OpenSubtitlesAttributesDto(
              language = "ita",
              files = listOf(OpenSubtitlesFileDto(fileId = 202L, fileName = "pending-two.srt"))
            )
          )
        )
      )
    )

    val provider = OpenSubtitlesSubtitleProvider(api = fakeApi)
    val results = provider.fetchSubtitles(
      imdbId = "tt1234567",
      resolveLinksOnDemand = false
    )

    // Nessuna chiamata a POST /download durante la ricerca: il link viene risolto
    // solo alla selezione dell'utente.
    assertEquals(0, fakeApi.downloadRequestCount)

    assertEquals(3, results.size)

    // Il risultato con URL diretto resta com'è.
    assertEquals("os-direct-1", results[0].id)
    assertEquals("https://sub.test/direct.srt", results[0].url)

    // I risultati "solo file_id" NON vengono più scartati: restano in lista per
    // essere risolti alla selezione.
    val pending1 = results.first { it.id == "os-pending-1" }
    assertEquals("", pending1.url)
    assertEquals(101L, pending1.fileId)
    assertEquals("OpenSubtitles v3", pending1.addonName)

    val pending2 = results.first { it.id == "os-pending-2" }
    assertEquals("", pending2.url)
    assertEquals(202L, pending2.fileId)
  }

  @Test
  fun cercaConTitoloFallbackQuandoMancaImdbEtmdb() = runBlocking {
    val fakeApi = FakeOpenSubtitlesApi()
    fakeApi.responseToReturn = Response.success(OpenSubtitlesSearchResponse(data = emptyList()))

    // Media senza IMDb né TMDB: la ricerca parte comunque (per titolo), ora che il
    // pannello "Sottotitoli Online" interroga l'API ufficiale e non solo l'addon.
    val provider = OpenSubtitlesSubtitleProvider(api = fakeApi)
    val results = provider.fetchSubtitles(title = "Corsari", isTv = false)

    assertEquals("movie", fakeApi.lastType)
    assertEquals("Corsari", fakeApi.lastQuery)
    assertTrue(results.isEmpty())
  }

  @Test
  fun risoluzioneSuRichiestaLimitataMaNessunRisultatoPerso() = runBlocking {
    val fakeApi = FakeOpenSubtitlesApi()
    fakeApi.responseToReturn = Response.success(
      OpenSubtitlesSearchResponse(
        data = (1L..4L).map { index ->
          OpenSubtitlesItemDto(
            id = "only-$index",
            attributes = OpenSubtitlesAttributesDto(
              language = "ita",
              files = listOf(OpenSubtitlesFileDto(fileId = index, fileName = "f$index.srt"))
            )
          )
        }
      )
    )

    val provider = OpenSubtitlesSubtitleProvider(api = fakeApi)
    val results = provider.fetchSubtitles(imdbId = "tt1234567", resolveLinksOnDemand = true)

    // Quota download consumata solo finché serve (max 3), MA tutti i 4 risultati
    // restano in lista: il quarto si risolve alla selezione.
    assertEquals(3, fakeApi.downloadRequestCount)
    assertEquals(4, results.size)
    assertEquals(3, results.count { it.url.isNotBlank() })
    val unresolved = results.first { it.url.isBlank() }
    assertEquals(4L, unresolved.fileId)
  }
}
