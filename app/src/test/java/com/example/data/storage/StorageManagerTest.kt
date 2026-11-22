package com.example.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Contratto dello Storage Manager: misura reale e formattazione. */
class StorageManagerTest {

  @Test
  fun `formats bytes with readable units`() {
    assertEquals("0 B", StorageManager.formatBytes(0))
    assertEquals("512 B", StorageManager.formatBytes(512))
    assertEquals("1 KB", StorageManager.formatBytes(1024))
    assertEquals("1.5 MB", StorageManager.formatBytes(1024L * 1024 + 512 * 1024))
    assertEquals("2.00 GB", StorageManager.formatBytes(2L * 1024 * 1024 * 1024))
  }

  @Test
  fun `measures nested directories`() {
    val root = createTempDir(prefix = "storage-test")
    try {
      val nested = File(root, "a/b").apply { mkdirs() }
      File(root, "a/one.bin").writeBytes(ByteArray(100))
      File(nested, "two.bin").writeBytes(ByteArray(250))
      assertEquals(350L, StorageManager.directorySizeBytes(root))
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun `missing directory measures zero`() {
    assertEquals(0L, StorageManager.directorySizeBytes(File("definitely-missing-dir-xyz")))
    assertEquals(0L, StorageManager.directorySizeBytes(null))
  }

  @Test
  fun `breakdown total is the sum of measured areas`() {
    val breakdown = StorageBreakdown(
      imageCacheBytes = 100,
      onlineSubtitlesBytes = 50,
      appCacheBytes = 25,
      externalCacheBytes = 10,
      updateFilesBytes = 5
    )
    assertEquals(190L, breakdown.totalBytes)
    assertTrue(breakdown.describe().contains("Immagini"))
  }
}
