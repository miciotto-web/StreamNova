package com.example.data.update

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * Contratto "l'asset validato dal controllo è l'asset scaricato": il controllo
 * (client GitHub puntato a un MockWebServer locale, ABI reali del dispositivo)
 * sceglie lo split del dispositivo e il download chiede esattamente quel file,
 * senza riselezionare e senza toccare gli altri.
 *
 * Ogni split ha un contenuto diverso, quindi il file scaricato dichiara da sé
 * quale asset è stato usato; la richiesta osservata dal server dice lo stesso.
 * Il download usa il downloader reale con una politica di test (host del server
 * locale, HTTP ammesso), come [ApkDownloaderIntegrationTest]: la policy di
 * produzione resta coperta da [ApkDownloaderHostPolicyTest].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class UpdateDownloadAssetIdentityTest {

  private lateinit var server: MockWebServer
  private lateinit var context: Context

  /** Sigle che il selettore riconosce: la prima ABI del dispositivo deve esserci. */
  private val publishedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

  private val deviceAbi: String get() = Build.SUPPORTED_ABIS?.firstOrNull().orEmpty()

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    context = ApplicationProvider.getApplicationContext()
    UpdateRepository.reset()
    UpdateRepository.client = GitHubUpdateClient(apiBaseUrl = server.url("/api/").toString())
    UpdateRepository.downloader =
      ApkDownloader(allowedHosts = setOf(server.url("/").host), requireHttps = false)
  }

  @After
  fun tearDown() {
    UpdateRepository.reset()
    UpdateRepository.client = GitHubUpdateClient()
    UpdateRepository.downloader = ApkDownloader()
    File(context.cacheDir, ApkDownloader.UPDATE_DIR).deleteRecursively()
    server.shutdown()
  }

  @Test
  fun theSplitChosenByTheCheckIsTheOnlyOneDownloaded() {
    assumeTrue("ABI del dispositivo non fra quelle pubblicate: $deviceAbi", deviceAbi in publishedAbis)

    val payloads = publishedAbis.associateWith { abi -> "contenuto-dello-split-$abi".repeat(64).toByteArray() }
    val names = payloads.mapValues { (abi, _) -> "StreamNova-1.0.1-$abi.apk" }
    val selectedName = names.getValue(deviceAbi)

    server.enqueue(MockResponse().setBody(releaseJson(names, payloads)))
    server.enqueue(MockResponse().setBody(payloads.getValue(deviceAbi).toString(Charsets.UTF_8)))

    UpdateRepository.checkForUpdates("1.0.0")
    val available = awaitCheck() as UpdateCheckState.UpdateAvailable
    assertEquals("1.0.1", available.newVersion)
    assertEquals(selectedName, available.assetName)

    UpdateRepository.downloadUpdate(context)
    val completed = awaitDownload() as UpdateDownloadState.Completed

    assertEquals(selectedName, File(completed.apkPath).name)
    assertEquals(
      payloads.getValue(deviceAbi).toString(Charsets.UTF_8),
      File(completed.apkPath).readBytes().toString(Charsets.UTF_8)
    )
    assertEquals(
      "Nella cache deve esserci solo lo split validato dal controllo",
      listOf(selectedName),
      downloadedFileNames()
    )

    // Due sole richieste: la release e l'asset scelto. Nessun altro split è
    // stato contattato, quindi il download non ha riselezionato nulla.
    val paths = takeRequestPaths()
    assertEquals(2, paths.size)
    assertEquals("/api/repos/miciotto-web/StreamNova/releases/latest", paths[0])
    assertEquals("/download/$selectedName", paths[1])
  }

  private fun downloadedFileNames(): List<String> =
    File(context.cacheDir, ApkDownloader.UPDATE_DIR)
      .listFiles()
      .orEmpty()
      .filter { it.isFile }
      .map { it.name }
      .sorted()

  private fun takeRequestPaths(): List<String> {
    val paths = mutableListOf<String>()
    while (true) {
      val request = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS) ?: break
      paths.add(request.path.orEmpty())
    }
    return paths
  }

  private fun awaitCheck(): UpdateCheckState {
    val deadline = System.currentTimeMillis() + AWAIT_MS
    while (System.currentTimeMillis() < deadline) {
      val state = UpdateRepository.state.value
      if (state !is UpdateCheckState.Idle && state !is UpdateCheckState.Checking) return state
      Thread.sleep(POLL_MS)
    }
    throw AssertionError("Controllo aggiornamenti non concluso: ${UpdateRepository.state.value}")
  }

  private fun awaitDownload(): UpdateDownloadState {
    val deadline = System.currentTimeMillis() + AWAIT_MS
    while (System.currentTimeMillis() < deadline) {
      val state = UpdateRepository.downloadState.value
      if (state !is UpdateDownloadState.Idle && state !is UpdateDownloadState.Downloading) return state
      Thread.sleep(POLL_MS)
    }
    throw AssertionError("Download non concluso: ${UpdateRepository.downloadState.value}")
  }

  private fun assetUrl(name: String) = server.url("/download/$name").toString()

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun releaseJson(names: Map<String, String>, payloads: Map<String, ByteArray>): String {
    val assets = names.entries.joinToString(",") { (abi, name) ->
      val payload = payloads.getValue(abi)
      """{"name":"$name","browser_download_url":"${assetUrl(name)}",""" +
        """"size":${payload.size},"digest":"sha256:${sha256(payload)}"}"""
    }
    return """
      {
        "tag_name": "v1.0.1-build.1003",
        "name": "StreamNova 1.0.1",
        "body": "selezione dell'APK per ABI",
        "html_url": "https://github.com/miciotto-web/StreamNova/releases/tag/v1.0.1-build.1003",
        "draft": false,
        "prerelease": false,
        "assets": [$assets]
      }
    """.trimIndent()
  }

  private companion object {
    const val AWAIT_MS = 15_000L
    const val POLL_MS = 20L
  }
}
