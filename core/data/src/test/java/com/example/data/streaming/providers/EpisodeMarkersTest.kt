package com.example.data.streaming.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Riconoscimento dei marker di episodio nei formati usati dai cataloghi
 * italiani (CB01, Eurostreaming) e dai player embed.
 */
class EpisodeMarkersTest {

  @Test
  fun riconosceTuttiIFormatiSxxExx() {
    listOf(
      "S01E03",
      "S1E3",
      "s01e03",
      "S01.E03",
      "S01-E03",
      "S01 E03",
      "S01_E03"
    ).forEach { marker ->
      assertNotNull("marker non riconosciuto: $marker", EpisodeMarkers.findRequested(marker, 1, 3))
    }
  }

  @Test
  fun riconosceFormatiConXEMoltiplicazione() {
    listOf("1x03", "1X03", "1×03", "01x003", "1 x 3").forEach { marker ->
      assertNotNull("marker non riconosciuto: $marker", EpisodeMarkers.findRequested(marker, 1, 3))
    }
  }

  @Test
  fun riconosceFormatoItalianoStagioneEpisodio() {
    listOf(
      "Stagione 1 Episodio 3",
      "STAGIONE 01 - EPISODIO 03",
      "Season 1 Episode 3"
    ).forEach { marker ->
      assertNotNull("marker non riconosciuto: $marker", EpisodeMarkers.findRequested(marker, 1, 3))
    }
  }

  @Test
  fun nonConfondeUnEpisodioConUnAltro() {
    assertNull(EpisodeMarkers.findRequested("S01E02", 1, 3))
    assertNull(EpisodeMarkers.findRequested("1x13", 1, 3))
    assertNull(EpisodeMarkers.findRequested("S02E03", 1, 3))
    // L'episodio 30 non deve soddisfare la richiesta dell'episodio 3.
    assertNull(EpisodeMarkers.findRequested("S01E030", 1, 3))
  }

  @Test
  fun leRisoluzioniVideoNonSonoEpisodi() {
    listOf("1920x1080", "1280x720", "720x480", "720 x 480", "3840×2160").forEach { resolution ->
      assertTrue("risoluzione non riconosciuta: $resolution", EpisodeMarkers.isResolution(resolution))
      assertNull("risoluzione scambiata per episodio: $resolution", EpisodeMarkers.findRequested(resolution, 1, 2))
    }
  }

  @Test
  fun segmentoUsaIlFormatoSuccessivoAncheSeDiverso() {
    val html = """
      <h3>S01.E02</h3>
      <a href="https://maxstream.video/e/A">MaxStream</a>
      <h3>1x03</h3>
      <a href="https://maxstream.video/e/B">MaxStream</a>
      <h3>Stagione 1 Episodio 4</h3>
      <a href="https://maxstream.video/e/C">MaxStream</a>
    """.trimIndent()

    val segment = EpisodeMarkers.segment(html, 1, 2).orEmpty()
    assertTrue(segment.contains("/A"))
    assertFalse("il marker successivo deve chiudere il segmento", segment.contains("/B"))
    assertFalse(segment.contains("/C"))
  }

  @Test
  fun segmentoIsoaltaSoloLEpisodioRichiesto() {
    val html = """
      1×01 <a href="https://maxstream.video/e/ONE">MaxStream</a>
      1×03 <a href="https://maxstream.video/e/THREE">MaxStream</a>
      1×04 <a href="https://maxstream.video/e/FOUR">MaxStream</a>
    """.trimIndent()

    val segment = EpisodeMarkers.segment(html, 1, 3).orEmpty()
    assertTrue(segment.contains("/THREE"))
    assertFalse(segment.contains("/ONE"))
    assertFalse(segment.contains("/FOUR"))
  }

  @Test
  fun markerAssenteRestituisceNull() {
    assertNull(EpisodeMarkers.segment("<p>1x01</p>", 2, 5))
  }

  @Test
  fun stagioneConPiuCifreRestaEsatta() {
    assertEquals("12", EpisodeMarkers.findRequested("S12E03", 12, 3)?.let { "12" })
    assertNull(EpisodeMarkers.findRequested("S1E03", 12, 3))
    assertNull(EpisodeMarkers.findRequested("S122E03", 12, 3))
  }
}
