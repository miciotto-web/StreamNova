package com.example.data.streaming.extractors

/**
 * Helper JSON minimali usati dall'estrattore Filemoon.
 *
 * Sono in un file dedicato per tenere [FilemoonExtractor] sotto il limite di
 * 400 LOC previsto dal progetto. Il comportamento è identico a quello
 * dell'implementazione originale: parsing tollerante (nessuna libreria JSON)
 * e decodifica manuale degli escape `\uXXXX`.
 */
internal object FilemoonPayloadJson {

  /**
   * Decodifica gli escape JSON di una stringa (`\u0026`, `\/`, ...).
   *
   * Il payload Filemoon contiene gli `&` dei token CDN come `\u0026`: senza
   * questa decodifica l'URL firmato diventa invalido e il CDN risponde 404.
   */
  fun unescapeJson(value: String): String {
    if (!value.contains('\\')) return value
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
      val c = value[i]
      if (c != '\\' || i == value.length - 1) {
        out.append(c)
        i++
        continue
      }
      when (val escaped = value[i + 1]) {
        '"', '\\', '/' -> out.append(escaped)
        'b' -> out.append('\b')
        'f' -> out.append('\u000C')
        'n' -> out.append('\n')
        'r' -> out.append('\r')
        't' -> out.append('\t')
        'u' -> {
          val hex = value.substring((i + 2).coerceAtMost(value.length), (i + 6).coerceAtMost(value.length))
          val code = hex.toIntOrNull(16)
          if (hex.length == 4 && code != null) {
            out.append(code.toChar())
            i += 6
            continue
          }
          out.append(escaped)
        }
        else -> out.append(escaped)
      }
      i += 2
    }
    return out.toString()
  }

  /** Corpo dell'oggetto JSON associato a [key] (graffe bilanciate, stringhe escluse). */
  fun jsonObjectBody(body: String, key: String): String? {
    val keyAt = body.indexOf("\"$key\"")
    if (keyAt < 0) return null
    val open = body.indexOf('{', keyAt)
    if (open < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (i in open until body.length) {
      val c = body[i]
      when {
        escaped -> escaped = false
        c == '\\' -> escaped = true
        c == '"' -> inString = !inString
        inString -> {}
        c == '{' -> depth++
        c == '}' -> {
          depth--
          if (depth == 0) return body.substring(open, i + 1)
        }
      }
    }
    return null
  }

  /** Contenuto dell'array JSON associato a [key]. */
  fun jsonArrayBody(body: String, key: String): String? {
    val keyAt = body.indexOf("\"$key\"")
    if (keyAt < 0) return null
    val open = body.indexOf('[', keyAt)
    if (open < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (i in open until body.length) {
      val c = body[i]
      when {
        escaped -> escaped = false
        c == '\\' -> escaped = true
        c == '"' -> inString = !inString
        inString -> {}
        c == '[' -> depth++
        c == ']' -> {
          depth--
          if (depth == 0) return body.substring(open + 1, i)
        }
      }
    }
    return null
  }

  /** Oggetti JSON di primo livello dentro un array. */
  fun splitJsonObjects(arrayBody: String): List<String> {
    val out = mutableListOf<String>()
    var depth = 0
    var start = -1
    var inString = false
    var escaped = false
    for (i in arrayBody.indices) {
      val c = arrayBody[i]
      when {
        escaped -> escaped = false
        c == '\\' -> escaped = true
        c == '"' -> inString = !inString
        inString -> {}
        c == '{' -> {
          if (depth == 0) start = i
          depth++
        }
        c == '}' -> {
          depth--
          if (depth == 0 && start >= 0) {
            out += arrayBody.substring(start, i + 1)
            start = -1
          }
        }
      }
    }
    return out
  }
}
