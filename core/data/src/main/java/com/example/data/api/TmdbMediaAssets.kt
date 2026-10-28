package com.example.data.api

/**
 * Regole deterministiche (e testabili in JVM puro) per scegliere gli asset multimediali
 * restituiti da TMDB: clear logo del titolo e chiave YouTube del trailer.
 *
 * Nessuna dipendenza Android: le funzioni operano solo sui DTO TMDB, cosi' la priorita'
 * linguistica (it -> en -> miglior disponibile) e' verificabile dai test unitari.
 */
object TmdbMediaAssets {

  /** Priorita' delle lingue per logo e trailer. */
  private val LANGUAGE_PRIORITY = listOf("it", "en")

  private const val TRAILER_TYPE = "Trailer"
  private const val TEASER_TYPE = "Teaser"
  private const val YOUTUBE_SITE = "YouTube"

  /**
   * Sceglie il clear logo da `images.logos` con priorita':
   * 1. lingua italiana (`iso_639_1 == "it"`);
   * 2. lingua inglese (`"en"`);
   * 3. primo disponibile con la risoluzione (larghezza) piu' alta.
   */
  fun pickLogo(logos: List<TmdbLogoItemDto>?): TmdbLogoItemDto? {
    val usable = logos.orEmpty().filter { it.filePath.isNotBlank() }
    if (usable.isEmpty()) return null

    for (language in LANGUAGE_PRIORITY) {
      usable.firstOrNull { it.language.equals(language, ignoreCase = true) }?.let { return it }
    }

    return usable.maxByOrNull { it.width ?: 0 } ?: usable.first()
  }

  /**
   * Sceglie la chiave YouTube del trailer da `videos.results` con priorita' ASSOLUTA
   * all'italiano:
   * 1. `type == "Trailer"` in italiano (`iso_639_1 == "it"` o nome con
   *    "italiano"/"ita"), preferendo i video `official == true`;
   * 2. `type == "Teaser"` in italiano;
   * 3. `type == "Trailer"` in inglese (`iso_639_1 == "en"`), preferendo `official == true`;
   * 4. primo `type == "Trailer"` disponibile (qualsiasi lingua), infine qualsiasi
   *    altro video YouTube restituito.
   */
  fun pickTrailer(videos: List<TmdbVideoDto>?): TmdbVideoDto? {
    val youtube = videos.orEmpty().filter {
      !it.key.isNullOrBlank() && it.site.equals(YOUTUBE_SITE, ignoreCase = true)
    }
    if (youtube.isEmpty()) return null

    // Preferenza per i video ufficiali, con fallback al primo del gruppo.
    fun officialFirst(group: List<TmdbVideoDto>): TmdbVideoDto? =
      group.firstOrNull { it.official == true } ?: group.firstOrNull()

    // 1. Trailer italiano (priorita' massima).
    officialFirst(
      youtube.filter { it.type.equals(TRAILER_TYPE, ignoreCase = true) && it.isItalian() }
    )?.let { return it }

    // 2. Teaser italiano.
    officialFirst(
      youtube.filter { it.type.equals(TEASER_TYPE, ignoreCase = true) && it.isItalian() }
    )?.let { return it }

    // 3. Trailer inglese (fallback principale).
    officialFirst(
      youtube.filter {
        it.type.equals(TRAILER_TYPE, ignoreCase = true) &&
          it.language.equals("en", ignoreCase = true)
      }
    )?.let { return it }

    // 4. Primo trailer YouTube di qualsiasi lingua, poi qualsiasi video YouTube.
    youtube.firstOrNull { it.type.equals(TRAILER_TYPE, ignoreCase = true) }?.let { return it }
    return youtube.first()
  }

  /** Come [pickTrailer], ma restituisce solo la chiave YouTube (pronta per l'embed). */
  fun pickTrailerKey(videos: List<TmdbVideoDto>?): String? =
    pickTrailer(videos)?.key?.takeIf { it.isNotBlank() }

  /**
   * true se il video e' in italiano: `iso_639_1 == "it"` oppure il nome del video
   * contiene "italiano"/"ita" (TMDB classifica alcuni doppiaggi italiani come "en").
   */
  private fun TmdbVideoDto.isItalian(): Boolean {
    if (language.equals("it", ignoreCase = true)) return true
    val videoName = name.orEmpty()
    return videoName.contains("italiano", ignoreCase = true) ||
      videoName.contains("ita", ignoreCase = true)
  }
}
