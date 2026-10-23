package com.example.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Logica di decisione del controllo aggiornamenti: confronto fra versione
 * installata, tag della release e versione dell'asset APK.
 *
 * Include il caso storico della release con tag `v1.0.0` e asset `V0.1.0`,
 * che NON deve essere proposta come aggiornamento.
 */
class UpdateDecisionTest {

  private val installed = AppVersion(0, 1, 1)

  private fun release(
    tag: String,
    assets: List<ReleaseAsset>,
    body: String? = null
  ) = LatestRelease(
    tagName = tag,
    version = AppVersion.parse(tag)!!,
    releaseUrl = "https://github.com/miciotto-web/StreamNova/releases/tag/$tag",
    assets = assets,
    body = body
  )

  private fun apk(name: String, size: Long? = null, digest: String? = null) = ReleaseAsset(
    name = name,
    downloadUrl = "https://github.com/miciotto-web/StreamNova/releases/download/x/$name",
    size = size,
    digest = digest,
    version = AppVersion.fromAssetName(name)
  )

  @Test
  fun higherVersionIsAnUpdate() {
    val state = evaluateUpdate(
      installed,
      release("v0.1.2", listOf(apk("StreamNova-0.1.2-arm64-v8a.apk")), body = "fix")
    )

    val available = state as UpdateCheckState.UpdateAvailable
    assertEquals("0.1.1", available.currentVersion)
    assertEquals("0.1.2", available.newVersion)
    assertEquals("StreamNova-0.1.2-arm64-v8a.apk", available.assetName)
    assertEquals("fix", available.changelog)
  }

  @Test
  fun equalVersionIsUpToDate() {
    val state = evaluateUpdate(installed, release("v0.1.1", listOf(apk("StreamNova-0.1.1.apk"))))
    assertEquals("0.1.1", (state as UpdateCheckState.UpToDate).currentVersion)
  }

  @Test
  fun lowerVersionIsUpToDate() {
    val state = evaluateUpdate(installed, release("v0.1.0", listOf(apk("StreamNova-0.1.0.apk"))))
    assertTrue(state is UpdateCheckState.UpToDate)
  }

  @Test
  fun tagAndAssetVersionDisagreementIsAMismatch() {
    // Caso storico: tag v1.0.0 con asset V0.1.0. Il tag è numericamente
    // maggiore della versione installata, ma il file pubblicato non lo è:
    // l'aggiornamento non deve essere proposto.
    val state = evaluateUpdate(
      installed,
      release("v1.0.0", listOf(apk("StreamNova-V0.1.0-arm64-v8a.apk")))
    )

    val mismatch = state as UpdateCheckState.VersionMismatch
    assertEquals("0.1.1", mismatch.currentVersion)
    assertEquals("1.0.0", mismatch.tagVersion)
    assertEquals("0.1.0", mismatch.assetVersion)
    assertEquals("StreamNova-V0.1.0-arm64-v8a.apk", mismatch.assetName)
  }

  @Test
  fun mismatchIsDetectedOnAnyAssetOfTheRelease() {
    // Anche se l'asset preferito fosse "app-release.apk" (senza versione), un
    // asset ABI con versione discordante rende la release incoerente.
    val state = evaluateUpdate(
      installed,
      release(
        "v1.0.0",
        listOf(
          apk("app-release.apk"),
          apk("StreamNova-0.1.0-arm64-v8a.apk")
        )
      )
    )
    assertTrue(state is UpdateCheckState.VersionMismatch)
  }

  @Test
  fun matchingTagAndAssetVersionsAreAccepted() {
    val state = evaluateUpdate(
      installed,
      release("v0.1.2", listOf(apk("StreamNova-0.1.2-armeabi-v7a.apk")))
    )
    assertTrue(state is UpdateCheckState.UpdateAvailable)
  }

  @Test
  fun assetWithoutExtractableVersionFallsBackToTheTag() {
    val state = evaluateUpdate(installed, release("v0.1.2", listOf(apk("app-release.apk"))))
    val available = state as UpdateCheckState.UpdateAvailable
    assertEquals("0.1.2", available.newVersion)
    assertEquals("app-release.apk", available.assetName)
  }

  @Test
  fun releaseWithoutApkAssetsUsesTheTagVersion() {
    val state = evaluateUpdate(
      installed,
      release("v0.1.2", listOf(ReleaseAsset("notes.txt", "https://github.com/notes.txt")))
    )
    val available = state as UpdateCheckState.UpdateAvailable
    assertEquals("0.1.2", available.newVersion)
    assertEquals(null, available.assetName)
  }

  @Test
  fun nonApkAssetIsNeverSelectedForDownload() {
    val assets = listOf(
      ReleaseAsset("checksums.txt", "https://github.com/checksums.txt"),
      ReleaseAsset("changelog.md", "https://github.com/changelog.md")
    )
    assertEquals(null, selectApkAsset(assets))
  }

  @Test
  fun selectApkAssetPrefersTheGenericReleaseApk() {
    val assets = listOf(
      apk("StreamNova-0.1.2-arm64-v8a.apk"),
      apk("app-release.apk")
    )
    assertEquals("app-release.apk", selectApkAsset(assets)?.name)
  }

  @Test
  fun selectApkAssetIsCaseInsensitive() {
    assertEquals("APP.APK", selectApkAsset(listOf(apk("APP.APK")))?.name)
  }
}
