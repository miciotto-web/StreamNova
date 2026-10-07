package com.example.data.stremio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test del bridge che unifica le due fonti di sottotitoli Stremio.
 *
 * Il bridge e' codice puro: nessuna rete, nessun client HTTP, nessun Android. I test
 * coprono merge, deduplificazione per URL normalizzato, provenienza e robustezza.
 */
class StremioSubtitleBridgeTest {

  private fun subtitle(id: String, url: String, lang: String, label: String? = null) =
    StremioSubtitle(id = id, url = url, lang = lang, label = label)

  private fun stream(vararg subtitles: StremioSubtitle?) =
    StremioStreamItem(name = "1080p", title = "Movie", subtitles = subtitles.toList())

  // ── Solo stream ──────────────────────────────────────────────────────────────

  @Test
  fun soloStream() {
    val entries = StremioSubtitleBridge.merge(
      stream = listOf(subtitle("s1", "https://sub.test/ita.srt", "ITA", "Italiano"))
    )

    val entry = entries.single()
    assertEquals("s1", entry.id)
    assertEquals("https://sub.test/ita.srt", entry.url)
    assertEquals("ita", entry.lang)
    assertEquals("Italiano", entry.label)
    assertEquals(StremioSubtitleSource.STREAM, entry.source)
    assertTrue(entry.isFromStream)
  }

  @Test
  fun soloStreamDaUnoStreamItem() {
    val entries = StremioSubtitleBridge.fromStream(
      stream(subtitle("s1", "https://sub.test/ita.srt", "ita"))
    )

    assertEquals(1, entries.size)
    assertEquals(StremioSubtitleSource.STREAM, entries.single().source)
  }

  @Test
  fun streamItemNullOssiaVuoto() {
    assertTrue(StremioSubtitleBridge.fromStream(null).isEmpty())
    assertTrue(StremioSubtitleBridge.fromStream(StremioStreamItem(name = "1080p")).isEmpty())
  }

  // ── Solo endpoint ────────────────────────────────────────────────────────────

  @Test
  fun soloEndpoint() {
    val entries = StremioSubtitleBridge.merge(
      endpoint = listOf(subtitle("e1", "https://sub.test/eng.srt", "eng", "English"))
    )

    val entry = entries.single()
    assertEquals("e1", entry.id)
    assertEquals("eng", entry.lang)
    assertEquals("English", entry.label)
    assertEquals(StremioSubtitleSource.ENDPOINT, entry.source)
    assertFalse(entry.isFromStream)
  }

  @Test
  fun soloEndpointDaListaDiEndpoint() {
    val entries = StremioSubtitleBridge.fromEndpoint(
      listOf(subtitle("e1", "https://sub.test/eng.srt", "eng"))
    )

    assertEquals(StremioSubtitleSource.ENDPOINT, entries.single().source)
  }

  // ── Entrambe le fonti: ordine STREAM -> ENDPOINT ──────────────────────────────

  @Test
  fun entrambeLeFontiVengonoCombinateInOrdineDiPriorita() {
    val entries = StremioSubtitleBridge.merge(
      stream = listOf(
        subtitle("s1", "https://sub.test/ita.srt", "ita"),
        subtitle("s2", "https://sub.test/ita-forced.srt", "ita")
      ),
      endpoint = listOf(
        subtitle("e1", "https://sub.test/eng.srt", "eng"),
        subtitle("e2", "https://sub.test/deu.srt", "deu")
      )
    )

    assertEquals(4, entries.size)
    assertEquals(
      listOf(
        "https://sub.test/ita.srt",
        "https://sub.test/ita-forced.srt",
        "https://sub.test/eng.srt",
        "https://sub.test/deu.srt"
      ),
      entries.map { it.url }
    )
    assertEquals(
      listOf(
        StremioSubtitleSource.STREAM,
        StremioSubtitleSource.STREAM,
        StremioSubtitleSource.ENDPOINT,
        StremioSubtitleSource.ENDPOINT
      ),
      entries.map { it.source }
    )
  }

  // ── Deduplicazione ───────────────────────────────────────────────────────────

  @Test
  fun stessoUrlDalleDueFontiRestaUnaVoceConProvenienzaStream() {
    val entries = StremioSubtitleBridge.merge(
      stream = listOf(subtitle("s1", "https://sub.test/ita.srt", "ita", "Italiano (stream)")),
      endpoint = listOf(subtitle("e1", "https://sub.test/ita.srt", "ita", "Italiano (endpoint)"))
    )

    val entry = entries.single()
    assertEquals(1, entries.size)
    assertEquals(StremioSubtitleSource.STREAM, entry.source)
    assertEquals("s1", entry.id)
    assertEquals("Italiano (stream)", entry.label)
  }

  @Test
  fun stessoUrlNdentroLaStessaFonteVieneDeduplicato() {
    val entries = StremioSubtitleBridge.merge(
      stream = listOf(
        subtitle("s1", "https://sub.test/ita.srt", "ita"),
        subtitle("s2", "https://sub.test/ita.srt", "ita")
      )
    )

    assertEquals(1, entries.size)
  }

  @Test
  fun stessaLinguaMaUrlDiversiRestanoEntrambi() {
    val entries = StremioSubtitleBridge.merge(
      stream = listOf(subtitle("s1", "https://sub.test/ita.srt", "ita")),
      endpoint = listOf(subtitle("e1", "https://sub.test/ita-forced.srt", "ita"))
    )

    assertEquals(2, entries.size)
    assertEquals(setOf("https://sub.test/ita.srt", "https://sub.test/ita-forced.srt"), entries.map { it.url }.toSet())
  }

  @Test
  fun deduplicaNonPerdeLInformazioneDellEtichetta() {
    val entries = StremioSubtitleBridge.merge(
      stream = listOf(subtitle("s1", "https://sub.test/ita.srt", "ita")),
      endpoint = listOf(subtitle("e1", "https://sub.test/ita.srt", "ita", "Italiano endpoint"))
    )

    val entry = entries.single()
    assertEquals("Italiano endpoint", entry.label)
    assertEquals(StremioSubtitleSource.STREAM, entry.source)
    assertEquals("ita", entry.lang)
  }

  @Test
  fun etichettaDelloStreamNonVieneSovrascritta() {
    val entries = StremioSubtitleBridge.merge(
      stream = listOf(subtitle("s1", "https://sub.test/ita.srt", "ita", "Etichetta stream")),
      endpoint = listOf(subtitle("e1", "https://sub.test/ita.srt", "ita", "Etichetta endpoint"))
    )

    assertEquals("Etichetta stream", entries.single().label)
  }

  @Test
  fun provenienzaCorrettaDopoDeduplica() {
    val entries = StremioSubtitleBridge.merge(
      stream = listOf(subtitle("s1", "https://sub.test/ita.srt", "ita")),
      endpoint = listOf(
        subtitle("e1", "https://sub.test/ita.srt", "ita"),
        subtitle("e2", "https://sub.test/spa.srt", "spa")
      )
    )

    assertEquals(StremioSubtitleSource.STREAM, entries[0].source)
    assertEquals(StremioSubtitleSource.ENDPOINT, entries[1].source)
    assertEquals("spa", entries[1].lang)
  }

  // ── Normalizzazione URL ──────────────────────────────────────────────────────

  @Test
  fun urlConSpaziVengonoNormalizzati() {
    assertEquals(
      "https://sub.test/my%20file.srt",
      StremioSubtitleBridge.normalizeUrl("  https://sub.test/my file.srt  ")
    )
    assertEquals(
      "https://sub.test/a%20b%20c.srt",
      StremioSubtitleBridge.normalizeUrl("https://sub.test/a  b   c.srt")
    )
  }

  @Test
  fun urlDifferentiPerSpaziVengonoDeduplicati() {
    val entries = StremioSubtitleBridge.merge(
      stream = listOf(subtitle("s1", " https://sub.test/my file.srt ", "ita")),
      endpoint = listOf(subtitle("e1", "https://sub.test/my%20file.srt", "ita"))
    )

    assertEquals(1, entries.size)
    assertEquals(StremioSubtitleSource.STREAM, entries.single().source)
    assertEquals("https://sub.test/my%20file.srt", entries.single().dedupeKey)
  }

  @Test
  fun urlNonVengonoLowercased() {
    // Due URL che differiscono solo per il case del percorso restano distinti.
    val entries = StremioSubtitleBridge.merge(
      stream = listOf(
        subtitle("s1", "https://sub.test/File.SRT", "ita"),
        subtitle("s2", "https://sub.test/file.srt", "ita")
      )
    )

    assertEquals(2, entries.size)
  }

  // ── Elementi invalidi ────────────────────────────────────────────────────────

  @Test
  fun elementiIncompletiVengonoScartati() {
    val entries = StremioSubtitleBridge.merge(
      stream = listOf(
        StremioSubtitle(id = "no-url", lang = "ita"),
        StremioSubtitle(id = "no-lang", url = "https://sub.test/a.srt"),
        StremioSubtitle(url = "https://sub.test/b.srt", lang = "ita"),
        subtitle("ok", "https://sub.test/ok.srt", "ita")
      ),
      endpoint = listOf(StremioSubtitle(id = "", url = "https://sub.test/c.srt", lang = ""))
    )

    assertEquals(1, entries.size)
    assertEquals("ok", entries.single().id)
  }

  @Test
  fun elementiNullVengonoScartati() {
    val entries = StremioSubtitleBridge.merge(
      stream = listOf(null, subtitle("ok", "https://sub.test/ok.srt", "ita")),
      endpoint = listOf(null)
    )

    assertEquals(1, entries.size)
    assertEquals("ok", entries.single().id)
  }

  // ── Liste vuote e null ───────────────────────────────────────────────────────

  @Test
  fun listeVuoteENull() {
    assertTrue(StremioSubtitleBridge.merge().isEmpty())
    assertTrue(StremioSubtitleBridge.merge(stream = null, endpoint = null).isEmpty())
    assertTrue(StremioSubtitleBridge.merge(stream = emptyList(), endpoint = emptyList()).isEmpty())
    assertTrue(StremioSubtitleBridge.fromEndpoint(null).isEmpty())
    assertTrue(StremioSubtitleBridge.fromStream(stream(null, null)).isEmpty())
  }

  @Test
  fun soloUnaFontePresente() {
    assertEquals(1, StremioSubtitleBridge.merge(stream = listOf(subtitle("s", "https://a.test/a.srt", "ita"))).size)
    assertEquals(1, StremioSubtitleBridge.merge(endpoint = listOf(subtitle("e", "https://a.test/a.srt", "ita"))).size)
  }

  // ── Il bridge non fa network e riusa la validazione esistente ────────────────

  @Test
  fun ilBridgeNonRichiedeClientHttp() {
    // Documentazione eseguibile: il bridge dipende solo dal modello/parser Stremio.
    val entries = StremioSubtitleBridge.merge(
      stream = StremioSubtitleParser.usable(
        listOf(StremioSubtitle(id = "1", url = "https://a.test/a.srt", lang = "ita"))
      )
    )

    assertEquals(1, entries.size)
    assertEquals(StremioSubtitleSource.STREAM, entries.single().source)
  }

  @Test
  fun labelAssenteRitornaAllaLingua() {
    val entries = StremioSubtitleBridge.merge(
      stream = listOf(subtitle("s1", "https://sub.test/ita.srt", "ita"))
    )

    assertEquals("ita", entries.single().displayLabel)
    assertEquals(null, entries.single().label)
  }
}