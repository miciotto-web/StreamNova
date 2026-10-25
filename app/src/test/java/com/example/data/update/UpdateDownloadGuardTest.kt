package com.example.data.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Guardia del download: senza un APK compatibile già validato dal controllo,
 * `downloadUpdate` non avvia nulla e riporta un errore tipizzato.
 *
 * Il controllo aggiornamenti non viene eseguito, quindi non esiste alcun asset
 * validato: è esattamente lo stato in cui il download non deve partire. Le
 * decisioni che portano a questo stato sono verificate senza Android in
 * [UpdateDecisionTest].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class UpdateDownloadGuardTest {

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    UpdateRepository.resetDownload()
  }

  @Test
  fun downloadDoesNotStartWithoutAValidatedCompatibleAsset() {
    UpdateRepository.downloadUpdate(context)

    assertEquals(
      UpdateDownloadState.Error(DownloadErrorReason.NO_APK_IN_RELEASE),
      UpdateRepository.downloadState.value
    )
    assertTrue(
      "Nessun file deve essere scritto quando manca un asset compatibile",
      downloadedFiles().isEmpty()
    )
  }

  @Test
  fun noDownloadFileIsLeftInTheCacheDirectory() {
    UpdateRepository.downloadUpdate(context)

    val dir = File(context.cacheDir, ApkDownloader.UPDATE_DIR)
    assertTrue(
      "La cartella dei download non deve contenere APK",
      !dir.exists() || dir.listFiles().orEmpty().none { it.name.endsWith(".apk") }
    )
  }

  private fun downloadedFiles(): List<String> =
    File(context.cacheDir, ApkDownloader.UPDATE_DIR).listFiles().orEmpty().map { it.name }
}
