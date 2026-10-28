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
 * Preferenza "Usa TMDB come fallback per i generi".
 *
 * Garanzie verificate: valore predefinito attivo, persistenza su DataStore dopo un
 * riavvio (stato in memoria azzerato, stesso disco) e indipendenza dalle altre
 * preferenze dell'app.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class TmdbGenreFallbackPreferenceTest {

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
    // Nessun test successivo deve ereditare la scelta fatta qui.
    AppSettingsRepository.setTmdbGenreFallbackEnabled(true)
  }

  /** Attende che la scrittura asincrona su DataStore sia effettivamente completata. */
  private suspend fun awaitPersisted(enabled: Boolean) {
    AppSettingsRepository.awaitLoaded()
    withTimeout(5_000) {
      AppSettingsRepository.tmdbGenreFallbackEnabled.first { it == enabled }
    }
    // La scrittura avviene in una coroutine su IO: attende che il round-trip su disco
    // emetta il valore, altrimenti il "riavvio" potrebbe leggere il valore precedente.
    repeat(50) {
      AppSettingsRepository.resetForTesting()
      AppSettingsRepository.init(context)
      val reloaded = withTimeoutOrNull(1_000) {
        AppSettingsRepository.tmdbGenreFallbackEnabled.first { it == enabled }
      }
      if (reloaded != null) return
      Thread.sleep(100)
    }
    throw AssertionError("Preferenza non persistita su disco: atteso $enabled")
  }

  @Test
  fun ilValorePredefinitoEAttivo() {
    assertTrue(
      "Il fallback TMDB è attivo per default (ricerca più completa)",
      AppSettingsRepository.tmdbGenreFallbackEnabled.value
    )
  }

  @Test
  fun laPreferenzaSopravviveAlRiavvio() = runBlocking {
    AppSettingsRepository.setTmdbGenreFallbackEnabled(false)
    awaitPersisted(false)

    // Simula il riavvio: stato in memoria azzerato, DataStore su disco invariato.
    AppSettingsRepository.resetForTesting()
    assertTrue(
      "Stato in memoria azzerato: prima del load vale il default",
      AppSettingsRepository.tmdbGenreFallbackEnabled.value
    )
    AppSettingsRepository.init(context)
    withTimeout(5_000) {
      AppSettingsRepository.tmdbGenreFallbackEnabled.first { !it }
    }

    assertFalse(
      "La scelta dell'utente resta memorizzata dopo il riavvio",
      AppSettingsRepository.tmdbGenreFallbackEnabled.value
    )
  }

  @Test
  fun laPreferenzaTornaAttivaSeLUtenteLaRiattiva() = runBlocking {
    AppSettingsRepository.setTmdbGenreFallbackEnabled(false)
    awaitPersisted(false)
    AppSettingsRepository.setTmdbGenreFallbackEnabled(true)
    awaitPersisted(true)

    AppSettingsRepository.resetForTesting()
    AppSettingsRepository.init(context)
    withTimeout(5_000) {
      AppSettingsRepository.tmdbGenreFallbackEnabled.first { it }
    }

    assertTrue(AppSettingsRepository.tmdbGenreFallbackEnabled.value)
  }

  @Test
  fun leAltrePreferenzeNonToccanoIlFallback() {
    AppSettingsRepository.setTmdbGenreFallbackEnabled(false)
    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
    AppSettingsRepository.setAutoplayEnabled(true)

    assertFalse(
      "Le altre preferenze non modificano il fallback TMDB",
      AppSettingsRepository.tmdbGenreFallbackEnabled.value
    )
  }
}
