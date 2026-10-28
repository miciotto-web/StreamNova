package com.example.data.streaming.extractors

import android.util.Log
import com.example.data.streaming.StreamSource
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Estrattore per **Filemoon** (`filemoon.to`, `filemoon.sx`, `filemoon.in`).
 *
 * Il deployment attuale è una SPA "Byse": la pagina `/e/{code}` non contiene il
 * player, che viene caricato via API:
 *  1. `GET {origin}/api/videos/{code}` (Referer = pagina embed) restituisce
 *     `{"playback":{"algorithm":"AES-256-GCM","version":"N","key_parts":[...],
 *     "iv":"...","payload":"..."}}`;
 *  2. la chiave si compone selezionando due parti in base a `version`
 *     (`version` e `31-version`, indici 1-based) e concatenandole dopo il
 *     decode base64url; l'ordine della chiave reale è `part[N] || part[31-N]`
 *     (per `N >= 16` la coppia è quindi in ordine discendente);
 *  3. `payload` è cifrato AES-256-GCM con `iv` (12 byte) e tag in coda, come
 *     prodotto da WebCrypto;
 *  4. il JSON decifrato contiene `sources` con `mime_type` e `url` (HLS o MP4).
 *
 * `version` è un valore randomizzato (1..20) e può anche essere assente o
 * malformato: per questo la decifratura prova una lista determinista e limitata
 * di coppie candidate (vedi [candidateKeyPartPairs]) invece di fidarsi del solo
 * valore ricevuto. Un plaintext viene accettato solo se contiene un array
 * `sources` con almeno una sorgente valida.
 *
 * Se l'API non è disponibile si tenta il parsing legacy della pagina embed
 * (blocchi packed/JWPlayer). Errori di rete, 404 e payload non decifrabili
 * producono un messaggio esplicito: nessun bypass di challenge o captcha.
 *
 * Verificato il 2026-10-21 con una risposta reale dell'API: HLS
 * `application/vnd.apple.mpegurl` presente dopo la decifratura.
 */
class FilemoonExtractor : VideoExtractor {

  override suspend fun extract(embedUrl: String): List<StreamSource> = withContext(Dispatchers.IO) {
    val origin = originOf(embedUrl)
    val code = videoCode(embedUrl)
      ?: throw IOException("URL Filemoon senza codice video valido: $embedUrl")
    val failures = mutableListOf<String>()

    // --- 1) API del player (percorso attuale) ------------------------------
    try {
      val apiUrl = "$origin/api/videos/$code"
      Log.i(TAG, "GET API $apiUrl")
      val body = ExtractorHttp.get(apiUrl, referer = embedUrl)
      val playback = parsePlayback(body)
      if (playback == null) {
        failures += "API senza playback cifrato"
      } else {
        val decoded = decryptPlayback(playback)
        val media = pickMediaUrl(decoded)
        if (media != null) {
          Log.i(TAG, "Sorgente Filemoon estratta: $media")
          return@withContext listOf(source(media, embedUrl, origin))
        }
        failures += "payload decifrato senza sorgenti HLS/MP4"
      }
    } catch (ex: CancellationException) {
      throw ex
    } catch (ex: Exception) {
      failures += "API: ${ex.message}"
    }

    // --- 2) fallback legacy: sorgenti nel markup della pagina embed ---------
    try {
      val (finalUrl, html) = ExtractorHttp.getWithFinalUrl(embedUrl, referer = "$origin/")
      val media = PlayerSourceParser.findMediaUrls(html, finalUrl).firstOrNull()
      if (media != null) {
        Log.i(TAG, "Sorgente Filemoon legacy: $media")
        return@withContext listOf(source(media, embedUrl, originOf(finalUrl)))
      }
      failures += "pagina embed senza sorgenti"
    } catch (ex: CancellationException) {
      throw ex
    } catch (ex: Exception) {
      failures += "pagina: ${ex.message}"
    }

    throw IOException("Filemoon non risolvibile (${failures.joinToString(" | ")})")
  }

  private fun source(url: String, embedUrl: String, origin: String) = StreamSource(
    streamUrl = url,
    quality = "Auto",
    serverName = SERVER_NAME,
    headers = mapOf(
      "Referer" to embedUrl,
      "Origin" to origin,
      "User-Agent" to ExtractorHttp.USER_AGENT
    )
  )

  /** Codice video: ultimo segmento di `/e/{code}`, `/d/{code}` o `/{code}`. */
  internal fun videoCode(url: String): String? = try {
    val path = URI(url).path ?: return null
    val code = path.trim('/').substringAfterLast('/')
    code.takeIf { it.matches(CODE_REGEX) }
  } catch (e: Exception) {
    null
  }

  internal fun parsePlayback(json: String): PlaybackPayload? {
    val block = FilemoonPayloadJson.jsonObjectBody(json, "playback") ?: return null
    val version = rawVersion(block)
    val keyPartsBody = KEY_PARTS_REGEX.find(block)?.groupValues?.get(1) ?: return null
    val keyParts = PART_REGEX.findAll(keyPartsBody).map { it.groupValues[1] }.toList()
    if (keyParts.isEmpty()) return null
    val iv = IV_REGEX.find(block)?.groupValues?.get(1) ?: return null
    val payload = PAYLOAD_REGEX.find(block)?.groupValues?.get(1) ?: return null
    return PlaybackPayload(version, keyParts, iv, payload)
  }

  /**
   * Token grezzo del campo `version`: `null` solo se il campo è assente.
   *
   * Un token non numerico (per esempio `"abc"` o `null`) viene conservato
   * così da distinguere "versione assente" da "versione malformata": la
   * selezione della chiave ([selectKeyParts]) potrà comunque ricadere sul
   * fallback deterministico.
   */
  private fun rawVersion(block: String): String? {
    val match = VERSION_REGEX.find(block) ?: return null
    val quoted = match.groupValues.getOrNull(1).orEmpty()
    val bare = match.groupValues.getOrNull(2).orEmpty()
    return when {
      quoted.isNotEmpty() -> quoted
      bare.isNotEmpty() -> bare
      else -> null
    }
  }

  /**
   * Selezione delle parti della chiave, come nel player:
   * per `version = N` si usano le parti `N` e `31-N` (1-based). Se la versione
   * non è numerica, è fuori da `1..20` o punta fuori dall'array si usa
   * l'intero array (comportamento di fallback).
   */
  internal fun selectKeyParts(keyParts: List<String>, version: String?): List<String> {
    val n = version?.trim()?.toIntOrNull() ?: return keyParts
    if (n !in 1..MAX_VERSION) return keyParts
    val first = n
    val second = 31 - n
    if (first !in 1..keyParts.size || second !in 1..keyParts.size) return keyParts
    val selected = listOf(keyParts[first - 1], keyParts[second - 1])
    return if (selected.all { it.isNotBlank() }) selected else keyParts
  }

  /**
   * Coppie di parti della chiave da provare, in ordine deterministico:
   *  1. la coppia derivata dalla `version` (`N` e `31-N`, indici 1-based);
   *  2. tutte le coppie `(n, 31-n)` con `n` in `1..20` presenti nell'array;
   *  3. le coppie fra le parti che decodificano a 16 byte, in **entrambi** gli
   *     ordini: la chiave reale è `part[N] || part[31-N]`, quindi con `N >= 16`
   *     l'ordine corretto è discendente e il solo tentativo ascendente
   *     fallirebbe.
   *
   * L'elenco viene deduplicato e troncato a [MAX_CANDIDATES].
   */
  internal fun candidateKeyPartPairs(keyParts: List<String>, version: String?): List<List<String>> {
    val out = LinkedHashSet<List<String>>()
    val primary = selectKeyParts(keyParts, version)
    // La coppia "primaria" ha senso solo se è composta da due parti: senza
    // versione `selectKeyParts` restituisce l'intero array, uselesse come chiave.
    if (primary.size == 2 && primary.all { it.isNotBlank() }) out += primary
    // Il campo version è randomizzato: prova tutte le coppie plausibili.
    for (n in 1..MAX_VERSION) {
      val first = n
      val second = 31 - n
      if (first in 1..keyParts.size && second in 1..keyParts.size) {
        val pair = listOf(keyParts[first - 1], keyParts[second - 1])
        if (pair.all { it.isNotBlank() }) out += pair
      }
    }
    // Ultimo fallback: cerca esplicitamente le parti lunghe 16 byte (formato
    // osservato in produzione) provandole in entrambi gli ordini.
    val sixteenByte = keyParts.mapIndexedNotNull { idx, p ->
      val bytes = runCatching { decodeBase64Url(p) }.getOrNull()
      if (bytes?.size == 16) idx + 1 else null
    }
    for (a in sixteenByte) {
      for (b in sixteenByte) {
        if (a == b) continue
        out += listOf(keyParts[a - 1], keyParts[b - 1])
      }
    }
    return out.take(MAX_CANDIDATES)
  }

  /**
   * Decifra il payload AES-256-GCM e restituisce il JSON in chiaro.
   *
   * Il successo richiede un plaintext **strutturalmente valido**: deve
   * contenere un array `sources` con almeno una sorgente riconosciuta da
   * [pickMediaUrl]. Un plaintext parziale o corrotto non viene mai accettato.
   * Ogni candidato fallito (parte non base64url, lunghezza di chiave errata,
   * tag GCM non valido) viene isolato e l'esito finale è sempre una
   * [IOException], mai un'eccezione non controllata.
   */
  internal fun decryptPlayback(playback: PlaybackPayload): String {
    val iv = decodeBase64UrlOrThrow(playback.iv, "IV")
    if (iv.size != IV_SIZE) {
      throw IOException("IV Filemoon non valido (${iv.size} byte, attesi $IV_SIZE)")
    }
    val payload = decodeBase64UrlOrThrow(playback.payload, "payload")
    if (payload.size <= GCM_TAG_SIZE) {
      throw IOException(
        "payload Filemoon troppo breve (${payload.size} byte, " +
          "il tag GCM ne richiede almeno ${GCM_TAG_SIZE + 1})"
      )
    }
    val errors = mutableListOf<String>()
    var decryptedWithoutSources = false
    for (pair in candidateKeyPartPairs(playback.keyParts, playback.version)) {
      val key = try {
        pair.map(::decodeBase64Url).fold(ByteArray(0)) { acc, part -> acc + part }
      } catch (e: IllegalArgumentException) {
        errors += "chiave non in base64url: ${e.message}"
        continue
      }
      if (key.size !in VALID_KEY_SIZES) {
        errors += "lunghezza chiave non valida (${key.size} byte)"
        continue
      }
      try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val plain = String(cipher.doFinal(payload), StandardCharsets.UTF_8)
        if (pickMediaUrl(plain) != null) return plain
        decryptedWithoutSources = true
        errors += "payload decifrato ma senza array 'sources' valido"
      } catch (e: Exception) {
        errors += e.message.orEmpty()
      }
    }
    val prefix = if (decryptedWithoutSources) {
      "payload Filemoon decifrato ma senza 'sources' valide"
    } else {
      "payload Filemoon non decifrabile"
    }
    val detail = errors.distinct().take(3).joinToString(" | ").ifBlank { "nessuna chiave candidata valida" }
    throw IOException("$prefix: $detail")
  }

  /** Decodifica base64url trasformando gli errori in [IOException]. */
  private fun decodeBase64UrlOrThrow(value: String, field: String): ByteArray = try {
    decodeBase64Url(value)
  } catch (e: IllegalArgumentException) {
    throw IOException("$field Filemoon non valido: base64url non valido (${e.message})")
  }

  /**
   * Sorgente migliore dal JSON decifrato: HLS (`mpegurl` o `.m3u8` nel path)
   * prima, poi MP4. Null se il payload non contiene sorgenti riconoscibili.
   *
   * L'estensione viene cercata solo nel path: la query string dei token CDN
   * non deve influenzare la classificazione della sorgente.
   */
  internal fun pickMediaUrl(payloadJson: String): String? {
    val sourcesBody = FilemoonPayloadJson.jsonArrayBody(payloadJson, "sources") ?: return null
    val entries = FilemoonPayloadJson.splitJsonObjects(sourcesBody).mapNotNull { obj ->
      val url = URL_REGEX.find(obj)?.groupValues?.get(1)
        ?.let(FilemoonPayloadJson::unescapeJson)
        ?: return@mapNotNull null
      if (!url.startsWith("http", ignoreCase = true)) return@mapNotNull null
      obj to url
    }
    val hls = entries.firstOrNull { (obj, url) ->
      obj.contains("mpegurl", ignoreCase = true) || mediaPath(url).contains(".m3u8", ignoreCase = true)
    }
    val mp4 = entries.firstOrNull { (obj, url) ->
      obj.contains("video/mp4", ignoreCase = true) || mediaPath(url).contains(".mp4", ignoreCase = true)
    }
    return (hls ?: mp4)?.second
  }

  /** Path dell'URL senza query string e senza frammento. */
  private fun mediaPath(url: String): String = url.substringBefore('?').substringBefore('#')

  private fun originOf(url: String): String = try {
    val uri = URI(url)
    val scheme = uri.scheme
    val host = uri.host
    if (scheme.isNullOrBlank() || host.isNullOrBlank()) {
      ""
    } else {
      val port = uri.port
      val default = (scheme.equals("http", true) && port == 80) ||
        (scheme.equals("https", true) && port == 443)
      if (port <= 0 || default) "$scheme://$host" else "$scheme://$host:$port"
    }
  } catch (e: Exception) {
    ""
  }

  /** Decoder base64url senza dipendenze Android (funziona anche nei test JVM). */
  internal fun decodeBase64Url(value: String): ByteArray {
    val out = ByteArray(value.length * 3 / 4 + 3)
    var buffer = 0
    var bits = 0
    var index = 0
    for (c in value) {
      val v = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '+', '-' -> 62
        '/', '_' -> 63
        '=' -> continue
        else -> throw IllegalArgumentException("carattere base64url non valido: $c")
      }
      buffer = (buffer shl 6) or v
      bits += 6
      if (bits >= 8) {
        bits -= 8
        out[index++] = ((buffer shr bits) and 0xFF).toByte()
      }
    }
    return out.copyOf(index)
  }

  companion object {
    private const val TAG = "FilemoonExtractor"
    const val SERVER_NAME = "Filemoon"

    /** Versioni/candidate gestite dal player: la coppia reale è `n` e `31-n`. */
    private const val MAX_VERSION = 20
    /** Limite duro delle coppie di parti provate in decifratura. */
    private const val MAX_CANDIDATES = 64
    /** Lunghezza dell'IV AES-GCM (12 byte). */
    private const val IV_SIZE = 12
    /** Lunghezza del tag GCM in coda al payload cifrato (16 byte). */
    private const val GCM_TAG_SIZE = 16
    /** Lunghezze di chiave AES valide (128/192/256 bit). */
    private val VALID_KEY_SIZES = setOf(16, 24, 32)

    private val CODE_REGEX = Regex("""[A-Za-z0-9]{4,}""")
    // Cattura il token grezzo: "11", 11, "abc" e null sono tutti riconosciuti,
    // mentre un campo `version` assente non produce alcun match.
    private val VERSION_REGEX = Regex(""""version"\s*:\s*(?:"([^"]*)"|([^"\s,}]+))""")
    private val KEY_PARTS_REGEX = Regex(""""key_parts"\s*:\s*\[([^\]]*)\]""")
    private val PART_REGEX = Regex(""""([A-Za-z0-9_\-=]+)"""")
    private val IV_REGEX = Regex(""""iv"\s*:\s*"([^"]+)"""")
    private val PAYLOAD_REGEX = Regex(""""payload"\s*:\s*"([^"]+)"""")
    private val URL_REGEX = Regex(""""url"\s*:\s*"([^"]+)"""")
  }
}

/** Parametri del payload cifrato restituito dall'API Filemoon. */
internal data class PlaybackPayload(
  val version: String?,
  val keyParts: List<String>,
  val iv: String,
  val payload: String
)
