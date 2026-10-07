package com.example.ui.screens.player

import android.media.MediaCodec
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.extractor.text.SubtitleDecoderException

/**
 * Classificazione rigorosa degli errori di riproduzione, usata da PlayerScreen per decidere
 * se applicare il fallback codec 720p (`maxVideoSize(1280, 720)`).
 *
 * `ERROR_CODE_DECODING_FAILED` da solo NON è la prova di un errore video: anche la decodifica
 * di un sottotitolo esterno corrotto (es. HTML di OpenSubtitles interpretato come SubRip) viene
 * riportata da ExoPlayer con lo stesso errorCode. Il fallback viene quindi attivato SOLO se
 * l'errore è effettivamente associato al renderer VIDEO; un errore di sottotitolo/testo non
 * modifica mai `maxVideoSize` e non tocca la traccia video.
 */
internal object PlayerCodecErrorClassifier {

  /** Dimensione video imposta dal fallback codec (invariata rispetto al comportamento precedente). */
  const val FALLBACK_MAX_VIDEO_WIDTH = 1280
  const val FALLBACK_MAX_VIDEO_HEIGHT = 720

  /** MIME type delle tracce sottotitoli/testo riconosciute da Media3. */
  private val SUBTITLE_MIME_TYPES = setOf(
    MimeTypes.APPLICATION_SUBRIP,
    MimeTypes.TEXT_VTT,
    MimeTypes.TEXT_SSA,
    "application/x-ssa",
    MimeTypes.APPLICATION_TTML,
    MimeTypes.APPLICATION_TX3G,
    MimeTypes.APPLICATION_MP4VTT,
    MimeTypes.APPLICATION_CEA608,
    MimeTypes.APPLICATION_CEA708,
    MimeTypes.APPLICATION_VOBSUB,
    MimeTypes.APPLICATION_PGS,
    MimeTypes.APPLICATION_DVBSUBS,
    MimeTypes.APPLICATION_MEDIA3_CUES
  )

  /** Marcatori tipici degli errori di decodifica sottotitoli nei messaggi di ExoPlayer. */
  private val SUBTITLE_ERROR_MARKERS = listOf(
    "subtitle decoding failed",
    "subtitle decoder",
    "subrip",
    "webvtt",
    "ttml"
  )

  /**
   * `true` se il MIME type identifica una traccia sottotitoli/testo.
   */
  fun isSubtitleMimeType(sampleMimeType: String?): Boolean {
    val mime = sampleMimeType?.trim()?.lowercase() ?: return false
    if (mime.isEmpty()) return false
    if (MimeTypes.isText(mime)) return true
    return mime in SUBTITLE_MIME_TYPES
  }

  /**
   * `true` se l'errore è associato a una traccia sottotitoli/testo.
   *
   * Riconosce: il renderer TextRenderer (o un renderer "text/subtitle"), il MIME type della
   * traccia in decodifica, le eccezioni di decodifica sottotitoli (es. SubRip) nella catena
   * delle cause e i messaggi tipici di un fallimento di decodifica sottotitoli.
   */
  fun isSubtitleTrackError(error: PlaybackException): Boolean {
    if (rendererTrackType(error) == C.TRACK_TYPE_TEXT) return true

    var cause: Throwable? = error
    while (cause != null) {
      if (cause is SubtitleDecoderException) return true
      val className = cause.javaClass.name.lowercase()
      if (className.contains("subrip") || className.contains("subtitle")) return true
      cause = cause.cause
    }

    val message = buildString {
      append(error.message.orEmpty())
      append(' ')
      append(error.cause?.message.orEmpty())
    }.lowercase()
    return SUBTITLE_ERROR_MARKERS.any { message.contains(it) }
  }

  /**
   * `true` se l'errore è effettivamente associato al renderer VIDEO.
   */
  fun isVideoRendererError(error: PlaybackException): Boolean =
    rendererTrackType(error) == C.TRACK_TYPE_VIDEO

  /**
   * `true` solo per gli errori codec VIDEO reali: firma codec (decodifica / format / MediaCodec /
   * Dolby Vision / VC-1) E renderer video identificato. Un errore sottotitoli/testo restituisce
   * sempre `false`, qualunque sia l'errorCode.
   */
  fun isVideoCodecError(error: PlaybackException, streamName: String? = null): Boolean {
    if (isSubtitleTrackError(error)) return false
    if (!hasCodecSignature(error, streamName)) return false
    return isVideoRendererError(error)
  }

  /**
   * Dimensione massima video da imporre al track selector in seguito all'errore,
   * oppure `null` quando `maxVideoSize` NON deve essere toccato (caso sottotitoli/testo,
   * errori non codec, errori senza renderer video identificato).
   */
  fun fallbackMaxVideoSize(error: PlaybackException, streamName: String? = null): Pair<Int, Int>? =
    if (isVideoCodecError(error, streamName)) {
      FALLBACK_MAX_VIDEO_WIDTH to FALLBACK_MAX_VIDEO_HEIGHT
    } else {
      null
    }

  /**
   * Stessa firma di errore codec usata in precedenza da PlayerScreen.
   */
  private fun hasCodecSignature(error: PlaybackException, streamName: String?): Boolean =
    error.cause is MediaCodec.CodecException ||
      error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
      error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ||
      PlayerRuntimeControllerErrorRecovery.isDolbyVisionDecoderFailure(error) ||
      Vc1VideoFormatHeuristics.isVc1PlaybackFailure(error, false, streamName)

  /**
   * Tipo di traccia del renderer che ha generato l'errore, ricostruito dal formato e dal nome
   * del renderer di ExoPlaybackException. `C.TRACK_TYPE_UNKNOWN` quando l'errore non è
   * riconducibile ad alcun renderer (allora non si attiva alcun fallback video).
   */
  private fun rendererTrackType(error: PlaybackException): Int {
    val exoError = error as? ExoPlaybackException ?: return C.TRACK_TYPE_UNKNOWN

    val mime = exoError.rendererFormat?.sampleMimeType
    if (!mime.isNullOrBlank()) {
      if (MimeTypes.isVideo(mime)) return C.TRACK_TYPE_VIDEO
      if (MimeTypes.isAudio(mime)) return C.TRACK_TYPE_AUDIO
      if (isSubtitleMimeType(mime)) return C.TRACK_TYPE_TEXT
    }

    val rendererName = exoError.rendererName ?: return C.TRACK_TYPE_UNKNOWN
    return when {
      rendererName.contains("text", ignoreCase = true) ||
        rendererName.contains("subtitle", ignoreCase = true) ||
        rendererName.contains("cue", ignoreCase = true) -> C.TRACK_TYPE_TEXT
      rendererName.contains("video", ignoreCase = true) -> C.TRACK_TYPE_VIDEO
      rendererName.contains("audio", ignoreCase = true) -> C.TRACK_TYPE_AUDIO
      else -> C.TRACK_TYPE_UNKNOWN
    }
  }
}
