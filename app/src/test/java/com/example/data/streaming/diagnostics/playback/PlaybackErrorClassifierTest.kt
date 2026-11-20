package com.example.data.streaming.diagnostics.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Classificazione diagnostica pura (FASE 5): nessuna dipendenza da rete né da
 * Robolectric. Gli errori temporanei NON sono mai dichiarazioni definitive.
 */
class PlaybackErrorClassifierTest {

  @Test
  fun http403Eauthority() {
    val diagnosis = PlaybackErrorClassifier.classify(
      PlaybackErrorInput(httpStatus = 403, phase = PlaybackPhase.MANIFEST)
    )
    assertEquals(HlsErrorCategory.AUTHORIZATION_ERROR, diagnosis!!.category)
    assertFalse(diagnosis.temporary)
    assertEquals(DiagnosisConfidence.HIGH, diagnosis.confidence)
  }

  @Test
  fun http404SulManifestDichiaraSorgenteNonDisponibile() {
    val diagnosis = PlaybackErrorClassifier.classify(
      PlaybackErrorInput(httpStatus = 404, phase = PlaybackPhase.MANIFEST)
    )
    assertEquals(HlsErrorCategory.SOURCE_UNAVAILABLE, diagnosis!!.category)
    assertFalse(diagnosis.temporary)
  }

  @Test
  fun http404SuSegmentoRestaErroreDiSegmento() {
    val diagnosis = PlaybackErrorClassifier.classify(
      PlaybackErrorInput(httpStatus = 404, phase = PlaybackPhase.MEDIA_SEGMENT)
    )
    assertEquals(HlsErrorCategory.SEGMENT_ERROR, diagnosis!!.category)
    // Un segmento mancante può essere un aggiornamento di playlist (live/DVR).
    assertTrue(diagnosis.temporary)
  }

  @Test
  fun http429ETemporaneo() {
    val diagnosis = PlaybackErrorClassifier.classify(
      PlaybackErrorInput(httpStatus = 429, phase = PlaybackPhase.MANIFEST)
    )
    assertEquals(HlsErrorCategory.HTTP_ERROR, diagnosis!!.category)
    assertTrue(diagnosis.temporary)
    assertEquals(429, diagnosis.httpCode)
  }

  @Test
  fun http500ETemporaneo() {
    val diagnosis = PlaybackErrorClassifier.classify(
      PlaybackErrorInput(httpStatus = 500, phase = PlaybackPhase.MEDIA_SEGMENT)
    )
    assertEquals(HlsErrorCategory.HTTP_ERROR, diagnosis!!.category)
    assertTrue(diagnosis.temporary)
  }

  @Test
  fun timeoutDiReteNonEDefinitivo() {
    val diagnosis = PlaybackErrorClassifier.classify(
      PlaybackErrorInput(
        exceptionClassNames = listOf("ExoPlaybackException", "SocketTimeoutException"),
        exceptionMessage = "timeout",
        phase = PlaybackPhase.MANIFEST
      )
    )
    assertEquals(HlsErrorCategory.NETWORK_ERROR, diagnosis!!.category)
    assertTrue("Un timeout singolo non dichiara mai la sorgente irrecuperabile", diagnosis.temporary)
  }

  @Test
  fun erroreTlsEClassificatoComeTls() {
    val diagnosis = PlaybackErrorClassifier.classify(
      PlaybackErrorInput(
        exceptionClassNames = listOf("ExoPlaybackException", "SSLHandshakeException"),
        phase = PlaybackPhase.MANIFEST
      )
    )
    assertEquals(HlsErrorCategory.TLS_ERROR, diagnosis!!.category)
    assertTrue(diagnosis.temporary)
    assertEquals(DiagnosisConfidence.HIGH, diagnosis.confidence)
  }

  @Test
  fun manifestMalformato() {
    val diagnosis = PlaybackErrorClassifier.classify(
      PlaybackErrorInput(
        media3ErrorCode = PlaybackErrorClassifier.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        phase = PlaybackPhase.MANIFEST
      )
    )
    assertEquals(HlsErrorCategory.MANIFEST_ERROR, diagnosis!!.category)
    assertFalse(diagnosis.temporary)
    assertEquals(DiagnosisConfidence.HIGH, diagnosis.confidence)
  }

  @Test
  fun segmentoMancanteSenzaHttpStatus() {
    val diagnosis = PlaybackErrorClassifier.classify(
      PlaybackErrorInput(
        media3ErrorCode = PlaybackErrorClassifier.ERROR_CODE_IO_FILE_NOT_FOUND,
        phase = PlaybackPhase.MEDIA_SEGMENT
      )
    )
    assertEquals(HlsErrorCategory.SEGMENT_ERROR, diagnosis!!.category)
    assertTrue(diagnosis.temporary)
  }

  @Test
  fun erroreDecoderETemporaneo() {
    val diagnosis = PlaybackErrorClassifier.classify(
      PlaybackErrorInput(
        media3ErrorCode = PlaybackErrorClassifier.ERROR_CODE_DECODING_FAILED,
        phase = PlaybackPhase.DECODE
      )
    )
    assertEquals(HlsErrorCategory.DECODER_ERROR, diagnosis!!.category)
    assertTrue(diagnosis.temporary)
  }

  @Test
  fun formatoOltreLeCapacitaEDecoderTemporaneo() {
    val diagnosis = PlaybackErrorClassifier.classify(
      PlaybackErrorInput(
        media3ErrorCode = PlaybackErrorClassifier.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        phase = PlaybackPhase.DECODE
      )
    )
    assertEquals(HlsErrorCategory.DECODER_ERROR, diagnosis!!.category)
  }

  @Test
  fun formatoNonSupportatoEDefinitivo() {
    val diagnosis = PlaybackErrorClassifier.classify(
      PlaybackErrorInput(
        media3ErrorCode = PlaybackErrorClassifier.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        phase = PlaybackPhase.DECODE
      )
    )
    assertEquals(HlsErrorCategory.FORMAT_UNSUPPORTED, diagnosis!!.category)
    assertFalse(diagnosis.temporary)
  }

  @Test
  fun erroreConnessioneReteETemporaneo() {
    val diagnosis = PlaybackErrorClassifier.classify(
      PlaybackErrorInput(
        media3ErrorCode = PlaybackErrorClassifier.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        phase = PlaybackPhase.MANIFEST
      )
    )
    assertEquals(HlsErrorCategory.NETWORK_ERROR, diagnosis!!.category)
    assertTrue(diagnosis.temporary)
  }

  @Test
  fun erroreSenzaEvidenzeRestaSconosciutoEBassaConfidenza() {
    val diagnosis = PlaybackErrorClassifier.classify(
      PlaybackErrorInput(
        media3ErrorCode = PlaybackErrorClassifier.ERROR_CODE_UNSPECIFIED,
        phase = PlaybackPhase.UNKNOWN
      )
    )
    assertEquals(HlsErrorCategory.UNKNOWN_ERROR, diagnosis!!.category)
    assertEquals(DiagnosisConfidence.LOW, diagnosis.confidence)
    assertTrue(diagnosis.temporary)
  }

  @Test
  fun cancellazioneNonEClassificata() {
    assertNull(
      PlaybackErrorClassifier.classify(
        PlaybackErrorInput(httpStatus = 403, canceled = true)
      )
    )
  }

  @Test
  fun codiciMedia3Stabili() {
    // I valori specchiati devono coincidere con androidx.media3.common.PlaybackException.
    // Verifica dei valori noti della superficie stabile dell'API.
    assertEquals(2004, PlaybackErrorClassifier.ERROR_CODE_IO_BAD_HTTP_STATUS)
    assertEquals(3001, PlaybackErrorClassifier.ERROR_CODE_PARSING_MANIFEST_MALFORMED)
    assertEquals(4003, PlaybackErrorClassifier.ERROR_CODE_DECODING_FAILED)
    assertEquals(5002, PlaybackErrorClassifier.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED)
  }

  @Test
  fun laDiagnosiContieneTuttiICampiRichiesti() {
    val diagnosis = PlaybackErrorClassifier.classify(
      PlaybackErrorInput(
        httpStatus = 403,
        exceptionClassNames = listOf("ExoPlaybackException", "InvalidResponseCodeException"),
        phase = PlaybackPhase.MANIFEST
      )
    )
    assertNotNull(diagnosis)
    assertEquals(HlsErrorCategory.AUTHORIZATION_ERROR, diagnosis!!.category)
    assertEquals(403, diagnosis.httpCode)
    assertEquals(DiagnosisConfidence.HIGH, diagnosis.confidence)
    // La spec della FASE 5 (provider, timestamp, tentativi) è completata dalla
    // sessione: qui si verifica che la parte classificatore produca il messaggio
    // tecnico quando ci sono evidenze.
    assertNotNull(diagnosis.technicalMessage)
  }
}
