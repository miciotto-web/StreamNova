package com.example.data.search

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * Codec delle ricerche recenti (Nova Discovery).
 *
 * Lista di stringhe, più recente in testa, deduplicata senza distinzione di
 * maiuscole. Un JSON corrotto degrada a lista vuota: la ricerca non deve mai
 * fallire per un suggerimento.
 */
object RecentSearchesCodec {

  /** Numero massimo di ricerche conservate. */
  const val MAX_ENTRIES = 8

  private val adapter = Moshi.Builder().build().adapter<List<String>>(
    Types.newParameterizedType(List::class.java, String::class.java)
  )

  fun encode(queries: List<String>): String = try {
    adapter.toJson(sanitize(queries))
  } catch (e: Exception) {
    "[]"
  }

  fun decode(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
      sanitize(adapter.fromJson(json).orEmpty())
    } catch (e: Exception) {
      emptyList()
    }
  }

  /** Inserisce una query in testa, deduplica (case-insensitive) e limita la lista. */
  fun insert(existing: List<String>, query: String): List<String> {
    val clean = query.trim()
    if (clean.length < 2) return sanitize(existing)
    val previous = existing.filterNot { it.equals(clean, ignoreCase = true) }
    return sanitize(listOf(clean) + previous)
  }

  private fun sanitize(queries: List<String>): List<String> {
    val seen = HashSet<String>()
    return queries
      .map { it.trim() }
      .filter { it.length >= 2 }
      .filter { seen.add(it.lowercase()) }
      .take(MAX_ENTRIES)
  }
}
