package com.example.ui.screens.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Regressione del crash "Errore playback: Unexpected runtime error" durante la
 * ricerca rapida all'indietro.
 *
 * La causa era `IllegalStateException: Unbalanced enter/exit`: con
 * `DefaultHttpDataSource` (HttpURLConnection) la cancellazione del loader
 * eseguita a ogni seek chiude la connessione dal thread della seek mentre il
 * thread di caricamento sta leggendo, e Okio segnala l'enter/exit sbilanciato.
 * Media3 avvolge la RuntimeException in `ExoPlaybackException` senza codice, da
 * cui il messaggio generico mostrato all'utente.
 *
 * Il contratto da non violare è quindi: il percorso di riproduzione (player
 * principale e decoder di anteprima, che condividono la factory) deve creare
 * `OkHttpDataSource`, il quale annulla la `Call` senza chiudere la connessione
 * dal thread di seek.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlayerHttpDataSourceFactoryTest {

  @Test
  fun `il trasporto del player e OkHttpDataSource e mai DefaultHttpDataSource`() {
    val factory = PlayerHttpDataSourceFactory(initialUserAgent = "StreamNovaTV/test")

    val dataSource = factory.asFactory().createDataSource()

    assertTrue(
      "Il player deve usare OkHttpDataSource: DefaultHttpDataSource chiude la " +
        "connessione dal thread della seek (Unbalanced enter/exit).",
      dataSource is OkHttpDataSource
    )
    assertFalse(
      "DefaultHttpDataSource non deve tornare nel percorso di riproduzione.",
      dataSource is DefaultHttpDataSource
    )
  }

  @Test
  fun `updateHeaders applica User-Agent e intestazioni della sorgente attiva`() {
    val factory = PlayerHttpDataSourceFactory(initialUserAgent = "StreamNovaTV/default")
    assertEquals("StreamNovaTV/default", factory.currentUserAgent)

    factory.updateHeaders(
      mapOf("User-Agent" to "StreamNovaTV/custom", "Referer" to "https://example.com"),
      defaultUserAgent = "StreamNovaTV/default"
    )
    assertEquals("StreamNovaTV/custom", factory.currentUserAgent)
    assertEquals("https://example.com", factory.currentHeaders["Referer"])

    // Sorgente senza User-Agent esplicito: torna il default, le altre header restano.
    factory.updateHeaders(
      mapOf("Referer" to "https://other.example"),
      defaultUserAgent = "StreamNovaTV/default"
    )
    assertEquals("StreamNovaTV/default", factory.currentUserAgent)
    assertEquals("https://other.example", factory.currentHeaders["Referer"])
  }

  @Test
  fun `la factory resta riutilizzabile e crea una DataSource per ogni richiesta`() {
    val factory = PlayerHttpDataSourceFactory(initialUserAgent = "StreamNovaTV/test")
    val httpFactory = factory.asFactory()

    val first = httpFactory.createDataSource()
    val second = httpFactory.createDataSource()

    assertNotSame(first, second)
    assertTrue(first is OkHttpDataSource)
    assertTrue(second is OkHttpDataSource)
  }

  @Test
  fun `il client condiviso mantiene i timeout e i redirect del trasporto precedente`() {
    val client = PlayerHttpDataSourceFactory.defaultClient()

    assertEquals(PlayerHttpDataSourceFactory.CONNECT_TIMEOUT_MS, client.connectTimeoutMillis.toLong())
    assertEquals(PlayerHttpDataSourceFactory.READ_TIMEOUT_MS, client.readTimeoutMillis.toLong())
    assertTrue(client.followRedirects)
    assertTrue(client.followSslRedirects)
  }
}
