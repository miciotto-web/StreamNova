package com.example.data.streaming.providers

import android.util.Log
import com.example.data.streaming.StreamProvider
import com.example.data.streaming.StreamSource
import com.example.data.streaming.diagnostics.StreamAttemptOutcome
import com.example.data.streaming.diagnostics.StreamDiagnosticSink
import com.example.data.streaming.diagnostics.StreamDiagnostics
import com.example.data.streaming.diagnostics.record
import com.example.data.streaming.extractors.ExtractorHttp
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Provider **Altadefinizione** basato sul player vidxgo.
 *
 * Il catalogo live (`altadefinizione.autos` -> `altadefinizionex.fit`) espone una
 * API JSON pubblica per la ricerca e incorpora i video tramite `v.vidxgo.co`.
 * Il provider non scarica la pagina HTML del sito: usa direttamente
 * l'endpoint JSON del player, che restituisce la master playlist HLS.
 *
 * Flusso:
 *  1. **IMDb ID** (preferito): arriva da TMDB/Stremio come `tt...`; la parte
 *     numerica, con zero-padding a 7 cifre, è l'identificativo del player.
 *  2. **Fallback per titolo**: se l'IMDb ID manca, `GET /api/v1/web/archive`
 *     sul catalogo restituisce gli item con `imdbId` e `kind`; si sceglie il
 *     match migliore per titolo/anno/tipo.
 *  3. **Token**: `GET {player}/t/{id}` (film) o `/t/{id}/{s}/{e}` (serie) ->
 *     JSON `{"url": "<master HLS>", "expire": <epoch_ms>}`.
 *  4. **Qualità**: la master playlist viene letta per ricavare la risoluzione
 *     massima (`RESOLUTION=WxH`). Un errore in questo passaggio non invalida la
 *     sorgente: la qualità resta "Auto".
 *
 * Il token HLS scade dopo circa 180 secondi: il rinnovo durante la riproduzione
 * è gestito da [com.example.ui.screens.player.VidxgoTokenInterceptor], non da
 * questo provider.
 */
class AltadefinizioneProvider internal constructor(
  private val mirrors: List<String>,
  private val playerOrigin: String,
  private val diagnostics: StreamDiagnosticSink,
  private val http: AltadefinizioneHttp
) : ImdbAwareStreamProvider {

  /** Costruttore pubblico: mirror reali e OkHttp condiviso. */
  constructor(diagnostics: StreamDiagnosticSink = StreamDiagnosticSink.NONE) : this(
    BASE_URLS,
    Vidxgo.ORIGIN,
    diagnostics,
    AltadefinizioneHttp { url, referer, headers ->
      ExtractorHttp.get(
        url,
        referer,
        extraHeaders = headers,
        callTimeoutMs = ExtractorHttp.MIRROR_CALL_TIMEOUT_MS
      )
    }
  )

  override val providerId: String get() = PROVIDER_ID

  override suspend fun getStreams(
    tmdbId: Int,
    isTv: Boolean,
    season: Int?,
    episode: Int?,
    title: String?,
    year: Int?
  ): List<StreamSource> = getStreams(
    tmdbId = tmdbId,
    isTv = isTv,
    season = season,
    episode = episode,
    title = title,
    year = year,
    imdbId = null
  )

  override suspend fun getStreams(
    tmdbId: Int,
    isTv: Boolean,
    season: Int?,
    episode: Int?,
    title: String?,
    year: Int?,
    imdbId: String?
  ): List<StreamSource> = withContext(Dispatchers.IO) {
    val startedAt = System.currentTimeMillis()
    try {
      val resolvedImdb = imdbId?.trim()?.takeIf { it.isNotBlank() }
        ?: findImdbId(title, year, isTv)
      val playerId = Vidxgo.playerId(resolvedImdb)
      if (playerId == null) {
        throw IOException("nessun IMDb ID disponibile per Altadefinizione (tmdbId=$tmdbId)")
      }

      val tokenPath = Vidxgo.tokenPath(
        playerId = playerId,
        season = if (isTv) season else null,
        episode = if (isTv) episode else null
      )
      val token = fetchToken("$playerOrigin$tokenPath", referer = "$playerOrigin/")
      val masterUrl = token.url?.trim().orEmpty()
      if (masterUrl.isBlank()) throw IOException("token Altadefinizione senza URL HLS")

      val quality = try {
        detectQuality(masterUrl)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "qualità non rilevata: ${StreamDiagnostics.sanitize(e.message) ?: "errore"}")
        "Auto"
      }

      val elapsed = System.currentTimeMillis() - startedAt
      Log.i(TAG, "sorgente Altadefinizione imdb=$resolvedImdb quality=$quality in ${elapsed}ms")
      diagnostics.record(
        providerId = PROVIDER_ID,
        outcome = StreamAttemptOutcome.SUCCESS,
        durationMs = elapsed,
        message = "imdb=$resolvedImdb quality=$quality"
      )

      listOf(
        StreamSource(
          streamUrl = masterUrl,
          quality = quality,
          serverName = SERVER_NAME,
          headers = Vidxgo.CDN_HEADERS,
          declaredQuality = quality,
          isProgressive = false,
          isItalian = true
        )
      )
    } catch (e: CancellationException) {
      diagnostics.record(
        providerId = PROVIDER_ID,
        outcome = StreamAttemptOutcome.CANCELLED,
        durationMs = System.currentTimeMillis() - startedAt,
        message = "ricerca annullata"
      )
      throw e
    } catch (e: Exception) {
      diagnostics.record(
        providerId = PROVIDER_ID,
        outcome = StreamDiagnostics.classify(e),
        durationMs = System.currentTimeMillis() - startedAt,
        message = e.message
      )
      throw e
    }
  }

  /**
   * Fallback di ricerca: interroga l'API JSON del catalogo finché un mirror
   * risponde. Il primo item con IMDb ID e tipo coerente vince.
   */
  private suspend fun findImdbId(title: String?, year: Int?, isTv: Boolean): String? {
    val query = HosterResolver.buildQuery(title, year)
    if (query.isBlank()) return null
    val wantedKind = if (isTv) KIND_SERIES else KIND_MOVIE
    for (base in mirrors) {
      currentCoroutineContext().ensureActive()
      val url = "$base/api/v1/web/archive" +
        "?q=${HosterResolver.encode(query)}&limit=30&count=1"
      try {
        val body = withTimeoutOrNull(SEARCH_BUDGET_MS) {
          http.get(url, "$base/", mapOf("Accept" to "application/json"))
        } ?: continue
        val items = archiveAdapter.fromJson(body)?.items.orEmpty()
        val best = pickItem(items, title, year, wantedKind) ?: continue
        val imdb = best.imdbId?.trim()?.takeIf { it.isNotBlank() }
        if (!imdb.isNullOrBlank()) {
          Log.i(TAG, "ricerca Altadefinizione: '$query' -> ${best.title} ($imdb)")
          return imdb
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "ricerca su $base fallita: ${StreamDiagnostics.sanitize(e.message) ?: "errore"}")
      }
    }
    return null
  }

  /** Scarica il token del player e ne valida la risposta. */
  private suspend fun fetchToken(url: String, referer: String): TokenPayload {
    val body = withTimeoutOrNull(TOKEN_BUDGET_MS) {
      http.get(url, referer, TOKEN_HEADERS)
    } ?: throw IOException("timeout token Altadefinizione")
    val payload = tokenAdapter.fromJson(body)
    if (payload?.url.isNullOrBlank()) {
      throw IOException("risposta token Altadefinizione non valida")
    }
    return payload
  }

  /** Legge la master playlist e ricava la qualità massima dichiarata. */
  private suspend fun detectQuality(masterUrl: String): String {
    val playlist = withTimeoutOrNull(MASTER_BUDGET_MS) {
      http.get(masterUrl, "$playerOrigin/", Vidxgo.CDN_HEADERS)
    } ?: return "Auto"
    return qualityFromMaster(playlist)
  }

  /** Sceglie il risultato migliore per titolo/anno/tipo (verificabile offline). */
  internal fun pickItem(
    items: List<ArchiveItem>,
    title: String?,
    year: Int?,
    kind: String?
  ): ArchiveItem? {
    val candidates = items.filter { item ->
      !item.imdbId.isNullOrBlank() && (kind == null || item.kind == kind)
    }
    if (candidates.isEmpty()) return null
    if (title.isNullOrBlank()) return candidates.first()
    val scored = candidates.map { it to scoreItem(it, title, year) }
    return scored.maxByOrNull { it.second }?.takeIf { it.second > 0 }?.first
  }

  /** Punteggio di corrispondenza titolo/anno; 0 = nessun match reale. */
  internal fun scoreItem(item: ArchiveItem, title: String, year: Int?): Int {
    val wanted = HosterResolver.normalize(title)
    val candidate = HosterResolver.normalize(item.title.orEmpty())
    if (wanted.isBlank() || candidate.isBlank()) return 0
    val titleScore = when {
      candidate == wanted -> 100
      candidate.startsWith(wanted) || wanted.startsWith(candidate) -> 60
      candidate.contains(wanted) -> 40
      else -> 0
    }
    if (titleScore == 0) return 0
    val yearScore = if (year != null && item.year == year) 20 else 0
    return titleScore + yearScore
  }

  /** Qualità massima dichiarata nella master playlist (`#EXT-X-STREAM-INF`). */
  internal fun qualityFromMaster(playlist: String): String =
    Vidxgo.qualityFromPlaylist(playlist)

  companion object {
    private const val TAG = "AltadefinizioneProvider"

    /** Identificativo stabile nella catena diagnostica. */
    internal const val PROVIDER_ID = "Altadefinizione"

    /** Nome mostrato nella card della sorgente. */
    internal const val SERVER_NAME = "Altadefinizione"

    /** Budget della ricerca per singolo mirror. */
    private const val SEARCH_BUDGET_MS = 4_000L

    /** Budget dell'endpoint token (risposta JSON molto piccola). */
    private const val TOKEN_BUDGET_MS = 5_000L

    /** Budget della master playlist per il rilevamento qualità. */
    private const val MASTER_BUDGET_MS = 5_000L

    private const val KIND_MOVIE = "movie"
    private const val KIND_SERIES = "series"

    /**
     * Mirror del catalogo per il fallback di ricerca. Il primo è il canonico
     * attuale; gli altri sono alias che redirigono su di esso.
     */
    private val BASE_URLS = listOf(
      "https://altadefinizionex.fit",
      "https://altadefinizionex.life",
      "https://altadefinizionex.bar"
    )

    private val TOKEN_HEADERS = mapOf("Accept" to "application/json")

    private val moshi: Moshi by lazy {
      Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    }
    private val tokenAdapter by lazy { moshi.adapter(TokenPayload::class.java) }
    private val archiveAdapter by lazy { moshi.adapter(ArchivePayload::class.java) }
  }
}

/**
 * Provider che accetta anche un **IMDb ID** oltre alla chiave TMDB.
 *
 * L'interfaccia base [StreamProvider] resta invariata: solo i provider che
 * possono sfruttare l'IMDb vengono richiamati con il parametro aggiuntivo da
 * [com.example.data.streaming.StreamManager].
 */
interface ImdbAwareStreamProvider : StreamProvider {

  suspend fun getStreams(
    tmdbId: Int,
    isTv: Boolean,
    season: Int? = null,
    episode: Int? = null,
    title: String? = null,
    year: Int? = null,
    imdbId: String? = null
  ): List<StreamSource>
}

/** Risposta dell'endpoint `/t/...` del player. */
internal data class TokenPayload(
  val url: String? = null,
  val expire: Long? = null
)

/** Risposta dell'API `/api/v1/web/archive` del catalogo. */
internal data class ArchivePayload(
  val ok: Boolean? = null,
  val items: List<ArchiveItem>? = null,
  val count: Int? = null
)

/** Singolo item restituito dall'API del catalogo. */
internal data class ArchiveItem(
  val id: Int? = null,
  val legacyId: Int? = null,
  val slug: String? = null,
  val title: String? = null,
  val year: Int? = null,
  val imdbId: String? = null,
  val kind: String? = null
)

/** Fetcher HTTP iniettabile per i test offline. */
internal fun interface AltadefinizioneHttp {
  suspend fun get(url: String, referer: String?, headers: Map<String, String>): String
}
