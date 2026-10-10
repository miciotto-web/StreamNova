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
  }

  @After
  fun tearDown() {
    IntroSkipRepository.clearCache()
    IntroSkipRepository.resetClient()
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

  @Test
  fun queryTramiteTmdbIdSeImdbAssente() = runBlocking {
    var queriedUrl = ""
    val fakeClient = OkHttpClient.Builder()
      .addInterceptor(Interceptor { chain ->
        val request = chain.request()
        queriedUrl = request.url.toString()
        val json = """{"tmdb_id":1399,"season":2,"episode":3,"start_ms":20000,"end_ms":90000}"""
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

    val window = IntroSkipRepository.getIntroWindow(
      imdbId = null,
      tmdbId = 1399,
      season = 2,
      episode = 3
    )

    assertNotNull(window)
    assertEquals(20_000L, window?.startMs)
    assertEquals(90_000L, window?.endMs)
    assert(queriedUrl.contains("tmdb_id=1399&season=2&episode=3"))
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
