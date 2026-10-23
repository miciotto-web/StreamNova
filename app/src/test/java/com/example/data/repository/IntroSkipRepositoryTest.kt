package com.example.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntroSkipRepositoryTest {

  @Before
  fun setup() {
    IntroSkipRepository.clearCache()
    // Nessun test tocca la rete reale per la risoluzione dell'id IMDb: i test che
    // esercitano quel percorso installano il proprio resolver.
    IntroSkipRepository.tmdbImdbResolver = { null }
  }

  @After
  fun tearDown() {
    IntroSkipRepository.clearCache()
    IntroSkipRepository.resetClient()
    IntroSkipRepository.resetTmdbImdbResolver()
  }

  @Test
  fun rispostaValidaRestituisceIntroWindowECache() = runBlocking {
    var networkCalls = 0
    val fakeClient = OkHttpClient.Builder()
      .addInterceptor(Interceptor { chain ->
        networkCalls++
        val request = chain.request()
        val url = request.url.toString()
        if (url.contains("imdb_id=tt0944947") && url.contains("season=1") && url.contains("episode=1")) {
          val json = """{"imdb_id":"tt0944947","season":1,"episode":1,"start_ms":15000,"end_ms":75000}"""
          Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(json.toResponseBody("application/json".toMediaType()))
            .build()
        } else {
          Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(404)
            .message("Not Found")
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()
        }
      })
      .build()

    IntroSkipRepository.setClientForTesting(fakeClient)

    val window = IntroSkipRepository.getIntroWindow(
      imdbId = "tt0944947",
      tmdbId = null,
      season = 1,
      episode = 1
    )

    assertNotNull(window)
    assertEquals(15_000L, window?.startMs)
    assertEquals(75_000L, window?.endMs)
    assertEquals(1, networkCalls)

    // Seconda chiamata: deve attingere dalla cache in memoria senza nuova chiamata di rete
    val cached = IntroSkipRepository.getIntroWindow(
      imdbId = "tt0944947",
      tmdbId = null,
      season = 1,
      episode = 1
    )
    assertNotNull(cached)
    assertEquals(15_000L, cached?.startMs)
    assertEquals(75_000L, cached?.endMs)
    assertEquals(1, networkCalls) // Invariato grazie alla cache
  }

  /**
   * Titolo identificato dal solo id TMDB (serie del catalogo TMDB: `MediaItem.id` =
   * "tmdb_tv_<id>"): l'id IMDb viene risolto e la query parte con `imdb_id`.
   *
   * IntroDB non supporta `tmdb_id` (risponde 400 "Invalid query params"): prima della
   * correzione questo percorso interrogava `?tmdb_id=...` e non otteneva mai la finestra,
   * quindi il pulsante "Salta intro" non poteva comparire per quelle serie.
   */
  @Test
  fun titoloIdentificatoSoloDalTmdbIdRisolveImdbEInterrogaIntroDbConImdbId() = runBlocking {
    var queriedUrls = mutableListOf<String>()
    val fakeClient = OkHttpClient.Builder()
      .addInterceptor(Interceptor { chain ->
        val request = chain.request()
        queriedUrls.add(request.url.toString())
        val json = """{"imdb_id":"tt23148340","season":1,"episode":1,"start_ms":876000,"end_ms":920000,"confidence":1}"""
        Response.Builder()
          .request(request)
          .protocol(Protocol.HTTP_1_1)
          .code(200)
          .message("OK")
          .body(json.toResponseBody("application/json".toMediaType()))
          .build()
      })
      .build()

    IntroSkipRepository.setClientForTesting(fakeClient)
    val resolvedFor = mutableListOf<Int>()
    IntroSkipRepository.tmdbImdbResolver = { tmdbId ->
      resolvedFor.add(tmdbId)
      "tt23148340"
    }

    val window = IntroSkipRepository.getIntroWindow(
      imdbId = null,
      tmdbId = 213562,
      season = 1,
      episode = 1
    )

    assertNotNull(window)
    assertEquals(876_000L, window?.startMs)
    assertEquals(920_000L, window?.endMs)
    assertEquals("l'id IMDb deve essere risolto una sola volta", listOf(213562), resolvedFor)
    assertEquals(1, queriedUrls.size)
    assert(queriedUrls.single().contains("imdb_id=tt23148340&season=1&episode=1")) {
      "la query IntroDB deve usare imdb_id: $queriedUrls"
    }
    assert(!queriedUrls.single().contains("tmdb_id=")) {
      "la query IntroDB non deve mai usare tmdb_id (400 Invalid query params): $queriedUrls"
    }
  }

  /** Senza id IMDb risolvibile non si interroga IntroDB: nessuna query, nessuna finestra. */
  @Test
  fun tmdbIdSenzaImdbRisolvibileNonInterrogaIntroDb() = runBlocking {
    var networkCalls = 0
    val fakeClient = OkHttpClient.Builder()
      .addInterceptor(Interceptor { chain ->
        networkCalls++
        Response.Builder()
          .request(chain.request())
          .protocol(Protocol.HTTP_1_1)
          .code(200)
          .message("OK")
          .body("{}".toResponseBody("application/json".toMediaType()))
          .build()
      })
      .build()

    IntroSkipRepository.setClientForTesting(fakeClient)
    IntroSkipRepository.tmdbImdbResolver = { null }

    val window = IntroSkipRepository.getIntroWindow(
      imdbId = null,
      tmdbId = 213562,
      season = 1,
      episode = 1
    )

    assertNull(window)
    assertEquals(0, networkCalls)
  }

  @Test
  fun risposta404RestituisceNull() = runBlocking {
    val fakeClient = OkHttpClient.Builder()
      .addInterceptor(Interceptor { chain ->
        Response.Builder()
          .request(chain.request())
          .protocol(Protocol.HTTP_1_1)
          .code(404)
          .message("Not Found")
          .body("{\"error\":\"Not Found\"}".toResponseBody("application/json".toMediaType()))
          .build()
      })
      .build()

    IntroSkipRepository.setClientForTesting(fakeClient)

    val window = IntroSkipRepository.getIntroWindow(
      imdbId = "tt0000000",
      tmdbId = null,
      season = 1,
      episode = 1
    )

    assertNull(window)
  }

  @Test
  fun jsonInvalidoOTimestampIncoerentiRestituisconoNull() = runBlocking {
    val fakeClient = OkHttpClient.Builder()
      .addInterceptor(Interceptor { chain ->
        val json = """{"start_ms":50000,"end_ms":20000}""" // end < start
        Response.Builder()
          .request(chain.request())
          .protocol(Protocol.HTTP_1_1)
          .code(200)
          .message("OK")
          .body(json.toResponseBody("application/json".toMediaType()))
          .build()
      })
      .build()

    IntroSkipRepository.setClientForTesting(fakeClient)

    val window = IntroSkipRepository.getIntroWindow(
      imdbId = "tt1234567",
      tmdbId = null,
      season = 1,
      episode = 1
    )

    assertNull(window)
  }

  @Test
  fun parametriNonValidiRestituisconoNullImmediatamente() = runBlocking {
    assertNull(IntroSkipRepository.getIntroWindow(null, null, 1, 1))
    assertNull(IntroSkipRepository.getIntroWindow("tt123", null, 0, 1))
    assertNull(IntroSkipRepository.getIntroWindow("tt123", null, 1, -1))
  }
}
