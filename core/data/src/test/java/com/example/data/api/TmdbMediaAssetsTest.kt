package com.example.data.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbMediaAssetsTest {

  private fun logo(path: String, language: String?, width: Int? = null) =
    TmdbLogoItemDto(filePath = path, language = language, aspectRatio = 1.5f, width = width, height = null)

  private fun video(
    key: String?,
    type: String,
    language: String?,
    site: String = "YouTube",
    name: String? = null,
    official: Boolean? = null,
    id: String? = key,
    publishedAt: String? = null
  ) = TmdbVideoDto(
    id = id,
    key = key,
    name = name ?: type,
    site = site,
    type = type,
    language = language,
    official = official,
    publishedAt = publishedAt
  )

  private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

  private fun parseVideos(json: String): TmdbVideosResponseDto =
    requireNotNull(moshi.adapter(TmdbVideosResponseDto::class.java).fromJson(json))

  // ---------------------------------------------------------------------------------
  // Clear logo (comportamento invariato)
  // ---------------------------------------------------------------------------------

  @Test
  fun pickLogoPrefersItalianOverEnglishAndWidth() {
    val logos = listOf(
      logo("/en.png", "en", width = 1280),
      logo("/it.png", "it", width = 300),
      logo("/xx.png", "xx", width = 2000)
    )
    assertEquals("/it.png", TmdbMediaAssets.pickLogo(logos)?.filePath)
  }

  @Test
  fun pickLogoFallsBackToEnglishThenWidest() {
    val englishFirst = listOf(
      logo("/en.png", "en", width = 300),
      logo("/xx.png", "xx", width = 1800)
    )
    assertEquals("/en.png", TmdbMediaAssets.pickLogo(englishFirst)?.filePath)

    val noPriorityLanguage = listOf(
      logo("/small.png", "fr", width = 300),
      logo("/big.png", "ja", width = 1000)
    )
    assertEquals("/big.png", TmdbMediaAssets.pickLogo(noPriorityLanguage)?.filePath)
  }

  @Test
  fun pickLogoIgnoresBlankPathsAndEmptyInput() {
    assertNull(TmdbMediaAssets.pickLogo(null))
    assertNull(TmdbMediaAssets.pickLogo(emptyList()))
    assertNull(TmdbMediaAssets.pickLogo(listOf(logo("  ", "it"))))
  }

  // ---------------------------------------------------------------------------------
  // Selezione trailer: lingua (it > en > non dichiarata > altre) -> tipo -> ufficialita'
  // ---------------------------------------------------------------------------------

  @Test
  fun italianOfficialTrailerBeatsEnglishOfficialTrailer() {
    val videos = listOf(
      video("en_official", "Trailer", "en", official = true),
      video("it_official", "Trailer", "it", official = true)
    )
    assertEquals("it_official", TmdbMediaAssets.pickTrailerKey(videos))
  }

  @Test
  fun unofficialItalianTrailerBeatsEnglishOfficialTrailer() {
    // Priorita' richiesta: il trailer italiano non ufficiale viene PRIMA del trailer
    // inglese ufficiale.
    val videos = listOf(
      video("en_official", "Trailer", "en", official = true),
      video("it_unofficial", "Trailer", "it", official = false)
    )
    assertEquals("it_unofficial", TmdbMediaAssets.pickTrailerKey(videos))

    // Anche quando l'inglese ufficiale e' l'unico video inglese e l'italiano ha
    // `official` assente (null): l'italiano vince comunque.
    val nullOfficial = listOf(
      video("en_official", "Trailer", "en", official = true),
      video("it_unknown_official", "Trailer", "it", official = null)
    )
    assertEquals("it_unknown_official", TmdbMediaAssets.pickTrailerKey(nullOfficial))
  }

  @Test
  fun officialItalianTrailerBeatsUnofficialItalianTrailer() {
    val videos = listOf(
      video("it_unofficial", "Trailer", "it", official = false),
      video("it_official", "Trailer", "it", official = true)
    )
    assertEquals("it_official", TmdbMediaAssets.pickTrailerKey(videos))
  }

  @Test
  fun englishTrailerUsedWhenNoItalianVideoExists() {
    val videos = listOf(
      video("ja_key", "Trailer", "ja"),
      video("en_unofficial", "Trailer", "en", official = false),
      video("en_official", "Trailer", "en", official = true)
    )
    assertEquals("en_official", TmdbMediaAssets.pickTrailerKey(videos))
  }

  @Test
  fun fallsBackToOtherLanguagesPreferringOfficialThenTrailer() {
    // Nessun video IT/EN: prima i trailer ufficiali, poi i non ufficiali.
    val otherLanguages = listOf(
      video("fr_unofficial", "Trailer", "fr", official = false),
      video("ja_official", "Trailer", "ja", official = true),
      video("de_official", "Trailer", "de", official = true)
    )
    assertEquals("ja_official", TmdbMediaAssets.pickTrailerKey(otherLanguages))

    // A parita' di lingua/lingue "altre" il tipo Trailer vince sul Teaser.
    val teaserOnlyOfficial = listOf(
      video("fr_teaser_official", "Teaser", "fr", official = true),
      video("fr_trailer", "Trailer", "fr", official = false)
    )
    assertEquals("fr_trailer", TmdbMediaAssets.pickTrailerKey(teaserOnlyOfficial))
  }

  @Test
  fun prefersTrailerOverTeaserWithinTheSameLanguage() {
    // Italiano: Trailer (anche non ufficiale) prima di un Teaser ufficiale.
    val italian = listOf(
      video("it_teaser_official", "Teaser", "it", official = true),
      video("it_trailer", "Trailer", "it", official = false)
    )
    assertEquals("it_trailer", TmdbMediaAssets.pickTrailerKey(italian))

    // Inglese: stessa regola.
    val english = listOf(
      video("en_teaser_official", "Teaser", "en", official = true),
      video("en_trailer", "Trailer", "en", official = false)
    )
    assertEquals("en_trailer", TmdbMediaAssets.pickTrailerKey(english))

    // Teaser prima di Clip/Featurette.
    val nonTrailers = listOf(
      video("it_clip", "Clip", "it", official = true),
      video("it_teaser", "Teaser", "it", official = false)
    )
    assertEquals("it_teaser", TmdbMediaAssets.pickTrailerKey(nonTrailers))
  }

  @Test
  fun italianVideoBeatsEnglishTrailerEvenWhenItIsOnlyATeaser() {
    // La lingua domina il tipo: un contenuto italiano (qui un Teaser) resta preferito
    // a un trailer inglese. Comportamento storico, nessuna regressione.
    val videos = listOf(
      video("en_official", "Trailer", "en", official = true),
      video("it_teaser", "Teaser", "it")
    )
    assertEquals("it_teaser", TmdbMediaAssets.pickTrailerKey(videos))
  }

  @Test
  fun videoWithoutDeclaredLanguageIsUsedAndRankedAfterEnglish() {
    // Senza lingua dichiarata: resta riproducibile se non c'e' nulla di meglio.
    val onlyUnknown = listOf(video("unknown_key", "Trailer", null))
    assertEquals("unknown_key", TmdbMediaAssets.pickTrailerKey(onlyUnknown))

    // Un video inglese dichiarato vince su uno senza lingua...
    val englishVsUnknown = listOf(
      video("unknown_key", "Trailer", null, official = true),
      video("en_key", "Trailer", "en")
    )
    assertEquals("en_key", TmdbMediaAssets.pickTrailerKey(englishVsUnknown))

    // ...ma un video senza lingua vince su una lingua "altra" esplicita.
    val unknownVsOther = listOf(
      video("fr_key", "Trailer", "fr", official = true),
      video("unknown_key", "Trailer", null)
    )
    assertEquals("unknown_key", TmdbMediaAssets.pickTrailerKey(unknownVsOther))
  }

  @Test
  fun italianDetectedByNameWhenIsoLanguageDiffers() {
    // TMDB classifica alcuni doppiaggi italiani con `iso_639_1` diverso da "it":
    // il nome del video ("italiano"/"ITA") deve comunque farlo vincere.
    val byItaliano = listOf(
      video("en_official", "Trailer", "en", official = true),
      video("it_by_name", "Trailer", "en", name = "Trailer ufficiale (Italiano)")
    )
    assertEquals("it_by_name", TmdbMediaAssets.pickTrailerKey(byItaliano))

    val byIta = listOf(
      video("en_official", "Trailer", "en", official = true),
      video("it_by_name", "Trailer", null, name = "Trailer ITA sottotitolato")
    )
    assertEquals("it_by_name", TmdbMediaAssets.pickTrailerKey(byIta))

    // Il confronto e' per parola intera: "Capital" non e' italiano.
    assertFalse(TmdbMediaAssets.isItalianVideo(video("capital", "Trailer", "en", name = "Capital Trailer")))
    assertTrue(TmdbMediaAssets.isItalianVideo(video("ita", "Trailer", "en", name = "Trailer ITA")))
  }

  @Test
  fun publishedAtBreaksTiesBetweenEquivalentVideos() {
    val videos = listOf(
      video("old", "Trailer", "it", official = true, publishedAt = "2019-01-01T00:00:00.000Z"),
      video("new", "Trailer", "it", official = true, publishedAt = "2024-05-01T00:00:00.000Z")
    )
    assertEquals("new", TmdbMediaAssets.pickTrailerKey(videos))
  }

  // ---------------------------------------------------------------------------------
  // Unione delle risposte localizzate
  // ---------------------------------------------------------------------------------

  @Test
  fun mergeVideosDeduplicatesResultsAcrossLocalizedResponses() {
    val italianResponse = listOf(
      video("it_key", "Trailer", "it", id = "1"),
      video("en_key", "Trailer", "en", id = "2")
    )
    // La risposta en-US ripete gli stessi video (id identici) e ne aggiunge uno
    // senza lingua: nessun duplicato, nessun video perso.
    val englishResponse = listOf(
      video("it_key_localized_name", "Trailer", "it", id = "1", name = "Trailer (EN page)"),
      video("unknown_key", "Trailer", null, id = "3")
    )

    val merged = TmdbMediaAssets.mergeVideos(italianResponse, englishResponse)

    assertEquals(listOf("1", "2", "3"), merged.map { it.id })
    // Vince la prima occorrenza: nome e metadati della risposta italiana.
    assertEquals("Trailer", merged.first { it.id == "1" }.name)
    assertEquals("it_key", TmdbMediaAssets.pickTrailerKey(merged))
  }

  @Test
  fun mergeVideosFallsBackToSiteAndKeyWhenIdIsMissing() {
    val first = listOf(video("shared_key", "Trailer", "en", id = null))
    val second = listOf(
      video("shared_key", "Trailer", "en", id = null),
      video("other_key", "Teaser", "en", id = null)
    )

    val merged = TmdbMediaAssets.mergeVideos(first, second)
    assertEquals(2, merged.size)
    assertEquals("shared_key", merged[0].key)
    assertEquals("other_key", merged[1].key)
  }

  @Test
  fun mergeVideosHandlesNullAndEmptyResponses() {
    assertTrue(TmdbMediaAssets.mergeVideos(null, emptyList(), null).isEmpty())
    val videos = listOf(video("it_key", "Trailer", "it"))
    assertEquals(videos, TmdbMediaAssets.mergeVideos(null, videos, emptyList()))
  }

  // ---------------------------------------------------------------------------------
  // Film e serie TV: stesso schema di risposta, stessa selezione
  // ---------------------------------------------------------------------------------

  @Test
  fun movieAndTvVideoResponsesExposeTheSameMetadataAndSelection() {
    val movieJson = """
      {
        "id": 550,
        "results": [
          {
            "iso_639_1": "en",
            "iso_3166_1": "US",
            "name": "Fight Club - Official Trailer",
            "key": "movie_en_key",
            "site": "YouTube",
            "size": 1080,
            "type": "Trailer",
            "official": true,
            "published_at": "2020-01-01T00:00:00.000Z",
            "id": "movie-en"
          },
          {
            "iso_639_1": "it",
            "iso_3166_1": "IT",
            "name": "Fight Club - Trailer italiano",
            "key": "movie_it_key",
            "site": "YouTube",
            "size": 1080,
            "type": "Trailer",
            "official": false,
            "published_at": "2020-02-01T00:00:00.000Z",
            "id": "movie-it"
          }
        ]
      }
    """.trimIndent()

    val tvJson = """
      {
        "id": 1399,
        "results": [
          {
            "iso_639_1": "en",
            "iso_3166_1": "US",
            "name": "Game of Thrones - Official Trailer",
            "key": "tv_en_key",
            "site": "YouTube",
            "size": 1080,
            "type": "Trailer",
            "official": true,
            "published_at": "2019-03-05T00:00:00.000Z",
            "id": "tv-en"
          },
          {
            "iso_639_1": "it",
            "iso_3166_1": "IT",
            "name": "Il Trono di Spade - Trailer italiano",
            "key": "tv_it_key",
            "site": "YouTube",
            "size": 1080,
            "type": "Trailer",
            "official": false,
            "published_at": "2019-03-06T00:00:00.000Z",
            "id": "tv-it"
          }
        ]
      }
    """.trimIndent()

    val movie = parseVideos(movieJson)
    val tv = parseVideos(tvJson)

    // Tutti i metadati richiesti sono effettivamente presenti nel DTO.
    val movieEnglish = movie.results.orEmpty().first { it.key == "movie_en_key" }
    assertEquals("en", movieEnglish.language)
    assertEquals("YouTube", movieEnglish.site)
    assertEquals("Trailer", movieEnglish.type)
    assertEquals(true, movieEnglish.official)
    assertEquals("2020-01-01T00:00:00.000Z", movieEnglish.publishedAt)

    assertEquals("movie_it_key", TmdbMediaAssets.pickTrailerKey(movie.results))
    assertEquals("tv_it_key", TmdbMediaAssets.pickTrailerKey(tv.results))
  }

  // ---------------------------------------------------------------------------------
  // Assenza di video / regressioni
  // ---------------------------------------------------------------------------------

  @Test
  fun noVideosAvailableReturnsNull() {
    assertNull(TmdbMediaAssets.pickTrailerKey(null))
    assertNull(TmdbMediaAssets.pickTrailerKey(emptyList()))
    assertNull(TmdbMediaAssets.pickTrailerKey(parseVideos("""{"id": 1, "results": []}""").results))
  }

  @Test
  fun videosWithoutPlayableYoutubeKeyAreIgnored() {
    val invalid = listOf(
      video("", "Trailer", "it"),
      video(null, "Trailer", "it"),
      video("vimeo_key", "Trailer", "it", site = "Vimeo")
    )
    assertNull(TmdbMediaAssets.pickTrailerKey(invalid))
    assertNull(TmdbMediaAssets.pickTrailer(invalid))
  }

  @Test
  fun selectedKeyIsReturnedVerbatimForTheEmbeddedPlayer() {
    // Nessuna trasformazione della chiave: il player embedded la usa cosi' com'e'.
    val videos = listOf(video("dQw4w9WgXcQ", "Trailer", "it", official = true))
    assertEquals("dQw4w9WgXcQ", TmdbMediaAssets.pickTrailerKey(videos))
    assertEquals("dQw4w9WgXcQ", TmdbMediaAssets.pickTrailer(videos)?.key)
  }
}
