package com.example.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.extractor.text.SubtitleDecoderException
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regressione dell'attribuzione degli errori di riproduzione.
 *
 * Bug originale: la selezione di un sottotitolo OpenSubtitles faceva fallire il player
 * con `ERROR_CODE_FAILED_RUNTIME_CHECK` ("Legacy decoding is disabled, can't handle
 * application/x-subrip samples") e l'errore veniva inoltrato ai gestori della sorgente
 * video (cambio sorgente / schermo nero / "Errore di Riproduzione").
 *
 * Qui si verifica che:
 *  - un errore riconducibile a una traccia sottotitoli esterna sia attribuito alla
 *    traccia (e resti distinguibile da un errore della sorgente video);
 *  - un errore della sorgente video resti attribuito al video, con la gestione normale;
 *  - un errore non attribuibile resti "unknown" (nessuna eccezione, nessuna corsia
 *    speciale che possa mascherare un errore reale).
 */
@RunWith(AndroidJUnit4::class)
class PlayerExternalSubtitleAttributionTest {

  private val videoUri = "https://cdn.test/video.m3u8"
  private val localSubtitleUri = "file:///data/user/0/com.example/cache/os_42.srt"
  private val remoteSubtitleUri = "https://subs.test/download/subtitle.srt"

  private fun attribute(
    error: PlaybackException,
    externalSubtitleUris: List<String> = listOf(localSubtitleUri, remoteSubtitleUri)
  ) = PlayerExternalSubtitleAttribution.attribute(
    error = error,
    videoUri = videoUri,
    externalSubtitleUris = externalSubtitleUris
  )

  /** Errore HTTP su uno specifico URI (con DataSpec, come fa Media3). */
  private fun httpError(uri: String, responseCode: Int): PlaybackException {
    val dataSpec = DataSpec.Builder()
      .setUri(android.net.Uri.parse(uri))
      .build()
    val cause = HttpDataSource.InvalidResponseCodeException(
      responseCode,
      "Response code: $responseCode",
      null,
      emptyMap(),
      dataSpec,
      ByteArray(0)
    )
    return ExoPlaybackException.createForSource(cause, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
  }

  /** Errore su file locale: il percorso compare nel messaggio, senza DataSpec. */
  private fun localFileError(path: String): PlaybackException {
    val cause = java.io.FileNotFoundException("$path: open failed: ENOENT (No such file or directory)")
    return ExoPlaybackException.createForSource(cause, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
  }

  /** Errore "legacy decoding is disabled" osservato sul player reale. */
  private fun legacyDecodingError(mimeType: String): PlaybackException {
    val cause = IllegalStateException(
      "Legacy decoding is disabled, can't handle $mimeType samples (expected application/x-media3-cues)."
    )
    return ExoPlaybackException.createForUnexpected(cause)
  }

  // ── 1. Errori della traccia esterna ──────────────────────────────────────────

  @Test
  fun erroreDelRendererDiTestoEAttribuitoAllaTracciaSottotitoli() {
    val error = ExoPlaybackException.createForRenderer(
      SubtitleDecoderException("subtitle decoding failed"),
      "TextRenderer",
      3,
      Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build(),
      C.FORMAT_HANDLED,
      false,
      PlaybackException.ERROR_CODE_DECODING_FAILED
    )

    val verdict = attribute(error)

    assertEquals(PlayerExternalSubtitleAttribution.Attribution.EXTERNAL_SUBTITLE, verdict.attribution)
    assertTrue(verdict.isExternalSubtitle)
  }

  @Test
  fun erroreHttpSuUnSottotitoloLocaleEAttribuitoAllaTraccia() {
    val error = httpError(localSubtitleUri, 404)

    val verdict = attribute(error)

    assertEquals(PlayerExternalSubtitleAttribution.Attribution.EXTERNAL_SUBTITLE, verdict.attribution)
    assertEquals(localSubtitleUri, verdict.failedSubtitleUri)
  }

  @Test
  fun erroreHttpSuUnSottotitoloRemotoEAttribuitoAllaTraccia() {
    val error = httpError(remoteSubtitleUri, 403)

    val verdict = attribute(error)

    assertEquals(PlayerExternalSubtitleAttribution.Attribution.EXTERNAL_SUBTITLE, verdict.attribution)
    assertEquals(remoteSubtitleUri, verdict.failedSubtitleUri)
  }

  @Test
  fun erroreSuFileLocaleConPercorsoNelMessaggioEAttribuitoAllaTraccia() {
    val error = localFileError("/data/user/0/com.example/cache/os_42.srt")

    val verdict = attribute(error)

    assertEquals(PlayerExternalSubtitleAttribution.Attribution.EXTERNAL_SUBTITLE, verdict.attribution)
    assertEquals(localSubtitleUri, verdict.failedSubtitleUri)
  }

  @Test
  fun erroreDiDecodificaRifiutataDelMimeTypeEAttribuitoAllaTraccia() {
    val error = legacyDecodingError("application/x-subrip")

    val verdict = attribute(error)

    assertEquals(PlayerExternalSubtitleAttribution.Attribution.EXTERNAL_SUBTITLE, verdict.attribution)
  }

  @Test
  fun mimeTypeWebVttRifiutatoEAttribuitoAllaTraccia() {
    // Caso che il classificatore basato su marcatori NON riconosceva: "text/vtt"
    // non contiene nessuno dei marcatori "subrip"/"webvtt"/"ttml".
    val error = legacyDecodingError("text/vtt")

    val verdict = attribute(error)

    assertEquals(PlayerExternalSubtitleAttribution.Attribution.EXTERNAL_SUBTITLE, verdict.attribution)
  }

  @Test
  fun mimeTypeTtmlRifiutatoEAttribuitoAllaTraccia() {
    val error = legacyDecodingError("application/ttml+xml")

    val verdict = attribute(error)

    assertEquals(PlayerExternalSubtitleAttribution.Attribution.EXTERNAL_SUBTITLE, verdict.attribution)
  }

  // ── 2. Gli errori della sorgente video restano gestiti normalmente ───────────

  @Test
  fun erroreHttpSullaSorgenteVideoEAttribuitoAlVideo() {
    val error = httpError(videoUri, 403)

    val verdict = attribute(error)

    assertEquals(PlayerExternalSubtitleAttribution.Attribution.VIDEO_SOURCE, verdict.attribution)
    assertFalse(verdict.isExternalSubtitle)
    assertNull(verdict.failedSubtitleUri)
  }

  @Test
  fun erroreCodecVideoNonEAttribuitoAiSottotitoli() {
    val error = ExoPlaybackException.createForRenderer(
      RuntimeException("Video decoder failed"),
      "MediaCodecVideoRenderer",
      0,
      Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(),
      C.FORMAT_HANDLED,
      false,
      PlaybackException.ERROR_CODE_DECODING_FAILED
    )

    val verdict = attribute(error)

    assertFalse("un errore codec video non può essere attribuito ai sottotitoli", verdict.isExternalSubtitle)
  }

  @Test
  fun erroreSconosciutoRestaNonAttribuito() {
    val error = PlaybackException("errore generico", null, PlaybackException.ERROR_CODE_UNSPECIFIED)

    val verdict = attribute(error)

    assertEquals(PlayerExternalSubtitleAttribution.Attribution.UNKNOWN, verdict.attribution)
    assertFalse(verdict.isExternalSubtitle)
  }

  @Test
  fun erroreVideoNonEAttribuitoAiSottotitoliAncheConTracceAllegate() {
    // Nessuna traccia sottotitoli allegata: anche un errore "di decodifica" resta
    // attribuito al video, per non mascherare un errore reale della sorgente.
    val error = ExoPlaybackException.createForRenderer(
      RuntimeException("Video decoder failed"),
      "MediaCodecVideoRenderer",
      0,
      Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build(),
      C.FORMAT_HANDLED,
      false,
      PlaybackException.ERROR_CODE_DECODING_FAILED
    )

    val verdict = attribute(error, externalSubtitleUris = emptyList())

    assertFalse(verdict.isExternalSubtitle)
  }
}
