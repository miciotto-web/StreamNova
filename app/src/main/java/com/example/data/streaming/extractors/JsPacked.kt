package com.example.data.streaming.extractors

/**
 * Decodificatore dei blocchi JavaScript **packed** (formato Dean Edwards) usati
 * dai player di alcuni hoster (VidHide, StreamWish, vecchi embed Filemoon).
 *
 * Forma tipica:
 * ```
 * eval(function(p,a,c,k,e,d){ ... }('payload',62,123,'w1|w2|w3'.split('|'),0,{}))
 * ```
 * Il decoder sostituisce i token numerici del payload con le parole del
 * dizionario. Non esegue JavaScript: analizza solo il testo.
 *
 * È volutamente tollerante: se un blocco non è riconosciuto viene ignorato e
 * il chiamante può proseguire con le altre euristiche di parsing.
 */
internal object JsPacked {

  /** Intestazione del packer, tollerante a spazi e al nome dell'ultimo parametro. */
  private val MARKER_REGEX = Regex(
    """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[a-z]\s*\)"""
  )

  /** Estrae e decodifica tutti i blocchi packed presenti in [source]. */
  fun unpackAll(source: String, limit: Int = 8): List<String> {
    val out = mutableListOf<String>()
    var from = 0
    while (out.size < limit) {
      val marker = MARKER_REGEX.find(source, from) ?: break
      val open = source.indexOf('(', marker.range.first)
      if (open < 0) break
      val end = matchingParen(source, open)
      if (end < 0) {
        from = marker.range.last + 1
        continue
      }
      unpack(source.substring(marker.range.first, end + 1))?.let { out += it }
      from = end + 1
    }
    return out
  }

  /** Decodifica un singolo blocco packed; null se il formato non è riconosciuto. */
  fun unpack(block: String): String? {
    val match = ARGS_REGEX.find(block) ?: return null
    val payload = unescapeJsString(match.groupValues[1])
    val radix = match.groupValues[2].toIntOrNull() ?: return null
    val count = match.groupValues[3].toIntOrNull() ?: return null
    val wordsRaw = unescapeJsString(match.groupValues[4])
    val delimiter = unescapeJsString(match.groupValues[5])
    if (radix < 2 || radix > 36 || count <= 0 || delimiter.isEmpty()) return null

    val words = wordsRaw.split(delimiter)
    if (words.isEmpty()) return null

    // Le due varianti del packer usano token con e senza padding a 3 cifre.
    val map = HashMap<String, String>(count)
    for (i in 0 until count) {
      val raw = words.getOrNull(i) ?: continue
      val key = i.toString(radix)
      val value = raw.ifEmpty { key }
      map[key] = value
      if (key.length < 3) map["0".repeat(3 - key.length) + key] = value
    }

    return TOKEN_REGEX.replace(payload) { hit -> map[hit.value] ?: hit.value }
  }

  /** Indice della parentesi di chiusura bilanciata, rispettando stringhe ed escape. */
  private fun matchingParen(source: String, openIndex: Int): Int {
    var depth = 0
    var quote: Char? = null
    var escaped = false
    for (i in openIndex until source.length) {
      val c = source[i]
      when {
        escaped -> escaped = false
        c == '\\' && quote != null -> escaped = true
        quote != null -> if (c == quote) quote = null
        c == '\'' || c == '"' -> quote = c
        c == '(' -> depth++
        c == ')' -> {
          depth--
          if (depth == 0) return i
        }
      }
    }
    return -1
  }

  private fun unescapeJsString(value: String): String =
    value.replace("\\'", "'")
      .replace("\\\"", "\"")
      .replace("\\\\", "\\")
      .replace("\\/", "/")

  private val ARGS_REGEX = Regex(
    """\}\s*\(\s*'((?:[^'\\]|\\.)*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:[^'\\]|\\.)*)'\s*\.split\(\s*['"]([^'"]+)['"]\s*\)"""
  )

  private val TOKEN_REGEX = Regex("""\b[0-9A-Za-z_]+\b""")
}
