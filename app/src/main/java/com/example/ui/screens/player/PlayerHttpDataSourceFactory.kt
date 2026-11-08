package com.example.ui.screens.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Factory HTTP unica del percorso di riproduzione: player principale, decoder
 * dedicato dell'anteprima di seek, HLS e flussi progressivi passano tutti da qui.
 *
 * Usa [OkHttpDataSource] e NON `DefaultHttpDataSource`: ogni seek all'indietro
 * fuori dal buffer obbliga ExoPlayer a cancellare il caricamento in corso
 * (`Loader.cancelLoading` → `Thread.interrupt` sul thread di loading). Con
 * `DefaultHttpDataSource` (basato su `HttpURLConnection`, cioè l'OkHttp interno
 * di Android) l'interrupt chiude il channel di rete dal thread che esegue la
 * seek mentre il thread di loading sta ancora leggendo: Okio solleva
 * `IllegalStateException: Unbalanced enter/exit`, che Media3 riporta come
 * `ExoPlaybackException: Unexpected runtime error`. [OkHttpDataSource] annulla
 * la `Call` senza chiudere la connessione dal thread di seek, quindi la stessa
 * sequenza di seek ripetute non produce più l'eccezione.
 *
 * La factory è mutabile in place: cambiare sorgente aggiorna intestazioni e
 * User-Agent senza ricreare la DataSource né il player.
 */
@UnstableApi
internal class PlayerHttpDataSourceFactory(
  client: OkHttpClient = defaultClient(),
  initialUserAgent: String,
  initialHeaders: Map<String, String> = emptyMap()
) {

  private val factory = OkHttpDataSource.Factory(client)

  /** User-Agent applicato alle richieste create da questa factory. */
  internal var currentUserAgent: String = initialUserAgent
    private set

  /** Intestazioni predefinite applicate alle richieste create da questa factory. */
  internal var currentHeaders: Map<String, String> = initialHeaders
    private set

  init {
    apply()
  }

  /**
   * Aggiorna in place User-Agent e intestazioni della sorgente attiva. Il player
   * mantiene la stessa istanza e la factory applica i valori alla prossima
   * `createDataSource()` (cioè al successivo `prepare()`).
   */
  fun updateHeaders(headers: Map<String, String>, defaultUserAgent: String) {
    currentHeaders = headers
    currentUserAgent = headers["User-Agent"] ?: defaultUserAgent
    apply()
  }

  /** Factory da passare a `DefaultMediaSourceFactory` e `HlsMediaSource.Factory`. */
  fun asFactory(): HttpDataSource.Factory = factory

  private fun apply() {
    factory.setUserAgent(currentUserAgent)
    factory.setDefaultRequestProperties(currentHeaders)
  }

  companion object {
    const val CONNECT_TIMEOUT_MS = 15_000L
    const val READ_TIMEOUT_MS = 20_000L

    /**
     * Client condiviso dalle richieste media: pool di connessioni riusato fra
     * player principale e decoder di anteprima, redirect cross-protocol
     * consentiti e stessi timeout del trasporto precedente.
     */
    fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
      .connectionPool(NuvioExoPlayerPerformanceHelper.sharedConnectionPool)
      .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      .followRedirects(true)
      .followSslRedirects(true)
      .build()
  }
}
