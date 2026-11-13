package com.example.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Preferenza "Provider HTTP principale".
 *
 * Verifica: default automatico, persistenza su DataStore dopo un riavvio,
 * normalizzazione di un id sconosciuto e indipendenza dalle altre preferenze.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class HttpPrimaryProviderPreferenceTest {

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    AppSettingsRepository.resetForTesting()
    AppSettingsRepository.init(context)
  }

  @After
  fun tearDown() {
    AppSettingsRepository.setHttpPrimaryProviderId(HttpProviderChoices.AUTO_ID)
    AppSettingsRepository.setHttpPrimaryProviderStrict(true)
    AppSettingsRepository.setHttpExperimentalProvidersEnabled(false)
  }

  private suspend fun awaitStrict(expected: Boolean) {
    AppSettingsRepository.awaitLoaded()
    withTimeout(5_000) {
      AppSettingsRepository.httpPrimaryProviderStrict.first { it == expected }
    }
    repeat(50) {
      AppSettingsRepository.resetForTesting()
      AppSettingsRepository.init(context)
      val reloaded = withTimeoutOrNull(1_000) {
        AppSettingsRepository.httpPrimaryProviderStrict.first { it == expected }
      }
      if (reloaded != null) return
      Thread.sleep(100)
    }
    throw AssertionError("Preferenza strict non persistita: atteso $expected")
  }

  private suspend fun awaitExperimental(expected: Boolean) {
    AppSettingsRepository.awaitLoaded()
    withTimeout(5_000) {
      AppSettingsRepository.httpExperimentalProvidersEnabled.first { it == expected }
    }
    repeat(50) {
      AppSettingsRepository.resetForTesting()
      AppSettingsRepository.init(context)
      val reloaded = withTimeoutOrNull(1_000) {
        AppSettingsRepository.httpExperimentalProvidersEnabled.first { it == expected }
      }
      if (reloaded != null) return
      Thread.sleep(100)
    }
    throw AssertionError("Preferenza provider sperimentali non persistita: atteso $expected")
  }

  private suspend fun awaitPersisted(expected: String) {
    AppSettingsRepository.awaitLoaded()
    withTimeout(5_000) {
      AppSettingsRepository.httpPrimaryProviderId.first { it == expected }
    }
    repeat(50) {
      AppSettingsRepository.resetForTesting()
      AppSettingsRepository.init(context)
      val reloaded = withTimeoutOrNull(1_000) {
        AppSettingsRepository.httpPrimaryProviderId.first { it == expected }
      }
      if (reloaded != null) return
      Thread.sleep(100)
    }
    throw AssertionError("Preferenza non persistita su disco: atteso $expected")
  }

  @Test
  fun ilValorePredefinitoEAutomatico() {
    assertEquals(
      HttpProviderChoices.AUTO_ID,
      AppSettingsRepository.httpPrimaryProviderId.value
    )
  }

  @Test
  fun laPreferenzaSopravviveAlRiavvio() = runBlocking {
    AppSettingsRepository.setHttpPrimaryProviderId("Altadefinizione")
    awaitPersisted("Altadefinizione")

    AppSettingsRepository.resetForTesting()
    AppSettingsRepository.init(context)
    withTimeout(5_000) {
      AppSettingsRepository.httpPrimaryProviderId.first { it == "Altadefinizione" }
    }

    assertEquals(
      "La scelta del provider resta memorizzata dopo il riavvio",
      "Altadefinizione",
      AppSettingsRepository.httpPrimaryProviderId.value
    )
  }

  @Test
  fun unIdSconosciutoTornaAutomatico() {
    AppSettingsRepository.setHttpPrimaryProviderId("ProviderRimosso")
    assertEquals(
      HttpProviderChoices.AUTO_ID,
      AppSettingsRepository.httpPrimaryProviderId.value
    )

    AppSettingsRepository.setHttpPrimaryProviderId("Cb01Provider")
    AppSettingsRepository.setHttpPrimaryProviderId(null)
    assertEquals(
      HttpProviderChoices.AUTO_ID,
      AppSettingsRepository.httpPrimaryProviderId.value
    )
  }

  @Test
  fun leAltrePreferenzeNonToccanoIlProviderPrincipale() {
    AppSettingsRepository.setHttpPrimaryProviderId("VixSrcProvider")
    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.DEBRID_TORBOX)
    AppSettingsRepository.setAutoplayEnabled(true)

    assertEquals(
      "Le altre preferenze non modificano il provider HTTP principale",
      "VixSrcProvider",
      AppSettingsRepository.httpPrimaryProviderId.value
    )
  }

  @Test
  fun laModalitaSoloProviderEAttivaPerDefaultEDisattivabile() = runBlocking {
    assertTrue(
      "La modalità solo provider scelto è attiva per default",
      AppSettingsRepository.httpPrimaryProviderStrict.value
    )

    AppSettingsRepository.setHttpPrimaryProviderStrict(false)
    awaitStrict(false)
    assertFalse(AppSettingsRepository.httpPrimaryProviderStrict.value)
  }

  @Test
  fun iProviderSperimentaliSonoSpentiPerDefaultEAttivabili() = runBlocking {
    assertFalse(
      "I provider sperimentali sono spenti per default",
      AppSettingsRepository.httpExperimentalProvidersEnabled.value
    )

    AppSettingsRepository.setHttpExperimentalProvidersEnabled(true)
    awaitExperimental(true)
    assertTrue(AppSettingsRepository.httpExperimentalProvidersEnabled.value)
  }
}
