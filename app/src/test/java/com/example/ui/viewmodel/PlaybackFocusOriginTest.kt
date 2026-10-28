package com.example.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.viewModelScope
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.opensubtitles.OpenSubtitlesSubtitleProvider
import com.example.domain.model.Subtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Provenienza del focus della riproduzione avviata da una card di riga.
 *
 * Percorso coperto: card di «Continua a guardare» -> riproduzione -> BACK dal player. Questa
 * riproduzione NON passa dal Dettaglio, quindi la Home resta composta sotto l'overlay del player
 * e al ritorno nessun segnale riportava il focus sulla card di origine: il focus restava alla
 * prima superficie focalizzabile (la sidebar).
 *
 * Qui si verifica il contratto del ViewModel:
 * - la provenienza registrata al click diventa il TARGET di ripristino row-scoped alla chiusura
 *   del player, con il token avanzato (una card di una riga ripristina SOLO quella riga);
 * - senza provenienza (riproduzione avviata dal Dettaglio) il target NON viene toccato;
 * - il rientro nel Dettaglio (player aperto dalla scheda) non pubblica nessun target per la Home;
 * - la provenienza è consumata una sola volta: non sopravvive alla sessione che l'ha generata.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaybackFocusOriginTest {

  private companion object {
    const val CW_ROW = "section_continue_watching"
    const val CW_ID = "cw-3"
  }

  private val testDispatcher = StandardTestDispatcher()
  private var createdViewModel: StreamNovaViewModel? = null

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @After
  fun tearDown() {
    createdViewModel?.let { vm ->
      vm.viewModelScope.coroutineContext[Job]?.let { job ->
        job.cancel()
        testDispatcher.scheduler.advanceUntilIdle()
      }
    }
    createdViewModel = null
    Dispatchers.resetMain()
  }

  private fun createViewModel(): StreamNovaViewModel {
    val viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher(testDispatcher.scheduler))
    // Nessuna rete nei test: la ricerca sottotitoli del player risponde subito vuota.
    viewModel.openSubtitlesProvider = object : OpenSubtitlesSubtitleProvider() {
      override suspend fun fetchSubtitles(
        mediaItem: MediaItem,
        episode: Episode?,
        preferredLanguage: String?,
        year: Int?,
        resolveLinksOnDemand: Boolean
      ) = emptyList<Subtitle>()
    }
    createdViewModel = viewModel
    return viewModel
  }

  private fun film(id: String = CW_ID, title: String = "Continua a guardare"): MediaItem = MediaItem(
    id = id,
    title = title,
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    tmdbId = null,
    currentProgressMs = 10 * 60_000L,
    totalDurationMs = 100 * 60_000L
  )

  @Test
  fun ilRientroDalPlayerPubblicaIlTargetDellaCardDiOrigine() {
    val viewModel = createViewModel()
    val tokenBefore = viewModel.focusRestoreToken.value

    // Click su una card di «Continua a guardare»: si registra la provenienza (riga + id).
    viewModel.savePlaybackFocusOrigin(CW_ROW, CW_ID)
    viewModel.closePlayer()

    assertEquals(
      "il target di ripristino deve essere row-scoped sulla riga di origine",
      CW_ROW to CW_ID,
      viewModel.lastFocusedTarget.value
    )
    assertEquals(CW_ID, viewModel.lastFocusedMediaId.value)
    assertEquals(
      "il token deve avanzare: rende ripetibile il ripristino anche sullo stesso titolo",
      tokenBefore + 1,
      viewModel.focusRestoreToken.value
    )
  }

  @Test
  fun senzaProvenienzaIlRientroNonToccaIlTarget() {
    val viewModel = createViewModel()
    val tokenBefore = viewModel.focusRestoreToken.value
    // Target di un'altra riga già salvato (percorso Dettaglio).
    viewModel.saveLastFocusedMedia("section_trending_film", "tf-4")

    viewModel.closePlayer()

    assertEquals(
      "un rientro dal player senza provenienza non deve toccare il target esistente",
      "section_trending_film" to "tf-4",
      viewModel.lastFocusedTarget.value
    )
    assertEquals(tokenBefore, viewModel.focusRestoreToken.value)
  }

  @Test
  fun laProvenienzaVieneConsumataUnaSolaVolta() {
    val viewModel = createViewModel()

    viewModel.savePlaybackFocusOrigin(CW_ROW, CW_ID)
    viewModel.closePlayer()
    assertEquals(CW_ROW to CW_ID, viewModel.lastFocusedTarget.value)

    // La Home consuma il target appena il focus atterra sulla card.
    viewModel.clearLastFocusedMedia()
    val tokenAfterFirstClose = viewModel.focusRestoreToken.value

    // Una seconda chiusura (nessuna nuova riproduzione da una card) non deve ripubblicarlo.
    viewModel.closePlayer()
    assertNull(
      "la provenienza non deve sopravvivere alla sessione di riproduzione che l'ha generata",
      viewModel.lastFocusedTarget.value
    )
    assertNull(viewModel.lastFocusedMediaId.value)
    assertEquals(tokenAfterFirstClose, viewModel.focusRestoreToken.value)
  }

  @Test
  fun ilRientroNelDettaglioNonPubblicaTargetPerLaHome() {
    val viewModel = createViewModel()
    val media = film(id = "media-dettaglio", title = "Dal Dettaglio")

    // Percorso Dettaglio: la scheda è aperta e la riproduzione parte da lì.
    viewModel.openDetail(media)
    viewModel.savePlaybackFocusOrigin(CW_ROW, CW_ID)
    viewModel.openPlayer(media)
    viewModel.closePlayer()

    assertEquals(
      "il player aperto dal Dettaglio rientra nella scheda: nessun target per la Home",
      ScreenState.DETAIL,
      viewModel.screenState.value
    )
    assertNull(
      "il target della Home non deve essere pubblicato quando si rientra nel Dettaglio",
      viewModel.lastFocusedTarget.value
    )
  }
}
