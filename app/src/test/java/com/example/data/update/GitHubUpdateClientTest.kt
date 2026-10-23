package com.example.data.update

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Parsing della risposta della GitHub Releases API su un MockWebServer locale:
 * nessuna richiesta reale a GitHub.
 *
 * Contratti verificati: metadati (body/size/digest), versione estratta dal nome
 * dell'asset, JSON incompleto/malformato, tag non valido e mappatura dei codici
 * HTTP significativi su motivi tipizzati.
 */
class GitHubUpdateClientTest {

  private lateinit var server: MockWebServer

  @Before
  fun setUp() {
    server = MockWebServer()
    server.start()
  }

  @After
  fun tearDown() {
    server.shutdown()
  }

  private fun client() = GitHubUpdateClient(
    owner = "miciotto-web",
    repo = "StreamNova",
    apiBaseUrl = server.url("/").toString()
  )

  private fun enqueueJson(body: String) {
    server.enqueue(MockResponse().setResponseCode(200).setBody(body))
  }

  private fun fetchFailure(): UpdateCheckException =
    assertThrows(UpdateCheckException::class.java) { client().fetchLatestRelease() }

  @Test
  fun parsesTagBodySizeDigestAndAssetVersion() {
    val digest = "sha256:" + "a".repeat(64)
    enqueueJson(
      """
      {
        "tag_name": "v0.1.1",
        "name": "StreamNova 0.1.1",
        "body": "Correzioni e miglioramenti",
        "html_url": "https://github.com/miciotto-web/StreamNova/releases/tag/v0.1.1",
        "draft": false,
        "prerelease": false,
        "assets": [
          {
            "name": "StreamNova-0.1.1-arm64-v8a.apk",
            "browser_download_url": "https://github.com/miciotto-web/StreamNova/releases/download/v0.1.1/StreamNova-0.1.1-arm64-v8a.apk",
            "size": 16384000,
            "digest": "$digest"
          }
        ]
      }
      """.trimIndent()
    )

    val release = client().fetchLatestRelease()

    assertEquals("v0.1.1", release.tagName)
    assertEquals(AppVersion(0, 1, 1), release.version)
    assertEquals("Correzioni e miglioramenti", release.body)
    assertEquals(1, release.assets.size)
    val asset = release.assets.single()
    assertEquals("StreamNova-0.1.1-arm64-v8a.apk", asset.name)
    assertEquals(16384000L, asset.size)
    assertEquals(digest, asset.digest)
    assertEquals(AppVersion(0, 1, 1), asset.version)
  }

  @Test
  fun toleratesMissingOptionalFields() {
    enqueueJson(
      """
      {
        "tag_name": "0.1.2",
        "assets": [
          { "name": "app-release.apk", "browser_download_url": "https://github.com/x/y.apk" }
        ]
      }
      """.trimIndent()
    )

    val release = client().fetchLatestRelease()

    assertEquals(AppVersion(0, 1, 2), release.version)
    assertNull(release.body)
    assertNull(release.releaseUrl)
    val asset = release.assets.single()
    assertNull(asset.size)
    assertNull(asset.digest)
    assertNull(asset.version)
  }

  @Test
  fun dropsAssetsWithoutNameOrDownloadUrl() {
    enqueueJson(
      """
      {
        "tag_name": "v0.1.2",
        "assets": [
          { "name": "", "browser_download_url": "https://github.com/x.apk" },
          { "name": "app.apk", "browser_download_url": "" },
          { "name": "good.apk", "browser_download_url": "https://github.com/good.apk" }
        ]
      }
      """.trimIndent()
    )

    val release = client().fetchLatestRelease()

    assertEquals(listOf("good.apk"), release.assets.map { it.name })
  }

  @Test
  fun releaseWithoutAssetsIsStillReadable() {
    enqueueJson("""{ "tag_name": "v0.1.2" }""")
    val release = client().fetchLatestRelease()
    assertTrue(release.assets.isEmpty())
  }

  @Test
  fun incompleteJsonIsRejected() {
    enqueueJson("""{ "name": "senza tag" }""")
    assertEquals(UpdateCheckErrorReason.INVALID_RELEASE, fetchFailure().reason)
  }

  @Test
  fun malformedJsonIsRejected() {
    enqueueJson("this is not json")
    assertEquals(UpdateCheckErrorReason.INVALID_RELEASE, fetchFailure().reason)
  }

  @Test
  fun nonNumericTagIsRejected() {
    enqueueJson("""{ "tag_name": "latest" }""")
    assertEquals(UpdateCheckErrorReason.INVALID_RELEASE, fetchFailure().reason)
  }

  @Test
  fun forbiddenAndRateLimitedResponsesMapToRateLimited() {
    server.enqueue(MockResponse().setResponseCode(403))
    assertEquals(UpdateCheckErrorReason.RATE_LIMITED, fetchFailure().reason)

    server.enqueue(MockResponse().setResponseCode(429))
    assertEquals(UpdateCheckErrorReason.RATE_LIMITED, fetchFailure().reason)
  }

  @Test
  fun notFoundResponseMapsToNotFound() {
    server.enqueue(MockResponse().setResponseCode(404))
    assertEquals(UpdateCheckErrorReason.NOT_FOUND, fetchFailure().reason)
  }

  @Test
  fun serverErrorMapsToUnknownNotToNotFound() {
    server.enqueue(MockResponse().setResponseCode(500))
    assertEquals(UpdateCheckErrorReason.UNKNOWN, fetchFailure().reason)
  }

  @Test
  fun networkFailureMapsToNetwork() {
    server.shutdown()
    assertEquals(UpdateCheckErrorReason.NETWORK, fetchFailure().reason)
  }

  @Test
  fun emptyBodyIsRejected() {
    server.enqueue(MockResponse().setResponseCode(200).setBody(""))
    assertEquals(UpdateCheckErrorReason.INVALID_RELEASE, fetchFailure().reason)
  }
}
