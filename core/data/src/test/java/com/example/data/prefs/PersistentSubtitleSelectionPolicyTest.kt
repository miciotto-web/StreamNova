package com.example.data.prefs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifica quando una scelta manuale deve cancellare l'associazione OpenSubtitles. */
class PersistentSubtitleSelectionPolicyTest {

  private fun saved(contentId: String = "tt0944947::season:1:episode:1") =
    PersistentSubtitleSelection(
      contentId = contentId,
      fileId = 42L,
      lang = "it",
      label = "Italiano",
      localFilePath = "/tmp/sub.srt",
      timestamp = 1L
    )

  @Test
  fun senzaSelezioneSalvataNonCEeNullaDaCancellare() {
    assertFalse(
      PersistentSubtitleSelectionPolicy.shouldClearOnManualSelection(
        saved = null,
        selectedTrackId = null
      )
    )
    assertFalse(
      PersistentSubtitleSelectionPolicy.shouldClearOnManualSelection(
        saved = null,
        selectedTrackId = "native-1"
      )
    )
  }

  @Test
  fun selezionareLaStessaTracciaPersistenteMantieneLAssociazione() {
    assertFalse(
      PersistentSubtitleSelectionPolicy.shouldClearOnManualSelection(
        saved = saved(),
        selectedTrackId = "tt0944947::season:1:episode:1"
      )
    )
  }

  @Test
  fun tracciaNativaRimuoveLAssociazione() {
    assertTrue(
      PersistentSubtitleSelectionPolicy.shouldClearOnManualSelection(
        saved = saved(),
        selectedTrackId = "embedded-subtitle-1"
      )
    )
  }

  @Test
  fun nessunoTracciaSenzaIdRimuoveLAssociazione() {
    assertTrue(
      PersistentSubtitleSelectionPolicy.shouldClearOnManualSelection(
        saved = saved(),
        selectedTrackId = null
      )
    )
  }

  @Test
  fun tracciaDiUnAltroEpisodioNonCorrisponde() {
    assertTrue(
      PersistentSubtitleSelectionPolicy.shouldClearOnManualSelection(
        saved = saved("tt0944947::season:1:episode:1"),
        selectedTrackId = "tt0944947::season:1:episode:2"
      )
    )
  }
}
