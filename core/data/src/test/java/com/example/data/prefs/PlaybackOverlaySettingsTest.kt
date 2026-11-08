package com.example.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Impostazioni "Overlay e avvisi di riproduzione" (sezione Riproduzione):
 *  - valori predefiniti richiesti: overlay di caricamento ON, stato di caricamento ON,
 *    overlay in pausa OFF, avvisi sui contenuti OFF;
 *  - round-trip su DataStore reale: la scrittura del setter emette il nuovo valore nel
 *    flusso condiviso [SettingsRepository.settings] (stesso percorso usato dal player
 *    e dalle Impostazioni);
 *  - indipendenza: attivare/disattivare una sola opzione non modifica le altre.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class PlaybackOverlaySettingsTest {

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
  }

  @Test
  fun valoriPredefinitiRichiesti() {
    val defaults = PlaybackSettings()
    assertTrue(defaults.loadingOverlayEnabled)
    assertTrue(defaults.loadingStatusEnabled)
    assertFalse(defaults.pauseOverlayEnabled)
    assertFalse(defaults.parentalGuideEnabled)
  }

  @Test
  fun roundTripSuDataStoreConIndipendenzaFraLeOpzioni() = runBlocking<Unit> {
    // `init` è idempotente: garantita qui per indipendenza dall'ordine dei test.
    SettingsRepository.init(context)

    // Overlay in pausa: default OFF → ON, senza toccare le altre tre.
    SettingsRepository.setPauseOverlayEnabled(true)
    withTimeout(5_000) {
      SettingsRepository.settings.first { it.pauseOverlayEnabled }
    }
    val afterPauseOn = SettingsRepository.settings.value
    assertTrue(afterPauseOn.loadingOverlayEnabled)
    assertTrue(afterPauseOn.loadingStatusEnabled)
    assertFalse(afterPauseOn.parentalGuideEnabled)

    // Avvisi sui contenuti: default OFF → ON, le altre restano com'erano.
    SettingsRepository.setParentalGuideEnabled(true)
    withTimeout(5_000) {
      SettingsRepository.settings.first { it.parentalGuideEnabled }
    }
    val afterGuideOn = SettingsRepository.settings.value
    assertTrue(afterGuideOn.pauseOverlayEnabled)
    assertTrue(afterGuideOn.loadingOverlayEnabled)

    // Disattivazione dell'overlay di caricamento (default ON → OFF) e dello stato.
    SettingsRepository.setLoadingOverlayEnabled(false)
    SettingsRepository.setLoadingStatusEnabled(false)
    withTimeout(5_000) {
      SettingsRepository.settings.first { !it.loadingOverlayEnabled && !it.loadingStatusEnabled }
    }
    val afterOff = SettingsRepository.settings.value
    assertFalse(afterOff.loadingOverlayEnabled)
    assertFalse(afterOff.loadingStatusEnabled)
    assertTrue(afterOff.pauseOverlayEnabled) // indipendente: resta com'era
    assertTrue(afterOff.parentalGuideEnabled) // indipendente: resta com'era

    // Ripristino dei default per i test successivi.
    SettingsRepository.setLoadingOverlayEnabled(true)
    SettingsRepository.setLoadingStatusEnabled(true)
    SettingsRepository.setPauseOverlayEnabled(false)
    SettingsRepository.setParentalGuideEnabled(false)
    withTimeout(5_000) {
      SettingsRepository.settings.first {
        it.loadingOverlayEnabled && it.loadingStatusEnabled &&
          !it.pauseOverlayEnabled && !it.parentalGuideEnabled
      }
    }
  }
}
