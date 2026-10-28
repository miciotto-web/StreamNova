package com.example.data.streaming.providers

import android.util.Log
import com.example.data.streaming.StreamSource
import com.example.data.streaming.diagnostics.StreamAttemptOutcome
import com.example.data.streaming.diagnostics.StreamDiagnosticSink
import com.example.data.streaming.diagnostics.StreamDiagnostics
import com.example.data.streaming.diagnostics.record
import com.example.data.streaming.extractors.ExtractorHttp
import com.example.data.streaming.extractors.FilemoonExtractor
import com.example.data.streaming.extractors.HtmlEntities
import com.example.data.streaming.extractors.MaxStreamExtractor
import com.example.data.streaming.extractors.MixdropExtractor
import com.example.data.streaming.extractors.StreamWishExtractor
import com.example.data.streaming.extractors.VidHideExtractor
import com.example.data.streaming.extractors.VideoExtractor
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Utilità condivise dai provider dei cataloghi italiani (CB01, Eurostreaming).
 *
 * I due siti espongono i link degli hoster in modo diverso:
 *  - **CB01**: `<a href="https://stayonline.pro/l/CODICE">Maxstream</a>` e iframe
 *    `data-src="https://stayonline.pro/e/CODICE"`;
 *  - **Eurostreaming**: `<a href="https://uprot.net/msf/CODE">MaxStream</a>` dentro
 *    gli spoiler per stagione;
 *  - alcuni articoli usano link **diretti** (`mixdrop.ag/e/...`).
 *
 * Gli shortener vengono risolti così:
 *  - `stayonline.pro/l|e/{code}` -> `POST /ajax/linkView.php` (o `linkEmbedView.php`)
 *    con parametro **`id`** -> JSON `{"status":"success","data":{"value":"<url>"}}`
 *    (parametro `linkId` -> "Invalid Parameters": il nome corretto è `id`);
 *  - `uprot.net/msf|mse|msfi|msei/{code}` -> serve un **captcha a immagini**:
 *    il link non è risolvibile in automatico, viene saltato con un log esplicito.
 */
object HosterResolver {

  private const val TAG = "HosterResolver"

  private val maxStreamExtractor: MaxStreamExtractor by lazy { MaxStreamExtractor() }
  private val mixdropExtractor: MixdropExtractor by lazy { MixdropExtractor() }
  private val vidHideExtractor: VidHideExtractor by lazy { VidHideExtractor() }
  private val filemoonExtractor: FilemoonExtractor by lazy { FilemoonExtractor() }
  private val streamWishExtractor: StreamWishExtractor by lazy { StreamWishExtractor() }

  /**
   * Label hoster riconosciute nei link dei cataloghi italiani.
   *
   * I cataloghi usano anche suffissi di qualità/audio ("Maxstream HD",
   * "Mixdrop HD (ITA)"): la label resta la stessa sorgente e va accettata,
   * altrimenti il mirror viene scartato prima ancora di risolverlo.
   */
  private val HOSTER_LABEL_REGEX = Regex(
    """^\s*(maxstream|mixdrop|turbovid|delta\s?bit|deltabit|voe|dood|filemoon|streamtape|vidhide|streamwish|flash|stream(?:ing)?)(?:\s*[\(\[]?\s*(?:hd|fhd|uhd|sd|4k|1080p|720p|ita)\s*[\)\]]?){0,2}\s*$""",
    RegexOption.IGNORE_CASE
  )

  fun matchesHosterLabel(label: String): Boolean = HOSTER_LABEL_REGEX.matches(label.trim())

  /**
   * True se l'host dell'URL appartiene a uno shortener/hoster noto.
   *
   * Controlliamo **il dominio**, non una sottostringa dell'intero URL:
   *  - `https://example.com/watch?next=maxstream.video` non è un link hoster;
   *  - `https://notmaxstream.video/e/x` non è MaxStream;
   *  - `https://www.maxstream.video/e/x` è un sottodominio legittimo.
   */
  fun isHosterUrl(url: String): Boolean {
    val host = hostOf(url) ?: return false
    return HOSTER_DOMAINS.any { matchesDomain(host, it) }
  }

  /**
   * True se l'URL è un hoster/shortener per cui esiste un percorso **reale**:
   * un estrattore integrato, lo shortener stayonline o il fail-fast uprot.
   * Un dominio solo riconosciuto (es. streamwish) non è "supportato".
   */
  fun isSupportedHosterUrl(url: String): Boolean =
    extractorFor(url) != null || parseStayOnline(url) != null || isUprotCaptcha(url)

  /** Host normalizzato (lowercase, senza punto finale) o null se l'URL non è valido. */
  internal fun hostOf(url: String): String? {
    return try {
      val uri = URI(url)
      val scheme = uri.scheme
      if (!scheme.equals("http", true) && !scheme.equals("https", true)) return null
      uri.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
      null
    }
  }

  /** Confronto di dominio con sottodomini: `x.maxstream.video` sì, `xmaxstream.video` no. */
  internal fun matchesDomain(host: String, domain: String): Boolean =
    host == domain || host.endsWith(".$domain")

  /**
   * Risolve un link hoster/shortener nel suo URL finale.
   *
   * @return l'URL risolto, oppure `null` se il link non è risolvibile in automatico
   *         (es. shortener con captcha) o non è un hoster supportato.
   */
  suspend fun resolveShortLink(url: String, referer: String): String? =
    when (val result = resolveShortLinkResult(url, referer)) {
      is ShortLinkResolution.Resolved -> result.url
      is ShortLinkResolution.Failed -> null
    }

  /**
   * Variante diagnostica di [resolveShortLink]: distingue una challenge da un
   * errore generico senza cambiare il contratto pubblico esistente.
   */
  internal suspend fun resolveShortLinkResult(
    url: String,
    referer: String
  ): ShortLinkResolution {
    currentCoroutineContext().ensureActive()
    val stay = parseStayOnline(url)
    if (stay != null) {
      return try {
        val body = ExtractorHttp.postForm(
          url = "${stay.origin}/ajax/${stay.endpoint}",
          referer = referer,
          form = mapOf("id" to stay.code),
          callTimeoutMs = ExtractorHttp.MIRROR_CALL_TIMEOUT_MS
        )
        parseStayOnlineResponse(stay, body)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        Log.w(TAG, "shortener stayonline fallito ($url): ${e.message}")
        ShortLinkResolution.Failed(
          outcome = StreamDiagnostics.classify(e),
          message = e.message
        )
      }
    }

    if (isUprotCaptcha(url)) {
      // uprot.net: captcha a immagini ("seleziona tutte le immagini con..."),
      // nessun endpoint JSON pubblico -> fail-fast, nessun bypass.
      Log.w(TAG, "link protetto da captcha uprot, salto: $url")
      return ShortLinkResolution.Failed(
        outcome = StreamAttemptOutcome.BLOCKED_BY_CHALLENGE,
        message = "challenge captcha richiesta: nessun bypass tentato"
      )
    }

    return ShortLinkResolution.Resolved(url)
  }

  /**
   * Interpreta la risposta JSON di stayonline.
   *
   * Il valore risolto può essere un hoster finale **oppure** un link
   * `uprot.net/msf|mse...` protetto da captcha: in quel caso la risoluzione è
   * già una challenge e va classificata come bloccata PRIMA di cercare un
   * estrattore. Altrimenti `uprot` finirebbe nel ramo "hoster non supportato"
   * e la causa reale (CAPTCHA) andrebbe persa.
   *
   * Parsing con regex: funziona sia sul dispositivo sia nei test JVM dove le
   * classi `org.json` sono stub.
   */
  internal fun parseStayOnlineResponse(
    link: StayOnlineLink,
    body: String
  ): ShortLinkResolution {
    val status = JSON_STATUS_REGEX.find(body)?.groupValues?.get(1).orEmpty()
    val value = JSON_VALUE_REGEX.find(body)?.groupValues?.get(1)
      ?.replace("\\/", "/")?.trim().orEmpty()
    if (status == "success" && value.startsWith("http")) {
      if (isUprotCaptcha(value)) {
        Log.w(TAG, "shortener stayonline verso captcha, salto: $value")
        return ShortLinkResolution.Failed(
          outcome = StreamAttemptOutcome.BLOCKED_BY_CHALLENGE,
          message = "challenge captcha richiesta: nessun bypass tentato"
        )
      }
      return ShortLinkResolution.Resolved(value)
    }
    val message = JSON_MSG_REGEX.find(body)?.groupValues?.get(1) ?: "risposta non valida"
    Log.w(TAG, "shortener stayonline non risolto (${link.code}): $message")
    return ShortLinkResolution.Failed(
      outcome = if (StreamDiagnostics.isChallengeMessage(message)) {
        StreamAttemptOutcome.BLOCKED_BY_CHALLENGE
      } else {
        StreamAttemptOutcome.EXTRACTION_FAILED
      },
      message = message
    )
  }

  /**
   * Riconosce un link stayonline dal **dominio effettivo** (con o senza `www.`)
   * e dal percorso `/l/{code}` o `/e/{code}`. Un dominio che imita quello vero
   * (`stayonline.pro.example.org`) non viene accettato.
   */
  internal fun parseStayOnline(url: String): StayOnlineLink? {
    val uri = try {
      URI(url)
    } catch (e: Exception) {
      return null
    }
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
    if (!matchesDomain(host, STAYONLINE_DOMAIN)) return null
    val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
    if (segments.size < 2) return null
    val kind = segments[0].lowercase()
    if (kind != "l" && kind != "e") return null
    val code = segments[1]
    if (!SHORTENER_CODE_REGEX.matches(code)) return null
    val port = if (uri.port > 0) ":${uri.port}" else ""
    val origin = "$scheme://$host$port"
    val endpoint = if (kind == "e") "linkEmbedView.php" else "linkView.php"
    return StayOnlineLink(origin = origin, endpoint = endpoint, code = code)
  }

  /** Vero se l'URL è un link uprot che richiede un captcha a immagini. */
  internal fun isUprotCaptcha(url: String): Boolean {
    val uri = try {
      URI(url)
    } catch (e: Exception) {
      return false
    }
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme != "http" && scheme != "https") return false
    val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
    if (!matchesDomain(host, UPROT_DOMAIN)) return false
    val first = uri.path.orEmpty().split('/').firstOrNull { it.isNotBlank() }?.lowercase()
    return first in UPROT_CAPTCHA_SEGMENTS
  }

  /**
   * Normalizza le varianti dello stesso embed Mixdrop.
   *
   * La pagina file `/f/{code}` e l'embed `/e/{code}` sono lo stesso video:
   * l'estrattore converte già `/f/` in `/e/`, ma la deduplica avviene prima e
   * considerava i due URL distinti, estraendo lo stesso file due volte (e
   * producendo due sorgenti con token diversi). La canonicalizzazione va fatta
   * sul dominio Mixdrop riconosciuto, mai su path arbitrari.
   */
  internal fun canonicalResolvedUrl(url: String): String {
    val host = hostOf(url) ?: return url
    if (MIXDROP_DOMAINS.none { matchesDomain(host, it) }) return url
    return url.replaceFirst(MIXDROP_FILE_PATH_REGEX, "\$1/e/")
  }

  /** Ritorna l'estrattore adatto all'hoster dell'URL, oppure null se non supportato. */
  fun extractorFor(url: String): VideoExtractor? {
    val host = hostOf(url) ?: return null
    return when {
      VIDHIDE_DOMAINS.any { matchesDomain(host, it) } -> vidHideExtractor
      FILEMOON_DOMAINS.any { matchesDomain(host, it) } -> filemoonExtractor
      STREAMWISH_DOMAINS.any { matchesDomain(host, it) } -> streamWishExtractor
      MAXSTREAM_DOMAINS.any { matchesDomain(host, it) } -> maxStreamExtractor
      MIXDROP_DOMAINS.any { matchesDomain(host, it) } -> mixdropExtractor
      else -> null
    }
  }

  /**
   * Risolve tutti i link di un articolo e li delega agli estrattori supportati.
   *
   * @param rawUrls coppie (label, url) estratte dall'HTML del catalogo.
   * @param referer pagina articolo (serve agli shortener e agli hoster).
   * @param onResolved callback per loggare gli URL risolti (utile in logcat).
   */
  suspend fun extractFromLinks(
    rawUrls: List<Pair<String, String>>,
    referer: String,
    onResolved: ((String) -> Unit)? = null,
    providerId: String = "HosterResolver",
    mirrorId: String? = null,
    diagnostics: StreamDiagnosticSink = StreamDiagnosticSink.NONE,
    /**
     * Limite per il singolo hoster/mirror. I link sono risolti in parallelo
     * (max [MAX_PARALLEL_HOSTERS]): il timeout di un hoster non sottrae tempo
     * agli altri. È distinto dal budget del mirror (12 s) e dai timeout delle
     * singole chiamate HTTP (5 s).
     */
    perHosterTimeoutMs: Long = HOSTER_TIMEOUT_MS,
    extractorSelector: (String) -> VideoExtractor? = { url -> extractorFor(url) },
    /**
     * Fetcher usato SOLO per gli shortener intermedi noti (es. `clicka.cc`) che
     * non sono hoster finali: i test offline lo sostituiscono per verificare il
     * redirect senza rete. In produzione segue i redirect HTTP reali.
     */
    redirectFetcher: suspend (url: String, referer: String?) -> Pair<String, String> =
      { url, referer ->
        ExtractorHttp.getWithFinalUrl(
          url,
          referer,
          callTimeoutMs = ExtractorHttp.MIRROR_CALL_TIMEOUT_MS
        )
      }
  ): List<StreamSource> {
    // Gli stessi file compaiono più volte nell'articolo (tabella + iframe):
    // i raw duplicati vengono scartati qui, quelli risolti dopo lo shortener
    // vengono deduplicati sul set concorrente.
    val uniqueLinks = rawUrls
      .filter { it.second.isNotBlank() }
      .distinctBy { it.second }
    if (uniqueLinks.isEmpty()) return emptyList()

    val seenResolved = ConcurrentHashMap.newKeySet<String>()
    return coroutineScope {
      // I link di un articolo sono indipendenti: risolverli in sequenza
      // sommava i timeout (fino a 10 s per hoster) e faceva scadere il budget
      // del mirror prima di provare gli altri. Il semaforo limita le richieste
      // concorrenti senza perdere nessun link.
      val gate = Semaphore(MAX_PARALLEL_HOSTERS)
      uniqueLinks
        .map { (label, rawUrl) ->
          async {
            gate.withPermit {
              extractOneLink(
                label = label,
                rawUrl = rawUrl,
                referer = referer,
                onResolved = onResolved,
                providerId = providerId,
                mirrorId = mirrorId,
                diagnostics = diagnostics,
                perHosterTimeoutMs = perHosterTimeoutMs,
                extractorSelector = extractorSelector,
                redirectFetcher = redirectFetcher,
                seenResolved = seenResolved
              )
            }
          }
        }
        .awaitAll()
        .flatten()
    }
  }

  /**
   * Risolve un singolo link dell'articolo e ne estrae le sorgenti.
   *
   * Un errore/challenge/timeout produce una lista vuota e un tentativo
   * diagnostico: non interrompe gli altri link, che girano in parallelo.
   */
  private suspend fun extractOneLink(
    label: String,
    rawUrl: String,
    referer: String,
    onResolved: ((String) -> Unit)?,
    providerId: String,
    mirrorId: String?,
    diagnostics: StreamDiagnosticSink,
    perHosterTimeoutMs: Long,
    extractorSelector: (String) -> VideoExtractor?,
    redirectFetcher: suspend (url: String, referer: String?) -> Pair<String, String>,
    seenResolved: MutableSet<String>
  ): List<StreamSource> {
    currentCoroutineContext().ensureActive()
    val startedAt = System.currentTimeMillis()
    val resolution = resolveShortLinkResult(rawUrl, referer)
    if (resolution is ShortLinkResolution.Failed) {
      diagnostics.record(
        providerId = providerId,
        mirrorId = mirrorId,
        outcome = resolution.outcome,
        durationMs = System.currentTimeMillis() - startedAt,
        message = resolution.message ?: "shortener non risolto"
      )
      return emptyList()
    }
    val resolved = canonicalResolvedUrl((resolution as ShortLinkResolution.Resolved).url)
    if (resolved.isBlank() || !seenResolved.add(resolved)) return emptyList()
    onResolved?.invoke(resolved)

    // Difesa in profondità: qualunque percorso che produca un link uprot non
    // deve finire nel ramo "hoster non supportato". Nessun bypass: solo
    // classificazione esplicita della challenge.
    if (isUprotCaptcha(resolved)) {
      Log.w(TAG, "link risolto verso captcha, salto: $resolved")
      diagnostics.record(
        providerId = providerId,
        mirrorId = mirrorId,
        outcome = StreamAttemptOutcome.BLOCKED_BY_CHALLENGE,
        durationMs = System.currentTimeMillis() - startedAt,
        message = "challenge captcha richiesta: nessun tentativo di bypass"
      )
      return emptyList()
    }

    val extractor = extractorSelector(resolved)
    if (extractor != null) {
      return extractResolved(
        resolved = resolved,
        extractor = extractor,
        startedAt = startedAt,
        providerId = providerId,
        mirrorId = mirrorId,
        diagnostics = diagnostics,
        perHosterTimeoutMs = perHosterTimeoutMs
      )
    }

    // URL non riconosciuto: può essere uno shortener intermedio (es.
    // `clicka.cc`) che redirige verso un hoster supportato oppure verso un
    // captcha. Si segue UN solo salto, mai una challenge.
    val unshortened = withTimeoutOrNull(perHosterTimeoutMs) {
      followUnshortener(resolved, referer, label, redirectFetcher)
    }
    if (unshortened != null && unshortened != resolved) {
      onResolved?.invoke(unshortened)
      // La challenge va riconosciuta PRIMA di selezionare un estrattore:
      // un redirect verso uprot non deve produrre alcuna richiesta.
      if (isUprotCaptcha(unshortened)) {
        Log.w(TAG, "shortener verso captcha, salto: $unshortened")
        diagnostics.record(
          providerId = providerId,
          mirrorId = mirrorId,
          outcome = StreamAttemptOutcome.BLOCKED_BY_CHALLENGE,
          durationMs = System.currentTimeMillis() - startedAt,
          message = "challenge captcha richiesta: nessun bypass tentato"
        )
        return emptyList()
      }
      val redirectedExtractor = extractorSelector(unshortened)
      if (redirectedExtractor != null) {
        Log.i(TAG, "shortener risolto: $resolved -> $unshortened")
        return extractResolved(
          resolved = unshortened,
          extractor = redirectedExtractor,
          startedAt = startedAt,
          providerId = providerId,
          mirrorId = mirrorId,
          diagnostics = diagnostics,
          perHosterTimeoutMs = perHosterTimeoutMs
        )
      }
    }

    Log.i(TAG, "hoster non supportato per ora: $resolved (label=$label)")
    diagnostics.record(
      providerId = providerId,
      mirrorId = mirrorId,
      outcome = StreamAttemptOutcome.EXTRACTION_FAILED,
      durationMs = System.currentTimeMillis() - startedAt,
      message = "hoster non supportato (${StreamDiagnostics.hostId(resolved) ?: "unknown"})"
    )
    return emptyList()
  }

  /** Estrae da un URL già risolto, registrando l'esito nella diagnostica. */
  private suspend fun extractResolved(
    resolved: String,
    extractor: VideoExtractor,
    startedAt: Long,
    providerId: String,
    mirrorId: String?,
    diagnostics: StreamDiagnosticSink,
    perHosterTimeoutMs: Long
  ): List<StreamSource> {
    try {
      // Un estrattore lento non consuma il budget degli altri link: il timeout
      // è per hoster e la cancellazione del chiamante continua a propagarsi
      // (withTimeoutOrNull rilancia solo il proprio timeout).
      val extracted = withTimeoutOrNull(perHosterTimeoutMs) {
        extractor.extract(resolved)
      }
      if (extracted == null) {
        Log.w(TAG, "estrazione oltre ${perHosterTimeoutMs}ms su $resolved")
        diagnostics.record(
          providerId = providerId,
          mirrorId = mirrorId,
          extractorId = extractor.javaClass.simpleName,
          outcome = StreamAttemptOutcome.TIMEOUT,
          durationMs = System.currentTimeMillis() - startedAt,
          message = "timeout hoster ${perHosterTimeoutMs}ms"
        )
        return emptyList()
      }
      diagnostics.record(
        providerId = providerId,
        mirrorId = mirrorId,
        extractorId = extractor.javaClass.simpleName,
        outcome = when {
          extracted.isEmpty() -> StreamAttemptOutcome.NO_RESULTS
          // Controllo leggero, senza richieste aggiuntive: solo HTTP(S) e'
          // dichiarato riproducibile. Un URL assente o con schema diverso
          // resta un esito diagnostico INVALID_STREAM_URL.
          extracted.any { source -> isHttpUrl(source.streamUrl) } -> StreamAttemptOutcome.SUCCESS
          else -> StreamAttemptOutcome.INVALID_STREAM_URL
        },
        durationMs = System.currentTimeMillis() - startedAt
      )
      return extracted
    } catch (e: CancellationException) {
      diagnostics.record(
        providerId = providerId,
        mirrorId = mirrorId,
        extractorId = extractor.javaClass.simpleName,
        outcome = StreamAttemptOutcome.CANCELLED,
        durationMs = System.currentTimeMillis() - startedAt,
        message = "estrazione annullata"
      )
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "estrazione fallita su $resolved: ${e.message}")
      diagnostics.record(
        providerId = providerId,
        mirrorId = mirrorId,
        extractorId = extractor.javaClass.simpleName,
        outcome = StreamDiagnostics.classify(e),
        durationMs = System.currentTimeMillis() - startedAt,
        message = e.message
      )
      return emptyList()
    }
  }

  /**
   * Segue un salto di shortener **solo** per gli intermedi noti
   * ([isKnownShortenerHost]) e solo quando il link è etichettato come hoster:
   * nessuna richiesta verso URL arbitrari o banner pubblicitari.
   *
   * @return l'URL finale se punta a un hoster supportato o a un captcha noto,
   *   altrimenti `null`.
   */
  internal suspend fun followUnshortener(
    url: String,
    referer: String,
    label: String,
    fetcher: suspend (url: String, referer: String?) -> Pair<String, String> = { u, r ->
      ExtractorHttp.getWithFinalUrl(u, r, callTimeoutMs = ExtractorHttp.MIRROR_CALL_TIMEOUT_MS)
    }
  ): String? {
    val host = hostOf(url) ?: return null
    if (!isKnownShortenerHost(host)) return null
    if (label != "iframe" && !matchesHosterLabel(label)) return null
    return try {
      val (finalUrl, body) = fetcher(url, referer)
      if (finalUrl != url && isResolvableTarget(finalUrl)) return finalUrl
      findRedirectTarget(body, finalUrl)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.i(TAG, "unshortener non risolto ($url): ${e.message}")
      null
    }
  }

  /** Host di shortener intermedi osservati nei cataloghi (non hoster finali). */
  internal fun isKnownShortenerHost(host: String): Boolean =
    matchesDomain(host.lowercase().trimEnd('.'), "clicka.cc") ||
      // clicka usa TLD diversi per aggirare i blocchi: il nome resta lo stesso.
      host.lowercase().startsWith("clicka.")

  /**
   * Cerca nell'HTML un redirect verso un hoster supportato: meta refresh,
   * `location = ...`, `location.replace(...)` oppure un URL hoster esplicito.
   * Non esegue JavaScript e non risolve challenge: solo cio' che il server ha
   * gia' consegnato nella pagina.
   */
  internal fun findRedirectTarget(html: String, baseUrl: String): String? {
    if (html.isBlank()) return null
    val normalized = HtmlEntities.decodeAttribute(html).replace("\\/", "/")
    val candidates = mutableListOf<String>()
    META_REFRESH_REGEX.findAll(normalized).forEach { candidates += it.groupValues[1] }
    JS_LOCATION_ASSIGN_REGEX.findAll(normalized).forEach { candidates += it.groupValues[1] }
    JS_LOCATION_CALL_REGEX.findAll(normalized).forEach { candidates += it.groupValues[1] }
    HOSTER_URL_REGEX.findAll(normalized).forEach { candidates += it.groupValues[1] }
    return candidates.asSequence()
      .map { it.trim() }
      .filter { it.isNotBlank() }
      .mapNotNull { absolutize(it, baseUrl) }
      .firstOrNull { candidate -> candidate != baseUrl && isResolvableTarget(candidate) }
  }

  private fun isResolvableTarget(url: String): Boolean =
    isUprotCaptcha(url) ||
      parseStayOnline(url) != null ||
      (extractorFor(url) != null && HOSTER_EMBED_PATH_REGEX.containsMatchIn(url))

  /** Risolve un URL relativo (`//host/...`, `/path`, `path`) contro [baseUrl]. */
  private fun absolutize(raw: String, baseUrl: String): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    if (value.startsWith("http://", ignoreCase = true) ||
      value.startsWith("https://", ignoreCase = true)
    ) {
      return value
    }
    if (value.startsWith("//")) return "https:$value"
    val base = try {
      URI(baseUrl)
    } catch (e: Exception) {
      return null
    }
    return try {
      base.resolve(value)?.toString()
    } catch (e: Exception) {
      null
    }
  }

  /** True per un URL HTTP(S) non vuoto (nessuna richiesta di rete). */
  private fun isHttpUrl(url: String?): Boolean {
    val trimmed = url?.trim().orEmpty()
    return trimmed.startsWith("http://", ignoreCase = true) ||
      trimmed.startsWith("https://", ignoreCase = true)
  }

  /** Normalizza il titolo per il confronto con gli slug delle pagine. */
  fun normalize(value: String): String =
    value.lowercase()
      .replace(Regex("""[àáâä]"""), "a")
      .replace(Regex("""[èéêë]"""), "e")
      .replace(Regex("""[ìíîï]"""), "i")
      .replace(Regex("""[òóôö]"""), "o")
      .replace(Regex("""[ùúûü]"""), "u")
      .replace(Regex("""[^a-z0-9]+"""), " ")
      .trim()

  /** Query di ricerca: titolo + anno (gli slug dei cataloghi includono l'anno). */
  fun buildQuery(title: String?, year: Int?): String {
    val base = title.orEmpty().trim()
    if (base.isEmpty()) return ""
    return if (year != null && year > 1900) "$base $year" else base
  }

  /** Percentuale-encoding della query per `?s=`. */
  fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

  /** Punteggio di corrispondenza slug <-> titolo (più alto = meglio). */
  fun matchScore(slug: String, title: String, year: Int? = null): Int {
    val normSlug = normalize(slug)
    val normTitle = normalize(title)
    if (normSlug.isBlank() || normTitle.isBlank()) return 0
    val slugWords = normSlug.split(" ").filter { it.isNotBlank() }
    val titleWords = normTitle.split(" ").filter { it.isNotBlank() }
    if (titleWords.isEmpty()) return 0

    // Anno: bonus di disambiguazione, mai un filtro rigido (il dato può mancare
    // e lo slug può non riportarlo).
    val yearBonus = if (year != null && year > 1900 && year.toString() in slugWords) YEAR_BONUS else 0

    // Titoli brevi ("It", "Up", "Io"): il match deve essere il titolo intero
    // come sequenza di token, non una sottostringa dentro una parola o una
    // descrizione. I cataloghi aggiungono spesso l'anno dopo il titolo.
    val isShortTitle = normTitle.length <= 2 || titleWords.all { it.length < 3 }
    if (isShortTitle) {
      return if (slugWords.take(titleWords.size) == titleWords) SHORT_TITLE_SCORE + yearBonus else 0
    }

    // Titoli normali: confronto per parola intera, non `contains` su tutto lo slug.
    val significant = titleWords.filter { it.length >= 3 }
    val matched = significant.count { slugWords.contains(it) }
    if (matched == 0) return 0
    val exactPhrase = slugWords.windowed(titleWords.size).any { it == titleWords }
    return matched * WORD_SCORE + (if (exactPhrase) EXACT_TITLE_BONUS else 0) + yearBonus
  }

  internal data class StayOnlineLink(val origin: String, val endpoint: String, val code: String)

  /** Esito diagnostico della risoluzione di uno shortener. */
  internal sealed interface ShortLinkResolution {
    data class Resolved(val url: String) : ShortLinkResolution
    data class Failed(
      val outcome: StreamAttemptOutcome,
      val message: String?
    ) : ShortLinkResolution
  }

  /**
   * Limite per singolo hoster. Resta sotto il budget del mirror (12 s) e copre
   * la catena più lunga degli estrattori (GET embed + eventuale POST player),
   * ciascuna con un proprio `callTimeout` di 5 s.
   */
  private const val HOSTER_TIMEOUT_MS = 10_000L

  /** Link hoster risolti contemporaneamente (limite prudente per i cataloghi). */
  private const val MAX_PARALLEL_HOSTERS = 3

  private const val YEAR_BONUS = 5
  private const val SHORT_TITLE_SCORE = 90
  private const val WORD_SCORE = 10
  private const val EXACT_TITLE_BONUS = 50

  private const val STAYONLINE_DOMAIN = "stayonline.pro"
  private const val UPROT_DOMAIN = "uprot.net"
  private val SHORTENER_CODE_REGEX = Regex("""[A-Za-z0-9]+""")
  private val UPROT_CAPTCHA_SEGMENTS = setOf("msf", "mse", "msfi", "msei", "msd")

  private val JSON_STATUS_REGEX = Regex(""""status"\s*:\s*"([^"]+)"""")
  private val JSON_VALUE_REGEX = Regex(""""value"\s*:\s*"([^"]+)"""")
  private val JSON_MSG_REGEX = Regex(""""(?:message|msg)"\s*:\s*"([^"]+)"""")
  private val META_REFRESH_REGEX = Regex(
    """<meta[^>]+http-equiv\s*=\s*["']?refresh["']?[^>]*content\s*=\s*["'][^"']*?url\s*=\s*([^"';]+)""",
    RegexOption.IGNORE_CASE
  )
  private val JS_LOCATION_ASSIGN_REGEX = Regex(
    """(?:window\.|document\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']""",
    RegexOption.IGNORE_CASE
  )
  private val JS_LOCATION_CALL_REGEX = Regex(
    """location\.(?:replace|assign)\(\s*["']([^"']+)["']""",
    RegexOption.IGNORE_CASE
  )
  private val HOSTER_URL_REGEX = Regex("""(https?://[^\s"'<>\\]+)""", RegexOption.IGNORE_CASE)
  /** Path tipici delle pagine embed: evita di scambiare un logo/asset per una sorgente. */
  private val HOSTER_EMBED_PATH_REGEX = Regex("""/(?:e|embed|v|f|w|d|msf|mse|msd)/""", RegexOption.IGNORE_CASE)

  private val VIDHIDE_DOMAINS = listOf(
    "vidhide.com", "vidhide.fun", "vidhide.pro", "vidhidepre.com", "vidhidevip.com",
    "filelions.live", "filelions.online", "filelions.to"
  )

  private val FILEMOON_DOMAINS = listOf(
    "filemoon.sx", "filemoon.to", "filemoon.in", "filemoon.nl"
  )

  /** Domini osservati nei cataloghi per la famiglia StreamWish (estrattore integrato). */
  private val STREAMWISH_DOMAINS = listOf(
    "streamwish.to", "streamwish.com", "streamwish.sx", "streamwish.xyz"
  )

  private val MAXSTREAM_DOMAINS = listOf(
    "maxstream.video", "maxstream.net", "maxstream.org"
  )

  /**
   * Domini Mixdrop osservati nei cataloghi. `m1xdrop.net` e `mxdrop.to`
   * redirigono sui mirror attivi (`mxdrop.click` -> `mixdrop.ag`): anche
   * l'URL finale deve restare riconosciuto come Mixdrop, altrimenti un
   * redirect da shortener viene scartato come "hoster non supportato".
   */
  private val MIXDROP_DOMAINS = listOf(
    "mixdrop.co", "mixdrop.to", "mixdrop.ch", "mixdrop.ag", "mixdrop.sx",
    "mixdrop.bz", "mxdrop.top", "mxdrop.click", "mxdrop.to", "m1xdrop.net"
  )
  /** `/f/{code}` -> `/e/{code}`: stessa pagina file, path embed del player. */
  private val MIXDROP_FILE_PATH_REGEX = Regex("""^(https?://[^/]+)/f/""", RegexOption.IGNORE_CASE)

  /**
   * Hoster riconosciuti ma senza estrattore integrato: i loro link vengono
   * scartati con un log esplicito ("hoster non supportato") invece di essere
   * ignorati silenziosamente.
   */
  private val OTHER_HOSTER_DOMAINS = listOf(
    "stayonline.pro", "uprot.net",
    "turbovid.co", "turbovid.pw", "turbovid.net",
    "deltabit.co", "deltabit.net",
    "voe.sx", "voe.to", "voe.eu",
    "dood.so", "dood.wf", "dood.li", "doodstream.com",
    "streamtape.com", "streamtape.net", "streamtape.to"
  )

  /** Tutti i domini riconosciuti (solo base domain: i sottodomini sono accettati). */
  private val HOSTER_DOMAINS =
    (VIDHIDE_DOMAINS + FILEMOON_DOMAINS + STREAMWISH_DOMAINS + MAXSTREAM_DOMAINS + MIXDROP_DOMAINS + OTHER_HOSTER_DOMAINS)
      .distinct()
}
