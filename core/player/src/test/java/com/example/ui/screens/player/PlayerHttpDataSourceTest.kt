package com.example.ui.screens.player

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regressione: il player NON deve usare `DefaultHttpDataSource` per le sorgenti HTTP.
 *
 * Su Android `DefaultHttpDataSource` si appoggia a `HttpURLConnection`
 * (`com.android.okhttp`). Quando una seek annulla il caricamento in corso, il `Loader`
 * di Media3 interrompe il thread di lettura; l'interrupt chiude il canale interruptibile
 * e la chiusura, dovendo scartare il body residuo, rientra in `okio.AsyncTimeout.enter()`
 * lanciando `IllegalStateException: Unbalanced enter/exit`. Media3 la riporta come
 * `ExoPlaybackException: Unexpected runtime error` (errore osservato dopo la ricerca
 * rapida su sorgenti progressive).
 *
 * La factory del player usa quindi OkHttp, il cui socket non è interruptibile: il
 * trasporto non produce quella chiusura reentrante. Il test blinda la scelta del
 * trasporto (e verifica che header e User-Agent continuino ad arrivare alla richiesta),
 * così un ritorno accidentale a `DefaultHttpDataSource` fallisce subito.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerHttpDataSourceTest {

  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  @Test
  fun `la factory del player usa il trasporto OkHttp`() {
    // Tipo d'interfaccia: il controllo dinamico qui sotto non è tautologico e fallisce
    // se la factory tornasse a DefaultHttpDataSource.
    val factory: HttpDataSource.Factory =
      PlayerHttpDataSource.createFactory("StreamNova/test")
    val source = factory.createDataSource()

    assertTrue(
      "Il player deve usare OkHttpDataSource: DefaultHttpDataSource riapre il bug " +
        "'Unbalanced enter/exit' alla cancellazione del loader durante la seek",
      source is OkHttpDataSource
    )
  }

  @Test
  fun `user agent e header della sorgente arrivano alla richiesta`() {
    server.enqueue(MockResponse().setBody("ok"))
    val factory = PlayerHttpDataSource.createFactory("StreamNova/test")
      .setDefaultRequestProperties(mapOf("X-StreamNova-Header" to "token"))
    val source = factory.createDataSource()
    val spec = DataSpec.Builder()
      .setUri(Uri.parse(server.url("/video.mkv").toString()))
      .build()

    val read = try {
      source.open(spec)
      val buffer = ByteArray(8)
      val count = source.read(buffer, 0, buffer.size)
      buffer.copyOf(count).decodeToString()
    } finally {
      source.close()
    }

    assertEquals("ok", read)
    val request = server.takeRequest()
    assertEquals("StreamNova/test", request.getHeader("User-Agent"))
    assertEquals("token", request.getHeader("X-StreamNova-Header"))
  }
}
