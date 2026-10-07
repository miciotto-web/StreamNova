package com.example.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
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
 * Regressione 1080p/4K: la decodifica fallita di un sottotitolo esterno (es. HTML di
 * OpenSubtitles passato come .srt) arrivava a PlayerScreen con `ERROR_CODE_DECODING_FAILED`
 * e veniva scambiata per un errore codec video, attivando `maxVideoSize(1280, 720)` che
 * escludeva l'unica traccia 1080p/4K (rimaneva solo l'audio).
 *
 * Qui si verifica che:
 * - un errore sottotitoli/text NON sia classificato come codec video;
 * - il fallback 720p NON venga attivato per un errore text;
 * - di conseguenza `maxVideoSize` resti invariato e il video 1080p/4K non venga escluso;
 * - gli errori codec VIDEO reali continuino ad attivare il fallback 720p.
 */
@RunWith(AndroidJUnit4::class)
class PlayerCodecErrorClassifierTest {

  private fun rendererError(
    rendererName: String,
    sampleMimeType: String?,
    cause: Throwable? = null,
    errorCode: Int = PlaybackException.ERROR_CODE_DECODING_FAILED
  ): PlaybackException {
    val format = sampleMimeType?.let { Format.Builder().setSampleMimeType(it).build() }
    return ExoPlaybackException.createForRenderer(
      cause ?: SubtitleDecoderException("decode failed"),
      rendererName,
      /* rendererIndex = */ 3,
      format,
      C.FORMAT_HANDLED,
      /* isFromDownloader = */ false,
      errorCode
    )
  }

  private val subRipError: PlaybackException
    get() = rendererError(
      rendererName = "TextRenderer",
      sampleMimeType = MimeTypes.APPLICATION_SUBRIP,
      cause = SubtitleDecoderException("SubRip parsing failed: HTML ricevuto al posto del file")
    )

  // ── 1. Un errore sottotitoli non è un errore codec video ────────────────────

  @Test
  fun erroreSottotitoloDecodificaNonECodecVideo() {
    assertTrue(PlayerCodecErrorClassifier.isSubtitleTrackError(subRipError))
    assertFalse(PlayerCodecErrorClassifier.isVideoRendererError(subRipError))
    assertFalse(PlayerCodecErrorClassifier.isVideoCodecError(subRipError))
  }

  @Test
  fun errorCodeDecodingFailedDaSoloNonBastaAProvareUnErroreVideo() {
    // Nessuna informazione sul renderer + causa di decodifica sottotitoli:
    // l'errorCode NON deve bastare per classificare l'errore come video.
    val error = PlaybackException(
      "Decoder failed",
      SubtitleDecoderException("SubRip parsing failed"),
      PlaybackException.ERROR_CODE_DECODING_FAILED
    )

    assertFalse(PlayerCodecErrorClassifier.isVideoCodecError(error))
    assertFalse(PlayerCodecErrorClassifier.isVideoRendererError(error))
    assertNull(PlayerCodecErrorClassifier.fallbackMaxVideoSize(error))
  }

  @Test
  fun erroreTextConMimeSconosciutoRiconosciutoDalNomeRenderer() {
    val error = rendererError(
      rendererName = "TextRenderer",
      sampleMimeType = null,
      cause = SubtitleDecoderException("Subtitle decoding failed")
    )

    assertTrue(PlayerCodecErrorClassifier.isSubtitleTrackError(error))
    assertFalse(PlayerCodecErrorClassifier.isVideoCodecError(error))
  }

  // ── 2. Il fallback 720p non viene attivato per un errore text ───────────────

  @Test
  fun fallback720pNonAttivatoPerErroreText() {
    assertNull(PlayerCodecErrorClassifier.fallbackMaxVideoSize(subRipError))
    assertNull(
      PlayerCodecErrorClassifier.fallbackMaxVideoSize(
        rendererError("TextRenderer", MimeTypes.TEXT_VTT, SubtitleDecoderException("bad vtt"))
      )
    )
    assertNull(
      // Errore codec senza renderer video identificabile: comunque nessun fallback.
      PlayerCodecErrorClassifier.fallbackMaxVideoSize(
        PlaybackException("decode failed", null, PlaybackException.ERROR_CODE_DECODING_FAILED)
      )
    )
  }

  // ── 3. Il video 1080p/4K non viene escluso a causa di un errore subtitle ────

  @Test
  fun video1080p4kNonEsclusoDaErroreSubtitle() {
    // Stessa applicazione del fallback che fa PlayerScreen: `maxVideoSize` viene toccato
    // SOLO se `fallbackMaxVideoSize` restituisce una dimensione.
    var maxVideoWidth = 3840
    var maxVideoHeight = 2160

    val fallback = PlayerCodecErrorClassifier.fallbackMaxVideoSize(subRipError)
    if (fallback != null) {
      maxVideoWidth = fallback.first
      maxVideoHeight = fallback.second
    }

    // Nessuna esclusione: la traccia unica 1080p/4K resta selezionabile, il video continua.
    assertEquals(3840, maxVideoWidth)
    assertEquals(2160, maxVideoHeight)
    assertNull(fallback)
    assertFalse(PlayerCodecErrorClassifier.isVideoCodecError(subRipError))
  }

  // ── 4. Gli errori codec video reali restano gestiti come prima ──────────────

  @Test
  fun erroreCodecVideoRealeAttivaAncoraIlFallback720p() {
    val error = rendererError(
      rendererName = "MediaCodecVideoRenderer",
      sampleMimeType = MimeTypes.VIDEO_H264,
      cause = RuntimeException("Video decoder failed")
    )

    assertTrue(PlayerCodecErrorClassifier.isVideoRendererError(error))
    assertTrue(PlayerCodecErrorClassifier.isVideoCodecError(error, "Film 4K"))
    assertEquals(
      PlayerCodecErrorClassifier.FALLBACK_MAX_VIDEO_WIDTH to
        PlayerCodecErrorClassifier.FALLBACK_MAX_VIDEO_HEIGHT,
      PlayerCodecErrorClassifier.fallbackMaxVideoSize(error, "Film 4K")
    )
    assertEquals(1280 to 720, PlayerCodecErrorClassifier.fallbackMaxVideoSize(error))
  }

  @Test
  fun erroreCodecSenzaRendererVideoNonAttivaIlFallback() {
    // ERROR_CODE_DECODING_FAILED senza renderer video associato: nessun fallback,
    // in coerenza con "attiva il fallback solo quando l'errore è associato al renderer VIDEO".
    val error = PlaybackException(
      "decode failed",
      RuntimeException("codec error"),
      PlaybackException.ERROR_CODE_DECODING_FAILED
    )

    assertFalse(PlayerCodecErrorClassifier.isVideoCodecError(error))
    assertNull(PlayerCodecErrorClassifier.fallbackMaxVideoSize(error))
  }

  @Test
  fun erroreTracciaAudioNonAttivaIlFallback720p() {
    val error = rendererError(
      rendererName = "MediaCodecAudioRenderer",
      sampleMimeType = MimeTypes.AUDIO_AAC,
      cause = RuntimeException("Audio decoder failed")
    )

    assertFalse(PlayerCodecErrorClassifier.isVideoCodecError(error))
    assertNull(PlayerCodecErrorClassifier.fallbackMaxVideoSize(error))
  }

  // ── MIME type sottotitoli ───────────────────────────────────────────────────

  @Test
  fun riconoscimentoMimeSottotitoli() {
    assertTrue(PlayerCodecErrorClassifier.isSubtitleMimeType(MimeTypes.APPLICATION_SUBRIP))
    assertTrue(PlayerCodecErrorClassifier.isSubtitleMimeType(MimeTypes.TEXT_VTT))
    assertTrue(PlayerCodecErrorClassifier.isSubtitleMimeType(MimeTypes.APPLICATION_TTML))
    assertFalse(PlayerCodecErrorClassifier.isSubtitleMimeType(MimeTypes.VIDEO_H264))
    assertFalse(PlayerCodecErrorClassifier.isSubtitleMimeType(MimeTypes.AUDIO_AAC))
    assertFalse(PlayerCodecErrorClassifier.isSubtitleMimeType(null))
    assertFalse(PlayerCodecErrorClassifier.isSubtitleMimeType("  "))
  }
}
