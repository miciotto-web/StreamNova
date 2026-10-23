package com.example.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.extractor.text.SubtitleDecoderException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.streaming.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Fallback automatico della sorgente: classificazione degli errori Media3 e scelta
 * dell'alternativa.
 *
 * Contratti verificati:
 *  - solo un errore DEFINITIVO per la sorgente autorizza il cambio di flusso;
 *  - gli errori transitori (rete, risposte temporanee del server) e quelli di
 *    codec/sottotitoli NON fanno cambiare sorgente;
 *  - la sorgente fallita e quelle gia' tentate non vengono mai riselezionate;
 *  - le voci non riproducibili senza un passaggio aggiuntivo restano al selettore
 *    manuale.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlayerSourceFallbackTest {

  private val streamUri = "https://cdn.example/stream.m3u8"

  private fun httpFailure(
    responseCode: Int,
    errorCode: Int = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
  ): PlaybackException = PlaybackException(
    "HTTP $responseCode",
    HttpDataSource.InvalidResponseCodeException(
      responseCode,
      "Error",
      null,
      emptyMap(),
      DataSpec.Builder().setUri(Uri.parse(streamUri)).build(),
      byteArrayOf()
    ),
    errorCode
  )

  private fun url(host: String, name: String) = "https://cdn.example/$host/$name/index.m3u8"

  private fun source(
    name: String,
    url: String = url("generico", "1080p"),
    quality: String = "1080p",
    infoHash: String? = null,
    fileIdx: Int? = null
  ) = StreamSource(
    streamUrl = url,
    quality = quality,
    serverName = name,
    infoHash = infoHash,
    fileIdx = fileIdx
  )

  // ── 1. Errori definitivi per la sorgente ────────────────────────────────────

  @Test
  fun erroreHttpDiRisorsaNonDisponibileEDefinitivo() {
    listOf(400, 401, 403, 404, 410).forEach { code ->
      assertEquals(
        "HTTP $code significa che la sorgente non e' utilizzabile: si prova un'alternativa",
        PlayerSourceFallback.Verdict.SOURCE_DEFINITIVE,
        PlayerSourceFallback.classify(httpFailure(code))
      )
    }
  }

  @Test
  fun fileMancantePermessiContenutoNonVideoEManifestRottoSonoDefinitivi() {
    val definitivi = listOf(
      // Risorsa assente o non leggibile.
      PlaybackException("manca", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND),
      PlaybackException("permessi", null, PlaybackException.ERROR_CODE_IO_NO_PERMISSION),
      // URL http:// bloccato dalle policy di rete: questa sorgente non partira' mai.
      PlaybackException("cleartext", null, PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED),
      // Pagina di errore HTML servita al posto del flusso video.
      PlaybackException("html", null, PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE),
      // Manifest/container illeggibile o non supportato.
      PlaybackException("manifest", null, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED),
      PlaybackException("manifest", null, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED),
      PlaybackException("container", null, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED),
      PlaybackException("container", null, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)
    )

    definitivi.forEach { error ->
      assertEquals(
        "${error.errorCodeName} deve autorizzare il cambio di sorgente",
        PlayerSourceFallback.Verdict.SOURCE_DEFINITIVE,
        PlayerSourceFallback.classify(error)
      )
    }
  }

  // ── 2. Errori che NON devono far cambiare sorgente ──────────────────────────

  @Test
  fun erroreDiReteRestaTransitorio() {
    val transitori = listOf(
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
      PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
      PlaybackException.ERROR_CODE_TIMEOUT,
      PlaybackException.ERROR_CODE_UNSPECIFIED,
      PlaybackException.ERROR_CODE_REMOTE_ERROR,
      PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK
    )

    transitori.forEach { code ->
      assertEquals(
        "L'errore $code non dichiara la sorgente irrecuperabile: nessun cambio automatico",
        PlayerSourceFallback.Verdict.TRANSIENT,
        PlayerSourceFallback.classify(PlaybackException("rete", null, code))
      )
    }
  }

  @Test
  fun letturaFuoriIntervalloDopoUnSeekNonFaCambiareSorgente() {
    // Un seek oltre l'intervallo disponibile e' un episodio di riproduzione, non
    // un errore definitivo della sorgente: il flusso resta quello in riproduzione.
    assertEquals(
      PlayerSourceFallback.Verdict.TRANSIENT,
      PlayerSourceFallback.classify(
        PlaybackException(
          "read position out of range",
          null,
          PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
        )
      )
    )
  }

  @Test
  fun rispostaTemporaneaDelServerNonFaCambiareSorgente() {
    listOf(408, 425, 429, 500, 502, 503, 504).forEach { code ->
      assertEquals(
        "HTTP $code e' una condizione passeggera del server",
        PlayerSourceFallback.Verdict.TRANSIENT,
        PlayerSourceFallback.classify(httpFailure(code))
      )
    }
  }

  @Test
  fun erroreDiCodecNonEColpaDellaSorgente() {
    val codec = listOf(
      PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
      PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
      PlaybackException.ERROR_CODE_DECODING_FAILED,
      PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
      PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
      PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
      PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
    )

    codec.forEach { code ->
      assertEquals(
        "L'errore decoder/decodifica $code e' gestito dal fallback codec, non dalla sorgente",
        PlayerSourceFallback.Verdict.NOT_SOURCE_RELATED,
        PlayerSourceFallback.classify(PlaybackException("codec", null, code))
      )
    }
  }

  @Test
  fun erroreDellaTracciaSottotitoliNonFaCambiareSorgente() {
    // La ripresa senza la traccia problematica e' gia' gestita dal player.
    val textRendererError = ExoPlaybackException.createForRenderer(
      SubtitleDecoderException("SubRip parsing failed"),
      "TextRenderer",
      /* rendererIndex = */ 3,
      Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build(),
      C.FORMAT_HANDLED,
      /* isFromDownloader = */ false,
      PlaybackException.ERROR_CODE_DECODING_FAILED
    )

    assertEquals(
      PlayerSourceFallback.Verdict.NOT_SOURCE_RELATED,
      PlayerSourceFallback.classify(textRendererError)
    )
  }

  // ── 3. Selezione dell'alternativa ───────────────────────────────────────────

  @Test
  fun sceglieLaPrimaAlternativaNonAncoraTentataNellOrdineDelRanking() {
    val migliore = source("Migliore", url("migliore", "1080p"))
    val discreta = source("Discreta", url("discreta", "720p"), quality = "720p")
    val fallita = source("Fallita", url("fallita", "1080p"))

    val scelta = PlayerSourceFallback.selectAlternative(
      candidates = listOf(migliore, fallita, discreta),
      currentKey = PlayerSourceFallback.keyOf(fallita),
      attemptedKeys = setOf(PlayerSourceFallback.keyOf(fallita))
    )

    assertEquals("L'ordine del ranking deve essere preservato", migliore, scelta)
  }

  @Test
  fun leSorgentiGiaTentateNonVengonoRiselezionate() {
    val prima = source("Prima", url("prima", "1080p"))
    val seconda = source("Seconda", url("seconda", "720p"), quality = "720p")

    val scelta = PlayerSourceFallback.selectAlternative(
      candidates = listOf(prima, seconda),
      currentKey = PlayerSourceFallback.keyOf(seconda),
      attemptedKeys = setOf(PlayerSourceFallback.keyOf(prima), PlayerSourceFallback.keyOf(seconda))
    )

    assertNull("Nessuna alternativa: il ciclo di tentativi e' esaurito", scelta)
  }

  @Test
  fun leSorgentiNonRiproducibiliRestanoAlSelettoreManuale() {
    val torrentGrezzo = source("Torrent", url = "https://cdn.example/torrent", infoHash = "abc123")
      .copy(streamUrl = null)
    val manifestJson = source("Manifest", url = "https://addon.example/manifest.json")
    val senzaUrl = source("Vuota", url = "").copy(streamUrl = null)

    val scelta = PlayerSourceFallback.selectAlternative(
      candidates = listOf(torrentGrezzo, manifestJson, senzaUrl),
      currentKey = "url:https://cdn.example/attiva/index.m3u8",
      attemptedKeys = emptySet()
    )

    assertNull(
      "Torrent non sbloccato, manifest JSON e URL vuoto non sono avviabili automaticamente",
      scelta
    )
  }

  @Test
  fun unaSorgenteConInfoHashCoincideConLaStessaSorgenteSbloccata() {
    val infoHash = "b1946ac92492d2347c6235b4d2611184"

    val nonSbloccata = source("Debrid", url = "", infoHash = infoHash, fileIdx = 2)
      .copy(streamUrl = null)
    val sbloccata = source("Debrid", url("cdn-torbox", "1080p"), infoHash = infoHash, fileIdx = 2)

    assertEquals(
      "La chiave logica deve unire la voce in lista e la sorgente in riproduzione",
      PlayerSourceFallback.keyOf(nonSbloccata),
      PlayerSourceFallback.keyOf(sbloccata)
    )

    val altroFile = source("Debrid", url("cdn-torbox", "1080p"), infoHash = infoHash, fileIdx = 5)
        .copy(streamUrl = null)
    assertTrue(
      "File diversi dello stesso torrent sono sorgenti diverse",
      PlayerSourceFallback.keyOf(altroFile) != PlayerSourceFallback.keyOf(nonSbloccata)
    )
  }

  @Test
  fun dueVociConLoStessoUrlSonoLaStessaSorgente() {
    val primo = source("Provider A", "https://cdn.example/stesso/index.m3u8")
    val secondo = source("Mirror Di A", "https://cdn.example/stesso/index.m3u8")

    assertEquals(PlayerSourceFallback.keyOf(primo), PlayerSourceFallback.keyOf(secondo))
    assertNotNull(PlayerSourceFallback.selectAlternative(listOf(primo, secondo), null, emptySet()))
  }
}
