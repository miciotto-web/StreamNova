package com.example.data.opensubtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubtitlesSubtitleAdapterTest {

  @Test
  fun mappingCompletoDaItemValido() {
    val item = OpenSubtitlesItemDto(
      id = "1001",
      type = "subtitle",
      attributes = OpenSubtitlesAttributesDto(
        subtitleId = "1001",
        language = "ita",
        downloadUrl = "https://sub.opensubtitles.com/download/file/1001.srt",
        release = "1080p.BluRay.x264",
        hearingImpaired = false
      )
    )

    val subtitle = OpenSubtitlesSubtitleAdapter.toSubtitle(item)
    assertNotNull(subtitle)
    assertEquals("os-1001", subtitle?.id)
    assertEquals("https://sub.opensubtitles.com/download/file/1001.srt", subtitle?.url)
    assertEquals("it", subtitle?.lang)
    assertEquals("1080p.BluRay.x264", subtitle?.label)
    assertEquals("OpenSubtitles v3", subtitle?.addonName)
    assertFalse(subtitle?.isStreamProvided == true)
  }

  @Test
  fun mappingConHearingImpairedAggiungeTagSDH() {
    val item = OpenSubtitlesItemDto(
      id = "2002",
      attributes = OpenSubtitlesAttributesDto(
        language = "eng",
        downloadUrl = "https://sub.test/file.srt",
        release = "WEB-DL",
        hearingImpaired = true
      )
    )

    val subtitle = OpenSubtitlesSubtitleAdapter.toSubtitle(item)
    assertNotNull(subtitle)
    assertEquals("WEB-DL [SDH]", subtitle?.label)
    assertEquals("en", subtitle?.lang)
  }

  @Test
  fun mappingDaFileDtoInterno() {
    val item = OpenSubtitlesItemDto(
      id = "3003",
      attributes = OpenSubtitlesAttributesDto(
        language = "spa",
        files = listOf(
          OpenSubtitlesFileDto(
            fileId = 9999L,
            fileName = "spanish_sub.srt",
            downloadUrl = "https://sub.test/spanish.srt"
          )
        )
      )
    )

    val subtitle = OpenSubtitlesSubtitleAdapter.toSubtitle(item)
    assertNotNull(subtitle)
    assertEquals("os-3003", subtitle?.id)
    assertEquals("https://sub.test/spanish.srt", subtitle?.url)
    assertEquals("es", subtitle?.lang)
    assertEquals("spanish_sub.srt", subtitle?.label)
  }

  @Test
  fun scartaRisultatiSenzaDownloadUrlValido() {
    // 1. URL null
    val itemNullUrl = OpenSubtitlesItemDto(
      id = "1",
      attributes = OpenSubtitlesAttributesDto(language = "it", downloadUrl = null)
    )
    assertNull(OpenSubtitlesSubtitleAdapter.toSubtitle(itemNullUrl))

    // 2. URL vuoto o solo spazi
    val itemEmptyUrl = OpenSubtitlesItemDto(
      id = "2",
      attributes = OpenSubtitlesAttributesDto(language = "it", downloadUrl = "   ")
    )
    assertNull(OpenSubtitlesSubtitleAdapter.toSubtitle(itemEmptyUrl))

    // 3. URL non-HTTP/HTTPS (schema non supportato dal player)
    val itemInvalidUrl = OpenSubtitlesItemDto(
      id = "3",
      attributes = OpenSubtitlesAttributesDto(language = "it", downloadUrl = "ftp://invalid.test/sub.srt")
    )
    assertNull(OpenSubtitlesSubtitleAdapter.toSubtitle(itemInvalidUrl))
  }

  @Test
  fun attrUrlDaSoloNonVieneUsatoComeSubtitleUrl() {
    // `attributes.url` è la PAGINA WEB OpenSubtitles, non il file: usarla come url del
    // sottotitolo fa caricare HTML al decoder SubRip e fa fallire la riproduzione.
    val pageUrl = "https://www.opensubtitles.org/en/subtitles/4472195/the-matrix-en"
    val item = OpenSubtitlesItemDto(
      id = "page-only",
      attributes = OpenSubtitlesAttributesDto(
        subtitleId = "page-only",
        language = "ita",
        url = pageUrl
      )
    )

    assertNull(OpenSubtitlesSubtitleAdapter.toSubtitle(item))
    assertFalse(OpenSubtitlesSubtitleAdapter.isValidDownloadUrl(pageUrl))
    assertTrue(OpenSubtitlesSubtitleAdapter.isWebsitePageUrl(pageUrl))
  }

  @Test
  fun attrUrlNonSostituisceUnDownloadUrlValido() {
    val item = OpenSubtitlesItemDto(
      id = "mixed",
      attributes = OpenSubtitlesAttributesDto(
        language = "ita",
        url = "https://www.opensubtitles.org/en/subtitles/123/title-en",
        downloadUrl = "https://dl.opensubtitles.com/download/file/123.srt"
      )
    )

    val subtitle = OpenSubtitlesSubtitleAdapter.toSubtitle(item)
    assertNotNull(subtitle)
    assertEquals("https://dl.opensubtitles.com/download/file/123.srt", subtitle?.url)
  }

  @Test
  fun pagineWebOpenSubtitlesNonSonoAccettateComeDownload() {
    assertFalse(
      OpenSubtitlesSubtitleAdapter.isValidDownloadUrl(
        "https://www.opensubtitles.org/it/sottotitoli/4472195/the-matrix-it"
      )
    )
    assertFalse(OpenSubtitlesSubtitleAdapter.isValidDownloadUrl("https://www.opensubtitles.com"))
    // I link di download reali usano host diversi dal sito e restano validi.
    assertTrue(
      OpenSubtitlesSubtitleAdapter.isValidDownloadUrl("https://dl.opensubtitles.com/download/file/1.srt")
    )
    assertTrue(
      OpenSubtitlesSubtitleAdapter.isValidDownloadUrl("https://www.opensubtitles.com/download/xyz")
    )
  }

  @Test
  fun rispostaVuotaRestituisceListaVuota() {
    assertTrue(OpenSubtitlesSubtitleAdapter.toSubtitles(null).isEmpty())
    assertTrue(OpenSubtitlesSubtitleAdapter.toSubtitles(OpenSubtitlesSearchResponse(data = null)).isEmpty())
    assertTrue(OpenSubtitlesSubtitleAdapter.toSubtitles(OpenSubtitlesSearchResponse(data = emptyList())).isEmpty())
  }

  @Test
  fun rispostaConMixDiValidiEInvalidiFiltraCorrettamente() {
    val validItem = OpenSubtitlesItemDto(
      id = "ok-1",
      attributes = OpenSubtitlesAttributesDto(
        language = "ita",
        downloadUrl = "https://sub.test/valid.srt"
      )
    )
    val invalidItem = OpenSubtitlesItemDto(
      id = "bad-1",
      attributes = OpenSubtitlesAttributesDto(
        language = "ita",
        downloadUrl = ""
      )
    )

    val response = OpenSubtitlesSearchResponse(data = listOf(validItem, invalidItem))
    val result = OpenSubtitlesSubtitleAdapter.toSubtitles(response)

    assertEquals(1, result.size)
    assertEquals("os-ok-1", result[0].id)
    assertEquals("https://sub.test/valid.srt", result[0].url)
  }

  @Test
  fun normalizzazioneLingue() {
    assertEquals("it", OpenSubtitlesSubtitleAdapter.normalizeLanguage("ita"))
    assertEquals("it", OpenSubtitlesSubtitleAdapter.normalizeLanguage("it"))
    assertEquals("it", OpenSubtitlesSubtitleAdapter.normalizeLanguage("IT-IT"))
    assertEquals("en", OpenSubtitlesSubtitleAdapter.normalizeLanguage("eng"))
    assertEquals("en", OpenSubtitlesSubtitleAdapter.normalizeLanguage("en-US"))
    assertEquals("es", OpenSubtitlesSubtitleAdapter.normalizeLanguage("spa"))
    assertEquals("fr", OpenSubtitlesSubtitleAdapter.normalizeLanguage("fra"))
    assertEquals("fr", OpenSubtitlesSubtitleAdapter.normalizeLanguage("fre"))
    assertEquals("de", OpenSubtitlesSubtitleAdapter.normalizeLanguage("deu"))
    assertEquals("de", OpenSubtitlesSubtitleAdapter.normalizeLanguage("ger"))
    assertEquals("pt", OpenSubtitlesSubtitleAdapter.normalizeLanguage("por"))
    assertEquals("und", OpenSubtitlesSubtitleAdapter.normalizeLanguage(null))
    assertEquals("und", OpenSubtitlesSubtitleAdapter.normalizeLanguage(""))
  }

  @Test
  fun verificaValiditaUrl() {
    assertTrue(OpenSubtitlesSubtitleAdapter.isValidDownloadUrl("http://a.test/s.srt"))
    assertTrue(OpenSubtitlesSubtitleAdapter.isValidDownloadUrl("https://a.test/s.srt"))
    assertFalse(OpenSubtitlesSubtitleAdapter.isValidDownloadUrl("file:///data/s.srt"))
    assertFalse(OpenSubtitlesSubtitleAdapter.isValidDownloadUrl("/local/path/s.srt"))
    assertFalse(OpenSubtitlesSubtitleAdapter.isValidDownloadUrl(""))
  }
}
