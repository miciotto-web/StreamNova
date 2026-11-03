package com.example.data.api

/**
 * Regole deterministiche (e testabili in JVM puro) per scegliere gli asset multimediali
 * restituiti da TMDB: clear logo del titolo e chiave YouTube del trailer.
 *
 * Nessuna dipendenza Android: le funzioni operano solo sui DTO TMDB, cosi' la priorita'
 * linguistica e' verificabile dai test unitari.
 *
 * La lingua del trailer viene determinata ESCLUSIVAMENTE dai metadati del singolo video
 * (`iso_639_1` e, come ripiego, il nome del video): la lingua originale del film o della
 * serie non viene mai usata come criterio.
 */
object TmdbMediaAssets {

  /** Priorita' delle lingue per logo e trailer. */
  private val LANGUAGE_PRIORITY = listOf("it", "en")

  private const val TRAILER_TYPE = "Trailer"
  private const val TEASER_TYPE = "Teaser"
  private const val YOUTUBE_SITE = "YouTube"

  /** Riconosce "ITA"/"italiano"/"italiana"/"italian" come parole intere nel nome del video. */
  private val ITALIAN_NAME_PATTERN = Regex("\\b(?:ita|italian[oa]?)\\b", RegexOption.IGNORE_CASE)

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
   * Sceglie la chiave YouTube del trailer da `videos.results`.
   *
   * Ordine di priorita' (confronto lessicografico):
   * 1. lingua del video: italiano > inglese > lingua non dichiarata > altre lingue;
   * 2. tipo: `Trailer` > `Teaser` > `Clip`/`Featurette`/altro;
   * 3. video ufficiali (`official == true`) prima dei non ufficiali;
   * 4. pubblicazione piu' recente (`published_at`) prima.
   *
   * L'ordine richiesto "trailer italiano ufficiale -> trailer italiano non ufficiale ->
   * trailer inglese ufficiale -> trailer inglese non ufficiale -> altri" e' quindi
   * rispettato. A parita' di lingua il tipo `Trailer` vince sempre, anche se il
   * `Teaser` della stessa lingua e' ufficiale.
   *
   * Vengono considerati solo i video con `site == "YouTube"` e `key` valorizzata: senza
   * chiave il player embedded non puo' riprodurre nulla.
   */
  fun pickTrailer(videos: List<TmdbVideoDto>?): TmdbVideoDto? =
    videos.orEmpty()
      .filter { it.isPlayableYoutubeVideo() }
      .sortedWith(
        compareBy<TmdbVideoDto> { languageRank(it) }
          .thenBy { typeRank(it.type) }
          .thenBy { if (it.official == true) 0 else 1 }
          .thenByDescending { it.publishedAt.orEmpty() }
      )
      .firstOrNull()

  /** Come [pickTrailer], ma restituisce solo la chiave YouTube (pronta per l'embed). */
  fun pickTrailerKey(videos: List<TmdbVideoDto>?): String? =
    pickTrailer(videos)?.key?.takeIf { it.isNotBlank() }

  /**
   * true se il video selezionato da [pickTrailer] e' italiano secondo i metadati del
   * video. Usato dal repository per decidere se serve una seconda richiesta localizzata.
   */
  fun isItalianVideo(video: TmdbVideoDto?): Boolean = video?.isItalian() == true

  /**
   * Unisce i risultati di piu' richieste localizzate TMDB senza perdere video e senza
   * duplicarli. L'ordine di arrivo e' preservato: in caso di duplicato vince la prima
   * occorrenza (tipicamente la risposta `language=it-IT`).
   *
   * L'identita' e' l'`id` TMDB del video; se assente si ripiega sulla coppia
   * `site + key`, unica per il contenuto riproducibile.
   */
  fun mergeVideos(vararg responses: List<TmdbVideoDto>?): List<TmdbVideoDto> {
    val merged = ArrayList<TmdbVideoDto>()
    val seen = HashSet<String>()
    for (video in responses.filterNotNull().flatten()) {
      if (seen.add(video.identity())) merged += video
    }
    return merged
  }

  private fun TmdbVideoDto.identity(): String {
    id?.takeIf { it.isNotBlank() }?.let { return "id:$it" }
    return "key:${site.orEmpty().lowercase()}|${key.orEmpty()}"
  }

  private fun TmdbVideoDto.isPlayableYoutubeVideo(): Boolean =
    !key.isNullOrBlank() && site.equals(YOUTUBE_SITE, ignoreCase = true)

  private fun languageRank(video: TmdbVideoDto): Int = when {
    video.isItalian() -> 0
    video.language.equals("en", ignoreCase = true) -> 1
    video.language.isNullOrBlank() -> 2
    else -> 3
  }

  private fun typeRank(type: String?): Int = when {
    type.equals(TRAILER_TYPE, ignoreCase = true) -> 0
    type.equals(TEASER_TYPE, ignoreCase = true) -> 1
    else -> 2
  }

  /**
   * true se il video e' in italiano: `iso_639_1 == "it"` oppure il nome del video
   * contiene la parola "italiano"/"italiana"/"italian"/"ita" (TMDB classifica alcuni
   * doppiaggi italiani con `iso_639_1` diverso da "it"). Il confronto e' per parola
   * intera, cosi' titoli come "Capital" non vengono scambiati per italiani.
   */
  private fun TmdbVideoDto.isItalian(): Boolean {
    if (language.equals("it", ignoreCase = true)) return true
    return ITALIAN_NAME_PATTERN.containsMatchIn(name.orEmpty())
  }
}
