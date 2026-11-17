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
    val args = parseArgs(block) ?: return null
    val payload = unescapeJsString(args.payload)
    val radix = args.radix
    val count = args.count
    val wordsRaw = unescapeJsString(args.dictionary)
    val delimiter = unescapeJsString(args.delimiter)
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

  private data class PackedArgs(
    val payload: String,
    val radix: Int,
    val count: Int,
    val dictionary: String,
    val delimiter: String
  )

  /**
   * Parser **lineare** degli argomenti del packer:
   * `}('payload', radix, count, 'dictionary'.split('|'), 0, {})`.
   *
   * Non usa regex ricorsive: i payload reali (Uqload ~20 KB) facevano andare
   * in `StackOverflowError` il vecchio `(?:[^'\\]|\\.)*`. Il parser legge le
   * stringhe quotate carattere per carattere, rispettando gli escape.
   */
  private fun parseArgs(block: String): PackedArgs? {
    // Gli argomenti seguono il corpo della funzione: l'ultimo `}` seguito da
    // `(` (tollerando whitespace). Il `{}` finale degli argomenti è seguito da
    // `)`, quindi viene saltato.
    var brace = block.lastIndexOf('}')
    var open = -1
    while (brace >= 0) {
      var next = brace + 1
      while (next < block.length && block[next].isWhitespace()) next++
      if (next < block.length && block[next] == '(') {
        open = next
        break
      }
      brace = if (brace == 0) -1 else block.lastIndexOf('}', brace - 1)
    }
    if (open < 0) return null
    var index = open + 1

    index = skipWhitespace(block, index)
    val payload = readQuoted(block, index) ?: return null
    index = skipWhitespace(block, payload.second)
    if (block.getOrNull(index) != ',') return null
    index = skipWhitespace(block, index + 1)

    val radix = readInt(block, index) ?: return null
    index = skipWhitespace(block, radix.second)
    if (block.getOrNull(index) != ',') return null
    index = skipWhitespace(block, index + 1)

    val count = readInt(block, index) ?: return null
    index = skipWhitespace(block, count.second)
    if (block.getOrNull(index) != ',') return null
    index = skipWhitespace(block, index + 1)

    val dictionary = readQuoted(block, index) ?: return null
    index = skipWhitespace(block, dictionary.second)
    if (!block.startsWith(".split", index)) return null
    index = skipWhitespace(block, index + ".split".length)
    if (block.getOrNull(index) != '(') return null
    index = skipWhitespace(block, index + 1)

    val delimiter = readQuoted(block, index) ?: return null
    return PackedArgs(
      payload = payload.first,
      radix = radix.first,
      count = count.first,
      dictionary = dictionary.first,
      delimiter = delimiter.first
    )
  }

  private fun skipWhitespace(source: String, start: Int): Int {
    var index = start
    while (index < source.length && source[index].isWhitespace()) index++
    return index
  }

  /** Legge una stringa JS tra apici singoli o doppi, preservando gli escape. */
  private fun readQuoted(source: String, start: Int): Pair<String, Int>? {
    if (start >= source.length) return null
    val quote = source[start]
    if (quote != '\'' && quote != '"') return null
    val out = StringBuilder()
    var index = start + 1
    while (index < source.length) {
      val c = source[index]
      when {
        c == '\\' && index + 1 < source.length -> {
          out.append(c)
          out.append(source[index + 1])
          index += 2
        }
        c == quote -> return out.toString() to (index + 1)
        else -> {
          out.append(c)
          index++
        }
      }
    }
    return null
  }

  private fun readInt(source: String, start: Int): Pair<Int, Int>? {
    var index = skipWhitespace(source, start)
    val begin = index
    while (index < source.length && source[index].isDigit()) index++
    if (index == begin) return null
    val value = source.substring(begin, index).toIntOrNull() ?: return null
    return value to index
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

  private val TOKEN_REGEX = Regex("""\b[0-9A-Za-z_]+\b""")
}
