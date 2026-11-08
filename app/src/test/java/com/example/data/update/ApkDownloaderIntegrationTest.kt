package com.example.data.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/**
 * Download end-to-end dell'APK contro un MockWebServer locale: nessuna
 * richiesta reale a GitHub.
 *
 * I test usano una politica host di test (host del server locale, http
 * ammesso) per poter esercitare il percorso di download reale; i default di
 * produzione restano quelli verificati da [ApkDownloaderHostPolicyTest].
 *
 * Contratti verificati: integrità (dimensione + digest SHA-256), eliminazione
 * del file non integro, progressione monotona, cancellazione, errori HTTP e
 * blocco dell'host non consentito (anche sul redirect).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ApkDownloaderIntegrationTest {

  private lateinit var server: MockWebServer
  private lateinit var context: Context

  private val payload = "S".repeat(200_000)
  private val payloadBytes: ByteArray get() = payload.toByteArray(Charsets.UTF_8)

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    context = ApplicationProvider.getApplicationContext()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  /** Downloader di test: solo l'host del server locale, HTTP ammesso. */
  private fun downloader(allowedHostHost: String = server.url("/").host) =
    ApkDownloader(allowedHosts = setOf(allowedHostHost), requireHttps = false)

  private fun targetFile(name: String) = File(context.cacheDir, "${ApkDownloader.UPDATE_DIR}/$name")

  private fun assetUrl(name: String) = server.url("/$name").toString()

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  @Test
  fun matchingSizeAndDigestDownloadSucceedsAndKeepsTheFile() {
    val name = "StreamNova-0.1.1-arm64-v8a.apk"
    server.enqueue(MockResponse().setBody(payload))
    val asset = ReleaseAsset(
      name = name,
      downloadUrl = assetUrl(name),
      size = payloadBytes.size.toLong(),
      digest = "sha256:" + sha256(payloadBytes)
    )

    val progress = mutableListOf<Int?>()
    val file = downloader().download(context, asset) { progress.add(it) }

    assertTrue(file.exists())
    assertEquals(payloadBytes.size.toLong(), file.length())
    assertEquals(payload, file.readText())
    assertEquals(100, progress.filterNotNull().last())
  }

  @Test
  fun truncatedResponseAgainstDeclaredSizeIsRejectedAndDeleted() {
    val name = "truncated.apk"
    server.enqueue(MockResponse().setBody(payload))
    val asset = ReleaseAsset(
      name = name,
      downloadUrl = assetUrl(name),
      size = payloadBytes.size.toLong() + 5_000L
    )

    val error = assertThrows(DownloadException::class.java) {
      downloader().download(context, asset) {}
    }

    assertEquals(DownloadErrorReason.SIZE_MISMATCH, error.reason)
    assertFalse(targetFile(name).exists())
  }

  @Test
  fun wrongDigestIsRejectedAndDeleted() {
    val name = "wrong-digest.apk"
    server.enqueue(MockResponse().setBody(payload))
    val asset = ReleaseAsset(
      name = name,
      downloadUrl = assetUrl(name),
      size = payloadBytes.size.toLong(),
      digest = "sha256:" + "0".repeat(64)
    )

    val error = assertThrows(DownloadException::class.java) {
      downloader().download(context, asset) {}
    }

    assertEquals(DownloadErrorReason.CHECKSUM_MISMATCH, error.reason)
    assertFalse(targetFile(name).exists())
  }

  @Test
  fun correctDigestWithNoDeclaredSizeSucceeds() {
    val name = "digest-only.apk"
    server.enqueue(MockResponse().setBody(payload))
    val asset = ReleaseAsset(
      name = name,
      downloadUrl = assetUrl(name),
      digest = "sha256:" + sha256(payloadBytes)
    )

    val file = downloader().download(context, asset) {}

    assertEquals(payloadBytes.size.toLong(), file.length())
  }

  @Test
  fun absentSizeAndDigestStillProducesAVerifiedNonEmptyFile() {
    val name = "no-metadata.apk"
    server.enqueue(MockResponse().setBody(payload))
    val asset = ReleaseAsset(name = name, downloadUrl = assetUrl(name))

    val file = downloader().download(context, asset) {}

    assertTrue(file.exists())
    assertTrue(file.length() > 0L)
  }

  @Test
  fun progressIsMonotonicAndNeverGoesBackwards() {
    val name = "progress.apk"
    server.enqueue(MockResponse().setBody(payload))
    val asset = ReleaseAsset(name = name, downloadUrl = assetUrl(name))
    val progress = mutableListOf<Int?>()

    downloader().download(context, asset) { progress.add(it) }

    val percents = progress.filterNotNull()
    assertTrue("Nessuna progressione emessa", percents.isNotEmpty())
    percents.zipWithNext { a, b -> assertTrue("Progressione non monotona: $a -> $b", b >= a) }
    assertEquals(100, percents.last())
  }

  @Test
  fun cancellationRemovesThePartialFile() {
    val name = "cancelled.apk"
    server.enqueue(MockResponse().setBody(payload))
    val asset = ReleaseAsset(name = name, downloadUrl = assetUrl(name))

    assertThrows(CancellationException::class.java) {
      downloader().download(context, asset) { percent ->
        if (percent != null && percent >= 1) throw CancellationException("download annullato")
      }
    }

    assertFalse(targetFile(name).exists())
  }

  @Test
  fun httpErrorIsReportedWithATypedReason() {
    // 500 ha un motivo proprio (HTTP_SERVER): il vecchio HTTP_ERROR generico
    // nascondeva la differenza fra disservizio temporaneo e asset assente.
    val name = "error.apk"
    server.enqueue(MockResponse().setResponseCode(500))
    val asset = ReleaseAsset(name = name, downloadUrl = assetUrl(name))

    val error = assertThrows(DownloadException::class.java) {
      downloader().download(context, asset) {}
    }

    assertEquals(DownloadErrorReason.HTTP_SERVER, error.reason)
    assertFalse(targetFile(name).exists())
  }

  @Test
  fun httpErrorCodesAreMappedToDistinctReasons() {
    // Ogni codice ricorrente nelle Releases di GitHub produce un motivo distinto:
    // la UI può dire all'utente SE e COME riprovare, invece di un generico
    // "errore del server" per qualunque cosa non sia 2xx.
    // Il 408 NON è qui: OkHttp riprova automaticamente una GET che riceve 408,
    // quindi il mock si desincronizza (il mapping del 408 resta verificato in
    // ApkDownloaderErrorMappingTest).
    val codes = mapOf(
      400 to DownloadErrorReason.HTTP_BAD_REQUEST,
      401 to DownloadErrorReason.HTTP_UNAUTHORIZED,
      403 to DownloadErrorReason.HTTP_FORBIDDEN,
      404 to DownloadErrorReason.HTTP_NOT_FOUND,
      429 to DownloadErrorReason.HTTP_RATE_LIMITED,
      502 to DownloadErrorReason.HTTP_BAD_GATEWAY,
      503 to DownloadErrorReason.HTTP_SERVICE_UNAVAILABLE,
      504 to DownloadErrorReason.HTTP_GATEWAY_TIMEOUT
    )
    codes.forEach { (code, expectedReason) ->
      val name = "error-$code.apk"
      server.enqueue(MockResponse().setResponseCode(code))
      val asset = ReleaseAsset(name = name, downloadUrl = assetUrl(name))

      val error = assertThrows(DownloadException::class.java) {
        downloader().download(context, asset) {}
      }

      assertEquals("Codice HTTP $code", expectedReason, error.reason)
      assertFalse(targetFile(name).exists())
    }
  }

  @Test
  fun diagnosticsCaptureTheSuccessPath() {
    val name = "diagnostics-ok.apk"
    server.enqueue(MockResponse().setBody(payload))
    val asset = ReleaseAsset(
      name = name,
      downloadUrl = assetUrl(name),
      size = payloadBytes.size.toLong(),
      digest = "sha256:" + sha256(payloadBytes)
    )
    val diagnostics = DownloadDiagnostics(
      installedVersion = "1.0.4",
      requestedVersion = "1.0.5"
    )

    downloader().download(context, asset, diagnostics) {}

    assertEquals(200, diagnostics.httpCode)
    assertEquals(0, diagnostics.redirectCount)
    assertEquals("http://" + server.url("/").host, diagnostics.initialEndpoint)
    assertEquals(diagnostics.initialEndpoint, diagnostics.finalEndpoint)
    assertEquals(name, diagnostics.assetName)
    assertEquals(payloadBytes.size.toLong(), diagnostics.declaredSizeBytes)
    assertEquals(payloadBytes.size.toLong(), diagnostics.downloadedBytes)
    assertEquals(DownloadDiagnostics.CHECKSUM_OK, diagnostics.checksum)
    assertEquals(null, diagnostics.failedPhase)
    assertEquals(null, diagnostics.exceptionType)
    assertTrue((diagnostics.durationMs ?: -1L) >= 0L)
  }

  @Test
  fun diagnosticsCaptureTheRedirectChain() {
    // Redirect verso un host CONSENTITO (alias di loopback dello stesso server):
    // la catena ricostruita dalla diagnostica deve riportare hop iniziale e
    // finale diversi, con il conteggio dei redirect.
    val allowedHost = server.url("/").host
    val otherHost = if (allowedHost.equals("localhost", ignoreCase = true)) "127.0.0.1" else "localhost"
    val name = "diagnostics-redirect.apk"

    server.enqueue(
      MockResponse().setResponseCode(302)
        .setHeader("Location", "http://$otherHost:${server.port}/$name")
    )
    server.enqueue(MockResponse().setBody(payload))

    val downloader = ApkDownloader(
      allowedHosts = setOf(allowedHost, otherHost),
      requireHttps = false
    )
    val diagnostics = DownloadDiagnostics()
    downloader.download(context, ReleaseAsset(name = name, downloadUrl = assetUrl(name)), diagnostics) {}

    assertEquals(1, diagnostics.redirectCount)
    assertEquals("http://$allowedHost", diagnostics.initialEndpoint)
    assertEquals("http://$otherHost", diagnostics.finalEndpoint)
    assertEquals(200, diagnostics.httpCode)
    assertEquals(DownloadDiagnostics.CHECKSUM_SKIPPED, diagnostics.checksum)
  }

  @Test
  fun diagnosticsCaptureTheHttpFailure() {
    val name = "diagnostics-http-error.apk"
    server.enqueue(MockResponse().setResponseCode(503))
    val asset = ReleaseAsset(
      name = name,
      downloadUrl = assetUrl(name),
      size = payloadBytes.size.toLong()
    )
    val diagnostics = DownloadDiagnostics(
      installedVersion = "1.0.4",
      requestedVersion = "1.0.5"
    )

    assertThrows(DownloadException::class.java) {
      downloader().download(context, asset, diagnostics) {}
    }

    assertEquals(503, diagnostics.httpCode)
    assertEquals(DownloadErrorReason.HTTP_SERVICE_UNAVAILABLE, diagnostics.failureReason)
    assertEquals(DownloadDiagnostics.PHASE_RISPOSTA_HTTP, diagnostics.failedPhase)
    assertEquals("DownloadException", diagnostics.exceptionType)
    assertEquals(0L, diagnostics.downloadedBytes)
    assertEquals(name, diagnostics.assetName)
    assertEquals(payloadBytes.size.toLong(), diagnostics.declaredSizeBytes)
    assertEquals(null, diagnostics.checksum)
    assertFalse(targetFile(name).exists())
  }

  @Test
  fun assetUrlOutsideTheAllowedHostsIsRejectedBeforeAnyRequest() {
    val asset = ReleaseAsset(
      name = "app-release.apk",
      downloadUrl = "https://evil.example/app-release.apk"
    )

    val error = assertThrows(DownloadException::class.java) {
      ApkDownloader().download(context, asset) {}
    }

    assertEquals(DownloadErrorReason.HOST_NOT_ALLOWED, error.reason)
    assertEquals(0, server.requestCount)
  }

  @Test
  fun redirectToADisallowedHostIsBlockedByTheNetworkInterceptor() {
    // L'host del server locale è consentito; il redirect punta invece
    // all'altro alias di loopback, che NON è nell'elenco: il controllo deve
    // scattare sulla destinazione effettiva e non fidarsi dell'header Location.
    val allowedHost = server.url("/").host
    val otherHost = if (allowedHost.equals("localhost", ignoreCase = true)) "127.0.0.1" else "localhost"
    val name = "redirected.apk"

    server.enqueue(
      MockResponse().setResponseCode(302)
        .setHeader("Location", "http://$otherHost:${server.port}/$name")
    )
    server.enqueue(MockResponse().setBody(payload))

    val asset = ReleaseAsset(name = name, downloadUrl = assetUrl(name))
    val error = assertThrows(DownloadException::class.java) {
      downloader(allowedHost).download(context, asset) {}
    }

    assertEquals(DownloadErrorReason.HOST_NOT_ALLOWED, error.reason)
    assertFalse(targetFile(name).exists())
  }
}
