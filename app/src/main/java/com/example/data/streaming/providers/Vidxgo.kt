package com.example.data.streaming.providers

import java.net.URI

/**
 * Costanti e regole condivise dal player **vidxgo** (`v.vidxgo.co`) e dai suoi
 * CDN (`*.d2b.you`), usato dal catalogo Altadefinizione.
 *
 * Il player espone due percorsi:
 *  - pagina HTML offuscata (non usata: richiederebbe di decodificare gli script);
 *  - endpoint JSON `/t/{id}` (film) e `/t/{id}/{stagione}/{episodio}` (serie),
 *    che restituisce direttamente la master playlist HLS con token ed expiry.
 *
 * L'identificativo del player è la parte numerica dell'IMDb ID, con zero-padding
 * a 7 cifre: `tt0133093` -> `0133093`, `tt16358384` -> `16358384` (già 8 cifre).
 * Senza padding il player risponde 404.
 *
 * Il CDN HLS richiede un User-Agent da browser e gli header `Origin`/`Referer` +
 * `Sec-Fetch-*`: con lo User-Agent di default dell'app risponde 403.
 */
object Vidxgo {

  /** Origine del player e dell'endpoint di refresh del token. */
  const val ORIGIN = "https://v.vidxgo.co"

  /** Suffisso dei CDN HLS che richiedono il refresh del token. */
  const val CDN_HOST_SUFFIX = ".d2b.you"

  /**
   * User-Agent browser richiesto dal CDN. NON usare lo User-Agent di default
   * dell'app (`StreamNovaTV/...`): il CDN risponde 403.
   */
  const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

  /**
   * Header applicati dal player alle richieste HLS. `User-Agent` viene letto da
   * [com.example.ui.screens.player.PlayerHttpDataSourceFactory.updateHeaders] e
   * applicato al player; gli altri header vengono inviati al CDN.
   */
  val CDN_HEADERS: Map<String, String> = mapOf(
    "User-Agent" to BROWSER_USER_AGENT,
    "Origin" to ORIGIN,
    "Referer" to "$ORIGIN/",
    "Sec-Fetch-Dest" to "empty",
    "Sec-Fetch-Mode" to "cors",
    "Sec-Fetch-Site" to "same-site",
    "Accept" to "*/*"
  )

  private val IMDb_REGEX = Regex("""(?i)^tt(\d+)$""")

  /**
   * Converte un IMDb ID (`tt0133093`) nell'identificativo numerico del player,
   * con zero-padding a 7 cifre.
   *
   * @return l'identificativo, oppure `null` se il valore non contiene cifre.
   */
  fun playerId(imdbId: String?): String? {
    // Un IMDb può arrivare con suffisso episodio (`tt123:1:2`): il player usa
    // solo la parte numerica dell'ID serie/film.
    val value = imdbId?.trim()?.substringBefore(':')?.trim().orEmpty()
    if (value.isBlank()) return null
    val digits = IMDb_REGEX.find(value)?.groupValues?.get(1)
      ?: value.filter { it.isDigit() }
    return digits.takeIf { it.isNotBlank() }?.padStart(7, '0')
  }

  /**
   * Percorso dell'endpoint JSON del token.
   *
   * @param playerId risultato di [playerId].
   * @param season stagione (solo serie TV).
   * @param episode episodio (solo serie TV).
   */
  fun tokenPath(playerId: String, season: Int? = null, episode: Int? = null): String {
    if (season == null || episode == null) return "/t/$playerId"
    val s = season.coerceAtLeast(1)
    val e = episode.coerceAtLeast(1)
    return "/t/$playerId/$s/$e"
  }

  /**
   * Converte un URL del player (`https://v.vidxgo.co/{id}[/{s}/{e}]?…`) nel
   * percorso dell'endpoint token (`/t/{id}` oppure `/t/{id}/{s}/{e}`).
   *
   * Ritorna `null` se l'URL non appartiene al player vidxgo: il chiamante deve
   * usare il percorso di estrazione normale.
   */
  fun tokenPathForPlayerUrl(playerUrl: String): String? {
    val uri = try {
      URI(playerUrl)
    } catch (e: Exception) {
      return null
    }
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
    if (host != "vidxgo.co" && !host.endsWith(".vidxgo.co")) return null

    val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
    val id = segments.getOrNull(0)?.takeIf { value -> value.all(Char::isDigit) } ?: return null
    val season = segments.getOrNull(1)?.toIntOrNull()
    val episode = segments.getOrNull(2)?.toIntOrNull()
    return if (season != null && episode != null) {
      tokenPath(id, season, episode)
    } else {
      tokenPath(id)
    }
  }

  /** Qualità massima dichiarata nella master playlist HLS (`RESOLUTION=WxH`). */
  fun qualityFromPlaylist(playlist: String): String {
    val resolutions = RESOLUTION_REGEX.findAll(playlist).mapNotNull { match ->
      val width = match.groupValues[1].toIntOrNull()
      val height = match.groupValues[2].toIntOrNull()
      if (width != null && height != null) width to height else null
    }.toList()
    if (resolutions.isEmpty()) return "Auto"
    val maxWidth = resolutions.maxOf { it.first }
    val maxHeight = resolutions.maxOf { it.second }
    return when {
      maxWidth >= 3000 || maxHeight >= 2000 -> "4K"
      maxWidth >= 1800 || maxHeight >= 1000 -> "1080p"
      maxWidth >= 1100 || maxHeight >= 650 -> "720p"
      maxWidth >= 700 -> "480p"
      else -> "Auto"
    }
  }

  private val RESOLUTION_REGEX = Regex("""RESOLUTION=(\d+)x(\d+)""")
}
