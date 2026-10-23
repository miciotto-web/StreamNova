package com.example.data.streaming.extractors

import java.net.URI

/**
 * Estrae gli URL media (HLS/MP4) dal markup o dagli script di un player
 * JWPlayer-like.
 *
 * Supporta le forme osservate negli embed degli hoster:
 *  - `sources: [{ file: "https://cdn/.../master.m3u8" }]`
 *  - `file: "..."`, `"file": "..."`
 *  - `hls2`/`hls3`/`hls4`, `videoUrl`, `streamUrl`, `url`, `src`
 *  - URL `.m3u8`/`.mp4` assoluti, protocol-relative o relativi all'origin
 *  - script packed (decodificati con [JsPacked])
 *
 * Le chiavi JSON vengono interpretate: il valore di una chiave nota (`file`,
 * `url`, `src`, ...) è una sorgente, il valore di una chiave estranea (per
 * esempio `profile`) non lo è. I valori delle chiavi note sono sempre
 * raccolti; quelli delle chiavi sconosciute vengono ignorati per non
 * generare falsi positivi.
 *
 * Non esegue JavaScript e non valida che l'URL risponda: quella verifica spetta
 * al player. Gli URL `.txt` (usati da alcuni hoster come alias HLS) non vengono
 * restituiti perché Media3 non può inferirne il tipo dal solo path.
 */
internal object PlayerSourceParser {

  /** URL media trovati, con HLS prima degli MP4 e senza duplicati. */
  fun findMediaUrls(html: String, baseUrl: String): List<String> {
    val normalized = html
      .replace("\\/", "/")
      .replace("\\u0026", "&")
      .replace("&amp;", "&")
    val scripts = buildList {
      add(normalized)
      addAll(JsPacked.unpackAll(normalized))
    }

    val found = LinkedHashSet<String>()
    scripts.forEach { script ->
      SOURCE_FILE_REGEX.findAll(script).forEach { add(clean(it.groupValues[1]), baseUrl, found) }
      MEDIA_KEY_REGEX.findAll(script).forEach { add(clean(it.groupValues[1]), baseUrl, found) }
      MEDIA_URL_REGEX.findAll(script).forEach { add(clean(it.value), baseUrl, found) }
      PROTOCOL_RELATIVE_REGEX.findAll(script).forEach { add(clean(it.value), baseUrl, found) }
      RELATIVE_MEDIA_REGEX.findAll(script).forEach { add(clean(it.groupValues[1]), baseUrl, found) }
    }

    // L'ordinamento guarda solo il path: la query string può contenere
    // ".m3u8" e falsare la classificazione. `sortedBy` è stabile, quindi
    // l'ordine di scoperta resta invariato fra gli HLS e fra gli MP4.
    return found.sortedBy { if (isHlsUrl(it)) 0 else 1 }
  }

  private fun clean(raw: String): String = raw.trim().trimEnd('\\', '"', '\'')

  private fun add(raw: String, baseUrl: String, out: MutableSet<String>) {
    if (raw.isBlank()) return
    val absolute = when {
      raw.startsWith("//") -> "https:$raw"
      raw.startsWith("/") -> originOf(baseUrl) + raw
      else -> raw
    }
    if (!absolute.startsWith("http", ignoreCase = true)) return
    if (!isMediaUrl(absolute)) return
    out += absolute
  }

  /** Path dell'URL: query string e fragment non fanno parte del path. */
  private fun pathOf(url: String): String = url.substringBefore('?').substringBefore('#')

  private fun isMediaUrl(url: String): Boolean {
    val path = pathOf(url)
    return path.contains(".m3u8", ignoreCase = true) || path.contains(".mp4", ignoreCase = true)
  }

  /** Vero se il path è un manifest HLS: la query string non conta. */
  private fun isHlsUrl(url: String): Boolean = pathOf(url).contains(".m3u8", ignoreCase = true)

  private fun originOf(url: String): String = try {
    val uri = URI(url)
    val scheme = uri.scheme ?: return ""
    val host = uri.host ?: return ""
    val port = uri.port
    if (port > 0 && !isDefaultPort(scheme, port)) "$scheme://$host:$port" else "$scheme://$host"
  } catch (e: Exception) {
    ""
  }

  private fun isDefaultPort(scheme: String, port: Int): Boolean =
    (scheme.equals("http", true) && port == 80) ||
      (scheme.equals("https", true) && port == 443)

  /**
   * Chiave `file`.
   *
   * Il lookbehind impedisce che il suffisso di una chiave come `profile`
   * venga scambiato per la chiave `file` (`pro` + `file`).
   */
  private val SOURCE_FILE_REGEX = Regex(
    """(?<![A-Za-z0-9_])["']?file["']?\s*:\s*["']([^"']+)["']""",
    RegexOption.IGNORE_CASE
  )

  /**
   * Altre chiavi note che contengono direttamente l'URL media.
   *
   * Solo le chiavi elencate sono considerate sorgenti: una chiave estranea
   * (per esempio `profile`) non può trasformare il proprio valore in una
   * sorgente media.
   *
   * L'ultimo ramo (`[A-Za-z0-9_.-]*(?:url|uri|src)`) copre le forme composte
   * usate dai vari deployment: `hlsurl`, `hls_url`, `hlsUrl`, `videoUrl`,
   * `video_src`, `masterUrl`, `playback-url`, `fileUrl`...
   */
  private val MEDIA_KEY_REGEX = Regex(
    """(?<![A-Za-z0-9_])["']?(?:file|sources?|media|stream|hls\d?|[A-Za-z0-9_.\-]*(?:url|uri|src))["']?\s*[:=]\s*["']([^"']+)["']""",
    RegexOption.IGNORE_CASE
  )

  /**
   * URL media assoluti.
   *
   * I delimitatori JSON/JS (`}`, `]`, `)`, `,`) non possono far parte della
   * query string, altrimenti finirebbero nell'URL estratto.
   *
   * Il lookbehind esclude i valori di chiavi quotate (`"profile":"..."` e
   * `"profile": "..."`): quelli vengono raccolti solo se la chiave è nota
   * (vedi [MEDIA_KEY_REGEX]). Sono lookbehind di lunghezza fissa, quindi
   * deterministi anche su ART/Java.
   */
  private val MEDIA_URL_REGEX = Regex(
    """(?<![:]["'])(?<!: ["'])(?<!:\t["'])https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\}\]),]*)?""",
    RegexOption.IGNORE_CASE
  )

  /**
   * URL media protocol-relative (`//cdn/...`).
   *
   * Il lookbehind `:` evita di riconoscere come protocol-relative il `//` di
   * un URL assoluto (`https://`); gli altri lookbehind escludono i valori di
   * chiavi quotate come in [MEDIA_URL_REGEX].
   */
  private val PROTOCOL_RELATIVE_REGEX = Regex(
    """(?<![:]["'])(?<!: ["'])(?<!:\t["'])(?<!:)//[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\}\]),]*)?""",
    RegexOption.IGNORE_CASE
  )
  private val RELATIVE_MEDIA_REGEX = Regex(
    """["'](/[^"']+?\.(?:m3u8|mp4)(?:\?[^"']*)?)["']""",
    RegexOption.IGNORE_CASE
  )
}
