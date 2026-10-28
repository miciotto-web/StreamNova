package com.example.data.update

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * Controllo aggiornamenti AUTOMATICO di avvio: parte una volta per sessione, in
 * background, e non tocca il percorso manuale delle Impostazioni.
 *
 * Copre le promesse fatte all'utente: nessuna richiesta duplicata, popup solo se
 * c'è davvero un aggiornamento installabile, nessun errore mostrato per un
 * controllo non richiesto, «Più tardi» che vale per la sessione, e download che
 * resta subordinato alla scelta esplicita e usa esattamente l'APK validato dal
 * controllo.
 *
 * Il client GitHub e il downloader reali sono puntati a un MockWebServer locale,
 * come [UpdateDownloadAssetIdentityTest].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class StartupUpdateCheckTest {

  private lateinit var server: MockWebServer
  private lateinit var context: Context

  /** Sigle riconosciute dal selettore: una deve corrispondere all'ABI del dispositivo. */
  private val publishedAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

  private val deviceAbi: String get() = Build.SUPPORTED_ABIS?.firstOrNull().orEmpty()

  private val installedVersion = "1.0.2"

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
    context = ApplicationProvider.getApplicationContext()
    UpdateRepository.resetSessionForTests()
    UpdateRepository.client = GitHubUpdateClient(apiBaseUrl = server.url("/api/").toString())
    UpdateRepository.downloader =
      ApkDownloader(allowedHosts = setOf(server.url("/").host), requireHttps = false)
  }

  @After
  fun tearDown() {
    UpdateRepository.resetSessionForTests()
    UpdateRepository.client = GitHubUpdateClient()
    UpdateRepository.downloader = ApkDownloader()
    File(context.cacheDir, ApkDownloader.UPDATE_DIR).deleteRecursively()
    server.shutdown()
  }

  @Test
  fun theStartupCheckOffersTheNewVersionOnceAndDownloadsNothing() {
    val payload = "contenuto-dello-split".repeat(64).toByteArray()
    val names = publishedAbis.associateWith { abi -> "StreamNova-1.0.3-$abi.apk" }
    server.enqueue(MockResponse().setBody(releaseJson("v1.0.3", "note di rilascio", names, payload)))

    UpdateRepository.checkForUpdatesOnStartup(installedVersion)
    UpdateRepository.checkForUpdatesOnStartup(installedVersion)

    val prompt = awaitPrompt()
    assertEquals(installedVersion, prompt.currentVersion)
    assertEquals("1.0.3", prompt.newVersion)
    assertEquals("note di rilascio", prompt.changelog)
    assertEquals(names.getValue(deviceAbi), prompt.assetName)

    // Il controllo automatico non parla al canale manuale: la riga e il dialog delle
    // Impostazioni restano l'esito di un'azione dell'utente.
    assertEquals(UpdateCheckState.Idle, UpdateRepository.state.value)
    assertEquals(UpdateDownloadState.Idle, UpdateRepository.downloadState.value)

    // Una sola richiesta: la release. Nessun APK scaricato senza la scelta dell'utente.
    assertEquals(1, server.requestCount)
    assertEquals(
      "/api/repos/miciotto-web/StreamNova/releases/latest",
      server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)?.path
    )
    assertTrue(
      "Nessun APK deve essere scritto prima della scelta dell'utente",
      downloadedFileNames().isEmpty()
    )
  }

  @Test
  fun upToDateAppGetsNoPopup() {
    val names = publishedAbis.associateWith { abi -> "StreamNova-$installedVersion-$abi.apk" }
    server.enqueue(
      MockResponse().setBody(releaseJson("v$installedVersion", "note", names, ByteArray(0)))
    )

    UpdateRepository.checkForUpdatesOnStartup(installedVersion)

    assertNoPromptWithin()
    assertEquals(UpdateCheckState.Idle, UpdateRepository.state.value)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun anOlderReleaseIsNotProposed() {
    val names = publishedAbis.associateWith { abi -> "StreamNova-1.0.1-$abi.apk" }
    server.enqueue(MockResponse().setBody(releaseJson("v1.0.1-build.1003", "note", names, ByteArray(0))))

    UpdateRepository.checkForUpdatesOnStartup(installedVersion)

    assertNoPromptWithin()
  }

  @Test
  fun anIncoherentReleaseIsNotProposed() {
    // Tag 1.0.3 con APK che dichiara 1.0.2: la release non è installabile in modo
    // verificabile e il controllo automatico non la propone.
    val names = publishedAbis.associateWith { abi -> "StreamNova-$installedVersion-$abi.apk" }
    server.enqueue(
      MockResponse().setBody(releaseJson("v1.0.3", "note", names, "payload".toByteArray()))
    )

    UpdateRepository.checkForUpdatesOnStartup(installedVersion)

    assertNoPromptWithin()
  }

  @Test
  fun aFailedStartupCheckStaysSilent() {
    server.enqueue(MockResponse().setResponseCode(500).setBody("errore del server"))

    UpdateRepository.checkForUpdatesOnStartup(installedVersion)

    assertNoPromptWithin()
    assertEquals(UpdateCheckState.Idle, UpdateRepository.state.value)
  }

  @Test
  fun anInvalidJsonResponseStaysSilent() {
    server.enqueue(MockResponse().setBody("<html>non è JSON</html>"))

    UpdateRepository.checkForUpdatesOnStartup(installedVersion)

    assertNoPromptWithin()
    assertEquals(UpdateCheckState.Idle, UpdateRepository.state.value)
  }

  @Test
  fun laterRemovesTheProposalForTheWholeSession() {
    val names = publishedAbis.associateWith { abi -> "StreamNova-1.0.3-$abi.apk" }
    server.enqueue(MockResponse().setBody(releaseJson("v1.0.3", "note", names, ByteArray(0))))

    UpdateRepository.checkForUpdatesOnStartup(installedVersion)
    awaitPrompt()

    UpdateRepository.dismissStartupPrompt()
    assertNull("«Più tardi» chiude il popup", UpdateRepository.startupPrompt.value)

    // Un nuovo avvio nella stessa sessione non ripropone nulla e non richiede GitHub.
    UpdateRepository.checkForUpdatesOnStartup(installedVersion)
    assertNoPromptWithin()
    assertEquals(1, server.requestCount)
  }

  @Test
  fun theManualCheckKeepsWorkingAfterLater() {
    val names = publishedAbis.associateWith { abi -> "StreamNova-1.0.3-$abi.apk" }
    val payload = "contenuto-dello-split".repeat(64).toByteArray()
    server.enqueue(MockResponse().setBody(releaseJson("v1.0.3", "note", names, payload)))
    server.enqueue(MockResponse().setBody(releaseJson("v1.0.3", "note", names, payload)))

    UpdateRepository.checkForUpdatesOnStartup(installedVersion)
    awaitPrompt()
    UpdateRepository.dismissStartupPrompt()

    // Il controllo manuale in Impostazioni resta intatto: stesso risultato, stesso
    // flusso di download già coperto dai test dell'updater.
    UpdateRepository.checkForUpdates(installedVersion)
    val manualState = awaitManualCheck()
    assertTrue(
      "Il controllo manuale deve offrire l'aggiornamento anche dopo «Più tardi»",
      manualState is UpdateCheckState.UpdateAvailable
    )
  }

  @Test
  fun theExplicitChoiceDownloadsExactlyTheValidatedAsset() {
    assumeDeviceAbiIsPublished()

    val payloads = publishedAbis.associateWith { abi -> "split-$abi".repeat(64).toByteArray() }
    val names = publishedAbis.associateWith { abi -> "StreamNova-1.0.3-$abi.apk" }
    val selectedName = names.getValue(deviceAbi)
    val selectedPayload = payloads.getValue(deviceAbi)

    server.enqueue(MockResponse().setBody(releaseJson("v1.0.3", "note", names, selectedPayload)))
    server.enqueue(MockResponse().setBody(selectedPayload.toString(Charsets.UTF_8)))

    UpdateRepository.checkForUpdatesOnStartup(installedVersion)
    awaitPrompt()
    assertTrue(
      "Il popup non deve aver scaricato nulla da solo",
      downloadedFileNames().isEmpty()
    )

    // Scelta esplicita: «Aggiorna».
    UpdateRepository.downloadUpdate(context)
    val completed = awaitDownload() as UpdateDownloadState.Completed

    assertEquals(selectedName, File(completed.apkPath).name)
    assertEquals(
      selectedPayload.toString(Charsets.UTF_8),
      File(completed.apkPath).readBytes().toString(Charsets.UTF_8)
    )
    assertEquals(listOf(selectedName), downloadedFileNames())

    // Due sole richieste: la release (controllo di avvio) e lo split validato.
    val releasePath = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)?.path
    val assetPath = server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)?.path
    assertEquals("/api/repos/miciotto-web/StreamNova/releases/latest", releasePath)
    assertEquals("/download/$selectedName", assetPath)
  }

  private fun assumeDeviceAbiIsPublished() {
    org.junit.Assume.assumeTrue(
      "ABI del dispositivo non fra quelle pubblicate: $deviceAbi",
      deviceAbi in publishedAbis
    )
  }

  private fun downloadedFileNames(): List<String> =
    File(context.cacheDir, ApkDownloader.UPDATE_DIR)
      .listFiles()
      .orEmpty()
      .filter { it.isFile }
      .map { it.name }
      .sorted()

  private fun assetUrl(name: String) = server.url("/download/$name").toString()

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun releaseJson(
    tag: String,
    body: String,
    names: Map<String, String>,
    payload: ByteArray
  ): String {
    val assets = names.values.joinToString(",") { name ->
      """{"name":"$name","browser_download_url":"${assetUrl(name)}",""" +
        """"size":${payload.size},"digest":"sha256:${sha256(payload)}"}"""
    }
    return """
      {
        "tag_name": "$tag",
        "name": "StreamNova",
        "body": "$body",
        "html_url": "https://github.com/miciotto-web/StreamNova/releases/tag/$tag",
        "draft": false,
        "prerelease": false,
        "assets": [$assets]
      }
    """.trimIndent()
  }

  private fun awaitPrompt(): UpdateCheckState.UpdateAvailable {
    val deadline = System.currentTimeMillis() + AWAIT_MS
    while (System.currentTimeMillis() < deadline) {
      UpdateRepository.startupPrompt.value?.let { return it }
      Thread.sleep(POLL_MS)
    }
    throw AssertionError("Popup dell'aggiornamento mai comparso")
  }

  /**
   * Attesa di un esito NEGATIVO: il popup non deve comparire entro un margine ampio
   * rispetto a un controllo che, con la risposta già disponibile sul server locale,
   * si conclude in pochi millisecondi. Il margine serve solo a non dichiarare un
   * falso positivo per una lentezza anomala del parsing.
   */
  private fun assertNoPromptWithin(windowMs: Long = NO_PROMPT_WINDOW_MS) {
    val deadline = System.currentTimeMillis() + windowMs
    while (System.currentTimeMillis() < deadline) {
      val prompt = UpdateRepository.startupPrompt.value
      if (prompt != null) {
        throw AssertionError("Popup non atteso: aggiornamento ${prompt.newVersion}")
      }
      Thread.sleep(POLL_MS)
    }
  }

  private fun awaitManualCheck(): UpdateCheckState {
    val deadline = System.currentTimeMillis() + AWAIT_MS
    while (System.currentTimeMillis() < deadline) {
      val state = UpdateRepository.state.value
      if (state !is UpdateCheckState.Idle && state !is UpdateCheckState.Checking) return state
      Thread.sleep(POLL_MS)
    }
    throw AssertionError("Controllo manuale non concluso: ${UpdateRepository.state.value}")
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

  private companion object {
    const val AWAIT_MS = 15_000L
    const val POLL_MS = 20L
    const val NO_PROMPT_WINDOW_MS = 2_000L
  }
}
