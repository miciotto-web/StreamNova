package com.aistudio.streamnova.mobile.ui

import com.example.data.prefs.AppSettingsRepository
import com.example.data.prefs.DecoderFallbackMode
import com.example.data.prefs.PlaybackSettings
import com.example.data.prefs.TargetBufferOption
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Persistenza delle preferenze usate dalla Fase 8.4.
 *
 * Il salvataggio su disco resta quello dei repository condivisi
 * (`SettingsRepository`/`AppSettingsRepository`, DataStore): la Mobile non
 * introduce un secondo store. Qui si verifica il valore in memoria che la UI e il
 * player leggono, così un toggle non può restare solo grafico.
 */
class MobilePlayerSettingsPersistenceTest {

  @Before
  fun setUp() {
    AppSettingsRepository.resetForTesting()
  }

  @After
  fun tearDown() {
    AppSettingsRepository.resetForTesting()
  }

  @Test
  fun ilFallbackTmdbRifletteSubitoIlToggleDelRepository() {
    AppSettingsRepository.setTmdbGenreFallbackEnabled(false)
    assertFalse(AppSettingsRepository.tmdbGenreFallbackEnabled.value)

    AppSettingsRepository.setTmdbGenreFallbackEnabled(true)
    assertTrue(AppSettingsRepository.tmdbGenreFallbackEnabled.value)
  }

  @Test
  fun ilModelloRiproduzioneHaAutoplaySuccessivoAttivoEDecoderFallbackComeDefault() {
    val settings = PlaybackSettings()
    assertTrue(settings.autoPlayNextEpisodeEnabled)
    assertEquals(DecoderFallbackMode.ON, settings.decoderFallbackMode)
    assertEquals(TargetBufferOption.DEFAULT, settings.targetBuffer)
  }

  @Test
  fun ilToggleAutoplaySuccessivoUsaLoStessoModelloDellaTv() {
    val disabled = PlaybackSettings().copy(autoPlayNextEpisodeEnabled = false)
    assertFalse(disabled.autoPlayNextEpisodeEnabled)
    assertTrue(disabled.copy(autoPlayNextEpisodeEnabled = true).autoPlayNextEpisodeEnabled)
  }

  @Test
  fun ilBufferExtraDellaSessioneNonModificaLePreferenzeGlobali() {
    val global = PlaybackSettings()
    val sessionExtra = global.copy(
      targetBuffer = TargetBufferOption.EXTENDED,
      backBuffer = com.example.data.prefs.BackBufferOption.EXTENDED
    )
    assertEquals(TargetBufferOption.DEFAULT, global.targetBuffer)
    assertEquals(TargetBufferOption.EXTENDED, sessionExtra.targetBuffer)
  }
}
