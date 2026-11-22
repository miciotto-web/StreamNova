package com.example.data.streaming

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Tipo effettivo di un flusso HTTP riproducibile da ExoPlayer.
 *
 *  - [HLS]: playlist (`#EXTM3U`) → serve `HlsMediaSource`;
 *  - [PROGRESSIVE]: contenitore MP4/TS/WebM/... → serve il percorso progressivo
 *    (`ProgressiveMediaSource` con extractors che snifano i byte).
 *
 * La scelta HLS/progressivo non è differibile a valle: gli extractors
 * progressivi di Media3 non riconoscono il testo di una playlist HLS, e
 * `HlsMediaSource` non legge un MP4. Una classificazione sbagliata è quindi
 * un errore di riproduzione (`PARSING_CONTAINER_UNSUPPORTED`).
 */
enum class HttpStreamKind { HLS, PROGRESSIVE }

/**
 * Classificazione del tipo effettivo di uno stream HTTP restituito da un addon
 * Stremio (livello comune: nessuna logica dedicata a singoli addon).
 *
 * MOTIVAZIONE (audit compatibilità proxy HTTP): gli endpoint proxy degli addon
 * servono contenuto da URL dinamici (token, percorsi opachi) che NON dichiarano
 * l'estensione del file. La classificazione basata sul solo URL è insufficiente:
 * una playlist HLS dietro `https://proxy.example/stream?token=...` verrebbe
 * riprodotta come file progressivo e fallirebbe. Il contenuto decide:
 *
 *  1. **Fast path** ([urlKind]): estensione dichiarata dal PATH dell'URL
 *     (`.m3u8`, `.mp4`, `.mkv`, ...). Sincrona, nessuna richiesta.
 *  2. **Sonda HTTP** ([probeKind]): richiesta GET leggera con gli STESSI header
 *     dello stream (`behaviorHints.proxyHeaders.request`): Content-Type della
 *     risposta, poi sniffing del corpo (`#EXTM3U`, box `ftyp`). Inconclusivo →
 *     `null`: nessun valore inventato.
 *
 * Gli URL NON vengono mai alterati: query string, token e parametri firmati
 * restano intatti (la sonda legge, non modifica). Il classificatore non applica
 * alcuna logica Debrid/Torrent: un URL proxy resta una sorgente HTTP diretta.
 */
object StremioHttpStreamClassifier {

  private const val TAG = "StremioHttpCompat"

  /** Massimo numero di sonde HTTP per una singola risoluzione di sorgenti. */
  const val MAX_PROBES = 8

  /** Estensioni di PATH che dichiarano un contenitore progressivo. */
  private val PROGRESSIVE_EXTENSIONS = listOf(
    ".mp4", ".mkv", ".webm", ".avi", ".m4v", ".ts", ".mov", ".flv"
  )

  /**
   * Tipo dichiarato dal PATH dell'URL (estensione). `null` se l'URL è ambiguo:
   * serve la sonda sul contenuto. La query string è ESCLUSA dalla verifica —
   * un token che contiene ".mp4" non dichiara il formato del contenuto.
   */
  fun urlKind(url: String?): HttpStreamKind? {
    val path = url?.trim()?.substringBefore('?')?.substringBefore('#')?.lowercase().orEmpty()
    if (path.isEmpty()) return null
    if (path.endsWith(".m3u8")) return HttpStreamKind.HLS
    if (PROGRESSIVE_EXTENSIONS.any { path.endsWith(it) }) return HttpStreamKind.PROGRESSIVE
    return null
  }

  /**
   * Tipo effettivo osservato con una sonda HTTP leggera.
   *
   * La richiesta porta gli header dello stream: un proxy che filtra per
   * User-Agent/Referer risponde realmente, invece di classificare su un 403.
   * Restituisce `null` quando la risposta non conclude (errore HTTP, pagina
   * HTML, contenuto non riconosciuto): il chiamante conserva il comportamento
   * storico del player senza inventare una classificazione.
   */
  internal suspend fun probeKind(
    url: String,
    headers: Map<String, String>,
    probe: StreamProbe = OkHttpStreamProbe()
  ): HttpStreamKind? = try {
    val response = probe.get(url, headers, rangeBytes = null)
    if (!response.isSuccessful) {
      logProbe(url, "http-${response.code}", null)
      null
    } else {
      classifyResponse(response, url)
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    // Rete assente, timeout della sonda, URL malformato: inconclusivo.
    logProbe(url, "probe-error-${e.javaClass.simpleName}", null)
    null
  }

  /** Classifica una risposta riuscita: Content-Type prima, poi sniffing del corpo. */
  internal fun classifyResponse(response: ProbeResponse, url: String): HttpStreamKind? {
    val kind = fromContentType(response.contentType) ?: fromBody(response.body)
    logProbe(url, if (response.finalUrl != url) "redirected" else "direct", kind)
    return kind
  }

  /** Content-Type dichiarato dal server: mpegurl → HLS, contenitori video → progressivo. */
  internal fun fromContentType(contentType: String?): HttpStreamKind? {
    val type = contentType?.lowercase()?.substringBefore(';')?.trim().orEmpty()
    if (type.isEmpty()) return null
    return when {
      type.contains("mpegurl") || type.contains("vnd.apple.mpegurl") -> HttpStreamKind.HLS
      type.startsWith("video/") -> HttpStreamKind.PROGRESSIVE
      // Server che non dichiarano il MIME preciso ma servono byte binari.
      type == "application/octet-stream" || type == "binary/octet-stream" ->
        HttpStreamKind.PROGRESSIVE
      else -> null
    }
  }

  /**
   * Sniffing del corpo (fallback quando il Content-Type manca o è generico).
   * Il corpo è decodificato UTF-8: `#EXTM3U` e `ftyp` sono ASCII e sopravvivono
   * alla decodifica dei primi byte di un MP4.
   */
  internal fun fromBody(body: String): HttpStreamKind? {
    val trimmed = body.trimStart('\uFEFF', '\r', '\n', ' ', '\t')
    if (trimmed.startsWith("#EXTM3U")) return HttpStreamKind.HLS
    if (trimmed.length >= 8 && trimmed.take(64).contains("ftyp")) {
      return HttpStreamKind.PROGRESSIVE
    }
    return null
  }

  /**
   * Completa il campo `isProgressive` delle sorgenti Stremio con URL ambiguo.
   *
   *  - sorgenti con estensione nota nel path → flag impostato in modo definitivo
   *    (`.m3u8` → HLS → `isProgressive=false`; contenitori → `true`);
   *  - sorgenti ambigue → sonda HTTP (massimo [MAX_PROBES], in parallelo, con
   *    i rispettivi header): esito noto → flag impostato; inconclusivo → flag
   *    `null`, il player applica il suo default storico per URL senza estensione.
   *
   * L'ordine delle sorgenti NON cambia: la selezione e il ranking restano quelli
   * di [StreamManager]. Nessun URL viene modificato.
   */
  internal suspend fun refineSources(
    sources: List<StreamSource>,
    probe: StreamProbe = OkHttpStreamProbe()
  ): List<StreamSource> = coroutineScope {
    if (sources.isEmpty()) return@coroutineScope sources

    // Fast path: estensione nel path → flag certo, nessuna richiesta.
    val resolved = sources.map { source ->
      val kind = urlKind(source.streamUrl)
      when (kind) {
        HttpStreamKind.HLS -> source.copy(isProgressive = false)
        HttpStreamKind.PROGRESSIVE -> source.copy(isProgressive = true)
        null -> source
      }
    }

    // Solo le ambigue sono sondate: sono i link proxy dinamici degli addon.
    val toProbe = resolved.filter { it.streamUrl != null && urlKind(it.streamUrl) == null }
      .take(MAX_PROBES)
    if (toProbe.isEmpty()) return@coroutineScope resolved

    val probed = toProbe.map { source ->
      async { source to probeKind(source.streamUrl.orEmpty(), source.headers, probe) }
    }.awaitAll().toMap()

    resolved.map { source ->
      when (probed[source]) {
        HttpStreamKind.HLS -> source.copy(isProgressive = false)
        HttpStreamKind.PROGRESSIVE -> source.copy(isProgressive = true)
        // Sonda inconclusiva o sorgente oltre il cap: il flag esistente resta
        // (di norma `null`): il player applica il suo default per URL ambigui.
        null -> source
      }
    }
  }

  /**
   * Log diagnostico della classificazione: SOLO host e tipo osservato, mai
   * path/query/token/valori di header (i path dei link proxy contengono le
   * credenziali degli addon). Il Content-Type è un valore dichiarato dal
   * server, non un dato utente.
   */
  private fun logProbe(url: String, via: String, kind: HttpStreamKind?) {
    val host = url.trim().toHttpUrlOrNull()?.host ?: "unknown-host"
    Log.i(TAG, "DIAG-CLASSIFY host=$host via=$via kind=${kind?.name ?: "UNKNOWN"}")
  }
}
