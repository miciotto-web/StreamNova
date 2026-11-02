package com.example.data.image

import androidx.test.core.app.ApplicationProvider
import coil.ImageLoaderFactory
import coil.annotation.ExperimentalCoilApi
import com.example.StreamNovaApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifica la configurazione Coil dell'app: il singleton dell'ImageLoader non è più
 * quello implicito della libreria, ma quello fornito da [StreamNovaApplication], con la
 * disk cache nella directory che "Svuota cache" conosce e rispetta.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageCacheConfigurationTest {

  @Test
  fun applicationFornisceUnImageLoaderConDiskCacheDedicata() {
    val app = ApplicationProvider.getApplicationContext<StreamNovaApplication>()
    assertTrue(
      "StreamNovaApplication deve implementare ImageLoaderFactory",
      app is ImageLoaderFactory
    )

    val expectedDir = ImageCache.directory(app)
    assertEquals("image_cache", expectedDir.name)

    val loader = app.newImageLoader()
    try {
      @OptIn(ExperimentalCoilApi::class)
      val diskCacheDir = loader.diskCache?.directory?.toFile()
      assertNotNull("La disk cache immagini deve essere configurata", diskCacheDir)
      assertEquals(expectedDir.absolutePath, diskCacheDir!!.absolutePath)
    } finally {
      loader.shutdown()
    }
  }
}
