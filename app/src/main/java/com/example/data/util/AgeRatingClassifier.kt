package com.example.data.util

import com.example.data.api.TmdbCountryReleaseDatesDto
import com.example.data.api.TmdbMovieReleaseDatesResponseDto
import com.example.data.api.TmdbTvContentRatingItemDto
import com.example.data.api.TmdbTvContentRatingsResponseDto

/**
 * Classificatore e normalizzatore centralizzato per i rating d'età TMDB (Parental Control).
 *
 * Converte le certificazioni cinematografiche e televisive (italiane e internazionali)
 * nelle soglie standard di riferimento:
 * - 0: Per tutti / Generale (T, G, TV-Y, TV-G, PG, 6)
 * - 12: Da 12 anni in su (12, 12+, VM12, FSK 12, 12A, 10+)
 * - 14: Da 14 anni in su (14, 14+, VM14, TV-14, PG-13)
 * - 16: Da 16 anni in su (16, 16+, VM16, R, 15, FSK 16)
 * - 18: Da 18 anni in su (18, 18+, VM18, NC-17, TV-MA)
 *
 * Ritorna `null` se il rating non è determinabile o non affidabile (mai inventare).
 */
object AgeRatingClassifier {

  /**
   * Ordine deterministico dei paesi di fallback se non è presente la certificazione italiana "IT".
   * 1. US (massima copertura globale standardizzata TMDB)
   * 2. GB (BBFC Regno Unito)
   * 3. DE (FSK Germania)
   * 4. FR (Francia)
   * 5. ES (Spagna)
   */
  val FALLBACK_COUNTRIES = listOf("US", "GB", "DE", "FR", "ES")

  /**
   * Normalizza una stringa di certificazione grezza in una delle soglie standard (0, 12, 14, 16, 18).
   * Restituisce null se la certificazione è assente, vuota o sconosciuta.
   */
  fun normalizeCertification(raw: String?): Int? {
    if (raw.isNullOrBlank()) return null
    val clean = raw.trim().uppercase()

    return when {
      // 0: Per tutti / Generale
      clean in setOf("T", "G", "ALL", "PER TUTTI", "PT", "U", "TOUS PUBLICS", "TP", "APTA", "A", "0", "FSK 0", "TV-Y", "TV-Y7", "TV-G", "TV-PG") -> 0
      clean in setOf("6", "6+", "VM6", "VM 6", "+6", "FSK 6", "7", "7+", "TV-Y7-FV") -> 0
      clean.startsWith("PG") && clean != "PG-13" && clean != "PG13" -> 0

      // 12: Da 12 anni in su
      clean in setOf("12", "12+", "+12", "VM12", "VM 12", "BA12", "FSK 12", "12A", "10", "10+") -> 12

      // 14: Da 14 anni in su
      clean in setOf("14", "14+", "+14", "VM14", "VM 14", "BA14", "TV-14", "PG-13", "PG13") -> 14

      // 16: Da 16 anni in su
      clean in setOf("16", "16+", "+16", "VM16", "VM 16", "BA16", "FSK 16", "R", "15", "15+") -> 16

      // 18: Da 18 anni in su
      clean in setOf("18", "18+", "+18", "VM18", "VM 18", "BA18", "FSK 18", "NC-17", "NC17", "TV-MA", "X", "XXX", "R18", "ADULT") -> 18

      // Fallback con pattern contenenti età esplicite
      clean.contains("18") -> 18
      clean.contains("16") -> 16
      clean.contains("14") -> 14
      clean.contains("12") -> 12
      clean.contains("6") -> 0
      clean == "T" || clean.contains("TUTTI") -> 0

      else -> null
    }
  }

  /**
   * Estrae e normalizza il rating d'età per un Film dai risultati TMDB /movie/{id}/release_dates.
   *
   * Rispetta l'ordine di priorità:
   * 1. Certificazione italiana "IT"
   * 2. Fallback deterministico (US, GB, DE, FR, ES)
   * 3. Altri paesi disponibili se presenti
   * 4. null (se nessuna certificazione valida)
   */
  fun classifyMovieRating(response: TmdbMovieReleaseDatesResponseDto?): Int? {
    val results = response?.results ?: return null
    if (results.isEmpty()) return null

    val countryMap = results.associateBy { it.iso3166_1.uppercase() }

    // 1. Priorità mercato italiano
    val itDates = countryMap["IT"]
    val itRating = extractMovieRatingFromCountry(itDates)
    if (itRating != null) return itRating

    // 2. Fallback deterministico documentato
    for (countryCode in FALLBACK_COUNTRIES) {
      val fallbackDates = countryMap[countryCode]
      val fallbackRating = extractMovieRatingFromCountry(fallbackDates)
      if (fallbackRating != null) return fallbackRating
    }

    // 3. Fallback su qualsiasi altro paese con certificazione valida
    for (item in results) {
      val anyRating = extractMovieRatingFromCountry(item)
      if (anyRating != null) return anyRating
    }

    return null
  }

  private fun extractMovieRatingFromCountry(countryDto: TmdbCountryReleaseDatesDto?): Int? {
    if (countryDto == null) return null
    val dates = countryDto.releaseDates ?: return null
    for (releaseDate in dates) {
      val cert = releaseDate.certification?.trim()
      if (!cert.isNullOrBlank()) {
        val normalized = normalizeCertification(cert)
        if (normalized != null) return normalized
      }
    }
    return null
  }

  /**
   * Estrae e normalizza il rating d'età per una Serie TV dai risultati TMDB /tv/{id}/content_ratings.
   *
   * Rispetta l'ordine di priorità:
   * 1. Certificazione italiana "IT"
   * 2. Fallback deterministico (US, GB, DE, FR, ES)
   * 3. Altri paesi disponibili se presenti
   * 4. null (se nessuna certificazione valida)
   */
  fun classifyTvRating(response: TmdbTvContentRatingsResponseDto?): Int? {
    val results = response?.results ?: return null
    if (results.isEmpty()) return null

    val countryMap = results.associateBy { it.iso3166_1.uppercase() }

    // 1. Priorità mercato italiano
    val itRating = normalizeCertification(countryMap["IT"]?.rating)
    if (itRating != null) return itRating

    // 2. Fallback deterministico documentato
    for (countryCode in FALLBACK_COUNTRIES) {
      val fallbackRating = normalizeCertification(countryMap[countryCode]?.rating)
      if (fallbackRating != null) return fallbackRating
    }

    // 3. Fallback su qualsiasi altro paese con certificazione valida
    for (item in results) {
      val anyRating = normalizeCertification(item.rating)
      if (anyRating != null) return anyRating
    }

    return null
  }
}
