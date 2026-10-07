package com.example.data.stremio

import com.example.domain.model.Subtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test dell'adapter `StremioSubtitleEntry` -> `Subtitle`.
 *
 * L'adapter e' codice puro: nessuna rete, nessun client HTTP, nessun Android. I test
 * coprono il mapping completo, la provenienza STREAM/ENDPOINT, la robustezza sugli
 * elementi non utilizzabili e la conservazione dell'ordine.
 */
class StremioSubtitleAdapterTest {

  private fun entry(
    id: String,
    url: String,
    lang: String,
    label: String? = null,
    source: StremioSubtitleSource = StremioSubtitleSource.STREAM
  ) = StremioSubtitleEntry(id = id, url = url, lang = lang, label = label, source = source)

  // ── Mapping completo ─────────────────────────────────────────────────────────

  @Test
  fun mappingCompleto() {
    val subtitle = StremioSubtitleAdapter.toSubtitle(
      entry = entry("sub-1", "https://sub.test/ita.srt", "ita", "Italiano"),
      addonName = "OpenSubtitles",
      addonLogo = "https://addon.test/logo.png"
    )

    assertEquals("sub-1", subtitle?.id)
    assertEquals("https://sub.test/ita.srt", subtitle?.url)
    assertEquals("ita", subtitle?.lang)
    assertEquals("Italiano", subtitle?.label)
    assertEquals("OpenSubtitles", subtitle?.addonName)
    assertEquals("https://addon.test/logo.png", subtitle?.addonLogo)
    assertTrue(subtitle?.isStreamProvided == true)
  }

  @Test
  fun mappingCompletoDalBridge() {
    // Il flusso reale: bridge -> adapter, senza rete.
    val entries = StremioSubtitleBridge.merge(
      stream = listOf(
        StremioSubtitle(id = "s1", url = "https://sub.test/ita.srt", lang = "ITA", label = "Italiano")
      ),
      endpoint = listOf(StremioSubtitle(id = "e1", url = "https://sub.test/eng.srt", lang = "eng"))
    )

    val subtitles = StremioSubtitleAdapter.toSubtitles(entries, addonName = "Addon")

    assertEquals(2, subtitles.size)
    assertEquals(listOf("s1", "e1"), subtitles.map { it.id })
    assertEquals(listOf("ita", "eng"), subtitles.map { it.lang })
    assertEquals(listOf(true, false), subtitles.map { it.isStreamProvided })
  }

  // ── Lingua ──────────────────────────────────────────────────────────────────

  @Test
  fun linguaPreservata() {
    val subtitle = StremioSubtitleAdapter.toSubtitle(
      entry("s1", "https://sub.test/x.srt", "por-BR"),
      addonName = "Addon"
    )

    assertEquals("por-BR", subtitle?.lang)
  }

  @Test
  fun linguaNonRielaborataDalBridge() {
    // L'adapter non reinterpreti la lingua: il bridge l'ha gia' normalizzata.
    val entries = StremioSubtitleBridge.fromEndpoint(
      listOf(StremioSubtitle(id = "e1", url = "https://sub.test/x.srt", lang = "ITA"))
    )

    assertEquals("ita", StremioSubtitleAdapter.toSubtitles(entries, "Addon").single().lang)
  }

  @Test
  fun linguaSconosciutaRestaUnd() {
    val subtitle = StremioSubtitleAdapter.toSubtitle(
      entry("s1", "https://sub.test/x.srt", StremioSubtitle.LANG_UNKNOWN),
      addonName = "Addon"
    )

    assertEquals(StremioSubtitle.LANG_UNKNOWN, subtitle?.lang)
  }

  // ── Label ───────────────────────────────────────────────────────────────────

  @Test
  fun labelPreservata() {
    val subtitle = StremioSubtitleAdapter.toSubtitle(
      entry("s1", "https://sub.test/x.srt", "ita", "Commento del regista"),
      addonName = "Addon"
    )

    assertEquals("Commento del regista", subtitle?.label)
  }

  @Test
  fun labelAssenteRestaNull() {
    val subtitle = StremioSubtitleAdapter.toSubtitle(
      entry("s1", "https://sub.test/x.srt", "ita"),
      addonName = "Addon"
    )

    assertNull(subtitle?.label)
  }

  @Test
  fun labelVuotaRestaNull() {
    val entries = StremioSubtitleBridge.fromStream(
      StremioStreamItem(
        subtitles = listOf(
          StremioSubtitle(id = "s1", url = "https://sub.test/x.srt", lang = "ita", label = "   ")
        )
      )
    )

    assertNull(StremioSubtitleAdapter.toSubtitles(entries, "Addon").single().label)
  }

  // ── URL ─────────────────────────────────────────────────────────────────────

  @Test
  fun urlPreservata() {
    val url = "https://sub.test/path/my%20file.srt?token=abc"

    val subtitle = StremioSubtitleAdapter.toSubtitle(entry("s1", url, "ita"), addonName = "Addon")

    assertEquals(url, subtitle?.url)
  }

  @Test
  fun urlRipulitaDagliSpaziEsterni() {
    val entries = StremioSubtitleBridge.fromEndpoint(
      listOf(StremioSubtitle(id = "e1", url = "  https://sub.test/x.srt  ", lang = "ita"))
    )

    assertEquals("https://sub.test/x.srt", StremioSubtitleAdapter.toSubtitles(entries, "Addon").single().url)
  }

  // ── Id ──────────────────────────────────────────────────────────────────────

  @Test
  fun idPreservato() {
    val subtitle = StremioSubtitleAdapter.toSubtitle(
      entry("tt0111161-s1-e1", "https://sub.test/x.srt", "ita"),
      addonName = "Addon"
    )

    assertEquals("tt0111161-s1-e1", subtitle?.id)
  }

  @Test
  fun idRipulitoDagliSpaziEsterni() {
    val subtitle = StremioSubtitleAdapter.toSubtitle(
      entry("  s1  ", "https://sub.test/x.srt", "ita"),
      addonName = "Addon"
    )

    assertEquals("s1", subtitle?.id)
  }

  // ── Provenienza ─────────────────────────────────────────────────────────────

  @Test
  fun sourceStreamDiventaIsStreamProvidedTrue() {
    val subtitle = StremioSubtitleAdapter.toSubtitle(
      entry("s1", "https://sub.test/x.srt", "ita", source = StremioSubtitleSource.STREAM),
      addonName = "Addon"
    )

    assertTrue(subtitle?.isStreamProvided == true)
  }

  @Test
  fun sourceEndpointDiventaIsStreamProvidedFalse() {
    val subtitle = StremioSubtitleAdapter.toSubtitle(
      entry("e1", "https://sub.test/x.srt", "ita", source = StremioSubtitleSource.ENDPOINT),
      addonName = "Addon"
    )

    assertFalse(subtitle?.isStreamProvided == true)
  }

  @Test
  fun provenienzaDiversaRimaneDistinguibile() {
    val entries = listOf(
      entry("x", "https://sub.test/ita.srt", "ita", "Italiano", StremioSubtitleSource.STREAM),
      entry("x", "https://sub.test/eng.srt", "eng", "English", StremioSubtitleSource.ENDPOINT)
    )

    val subtitles = StremioSubtitleAdapter.toSubtitles(entries, "Addon")

    assertTrue(subtitles[0].isStreamProvided)
    assertFalse(subtitles[1].isStreamProvided)
  }

  // ── Elemento non utilizzabile ───────────────────────────────────────────────

  @Test
  fun elementoSenzaUrlVieneScartato() {
    assertNull(
      StremioSubtitleAdapter.toSubtitle(entry("s1", "   ", "ita"), addonName = "Addon")
    )
  }

  @Test
  fun elementoSenzaIdVieneScartato() {
    assertNull(
      StremioSubtitleAdapter.toSubtitle(entry("  ", "https://sub.test/x.srt", "ita"), addonName = "Addon")
    )
  }

  @Test
  fun listaConElementoInvalidoScartaSoloQuello() {
    val entries = listOf(
      entry("ok1", "https://sub.test/a.srt", "ita"),
      entry("", "https://sub.test/b.srt", "ita"),
      entry("ok2", "https://sub.test/c.srt", "eng")
    )

    val subtitles = StremioSubtitleAdapter.toSubtitles(entries, "Addon")

    assertEquals(2, subtitles.size)
    assertEquals(listOf("ok1", "ok2"), subtitles.map { it.id })
  }

  // ── Lista vuota ─────────────────────────────────────────────────────────────

  @Test
  fun listaVuota() {
    assertTrue(StremioSubtitleAdapter.toSubtitles(emptyList(), addonName = "Addon").isEmpty())
  }

  @Test
  fun listaVuotaDalBridgeSenzaSottotitoli() {
    assertTrue(StremioSubtitleAdapter.toSubtitles(StremioSubtitleBridge.merge(), "Addon").isEmpty())
  }

  // ── Ordine ──────────────────────────────────────────────────────────────────

  @Test
  fun ordinePreservato() {
    val entries = listOf(
      entry("1", "https://sub.test/1.srt", "ita"),
      entry("2", "https://sub.test/2.srt", "eng"),
      entry("3", "https://sub.test/3.srt", "spa")
    )

    assertEquals(listOf("1", "2", "3"), StremioSubtitleAdapter.toSubtitles(entries, "Addon").map { it.id })
  }

  @Test
  fun ordineBridgePrimaPerProvenienza() {
    val entries = StremioSubtitleBridge.merge(
      stream = listOf(
        StremioSubtitle(id = "s1", url = "https://sub.test/ita.srt", lang = "ita"),
        StremioSubtitle(id = "s2", url = "https://sub.test/ita2.srt", lang = "ita")
      ),
      endpoint = listOf(StremioSubtitle(id = "e1", url = "https://sub.test/eng.srt", lang = "eng"))
    )

    assertEquals(
      listOf("s1", "s2", "e1"),
      StremioSubtitleAdapter.toSubtitles(entries, "Addon").map { it.id }
    )
  }

  // ── Modello interno ─────────────────────────────────────────────────────────

  @Test
  fun ilRisultatoEUnSubtitleDelModelloInterno() {
    val subtitles: List<Subtitle> = StremioSubtitleAdapter.toSubtitles(
      listOf(entry("s1", "https://sub.test/x.srt", "ita")),
      addonName = "Addon"
    )

    // Il modello interno, non un tipo parallelo: la UI gia' sa mostrarlo.
    assertEquals("Italiano", subtitles.single().getDisplayLanguage())
  }
}
