package com.example.data.repository

import android.util.Log
import com.example.data.local.StreamNovaDatabase
import com.example.data.local.TmdbResponseCacheDao
import com.example.data.local.TmdbResponseCacheEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Categorie della Parental Guide esposte a schermo. Sono ESCLUSIVAMENTE le quattro
 * previste dalla specifica: `SEXUAL_CONTENT` e qualunque categoria sconosciuta
 * restituita da Tiffara NON viene mostrata né tradotta in altre voci.
 */
enum class ParentalGuideCategoryType(val apiName: String) {
  PROFANITY("PROFANITY"),
  VIOLENCE("VIOLENCE"),
  FRIGHTENING_INTENSE_SCENES("FRIGHTENING_INTENSE_SCENES"),
  ALCOHOL_DRUGS("ALCOHOL_DRUGS");

  companion object {
    private val byApiName = entries.associateBy { it.apiName }

    /** `null` per categorie non visualizzabili (SEXUAL_CONTENT, sconosciute). */
    fun fromApiName(raw: String?): ParentalGuideCategoryType? =
      raw?.trim()?.uppercase()?.let { byApiName[it] }
  }
}

/** Livello di gravità dominante di una categoria, come mostrato all'utente. */
enum class ParentalGuideSeverity(val apiLevel: String) {
  MILD("mild"),
  MODERATE("moderate"),
  SEVERE("severe");

  companion object {
    private val byApiLevel = entries.associateBy { it.apiLevel }

    /** `null` per livelli sconosciuti: mai inventati né mappati per similitudine. */
    fun fromApiLevel(raw: String?): ParentalGuideSeverity? =
      raw?.trim()?.lowercase()?.let { byApiLevel[it] }
  }
}

/** Una categoria visualizzabile con il suo livello di gravità dominante. */
data class ParentalGuideCategory(
  val type: ParentalGuideCategoryType,
  val severity: ParentalGuideSeverity
)

/**
 * Risultato risolto per un IMDb ID. [categories] vuoto = Tiffara non ha dati mostrabili
 * per il titolo (risposta valida ma senza categorie): l'overlay non deve comparire.
 */
data class ParentalGuideSummary(
  val imdbId: String,
  val categories: List<ParentalGuideCategory>
) {
  val hasVisibleCategories: Boolean get() = categories.isNotEmpty()
}

/**
 * Logica di valutazione dei voti (stessa regola dell'implementazione Nuvio):
 *
 * 1. si leggono i voti di `severityBreakdowns`;
 * 2. si individua il livello di gravità (mild/moderate/severe) con il maggior numero
 *    di voti — in caso di parità fra livelli vince il primo nell'ordine MILD →
 *    MODERATE → SEVERE (scelta deterministica, nessun dato inventato);
 * 3. si confronta con i voti `none`;
 * 4. la categoria è mostrata SOLO se il livello dominante ha più voti di `none`;
 * 5. parità o prevalenza di `none` → la categoria non viene mostrata.
 *
 * Funzione PURA, verificabile in test JVM senza rete né Android.
 */
object ParentalGuideRating {

  /**
   * @param votes mappa `severityLevel` API (minuscolo) → numero di voti; le chiavi
   *   non corrispondenti a un livello noto sono state già escluse dal parser.
   * @return il livello dominante, oppure `null` se non emerge (tutto a zero, parità
   *   con `none`, prevalenza di `none`).
   */
  fun dominantSeverity(votes: Map<String, Int>): ParentalGuideSeverity? {
    val noneVotes = (votes["none"] ?: 0).coerceAtLeast(0)
    var best: ParentalGuideSeverity? = null
    var bestVotes = 0
    for (severity in ParentalGuideSeverity.entries) {
      val count = (votes[severity.apiLevel] ?: 0).coerceAtLeast(0)
      if (count > bestVotes) {
        best = severity
        bestVotes = count
      }
    }
    return best.takeIf { bestVotes > noneVotes }
  }
}

/**
 * Repository della Parental Guide (avvisi sui contenuti) basata sull'API pubblica
 * Tiffara: `GET https://api.tiffara.com/titles/{imdbId}/parentsGuide`.
 *
 * Requisiti architetturali (specifiche "Avvisi sui contenuti"):
 *  - chiamate HTTPS DIRETTE dal dispositivo: nessun backend, proxy o servizio
 *    intermedio (stesso pattern di [IntroSkipRepository]);
 *  - nessuna dipendenza di StreamNova dalla disponibilità di Tiffara: cache
 *    persistente su Room (tabella ESISTENTE `tmdb_response_cache`, chiave
 *    `parental_guide_<imdbId>`, NESSUNA nuova tabella o migrazione), fallback
 *    all'ultimo dato locale anche scaduto, rate limiting su HTTP 429 e circuit
 *    breaker globale dopo errori ripetuti;
 *  - i risultati VUOTI vengono memorizzati quanto quelli validi, per non
 *    interrogare di nuovo titoli senza dati;
 *  - timeout complessivo ~2,8s; MAI blocco del thread principale e MAI attesa
 *    della risposta per avviare la riproduzione (la chiamata è sospensiva e la UI
 *    la lancia in un proprio effetto);
 *  - nessun dato inventato: categorie/livelli sconosciuti sono ignorati.
 */
object ParentalGuideRepository {

  private const val TAG = "ParentalGuideRepository"
  private const val BASE_URL = "https://api.tiffara.com/titles"
  private const val TIMEOUT_MS = 2800L

  /** TTL della cache persistente: 7 giorni, ragionevole per guide raremente aggiornate. */
  const val CACHE_TTL_MS: Long = 7L * 24L * 60L * 60L * 1000L

  private const val CACHE_KEY_PREFIX = "parental_guide_"

  /** IMDb ID valido nel formato `tt1234567` (7-8 cifre). */
  private val IMDB_ID_PATTERN = Regex("^tt\\d{7,8}$")

  // ── Protezione HTTP 429 ────────────────────────────────────────────────
  // Prima di ogni chiamata lo stato di limitazione viene verificato: se attivo non
  // si tocca la rete (si usa la cache). Retry-After viene rispettato; senza header
  // si applica un backoff progressivo. Nessun retry immediato né continuo.
  private val RATE_LIMIT_BACKOFF_STEPS_MS = longArrayOf(5_000L, 15_000L, 45_000L)
  private const val RATE_LIMIT_BACKOFF_MAX_MS = 120_000L
  private const val RATE_LIMIT_HEADER_MAX_MS = 10L * 60L * 1000L
  private val rateLimitedUntilMs = AtomicLong(0L)
  private val rateLimitStrikes = AtomicInteger(0)

  // ── Circuit breaker ────────────────────────────────────────────────────
  // Dopo errori ripetuti (5xx, timeout, DNS, JSON non valido) le nuove chiamate
  // vengono sospese per [CIRCUIT_OPEN_MS]: in quel periodo si usa solo la cache
  // locale. Il circuito è GLOBALE (indipendente dai singoli contenuti).
  private const val CIRCUIT_FAILURE_THRESHOLD = 3
  private const val CIRCUIT_OPEN_MS = 2L * 60L * 1000L
  private val circuitOpenUntilMs = AtomicLong(0L)
  private val consecutiveFailures = AtomicInteger(0)

  /** Cache in memoria dei risultati definiti (validi E vuoti): evita chiamate duplicate. */
  private val memoryCache = ConcurrentHashMap<String, ParentalGuideSummary>()

  /** Una sola richiesta per IMDb ID: chiamate concorrenti sullo stesso titolo si serializzano. */
  private val inFlightLocks = ConcurrentHashMap<String, Mutex>()

  @Volatile
  private var httpClient: OkHttpClient = buildDefaultClient()

  /**
   * Fornitore del DAO della cache Room (`tmdb_response_cache`). Sostituibile nei test
   * unitari con un DAO fittizio in memoria: i test non toccano mai un database reale.
   */
  @Volatile
  internal var cacheDaoProvider: () -> TmdbResponseCacheDao? = {
    StreamNovaDatabase.getInstance()?.tmdbResponseCacheDao()
  }

  /** Fonte del tempo, sostituibile nei test per simulare scadenze e backoff. */
  @Volatile
  internal var nowMs: () -> Long = { System.currentTimeMillis() }

  private fun buildDefaultClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .build()

  /** Permette di sostituire il client nei test unitari (nessuna rete reale). */
  fun setClientForTesting(client: OkHttpClient) {
    httpClient = client
  }

  fun resetClient() {
    httpClient = buildDefaultClient()
  }

  fun resetCacheDaoProvider() {
    cacheDaoProvider = { StreamNovaDatabase.getInstance()?.tmdbResponseCacheDao() }
  }

  fun resetClock() {
    nowMs = { System.currentTimeMillis() }
  }

  /** Svuota la cache in memoria e azzera limitazione e circuit breaker (test/teardown). */
  fun clearCaches() {
    memoryCache.clear()
    inFlightLocks.clear()
    rateLimitedUntilMs.set(0L)
    rateLimitStrikes.set(0)
    circuitOpenUntilMs.set(0L)
    consecutiveFailures.set(0)
  }

  /**
   * Recupera la Parental Guide per un IMDb ID.
   *
   * Strategia di fallback (spec 5.3): cache locale → richiesta diretta a Tiffara →
   * in caso di errore l'ultimo risultato locale disponibile (anche scaduto) → se non
   * esistono dati locali si restituisce `null` (nessun overlay).
   *
   * @param imdbId ID IMDb nel formato `tt1234567`; formati non validi non generano
   *   NESSUNA chiamata di rete.
   * @return il riepilogo (anche senza categorie visualizzabili), oppure `null` se
   *   non ci sono dati affidabili da mostrare.
   */
  suspend fun getParentalGuide(imdbId: String?): ParentalGuideSummary? =
    withContext(Dispatchers.IO) {
      val cleanId = imdbId?.trim()?.lowercase()
        ?.takeIf { IMDB_ID_PATTERN.matches(it) }
        ?: return@withContext null

      val cacheKey = CACHE_KEY_PREFIX + cleanId

      memoryCache[cacheKey]?.let { return@withContext it }

      inFlightLocks.computeIfAbsent(cacheKey) { Mutex() }.withLock {
        // Riletta dopo l'attesa: un'altra coroutine può aver già risolto il titolo.
        memoryCache[cacheKey]?.let { return@withLock it }

        val dao = cacheDaoProvider()
        val cachedEntity = readCache(dao, cacheKey)
        val cachedFresh = cachedEntity?.takeIf { !it.isExpired(nowMs()) }
        if (cachedFresh != null) {
          val summary = parseParentsGuide(cachedFresh.jsonResponse, cleanId)
          memoryCache[cacheKey] = summary
          return@withLock summary
        }

        // Limitazione attiva o circuito aperto: solo cache, NESSUNA chiamata.
        val now = nowMs()
        if (now < rateLimitedUntilMs.get() || now < circuitOpenUntilMs.get()) {
          Log.d(TAG, "Rate limit/circuit breaker attivo per $cleanId: uso solo la cache")
          return@withLock cachedEntity?.let { parseParentsGuide(it.jsonResponse, cleanId) }
        }

        val fetched = fetchFromTiffara(cleanId)

        when {
          fetched is FetchResult.Success -> {
            // I risultati VUOTI vengono memorizzati quanto quelli validi: un titolo
            // senza dati non deve generare richieste ripetute.
            storeInCache(dao, cacheKey, fetched.rawJson)
            val summary = parseParentsGuide(fetched.rawJson, cleanId)
            memoryCache[cacheKey] = summary
            onNetworkSuccess()
            summary
          }
          fetched is FetchResult.NotFound -> {
            // 404: il titolo non ha una guida. Risultato "vuoto" definitivo per
            // questa sessione e memorizzato in cache, senza contare errori.
            storeInCache(dao, cacheKey, EMPTY_GUIDE_JSON)
            val summary = parseParentsGuide(EMPTY_GUIDE_JSON, cleanId)
            memoryCache[cacheKey] = summary
            summary
          }
          fetched is FetchResult.Rejected -> {
            // 400: richiesta rifiutata dal servizio. Nessuna cache, nessun conteggio
            // di errore (non è un malfunzionamento del servizio): eventuale cache locale.
            Log.d(TAG, "Tiffara 400 per $cleanId: richiesta non accettata")
            cachedEntity?.let { parseParentsGuide(it.jsonResponse, cleanId) }
          }
          fetched is FetchResult.RateLimited -> {
            applyRateLimit(fetched.retryAfterMs)
            cachedEntity?.let { parseParentsGuide(it.jsonResponse, cleanId) }
          }
          fetched is FetchResult.Error -> {
            onNetworkFailure()
            cachedEntity?.let { parseParentsGuide(it.jsonResponse, cleanId) }
          }
          else -> null
        }
      }
    }

  // ── Rete ───────────────────────────────────────────────────────────────

  private sealed class FetchResult {
    data class Success(val rawJson: String) : FetchResult()
    /** 404: il titolo non ha una guida su Tiffara (risposta definitiva, cacheabile). */
    object NotFound : FetchResult()
    /** 400: richiesta rifiutata dal servizio (non cacheabile, senza conteggio errori). */
    object Rejected : FetchResult()
    data class RateLimited(val retryAfterMs: Long?) : FetchResult()
    object Error : FetchResult()
  }

  private fun fetchFromTiffara(imdbId: String): FetchResult {
    val url = "$BASE_URL/$imdbId/parentsGuide"
    val request = Request.Builder()
      .url(url)
      .header("Accept", "application/json")
      .header("User-Agent", "StreamNova/1.0")
      .build()

    return try {
      Log.d(TAG, "GET Tiffara: $url")
      httpClient.newCall(request).execute().use { response ->
        when {
          response.code == 200 -> {
            val body = response.body?.string()
            // JSON non valido → errore (conteggia per il circuit breaker e attiva il
            // fallback sulla cache): non viene mai cacheato come "verità" per il titolo.
            if (body.isNullOrBlank() || !isValidJsonObject(body)) FetchResult.Error
            else FetchResult.Success(body)
          }
          response.code == 404 -> FetchResult.NotFound
          response.code == 400 -> FetchResult.Rejected
          response.code == 429 -> FetchResult.RateLimited(retryAfterMs(response))
          else -> {
            Log.d(TAG, "Tiffara HTTP ${response.code} per $imdbId")
            FetchResult.Error
          }
        }
      }
    } catch (e: IOException) {
      Log.w(TAG, "Errore rete/timeout Tiffara ($imdbId): ${e.message}")
      FetchResult.Error
    } catch (e: Exception) {
      Log.w(TAG, "Errore inatteso Tiffara ($imdbId): ${e.message}")
      FetchResult.Error
    }
  }

  /** `true` se il corpo è un oggetto JSON sintatticamente valido. */
  private fun isValidJsonObject(body: String): Boolean = try {
    JSONObject(body)
    true
  } catch (e: Exception) {
    false
  }

  /** Legge l'header `Retry-After` (secondi o data HTTP); `null` se assente/non leggibile. */
  private fun retryAfterMs(response: okhttp3.Response): Long? {
    val header = response.header("Retry-After")?.trim().takeUnless { it.isNullOrEmpty() }
      ?: return null
    return header.toLongOrNull()?.let { seconds -> seconds * 1000L }
      ?: run {
        // Formato data HTTP (es. "Wed, 21 Oct 2026 07:28:00 GMT").
        try {
          val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
          format.timeZone = java.util.TimeZone.getTimeZone("GMT")
          val target = format.parse(header)?.time ?: return null
          (target - nowMs()).takeIf { it > 0L }
        } catch (e: Exception) {
          null
        }
      }
      ?.coerceAtMost(RATE_LIMIT_HEADER_MAX_MS)
  }

  private fun applyRateLimit(retryAfterMs: Long?) {
    val now = nowMs()
    val delayMs = retryAfterMs ?: run {
      val step = rateLimitStrikes.getAndIncrement()
        .coerceIn(0, RATE_LIMIT_BACKOFF_STEPS_MS.size - 1)
      RATE_LIMIT_BACKOFF_STEPS_MS[step]
    }
    val effective = (delayMs.takeIf { it > 0L } ?: RATE_LIMIT_BACKOFF_STEPS_MS.first())
      .coerceAtMost(RATE_LIMIT_HEADER_MAX_MS)
    rateLimitedUntilMs.set(now + effective)
    Log.w(TAG, "Tiffara 429: nuove richieste sospese per ${effective}ms (retryAfter=${retryAfterMs != null})")
  }

  private fun onNetworkSuccess() {
    consecutiveFailures.set(0)
    rateLimitStrikes.set(0)
  }

  private fun onNetworkFailure() {
    val failures = consecutiveFailures.incrementAndGet()
    if (failures >= CIRCUIT_FAILURE_THRESHOLD) {
      circuitOpenUntilMs.set(nowMs() + CIRCUIT_OPEN_MS)
      consecutiveFailures.set(0)
      Log.w(TAG, "Circuit breaker APERTO: nuove chiamate Tiffara sospese per ${CIRCUIT_OPEN_MS}ms")
    }
  }

  // ── Cache persistente (tabella ESISTENTE tmdb_response_cache) ─────────

  private suspend fun readCache(dao: TmdbResponseCacheDao?, key: String): TmdbResponseCacheEntity? {
    if (dao == null) return null
    return try {
      dao.getCache(key)
    } catch (e: Exception) {
      Log.w(TAG, "Lettura cache Parental Guide fallita per $key: ${e.message}")
      null
    }
  }

  private suspend fun storeInCache(dao: TmdbResponseCacheDao?, key: String, rawJson: String) {
    if (dao == null) return
    try {
      dao.insertCache(
        TmdbResponseCacheEntity(
          endpointKey = key,
          jsonResponse = rawJson,
          cachedAt = nowMs(),
          ttlMillis = CACHE_TTL_MS
        )
      )
    } catch (e: Exception) {
      Log.w(TAG, "Scrittura cache Parental Guide fallita per $key: ${e.message}")
    }
  }

  // ── Parsing ────────────────────────────────────────────────────────────

  /** Guida vuota memorizzata in cache per i titoli senza dati (evita richieste ripetute). */
  internal const val EMPTY_GUIDE_JSON = "{\"parentsGuide\":[]}"

  /**
   * Parser della risposta Tiffara. Formato reale (verificato sull'API):
   * `{"parentsGuide":[{"category":"VIOLENCE","severityBreakdowns":[
   *   {"severityLevel":"none","voteCount":141},…]},…]}`
   *
   * Tolleranza ai dati mancanti o sconosciuti (mai crash, mai dati inventati):
   *  - categoria assente/sconosciuta/SEXUAL_CONTENT → voce ignorata;
   *  - `severityBreakdowns` assente → voce ignorata;
   *  - `severityLevel` sconosciuto → voti ignorati;
   *  - `voteCount` mancante o negativo → trattato come 0.
   *
   * Un JSON non valido restituisce una guida vuota (null-safe, mai eccezioni).
   */
  fun parseParentsGuide(json: String?, imdbId: String): ParentalGuideSummary {
    if (json.isNullOrBlank()) return ParentalGuideSummary(imdbId, emptyList())
    val categories = try {
      val root = JSONObject(json)
      val guideArray = root.optJSONArray("parentsGuide") ?: JSONArray()
      buildList {
        for (i in 0 until guideArray.length()) {
          val entry = guideArray.optJSONObject(i) ?: continue
          val type = ParentalGuideCategoryType.fromApiName(entry.optString("category"))
            ?: continue
          val breakdowns = entry.optJSONArray("severityBreakdowns") ?: continue
          val votes = HashMap<String, Int>()
          for (j in 0 until breakdowns.length()) {
            val breakdown = breakdowns.optJSONObject(j) ?: continue
            val level = breakdown.optString("severityLevel").trim().lowercase()
            if (level.isEmpty()) continue
            // Livelli sconosciuti: voti ignorati, nessun livello inventato.
            if (ParentalGuideSeverity.fromApiLevel(level) == null && level != "none") continue
            val count = breakdown.optInt("voteCount", 0).coerceAtLeast(0)
            votes[level] = (votes[level] ?: 0) + count
          }
          val dominant = ParentalGuideRating.dominantSeverity(votes) ?: continue
          add(ParentalGuideCategory(type, dominant))
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "JSON Parental Guide non valido: ${e.message}")
      emptyList()
    }
    return ParentalGuideSummary(imdbId, categories)
  }
}
