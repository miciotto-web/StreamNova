package com.example.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.stremio.StremioSubtitle
import com.example.data.stremio.StremioSubtitleBridge
import com.example.domain.model.Subtitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test della trasformazione `Subtitle` -> `MediaItem.SubtitleConfiguration`.
 *
 * Copre il mapping completo richiesto da Media3 (id, uri, lingua, label, MIME) e la
 * composizione del MediaItem. `Uri.parse` richiede Android, quindi il test gira con
 * AndroidJUnit4.
 */
@RunWith(AndroidJUnit4::class)
class PlayerSubtitleMediaItemBuilderTest {

  private fun subtitle(
    id: String = "sub-1",
    url: String = "https://sub.test/ita.srt",
    lang: String = "ita",
    label: String? = null,
    isStreamProvided: Boolean = true
  ) = Subtitle(
    id = id,
    url = url,
    lang = lang,
    addonName = "Addon",
    isStreamProvided = isStreamProvided,
    label = label
  )

  // ── Mapping di un singolo sottotitolo ───────────────────────────────────────

  @Test
  fun mappingCompleto() {
    val spec = PlayerSubtitleMediaItemBuilder.toSpec(
      subtitle(id = "tt0111161-ita", url = "https://sub.test/it.srt", lang = "ita", label = "Italiano")
    )!!

    assertEquals("tt0111161-ita", spec.id)
    assertEquals("https://sub.test/it.srt", spec.url)
    // La lingua viene normalizzata in forma BCP-47 minuscola dal builder.
    assertEquals("it", spec.language)
    assertEquals("Italiano", spec.label)
    assertEquals(MimeTypes.APPLICATION_SUBRIP, spec.mimeType)
  }

  @Test
  fun idStremioNonVienePerso() {
    val id = "opensubtitles-id-42"

    val spec = PlayerSubtitleMediaItemBuilder.toSpec(subtitle(id = id))!!

    assertEquals(id, spec.id)
  }

  @Test
  fun urlPreservata() {
    val url = "https://sub.test/path/my%20file.srt?token=abc"

    assertEquals(url, PlayerSubtitleMediaItemBuilder.toSpec(subtitle(url = url))?.url)
  }

  @Test
  fun linguaPreservata() {
    // Codice normalizzato (minuscolo + separatore '-'), non il valore grezzo dell'addon.
    assertEquals("por-br", PlayerSubtitleMediaItemBuilder.toSpec(subtitle(lang = "por-BR"))?.language)
    assertEquals("pt-br", PlayerSubtitleMediaItemBuilder.toSpec(subtitle(lang = "PT-BR"))?.language)
    assertEquals("und", PlayerSubtitleMediaItemBuilder.toSpec(subtitle(lang = "und"))?.language)
  }

  @Test
  fun labelPreservata() {
    assertEquals(
      "Commento del regista",
      PlayerSubtitleMediaItemBuilder.toSpec(subtitle(label = "Commento del regista"))?.label
    )
  }

  @Test
  fun labelAssenteRestaNull() {
    assertNull(PlayerSubtitleMediaItemBuilder.toSpec(subtitle(label = null))?.label)
  }

  @Test
  fun labelVuotaRestaNull() {
    assertNull(PlayerSubtitleMediaItemBuilder.toSpec(subtitle(label = "   "))?.label)
  }

  // ── MIME type ───────────────────────────────────────────────────────────────

  @Test
  fun mimeRiusaIlRiconoscimentoEsistente() {
    assertEquals(
      MimeTypes.APPLICATION_SUBRIP,
      PlayerSubtitleMediaItemBuilder.toSpec(subtitle(url = "https://a.test/x.srt"))?.mimeType
    )
    assertEquals(
      MimeTypes.TEXT_VTT,
      PlayerSubtitleMediaItemBuilder.toSpec(subtitle(url = "https://a.test/x.vtt"))?.mimeType
    )
    assertEquals(
      MimeTypes.TEXT_SSA,
      PlayerSubtitleMediaItemBuilder.toSpec(subtitle(url = "https://a.test/x.ass"))?.mimeType
    )
    assertEquals(
      MimeTypes.APPLICATION_TTML,
      PlayerSubtitleMediaItemBuilder.toSpec(subtitle(url = "https://a.test/x.ttml"))?.mimeType
    )
  }

  @Test
  fun mimeDiDefaultQuandoEstensioneSconosciuta() {
    assertEquals(
      MimeTypes.APPLICATION_SUBRIP,
      PlayerSubtitleMediaItemBuilder.toSpec(subtitle(url = "https://a.test/download?id=7"))?.mimeType
    )
  }

  @Test
  fun mimeIgnoraQueryString() {
    assertEquals(
      MimeTypes.APPLICATION_SUBRIP,
      PlayerSubtitleMediaItemBuilder.toSpec(subtitle(url = "https://a.test/x.srt?v=2&f=y.vtt"))?.mimeType
    )
  }

  // ── Più sottotitoli ─────────────────────────────────────────────────────────

  @Test
  fun piuSottotitoli() {
    val specs = PlayerSubtitleMediaItemBuilder.toSpecs(
      listOf(
        subtitle(id = "s1", url = "https://a.test/ita.srt", lang = "ita"),
        subtitle(id = "s2", url = "https://a.test/eng.vtt", lang = "eng", label = "English"),
        subtitle(id = "s3", url = "https://a.test/spa.ass", lang = "spa")
      )
    )

    assertEquals(3, specs.size)
    assertEquals(listOf("s1", "s2", "s3"), specs.map { it.id })
    assertEquals(
      listOf(MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_VTT, MimeTypes.TEXT_SSA),
      specs.map { it.mimeType }
    )
  }

  @Test
  fun ordinePreservato() {
    val specs = PlayerSubtitleMediaItemBuilder.toSpecs(
      (1..5).map { subtitle(id = "$it", url = "https://a.test/$it.srt") }
    )

    assertEquals(listOf("1", "2", "3", "4", "5"), specs.map { it.id })
  }

  // ── Lista vuota / elementi non utilizzabili ─────────────────────────────────

  @Test
  fun listaVuota() {
    assertTrue(PlayerSubtitleMediaItemBuilder.toSpecs(emptyList()).isEmpty())
    assertTrue(PlayerSubtitleMediaItemBuilder.toConfigurations(emptyList()).isEmpty())
  }

  @Test
  fun elementoSenzaUrlVieneScartato() {
    assertNull(PlayerSubtitleMediaItemBuilder.toSpec(subtitle(url = "   ")))
  }

  @Test
  fun listaConElementoInvalidoScartaSoloQuello() {
    val specs = PlayerSubtitleMediaItemBuilder.toSpecs(
      listOf(
        subtitle(id = "ok1", url = "https://a.test/a.srt"),
        subtitle(id = "", url = "   "),
        subtitle(id = "ok2", url = "https://a.test/b.vtt")
      )
    )

    assertEquals(listOf("ok1", "ok2"), specs.map { it.id })
  }

  // ── SubtitleConfiguration ───────────────────────────────────────────────────

  @Test
  fun configurazioneRiportaIdUrlLinguaLabelEMime() {
    val configuration = PlayerSubtitleMediaItemBuilder.toConfigurations(
      listOf(subtitle(id = "sub-9", url = "https://a.test/x.vtt", lang = "ita", label = "Italiano"))
    ).single()

    assertEquals("sub-9", configuration.id)
    assertEquals("https://a.test/x.vtt", configuration.uri.toString())
    assertEquals("it", configuration.language)
    assertEquals("Italiano", configuration.label)
    assertEquals(MimeTypes.TEXT_VTT, configuration.mimeType)
  }

  @Test
  fun configurazioniPiuSottotitoli() {
    val configurations = PlayerSubtitleMediaItemBuilder.toConfigurations(
      listOf(
        subtitle(id = "s1", url = "https://a.test/ita.srt", lang = "ita"),
        subtitle(id = "s2", url = "https://a.test/eng.vtt", lang = "eng")
      )
    )

    assertEquals(2, configurations.size)
    assertEquals("s1", configurations[0].id)
    assertEquals("s2", configurations[1].id)
  }

  // ── MediaItem ───────────────────────────────────────────────────────────────

  @Test
  fun mediaItemSenzaSottotitoliNonHaConfigurazioni() {
    val mediaItem = PlayerSubtitleMediaItemBuilder.buildMediaItem("https://v.test/movie.mkv", emptyList())

    assertEquals("https://v.test/movie.mkv", mediaItem.localConfiguration?.uri.toString())
    assertTrue(mediaItem.localConfiguration?.subtitleConfigurations.isNullOrEmpty())
  }

  @Test
  fun mediaItemConSottotitoliEsponeLeConfigurazioni() {
    val mediaItem = PlayerSubtitleMediaItemBuilder.buildMediaItem(
      "https://v.test/movie.mkv",
      listOf(
        subtitle(id = "s1", url = "https://a.test/ita.srt", lang = "ita"),
        subtitle(id = "s2", url = "https://a.test/eng.vtt", lang = "eng")
      )
    )

    val local = mediaItem.localConfiguration!!
    assertEquals("https://v.test/movie.mkv", local.uri.toString())
    assertEquals(2, local.subtitleConfigurations.size)
    assertEquals(listOf("s1", "s2"), local.subtitleConfigurations.map { it.id })
  }

  /**
   * Le tracce embedded del video non vivono nel MediaItem: sono prodotte dal
   * MediaSource. Quindi l'unica cosa che il MediaItem deve garantire e' che la URI
   * video resti intatta: i sottotitoli si aggiungono, non sostituiscono.
   */
  @Test
  fun preservazioneUriVideoConSottotitoliStremio() {
    val mediaItem: MediaItem = PlayerSubtitleMediaItemBuilder.buildMediaItem(
      "https://v.test/movie.mkv",
      listOf(subtitle(id = "s1", url = "https://a.test/ita.srt", lang = "ita"))
    )

    assertEquals("https://v.test/movie.mkv", mediaItem.localConfiguration?.uri.toString())
    assertEquals(1, mediaItem.localConfiguration?.subtitleConfigurations?.size)
  }

  // ── Flusso reale: bridge + adapter -> builder ───────────────────────────────

  @Test
  fun flussoCompletoDaBridge() {
    val entries = StremioSubtitleBridge.merge(
      stream = listOf(StremioSubtitle(id = "s1", url = "https://a.test/ita.srt", lang = "ITA", label = "Italiano")),
      endpoint = listOf(StremioSubtitle(id = "e1", url = "https://a.test/eng.vtt", lang = "eng"))
    )
    val subtitles = com.example.data.stremio.StremioSubtitleAdapter.toSubtitles(entries, "Addon")

    val mediaItem = PlayerSubtitleMediaItemBuilder.buildMediaItem("https://v.test/movie.mkv", subtitles)
    val configurations = mediaItem.localConfiguration!!.subtitleConfigurations

    assertEquals(2, configurations.size)
    assertEquals("s1", configurations[0].id)
    assertEquals("it", configurations[0].language)
    assertEquals("Italiano", configurations[0].label)
    assertEquals(MimeTypes.APPLICATION_SUBRIP, configurations[0].mimeType)
    assertEquals("e1", configurations[1].id)
    assertEquals(MimeTypes.TEXT_VTT, configurations[1].mimeType)
  }

  // ── OpenSubtitles v3: precedenza, SDH e MIME ────────────────────────────────

  @Test
  fun precedenzaEmbeddedStremioPrimaDiOpenSubtitles() {
    val embeddedStremio = Subtitle(
      id = "emb-1",
      url = "https://strem.io/emb.srt",
      lang = "ita",
      addonName = "IlCorsaroViola",
      isStreamProvided = true
    )
    val openSubtitles = Subtitle(
      id = "os-2001",
      url = "https://sub.opensubtitles.com/os.srt",
      lang = "ita",
      addonName = "OpenSubtitles v3",
      isStreamProvided = false
    )
    val stremioEndpoint = Subtitle(
      id = "end-2",
      url = "https://strem.io/end.vtt",
      lang = "eng",
      addonName = "OfficialAddon",
      isStreamProvided = false
    )

    // Passati in ordine arbitrario: OpenSubtitles per primo
    val input = listOf(openSubtitles, embeddedStremio, stremioEndpoint)
    val specs = PlayerSubtitleMediaItemBuilder.toSpecs(input)

    assertEquals(3, specs.size)
    // Precedenza verificata:
    // 1. embedded Stremio
    assertEquals("emb-1", specs[0].id)
    // 2. altro Stremio
    assertEquals("end-2", specs[1].id)
    // 3. OpenSubtitles come traccia aggiuntiva
    assertEquals("os-2001", specs[2].id)
  }

  @Test
  fun configurazioneSdhImpostaRoleFlagsMedia3() {
    val sdhSub = Subtitle(
      id = "os-sdh",
      url = "https://sub.test/it.srt",
      lang = "ita",
      addonName = "OpenSubtitles v3",
      label = "Italiano [SDH]"
    )
    val standardSub = Subtitle(
      id = "os-std",
      url = "https://sub.test/std.srt",
      lang = "ita",
      addonName = "OpenSubtitles v3",
      label = "Italiano"
    )

    val sdhSpec = PlayerSubtitleMediaItemBuilder.toSpec(sdhSub)!!
    val stdSpec = PlayerSubtitleMediaItemBuilder.toSpec(standardSub)!!

    // SDH deve includere i flag descrittivi audio e dialoghi per non udenti
    assertTrue((sdhSpec.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0)
    assertTrue((sdhSpec.roleFlags and C.ROLE_FLAG_TRANSCRIBES_DIALOG) != 0)

    // Standard non deve avere flag SDH
    assertEquals(0, stdSpec.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND)
    assertEquals(0, stdSpec.roleFlags and C.ROLE_FLAG_TRANSCRIBES_DIALOG)
  }

  @Test
  fun configurazioneForzatiImpostaSelectionFlags() {
    val forcedSub = Subtitle(
      id = "os-forced",
      url = "https://sub.test/forced.srt",
      lang = "ita",
      addonName = "OpenSubtitles v3",
      label = "Italiano (Forzato)"
    )
    val spec = PlayerSubtitleMediaItemBuilder.toSpec(forcedSub)!!

    assertTrue((spec.selectionFlags and C.SELECTION_FLAG_FORCED) != 0)
  }

  @Test
  fun openSubtitlesUrlSenzaEstensioneRilevaMimeCorrettoDaQueryOContesto() {
    // 1. WebVTT via query param
    val vttSub = Subtitle(
      id = "os-vtt",
      url = "https://api.opensubtitles.com/download/file/1234?sub_format=vtt",
      lang = "en",
      addonName = "OpenSubtitles v3"
    )
    assertEquals(MimeTypes.TEXT_VTT, PlayerSubtitleMediaItemBuilder.toSpec(vttSub)?.mimeType)

    // 2. WebVTT via label
    val vttLabelSub = Subtitle(
      id = "os-vtt-2",
      url = "https://api.opensubtitles.com/download/file/1234",
      lang = "en",
      addonName = "OpenSubtitles v3",
      label = "English_subs.vtt"
    )
    assertEquals(MimeTypes.TEXT_VTT, PlayerSubtitleMediaItemBuilder.toSpec(vttLabelSub)?.mimeType)

    // 3. Default a SubRip (.srt) per download generico OpenSubtitles
    val srtSub = Subtitle(
      id = "os-srt",
      url = "https://api.opensubtitles.com/download/file/5678",
      lang = "it",
      addonName = "OpenSubtitles v3"
    )
    assertEquals(MimeTypes.APPLICATION_SUBRIP, PlayerSubtitleMediaItemBuilder.toSpec(srtSub)?.mimeType)
  }
}
