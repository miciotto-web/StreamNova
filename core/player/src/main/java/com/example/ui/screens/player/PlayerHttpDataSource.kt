package com.example.ui.screens.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Factory HTTP del player basata su OkHttp.
 *
 * **Perché non `DefaultHttpDataSource`.** Su Android `DefaultHttpDataSource` usa
 * `HttpURLConnection` (implementazione `com.android.okhttp`), il cui `InputStream` è un
 * `java.nio.channels.InterruptibleChannel`. Quando Media3 annulla un caricamento per una
 * seek (`ProgressiveMediaPeriod.seekToUs` → `Loader.cancelLoading` → `Thread.interrupt`),
 * l'interrupt chiude il canale; la chiusura deve scartare il body residuo e rientra in
 * `okio.AsyncTimeout.enter()`, che lancia:
 *
 * ```
 * java.lang.IllegalStateException: Unbalanced enter/exit
 * ```
 *
 * L'eccezione sfugge dal playback thread e Media3 la converte in
 * `ExoPlaybackException: Unexpected runtime error` (`ERROR_CODE_FAILED_RUNTIME_CHECK`),
 * cioè l'errore mostrato dopo la ricerca rapida su una sorgente progressiva HTTP.
 *
 * OkHttp usa socket non interruptibili: l'interrupt del `Loader` non provoca la chiusura
 * reentrante della sorgente, quindi la seek annulla il caricamento senza eccezioni. È lo
 * stesso trasporto usato dal resto dell'app (addon, resolver, download).
 */
@UnstableApi
object PlayerHttpDataSource {

  const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
  const val DEFAULT_READ_TIMEOUT_MS = 20_000

  /**
   * Costruisce la factory HTTP del player.
   *
   * @param userAgent User-Agent applicato a ogni richiesta (sovrascrivibile con
   *   [OkHttpDataSource.Factory.setUserAgent] quando la sorgente dichiara il proprio).
   * @param connectTimeoutMs timeout di connessione, allineato al vecchio
   *   `DefaultHttpDataSource.Factory`.
   * @param readTimeoutMs timeout di lettura, allineato al vecchio
   *   `DefaultHttpDataSource.Factory`.
   */
  fun createFactory(
    userAgent: String,
    connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
  ): OkHttpDataSource.Factory {
    val client = OkHttpClient.Builder()
      .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
      .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
      // Stesso comportamento di DefaultHttpDataSource.setAllowCrossProtocolRedirects(true).
      .followRedirects(true)
      .followSslRedirects(true)
      .build()
    return OkHttpDataSource.Factory(client).setUserAgent(userAgent)
  }
}
