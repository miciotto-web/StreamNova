package com.example.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Cartella in-app dei backup (fallback SAF-less per Fire TV) e export su file:
 * scrittura atomica, lista ordinata, nessun file incompleto presentato come valido.
 */
@RunWith(RobolectricTestRunner::class)
class LocalBackupStoreTest {

  private lateinit var context: Context

  @Before
  fun setUp() = kotlinx.coroutines.test.runTest {
    context = ApplicationProvider.getApplicationContext()
    LocalBackupStore.backupsDir(context).deleteRecursively()
    // I repository letto dal SyncConfigBuilder: inizializzati come in produzione.
    com.example.data.prefs.AppSettingsRepository.init(context)
    com.example.data.prefs.SettingsRepository.init(context)
    com.example.data.prefs.AppSettingsRepository.awaitLoaded()
  }

  @After
  fun tearDown() {
    LocalBackupStore.backupsDir(context).deleteRecursively()
  }

  @Test
  fun `suggested backup file name matches the specification`() {
    // StreamNova_Backup_YYYY-MM-DD_HH-mm-ss.json
    val name = BackupManager.suggestedBackupFileName(1_763_830_000_000L) // 2025-11-22 ~16:20 UTC
    assertTrue(name.startsWith("StreamNova_Backup_"))
    assertTrue(name.endsWith(".json"))
    val regex = Regex("""StreamNova_Backup_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}\.json""")
    assertTrue("Nome file non conforme: $name", regex.matches(name))
  }

  @Test
  fun `backups dir is created and lists json files newest first`() {
    val dir = LocalBackupStore.backupsDir(context)
    assertTrue(dir.exists())
    val older = File(dir, "StreamNova_Backup_2026-11-20_10-00-00.json")
    val newer = File(dir, "StreamNova_Backup_2026-11-22_18-00-00.json")
    val notBackup = File(dir, "note.txt")
    older.writeText("{}")
    Thread.sleep(20)
    newer.writeText("{}")
    notBackup.writeText("x")

    val listed = LocalBackupStore.listBackups(context)
    assertEquals(listOf(newer.name, older.name), listed.map { it.name })
  }

  @Test
  fun `describe shows date and size`() {
    val dir = LocalBackupStore.backupsDir(context)
    val file = File(dir, "a.json")
    file.writeText("abcd".repeat(100)) // 400 byte
    val label = LocalBackupStore.describe(file)
    assertTrue(label.contains("KB") || label.contains("B"))
    assertTrue(label.contains("·"))
  }

  @Test
  fun `exportToFile writes a valid backup atomically`() = runTest {
    val target = File(LocalBackupStore.backupsDir(context), BackupManager.suggestedBackupFileName())
    val result = BackupManager.exportToFile(
      context = context,
      target = target,
      includeCredentials = false,
      appVersionName = "test",
      appVersionCode = 1
    )
    assertTrue(result.isSuccess)
    val exported = result.getOrThrow()
    assertTrue(exported.sizeBytes > 0)
    assertTrue(target.exists())
    // Nessun file temporaneo residuo: un export interrotto non lascia file incompleti.
    assertEquals(0, LocalBackupStore.backupsDir(context).listFiles { f -> f.name.endsWith(".tmp") }!!.size)

    // Il file scritto è leggibile e supera la validazione del codec (round-trip).
    val preview = BackupManager.previewFile(target)
    assertTrue(preview.isSuccess)
    val envelope = preview.getOrThrow()
    assertEquals(BACKUP_FORMAT_VERSION, envelope.formatVersion)
    assertEquals("test", envelope.appVersionName)
    assertFalse(envelope.includesCredentials)
  }

  @Test
  fun `exportToFile without credentials never serializes secrets`() = runTest {
    // Precondition: una chiave TorBox salvata nelle preferenze dell'app di test.
    com.example.data.prefs.AppSettingsRepository.setTorBoxApiKey("tb-secret-for-test")

    val target = File(LocalBackupStore.backupsDir(context), "no_secrets.json")
    val result = BackupManager.exportToFile(context = context, target = target)
    assertTrue(result.isSuccess)
    val json = target.readText()
    assertFalse("Le chiavi API non devono mai finire su disco senza consenso esplicito", json.contains("tb-secret-for-test"))
  }

  @Test
  fun `previewFile rejects a corrupted backup`() = runTest {
    val target = File(LocalBackupStore.backupsDir(context), "corrotto.json")
    target.writeText("{rotto")
    val preview = BackupManager.previewFile(target)
    assertTrue(preview.isFailure)
  }

  @Test
  fun `previewFile rejects an incompatible future format`() = runTest {
    val target = File(LocalBackupStore.backupsDir(context), "futuro.json")
    target.writeText(
      """{"formatVersion": 999, "payload": {"addons": [{"url": "https://a.example/m.json", "name": "A", "enabled": true}]}}"""
    )
    val preview = BackupManager.previewFile(target)
    assertTrue(preview.isFailure)
    assertEquals(
      BackupError.UNSUPPORTED_VERSION.message,
      preview.exceptionOrNull()!!.message
    )
  }

  @Test
  fun `previewJson validates a drive payload the same way`() = runTest {
    val good = """{"formatVersion": 1, "payload": {"addons": [{"url": "https://a.example/m.json", "name": "A", "enabled": true}]}}"""
    val result = BackupManager.previewJson(good)
    assertTrue(result.isSuccess)
    assertNotNull(result.getOrThrow().payload.addons)

    val empty = """{"formatVersion": 1}"""
    assertTrue(BackupManager.previewJson(empty).isFailure)
    assertTrue(BackupManager.previewJson(null).isFailure)
  }
}
