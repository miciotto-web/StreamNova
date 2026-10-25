package com.example.ui.screens.player

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Politica di piattaforma della cattura per l'anteprima di scrubbing.
 *
 * Le firme usate nei test sono quelle reali: Fire TV Stick 4K Max 2ª gen (`Amazon` /
 * `AFTMA08C15` / `karat` / `mt8696`, Fire OS 8.1.8.5 = API 30) per il caso non compatibile,
 * e un Android TV generico per il caso compatibile. Il tombstone alla prima pressione di
 * scrubbing arriva da quella Fire TV (vedi [SeekPreviewCaptureSupport]).
 */
class SeekPreviewCaptureSupportTest {

  private val tempDirs = mutableListOf<File>()

  @After
  fun cleanup() {
    tempDirs.forEach { runCatching { it.deleteRecursively() } }
    tempDirs.clear()
  }

  private fun tempDir(): File {
    val dir = Files.createTempDirectory("seekpreview-support").toFile()
    tempDirs += dir
    return dir
  }

  // ---------------------------------------------------------------------------
  // Firma della piattaforma
  // ---------------------------------------------------------------------------

  @Test
  fun `la Fire TV che ha abortito alla prima pressione non usa la cattura`() {
    assertFalse(
      SeekPreviewCaptureSupport.isCaptureSupportedByPlatform(
        manufacturer = "Amazon",
        board = "mt8696",
        hardware = "mt8696"
      )
    )
  }

  @Test
  fun `il produttore Amazon disattiva la cattura anche con un SoC diverso`() {
    assertFalse(
      SeekPreviewCaptureSupport.isCaptureSupportedByPlatform(
        manufacturer = "Amazon",
        board = "someOtherBoard",
        hardware = "someOtherBoard"
      )
    )
  }

  @Test
  fun `lo stesso SoC su un produttore diverso resta disattivato`() {
    assertFalse(
      SeekPreviewCaptureSupport.isCaptureSupportedByPlatform(
        manufacturer = "SomeVendor",
        board = "mt8696",
        hardware = "mt8696"
      )
    )
  }

  @Test
  fun `basta il board mt8696 con hardware sconosciuto`() {
    assertFalse(
      SeekPreviewCaptureSupport.isCaptureSupportedByPlatform(
        manufacturer = "SomeVendor",
        board = "mt8696",
        hardware = null
      )
    )
  }

  @Test
  fun `un Android TV generico mantiene la cattura`() {
    assertTrue(
      SeekPreviewCaptureSupport.isCaptureSupportedByPlatform(
        manufacturer = "Google",
        board = "amati",
        hardware = "amati"
      )
    )
  }

  @Test
  fun `la firma non dipende da maiuscole o spazi`() {
    assertFalse(
      SeekPreviewCaptureSupport.isCaptureSupportedByPlatform(
        manufacturer = " amazon ",
        board = " MT8696",
        hardware = "mt8696 "
      )
    )
    assertTrue(
      SeekPreviewCaptureSupport.isCaptureSupportedByPlatform(
        manufacturer = "google",
        board = "AMATI",
        hardware = "Amati"
      )
    )
  }

  @Test
  fun `una piattaforma senza informazioni non viene dichiarata incompatibile`() {
    // Fail-open dichiarato: l'euristica spegne solo firme note, così un device nuovo non
    // perde l'anteprima per una informazione mancante.
    assertTrue(
      SeekPreviewCaptureSupport.isCaptureSupportedByPlatform(
        manufacturer = null,
        board = null,
        hardware = null
      )
    )
    assertTrue(
      SeekPreviewCaptureSupport.isCaptureSupportedByPlatform(
        manufacturer = "",
        board = "  ",
        hardware = ""
      )
    )
  }

  // ---------------------------------------------------------------------------
  // Override manuale per la verifica su device
  // ---------------------------------------------------------------------------

  @Test
  fun `senza file di override non c'e nessuna decisione manuale`() {
    assertNull(SeekPreviewCaptureSupport.overrideFrom(null))
    assertNull(SeekPreviewCaptureSupport.overrideFrom(tempDir()))
  }

  @Test
  fun `il file force-on accende la cattura`() {
    val dir = tempDir()
    File(dir, SeekPreviewCaptureSupport.FORCE_ON_FILE).createNewFile()
    assertEquals(true, SeekPreviewCaptureSupport.overrideFrom(dir))
  }

  @Test
  fun `il file force-off spegne la cattura`() {
    val dir = tempDir()
    File(dir, SeekPreviewCaptureSupport.FORCE_OFF_FILE).createNewFile()
    assertEquals(false, SeekPreviewCaptureSupport.overrideFrom(dir))
  }

  @Test
  fun `con entrambi i file vince lo spegnimento`() {
    val dir = tempDir()
    File(dir, SeekPreviewCaptureSupport.FORCE_ON_FILE).createNewFile()
    File(dir, SeekPreviewCaptureSupport.FORCE_OFF_FILE).createNewFile()
    assertEquals(false, SeekPreviewCaptureSupport.overrideFrom(dir))
  }

  @Test
  fun `lo spegnimento manuale vale anche nella decisione operativa`() {
    val dir = tempDir()
    File(dir, SeekPreviewCaptureSupport.FORCE_OFF_FILE).createNewFile()
    // Deterministico su qualunque piattaforma di esecuzione: l'override precede la firma.
    assertFalse(SeekPreviewCaptureSupport.isCaptureSupported(dir))
  }

  @Test
  fun `l'accensione manuale vale anche nella decisione operativa`() {
    val dir = tempDir()
    File(dir, SeekPreviewCaptureSupport.FORCE_ON_FILE).createNewFile()
    assertTrue(SeekPreviewCaptureSupport.isCaptureSupported(dir))
  }
}
