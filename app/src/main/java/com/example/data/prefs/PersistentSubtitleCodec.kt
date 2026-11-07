package com.example.data.prefs

import android.util.Log

/**
 * Codec JSON delle selezioni persistenti, separato dal DataStore per essere verificabile
 * in unit test JVM puri.
 *
 * Il formato è volutamente semplice e stabile perché è già presente sui dispositivi:
 *
 * ```json
 * {
 *   "tmdb:123": {"fileId":42,"lang":"it","label":"Italiano","localFilePath":"/data/.../a.srt","timestamp":1700000000000}
 * }
 * ```
 *
 * Il parser è tollerante: un record incompleto viene scartato senza invalidare gli altri,
 * così un dato corrotto non impedisce il ripristino delle selezioni valide.
 */
internal object PersistentSubtitleCodec {

  private const val TAG = "PersistentSubtitleCodec"

  fun encode(selections: Map<String, PersistentSubtitleSelection>): String {
    if (selections.isEmpty()) return "{}"
    val sb = StringBuilder("{")
    selections.entries.forEachIndexed { index, (key, value) ->
      if (index > 0) sb.append(",")
      sb.append('"').append(escape(key)).append("\":{")
      sb.append("\"fileId\":").append(value.fileId).append(',')
      sb.append("\"lang\":\"").append(escape(value.lang)).append("\",")
      sb.append("\"label\":\"").append(escape(value.label ?: "")).append("\",")
      sb.append("\"localFilePath\":\"").append(escape(value.localFilePath ?: "")).append("\",")
      sb.append("\"timestamp\":").append(value.timestamp)
      sb.append('}')
    }
    sb.append('}')
    return sb.toString()
  }

  fun decode(json: String?): Map<String, PersistentSubtitleSelection> {
    if (json.isNullOrBlank() || json == "{}") return emptyMap()
    return try {
      parse(json)
    } catch (e: Exception) {
      Log.w(TAG, "Errore parsing selezioni persistenti: ${e.message}")
      emptyMap()
    }
  }

  private fun parse(json: String): Map<String, PersistentSubtitleSelection> {
    val result = mutableMapOf<String, PersistentSubtitleSelection>()
    if (json.length < 4) return result

    var pos = 1 // salta la {
    while (pos < json.length) {
      if (json[pos] != '"') {
        pos++
        continue
      }
      val keyEnd = findClosingQuote(json, pos + 1)
      if (keyEnd < 0) break
      val key = unescape(json.substring(pos + 1, keyEnd))
      pos = keyEnd + 1

      while (pos < json.length && json[pos] != ':') pos++
      pos++

      if (pos >= json.length || json[pos] != '{') {
        pos++
        continue
      }
      val valueEnd = findMatchingBrace(json, pos)
      if (valueEnd < 0) break
      parseSelection(key, json.substring(pos, valueEnd + 1))?.let { result[key] = it }
      pos = valueEnd + 1
    }
    return result
  }

  private fun parseSelection(key: String, json: String): PersistentSubtitleSelection? {
    var fileId: Long? = null
    var lang: String? = null
    var label: String? = null
    var localFilePath: String? = null
    var timestamp: Long? = null

    var pos = 1
    while (pos < json.length) {
      if (json[pos] != '"') {
        pos++
        continue
      }
      val propEnd = findClosingQuote(json, pos + 1)
      if (propEnd < 0) break
      val prop = unescape(json.substring(pos + 1, propEnd))
      pos = propEnd + 1

      while (pos < json.length && json[pos] != ':') pos++
      pos++

      when (prop) {
        "fileId" -> {
          val end = findNumberEnd(json, pos)
          fileId = json.substring(pos, end).toLongOrNull()
          pos = end
        }
        "lang", "label", "localFilePath" -> {
          if (pos < json.length && json[pos] == '"') {
            val end = findClosingQuote(json, pos + 1)
            if (end < 0) break
            val value = unescape(json.substring(pos + 1, end))
            when (prop) {
              "lang" -> lang = value
              "label" -> label = value.ifEmpty { null }
              "localFilePath" -> localFilePath = value.ifEmpty { null }
            }
            pos = end + 1
          } else {
            pos++
          }
        }
        "timestamp" -> {
          val end = findNumberEnd(json, pos)
          timestamp = json.substring(pos, end).toLongOrNull()
          pos = end
        }
        else -> pos++
      }

      while (pos < json.length && json[pos] != ',' && json[pos] != '}') pos++
      if (pos >= json.length || json[pos] == '}') break
      pos++
    }

    if (fileId == null || lang == null || timestamp == null) return null
    return PersistentSubtitleSelection(
      contentId = key,
      fileId = fileId,
      lang = lang,
      label = label,
      localFilePath = localFilePath,
      timestamp = timestamp
    )
  }

  /** Indice della virgoletta di chiusura, saltando quelle precedute da backslash. */
  private fun findClosingQuote(json: String, start: Int): Int {
    var pos = start
    while (pos < json.length) {
      when (json[pos]) {
        '\\' -> pos += 2
        '"' -> return pos
        else -> pos++
      }
    }
    return -1
  }

  private fun findMatchingBrace(json: String, start: Int): Int {
    var depth = 1
    var pos = start + 1
    var inString = false
    while (pos < json.length && depth > 0) {
      val ch = json[pos]
      if (inString) {
        when (ch) {
          '\\' -> pos++
          '"' -> inString = false
        }
      } else {
        when (ch) {
          '"' -> inString = true
          '{' -> depth++
          '}' -> depth--
        }
      }
      pos++
    }
    return if (depth == 0) pos - 1 else -1
  }

  private fun findNumberEnd(json: String, start: Int): Int {
    var pos = start
    while (pos < json.length &&
      (json[pos].isDigit() || json[pos] == '-' || json[pos] == '.' ||
        json[pos] == 'E' || json[pos] == 'e')
    ) {
      pos++
    }
    return pos
  }

  internal fun escape(s: String): String = s
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

  internal fun unescape(s: String): String {
    if (!s.contains('\\')) return s
    val out = StringBuilder(s.length)
    var pos = 0
    while (pos < s.length) {
      val ch = s[pos]
      if (ch != '\\' || pos == s.length - 1) {
        out.append(ch)
        pos++
        continue
      }
      when (val next = s[pos + 1]) {
        '\\' -> out.append('\\')
        '"' -> out.append('"')
        'n' -> out.append('\n')
        'r' -> out.append('\r')
        't' -> out.append('\t')
        else -> {
          out.append('\\')
          out.append(next)
        }
      }
      pos += 2
    }
    return out.toString()
  }
}
