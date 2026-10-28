package com.example.data.repository

import com.example.data.api.TmdbApiClient
import com.example.data.api.TmdbMovieDetailDto
import com.example.data.api.TmdbTvDetailDto
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.prefs.AppSettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Sinossi localizzata del titolo mostrato nell'Hero Banner della Home.
 *
 * Perché serve: le righe della Home arrivano dai cataloghi Stremio (per default
 * Cinemeta), che restituiscono `description` sempre in inglese. Cinemeta non
 * espone alcun parametro di lingua: `?lang=` / `?language=` rispondono HTTP 404
 * e `Accept-Language` viene ignorato. La sinossi dell'Hero va quindi chiesta a
 * TMDB nella lingua dell'app, senza toccare grafica, poster, titoli, ordine o
 * navigazione: l'item resta quello del catalogo di provenienza e si sostituisce
 * solo il testo della sinossi.
 *
 * Regole:
 *  - MAI una sinossi vuota: se TMDB non fornisce un `overview` localizzato si usa
 *    l'overview "originale" già presente sull'item (inglese), poi la traduzione
 *    italiana esplicita, poi quella inglese.
 *  - Nessuna traduzione automatica: solo testi già presenti in TMDB.
 *  - Il testo italiano viene preferito solo se è davvero in italiano, così
 *    un'`overview` inglese restituita da TMDB come fallback non viene
 *    spacciata per italiana.
 */
object HeroSynopsisLocalizer {

  /** Lingua metadati dell'app, nel formato ISO regionale atteso da TMDB. */
  internal fun language(): String = AppSettingsRepository.tmdbLanguage()

  /** Codice lingua breve ("it", "en", ...) usato per scegliere la traduzione. */
  internal fun languageCode(language: String = language()): String =
    language.trim().substringBefore('-').substringBefore('_').lowercase()

  /**
   * Estrae l'id TMDB dai formati usati dai cataloghi Stremio/TMDB
   * (`tmdbId`, `tmdb:603`, `603`, `tmdb_m_603`, `tmdb_tv_603`).
   */
  internal fun tmdbIdOf(item: MediaItem): Int? {
    item.tmdbId?.takeIf { it > 0 }?.let { return it }
    val id = item.id.trim()
    if (id.isEmpty()) return null
    return when {
      id.startsWith("tmdb:", ignoreCase = true) -> id.substringAfter(":").trim().toIntOrNull()
      id.startsWith("tmdb_m_", ignoreCase = true) -> id.removePrefix("tmdb_m_").toIntOrNull()
      id.startsWith("tmdb_tv_", ignoreCase = true) -> id.removePrefix("tmdb_tv_").toIntOrNull()
      else -> id.toIntOrNull()
    }?.takeIf { it > 0 }
  }

  /** IMDb id dell'item (`tt...`), usato quando il catalogo non espone un id TMDB. */
  internal fun imdbIdOf(item: MediaItem): String? {
    item.imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }?.let { return it }
    val id = item.id.trim()
    return id.takeIf { it.startsWith("tt", ignoreCase = true) }?.substringBefore(":")
  }

  /**
   * Sceglie la sinossi da mostrare: `localized` (lingua app) → originale già
   * presente sull'item → traduzioni esplicite (lingua app, poi inglese).
   * Il risultato non è mai vuoto quando l'item aveva già una sinossi.
   */
  internal fun pickSynopsis(
    item: MediaItem,
    localized: String?,
    translations: List<Pair<String, String?>> = emptyList(),
    code: String = languageCode()
  ): String {
    val original = item.synopsis.trim()

    pickLocalized(original, localized, code)?.let { return it }

    translations.firstOrNull { (lang, text) ->
      lang.equals(code, ignoreCase = true) && pickLocalized(original, text, code) != null
    }?.let { (_, text) -> return text!!.trim() }

    translations.firstOrNull { (lang, text) ->
      lang.equals(FALLBACK_TRANSLATION_CODE, ignoreCase = true) && !text.isNullOrBlank()
    }?.let { (_, text) -> return text!!.trim() }

    return original.ifEmpty { localized?.trim().orEmpty() }
  }

  /**
   * Accetta [candidate] solo se è scritto nella lingua attesa e non è una copia
   * dell'originale: senza traduzione TMDB restituisce l'overview originale
   * (spesso inglese) al posto di quella richiesta.
   */
  private fun pickLocalized(original: String, candidate: String?, code: String): String? {
    val text = candidate?.trim().orEmpty()
    if (text.isEmpty()) return null
    if (text.equals(original, ignoreCase = true)) return null
    if (code == "it" && !isItalianLookingText(text, original)) return null
    return text
  }

  /**
   * Euristica testuale per una sinossi italiana: script latino, almeno due
   * lettere e presenza di parole/caratteri tipicamente italiani. Un testo in
   * un'altra scrittura (cirillico, CJK, greco, arabo) non è mai italiano.
   */
  internal fun isItalianLookingText(text: String, original: String = ""): Boolean {
    val value = text.trim()
    if (value.isEmpty()) return false
    if (value.equals(original.trim(), ignoreCase = true)) return false
    if (value.count { it.isLetter() } < 2) return false
    if (value.any { it.code > 0x24F && it.isLetter() }) return false
    val padded = " ${value.lowercase()} "
    if (ITALIAN_MARKERS.any { padded.contains(it) }) return true
    // Nessun marcatore italiano esplicito: se compaiono parole tipicamente
    // inglesi il testo è considerato NON italiano.
    return ENGLISH_MARKERS.none { padded.contains(it) }
  }

  /**
   * Sinossi localizzata dell'[item] chiesta a TMDB nella lingua dell'app.
   * `null` quando l'id non è risolvibile o TMDB non è raggiungibile: in quel
   * caso il chiamante mantiene la sinossi già presente sull'item.
   */
  internal suspend fun fetchLocalizedSynopsis(
    item: MediaItem,
    detailsProvider: (suspend (tmdbId: Int, isTv: Boolean) -> LocalizedSynopsisSource?)? = null
  ): String? {
    val isTv = item.type == MediaType.SERIE_TV
    return try {
      val tmdbId = tmdbIdOf(item)
        ?: MediaRepository.resolveImdbToTmdbId(imdbIdOf(item), isTv)
        ?: return null
      val source = detailsProvider?.invoke(tmdbId, isTv) ?: loadFromTmdb(tmdbId, isTv)
      pickSynopsis(item, source.overview, source.translations)
    } catch (e: Exception) {
      null
    }
  }

  /**
   * Sostituisce la sinossi dei soli titoli dell'Hero Banner con la versione
   * nella lingua dell'app. Poster, titoli, ordine, id, provider e ogni altro
   * campo restano intatti.
   */
  internal suspend fun localizeAll(
    items: List<MediaItem>,
    detailsProvider: (suspend (tmdbId: Int, isTv: Boolean) -> LocalizedSynopsisSource?)? = null
  ): List<MediaItem> = coroutineScope {
    if (items.isEmpty()) return@coroutineScope items
    items.map { item ->
      async {
        val synopsis = fetchLocalizedSynopsis(item, detailsProvider)
        if (synopsis.isNullOrBlank() || synopsis == item.synopsis) {
          item
        } else {
          item.copy(synopsis = synopsis)
        }
      }
    }.awaitAll()
  }

  /** Testi TMDB usati per scegliere la sinossi ([overview] + traduzioni `iso639_1`). */
  data class LocalizedSynopsisSource(
    val overview: String?,
    val translations: List<Pair<String, String?>> = emptyList()
  )

  private suspend fun loadFromTmdb(tmdbId: Int, isTv: Boolean): LocalizedSynopsisSource {
    val apiKey = MediaRepository.getEffectiveApiKey()
    val language = language()
    return if (isTv) {
      val details = TmdbApiClient.service.getTvDetails(
        seriesId = tmdbId,
        apiKey = apiKey,
        language = language
      )
      LocalizedSynopsisSource(details.overview, translationsOf(details))
    } else {
      val details = TmdbApiClient.service.getMovieDetails(
        movieId = tmdbId,
        apiKey = apiKey,
        language = language
      )
      LocalizedSynopsisSource(details.overview, translationsOf(details))
    }
  }

  private fun translationsOf(details: TmdbMovieDetailDto): List<Pair<String, String?>> =
    details.translations?.translations.orEmpty().map { translation ->
      translation.iso6391.orEmpty().lowercase() to translation.data?.overview
    }

  private fun translationsOf(details: TmdbTvDetailDto): List<Pair<String, String?>> =
    details.translations?.translations.orEmpty().map { translation ->
      translation.iso6391.orEmpty().lowercase() to translation.data?.overview
    }

  private const val FALLBACK_TRANSLATION_CODE = "en"

  private val ITALIAN_MARKERS = listOf(
    " il ", " lo ", " la ", " gli ", " le ", " un ", " uno ", " una ", " di ", " del ",
    " della ", " dei ", " delle ", " che ", " con ", " per ", " non ", " suo ", " sua ",
    " dopo ", " quando ", " mentre ", " nella ", " nel ", " si ", " è ", " sono ", " ha ",
    " hanno ", "à", "è", "é", "ì", "ò", "ù"
  )

  private val ENGLISH_MARKERS = listOf(
    " the ", " and ", " of ", " to ", " his ", " her ", " their ", " is ", " was ", " when ",
    " with ", " from ", " that ", " who ", " discovers ", " story ", " life ", " must "
  )
}
