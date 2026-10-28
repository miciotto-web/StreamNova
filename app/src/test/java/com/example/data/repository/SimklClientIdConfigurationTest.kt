package com.example.data.repository

import com.example.BuildConfig
import com.example.data.prefs.AppSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il Client ID SIMKL è una **configurazione dello sviluppatore**, non un dato dell'utente:
 * arriva con la build (variabile d'ambiente, `local.properties`, `.env`) e l'app non espone
 * alcun campo per inserirlo. Questi test fissano tre cose:
 *  - da dove il runtime prende il valore: i due campi inclusi nell'APK, mai l'ambiente del
 *    dispositivo (una TV non ha le variabili d'ambiente della macchina di build);
 *  - che i segnaposto di `.env.example` non passino per un Client ID, e che quindi una build
 *    senza configurazione si dichiari priva di configurazione;
 *  - che un valore valido arrivi intero alla richiesta, spazi e virgolette esclusi.
 */
class SimklClientIdConfigurationTest {

  private fun channel(name: String, raw: String?) =
    SimklRepository.SimklClientIdChannel(name, raw)

  @Test
  fun ilClientIdDiBuildVinceSulResiduoSuDisco() {
    val resolution = SimklRepository.resolveSimklClientId(
      listOf(
        channel("build:ENV_SIMKL_CLIENT_ID", "client-build"),
        channel("build:SIMKL_CLIENT_ID", "client-dotenv"),
        channel("prefs:simkl_client_id", "client-residuo")
      )
    )

    assertEquals("client-build", resolution.value)
    assertEquals("build:ENV_SIMKL_CLIENT_ID", resolution.source)
    assertTrue(resolution.isConfigured)
  }

  @Test
  fun unCanaleVuotoNonNascondeQuelliSuccessivi() {
    val resolution = SimklRepository.resolveSimklClientId(
      listOf(
        channel("build:ENV_SIMKL_CLIENT_ID", ""),
        channel("build:SIMKL_CLIENT_ID", "   "),
        channel("prefs:simkl_client_id", "client-residuo")
      )
    )

    assertEquals("client-residuo", resolution.value)
    assertEquals("prefs:simkl_client_id", resolution.source)
  }

  @Test
  fun unClientIdValidoArrivaInteroAllaRichiesta() {
    // Spazi e virgolette sono il modo più comune di sbagliare un valore copiato a mano:
    // non devono finire nel client_id inviato a SIMKL.
    assertEquals("3552f0b1a2", SimklRepository.sanitizeSimklClientId("  3552f0b1a2 "))
    assertEquals("3552f0b1a2", SimklRepository.sanitizeSimklClientId("\"3552f0b1a2\""))
    assertEquals(
      "3552f0b1a2",
      SimklRepository.resolveSimklClientId(listOf(channel("build:ENV_SIMKL_CLIENT_ID", " \"3552f0b1a2\" "))).value
    )
  }

  @Test
  fun iSegnapostoNonSonoUnClientId() {
    assertNull(SimklRepository.sanitizeSimklClientId("DEFAULT_SIMKL_CLIENT_ID"))
    assertNull(SimklRepository.sanitizeSimklClientId("YOUR_SIMKL_CLIENT_ID"))
    assertNull(SimklRepository.sanitizeSimklClientId("SIMKL_CLIENT_ID"))
    assertNull(SimklRepository.sanitizeSimklClientId(""))
    assertNull(SimklRepository.sanitizeSimklClientId("   "))
    assertNull(SimklRepository.sanitizeSimklClientId(null))
  }

  @Test
  fun senzaConfigurazioneApplicativaNonEsisteAlcunClientId() {
    val resolution = SimklRepository.resolveSimklClientId(
      listOf(
        channel("build:ENV_SIMKL_CLIENT_ID", null),
        channel("build:SIMKL_CLIENT_ID", ""),
        channel("prefs:simkl_client_id", null)
      )
    )

    assertFalse("nessun canale valorizzato ⇒ Client ID assente", resolution.isConfigured)
    assertEquals("", resolution.value)
    assertNull("senza Client ID non si indica nemmeno un canale", resolution.source)
  }

  @Test
  fun ilSegnapostoDiEnvExampleEquivaleaNessunaConfigurazione() {
    // Condizione reale di una build senza `.env` né `local.properties`: il plugin `secrets`
    // copia `.env.example`, quindi l'unico valore presente è il suo segnaposto. Deve
    // valere come "Client ID assente", non come un ID con cui interrogare SIMKL.
    val resolution = SimklRepository.resolveSimklClientId(
      listOf(
        channel("build:ENV_SIMKL_CLIENT_ID", ""),
        channel("build:SIMKL_CLIENT_ID", "DEFAULT_SIMKL_CLIENT_ID"),
        channel("prefs:simkl_client_id", null)
      )
    )

    assertFalse(resolution.isConfigured)
    assertNull(resolution.source)
  }

  @Test
  fun ilRuntimeLeggeIFlussiDiBuildPrimaDellAmbienteDelDispositivo() {
    val channels = SimklRepository.simklClientIdChannels()

    // Contratto dei canali: due valori inclusi nell'APK, più il residuo su disco scritto
    // dalle build di Fase 3 (nessuna UI lo scrive più). Nessun `System.getenv`: su una TV
    // l'ambiente della macchina di build non esiste, e un login non deve dipenderne.
    assertEquals(
      listOf("build:ENV_SIMKL_CLIENT_ID", "build:SIMKL_CLIENT_ID", "prefs:simkl_client_id"),
      channels.map { it.name }
    )
    // I primi due sono i campi generati in fase di build: è da qui che il runtime prende
    // il valore, qualunque esso sia nella build in esame.
    assertEquals(BuildConfig.ENV_SIMKL_CLIENT_ID, channels[0].raw)
    assertEquals(BuildConfig.SIMKL_CLIENT_ID, channels[1].raw)
    assertEquals(AppSettingsRepository.simklClientId.value, channels[2].raw)
  }
}
