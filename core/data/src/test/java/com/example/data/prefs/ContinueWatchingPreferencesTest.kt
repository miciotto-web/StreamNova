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
 * Preferenze della sezione «Continua a guardare».
 *
 * Garanzie: i default coincidono con il comportamento storico (riga visibile, card 16:9,
 * backdrop, nessuna sfocatura, ripresa dall'episodio più recente, episodi futuri inclusi,
 * ordinamento predefinito), ogni opzione è indipendente e ogni scelta sopravvive al
 * riavvio leggendo lo stesso DataStore.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ContinueWatchingPreferencesTest {

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    AppSettingsRepository.resetForTesting()
    AppSettingsRepository.init(context)
    // Il DataStore è condiviso fra le classi di test dello stesso processo: si
    // riportano SUBITO i valori di default in memoria (e su disco) prima di ogni
    // asserzione, così il test non dipende dall'ordine di esecuzione.
    resetToDefaults()
  }

  @After
  fun tearDown() {
    resetToDefaults()
  }

  private fun resetToDefaults() {
    AppSettingsRepository.setContinueWatchingShowRow(true)
    AppSettingsRepository.setContinueWatchingLayout(ContinueWatchingLayout.CARD)
    AppSettingsRepository.setContinueWatchingEpisodeThumbnails(false)
    AppSettingsRepository.setContinueWatchingBlurUnwatched(false)
    AppSettingsRepository.setContinueWatchingNextEpisodeFromFurthest(false)
    AppSettingsRepository.setContinueWatchingShowUnairedEpisodes(true)
    AppSettingsRepository.setContinueWatchingSort(ContinueWatchingSort.DEFAULT)
  }

  /** Attende che la scrittura asincrona sia su disco rileggendo dopo un finto riavvio. */
  private suspend fun awaitPersisted(expected: ContinueWatchingPreferences) {
    AppSettingsRepository.awaitLoaded()
    withTimeout(5_000) {
      AppSettingsRepository.continueWatchingPreferences.first { it == expected }
    }
    repeat(50) {
      AppSettingsRepository.resetForTesting()
      AppSettingsRepository.init(context)
      val reloaded = withTimeoutOrNull(1_000) {
        AppSettingsRepository.continueWatchingPreferences.first { it == expected }
      }
      if (reloaded != null) return
      Thread.sleep(100)
    }
    throw AssertionError("Preferenze non persistite su disco: atteso $expected")
  }

  @Test
  fun iDefaultCorrispondonoAlComportamentoStorico() {
    val defaults = AppSettingsRepository.continueWatchingPreferences.value
    assertTrue("La riga è visibile come prima", defaults.showRow)
    assertEquals(ContinueWatchingLayout.CARD, defaults.layout)
    assertFalse("L'immagine di default resta il backdrop", defaults.useEpisodeThumbnails)
    assertFalse("Nessuna sfocatura di default", defaults.blurUnwatched)
    assertFalse("Ripresa dall'episodio guardato più di recente", defaults.nextEpisodeFromFurthest)
    assertTrue("Nessun filtro sulle date per default", defaults.showUnairedEpisodes)
    assertEquals(ContinueWatchingSort.DEFAULT, defaults.sort)
  }

  @Test
  fun ogniOpzioneAggiornaLoSnapshotInModoIndipendente() {
    AppSettingsRepository.setContinueWatchingShowRow(false)
    assertFalse(AppSettingsRepository.continueWatchingPreferences.value.showRow)
    assertTrue("Le altre opzioni restano invariate", AppSettingsRepository.continueWatchingPreferences.value.useEpisodeThumbnails == false)

    AppSettingsRepository.setContinueWatchingLayout(ContinueWatchingLayout.WIDE)
    assertEquals(ContinueWatchingLayout.WIDE, AppSettingsRepository.continueWatchingPreferences.value.layout)

    AppSettingsRepository.setContinueWatchingEpisodeThumbnails(true)
    assertTrue(AppSettingsRepository.continueWatchingPreferences.value.useEpisodeThumbnails)

    AppSettingsRepository.setContinueWatchingBlurUnwatched(true)
    assertTrue(AppSettingsRepository.continueWatchingPreferences.value.blurUnwatched)

    AppSettingsRepository.setContinueWatchingNextEpisodeFromFurthest(true)
    assertTrue(AppSettingsRepository.continueWatchingPreferences.value.nextEpisodeFromFurthest)

    AppSettingsRepository.setContinueWatchingShowUnairedEpisodes(false)
    assertFalse(AppSettingsRepository.continueWatchingPreferences.value.showUnairedEpisodes)

    AppSettingsRepository.setContinueWatchingSort(ContinueWatchingSort.TITLE)
    assertEquals(ContinueWatchingSort.TITLE, AppSettingsRepository.continueWatchingPreferences.value.sort)
  }

  @Test
  fun tutteLeScelteSopravvivonoAlRiavvio() = runBlocking {
    val expected = ContinueWatchingPreferences(
      showRow = false,
      layout = ContinueWatchingLayout.POSTER,
      useEpisodeThumbnails = true,
      blurUnwatched = true,
      nextEpisodeFromFurthest = true,
      showUnairedEpisodes = false,
      sort = ContinueWatchingSort.RATING
    )
    AppSettingsRepository.setContinueWatchingShowRow(expected.showRow)
    AppSettingsRepository.setContinueWatchingLayout(expected.layout)
    AppSettingsRepository.setContinueWatchingEpisodeThumbnails(expected.useEpisodeThumbnails)
    AppSettingsRepository.setContinueWatchingBlurUnwatched(expected.blurUnwatched)
    AppSettingsRepository.setContinueWatchingNextEpisodeFromFurthest(expected.nextEpisodeFromFurthest)
    AppSettingsRepository.setContinueWatchingShowUnairedEpisodes(expected.showUnairedEpisodes)
    AppSettingsRepository.setContinueWatchingSort(expected.sort)

    awaitPersisted(expected)

    // Riavvio simulato: stato in memoria azzerato, disco invariato.
    AppSettingsRepository.resetForTesting()
    assertEquals(
      "Prima del load valgono i default",
      ContinueWatchingPreferences(),
      AppSettingsRepository.continueWatchingPreferences.value
    )
    AppSettingsRepository.init(context)
    withTimeout(5_000) {
      AppSettingsRepository.continueWatchingPreferences.first { it == expected }
    }
    assertEquals(expected, AppSettingsRepository.continueWatchingPreferences.value)
  }

  @Test
  fun leAltrePreferenzeNonToccanoContinuaAGuardare() {
    val before = AppSettingsRepository.continueWatchingPreferences.value
    AppSettingsRepository.setHideUnreleasedContent(true)
    AppSettingsRepository.setAutoplayEnabled(true)
    AppSettingsRepository.setTmdbGenreFallbackEnabled(false)
    AppSettingsRepository.setStreamingEngineMode(StreamingEngineMode.HTTP_WEB)
    assertEquals(before, AppSettingsRepository.continueWatchingPreferences.value)

    AppSettingsRepository.setHideUnreleasedContent(false)
    AppSettingsRepository.setAutoplayEnabled(false)
    AppSettingsRepository.setTmdbGenreFallbackEnabled(true)
  }

  @Test
  fun iValoriSconosciutiSuDiscoTornanoAlDefault() {
    assertEquals(ContinueWatchingLayout.CARD, ContinueWatchingLayout.fromStorage("SCONOSCIUTO"))
    assertEquals(ContinueWatchingLayout.CARD, ContinueWatchingLayout.fromStorage(null))
    assertEquals(ContinueWatchingSort.DEFAULT, ContinueWatchingSort.fromStorage("SCONOSCIUTO"))
    assertEquals(ContinueWatchingSort.DEFAULT, ContinueWatchingSort.fromStorage(null))
  }
}
