package com.example.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Classificazione degli errori di download senza rete e senza MockWebServer:
 * ogni famiglia di eccezioni deve produrre un motivo DISTINTO, così il
 * messaggio mostrato all'utente (e la riga diagnostica) dicono che problema
 * c'è davvero — TLS, DNS, connessione rifiutata, timeout, interruzione —
 * invece del generico "download interrotto" che nascondeva tutto.
 */
class ApkDownloaderErrorMappingTest {

  @Test
  fun sslHandshakeIsNotAGenericInterruption() {
    val cause = SSLHandshakeException("Handshake fallito")
    assertEquals(DownloadErrorReason.SSL_ERROR, mapToDownloadException(cause).reason)
  }

  @Test
  fun sslPeerUnverifiedIsNotAGenericInterruption() {
    val cause = SSLPeerUnverifiedException("Identità del server non verificata")
    assertEquals(DownloadErrorReason.SSL_ERROR, mapToDownloadException(cause).reason)
  }

  @Test
  fun unknownHostIsADnsFailure() {
    val cause = UnknownHostException("github.com: Name or service not known")
    assertEquals(DownloadErrorReason.DNS_FAILURE, mapToDownloadException(cause).reason)
  }

  @Test
  fun connectExceptionIsAConnectionFailure() {
    val cause = ConnectException("Connection refused")
    assertEquals(DownloadErrorReason.CONNECTION_FAILED, mapToDownloadException(cause).reason)
  }

  @Test
  fun socketTimeoutRemainsATimeout() {
    val cause = SocketTimeoutException("Read timed out")
    assertEquals(DownloadErrorReason.TIMEOUT, mapToDownloadException(cause).reason)
  }

  @Test
  fun diskSpaceErrorsStayOnInsufficientSpace() {
    val cause = IOException("write failed: ENOSPC (No space left on device)")
    assertEquals(
      DownloadErrorReason.INSUFFICIENT_SPACE,
      mapToDownloadException(cause).reason
    )
  }

  @Test
  fun otherIoErrorsRemainInterrupted() {
    val cause = IOException("Connection reset by peer")
    assertEquals(DownloadErrorReason.INTERRUPTED, mapToDownloadException(cause).reason)
  }

  @Test
  fun aDownloadExceptionIsPassedThroughUnchanged() {
    val original = DownloadException(
      DownloadErrorReason.CHECKSUM_MISMATCH,
      "Digest non corrispondente"
    )
    assertSame(original, mapToDownloadException(original))
  }

  @Test
  fun httpCodesMapToDistinctReasons() {
    assertEquals(DownloadErrorReason.HTTP_BAD_REQUEST, downloadErrorReasonForHttpCode(400))
    assertEquals(DownloadErrorReason.HTTP_UNAUTHORIZED, downloadErrorReasonForHttpCode(401))
    assertEquals(DownloadErrorReason.HTTP_FORBIDDEN, downloadErrorReasonForHttpCode(403))
    assertEquals(DownloadErrorReason.HTTP_NOT_FOUND, downloadErrorReasonForHttpCode(404))
    assertEquals(DownloadErrorReason.HTTP_REQUEST_TIMEOUT, downloadErrorReasonForHttpCode(408))
    assertEquals(DownloadErrorReason.HTTP_RATE_LIMITED, downloadErrorReasonForHttpCode(429))
    assertEquals(DownloadErrorReason.HTTP_SERVER, downloadErrorReasonForHttpCode(500))
    assertEquals(DownloadErrorReason.HTTP_BAD_GATEWAY, downloadErrorReasonForHttpCode(502))
    assertEquals(
      DownloadErrorReason.HTTP_SERVICE_UNAVAILABLE,
      downloadErrorReasonForHttpCode(503)
    )
    assertEquals(DownloadErrorReason.HTTP_GATEWAY_TIMEOUT, downloadErrorReasonForHttpCode(504))
    // Codici non tabellati: secchio residuo, non un motivo sbagliato.
    assertEquals(DownloadErrorReason.HTTP_ERROR, downloadErrorReasonForHttpCode(418))
    assertEquals(DownloadErrorReason.HTTP_ERROR, downloadErrorReasonForHttpCode(505))
  }
}
