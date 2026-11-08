package com.example.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Label
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressione per la priorità della lingua audio italiana.
 *
 * I flussi Debrid/Stremio spesso espongono la traccia italiana senza codice ISO
 * (`language = "und"`) ma con l'etichetta "Italiano"/"Italian". In quel caso
 * `DefaultTrackSelector` non può riconoscerla e, se l'inglese è la prima traccia,
 * il video parte in inglese. [PlayerAudioTrackSelector] colma questo vuoto e
 * viene usato da PlayerScreen per applicare un override esplicito.
 */
class PlayerAudioTrackSelectorTest {

  private val italianPreferences = listOf("it", "ita")

  @Test
  fun isoCodesAndRegionVariantsMatchItalian() {
    assertTrue(PlayerAudioTrackSelector.matchesLanguageCode("it", italianPreferences))
    assertTrue(PlayerAudioTrackSelector.matchesLanguageCode("ita", italianPreferences))
    assertTrue(PlayerAudioTrackSelector.matchesLanguageCode("it-IT", italianPreferences))
    assertTrue(PlayerAudioTrackSelector.matchesLanguageCode("IT", italianPreferences))
    assertFalse(PlayerAudioTrackSelector.matchesLanguageCode("en", italianPreferences))
    assertFalse(PlayerAudioTrackSelector.matchesLanguageCode("und", italianPreferences))
    assertFalse(PlayerAudioTrackSelector.matchesLanguageCode(null, italianPreferences))
  }

  @Test
  fun labelsMatchLanguageNamesAndIdsWithoutFalsePositives() {
    assertTrue(
      PlayerAudioTrackSelector.matchesLanguageLabel(
        audioFormat(language = "und", label = "Italiano 5.1"),
        italianPreferences
      )
    )
    assertTrue(
      PlayerAudioTrackSelector.matchesLanguageLabel(
        audioFormat(language = "und", label = "Italian"),
        italianPreferences
      )
    )
    assertTrue(
      PlayerAudioTrackSelector.matchesLanguageLabel(
        audioFormat(language = "und", label = "ITA (DTS)"),
        italianPreferences
      )
    )
    assertTrue(
      PlayerAudioTrackSelector.matchesLanguageLabel(
        audioFormat(language = null, id = "audio-it"),
        italianPreferences
      )
    )
    assertTrue(
      PlayerAudioTrackSelector.matchesLanguageLabel(
        Format.Builder()
          .setId("audio")
          .setSampleMimeType(MimeTypes.AUDIO_AAC)
          .setLanguage("und")
          .setLabels(listOf(Label("it", "Italiano")))
          .build(),
        italianPreferences
      )
    )
    assertFalse(
      PlayerAudioTrackSelector.matchesLanguageLabel(
        audioFormat(language = "und", label = "English"),
        italianPreferences
      )
    )
    // "Digital" contiene la sequenza "ita": il match deve rispettare i confini di parola.
    assertFalse(
      PlayerAudioTrackSelector.matchesLanguageLabel(
        audioFormat(language = "und", label = "Digital"),
        italianPreferences
      )
    )
  }

  @Test
  fun undeterminedLanguagesSelectItalianTrackByLabel() {
    val english = audioFormat(language = "und", label = "English")
    val italian = audioFormat(language = "und", label = "Italiano")
    val tracks = tracksOf(audioGroup(english, selected = true), audioGroup(italian))

    val selected = PlayerAudioTrackSelector.findSelectedAudioTrack(tracks)
    assertEquals("English", selected?.format?.label)
    assertFalse(PlayerAudioTrackSelector.isPreferredAudioTrack(selected!!, italianPreferences))

    val preferred = PlayerAudioTrackSelector.findPreferredAudioTrack(tracks, italianPreferences)
    assertEquals("Italiano", preferred?.format?.label)
    assertTrue(PlayerAudioTrackSelector.isPreferredAudioTrack(preferred!!, italianPreferences))
  }

  @Test
  fun languageCodeWinsOverLabelMatch() {
    val labelOnlyItalian = audioFormat(language = "und", label = "Italiano")
    val codedItalian = audioFormat(language = "it", label = "Italian")
    val tracks = tracksOf(audioGroup(labelOnlyItalian), audioGroup(codedItalian))

    val preferred = PlayerAudioTrackSelector.findPreferredAudioTrack(tracks, italianPreferences)

    assertEquals("it", preferred?.format?.language)
    assertEquals("Italian", preferred?.format?.label)
  }

  @Test
  fun mainTrackWinsOverCommentaryWithSameLanguage() {
    val commentary = audioFormat(
      language = "it",
      label = "Italian",
      roleFlags = C.ROLE_FLAG_COMMENTARY
    )
    val main = audioFormat(language = "it", label = "Italian")
    val tracks = tracksOf(audioGroup(commentary), audioGroup(main))

    val preferred = PlayerAudioTrackSelector.findPreferredAudioTrack(tracks, italianPreferences)

    assertEquals(0, preferred?.format?.roleFlags)
  }

  @Test
  fun unsupportedItalianTrackIsIgnored() {
    val english = audioGroup(audioFormat(language = "en", label = "English"))
    val brokenItalian = Tracks.Group(
      TrackGroup(audioFormat(language = "it", label = "Italian")),
      false,
      intArrayOf(C.FORMAT_UNSUPPORTED_SUBTYPE),
      booleanArrayOf(false)
    )

    val preferred = PlayerAudioTrackSelector.findPreferredAudioTrack(
      tracksOf(english, brokenItalian),
      italianPreferences
    )

    assertNull(preferred)
  }

  @Test
  fun noPreferredTrackLeavesMedia3SelectionUntouched() {
    val tracks = tracksOf(
      audioGroup(audioFormat(language = "en", label = "English")),
      audioGroup(audioFormat(language = "fr", label = "Français"))
    )

    assertNull(PlayerAudioTrackSelector.findPreferredAudioTrack(tracks, italianPreferences))
  }

  @Test
  fun emptyPreferenceDisablesMatching() {
    val tracks = tracksOf(audioGroup(audioFormat(language = "it", label = "Italiano")))

    assertNull(PlayerAudioTrackSelector.findPreferredAudioTrack(tracks, emptyList()))
  }

  private fun audioFormat(
    language: String?,
    label: String? = null,
    id: String = label ?: language ?: "audio",
    roleFlags: Int = 0
  ): Format = Format.Builder()
    .setId(id)
    .setSampleMimeType(MimeTypes.AUDIO_AAC)
    .setLanguage(language)
    .setLabel(label)
    .setRoleFlags(roleFlags)
    .build()

  private fun audioGroup(format: Format, selected: Boolean = false): Tracks.Group =
    Tracks.Group(
      TrackGroup(format),
      false,
      intArrayOf(C.FORMAT_HANDLED),
      booleanArrayOf(selected)
    )

  private fun tracksOf(vararg groups: Tracks.Group): Tracks = Tracks(groups.toList())
}
