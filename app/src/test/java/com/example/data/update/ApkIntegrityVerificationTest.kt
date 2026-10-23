package com.example.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Verifica dell'integrità del file scaricato e delle primitive di checksum.
 * Nessuna rete: i file di prova sono creati su disco temporaneo.
 */
class ApkIntegrityVerificationTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private var fileCounter = 0

  private fun file(content: ByteArray): File {
    fileCounter++
    return tempFolder.newFile("app-release-$fileCounter.apk").apply { writeBytes(content) }
  }

  @Test
  fun sha256MatchesKnownVectors() {
    assertEquals(
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      sha256Hex(file(ByteArray(0)))
    )
    assertEquals(
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
      sha256Hex(file("abc".toByteArray(Charsets.UTF_8)))
    )
  }

  @Test
  fun parsesOnlyWellFormedSha256Digests() {
    val hex = "a".repeat(64)
    assertEquals(hex, parseSha256Digest("sha256:$hex"))
    assertEquals(hex, parseSha256Digest("SHA256:$hex"))
    assertEquals(hex, parseSha256Digest("  sha256:$hex  "))
    assertEquals("A".repeat(64).lowercase(), parseSha256Digest("sha256:${"A".repeat(64)}"))
  }

  @Test
  fun unverifiableDigestsAreIgnoredNotInvented() {
    assertNull(parseSha256Digest(null))
    assertNull(parseSha256Digest(""))
    assertNull(parseSha256Digest("sha256:"))
    assertNull(parseSha256Digest("md5:d41d8cd98f00b204e9800998ecf8427e"))
    assertNull(parseSha256Digest("sha256:not-hex"))
    assertNull(parseSha256Digest("sha256:" + "a".repeat(63)))
  }

  @Test
  fun emptyFileIsRejected() {
    val empty = tempFolder.newFile("empty.apk")
    val error = assertThrows(DownloadException::class.java) {
      verifyDownloadedApk(empty, expectedSize = null, expectedDigest = null)
    }
    assertEquals(DownloadErrorReason.FILE_MISSING, error.reason)
  }

  @Test
  fun truncatedFileAgainstDeclaredSizeIsRejected() {
    val content = "A".repeat(100).toByteArray(Charsets.UTF_8)
    val apk = file(content)
    val error = assertThrows(DownloadException::class.java) {
      verifyDownloadedApk(apk, expectedSize = 200L, expectedDigest = null)
    }
    assertEquals(DownloadErrorReason.SIZE_MISMATCH, error.reason)
  }

  @Test
  fun fileLargerThanDeclaredSizeIsRejected() {
    val apk = file("A".repeat(300).toByteArray(Charsets.UTF_8))
    val error = assertThrows(DownloadException::class.java) {
      verifyDownloadedApk(apk, expectedSize = 100L, expectedDigest = null)
    }
    assertEquals(DownloadErrorReason.SIZE_MISMATCH, error.reason)
  }

  @Test
  fun matchingSizeAndDigestPasses() {
    val content = "StreamNova test payload".toByteArray(Charsets.UTF_8)
    val apk = file(content)
    verifyDownloadedApk(
      apk,
      expectedSize = content.size.toLong(),
      expectedDigest = "sha256:${sha256Hex(apk)}"
    )
  }

  @Test
  fun wrongDigestIsRejected() {
    val apk = file("payload".toByteArray(Charsets.UTF_8))
    val error = assertThrows(DownloadException::class.java) {
      verifyDownloadedApk(apk, expectedSize = null, expectedDigest = "sha256:" + "0".repeat(64))
    }
    assertEquals(DownloadErrorReason.CHECKSUM_MISMATCH, error.reason)
  }

  @Test
  fun absentSizeAndDigestStillRequireANonEmptyFile() {
    val apk = file("payload".toByteArray(Charsets.UTF_8))
    verifyDownloadedApk(apk, expectedSize = null, expectedDigest = null)
    assertTrue(apk.exists())
  }
}
