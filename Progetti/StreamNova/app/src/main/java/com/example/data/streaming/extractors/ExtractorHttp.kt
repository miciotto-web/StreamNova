package com.example.data.streaming.extractors

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * HTTP condiviso dagli estrattori hoster: User-Agent desktop, redirect seguiti
 * e timeout contenuti (gli hoster di solito rispondono in poche centinaia di ms).
 */
object ExtractorHttp {

  const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

  val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(12, TimeUnit.SECONDS)
    .readTimeout(18, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .build()

  /**
   * GET della pagina embed/risorsa.
   *
   * @param referer Referer da inviare: per molti CDN è obbligatorio (403 altrimenti).
   * @return corpo della risposta.
   * @throws IOException su status non 2xx.
   */
  fun get(url: String, referer: String? = null, extraHeaders: Map<String, String> = emptyMap()): String =
    getWithFinalUrl(url, referer, extraHeaders).second

  /** Costruisce la richiesta validando l'URL (messaggio d'errore con contesto). */
  private fun buildRequest(url: String): Request.Builder = try {
    Request.Builder().url(url)
  } catch (e: IllegalArgumentException) {
    throw IOException("URL non valido ($url): ${e.message}")
  }

  /**
   * GET che ritorna anche l'**URL finale** dopo i redirect.
   *
   * Serve agli estrattori che devono POSTare sulla pagina definitiva: un POST
   * inviato a un URL che risponde 302 verrebbe degradato in GET (OkHttp) e
   * perderebbe il body.
   */
  fun getWithFinalUrl(
    url: String,
    referer: String? = null,
    extraHeaders: Map<String, String> = emptyMap()
  ): Pair<String, String> {
    val builder = buildRequest(url)
      .header("User-Agent", USER_AGENT)
      .header("Accept", "*/*")
      .header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
      .get()
    if (!referer.isNullOrBlank()) builder.header("Referer", referer)
    extraHeaders.forEach { (k, v) -> builder.header(k, v) }

    client.newCall(builder.build()).execute().use { response ->
      val body = response.body?.string().orEmpty()
      if (!response.isSuccessful) throw IOException("HTTP ${response.code} su $url")
      val finalUrl = response.request.url.toString()
      return finalUrl to body
    }
  }

  /** POST form-urlencoded (usato dagli shortener tipo stayonline). */
  fun postForm(url: String, referer: String?, form: Map<String, String>, extraHeaders: Map<String, String> = emptyMap()): String {
    val body = form.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
    val builder = buildRequest(url)
      .header("User-Agent", USER_AGENT)
      .header("Accept", "*/*")
      .header("X-Requested-With", "XMLHttpRequest")
      .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
      .post(body.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType()))
    if (!referer.isNullOrBlank()) builder.header("Referer", referer)
    extraHeaders.forEach { (k, v) -> builder.header(k, v) }

    client.newCall(builder.build()).execute().use { response ->
      val respBody = response.body?.string().orEmpty()
      if (!response.isSuccessful) throw IOException("HTTP ${response.code} su $url")
      return respBody
    }
  }
}
