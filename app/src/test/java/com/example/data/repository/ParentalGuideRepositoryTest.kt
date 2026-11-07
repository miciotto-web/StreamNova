package com.example.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.local.TmdbResponseCacheDao
import com.example.data.local.TmdbResponseCacheEntity
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Test del repository Parental Guide (Tiffara): valutazione voti Nuvio, cache
 * persistente riutilizzata da `tmdb_response_cache`, fallback su dati scaduti,
 * HTTP 429 (Retry-After + backoff progressivo) e circuit breaker.
 *
 * Nessuna rete reale: il client OkHttp è sostituito con interceptor fake (stesso
 * pattern di [IntroSkipRepositoryTest]) e la cache Room con un DAO fittizio in memoria.
 */
@RunWith(AndroidJUnit4::class)
class ParentalGuideRepositoryTest {

  /** DAO fittizio in memoria: replica la semantica di `TmdbResponseCacheDao`. */
  private class FakeCacheDao : TmdbResponseCacheDao {
    val store = HashMap<String, TmdbResponseCacheEntity>()
    var writes = 0
    override suspend fun getCache(key: String): TmdbResponseCacheEntity? = store[key]
    override suspend fun insertCache(entry: TmdbResponseCacheEntity) {
      writes++
      store[entry.endpointKey] = entry
    }
    override suspend fun deleteCache(key: String) {
      store.remove(key)
    }
    override suspend fun deleteExpired(currentTime: Long): Int {
      val expired = store.values.filter { (currentTime - it.cachedAt) > it.ttlMillis }
      expired.forEach { store.remove(it.endpointKey) }
      return expired.size
    }
    override suspend fun clearAll() = store.clear()
  }

  private val dao = FakeCacheDao()
  private var fakeNow = 1_000_000L

  @Before
  fun setup() {
    ParentalGuideRepository.clearCaches()
    dao.store.clear()
    dao.writes = 0
    fakeNow = 1_000_000L
    ParentalGuideRepository.nowMs = { fakeNow }
    ParentalGuideRepository.cacheDaoProvider = { dao }
  }

  @After
  fun tearDown() {
    ParentalGuideRepository.clearCaches()
    ParentalGuideRepository.resetClient()
    ParentalGuideRepository.resetCacheDaoProvider()
    ParentalGuideRepository.resetClock()
  }

  private fun fakeClient(status: Int, body: String, retryAfter: String? = null): OkHttpClient =
    OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
      val request = chain.request()
      val builder = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message(if (status == 200) "OK" else "Error")
        .body(body.toResponseBody("application/json".toMediaType()))
      retryAfter?.let { builder.header("Retry-After", it) }
      builder.build()
    }).build()

  private fun failingClient(error: Exception): OkHttpClient =
    OkHttpClient.Builder().addInterceptor(Interceptor { throw error }).build()

  /** Corpo Tiffara reale (schema verificato sull'API pubblica). */
  private fun guideJson(vararg categories: String): String {
    val entries = categories.joinToString(",") { category ->
      """{"category":"$category","severityBreakdowns":[
         {"severityLevel":"none","voteCount":10},
         {"severityLevel":"mild","voteCount":20},
         {"severityLevel":"moderate","voteCount":30},
         {"severityLevel":"severe","voteCount":40}]}"""
    }
    return """{"parentsGuide":[$entries]}"""
  }

  // ── Logica di valutazione (pura, senza rete) ──────────────────────────

  @Test
  fun livelloDominanteSuperioreANoneVieneMostrato() {
    val severity = ParentalGuideRating.dominantSeverity(
      mapOf("none" to 10, "mild" to 20, "moderate" to 30, "severe" to 40)
    )
    assertEquals(ParentalGuideSeverity.SEVERE, severity)
  }

  @Test
  fun prevalenzaDiNoneNonMostrata() {
    val severity = ParentalGuideRating.dominantSeverity(
      mapOf("none" to 500, "mild" to 20, "moderate" to 30, "severe" to 40)
    )
    assertNull(severity)
  }

  @Test
  fun paritaFraNoneEDominanteNonMostrata() {
    val severity = ParentalGuideRating.dominantSeverity(
      mapOf("none" to 40, "mild" to 20, "moderate" to 30, "severe" to 40)
    )
    assertNull(severity)
  }

  @Test
  fun livelloGravitaSconosciutoIgnorato() {
    // Solo livelli sconosciuti oltre a none: nessuna categoria mostrabile, nessun
    // livello inventato.
    assertNull(
      ParentalGuideRating.dominantSeverity(mapOf("none" to 1, "extreme" to 99, "unknown" to 5))
    )
    // Con un livello noto: i voti sconosciuti non partecipano al dominio.
    assertEquals(
      ParentalGuideSeverity.MILD,
      ParentalGuideRating.dominantSeverity(mapOf("none" to 1, "extreme" to 99, "mild" to 5))
    )
  }

  @Test
  fun tuttoAZeroNonMostraNessunaCategoria() {
    assertNull(ParentalGuideRating.dominantSeverity(emptyMap()))
    assertNull(ParentalGuideRating.dominantSeverity(mapOf("none" to 0, "severe" to 0)))
  }

  // ── Parsing ────────────────────────────────────────────────────────────

  @Test
  fun rispostaValidaConCategorieNote() {
    val summary = ParentalGuideRepository.parseParentsGuide(
      guideJson("PROFANITY", "VIOLENCE", "FRIGHTENING_INTENSE_SCENES", "ALCOHOL_DRUGS"),
      "tt0944947"
    )
    assertEquals(4, summary.categories.size)
    assertEquals(
      listOf(
        ParentalGuideCategoryType.PROFANITY,
        ParentalGuideCategoryType.VIOLENCE,
        ParentalGuideCategoryType.FRIGHTENING_INTENSE_SCENES,
        ParentalGuideCategoryType.ALCOHOL_DRUGS
      ),
      summary.categories.map { it.type }
    )
    // Tutte le categorie del corpo fake hanno severe dominante (40 > 10 none).
    assertTrue(summary.categories.all { it.severity == ParentalGuideSeverity.SEVERE })
  }

  @Test
  fun categoriaSessualeECategoriaSconosciutaIgnorate() {
    val json = """{"parentsGuide":[
      ${guideJson("SEXUAL_CONTENT").removePrefix("{\"parentsGuide\":[").removeSuffix("]}")},
      {"category":"TOTALLY_UNKNOWN","severityBreakdowns":[{"severityLevel":"severe","voteCount":99}]},
      {"category":"VIOLENCE","severityBreakdowns":[{"severityLevel":"severe","voteCount":99}]}
    ]}"""
    val summary = ParentalGuideRepository.parseParentsGuide(json, "tt0000001")
    assertEquals(listOf(ParentalGuideCategoryType.VIOLENCE), summary.categories.map { it.type })
  }

  @Test
  fun rispostaSenzaCategorieEVotiMancantiGestitiSenzaCrash() {
    // Struttura reale Tiffara: breakdown SENZA voteCount (voto assente = 0).
    val json = """{"parentsGuide":[
      {"category":"PROFANITY","severityBreakdowns":[
        {"severityLevel":"none","voteCount":4},
        {"severityLevel":"mild"},
        {"severityLevel":"moderate"}]},
      {"category":"VIOLENCE","severityBreakdowns":[{"severityLevel":"severe","voteCount":1}]}
    ]}"""
    val summary = ParentalGuideRepository.parseParentsGuide(json, "tt0000001")
    // PROFANITY: solo none=4 → non mostrata. VIOLENCE: severe=1 > 0 → Grave.
    assertEquals(
      listOf(ParentalGuideCategory(ParentalGuideCategoryType.VIOLENCE, ParentalGuideSeverity.SEVERE)),
      summary.categories
    )
  }

  @Test
  fun jsonNonValidoVuotoENullRestituisconoGuidaVuota() {
    assertTrue(
      ParentalGuideRepository.parseParentsGuide("not-json{", "tt1234567").categories.isEmpty()
    )
    assertTrue(
      ParentalGuideRepository.parseParentsGuide("", "tt1234567").categories.isEmpty()
    )
    assertTrue(
      ParentalGuideRepository.parseParentsGuide(null, "tt1234567").categories.isEmpty()
    )
    // Array invece di oggetto: nessun crash.
    assertTrue(
      ParentalGuideRepository.parseParentsGuide("[1,2,3]", "tt1234567").categories.isEmpty()
    )
  }

  // ── Flusso completo con rete fake ─────────────────────────────────────

  @Test
  fun risposta200Valida() = runBlocking {
    var networkCalls = 0
    ParentalGuideRepository.setClientForTesting(
      OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        networkCalls++
        fakeResponse(chain, 200, guideJson("VIOLENCE", "PROFANITY"))
      }).build()
    )

    val summary = ParentalGuideRepository.getParentalGuide("tt0944947")
    assertNotNull(summary)
    assertEquals(2, summary!!.categories.size)
    assertTrue(summary.hasVisibleCategories)
    assertEquals(1, networkCalls)
    assertEquals(1, dao.writes) // risultato persistito su `tmdb_response_cache`

    // Seconda chiamata: cache in memoria, NESSUNA nuova richiesta.
    val cached = ParentalGuideRepository.getParentalGuide("tt0944947")
    assertEquals(summary, cached)
    assertEquals(1, networkCalls)

    // Dopo lo svuotamento della memoria la cache Room (fresca) evita comunque la rete.
    ParentalGuideRepository.clearCaches()
    val persisted = ParentalGuideRepository.getParentalGuide("tt0944947")
    assertEquals(summary, persisted)
    assertEquals(1, networkCalls)
    assertEquals(1, dao.writes)
  }

  @Test
  fun risposta200SenzaCategorieVieneCacheataEvitaRichiesteRipetute() = runBlocking {
    var networkCalls = 0
    ParentalGuideRepository.setClientForTesting(
      OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        networkCalls++
        fakeResponse(chain, 200, """{"parentsGuide":[]}""")
      }).build()
    )

    val empty = ParentalGuideRepository.getParentalGuide("tt0000002")
    assertNotNull(empty)
    assertFalse(empty!!.hasVisibleCategories)
    assertEquals(1, networkCalls)

    // Anche il risultato VUOTO è in memoria e in cache: nessuna nuova richiesta.
    ParentalGuideRepository.getParentalGuide("tt0000002")
    ParentalGuideRepository.clearCaches()
    ParentalGuideRepository.getParentalGuide("tt0000002")
    assertEquals(1, networkCalls)
  }

  @Test
  fun risposta404CacheggiataComeTitoloSenzaGuida() = runBlocking {
    var networkCalls = 0
    ParentalGuideRepository.setClientForTesting(
      OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        networkCalls++
        fakeResponse(chain, 404, """{"error":"Not Found"}""")
      }).build()
    )

    val empty = ParentalGuideRepository.getParentalGuide("tt0000003")
    assertNotNull(empty)
    assertFalse(empty!!.hasVisibleCategories)
    assertEquals(1, networkCalls)

    // Il 404 è definitivo per il titolo: nessuna nuova richiesta dopo la cache.
    ParentalGuideRepository.clearCaches()
    ParentalGuideRepository.getParentalGuide("tt0000003")
    assertEquals(1, networkCalls)
  }

  @Test
  fun http400NonVieneCacheatoENonContaComeErrore() = runBlocking {
    var networkCalls = 0
    ParentalGuideRepository.setClientForTesting(
      OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        networkCalls++
        fakeResponse(chain, 400, """{"error":"Bad Request"}""")
      }).build()
    )

    assertNull(ParentalGuideRepository.getParentalGuide("tt0000004"))
    // Il 400 non è cacheabile: dopo lo svuotamento memoria la richiesta riparte
    // (nessun dato inventato), ma non ha aperto il circuit breaker né il rate limit.
    ParentalGuideRepository.clearCaches()
    assertNull(ParentalGuideRepository.getParentalGuide("tt0000004"))
    assertEquals(2, networkCalls)
  }

  @Test
  fun http429ConRetryAfterRispettato() = runBlocking {
    var networkCalls = 0
    ParentalGuideRepository.setClientForTesting(
      OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        networkCalls++
        if (networkCalls == 1) {
          fakeResponse(chain, 429, """{"error":"rate limited"}""", retryAfter = "120")
        } else {
          fakeResponse(chain, 200, guideJson("VIOLENCE"))
        }
      }).build()
    )

    // Prima chiamata: 429 → nessun dato (nessuna cache precedente), fallback null.
    assertNull(ParentalGuideRepository.getParentalGuide("tt0000005"))
    assertEquals(1, networkCalls)

    // NESSUN retry immediato: dentro la finestra indicata dal server non si tocca la rete.
    assertNull(ParentalGuideRepository.getParentalGuide("tt0000005"))
    assertEquals(1, networkCalls)

    // Scaduto il Retry-After la richiesta riparte e questa volta ha successo.
    fakeNow += 121_000L
    val summary = ParentalGuideRepository.getParentalGuide("tt0000005")
    assertNotNull(summary)
    assertTrue(summary!!.hasVisibleCategories)
    assertEquals(2, networkCalls)
  }

  @Test
  fun http429SenzaHeaderApplicaBackoffProgressivo() = runBlocking {
    var networkCalls = 0
    ParentalGuideRepository.setClientForTesting(
      OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        networkCalls++
        fakeResponse(chain, 429, """{"error":"rate limited"}""")
      }).build()
    )

    // Prima limitazione senza header: backoff del primo passo (5s).
    assertNull(ParentalGuideRepository.getParentalGuide("tt0000006"))
    assertEquals(1, networkCalls)
    fakeNow += 1_000L
    assertNull(ParentalGuideRepository.getParentalGuide("tt0000006"))
    assertEquals(1, networkCalls) // ancora dentro il backoff: nessun retry immediato

    // Seconda limitazione: backoff progressivo (15s), non di nuovo 5s.
    fakeNow += 4_001L // totale > 5s
    assertNull(ParentalGuideRepository.getParentalGuide("tt0000006"))
    assertEquals(2, networkCalls)
    fakeNow += 5_000L
    assertNull(ParentalGuideRepository.getParentalGuide("tt0000006"))
    assertEquals(2, networkCalls) // dentro il secondo backoff (15s)
    fakeNow += 10_001L
    assertNull(ParentalGuideRepository.getParentalGuide("tt0000006"))
    assertEquals(3, networkCalls)
  }

  @Test
  fun http500RipetutoApreIlCircuitBreaker() = runBlocking {
    var networkCalls = 0
    ParentalGuideRepository.setClientForTesting(
      OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        networkCalls++
        fakeResponse(chain, 500, """{"error":"server"}""")
      }).build()
    )

    repeat(3) {
      assertNull(ParentalGuideRepository.getParentalGuide("tt0000007"))
    }
    assertEquals(3, networkCalls)

    // Al quarto errore consecutivo il circuito è aperto: NESSUNA nuova chiamata.
    assertNull(ParentalGuideRepository.getParentalGuide("tt0000007"))
    assertEquals(3, networkCalls)

    // Trascorso il periodo di sospensione una nuova richiesta è consentita.
    fakeNow += 121_000L
    assertNull(ParentalGuideRepository.getParentalGuide("tt0000007"))
    assertEquals(4, networkCalls)
  }

  @Test
  fun timeoutEConnessioneOfflineSenzaCrash() = runBlocking {
    ParentalGuideRepository.setClientForTesting(failingClient(SocketTimeoutException("timed out")))
    assertNull(ParentalGuideRepository.getParentalGuide("tt0000008"))

    ParentalGuideRepository.clearCaches()
    ParentalGuideRepository.setClientForTesting(failingClient(UnknownHostException("api.tiffara.com")))
    assertNull(ParentalGuideRepository.getParentalGuide("tt0000008"))
  }

  @Test
  fun jsonNonValidoNonVieneCacheatoEUsaFallback() = runBlocking {
    var networkCalls = 0
    ParentalGuideRepository.setClientForTesting(
      OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        networkCalls++
        if (networkCalls == 1) {
          fakeResponse(chain, 200, "not-json{")
        } else {
          fakeResponse(chain, 200, guideJson("VIOLENCE"))
        }
      }).build()
    )

    // JSON non valido → nessun dato mostrato, nessun crash.
    val broken = ParentalGuideRepository.getParentalGuide("tt0000009")
    assertNull(broken) // nessuna cache precedente → null
    assertEquals(0, dao.writes) // NON cacheato come verità

    // La richiesta successiva (nuova sessione) riparte e riceve dati validi.
    ParentalGuideRepository.clearCaches()
    fakeNow += 121_000L // salta l'eventuale limitazione/circuito accumulata
    val good = ParentalGuideRepository.getParentalGuide("tt0000009")
    assertNotNull(good)
    assertTrue(good!!.hasVisibleCategories)
  }

  @Test
  fun cacheValidaEvitaLaReteCacheScadutaLaAggiorna() = runBlocking {
    // Cache fresca scritta a mano: NESSUNA chiamata di rete.
    dao.store["parental_guide_tt0000010"] = TmdbResponseCacheEntity(
      endpointKey = "parental_guide_tt0000010",
      jsonResponse = guideJson("PROFANITY"),
      cachedAt = fakeNow,
      ttlMillis = ParentalGuideRepository.CACHE_TTL_MS
    )
    var networkCalls = 0
    ParentalGuideRepository.setClientForTesting(
      OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        networkCalls++
        fakeResponse(chain, 200, guideJson("VIOLENCE"))
      }).build()
    )

    val fresh = ParentalGuideRepository.getParentalGuide("tt0000010")
    assertEquals(
      listOf(ParentalGuideCategoryType.PROFANITY),
      fresh!!.categories.map { it.type }
    )
    assertEquals(0, networkCalls)

    // Cache scaduta: la richiesta parte e AGGIORNA la cache.
    fakeNow += ParentalGuideRepository.CACHE_TTL_MS + 1L
    ParentalGuideRepository.clearCaches()
    val refreshed = ParentalGuideRepository.getParentalGuide("tt0000010")
    assertEquals(
      listOf(ParentalGuideCategoryType.VIOLENCE),
      refreshed!!.categories.map { it.type }
    )
    assertEquals(1, networkCalls)
    assertEquals(
      ParentalGuideCategoryType.VIOLENCE,
      ParentalGuideRepository.parseParentsGuide(
        dao.store["parental_guide_tt0000010"]!!.jsonResponse,
        "tt0000010"
      ).categories.first().type
    )
  }

  @Test
  fun fallbackSuDatiStaleQuandoLaReteFallisce() = runBlocking {
    // Un solo dato locale, GIA' SCADUTO, e una rete che risponde 500.
    dao.store["parental_guide_tt0000011"] = TmdbResponseCacheEntity(
      endpointKey = "parental_guide_tt0000011",
      jsonResponse = guideJson("ALCOHOL_DRUGS"),
      cachedAt = fakeNow - ParentalGuideRepository.CACHE_TTL_MS - 5_000L,
      ttlMillis = ParentalGuideRepository.CACHE_TTL_MS
    )
    ParentalGuideRepository.setClientForTesting(
      OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        fakeResponse(chain, 500, """{"error":"server"}""")
      }).build()
    )

    val stale = ParentalGuideRepository.getParentalGuide("tt0000011")
    assertNotNull("l'ultimo risultato locale va usato anche se scaduto", stale)
    assertEquals(
      listOf(ParentalGuideCategoryType.ALCOHOL_DRUGS),
      stale!!.categories.map { it.type }
    )
  }

  @Test
  fun imdbIdNonValidoNonGeneraRete() = runBlocking {
    var networkCalls = 0
    ParentalGuideRepository.setClientForTesting(
      OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        networkCalls++
        fakeResponse(chain, 200, guideJson("VIOLENCE"))
      }).build()
    )

    assertNull(ParentalGuideRepository.getParentalGuide(null))
    assertNull(ParentalGuideRepository.getParentalGuide(""))
    assertNull(ParentalGuideRepository.getParentalGuide("abc123"))
    assertNull(ParentalGuideRepository.getParentalGuide("tt123")) // troppo corto
    assertNull(ParentalGuideRepository.getParentalGuide("nm0944947"))
    assertEquals(0, networkCalls)
  }

  @Test
  fun assenzaDiDatiCompletaSenzaCrash() = runBlocking {
    // Nessun client impostato (client default senza rete raggiungibile in test):
    // si usa il client fake che scatta timeout su qualunque richiesta.
    ParentalGuideRepository.setClientForTesting(failingClient(UnknownHostException("no dns")))
    val result = ParentalGuideRepository.getParentalGuide("tt9999999")
    assertNull(result) // niente cache, niente rete → nessun overlay, nessun crash
  }

  private fun fakeResponse(
    chain: Interceptor.Chain,
    status: Int,
    body: String,
    retryAfter: String? = null
  ): Response {
    val builder = Response.Builder()
      .request(chain.request())
      .protocol(Protocol.HTTP_1_1)
      .code(status)
      .message(if (status == 200) "OK" else "Error")
      .body(body.toResponseBody("application/json".toMediaType()))
    retryAfter?.let { builder.header("Retry-After", it) }
    return builder.build()
  }
}
