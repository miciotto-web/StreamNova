package com.example.data.stremio

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test del campo ufficiale `stream.subtitles[]` (fase S1.2).
 *
 * E' una SORGENTE distinta da `/subtitles/{type}/{id}.json`: qui i sottotitoli arrivano
 * gia' abbinati allo stream restituito dall'addon, e devono convivere con gli altri
 * campi dello stream senza degradarne il parsing.
 *
 * Nessuna rete, nessun Android, nessun Compose: parsing via Moshi come in produzione.
 */
class StremioStreamSubtitlesTest {

  private val moshi: Moshi = Moshi.Builder()
    .add(KotlinJsonAdapterFactory())
    .build()

  private val adapter = moshi.adapter(StremioStreamResponse::class.java)

  /** Parsing difensivo: un corpo non valido produce `null`, come in produzione. */
  private fun parseStream(body: String): StremioStreamItem? = try {
    adapter.fromJson(body)?.streams?.firstOrNull()
  } catch (e: Exception) {
    null
  }

  private fun streamWithSubtitles(vararg rawItems: String): StremioStreamItem? {
    val items = if (rawItems.isEmpty()) "" else rawItems.joinToString(",")
    return parseStream("""{ "streams": [ { "name": "1080p", "title": "Movie", $items } ] }""")
  }

  // ── Stream senza subtitles ──────────────────────────────────────────────────

  @Test
  fun streamSenzaSubtitlesRestituisceListaVuota() {
    val stream = parseStream("""{ "streams": [ { "name": "1080p", "title": "Movie", "url": "https://v.test/a.mkv" } ] }""")

    assertTrue(stream != null)
    assertTrue(stream!!.subtitleList.isEmpty())
    assertFalse(stream.hasSubtitles)
  }

  @Test
  fun subtitlesNullRestituisceListaVuota() {
    val stream = parseStream("""{ "streams": [ { "name": "1080p", "subtitles": null } ] }""")

    assertTrue(stream!!.subtitleList.isEmpty())
  }

  @Test
  fun subtitlesVuotiRestituisconoListaVuota() {
    val stream = parseStream("""{ "streams": [ { "name": "1080p", "subtitles": [] } ] }""")

    assertTrue(stream!!.subtitleList.isEmpty())
    assertFalse(stream.hasSubtitles)
  }

  @Test
  fun streamSenzaCampoSubtitlesNonRompeGliAltriCampi() {
    val stream = parseStream(
      """{ "streams": [ { "name": "1080p", "title": "Dune", "url": "https://v.test/a.mkv", "fileIdx": 2 } ] }"""
    )

    assertEquals("1080p • Dune", stream!!.label)
    assertEquals("https://v.test/a.mkv", stream.url)
    assertEquals(2, stream.fileIdx)
    assertTrue(stream.subtitleList.isEmpty())
  }

  // ── Subtitle valido ──────────────────────────────────────────────────────────

  @Test
  fun subtitleValidoVieneLetto() {
    val stream = streamWithSubtitles(
      """"subtitles": [ { "id": "sub1", "url": "https://sub.test/ita.srt", "lang": "ita", "label": "Italiano" } ]"""
    )

    val subtitle = stream!!.subtitleList.single()
    assertEquals("sub1", subtitle.effectiveId)
    assertEquals("https://sub.test/ita.srt", subtitle.effectiveUrl)
    assertEquals("ita", subtitle.normalizedLang)
    assertEquals("Italiano", subtitle.displayLabel)
    assertTrue(stream.hasSubtitles)
  }

  @Test
  fun subtitleSenzaLabelUsaLaLingua() {
    val stream = streamWithSubtitles(
      """"subtitles": [ { "id": "sub1", "url": "https://sub.test/eng.srt", "lang": "eng" } ]"""
    )

    assertEquals("eng", stream!!.subtitleList.single().displayLabel)
  }

  // ── Più subtitles ────────────────────────────────────────────────────────────

  @Test
  fun piuSubtitlesVengonoConservatiNellOrdineDichiarato() {
    val stream = streamWithSubtitles(
      """"subtitles": [
        { "id": "1", "url": "https://sub.test/ita.srt", "lang": "ita" },
        { "id": "2", "url": "https://sub.test/eng.srt", "lang": "eng" },
        { "id": "3", "url": "https://sub.test/spa.srt", "lang": "spa" }
      ]"""
    )

    val subtitles = stream!!.subtitleList
    assertEquals(3, subtitles.size)
    assertEquals(listOf("ita", "eng", "spa"), subtitles.map { it.normalizedLang })
  }

  // ── Elementi incompleti o null ──────────────────────────────────────────────

  @Test
  fun elementoSenzaUrlVieneScartatoGliAltriRestano() {
    val stream = streamWithSubtitles(
      """"subtitles": [
        { "id": "no-url", "lang": "ita" },
        { "id": "ok", "url": "https://sub.test/ita.srt", "lang": "ita" }
      ]"""
    )

    assertEquals(1, stream!!.subtitleList.size)
    assertEquals("ok", stream.subtitleList.single().effectiveId)
  }

  @Test
  fun elementoSenzaLinguaOIdVieneScartato() {
    val stream = streamWithSubtitles(
      """"subtitles": [
        { "url": "https://sub.test/a.srt" },
        { "id": "no-lang", "url": "https://sub.test/b.srt" }
      ]"""
    )

    assertTrue(stream!!.subtitleList.isEmpty())
  }

  @Test
  fun elementoNullNelLinguaggioNonCauseCrash() {
    val stream = streamWithSubtitles(
      """"subtitles": [
        null,
        { "id": "ok", "url": "https://sub.test/ita.srt", "lang": "ita" }
      ]"""
    )

    // Il resto dello stream resta leggibile anche con un elemento null.
    assertEquals("1080p • Movie", stream!!.label)
    assertEquals(1, stream.subtitleList.size)
  }

  @Test
  fun campiBlankVengonoScartati() {
    val stream = streamWithSubtitles(
      """"subtitles": [
        { "id": "", "url": "https://sub.test/a.srt", "lang": "ita" },
        { "id": "vuoto", "url": "  ", "lang": "ita" },
        { "id": "vuoto2", "url": "https://sub.test/b.srt", "lang": "" },
        { "id": "ok", "url": "https://sub.test/c.srt", "lang": "eng" }
      ]"""
    )

    assertEquals(1, stream!!.subtitleList.size)
    assertEquals("ok", stream.subtitleList.single().effectiveId)
  }

  // ── JSON / stream non valido ─────────────────────────────────────────────────

  @Test
  fun jsonNonValidoNonCauseCrash() {
    assertEquals(null, parseStream("non è json"))
    assertEquals(null, parseStream("{"))
    assertEquals(null, parseStream(""))
    assertEquals(null, parseStream("""{ "streams": [ { "name": } ] }"""))
    assertEquals(null, parseStream("""[1, 2, 3]"""))
  }

  @Test
  fun streamsAssenteNonCauseCrash() {
    val response = adapter.fromJson("{}")

    assertTrue(response?.streams.isNullOrEmpty())
  }

  // ── Le due sorgenti restano separate ─────────────────────────────────────────

  @Test
  fun leDueSorgentiRestanoIndipendenti() {
    val stream = streamWithSubtitles(
      """"subtitles": [ { "id": "inline", "url": "https://sub.test/ita.srt", "lang": "ita" } ]"""
    )

    // 1) Sorgente stream.subtitles[]: letta dal modello dello stream.
    assertEquals(1, stream!!.subtitleList.size)
    assertEquals("inline", stream.subtitleList.single().effectiveId)

    // 2) Sorgente /subtitles/...: letta dal parser dedicato, indipendente.
    val endpoint = StremioSubtitleParser.parse(
      """{ "subtitles": [ { "id": "endpoint", "url": "https://sub.test/eng.srt", "lang": "eng" } ] }"""
    )
    assertEquals(1, endpoint.size)
    assertEquals("endpoint", endpoint.single().effectiveId)

    // Lo stesso modello StremioSubtitle, due origini diverse.
    assertTrue(stream.subtitleList.single() is StremioSubtitle)
    assertTrue(endpoint.single() is StremioSubtitle)
  }

  @Test
  fun ilFiltroUsabileEcondivisoDaiDueParser() {
    val raw = listOf(
      StremioSubtitle(id = "ok", url = "https://sub.test/a.srt", lang = "ita"),
      StremioSubtitle(id = "no-url", lang = "ita"),
      null
    )

    val usable = StremioSubtitleParser.usable(raw)

    assertEquals(1, usable.size)
    assertEquals("ok", usable.single().effectiveId)
    assertTrue(StremioSubtitleParser.usable(null).isEmpty())
  }
}
