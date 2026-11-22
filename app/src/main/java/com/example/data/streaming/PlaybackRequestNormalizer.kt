package com.example.data.streaming

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Richiesta di riproduzione normalizzata: URL riproducibile + header da
 * applicare alle richieste HTTP del player.
 */
data class NormalizedPlaybackRequest(
  val url: String?,
  val headers: Map<String, String>
)

/**
 * Normalizza la richiesta di riproduzione di uno stream HTTP di un addon
 * Stremio (livello comune: nessuna logica dedicata a singoli addon).
 *
 * Cosa fa (l'unica trasformazione ammessa):
 *  - **userinfo → Authorization Basic**. Alcuni addon incorporano le credenziali
 *    direttamente nell'URL dello stream nella forma `https://user:pass@host/...`
 *    (es. Easynews/EasyStream: `utils.ts` → `downURL.replace('://',
 *    '://user:pass@')`, SENZA alcun `behaviorHints.proxyHeaders`). È la
 *    convenzione dei link di download dei browser: l'userinfo va convertito in
 *    header `Authorization: Basic`. OkHttp lo PARSE ma non lo invia mai, quindi
 *    la richiesta parte senza credenziali e il CDN rifiuta (il 502 di Easynews
 *    è un fallimento di autenticazione mascherato, issue note degli addon
 *    Easynews su client Android). La conversione allinea StreamNova a browser,
 *    Stremio e NuvioTV (che la implementa in `extractUserInfoAuth`).
 *  - **pulizia dell'URL**: l'userinfo rimosso NON altera path, query, token o
 *    fragment.
 *
 * Cosa NON fa:
 *  - non tocca gli URL senza userinfo (byte-per-byte invariati);
 *  - non sovrascrive un `Authorization` già dichiarato dall'addon in
 *    `proxyHeaders.request` (quel valore è quello corretto);
 *  - non inventa credenziali: userinfo assente o URL malformato → richiesta
 *    restituita identica.
 */
object PlaybackRequestNormalizer {

  /** Nome header di autorizzazione, confrontato case-insensitive. */
  private const val AUTHORIZATION = "Authorization"

  fun normalize(url: String?, headers: Map<String, String>): NormalizedPlaybackRequest {
    val rawUrl = url ?: return NormalizedPlaybackRequest(url = null, headers = headers)
    val credentials = extractUserinfoCredentials(rawUrl)
      ?: return NormalizedPlaybackRequest(url = rawUrl, headers = headers)
    val cleanUrl = stripUserinfo(rawUrl)
      ?: return NormalizedPlaybackRequest(url = rawUrl, headers = headers)

    // Un Authorization dichiarato dall'addon ha la precedenza: solo l'URL viene
    // pulito (l'userinfo in un URL con header esplicito è comunque inutile e
    // alcuni server lo rifiutano).
    if (headers.entries.any { it.key.equals(AUTHORIZATION, ignoreCase = true) }) {
      return NormalizedPlaybackRequest(url = cleanUrl, headers = headers)
    }

    val merged = LinkedHashMap<String, String>(headers.size + 1)
    merged.putAll(headers)
    merged[AUTHORIZATION] = basicAuthHeader(credentials)
    return NormalizedPlaybackRequest(url = cleanUrl, headers = merged)
  }

  /**
   * Credenziali `user:pass` dal componente userinfo dell'URL, decodificate.
   * `null` se l'URL non ha userinfo o non è analizzabile: nessun valore inventato.
   */
  internal fun extractUserinfoCredentials(url: String?): String? {
    if (url.isNullOrBlank()) return null
    if (!url.startsWith("http://", ignoreCase = true) &&
      !url.startsWith("https://", ignoreCase = true)
    ) {
      return null
    }
    val rawAuthority = rawAuthorityOf(url) ?: return null
    val rawUserinfo = rawAuthority.substringBeforeLast('@', missingDelimiterValue = "")
      .takeIf { it.isNotBlank() && it != rawAuthority }
      ?: return null
    return decodeUserinfo(rawUserinfo)
  }

  /**
   * URL senza il componente userinfo (schema://authority@ → schema://authority).
   * Path, query e fragment restano byte-per-byte. `null` se la pulizia non è
   * possibile con sicurezza (il chiamante conserva l'URL originale).
   */
  internal fun stripUserinfo(url: String): String? {
    val uri = try {
      URI(url)
    } catch (e: Exception) {
      return null
    }
    val scheme = uri.scheme?.takeIf { it.isNotBlank() } ?: return null
    val rawAuthority = uri.rawAuthority ?: return null
    val cleanAuthority = rawAuthority.substringAfterLast('@', missingDelimiterValue = "")
      .takeIf { it.isNotBlank() && it != rawAuthority }
      ?: return null
    return buildString {
      append(scheme)
      append("://")
      append(cleanAuthority)
      append(uri.rawPath.orEmpty())
      uri.rawQuery?.let { append('?').append(it) }
      uri.rawFragment?.let { append('#').append(it) }
    }
  }

  /**
   * Header Basic secondo RFC 7617: `Basic base64(user:pass)`.
   * Un userinfo senza `:` (solo username) viene trattato come username con
   * password vuota (`user:`), come da convenzione HTTP.
   */
  internal fun basicAuthHeader(userinfo: String): String {
    val withPassword = if (userinfo.contains(':')) userinfo else "$userinfo:"
    val encoded = Base64.getEncoder()
      .encodeToString(withPassword.toByteArray(StandardCharsets.UTF_8))
    return "Basic $encoded"
  }

  /** Authority RAW dell'URL (userinfo + host + port), senza riscritture. */
  private fun rawAuthorityOf(url: String): String? = try {
    URI(url).rawAuthority
  } catch (e: Exception) {
    null
  }

  /**
   * Decodifica percentuale del userinfo grezzo. In un userinfo `+` è un
   * carattere letterale (diverso dalla query): viene protetto prima della
   * decodifica, come fa `URLDecoder` per i casi `%2B`.
   */
  private fun decodeUserinfo(raw: String): String = try {
    URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8)
  } catch (e: Exception) {
    raw
  }
}
