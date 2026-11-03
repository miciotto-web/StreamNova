package com.aistudio.streamnova.mobile.ui

/**
 * Classificazione HLS/progressiva della sorgente del player Mobile.
 *
 * Replica la semantica di `computeIsHlsStream` della TV: le sorgenti HLS non
 * sempre hanno `.m3u8` nell'URL. VixSrc, ad esempio, serve il manifest da
 * `/playlist/<id>` senza estensione (risposta `application/vnd.apple.mpegurl`);
 * con il solo test sull'estensione il player costruirebbe una sorgente
 * progressiva e fallirebbe con `UnrecognizedInputFormatException`
 * ("Source error" a schermo).
 *
 * Regole, nello stesso ordine della TV:
 *  1. `.m3u8` nel path o nell'URL → HLS;
 *  2. estensioni progressive (.mp4/.mkv/.webm/.avi e `.mp4`/`.mkv`/`.m4v`
 *     anche solo in query string) → progressiva;
 *  3. hint della sorgente ([com.example.data.streaming.StreamSource.isProgressive]):
 *     i link Debrid sbloccati sono file progressivi anche senza estensione;
 *  4. URL senza estensione né hint (endpoint HLS dei provider) → HLS.
 */
object MobilePlaybackSourcePolicy {

  fun isHlsStream(url: String, progressiveHint: Boolean?): Boolean {
    val trimmed = url.trim()
    val lower = trimmed.lowercase()
    val path = lower.substringBefore('?').substringBefore('#')
    return when {
      path.endsWith(".m3u8") || lower.contains(".m3u8") -> true

      path.endsWith(".mp4") || path.endsWith(".mkv") ||
        path.endsWith(".webm") || path.endsWith(".avi") ||
        lower.contains(".mp4") || lower.contains(".mkv") || lower.contains(".m4v") -> false

      progressiveHint != null -> !progressiveHint

      else -> trimmed.isNotBlank()
    }
  }
}
