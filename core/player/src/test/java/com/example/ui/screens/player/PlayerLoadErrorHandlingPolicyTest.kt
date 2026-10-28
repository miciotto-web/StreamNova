package com.example.ui.screens.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Una traccia sottotitoli esterna (sidecar OpenSubtitles/Stremio) che fallisce non deve
 * rendere fatale la riproduzione del video: la policy la riconosce come traccia testo e
 * non le assegua retry, così Media3 scarta la sola traccia e il video continua.
 * La gestione di video/audio deve restare invariata.
 */
@RunWith(AndroidJUnit4::class)
class PlayerLoadErrorHandlingPolicyTest {

  private val policy = PlayerLoadErrorHandlingPolicy()

  private fun loadErrorInfo(
    trackType: Int,
    sampleMimeType: String?,
    exception: IOException = IOException("load failed"),
    errorCount: Int = 1,
    uri: String = "https://subs.example/ita.srt"
  ): LoadErrorHandlingPolicy.LoadErrorInfo {
    val format = sampleMimeType?.let { Format.Builder().setSampleMimeType(it).build() }
    val mediaLoadData = MediaLoadData(C.DATA_TYPE_MEDIA, trackType, format, 0, null, 0L, 0L)
    val dataSpec = DataSpec.Builder().setUri(Uri.parse(uri)).build()
    val loadEventInfo = LoadEventInfo(1L, dataSpec, 0L)
    return LoadErrorHandlingPolicy.LoadErrorInfo(loadEventInfo, mediaLoadData, exception, errorCount)
  }

  private fun invalidResponseCodeException(
    responseCode: Int,
    uri: String
  ) = HttpDataSource.InvalidResponseCodeException(
    responseCode,
    "Error",
    null,
    emptyMap(),
    DataSpec.Builder().setUri(Uri.parse(uri)).build(),
    byteArrayOf()
  )

  // ── Riconoscimento del caricamento sottotitoli ──────────────────────────────

  @Test
  fun sidecarEsternoConMimeSubRipRiconosciutoComeSottotitolo() {
    // I sidecar esterni arrivano con trackType sconosciuto: a basta il MIME type.
    assertTrue(
      PlayerLoadErrorHandlingPolicy.isSubtitleLoad(C.TRACK_TYPE_UNKNOWN, MimeTypes.APPLICATION_SUBRIP)
    )
    assertTrue(
      PlayerLoadErrorHandlingPolicy.isSubtitleLoad(C.TRACK_TYPE_UNKNOWN, MimeTypes.TEXT_VTT)
    )
    assertTrue(PlayerLoadErrorHandlingPolicy.isSubtitleLoad(C.TRACK_TYPE_TEXT, null))
  }

  @Test
  fun tracceVideoEAudioNonConfuseConISottotitoli() {
    assertFalse(
      PlayerLoadErrorHandlingPolicy.isSubtitleLoad(C.TRACK_TYPE_VIDEO, MimeTypes.VIDEO_H264)
    )
    assertFalse(
      PlayerLoadErrorHandlingPolicy.isSubtitleLoad(C.TRACK_TYPE_AUDIO, MimeTypes.AUDIO_AAC)
    )
    assertFalse(PlayerLoadErrorHandlingPolicy.isSubtitleLoad(C.TRACK_TYPE_UNKNOWN, null))
  }

  // ── Errore di caricamento sottotitoli: mai fatale ───────────────────────────

  @Test
  fun caricamentoSottotitoloEsternoCheFallisceNonEUnErroreFatale() {
    val info = loadErrorInfo(
      trackType = C.TRACK_TYPE_UNKNOWN,
      sampleMimeType = MimeTypes.APPLICATION_SUBRIP,
      exception = invalidResponseCodeException(500, "https://subs.example/ita.srt")
    )

    // Nessun retry → Media3 tratta il sidecar come end-of-stream ed esclude la sola traccia,
    // proseguendo con la riproduzione video senza quel sottotitolo.
    assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info))
  }

  @Test
  fun sottotitoloEsternoConErroreDiReteNonFaRiprovaAllInfinito() {
    val info = loadErrorInfo(
      trackType = C.TRACK_TYPE_UNKNOWN,
      sampleMimeType = MimeTypes.APPLICATION_SUBRIP,
      exception = SocketTimeoutException("timeout"),
      errorCount = 1
    )

    assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info))
  }

  @Test
  fun erroreSottotitoloConFallbackDisponibileEscludeSoloQuellaTraccia() {
    val info = loadErrorInfo(
      trackType = C.TRACK_TYPE_UNKNOWN,
      sampleMimeType = MimeTypes.APPLICATION_SUBRIP
    )
    // Sia le location sia le tracce hanno alternative disponibili per Media3.
    val fallbackOptions = LoadErrorHandlingPolicy.FallbackOptions(4, 1, 4, 1)

    val selection = policy.getFallbackSelectionFor(fallbackOptions, info)

    assertNotNull(selection)
    assertEquals(LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK, selection!!.type)
  }

  // ── Gestione video/audio invariata ──────────────────────────────────────────

  @Test
  fun timeoutSuTracciaVideoMantieneIlBackoffPrecedente() {
    val info = loadErrorInfo(
      trackType = C.TRACK_TYPE_VIDEO,
      sampleMimeType = MimeTypes.VIDEO_H264,
      exception = SocketTimeoutException("timeout"),
      errorCount = 1
    )

    assertEquals(750L, policy.getRetryDelayMsFor(info))
  }

  @Test
  fun http404SuTracciaVideoRestaGestitoComePrima() {
    val info = loadErrorInfo(
      trackType = C.TRACK_TYPE_VIDEO,
      sampleMimeType = MimeTypes.VIDEO_H264,
      exception = invalidResponseCodeException(404, "https://cdn.example/video.m3u8")
    )

    assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info))
  }
}
