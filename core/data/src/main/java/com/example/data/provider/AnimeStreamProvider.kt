package com.example.data.provider

import android.util.Base64
import android.util.Log
import com.example.data.api.TmdbApiClient
import com.example.data.repository.MediaRepository
import com.example.data.streaming.StreamProvider
import com.example.data.streaming.StreamSource
import com.example.data.streaming.extractors.ExtractorHttp
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Provider dedicato agli **anime** (titoli da Crunchyroll o con genere
 * "Animation" / "Animazione" / "Anime").
 *
 * Catena verificata su AnimeSaturn (catalogo italiano Sub ITA/ITA):
 *  1. `GET {base}/api/search?q={query}` -> `{"results":[{"title","url":"/anime/{slug}","year","episodes"}]}`;
 *     le query sono costruite con **sia** `title` (IT/EN) **sia** `originalTitle` (Romaji/Giapponese),
 *     più varianti "… Season N" e, in extremis, la parola più lunga del titolo.
 *  2. `GET {base}/api/watch/{slug}/ep-{n}` -> `{"ok":true,"videoUrl":"{origin}/embed/{id}?token=…&expires=…",
 *     "servers":[…],"totalEps":N}`. Episodio inesistente -> HTTP 404 `{"ok":false,…}`.
 *  3. `GET {origin}/embed/{id}/playlist?token=…&expires=…` -> `{"d":"<base64>","p":"","t":""}`
 *     dove `d` è **base64XOR** con chiave il `token` (come da `embed.js`: `L(e,k)`).
 *  4. Decodifica -> URL diretto `.mp4` / `.m3u8` con `token`/`expires` propri.
 *
 * Mapping episodi: per una serie a stagione si provano sia l'**episodio relativo**
 * (S2 E3 -> `ep-3` sull'entry della stagione) sia l'**episodio assoluto/progressivo**
 * (somma degli episodi delle stagioni precedenti via TMDB), scegliendo l'ordine in
 * base al numero totale di episodi dell'entry (numbering continuo vs stagione separata).
 *
 * Header obbligatori: User-Agent desktop + Referer coerente con l'host del player,
 * altrimenti il CDN risponde 403 Forbidden.
 */
class AnimeStreamProvider : StreamProvider {

  override suspend fun getStreams(
    tmdbId: Int,
    isTv: Boolean,
    season: Int?,
    episode: Int?,
    title: String?,
    year: Int?
  ): List<StreamSource> = getStreams(tmdbId, isTv, season, episode, title, originalTitle = null, year = year)

  /**
   * Variante con **titolo originale** (Romaji/Giapponese): usato da `StreamManager`
   * quando il chiamante conosce `media.originalTitle`.
   */
  suspend fun getStreams(
    tmdbId: Int,
    isTv: Boolean,
    season: Int?,
    episode: Int?,
    title: String?,
    originalTitle: String?,
    year: Int?
  ): List<StreamSource> = withContext(Dispatchers.IO) {
    val s = (season ?: 1).coerceAtLeast(1)
    val e = (episode ?: 1).coerceAtLeast(1)
    Log.d(
      TAG,
      "Searching anime stream for: ${title.orEmpty()} (orig:${originalTitle.orEmpty()}) S${s}E$e" +
        " (tmdbId=$tmdbId isTv=$isTv year=$year)"
    )

    val queries = buildSearchQueries(title, originalTitle, s)
    if (queries.isEmpty()) throw IOException("Titolo anime assente: impossibile cercare")

    // Episodio relativo (S2E3 -> 3) + progressivo/assoluto (se S2E3 -> somma stagioni + 3)
    val relativeCandidates = LinkedHashSet<Int>().apply { add(e) }
    if (s > 1 && isTv) {
      val absolute = absoluteEpisode(tmdbId, s, e)
      if (absolute != null && absolute != e) {
        Log.d(TAG, "Mapping episodi: S${s}E$e -> progressivo #$absolute")
        relativeCandidates.add(absolute)
      }
    }

    val failures = mutableListOf<String>()
    for (base in BASE_URLS) {
      currentCoroutineContext().ensureActive()
      for (query in queries) {
        currentCoroutineContext().ensureActive()
        val entries = try {
          rankEntries(searchAnime(base, query), query, year)
        } catch (ex: CancellationException) {
          throw ex
        } catch (ex: Exception) {
          failures += "search '$query': ${ex.message}"
          continue
        }
        if (entries.isEmpty()) {
          failures += "nessun risultato per '$query'"
          continue
        }
        for (entry in entries) {
          currentCoroutineContext().ensureActive()
          val episodes = orderEpisodes(entry.episodes, relativeCandidates)
          for (ep in episodes) {
            currentCoroutineContext().ensureActive()
            try {
              val sources = resolveEpisode(base, entry.slug, ep)
              if (sources.isNotEmpty()) {
                Log.i(TAG, "Sorgente trovata: '${entry.title}' ep $ep -> ${sources.size} link")
                return@withContext sources
              }
            } catch (ex: CancellationException) {
              throw ex
            } catch (ex: Exception) {
              failures += "${entry.title} ep$ep: ${ex.message}"
            }
          }
        }
      }
    }
    throw IOException("AnimeStream non risolvibile (${failures.take(4).joinToString(" | ")})")
  }

  // --------------------------------------------------------------------- //
  //  Ricerca
  // --------------------------------------------------------------------- //

  private data class AnimeEntry(
    val slug: String,
    val title: String,
    val year: String?,
    val episodes: Int?
  )

  /** Query ordinate: titolo IT/EN, titolo originale, varianti stagione, parola chiave. */
  private fun buildSearchQueries(title: String?, originalTitle: String?, season: Int): List<String> {
    val queries = LinkedHashSet<String>()
    fun add(value: String?) {
      value?.trim()?.takeIf { it.isNotBlank() }?.let { queries.add(it) }
    }
    add(title)
    // Il titolo originale e' utile solo se in caratteri latini (Romaji/EN):
    // i titoli in giapponese/cinesi non trovano risultati nei cataloghi.
    add(originalTitle?.takeIf { it.any { c -> c in 'a'..'z' || c in 'A'..'Z' } })
    val baseTitle = title?.trim().orEmpty()
    if (season > 1 && baseTitle.isNotBlank()) {
      add("$baseTitle Season $season")
      add("$baseTitle $season")
    }
    // Ultima spiaggia: i cataloghi anime usano spesso il titolo originale/inglese:
    // si cerca la parola più lunga del titolo (min 4 caratteri) e si classifica
    // poi i risultati con [relevance].
    baseTitle.split(Regex("[^\\p{L}\\p{N}]+"))
      .filter { it.length >= 4 }
      .maxByOrNull { it.length }
      ?.let { add(it) }
    return queries.toList().take(5)
  }

  private suspend fun searchAnime(base: String, query: String): List<AnimeEntry> {
    val url = "$base/api/search?q=${URLEncoder.encode(query, "UTF-8")}"
    val body = ExtractorHttp.get(url, referer = "$base/")
    val array = JSONObject(body).optJSONArray("results") ?: JSONArray()
    return (0 until array.length()).mapNotNull { i ->
      val obj = array.optJSONObject(i) ?: return@mapNotNull null
      val pageUrl = obj.optString("url")
      val slug = pageUrl.substringAfter("/anime/", "").trim('/')
      if (slug.isEmpty()) return@mapNotNull null
      AnimeEntry(
        slug = slug,
        title = obj.optString("title"),
        year = obj.optString("year").takeIf { it.isNotBlank() },
        episodes = obj.optString("episodes").toIntOrNull()
      )
    }
  }

  /** Ordina i risultati per rilevanza e ne tiene i primi [MAX_RESULTS]. */
  private fun rankEntries(entries: List<AnimeEntry>, query: String, year: Int?): List<AnimeEntry> =
    entries
      .sortedByDescending { relevance(it, query, year) }
      .take(MAX_RESULTS)
      .filter { relevance(it, query, year) > 0 }

  private fun relevance(entry: AnimeEntry, query: String, year: Int?): Int {
    val e = normalize(entry.title)
    val q = normalize(query)
    if (e.isEmpty() || q.isEmpty()) return 0
    var score = when {
      e == q -> 100
      q.contains(e) || e.contains(q) -> 80
      else -> {
        val et = e.split(' ').toSet()
        val qt = q.split(' ').toSet()
        val common = et.intersect(qt).size
        if (common == 0) 0 else (60.0 * common / minOf(et.size, qt.size)).toInt()
      }
    }
    if (year != null && entry.year == year.toString()) score += 8
    return score
  }

  private fun normalize(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD)
      .replace(Regex("\\p{M}"), "")
      .lowercase(Locale.ROOT)
      .replace(Regex("[^a-z0-9]+"), " ")
      .trim()

  /**
   * Ordine degli episodi da provare per un'entry:
   * - entry con pochi episodi (stagione separata) -> prima l'episodio **relativo**;
   * - entry che copre l'intera serie (numbering continuo) -> prima l'**assoluto**.
   */
  private fun orderEpisodes(entryEpisodes: Int?, candidates: Collection<Int>): List<Int> {
    val list = candidates.toList()
    if (list.size < 2) return list
    val relative = list[0]
    val absolute = list[1]
    val continuous = entryEpisodes != null && entryEpisodes >= absolute
    return if (continuous) listOf(absolute, relative) else listOf(relative, absolute)
  }

  // --------------------------------------------------------------------- //
  //  Risoluzione episodio -> URL media
  // --------------------------------------------------------------------- //

  private suspend fun resolveEpisode(base: String, slug: String, episode: Int): List<StreamSource> {
    val body = ExtractorHttp.get("$base/api/watch/$slug/ep-$episode", referer = "$base/")
    val json = JSONObject(body)
    if (!json.optBoolean("ok", false)) {
      throw IOException(json.optString("error", "episodio non trovato"))
    }

    // URL del player attivo + quelli degli altri server (stesso embed, token diverso)
    val embeds = LinkedHashSet<String>()
    json.optString("videoUrl").takeIf { it.isNotBlank() }?.let { embeds.add(it) }
    val servers = json.optJSONArray("servers")
    if (servers != null) {
      for (i in 0 until servers.length()) {
        val server = servers.optJSONObject(i) ?: continue
        server.optString("link").takeIf { it.isNotBlank() }?.let { embeds.add(it) }
        server.optString("embedUrl").takeIf { it.isNotBlank() }?.let { embeds.add(it) }
      }
    }

    val failures = mutableListOf<String>()
    for (embed in embeds) {
      try {
        val sources = extractFromEmbed(embed)
        if (sources.isNotEmpty()) return sources
        failures += "embed senza sorgenti"
      } catch (ex: Exception) {
        failures += ex.message ?: "errore embed"
      }
    }
    throw IOException(failures.joinToString(" | ").ifBlank { "nessun embed disponibile" })
  }

  /** Pagina embed -> `/playlist` -> decodifica `d` (base64 XOR token) -> URL diretto. */
  private suspend fun extractFromEmbed(embedUrl: String): List<StreamSource> {
    val uri = try {
      URI(embedUrl)
    } catch (ex: Exception) {
      throw IOException("URL embed non valido: $embedUrl")
    }
    val origin = "${uri.scheme}://${uri.authority}"
    val path = uri.path ?: ""
    val embedId = path.substringAfter("/embed/", "").substringBefore("/")
    if (embedId.isBlank()) throw IOException("id embed assente in $embedUrl")

    val query = uri.query.orEmpty()
    fun param(name: String): String =
      query.split('&')
        .firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        .orEmpty()

    val token = param("token")
    val expires = param("expires")
    if (token.isBlank()) throw IOException("token assente nell'embed")

    val playlistUrl = buildString {
      append(origin)
      append("/embed/").append(embedId).append("/playlist?token=")
      append(URLEncoder.encode(token, "UTF-8"))
      if (expires.isNotBlank()) append("&expires=").append(URLEncoder.encode(expires, "UTF-8"))
    }
    val body = ExtractorHttp.get(playlistUrl, referer = "$origin/")
    val json = JSONObject(body)
    val encoded = json.optString("d")
    if (encoded.isBlank()) throw IOException("playlist senza sorgente ('d' vuoto)")

    val decoded = xorBase64(encoded, token).trim()
    val proxy = json.optString("p").orEmpty()
    val mediaUrl = when {
      decoded.startsWith("http://") || decoded.startsWith("https://") -> decoded
      decoded.startsWith("/") -> origin + decoded
      proxy.isNotBlank() -> origin + proxy + decoded
      else -> throw IOException("URL media non riconosciuto: ${decoded.take(80)}")
    }

    val headers = mapOf(
      "User-Agent" to ExtractorHttp.USER_AGENT,
      "Referer" to "$origin/",
      "Accept" to "*/*"
    )
    Log.d(TAG, "Stream decodificato: $mediaUrl")
    return listOf(
      StreamSource(
        streamUrl = mediaUrl,
        quality = "Auto",
        serverName = SERVER_NAME,
        headers = headers
      )
    )
  }

  /** `base64decode(d)` XOR con chiave ripetuta il `token` (vedi embed.js: funzione L). */
  private fun xorBase64(encoded: String, key: String): String {
    val raw = try {
      Base64.decode(encoded, Base64.DEFAULT)
    } catch (ex: Exception) {
      throw IOException("decode base64 fallita: ${ex.message}")
    }
    val keyBytes = key.toByteArray(Charsets.ISO_8859_1)
    if (keyBytes.isEmpty()) throw IOException("chiave di decodifica vuota")
    val out = ByteArray(raw.size) { i -> (raw[i].toInt() xor keyBytes[i % keyBytes.size].toInt()).toByte() }
    return String(out, Charsets.UTF_8)
  }

  // --------------------------------------------------------------------- //
  //  Mapping stagione/episodio (relativo vs assoluto)
  // --------------------------------------------------------------------- //

  /** Episodio progressivo = somma episodi delle stagioni precedenti + episodio corrente. */
  private suspend fun absoluteEpisode(tmdbId: Int, season: Int, episode: Int): Int? {
    if (tmdbId <= 0) return null
    return try {
      val apiKey = MediaRepository.getEffectiveApiKey()
      var total = 0
      for (s in 1 until season) {
        val details = TmdbApiClient.service.getSeasonDetails(
          seriesId = tmdbId,
          seasonNumber = s,
          apiKey = apiKey
        )
        total += details.episodes?.size ?: 0
      }
      if (total <= 0) null else total + episode
    } catch (ex: Exception) {
      Log.w(TAG, "Calcolo episodio assoluto fallito (S${season}E$episode): ${ex.message}")
      null
    }
  }

  companion object {
    private const val TAG = "ANIME_STREAM"
    private const val SERVER_NAME = "AnimeSaturn"
    private const val MAX_RESULTS = 3

    /** Mirror del sito (il .com reindirizza sul .net). */
    private val BASE_URLS = listOf(
      "https://www.animesaturn.net",
      "https://animesaturn.com"
    )
  }
}
