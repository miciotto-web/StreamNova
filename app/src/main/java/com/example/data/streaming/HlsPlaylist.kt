package com.example.data.streaming

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Variante dichiarata da una master playlist HLS (`#EXT-X-STREAM-INF`).
 *
 * @param url URL assoluta della playlist di variante (risolta rispetto alla
 *        master, con il token della master propagato quando la variante è
 *        relativa e non porta una propria query).
 * @param height altezza convenzionale ricavata da `RESOLUTION=WxH`; `null` se
 *        la variante non dichiara alcuna risoluzione.
 * @param bandwidth banda dichiarata (`BANDWIDTH=`), solo informativa.
 * @param codecs codec dichiarati nell'attributo `CODECS=`, in ordine di
 *        dichiarazione (es. `["avc1.64001f", "mp4a.40.2"]`); vuoto se assente.
 */
data class HlsVariant(
  val url: String,
  val height: Int?,
  val bandwidth: Long? = null,
  val codecs: List<String> = emptyList()
)

/**
 * Parser HLS minimale e tollerante, condiviso dal provider Altadefinizione e
 * dal verificatore di riproducibilità.
 *
 * Non è un player: legge solo le direttive necessarie a classificare le
 * varianti e a individuare il primo segmento da provare.
 */
object HlsPlaylist {

  /** `true` se il testo è una master playlist (contiene varianti). */
  fun isMaster(playlist: String): Boolean =
    playlist.lineSequence().any { it.trimStart().startsWith("#EXT-X-STREAM-INF") }

  /** `true` se il testo è una playlist HLS (master o media). */
  fun isHls(playlist: String): Boolean =
    playlist.trimStart('\uFEFF', '\r', '\n', ' ', '\t').startsWith("#EXTM3U")

  /**
   * Estrae le varianti di una master playlist, in ordine di dichiarazione.
   * Le righe non valide vengono ignorate: nessun dato inventato.
   */
  fun variants(master: String, masterUrl: String): List<HlsVariant> {
    val lines = master.lineSequence().map { it.trim() }.toList()
    val out = mutableListOf<HlsVariant>()
    var index = 0
    while (index < lines.size) {
      val line = lines[index]
      if (!line.startsWith("#EXT-X-STREAM-INF")) {
        index++
        continue
      }
      val attributes = line.substringAfter(':', "")
      val resolution = RESOLUTION_REGEX.find(attributes)
      val width = resolution?.groupValues?.getOrNull(1)?.toIntOrNull()
      val height = resolution?.groupValues?.getOrNull(2)?.toIntOrNull()
      val bandwidth = BANDWIDTH_REGEX.find(attributes)?.groupValues?.getOrNull(1)?.toLongOrNull()
      val codecs = CODECS_REGEX.find(attributes)?.groupValues?.getOrNull(1)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()

      var uri: String? = null
      var next = index + 1
      while (next < lines.size) {
        val candidate = lines[next]
        if (candidate.isNotEmpty() && !candidate.startsWith("#")) {
          uri = candidate
          break
        }
        next++
      }
      if (uri != null) {
        val resolved = resolveUri(masterUrl, uri)
        val conventionalHeight = StreamResolution.heightOfResolution(width, height)
          .takeIf { it != StreamResolution.UNKNOWN }
        out += HlsVariant(resolved, conventionalHeight, bandwidth, codecs)
        index = next
      }
      index++
    }
    return out.distinctBy { it.url }
  }

  /**
   * URI del primo segmento di una media playlist, oppure `null` se non ne
   * esiste alcuno (playlist vuota o non media).
   */
  fun firstSegmentUri(playlist: String, playlistUrl: String): String? {
    val lines = playlist.lineSequence().map { it.trim() }.toList()
    for (index in lines.indices) {
      if (!lines[index].startsWith("#EXTINF")) continue
      for (next in index + 1 until lines.size) {
        val candidate = lines[next]
        if (candidate.isEmpty() || candidate.startsWith("#")) continue
        return resolveUri(playlistUrl, candidate)
      }
    }
    return null
  }

  /**
   * URI del segmento di inizializzazione (`#EXT-X-MAP:URI="..."`) per i flussi
   * fMP4, oppure `null`.
   */
  fun initSegmentUri(playlist: String, playlistUrl: String): String? {
    val line = playlist.lineSequence()
      .map { it.trim() }
      .firstOrNull { it.startsWith("#EXT-X-MAP") }
      ?: return null
    val raw = MAP_URI_REGEX.find(line)?.groupValues?.getOrNull(1)
      ?.takeIf { it.isNotBlank() }
      ?: return null
    return resolveUri(playlistUrl, raw)
  }

  /**
   * Risolve un URI relativo rispetto alla playlist. Se la variante non porta
   * una query ma la playlist padre ne ha una (tipico dei token `?t=…&e=…`),
   * la query viene propagata: senza token il CDN risponde 403.
   */
  fun resolveUri(baseUrl: String, raw: String): String {
    val rawTrimmed = raw.trim()
    if (rawTrimmed.startsWith("http://", ignoreCase = true) ||
      rawTrimmed.startsWith("https://", ignoreCase = true)
    ) {
      return rawTrimmed
    }
    val base = baseUrl.toHttpUrlOrNull() ?: return rawTrimmed
    val resolved = base.resolve(rawTrimmed) ?: return rawTrimmed
    if (resolved.query == null && base.query != null) {
      return resolved.newBuilder().query(base.query).build().toString()
    }
    return resolved.toString()
  }

  private val RESOLUTION_REGEX = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
  private val BANDWIDTH_REGEX = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
  private val CODECS_REGEX = Regex("""CODECS="([^"]+)"""", RegexOption.IGNORE_CASE)
  private val MAP_URI_REGEX = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE)
}
