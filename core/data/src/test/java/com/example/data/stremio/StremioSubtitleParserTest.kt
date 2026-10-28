package com.example.data.stremio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test del layer dati della risorsa `subtitles` del protocollo Stremio.
 *
 * Nessuna rete, nessun Android, nessun Compose: parser, modello e costruzione del path
 * sono funzioni pure, quindi ogni regola di robustezza è verificabile in JVM.
 *
 * Invarianti verificati:
 * - risposta assente, `null`, malformata → lista vuota senza eccezioni;
 * - elemento incompleto (senza `id`, `url` o `lang`) → scartato, gli altri restano;
 * - i parametri extra ufficiali finiscono nel path solo se valorizzati;
 * - un addon che non dichiara `subtitles` non viene interrogato.
 */
class StremioSubtitleParserTest {

  // ── Parser ──────────────────────────────────────────────────────────────────

  @Test
  fun parseRispostaCompleta() {
    val json = """
      {
        "subtitles": [
          { "id": "1", "url": "https://sub.test/ita.srt", "lang": "ita", "label": "Italiano" },
          { "id": "2", "url": "https://sub.test/eng.srt", "lang": "eng" }
        ]
      }
    """.trimIndent()

    val parsed = StremioSubtitleParser.parse(json)

    assertEquals(2, parsed.size)
    assertEquals("1", parsed[0].effectiveId)
    assertEquals("https://sub.test/ita.srt", parsed[0].effectiveUrl)
    assertEquals("ita", parsed[0].normalizedLang)
    assertEquals("Italiano", parsed[0].displayLabel)
    assertEquals("2", parsed[1].effectiveId)
    // Senza label l'etichetta cade sulla lingua.
    assertEquals("eng", parsed[1].displayLabel)
  }

  @Test
  fun subtitlesAssenteONullProduceListaVuota() {
    assertTrue(StremioSubtitleParser.parse("{}").isEmpty())
    assertTrue(StremioSubtitleParser.parse("""{"subtitles": null}""").isEmpty())
    assertTrue(StremioSubtitleParser.parse("""{"subtitles": []}""").isEmpty())
  }

  @Test
  fun jsonVuotoONonValidoProduceListaVuotaSenzaEccezioni() {
    assertTrue(StremioSubtitleParser.parse(null).isEmpty())
    assertTrue(StremioSubtitleParser.parse("").isEmpty())
    assertTrue(StremioSubtitleParser.parse("   ").isEmpty())
    assertTrue(StremioSubtitleParser.parse("non è json").isEmpty())
    assertTrue(StremioSubtitleParser.parse("""{"subtitles": [{"id": }]}""").isEmpty())
    assertTrue(StremioSubtitleParser.parse("[1, 2, 3]").isEmpty())
  }

  @Test
  fun elementoSenzaUrlVieneScartatoGliAltriRestano() {
    val json = """
      {
        "subtitles": [
          { "id": "senza-url", "lang": "ita" },
          { "id": "ok", "url": "https://sub.test/ita.srt", "lang": "ita" }
        ]
      }
    """.trimIndent()

    val parsed = StremioSubtitleParser.parse(json)

    assertEquals(1, parsed.size)
    assertEquals("ok", parsed.single().effectiveId)
  }

  @Test
  fun elementoSenzaLinguaVieneScartato() {
    val json = """
      {
        "subtitles": [
          { "id": "no-lang", "url": "https://sub.test/a.srt" },
          { "id": "ok", "url": "https://sub.test/ita.srt", "lang": "ita" }
        ]
      }
    """.trimIndent()

    val parsed = StremioSubtitleParser.parse(json)

    assertEquals(1, parsed.size)
    assertEquals("ita", parsed.single().normalizedLang)
  }

  @Test
  fun elementoSenzaIdVieneScartato() {
    val json = """
      {
        "subtitles": [
          { "url": "https://sub.test/a.srt", "lang": "ita" },
          { "id": "ok", "url": "https://sub.test/ita.srt", "lang": "ita" }
        ]
      }
    """.trimIndent()

    assertEquals(1, StremioSubtitleParser.parse(json).size)
  }

  @Test
  fun valoriVuotiENullVengonoScartati() {
    val json = """
      {
        "subtitles": [
          { "id": "", "url": "https://sub.test/a.srt", "lang": "ita" },
          { "id": "vuoto", "url": "   ", "lang": "ita" },
          { "id": "vuoto2", "url": "https://sub.test/b.srt", "lang": "" },
          { "id": null, "url": "https://sub.test/c.srt", "lang": "ita" },
          { "id": "ok", "url": "https://sub.test/d.srt", "lang": "eng" }
        ]
      }
    """.trimIndent()

    val parsed = StremioSubtitleParser.parse(json)

    assertEquals(1, parsed.size)
    assertEquals("ok", parsed.single().effectiveId)
  }

  @Test
  fun elementoNullNelLinguaggioNonCauseCrash() {
    val json = """{ "subtitles": [ null, { "id": "ok", "url": "https://sub.test/d.srt", "lang": "eng" } ] }"""

    assertEquals(1, StremioSubtitleParser.parse(json).size)
  }

  @Test
  fun parseBodyCondivideIlComportamentoDiParse() {
    assertEquals(
      StremioSubtitleParser.parse("""{"subtitles":[{"id":"1","url":"https://a.test/x.srt","lang":"ita"}]}"""),
      StremioSubtitleParser.parseBody("""{"subtitles":[{"id":"1","url":"https://a.test/x.srt","lang":"ita"}]}""")
    )
    assertTrue(StremioSubtitleParser.parseBody(null).isEmpty())
  }

  // ── Modello ─────────────────────────────────────────────────────────────────

  @Test
  fun modelloEsponeCampiNormalizzati() {
    val subtitle = StremioSubtitle(id = " 1 ", url = " https://a.test/x.srt ", lang = " ITA ")

    assertEquals("1", subtitle.effectiveId)
    assertEquals("https://a.test/x.srt", subtitle.effectiveUrl)
    assertEquals("ITA", subtitle.effectiveLang)
    assertEquals("ita", subtitle.normalizedLang)
    assertTrue(subtitle.isValid)
  }

  @Test
  fun modelloIncompletoNonEValido() {
    assertFalse(StremioSubtitle().isValid)
    assertFalse(StremioSubtitle(id = "1", lang = "ita").isValid)
    assertFalse(StremioSubtitle(id = "1", url = "https://a.test/x.srt").isValid)
    assertNull(StremioSubtitle().effectiveUrl)
  }

  @Test
  fun linguaSconosciutaFallbackSuUnd() {
    assertEquals(StremioSubtitle.LANG_UNKNOWN, StremioSubtitle(id = "1").normalizedLang)
    assertEquals("", StremioSubtitle(id = "1").displayLabel)
  }

  @Test
  fun optionsScartaIValoriVuoti() {
    val options = StremioSubtitleOptions(
      videoHash = "  ",
      videoSize = 0L,
      filename = " movie.mkv "
    )

    assertEquals(mapOf(EXTRA_FILENAME to "movie.mkv"), options.toExtra())
    assertTrue(StremioSubtitleOptions().toExtra().isEmpty())
  }

  @Test
  fun optionsEsponeIParametriUfficiali() {
    val extra = StremioSubtitleOptions(
      videoHash = "8e245d9679d31e12",
      videoSize = 1073741824L,
      filename = "Dune.2021.mkv"
    ).toExtra()

    assertEquals("8e245d9679d31e12", extra[EXTRA_VIDEO_HASH])
    assertEquals("1073741824", extra[EXTRA_VIDEO_SIZE])
    assertEquals("Dune.2021.mkv", extra[EXTRA_FILENAME])
  }

  // ── Manifest ────────────────────────────────────────────────────────────────

  @Test
  fun manifestDichiaraLaRisorsaSubtitles() {
    val manifest = StremioManifest(
      id = "addon.a",
      resources = listOf("stream", "subtitles"),
      types = listOf(TYPE_MOVIE, TYPE_SERIES)
    )

    assertTrue(manifest.supportsSubtitles())
    assertTrue(manifest.supportsSubtitlesForType(TYPE_MOVIE))
    assertTrue(manifest.supportsSubtitlesForType(TYPE_SERIES))
    assertFalse(manifest.supportsSubtitlesForType("anime"))
  }

  @Test
  fun manifestSenzaSubtitlesNonECompatibile() {
    val manifest = StremioManifest(id = "addon.a", resources = listOf("stream"), types = listOf(TYPE_MOVIE))

    assertFalse(manifest.supportsSubtitles())
    assertFalse(manifest.supportsSubtitlesForType(TYPE_MOVIE))
  }

  @Test
  fun manifestLegacySenzaRisorseRestaCompatibile() {
    val manifest = StremioManifest(id = "addon.a", types = listOf(TYPE_MOVIE))

    assertTrue(manifest.supportsSubtitles())
    assertTrue(manifest.supportsSubtitlesForType(TYPE_MOVIE))
    assertFalse(manifest.supportsSubtitlesForType(TYPE_SERIES))
  }

  @Test
  fun risorsaSubtitlesPuoDichiarareTipiDedici() {
    val manifest = StremioManifest(
      id = "addon.a",
      resources = listOf(
        mapOf("name" to "subtitles", "types" to listOf(TYPE_SERIES)),
        "stream"
      ),
      types = listOf(TYPE_MOVIE, TYPE_SERIES)
    )

    assertTrue(manifest.supportsSubtitlesForType(TYPE_SERIES))
    assertFalse(manifest.supportsSubtitlesForType(TYPE_MOVIE))
  }

  // ── Repository: costruzione del path ────────────────────────────────────────

  @Test
  fun pathSenzaExtraUsaIlFormatoUfficiale() {
    assertEquals(
      "subtitles/movie/tt1234567.json",
      StremioAddonRepository.buildSubtitlesPath(TYPE_MOVIE, "tt1234567")
    )
    assertEquals(
      "subtitles/series/tt1234567:2:5.json",
      StremioAddonRepository.buildSubtitlesPath(TYPE_SERIES, "tt1234567:2:5")
    )
  }

  @Test
  fun pathConExtraUsaChiaviOrdinateEValoriCodificati() {
    val extra = StremioSubtitleOptions(
      videoHash = "abc",
      videoSize = 1024L,
      filename = "Il Mio Film.mkv"
    ).toExtra()

    val path = StremioAddonRepository.buildSubtitlesPath(TYPE_MOVIE, "tt1", extra)

    assertEquals("subtitles/movie/tt1/filename=Il%20Mio%20Film.mkv&videoHash=abc&videoSize=1024.json", path)
  }

  @Test
  fun pathScartaGliExtraVuoti() {
    val path = StremioAddonRepository.buildSubtitlesPath(
      type = TYPE_MOVIE,
      id = "tt1",
      extra = mapOf(EXTRA_VIDEO_HASH to "  ", EXTRA_FILENAME to "movie.mkv")
    )

    assertEquals("subtitles/movie/tt1/filename=movie.mkv.json", path)
  }

  @Test
  fun urlAssolutoCoincideConIlPath() {
    val url = StremioAddonRepository.buildSubtitlesUrl(
      baseUrl = "https://addon.test/",
      type = TYPE_MOVIE,
      id = "tt1"
    )

    assertEquals("https://addon.test/subtitles/movie/tt1.json", url)
  }

  // ── Repository: nessuna chiamata di rete quando l'addon non è compatibile ───

  @Test
  fun addonSenzaRisorsaSubtitlesRestituisceListaVuota() {
    val addon = InstalledAddon(
      baseUrl = "https://addon.test",
      manifest = StremioManifest(id = "addon.a", resources = listOf("stream"), types = listOf(TYPE_MOVIE))
    )

    // Il manifest non espone `subtitles`: la funzione torna indietro senza fare richieste,
    // quindi il test resta deterministico e offline.
    val subtitles = kotlinx.coroutines.runBlocking {
      StremioAddonRepository.fetchSubtitles(addon, TYPE_MOVIE, "tt1")
    }

    assertTrue(subtitles.isEmpty())
  }

  @Test
  fun idOrTipoVuotoRestituisceListaVuota() {
    val addon = InstalledAddon(
      baseUrl = "https://addon.test",
      manifest = StremioManifest(id = "addon.a", resources = listOf("subtitles"), types = listOf(TYPE_MOVIE))
    )

    val subtitles = kotlinx.coroutines.runBlocking {
      StremioAddonRepository.fetchSubtitles(addon, TYPE_MOVIE, "  ")
    }

    assertTrue(subtitles.isEmpty())
  }
}
