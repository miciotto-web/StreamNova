package com.example.data.streaming.extractors

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Wrapper suspend cancellabile per OkHttp.
 * Se la coroutine chiamante viene cancellata (o scade un withTimeout),
 * la richiesta HTTP viene interrotta fisicamente tramite [Call.cancel].
 */
suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
  continuation.invokeOnCancellation {
    cancel()
  }
  enqueue(object : Callback {
    override fun onResponse(call: Call, response: Response) {
      if (continuation.isCancelled) {
        response.close()
        return
      }
      continuation.resume(response)
    }

    override fun onFailure(call: Call, e: IOException) {
      if (!continuation.isCancelled) {
        continuation.resumeWithException(e)
      }
    }
  })
}

/**
 * HTTP condiviso dagli estrattori hoster: User-Agent desktop, redirect seguiti
 * e timeout contenuti (gli hoster di solito rispondono in poche centinaia di ms).
 */
object ExtractorHttp {

  const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

  /**
   * Cookie di sessione condivisi dalla catena padre -> iframe/player.
   *
   * Il jar è unico per tutti gli hoster ma il matching resta per dominio,
   * percorso e scadenza: nessun cookie viene inviato a un host che non lo ha
   * rilasciato. Anche i client derivati ([mirrorClient]) ereditano lo stesso jar.
   */
  private val extractorCookies = InMemoryCookieJar()

  val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(12, TimeUnit.SECONDS)
    .readTimeout(18, TimeUnit.SECONDS)
    .retryOnConnectionFailure(true)
    .cookieJar(extractorCookies)
    .build()

  /** Svuota il cookie store (isolamento fra test; non usato in produzione). */
  internal fun clearCookies() {
    extractorCookies.clear()
  }

  /**
   * Timeout complessivo di una singola richiesta nei percorsi "per mirror".
   *
   * Non è il budget globale di [com.example.data.streaming.StreamManager]
   * (15 s): è il limite oltre il quale un tentativo verso un mirror viene
   * abbandonato e si passa al successivo. Il `callTimeout` di OkHttp copre
   * l'intera call (connessione, redirect, lettura del body) e la cancellazione
   * della coroutine continua a interrompere fisicamente la richiesta.
   */
  const val MIRROR_CALL_TIMEOUT_MS = 5_000L

  /** Client con [MIRROR_CALL_TIMEOUT_MS] per i tentativi indipendenti per mirror. */
  private val mirrorClient: OkHttpClient by lazy {
    client.newBuilder()
      .callTimeout(MIRROR_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      .build()
  }

  /**
   * Client da usare per la richiesta.
   *
   * `null` mantiene il client storico (nessun `callTimeout` globale): i provider
   * già funzionanti non cambiano comportamento. Un timeout esplicito diverso da
   * [MIRROR_CALL_TIMEOUT_MS] crea un client dedicato (caso raro, documentato).
   */
  private fun clientFor(callTimeoutMs: Long?): OkHttpClient = when {
    callTimeoutMs == null -> client
    callTimeoutMs == MIRROR_CALL_TIMEOUT_MS -> mirrorClient
    else -> client.newBuilder()
      .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
      .build()
  }

  /**
   * GET della pagina embed/risorsa.
   *
   * @param referer Referer da inviare: per molti CDN è obbligatorio (403 altrimenti).
   * @param callTimeoutMs limite complessivo della singola richiesta; `null` usa il
   *   client storico senza `callTimeout`.
   * @return corpo della risposta.
   * @throws IOException su status non 2xx.
   */
  suspend fun get(
    url: String,
    referer: String? = null,
    extraHeaders: Map<String, String> = emptyMap(),
    callTimeoutMs: Long? = null
  ): String = getWithFinalUrl(url, referer, extraHeaders, callTimeoutMs).second

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
  suspend fun getWithFinalUrl(
    url: String,
    referer: String? = null,
    extraHeaders: Map<String, String> = emptyMap(),
    callTimeoutMs: Long? = null
  ): Pair<String, String> {
    val builder = buildRequest(url)
      .header("User-Agent", USER_AGENT)
      .header("Accept", "*/*")
      .header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
      .get()
    if (!referer.isNullOrBlank()) builder.header("Referer", referer)
    extraHeaders.forEach { (k, v) -> builder.header(k, v) }

    val response = clientFor(callTimeoutMs).newCall(builder.build()).awaitResponse()
    response.use { resp ->
      val body = resp.body?.string().orEmpty()
      if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} su $url")
      val finalUrl = resp.request.url.toString()
      return finalUrl to body
    }
  }

  /** POST form-urlencoded (usato dagli shortener tipo stayonline). */
  suspend fun postForm(
    url: String,
    referer: String?,
    form: Map<String, String>,
    extraHeaders: Map<String, String> = emptyMap(),
    callTimeoutMs: Long? = null
  ): String {
    val body = form.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
    val builder = buildRequest(url)
      .header("User-Agent", USER_AGENT)
      .header("Accept", "*/*")
      .header("X-Requested-With", "XMLHttpRequest")
      .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
      .post(body.toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType()))
    if (!referer.isNullOrBlank()) builder.header("Referer", referer)
    extraHeaders.forEach { (k, v) -> builder.header(k, v) }

    val response = clientFor(callTimeoutMs).newCall(builder.build()).awaitResponse()
    response.use { resp ->
      val respBody = resp.body?.string().orEmpty()
      if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} su $url")
      return respBody
    }
  }
}
