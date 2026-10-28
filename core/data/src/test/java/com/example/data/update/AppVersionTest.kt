package com.example.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing e confronto delle versioni, incluse le versioni estratte dal nome
 * dell'asset APK. Test deterministici, senza rete e senza Android.
 */
class AppVersionTest {

  @Test
  fun parsesTagWithAndWithoutPrefix() {
    assertEquals(AppVersion(0, 1, 1), AppVersion.parse("v0.1.1"))
    assertEquals(AppVersion(0, 1, 1), AppVersion.parse("0.1.1"))
    assertEquals(AppVersion(0, 1, 1), AppVersion.parse("V0.1.1"))
    assertEquals(AppVersion(1, 0, 0), AppVersion.parse("v1.0.0"))
    assertEquals(AppVersion(1, 0, 0), AppVersion.parse("  1.0.0  "))
  }

  @Test
  fun parsesPartialAndQualifiedVersions() {
    assertEquals(AppVersion(1, 0, 0), AppVersion.parse("1.0"))
    assertEquals(AppVersion(1, 0, 0), AppVersion.parse("v1"))
    assertEquals(AppVersion(1, 2, 3), AppVersion.parse("v1.2.3-beta.1"))
    assertEquals(AppVersion(1, 2, 3), AppVersion.parse("1.2.3+build.7"))
  }

  @Test
  fun rejectsNonNumericVersions() {
    assertNull(AppVersion.parse(null))
    assertNull(AppVersion.parse(""))
    assertNull(AppVersion.parse("   "))
    assertNull(AppVersion.parse("latest"))
    assertNull(AppVersion.parse("v"))
    assertNull(AppVersion.parse("1.2.3.4"))
  }

  @Test
  fun comparesNumericallyComponentByComponent() {
    assertTrue(AppVersion.parse("0.1.1")!! < AppVersion.parse("0.1.2")!!)
    assertTrue(AppVersion.parse("0.1.9")!! < AppVersion.parse("0.2.0")!!)
    assertTrue(AppVersion.parse("0.9.9")!! < AppVersion.parse("1.0.0")!!)
    assertTrue(AppVersion.parse("1.0.0")!! < AppVersion.parse("1.0.10")!!)
    assertEquals(0, AppVersion.parse("v0.1.1")!!.compareTo(AppVersion.parse("0.1.1")!!))
  }

  @Test
  fun canonicalStringIsIndependentFromTagPrefix() {
    assertEquals("0.1.1", AppVersion.parse("v0.1.1").toString())
    assertEquals("1.0.0", AppVersion(1, 0, 0).toString())
  }

  @Test
  fun extractsVersionFromVersionedAssetName() {
    assertEquals(
      AppVersion(0, 1, 0),
      AppVersion.fromAssetName("StreamNova-V0.1.0-arm64-v8a.apk")
    )
    assertEquals(
      AppVersion(0, 1, 1),
      AppVersion.fromAssetName("StreamNova-0.1.1-armeabi-v7a.apk")
    )
    assertEquals(
      AppVersion(0, 1, 1),
      AppVersion.fromAssetName("StreamNova-0.1.1-x86_64.apk")
    )
    assertEquals(AppVersion(1, 0, 0), AppVersion.fromAssetName("StreamNova-v1.0.0.apk"))
    assertEquals(AppVersion(1, 0, 0), AppVersion.fromAssetName("streamnova-1.0.apk"))
    assertEquals(
      AppVersion(0, 1, 1),
      AppVersion.fromAssetName("StreamNova-0.1.1-beta.apk")
    )
  }

  @Test
  fun extractsVersionFromThePlannedAssetNamesOfRelease101() {
    // Nomi previsti per la 1.0.1: la versione va letta per intero, senza che le
    // cifre delle sigle ABI ("v8a", "v7a", "x86_64") entrino nel confronto.
    listOf(
      "StreamNova-1.0.1-arm64-v8a.apk",
      "StreamNova-1.0.1-armeabi-v7a.apk",
      "StreamNova-1.0.1-x86.apk",
      "StreamNova-1.0.1-x86_64.apk"
    ).forEach { name ->
      assertEquals("Versione errata per $name", AppVersion(1, 0, 1), AppVersion.fromAssetName(name))
    }
    assertEquals(
      AppVersion(1, 0, 1),
      AppVersion.fromAssetName("StreamNova-1.0.1-arm64-v8a-release.apk")
    )
  }

  @Test
  fun neverReadsAbiTagsAsVersions() {
    // "arm64-v8a", "armeabi-v7a" e "x86_64" non contengono una coppia di
    // componenti numeriche separate da punto: non sono versioni.
    assertNull(AppVersion.fromAssetName("app-arm64-v8a.apk"))
    assertNull(AppVersion.fromAssetName("app-armeabi-v7a.apk"))
    assertNull(AppVersion.fromAssetName("app-x86_64.apk"))
    assertNull(AppVersion.fromAssetName("arm64-v8a"))
    // Nomi senza versione prodotti da AGP con gli ABI split (release 1.0.0).
    assertNull(AppVersion.fromAssetName("StreamNova-arm64-v8a-release.apk"))
    assertNull(AppVersion.fromAssetName("StreamNova-x86_64-release.apk"))
  }

  @Test
  fun assetWithoutVersionYieldsNull() {
    // Asset senza versione: nulla da confrontare con il tag, non è un mismatch.
    assertNull(AppVersion.fromAssetName("app-release.apk"))
    assertNull(AppVersion.fromAssetName("StreamNova.apk"))
    assertNull(AppVersion.fromAssetName(null))
    assertNull(AppVersion.fromAssetName(""))
  }
}
