package com.example.data.streaming

import com.example.data.streaming.extractors.ExtractorHttp
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import com.example.data.streaming.extractors.awaitResponse

/** Esito della verifica di riproducibilità di una sorgente. */
enum class SourcePlayability {
  /** Manifest raggiungibile, playlist valida e primo segmento disponibile. */
  Playable,

  /** La risorsa non è utilizzabile (HTTP 4xx, manifest non HLS, segmento assente). */
  Unavailable,

  /**
   * Errore temporaneo (rete, timeout, 408/425/429/5xx): non prova che la
   * sorgente non esista. La selezione passa al candidato successivo ma la
   * sorgente non viene dichiarata inesistente.
   */
  TransientFailure
}

/**
 * Esito esteso della verifica: playability (l'unica informazione che la
 * selezione legge) + sintesi HLS osservata dalle risposte sondate. La sintesi
 * è METADATO OSSERVATIVO per il report di riproduzione: nessuna decisione la
 * consuma. `summary` è `null` per i file progressivi e per i corpi non-HLS.
 */
data class SourceVerification(
  val playability: SourcePlayability,
  val summary: HlsPlaylistSummary?
)

/**
 * Contratto di verifica della riproducibilità **effettiva**, iniettabile nei
 * test. Le implementazioni non devono avviare player: solo richieste HTTP
 * sequenziali e leggere.
 */
fun interface SourceVerifier {
  suspend fun verify(source: StreamSource): SourcePlayability
}

/** Implementazione HTTP condivisa dalla selezione automatica. */
object StreamSourceVerifier {
  val DEFAULT: SourceVerifier = HttpSourceVerifier()
}

/** Risposta minima di una sonda HTTP (solo status, URL finale e corpo limitato). */
internal data class ProbeResponse(
  val code: Int,
  val finalUrl: String,
  val body: String,
  val contentType: String?
) {
  val isSuccessful: Boolean get() = code in 200..299
}

/** Sonda HTTP iniettabile: legge al massimo [rangeBytes] byte del corpo. */
internal fun interface StreamProbe {
  suspend fun get(
    url: String,
    headers: Map<String, String>,
    rangeBytes: Int?
  ): ProbeResponse
}

/**
 * Verifica che una sorgente sia **realmente riproducibile** senza avviare una
 * riproduzione.
 *
 * Per HLS:
 *  1. scarica il manifest (o la playlist di variante) con gli stessi header
 *     usati dal player;
 *  2. se è una master playlist segue la variante migliore (o quella dichiarata
 *     dalla sorgente);
 *  3. richiede una media playlist con almeno un segmento;
 *  4. prova l'eventuale segmento di inizializzazione e il primo segmento con
 *     una richiesta `Range` (pochi byte).
 *
 * Per i file progressivi è sufficiente una richiesta `Range` riuscita. Una
 * pagina HTML al posto del flusso non è mai considerata riproducibile.
 *
 * Le richieste sono **sequenziali**: nessuna riproduzione multipla in parallelo.
 */
internal class HttpSourceVerifier(
  private val probe: StreamProbe = OkHttpStreamProbe()
) : SourceVerifier {

  override suspend fun verify(source: StreamSource): SourcePlayability =
    verifyDetailed(source).playability

  /**
   * Stessa verifica di [verify], restituendo anche la sintesi HLS osservata
   * (A-04): il testo già scaricato dalla sonda viene analizzato da
   * [HlsPlaylistSummaryParser] — nessuna richiesta aggiuntiva. Quando la sonda
   * segue una master fino alla playlist di variante, la sintesi della playlist
   * giocata (tipo, segmenti, live/VOD) viene arricchita con la qualità e i
   * codec dichiarati dalla master: dati reali osservati, mai inventati.
   */
  suspend fun verifyDetailed(source: StreamSource): SourceVerification {
    val url = source.streamUrl?.trim().orEmpty()
    if (!StreamManager.isPlayableDirectUrl(url)) return SourceVerification(SourcePlayability.Unavailable, null)

    return try {
      var currentUrl = url
      var response = probe.get(currentUrl, source.headers, rangeBytes = null)
      if (!response.isSuccessful) return SourceVerification(classify(response.code), null)
      if (isHtml(response)) return SourceVerification(SourcePlayability.Unavailable, null)

      var body = response.body.trimStart('\uFEFF', '\r', '\n', ' ', '\t')
      var hops = 0
      var masterSummary: HlsPlaylistSummary? = null
      while (HlsPlaylist.isHls(body) && HlsPlaylist.isMaster(body) && hops < MAX_MASTER_HOPS) {
        masterSummary = HlsPlaylistSummaryParser.analyze(body, currentUrl)
        val variant = pickVariant(body, currentUrl, source.verifiedHeight)
          ?: return SourceVerification(SourcePlayability.Unavailable, null)
        currentUrl = variant.url
        response = probe.get(currentUrl, source.headers, rangeBytes = null)
        if (!response.isSuccessful) return SourceVerification(classify(response.code), null)
        if (isHtml(response)) return SourceVerification(SourcePlayability.Unavailable, null)
        body = response.body.trimStart('\uFEFF', '\r', '\n', ' ', '\t')
        hops++
      }

      if (!HlsPlaylist.isHls(body)) {
        // File progressivo (MP4/WebM) o flusso diretto: il 2xx è sufficiente.
        return SourceVerification(SourcePlayability.Playable, null)
      }

      HlsPlaylist.initSegmentUri(body, currentUrl)?.let { initUrl ->
        val init = probe.get(initUrl, source.headers, rangeBytes = PROBE_BYTES)
        if (!init.isSuccessful) return SourceVerification(classify(init.code), null)
      }

      val segmentUrl = HlsPlaylist.firstSegmentUri(body, currentUrl)
        ?: return SourceVerification(SourcePlayability.Unavailable, null)
      val segment = probe.get(segmentUrl, source.headers, rangeBytes = PROBE_BYTES)
      if (!segment.isSuccessful) return SourceVerification(classify(segment.code), null)
      if (isHtml(segment)) return SourceVerification(SourcePlayability.Unavailable, null)

      val played = HlsPlaylistSummaryParser.analyze(body, currentUrl)
      val summary = masterSummary?.let { master ->
        played.copy(
          declaredResolutionHeight = master.declaredResolutionHeight ?: played.declaredResolutionHeight,
          declaredCodecs = master.declaredCodecs.ifEmpty { played.declaredCodecs }
        )
      } ?: played
      SourceVerification(SourcePlayability.Playable, summary)
    } catch (e: TimeoutCancellationException) {
      SourceVerification(SourcePlayability.TransientFailure, null)
    } catch (e: CancellationException) {
      throw e
    } catch (e: IOException) {
      SourceVerification(SourcePlayability.TransientFailure, null)
    } catch (e: Exception) {
      SourceVerification(SourcePlayability.TransientFailure, null)
    }
  }

  /** Sceglie la variante con la risoluzione dichiarata dalla sorgente o la più alta. */
  private fun pickVariant(master: String, masterUrl: String, targetHeight: Int?): HlsVariant? {
    val variants = HlsPlaylist.variants(master, masterUrl)
    if (variants.isEmpty()) return null
    if (targetHeight != null && targetHeight > 0) {
      variants.firstOrNull { it.height == targetHeight }?.let { return it }
    }
    return variants.maxWithOrNull(
      compareBy<HlsVariant> { it.height ?: 0 }.thenBy { it.bandwidth ?: 0 }
    )
  }

  private fun classify(httpCode: Int): SourcePlayability = when (httpCode) {
    HTTP_REQUEST_TIMEOUT,
    HTTP_TOO_EARLY,
    HTTP_TOO_MANY_REQUESTS,
    in 500..599 -> SourcePlayability.TransientFailure
    else -> SourcePlayability.Unavailable
  }

  /** Pagina HTML/JSON al posto del flusso: non è un manifest né un video. */
  private fun isHtml(response: ProbeResponse): Boolean {
    val type = response.contentType?.lowercase().orEmpty()
    if (type.contains("text/html") || type.contains("application/json")) return true
    val text = response.body.trimStart('\uFEFF', '\r', '\n', ' ', '\t').lowercase()
    return text.startsWith("<!doctype") || text.startsWith("<html") || text.startsWith("{")
  }

  companion object {
    /** Byte letti dal primo segmento (verifica di disponibilità, non di qualità). */
    const val PROBE_BYTES = 1024

    /** Massimo numero di master annidate seguite (protezione da loop). */
    private const val MAX_MASTER_HOPS = 3

    private const val HTTP_REQUEST_TIMEOUT = 408
    private const val HTTP_TOO_EARLY = 425
    private const val HTTP_TOO_MANY_REQUESTS = 429
  }
}

/**
 * Sonda OkHttp: stessa catena di [ExtractorHttp] (cookie condivisi, redirect)
 * con un `callTimeout` breve e un limite esplicito sul corpo letto.
 */
internal class OkHttpStreamProbe(
  private val client: OkHttpClient = probeClient
) : StreamProbe {

  override suspend fun get(
    url: String,
    headers: Map<String, String>,
    rangeBytes: Int?
  ): ProbeResponse {
    // Stessa normalizzazione del player (userinfo → Authorization Basic, es.
    // link Easynews/EasyStream con credenziali nell'URL): la sonda deve
    // osservare il comportamento REALE della riproduzione, non una richiesta
    // senza credenziali che il CDN rifiuta.
    val normalized = PlaybackRequestNormalizer.normalize(url, headers)
    val request = try {
      Request.Builder()
        .url(normalized.url.orEmpty())
        .header("Accept", "*/*")
        .header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
        .get()
        .apply {
          normalized.headers.forEach { (name, value) -> header(name, value) }
          if (rangeBytes != null && rangeBytes > 0) {
            header("Range", "bytes=0-${rangeBytes - 1}")
          }
        }
        .build()
    } catch (e: IllegalArgumentException) {
      throw IOException("URL non valido: ${e.message}")
    }

    val response = client.newCall(request).awaitResponse()
    return response.use { resp: Response ->
      val limit = if (rangeBytes != null) rangeBytes else MAX_PLAYLIST_BYTES
      val body = resp.peekBody(limit.toLong()).string()
      ProbeResponse(
        code = resp.code,
        finalUrl = resp.request.url.toString(),
        body = body,
        contentType = resp.header("Content-Type")
      )
    }
  }

  companion object {
    /** Limite di lettura dei manifest: molto più che una playlist reale. */
    private const val MAX_PLAYLIST_BYTES = 512 * 1024

    /** Timeout per singola richiesta di verifica: non blocca la ricerca. */
    private const val PROBE_TIMEOUT_MS = 4_000L

    private val probeClient: OkHttpClient by lazy {
      ExtractorHttp.client.newBuilder()
        .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    }
  }
}
