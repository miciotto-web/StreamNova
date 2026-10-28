package com.example.data.opensubtitles

import com.example.data.prefs.AppSettingsRepository
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La chiave API OpenSubtitles viaggia SOLO nell'header `Api-Key`: il client non deve
 * loggare gli header (BASIC logga solo riga di richiesta + riga di risposta, mai
 * header), così la chiave e gli URL temporanei non finiscono nei logcat.
 *
 * @see com.example.data.opensubtitles.OpenSubtitlesSubtitleDownloader defaultDownloadClient:
 *      il client di download è già privo di logging interceptor.
 */
class OpenSubtitlesClientLoggingTest {

  @After
  fun tearDown() {
    AppSettingsRepository.setOpenSubtitlesApiKey(null)
  }

  @Test
  fun ilLoggingDelClientDiRicercaNonEsponeHeader() {
    val client = OpenSubtitlesApiClient.createOkHttpClient(apiKeyProvider = { "super-secret-key" })

    val loggingInterceptors = client.interceptors.filterIsInstance<HttpLoggingInterceptor>()
    assertTrue(
      "Il client deve avere un logging interceptor configurato in modo sicuro",
      loggingInterceptors.isNotEmpty()
    )
    loggingInterceptors.forEach { interceptor ->
      assertEquals(
        "Il livello di log NON deve includere header (la chiave sta in Api-Key)",
        HttpLoggingInterceptor.Level.BASIC,
        interceptor.level
      )
    }
  }

  @Test
  fun laChiaveSalvataVieneLettaDalClientCorretto() {
    AppSettingsRepository.setOpenSubtitlesApiKey("saved-key-123")
    assertEquals(
      "getEffectiveApiKey deve restituire la chiave configurata nelle Impostazioni",
      "saved-key-123",
      OpenSubtitlesApiClient.getEffectiveApiKey()
    )
  }

  @Test
  fun chiaveAssenteEVuotoSonoEquivalentiANonConfigurato() {
    AppSettingsRepository.setOpenSubtitlesApiKey(null)
    val key = OpenSubtitlesApiClient.getEffectiveApiKey()
    // Con la chiave non salvata resta solo il fallback della variabile d'ambiente,
    // non settata negli ambienti di test: può essere null o la chiave d'ambiente,
    // MAI una chiave inesistente.
    assertTrue(key == null || key == System.getenv("OPENSUBTITLES_API_KEY")?.trim())
  }
}
