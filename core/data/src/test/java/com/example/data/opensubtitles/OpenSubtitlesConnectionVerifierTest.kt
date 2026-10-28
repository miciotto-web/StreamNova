package com.example.data.opensubtitles

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * La verifica della connessione OpenSubtitles usa l'endpoint di ricerca ufficiale già
 * in uso (`GET /api/v1/subtitles`): il risultato conta solo sul codice HTTP, senza
 * endpoint nuovi e senza mai esporre la chiave.
 */
class OpenSubtitlesConnectionVerifierTest {

  private val dispatcher = StandardTestDispatcher()

  /** Fake API Retrofit: registra le query ricevute e restituisce la risposta preparata. */
  private class FakeApi(
    private val responseFactory: () -> Response<OpenSubtitlesSearchResponse>
  ) : OpenSubtitlesApi {
        var lastQuery: String? = null

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
      lastQuery = query
      return responseFactory()
    }

    override suspend fun requestDownloadLink(
      request: OpenSubtitlesDownloadRequest
    ): Response<OpenSubtitlesDownloadResponse> =
      throw UnsupportedOperationException("La verifica non deve risolvere download")
  }

  private fun successBody() = OpenSubtitlesSearchResponse(totalCount = 0, data = emptyList())

  private fun errorBody(code: Int) = Response.error<OpenSubtitlesSearchResponse>(
    code,
    "{}".toResponseBody("application/json".toMediaType())
  )

  @Test
  fun senzaChiaveNonInviaRichieste() = runTest(dispatcher) {
    val api = FakeApi { successBody().let { Response.success(it) } }
    val verifier = OpenSubtitlesConnectionVerifier(api = api, ioDispatcher = dispatcher)

    assertEquals(
      OpenSubtitlesConnectionCheck.NotConfigured,
      verifier.check(null)
    )
    assertEquals(
      OpenSubtitlesConnectionCheck.NotConfigured,
      verifier.check("   ")
    )
    assertEquals("Nessuna richiesta senza chiave", null, api.lastQuery)
  }

  @Test
  fun http200ConfermaLaChiave() = runTest(dispatcher) {
    val api = FakeApi { Response.success(successBody()) }
    val verifier = OpenSubtitlesConnectionVerifier(api = api, ioDispatcher = dispatcher)

    val result = verifier.check("valid-key")

    assertEquals(OpenSubtitlesConnectionCheck.Valid, result)
    assertEquals("La verifica usa l'endpoint di ricerca ufficiale", "test", api.lastQuery)
  }

  @Test
  fun http401_403_406IndicanoChiaveRifiutata() = runTest(dispatcher) {
    listOf(401, 403, 406).forEach { code ->
      val api = FakeApi { errorBody(code) }
      val verifier = OpenSubtitlesConnectionVerifier(api = api, ioDispatcher = dispatcher)
      val result = verifier.check("bad-key")
      assertTrue(
        "HTTP $code deve essere classificato come chiave non valida",
        result is OpenSubtitlesConnectionCheck.Failed &&
          result.error == OpenSubtitlesVerifyError.INVALID_KEY &&
          result.httpCode == code
      )
    }
  }

  @Test
  fun altriErroriHttpNonSonoUnProblemaDiChiave() = runTest(dispatcher) {
    val api = FakeApi { errorBody(429) }
    val verifier = OpenSubtitlesConnectionVerifier(api = api, ioDispatcher = dispatcher)

    val result = verifier.check("key")

    assertTrue(
      result is OpenSubtitlesConnectionCheck.Failed &&
        result.error == OpenSubtitlesVerifyError.HTTP
    )
  }

  @Test
  fun eccezioneDiReteVieneClassificata() = runTest(dispatcher) {
    val api = FakeApi { throw java.io.IOException("offline") }
    val verifier = OpenSubtitlesConnectionVerifier(api = api, ioDispatcher = dispatcher)

    val result = verifier.check("key")

    assertTrue(
      result is OpenSubtitlesConnectionCheck.Failed &&
        result.error == OpenSubtitlesVerifyError.NETWORK
    )
  }
}
