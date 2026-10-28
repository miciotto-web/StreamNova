package com.example.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Archiviazione della chiave API OpenSubtitles (Impostazioni → Sottotitoli).
 *
 * Garanzie verificate a livello repository (Robolectric + DataStore reale, pattern
 * "reset in memoria e ricarica da disco" come per le altre preferenze):
 * - la chiave salvata viene CIFRATA prima della persistenza: ricaricandola con il
 *   cipher giusto torna identica (roundtrip), mentre un lettore "in chiaro" NON
 *   riesce a recuperare il segreto dal disco;
 * - la chiave sopravvive a un riavvio dell'app;
 * - un valore storico in chiaro resta leggibile (migrazione soft);
 * - un blob non decifrabile (chiave Keystore invalidata) non configura nulla;
 * - un valore vuoto/blank rimuove la configurazione.
 *
 * NOTA TEST: le asserzioni sul file raw del DataStore non sono affidabili in suite
 * (il singleton della DataStore punta alla dir del primo init nel JVM): la verifica
 * "mai in chiaro su disco" è [unBlobCifratoNonLeggibileDaUnLettoreInChiaro].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenSubtitlesApiKeyStorageTest {

  private lateinit var context: Context

  private val secret = "abcd1234EFGH5678"

  /** Cifratura finta deterministica: marcatura + payload delle cifre invertite. */
  private class FakeCipher : ApiKeyCipher {
    override fun encrypt(plain: String): String =
      ApiKeyCipherFormat.MARKER + java.util.Base64.getEncoder().encodeToString(
        plain.toByteArray(Charsets.UTF_8).reversedArray()
      )

    override fun decrypt(stored: String): String? {
      if (!ApiKeyCipherFormat.isEncrypted(stored)) return null
      return try {
        String(
          java.util.Base64.getDecoder().decode(ApiKeyCipherFormat.payload(stored)),
          Charsets.UTF_8
        ).reversed()
      } catch (_: IllegalArgumentException) {
        null
      }
    }
  }

  /** Comportamento storico: il valore salvato è già testo in chiaro, senza marcatura. */
  private class LegacyPlainCipher : ApiKeyCipher {
    override fun encrypt(plain: String): String = plain
    override fun decrypt(stored: String): String? = null
  }

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    AppSettingsRepository.resetForTesting()
    AppSettingsRepository.init(context, FakeCipher())
  }

  /**
   * Attende che la scrittura asincrona su disco sia effettivamente completata:
   * simula il riavvio (reset + init) finché la ricarica non produce [expected].
   */
  private suspend fun awaitPersistedAcrossRestart(cipher: ApiKeyCipher, expected: String?) {
    AppSettingsRepository.awaitLoaded()
    withTimeoutOrNull(5_000) {
      AppSettingsRepository.openSubtitlesApiKey.first { it == expected }
    }
    repeat(50) {
      AppSettingsRepository.resetForTesting()
      AppSettingsRepository.init(context, cipher)
      var found = false
      withTimeoutOrNull(1_000) {
        AppSettingsRepository.openSubtitlesApiKey.first { it == expected }
        found = true
      }
      if (found) return
      Thread.sleep(100)
    }
    throw AssertionError("Chiave non persistita su disco: atteso $expected")
  }

  @Test
  fun senzaChiaveSalvataIlFlussoValeNull() {
    assertNull(AppSettingsRepository.openSubtitlesApiKey.value)
  }

  @Test
  fun ilRoundTripCifratoRestituisceLaChiaveOriginale() = runBlocking {
    AppSettingsRepository.setOpenSubtitlesApiKey(secret)
    awaitPersistedAcrossRestart(FakeCipher(), secret)
    assertEquals(secret, AppSettingsRepository.openSubtitlesApiKey.value)
  }

  @Test
  fun unBlobCifratoNonLeggibileDaUnLettoreInChiaro() = runBlocking {
    // Salvataggio con cifratura + conferma che il valore è già su disco.
    AppSettingsRepository.setOpenSubtitlesApiKey(secret)
    awaitPersistedAcrossRestart(FakeCipher(), secret)

    // Un lettore che ignora la cifratura (come una lettura raw del file) NON deve
    // riuscire a recuperare il segreto: su disco c'è solo il blob cifrato.
    AppSettingsRepository.resetForTesting()
    AppSettingsRepository.init(context, LegacyPlainCipher())
    withTimeoutOrNull(2_000) {
      AppSettingsRepository.openSubtitlesApiKey.first { it != null }
    }
    assertNotEquals(
      "Su disco NON deve esserci la chiave in chiaro",
      secret,
      AppSettingsRepository.openSubtitlesApiKey.value
    )
  }

  @Test
  fun unValoreStoricoInChiaroRestaLeggibile() = runBlocking {
    // Simula una build precedente: la chiave era salvata senza cifratura.
    AppSettingsRepository.resetForTesting()
    AppSettingsRepository.init(context, LegacyPlainCipher())
    AppSettingsRepository.setOpenSubtitlesApiKey(secret)
    awaitPersistedAcrossRestart(LegacyPlainCipher(), secret)
    assertEquals(
      "Un valore legacy non cifrato resta utilizzabile senza perdere la configurazione",
      secret,
      AppSettingsRepository.openSubtitlesApiKey.value
    )
  }

  @Test
  fun unBlobNonDecifrabileSignificaNonConfigurato() = runBlocking {
    AppSettingsRepository.setOpenSubtitlesApiKey(secret)
    awaitPersistedAcrossRestart(FakeCipher(), secret)

    // Riapre con un cipher che NON riesce a decifrare (chiave Keystore invalidata).
    AppSettingsRepository.resetForTesting()
    AppSettingsRepository.init(context, LegacyPlainCipher())
    withTimeoutOrNull(2_000) {
      AppSettingsRepository.openSubtitlesApiKey.first { it != null }
    }
    assertNull(
      "Blob irrileggibile → nessuna chiave configurata, senza crash",
      AppSettingsRepository.openSubtitlesApiKey.value
    )
  }

  @Test
  fun unValoreVuotoRimuoveLaConfigurazione() = runBlocking {
    AppSettingsRepository.setOpenSubtitlesApiKey(secret)
    awaitPersistedAcrossRestart(FakeCipher(), secret)

    AppSettingsRepository.setOpenSubtitlesApiKey("   ")
    awaitPersistedAcrossRestart(FakeCipher(), null)
    assertNull(AppSettingsRepository.openSubtitlesApiKey.value)
  }

}
