package com.example.data.streaming.providers

import android.util.Log
import com.example.data.streaming.StreamSource
import com.example.data.streaming.extractors.ExtractorHttp
import com.example.data.streaming.extractors.MaxStreamExtractor
import com.example.data.streaming.extractors.MixdropExtractor
import com.example.data.streaming.extractors.VideoExtractor
import java.io.IOException
import java.net.URI
import java.net.URLEncoder

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

  /** Label hoster riconosciute nei link dei cataloghi italiani. */
  private val HOSTER_LABEL_REGEX =
    Regex("""^\s*(maxstream|mixdrop|turbovid|delta\s?bit|deltabit|voe|dood|filemoon|streamtape|flash|stream(?:ing)?)\s*$""", RegexOption.IGNORE_CASE)

  fun matchesHosterLabel(label: String): Boolean = HOSTER_LABEL_REGEX.matches(label.trim())

  /** true se l'URL punta a uno shortener/hoster noto (esclude trailer/iframe generici). */
  fun isHosterUrl(url: String): Boolean = HOSTER_URL_KEYWORDS.any { it in url.lowercase() }

  /**
   * Risolve un link hoster/shortener nel suo URL finale.
   *
   * @return l'URL risolto, oppure `null` se il link non è risolvibile in automatico
   *         (es. shortener con captcha) o non è un hoster supportato.
   */
  fun resolveShortLink(url: String, referer: String): String? {
    val stayMatch = STAYONLINE_REGEX.find(url)
    if (stayMatch != null) {
      val origin = stayMatch.groupValues[1]
      val code = stayMatch.groupValues[3]
      val endpoint = if (stayMatch.groupValues[2] == "e") "linkEmbedView.php" else "linkView.php"
      return try {
        val body = ExtractorHttp.postForm(
          url = "$origin/ajax/$endpoint",
          referer = referer,
          form = mapOf("id" to code)
        )
        // Parsing con regex: funziona sia sul dispositivo sia nei test JVM
        // dove le classi org.json sono stub.
        val status = JSON_STATUS_REGEX.find(body)?.groupValues?.get(1).orEmpty()
        val value = JSON_VALUE_REGEX.find(body)?.groupValues?.get(1)
          ?.replace("\\/", "/")?.trim().orEmpty()
        if (status == "success" && value.startsWith("http")) {
          value
        } else {
          val message = JSON_MSG_REGEX.find(body)?.groupValues?.get(1) ?: "risposta non valida"
          Log.w(TAG, "shortener stayonline non risolto ($code): $message")
          null
        }
      } catch (e: Exception) {
        Log.w(TAG, "shortener stayonline fallito ($url): ${e.message}")
        null
      }
    }

    if (UPROT_REGEX.containsMatchIn(url)) {
      // uprot.net: captcha a immagini ("seleziona tutte le immagini con..."),
      // nessun endpoint JSON pubblico -> non risolvibile in automatico.
      Log.w(TAG, "link protetto da captcha uprot, salto: $url")
      return null
    }

    return url
  }

  /** Ritorna l'estrattore adatto all'hoster dell'URL, oppure null se non supportato. */
  fun extractorFor(url: String): VideoExtractor? {
    val host = try {
      URI(url).host?.lowercase() ?: return null
    } catch (e: Exception) {
      return null
    }
    return when {
      "maxstream" in host -> maxStreamExtractor
      listOf("mixdrop", "m1xdrop", "mxdrop").any { host.contains(it) } -> mixdropExtractor
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
    onResolved: ((String) -> Unit)? = null
  ): List<StreamSource> {
    val sources = mutableListOf<StreamSource>()
    val seenRaw = HashSet<String>()
    // Gli stessi file compaiono più volte nell'articolo (tabella + iframe):
    // la dedupe va fatta sull'URL RISOLTO, non su quello dello shortener.
    val seenResolved = HashSet<String>()

    for ((label, rawUrl) in rawUrls) {
      if (rawUrl.isBlank() || !seenRaw.add(rawUrl)) continue
      val resolved = resolveShortLink(rawUrl, referer)
      if (resolved.isNullOrBlank() || !seenResolved.add(resolved)) continue
      onResolved?.invoke(resolved)

      val extractor = extractorFor(resolved)
      if (extractor == null) {
        Log.i(TAG, "hoster non supportato per ora: $resolved (label=$label)")
        continue
      }
      try {
        sources += extractor.extract(resolved)
      } catch (e: Exception) {
        Log.w(TAG, "estrazione fallita su $resolved: ${e.message}")
      }
    }
    return sources
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
  fun matchScore(slug: String, title: String): Int {
    val normSlug = normalize(slug)
    val words = normalize(title).split(" ").filter { it.length >= 3 }
    if (words.isEmpty()) return 0
    return words.count { normSlug.contains(it) } * 10 + if (normSlug.contains(normalize(title))) 50 else 0
  }

  private val STAYONLINE_REGEX = Regex("""^(https?://(stayonline\.pro))/(?:l|e)/([A-Za-z0-9]+)/?""")
  private val UPROT_REGEX = Regex("""https?://uprot\.net/(?:msf|mse|msfi|msei|msd)/""", RegexOption.IGNORE_CASE)

  private val JSON_STATUS_REGEX = Regex(""""status"\s*:\s*"([^"]+)"""")
  private val JSON_VALUE_REGEX = Regex(""""value"\s*:\s*"([^"]+)"""")
  private val JSON_MSG_REGEX = Regex(""""(?:message|msg)"\s*:\s*"([^"]+)"""")

  private val HOSTER_URL_KEYWORDS = listOf(
    "stayonline.pro", "uprot.net", "mixdrop", "m1xdrop", "mxdrop", "maxstream",
    "turbovid", "deltabit", "delta", "voe.", "dood", "filemoon", "streamtape"
  )
}
