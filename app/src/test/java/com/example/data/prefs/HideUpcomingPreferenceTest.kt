package com.example.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Preferenza "Nascondi contenuti non rilasciati" (sezione Home).
 *
 * Garanzie: default OFF (comportamento attuale invariato), persistenza su DataStore
 * dopo un riavvio (stato in memoria azzerato, stesso disco) e indipendenza dalle
 * altre preferenze.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class HideUpcomingPreferenceTest {

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    // Ordine obbligatorio: prima si azzera lo stato in memoria, poi si (ri)collega il
    // DataStore — `init` è idempotente e senza il reset non rileggerebbe mai il disco.
    AppSettingsRepository.resetForTesting()
    AppSettingsRepository.init(context)
  }

  @After
  fun tearDown() {
    AppSettingsRepository.setHideUnreleasedContent(false)
  }

  /** Attende che la scrittura asincrona su DataStore sia effettivamente completata. */
  private suspend fun awaitPersisted(enabled: Boolean) {
    AppSettingsRepository.awaitLoaded()
    withTimeout(5_000) {
      AppSettingsRepository.hideUnreleasedContent.first { it == enabled }
    }
    // La scrittura avviene in una coroutine su IO: attende che il round-trip su disco
    // emetta il valore, altrimenti il "riavvio" potrebbe leggere il valore precedente.
    repeat(50) {
      AppSettingsRepository.resetForTesting()
      AppSettingsRepository.init(context)
      val reloaded = withTimeoutOrNull(1_000) {
        AppSettingsRepository.hideUnreleasedContent.first { it == enabled }
      }
      if (reloaded != null) return
      Thread.sleep(100)
    }
    throw AssertionError("Preferenza non persistita su disco: atteso $enabled")
  }

  @Test
  fun ilValorePredefinitoENonAttivo() {
    assertFalse(
      "Il filtro è OFF per default: la Home resta quella attuale",
      AppSettingsRepository.hideUnreleasedContent.value
    )
  }

  @Test
  fun laPreferenzaSopravviveAlRiavvio() = runBlocking {
    AppSettingsRepository.setHideUnreleasedContent(true)
    awaitPersisted(true)

    // Simula il riavvio: stato in memoria azzerato, DataStore su disco invariato.
    AppSettingsRepository.resetForTesting()
    assertFalse(
      "Stato in memoria azzerato: prima del load vale il default OFF",
      AppSettingsRepository.hideUnreleasedContent.value
    )
    AppSettingsRepository.init(context)
    withTimeout(5_000) {
      AppSettingsRepository.hideUnreleasedContent.first { it }
    }

    assertTrue(
      "La scelta dell'utente resta memorizzata dopo il riavvio",
      AppSettingsRepository.hideUnreleasedContent.value
    )
  }

  @Test
  fun laPreferenzaTornaSpentaSeLUtenteLaDisattiva() = runBlocking {
    AppSettingsRepository.setHideUnreleasedContent(true)
    awaitPersisted(true)
    AppSettingsRepository.setHideUnreleasedContent(false)
    awaitPersisted(false)

    AppSettingsRepository.resetForTesting()
    AppSettingsRepository.init(context)
    withTimeout(5_000) {
      AppSettingsRepository.hideUnreleasedContent.first { !it }
    }

    assertFalse(AppSettingsRepository.hideUnreleasedContent.value)
  }

  @Test
  fun leAltrePreferenzeNonToccanoIlFiltroHome() {
    AppSettingsRepository.setHideUnreleasedContent(true)
    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
    AppSettingsRepository.setAutoplayEnabled(true)
    AppSettingsRepository.setTmdbGenreFallbackEnabled(false)

    assertTrue(
      "Le altre preferenze non modificano il filtro della Home",
      AppSettingsRepository.hideUnreleasedContent.value
    )

    // Ripristina i valori di default per non lasciare stato ai test successivi.
    AppSettingsRepository.setAutoplayEnabled(false)
    AppSettingsRepository.setTmdbGenreFallbackEnabled(true)
  }
}
