package com.example.ui.screens.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Creazione differita della sorgente di anteprima.
 *
 * Il player di preview NON deve nascere all'apertura della schermata: la factory
 * viene registrata subito, ma [SeekPreviewRegistry.source] la esegue solo quando
 * il controller collega una sorgente, cioè alla prima pressione di scrubbing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeekPreviewLazySourceTest {

  private class FakeSource : SeekPreviewSource {
    var released = false
    override val isAvailable: Boolean = true
    override suspend fun load(positionMs: Long): SeekPreviewFrame? = null
    override fun release() {
      released = true
    }
  }

  @Before
  fun setUp() = SeekPreviewRegistry.clear()

  @After
  fun tearDown() = SeekPreviewRegistry.clear()

  @Test
  fun `installare la factory non crea la sorgente`() {
    var creations = 0
    SeekPreviewRegistry.installLazy {
      creations++
      FakeSource()
    }

    assertEquals("nessuna creazione prima dello scrubbing", 0, creations)
  }

  @Test
  fun `la sorgente nasce solo quando parte il primo scrubbing`() = runTest {
    var creations = 0
    val source = FakeSource()
    SeekPreviewRegistry.installLazy {
      creations++
      source
    }

    val controller = controller(this)
    assertEquals("la costruzione del controller non crea il decoder", 0, creations)

    // Prima pressione di scrubbing: PlayerScreen chiama setSource solo qui.
    controller.setSource(key = "episodio-1", seekable = true)
    assertEquals(1, creations)
    assertSame("una sola istanza riutilizzabile", source, SeekPreviewRegistry.source())

    // Ricomposizioni / nuove pressioni / riapertura dello scrubbing: nessuna nuova istanza.
    controller.setSource(key = "episodio-1", seekable = true)
    controller.hide()
    controller.setSource(key = "episodio-1", seekable = true)
    assertEquals(1, creations)
    assertSame(source, SeekPreviewRegistry.source())

    controller.release()
  }

  @Test
  fun `una sorgente non ricercabile non crea il decoder`() = runTest {
    var creations = 0
    SeekPreviewRegistry.installLazy {
      creations++
      FakeSource()
    }

    val controller = controller(this)
    controller.setSource(key = "live", seekable = false)

    assertEquals("contenuto non seekable: nessuna anteprima da costruire", 0, creations)
    controller.release()
  }

  @Test
  fun `clear rilascia la sorgente creata e ferma la factory`() = runTest {
    val first = FakeSource()
    SeekPreviewRegistry.installLazy { first }
    val controller = controller(this)
    controller.setSource(key = "episodio-1", seekable = true)
    assertFalse(first.released)

    // Uscita dalla schermata: il DisposableEffect di PlayerScreen azzera il registro.
    SeekPreviewRegistry.clear()
    assertTrue("le risorse del preview vengono rilasciate", first.released)
    assertNull(SeekPreviewRegistry.source())

    controller.release()
  }

  @Test
  fun `sostituire la factory rilascia la sorgente precedente`() {
    val first = FakeSource()
    SeekPreviewRegistry.installLazy { first }
    SeekPreviewRegistry.source()
    assertFalse(first.released)

    val second = FakeSource()
    SeekPreviewRegistry.installLazy { second }

    assertTrue("la sorgente del contenuto precedente viene rilasciata", first.released)
    assertSame(second, SeekPreviewRegistry.source())
    assertFalse(second.released)
  }

  @Test
  fun `una factory che fallisce non viene ritentata a ogni evento D-pad`() {
    var attempts = 0
    SeekPreviewRegistry.installLazy {
      attempts++
      error("decoder non disponibile")
    }

    assertNull(SeekPreviewRegistry.source())
    assertNull(SeekPreviewRegistry.source())

    assertEquals("un solo tentativo, nessun loop di creazioni", 1, attempts)
  }

  private fun TestScope.controller(scope: TestScope): SeekPreviewController =
    SeekPreviewController(
      scope = scope,
      dispatcher = UnconfinedTestDispatcher(testScheduler),
      sourceProvider = { SeekPreviewRegistry.source() }
    )
}
