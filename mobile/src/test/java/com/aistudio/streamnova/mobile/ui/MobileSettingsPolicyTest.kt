package com.aistudio.streamnova.mobile.ui

import com.example.data.prefs.PreferredResolution
import com.example.data.prefs.StreamingEngineMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileSettingsPolicyTest {

  @Test
  fun leLingueDellAppSonoLeCinqueDellaTv() {
    assertEquals(listOf("it", "en", "es", "fr", "de"), MobileSettingsPolicy.APP_LANGUAGE_CODES)
  }

  @Test
  fun ilMappingAudioUsaCodiciIso639_1e639_2() {
    assertEquals(listOf("it", "ita"), MobileSettingsPolicy.mapPreferredAudioLanguages("it"))
    assertEquals(listOf("en", "eng"), MobileSettingsPolicy.mapPreferredAudioLanguages("EN"))
    assertEquals(listOf("de", "deu", "ger"), MobileSettingsPolicy.mapPreferredAudioLanguages("de"))
    assertEquals(listOf("pt-br", "pt"), MobileSettingsPolicy.mapPreferredAudioLanguages("pt-BR"))
  }

  @Test
  fun originaleQualsiasiNonImponeLingue() {
    assertTrue(MobileSettingsPolicy.mapPreferredAudioLanguages("").isEmpty())
    assertTrue(MobileSettingsPolicy.mapPreferredAudioLanguages("und").isEmpty())
    assertNull(MobileSettingsPolicy.subtitleLanguage(""))
    assertNull(MobileSettingsPolicy.subtitleLanguage("und"))
  }

  @Test
  fun autoE4KNonImpongonoVincoliVideo() {
    assertNull(MobileSettingsPolicy.resolutionConstraint(PreferredResolution.AUTO))
    assertNull(MobileSettingsPolicy.resolutionConstraint(PreferredResolution.UHD_4K))
    assertEquals(
      1920 to 1080,
      MobileSettingsPolicy.resolutionConstraint(PreferredResolution.FULL_HD_1080P)
    )
    assertEquals(
      1280 to 720,
      MobileSettingsPolicy.resolutionConstraint(PreferredResolution.HD_720P)
    )
  }

  @Test
  fun ilSelettoreSorgentiSiApreSoloInDebridSenzaAutoplay() {
    assertTrue(
      MobileSettingsPolicy.shouldShowSourcePicker(
        autoplayEnabled = false,
        engineMode = StreamingEngineMode.DEBRID_TORBOX,
        sourceCount = 3
      )
    )
    assertFalse(
      MobileSettingsPolicy.shouldShowSourcePicker(
        autoplayEnabled = true,
        engineMode = StreamingEngineMode.DEBRID_TORBOX,
        sourceCount = 3
      )
    )
    assertFalse(
      MobileSettingsPolicy.shouldShowSourcePicker(
        autoplayEnabled = false,
        engineMode = StreamingEngineMode.HTTP_WEB,
        sourceCount = 3
      )
    )
    assertFalse(
      MobileSettingsPolicy.shouldShowSourcePicker(
        autoplayEnabled = false,
        engineMode = StreamingEngineMode.DEBRID_TORBOX,
        sourceCount = 0
      )
    )
  }

  @Test
  fun autoResumeOffRiparteDaZero() {
    assertEquals(
      0L,
      MobileSettingsPolicy.startPositionMs(
        savedPositionMs = 120_000L,
        durationMs = 600_000L,
        autoResume = false
      )
    )
  }

  @Test
  fun autoResumeOnRiprendeDallaPosizioneSalvata() {
    assertEquals(
      120_000L,
      MobileSettingsPolicy.startPositionMs(
        savedPositionMs = 120_000L,
        durationMs = 600_000L,
        autoResume = true
      )
    )
  }

  @Test
  fun unTitoloGiaConclusoRiparteDaZero() {
    assertEquals(
      0L,
      MobileSettingsPolicy.startPositionMs(
        savedPositionMs = 590_000L,
        durationMs = 600_000L,
        autoResume = true
      )
    )
  }

  @Test
  fun laChiaveApiVieneMascherata() {
    assertEquals("••••••••", MobileSettingsPolicy.maskApiKey(null))
    assertEquals("••••••••", MobileSettingsPolicy.maskApiKey("short"))
    assertEquals("abcd••••••••wxyz", MobileSettingsPolicy.maskApiKey("abcdefgh12345678wxyz"))
  }

  @Test
  fun ilRiconoscimentoAudioItalianoCopreIcodiciIso() {
    assertTrue(MobileSettingsPolicy.isItalianAudioLanguage("it"))
    assertTrue(MobileSettingsPolicy.isItalianAudioLanguage("ita"))
    assertTrue(MobileSettingsPolicy.isItalianAudioLanguage("it-IT"))
    assertFalse(MobileSettingsPolicy.isItalianAudioLanguage("en"))
    assertFalse(MobileSettingsPolicy.isItalianAudioLanguage(null))
  }
}
