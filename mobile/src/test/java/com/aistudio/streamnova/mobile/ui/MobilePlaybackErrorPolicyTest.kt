package com.aistudio.streamnova.mobile.ui

import androidx.media3.common.PlaybackException
import com.aistudio.streamnova.mobile.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mapping degli errori di riproduzione Media3 sui messaggi localizzati:
 * errori comprensibili per l'utente, dettaglio tecnico solo in logcat/overlay debug.
 */
class MobilePlaybackErrorPolicyTest {

  @Test
  fun erroriDiReteETimeoutMostranoIlMessaggioConnessione() {
    val codes = listOf(
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
      PlaybackException.ERROR_CODE_TIMEOUT
    )
    codes.forEach { code ->
      assertEquals(
        "errorCode=$code",
        R.string.player_playback_error_connection,
        MobilePlaybackErrorPolicy.messageResId(code)
      )
    }
  }

  @Test
  fun statiHttpDefinitiviMostranoIlMessaggioNonDisponibile() {
    listOf(401, 403, 404, 410).forEach { http ->
      assertEquals(
        "http=$http",
        R.string.player_playback_error_unavailable,
        MobilePlaybackErrorPolicy.messageResId(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, http)
      )
    }
  }

  @Test
  fun statiHttpTemporaneiMostranoIlMessaggioConnessione() {
    listOf(408, 425, 429, 500, 502, 503, 504).forEach { http ->
      assertEquals(
        "http=$http",
        R.string.player_playback_error_connection,
        MobilePlaybackErrorPolicy.messageResId(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, http)
      )
    }
  }

  @Test
  fun risorsaAssenteOPermessiMostranoIlMessaggioNonDisponibile() {
    listOf(
      PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
      PlaybackException.ERROR_CODE_IO_NO_PERMISSION
    ).forEach { code ->
      assertEquals(
        "errorCode=$code",
        R.string.player_playback_error_unavailable,
        MobilePlaybackErrorPolicy.messageResId(code)
      )
    }
  }

  @Test
  fun erroriDiFormatoMostranoIlMessaggioFormato() {
    val codes = listOf(
      PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
      PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
      PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
      PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
      PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
      PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
    )
    codes.forEach { code ->
      assertEquals(
        "errorCode=$code",
        R.string.player_playback_error_format,
        MobilePlaybackErrorPolicy.messageResId(code)
      )
    }
  }

  @Test
  fun erroriDiDecodificaMostranoIlMessaggioDecoder() {
    val codes = listOf(
      PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
      PlaybackException.ERROR_CODE_DECODING_FAILED,
      PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
      PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
      PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
      PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
    )
    codes.forEach { code ->
      assertEquals(
        "errorCode=$code",
        R.string.player_playback_error_decoder,
        MobilePlaybackErrorPolicy.messageResId(code)
      )
    }
  }

  @Test
  fun codiceSconosciutoMostraIlMessaggioGenerico() {
    assertEquals(
      R.string.player_playback_error_generic,
      MobilePlaybackErrorPolicy.messageResId(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
    )
    assertEquals(
      R.string.player_playback_error_generic,
      MobilePlaybackErrorPolicy.messageResId(PlaybackException.ERROR_CODE_UNSPECIFIED)
    )
    // BAD_HTTP_STATUS senza stato HTTP estraibile: generico, mai un crash.
    assertEquals(
      R.string.player_playback_error_generic,
      MobilePlaybackErrorPolicy.messageResId(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
    )
  }

  @Test
  fun ilWrapperDelegaAlCodiceDellaPlaybackException() {
    val error = PlaybackException(
      "rete",
      null,
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
    )
    assertEquals(
      R.string.player_playback_error_connection,
      MobilePlaybackErrorPolicy.messageResId(error)
    )
  }
}
