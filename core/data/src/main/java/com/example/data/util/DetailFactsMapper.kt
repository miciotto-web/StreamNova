package com.example.data.util

import com.example.data.api.TmdbCountryReleaseDatesDto
import com.example.data.api.TmdbMovieReleaseDatesResponseDto
import com.example.data.api.TmdbProductionCountryDto
import com.example.data.api.TmdbTvContentRatingsResponseDto
import java.util.Locale

/**
 * Dati di «Dettagli Film» ricavati dai metadati TMDB, senza valori di comodo.
 *
 * Ogni funzione restituisce `null` quando il dato è assente, vuoto o non valido: la
 * schermata Dettagli usa `null` per nascondere la singola riga (mai righe vuote o
 * segnaposto). Le funzioni sono pure e verificabili con test JVM.
 */
object DetailFactsMapper {

  /** Stato editoriale di un titolo TMDB (i valori esposti dall'API sono in inglese). */
  enum class TitleStatus {
    RELEASED,
    POST_PRODUCTION,
    IN_PRODUCTION,
    PLANNED,
    CANCELED,
    RUMORED,
    RETURNING_SERIES,
    ENDED,
    PILOT
  }

  /**
   * Normalizza lo `status` TMDB in una costante localizzabile.
   * `null` per stato assente o non riconosciuto: la riga «Stato» non viene mostrata.
   */
  fun status(raw: String?): TitleStatus? =
    when (raw?.trim()?.lowercase(Locale.ROOT)) {
      "released" -> TitleStatus.RELEASED
      "post production" -> TitleStatus.POST_PRODUCTION
      "in production" -> TitleStatus.IN_PRODUCTION
      "planned" -> TitleStatus.PLANNED
      "canceled", "cancelled" -> TitleStatus.CANCELED
      "rumored" -> TitleStatus.RUMORED
      "returning series" -> TitleStatus.RETURNING_SERIES
      "ended" -> TitleStatus.ENDED
      "pilot" -> TitleStatus.PILOT
      else -> null
    }

  /**
   * Durata leggibile (`2h 13m`, `45m`) da minuti TMDB.
   * `null` se il valore manca o non è positivo: nessuna durata inventata.
   */
  fun formatDuration(minutes: Int?): String? {
    val total = minutes?.takeIf { it > 0 } ?: return null
    val hours = total / 60
    val mins = total % 60
    return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
  }

  /**
   * Anno da una data ISO completa e valida (`yyyy-MM-dd`). Una stringa con il solo
   * anno o una data impossibile restituisce `null`, coerente con il resto del data
   * layer ([ReleaseDateUtils]).
   */
  fun releaseYear(isoDate: String?): Int? =
    ReleaseDateUtils.normalizeIsoDate(isoDate)?.take(4)?.toIntOrNull()

  /**
   * Certificazione cinematografica grezza (es. `PG-13`, `T`, `VM14`) da
   * `release_dates`, con la stessa priorità del classificatore d'età:
   * IT, poi US/GB/DE/FR/ES, poi gli altri paesi nell'ordine TMDB.
   *
   * All'interno del paese si preferisce la certificazione teatrale (type 3) e si
   * scartano i valori non normalizzabili (es. `NR`). `null` se nessuna è valida.
   */
  fun movieCertification(response: TmdbMovieReleaseDatesResponseDto?): String? {
    val results = response?.results.orEmpty()
    return orderedCountries(results).firstNotNullOfOrNull { country ->
      movieCertificationForCountry(country)
    }
  }

  /**
   * Certificazione televisiva grezza (`TV-14`, `T`, …) da `content_ratings`, con la
   * stessa priorità dei paesi usata per i film. `null` se nessuna è valida.
   */
  fun tvCertification(response: TmdbTvContentRatingsResponseDto?): String? {
    val results = response?.results.orEmpty()
    val ordered = orderedIsoCodes(results.map { it.iso3166_1 })
      .mapNotNull { code -> results.firstOrNull { it.iso3166_1.equals(code, ignoreCase = true) } }
    return ordered.firstNotNullOfOrNull { item ->
      item.rating?.trim()?.takeIf { it.isNotEmpty() && AgeRatingClassifier.normalizeCertification(it) != null }
    }
  }

  /**
   * Nome leggibile del paese d'origine. La fonte primaria è `production_countries`
   * (nome già pronto, es. `United States of America`); il primo codice di
   * `origin_country` serve solo ad abbinare il paese corretto o, se il nome non è
   * disponibile, a ricavarlo dal codice ISO.
   *
   * `null` se TMDB non espone nulla di utilizzabile: nessun paese inventato.
   */
  fun originCountry(
    productionCountries: List<TmdbProductionCountryDto>?,
    originCountryCodes: List<String>?
  ): String? {
    val countries = productionCountries.orEmpty()
    val namesByIso = countries.mapNotNull { country ->
      val code = country.iso3166_1?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        ?: return@mapNotNull null
      val name = country.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
      code to name
    }.toMap()

    val firstOriginCode = originCountryCodes.orEmpty()
      .asSequence()
      .map { it.trim().uppercase(Locale.ROOT) }
      .firstOrNull { it.isNotEmpty() }

    if (firstOriginCode != null) {
      namesByIso[firstOriginCode]?.let { return it }
      displayCountryName(firstOriginCode)?.let { return it }
    }

    return countries.firstNotNullOfOrNull { it.name?.trim()?.takeIf(String::isNotEmpty) }
  }

  private fun movieCertificationForCountry(country: TmdbCountryReleaseDatesDto): String? {
    val dates = country.releaseDates.orEmpty()
    // La certificazione teatrale rappresenta la classificazione "ufficiale"; le altre
    // (home video, TV) restano fallback nell'ordine restituito da TMDB.
    val ordered = dates.filter { it.type == 3 } + dates.filter { it.type != 3 }
    return ordered.firstNotNullOfOrNull { item ->
      item.certification?.trim()?.takeIf { it.isNotEmpty() && AgeRatingClassifier.normalizeCertification(it) != null }
    }
  }

  private fun orderedCountries(results: List<TmdbCountryReleaseDatesDto>): List<TmdbCountryReleaseDatesDto> {
    val byIso = results.associateBy { it.iso3166_1.trim().uppercase(Locale.ROOT) }
    return orderIsoCodes(byIso.keys).mapNotNull { byIso[it] }.distinct()
  }

  private fun orderedIsoCodes(available: List<String>): List<String> {
    val codes = available.map { it.trim().uppercase(Locale.ROOT) }.filter { it.isNotEmpty() }
    return orderIsoCodes(codes).distinct()
  }

  private fun orderIsoCodes(available: Collection<String>): List<String> {
    val ordered = buildList {
      if ("IT" in available) add("IT")
      AgeRatingClassifier.FALLBACK_COUNTRIES.forEach { code -> if (code in available) add(code) }
      addAll(available)
    }
    return ordered.distinct()
  }

  /**
   * Nome inglese stabile dal codice ISO. Usato solo come fallback quando TMDB non
   * include `production_countries`: un codice sconosciuto resta `null` (mai una
   * sigla mostrata come nome).
   */
  private fun displayCountryName(isoCode: String): String? =
    runCatching {
      Locale.Builder().setRegion(isoCode).build().getDisplayCountry(Locale.ENGLISH)
    }.getOrNull()
      ?.trim()
      ?.takeIf { it.isNotEmpty() && !it.equals(isoCode, ignoreCase = true) }
}
