package com.example.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    official: Boolean? = null
  ) = TmdbVideoDto(
    id = key,
    key = key,
    name = name ?: type,
    site = site,
    type = type,
    language = language,
    official = official
  )

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

  @Test
  fun pickTrailerPrefersItalianTrailerThenEnglishThenAnyLanguage() {
    val italian = listOf(
      video("en_key", "Trailer", "en"),
      video("it_key", "Trailer", "it")
    )
    assertEquals("it_key", TmdbMediaAssets.pickTrailerKey(italian))

    val englishOnly = listOf(
      video("xx_key", "Trailer", "ja"),
      video("en_key", "Trailer", "en")
    )
    assertEquals("en_key", TmdbMediaAssets.pickTrailerKey(englishOnly))

    val anyLanguage = listOf(video("xx_key", "Trailer", "ja"))
    assertEquals("xx_key", TmdbMediaAssets.pickTrailerKey(anyLanguage))
  }

  @Test
  fun pickTrailerFallsBackToTeaserThenAnyYoutubeVideo() {
    val teaserOnly = listOf(video("teaser_it", "Teaser", "it"))
    assertEquals("teaser_it", TmdbMediaAssets.pickTrailerKey(teaserOnly))

    val clipOnly = listOf(video("clip", "Clip", null))
    assertEquals("clip", TmdbMediaAssets.pickTrailerKey(clipOnly))
  }

  @Test
  fun pickTrailerAlwaysPrefersItalianOverEnglishInMixedList() {
    // Lista mista IT/EN: l'italiano vince SEMPRE, anche quando l'inglese e' ufficiale
    // ed e' il primo elemento restituito da TMDB.
    val mixed = listOf(
      video("en_official", "Trailer", "en", official = true),
      video("en_key", "Trailer", "en"),
      video("it_key", "Trailer", "it")
    )
    assertEquals("it_key", TmdbMediaAssets.pickTrailerKey(mixed))

    // Anche quando l'italiano e' l'ultimo elemento e non ufficiale.
    val italianLast = listOf(
      video("en_1", "Trailer", "en", official = true),
      video("en_2", "Trailer", "en"),
      video("it_key", "Trailer", "it", official = false)
    )
    assertEquals("it_key", TmdbMediaAssets.pickTrailerKey(italianLast))
  }

  @Test
  fun pickTrailerPrefersOfficialItalianThenAnyItalian() {
    val list = listOf(
      video("it_second", "Trailer", "it", official = false),
      video("it_official", "Trailer", "it", official = true),
      video("en_official", "Trailer", "en", official = true)
    )
    assertEquals("it_official", TmdbMediaAssets.pickTrailerKey(list))

    val noOfficialItalian = listOf(
      video("it_b", "Trailer", "it"),
      video("it_a", "Trailer", "it"),
      video("en_official", "Trailer", "en", official = true)
    )
    assertEquals("it_b", TmdbMediaAssets.pickTrailerKey(noOfficialItalian))
  }

  @Test
  fun pickTrailerDetectsItalianByNameWhenIsoLanguageDiffers() {
    // TMDB classifica alcuni doppiaggi italiani con iso_639_1 != "it": il nome
    // ("italiano"/"ita") deve comunque far vincere il trailer italiano.
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
  }

  @Test
  fun pickTrailerItalianTeaserBeatsEnglishTrailerButNotItalianTrailer() {
    // Priorita': 1) Trailer IT, 2) Teaser IT, 3) Trailer EN.
    val teaserOverEnglish = listOf(
      video("en_official", "Trailer", "en", official = true),
      video("teaser_it", "Teaser", "it")
    )
    assertEquals("teaser_it", TmdbMediaAssets.pickTrailerKey(teaserOverEnglish))

    val italianTrailerOverItalianTeaser = listOf(
      video("teaser_it", "Teaser", "it"),
      video("it_key", "Trailer", "it")
    )
    assertEquals("it_key", TmdbMediaAssets.pickTrailerKey(italianTrailerOverItalianTeaser))
  }

  @Test
  fun pickTrailerFallsBackToEnglishWhenNoItalianVideoExists() {
    val english = listOf(
      video("xx_key", "Trailer", "ja"),
      video("en_official", "Trailer", "en", official = true),
      video("en_key", "Trailer", "en")
    )
    assertEquals("en_official", TmdbMediaAssets.pickTrailerKey(english))
  }

  @Test
  fun pickTrailerIgnoresNonYoutubeSitesAndBlankKeys() {
    val invalid = listOf(
      video("", "Trailer", "it"),
      video("vimeo_key", "Trailer", "it", site = "Vimeo")
    )
    assertNull(TmdbMediaAssets.pickTrailerKey(invalid))
    assertNull(TmdbMediaAssets.pickTrailerKey(emptyList()))
  }
}
