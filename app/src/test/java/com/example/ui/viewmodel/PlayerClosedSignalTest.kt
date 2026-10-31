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
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * SEGNALE DI RIENTRO Player -> Dettaglio.
 *
 * Il player è un overlay: chiuderlo dal Dettaglio non smonta la scheda e nessun effetto di
 * focus della scheda ri-parte. Il ViewModel emette un segnale DEDICATO
 * ([StreamNovaViewModel.playerClosedToken]) a ogni chiusura reale del player, che il
 * Dettaglio usa per riprendere il focus su «Riproduci».
 *
 * Contratto verificato:
 * - il token avanza SOLO se il player era aperto (una doppia chiusura non duplica il segnale);
 * - non avanza e non consuma i token del selettore sorgenti e del trailer (canali indipendenti);
 * - il rientro nel Dettaglio non tocca né consuma il target pendente Dettaglio -> Home.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlayerClosedSignalTest {

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
    viewModel.openSubtitlesProvider = object : OpenSubtitlesSubtitleProvider() {
      override suspend fun fetchSubtitles(
        mediaItem: MediaItem,
        episode: Episode?,
        preferredLanguage: String?
      ) = emptyList<Subtitle>()
    }
    createdViewModel = viewModel
    return viewModel
  }

  private fun movie(id: String = "media-dettaglio", title: String = "Dal Dettaglio") = MediaItem(
    id = id,
    title = title,
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    tmdbId = 900001
  )

  @Test
  fun laChiusuraDelPlayerAvanzaIlSegnaleDiRientro() {
    val viewModel = createViewModel()
    val media = movie()
    viewModel.openDetail(media)
    viewModel.openPlayer(media)
    val tokenBefore = viewModel.playerClosedToken.value

    viewModel.closePlayer()

    assertEquals(
      "la chiusura del player deve emettere UN segnale di rientro al Dettaglio",
      tokenBefore + 1,
      viewModel.playerClosedToken.value
    )
    assertEquals(
      "il rientro deve lasciare il Dettaglio come schermata attiva",
      ScreenState.DETAIL,
      viewModel.screenState.value
    )
  }

  @Test
  fun unaChiusuraDoppiaNonDuplicaIlSegnale() {
    val viewModel = createViewModel()
    val media = movie()
    viewModel.openDetail(media)
    viewModel.openPlayer(media)
    val tokenBefore = viewModel.playerClosedToken.value

    // BACK ripetuto prima della ricomposizione: la seconda chiusura non è una nuova uscita.
    viewModel.closePlayer()
    viewModel.closePlayer()

    assertEquals(
      "un secondo closePlayer() senza player aperto non deve duplicare il ripristino",
      tokenBefore + 1,
      viewModel.playerClosedToken.value
    )
  }

  @Test
  fun laChiusuraSenzaPlayerApertoNonAvanzaIlSegnale() {
    val viewModel = createViewModel()
    viewModel.openDetail(movie())
    val tokenBefore = viewModel.playerClosedToken.value

    viewModel.closePlayer()

    assertEquals(
      "senza player aperto non c'è alcun rientro dal player da segnalare",
      tokenBefore,
      viewModel.playerClosedToken.value
    )
    assertEquals(ScreenState.DETAIL, viewModel.screenState.value)
  }

  @Test
  fun ilRientroDalPlayerNonAvanzaITokenDiSorgentiETrailer() {
    val viewModel = createViewModel()
    val media = movie()
    viewModel.openDetail(media)
    viewModel.openPlayer(media)
    val sourceTokenBefore = viewModel.sourceDialogDismissedToken.value
    val trailerTokenBefore = viewModel.trailerDismissedToken.value

    viewModel.closePlayer()

    assertEquals(
      "il rientro dal player non deve consumare il segnale del selettore sorgenti",
      sourceTokenBefore,
      viewModel.sourceDialogDismissedToken.value
    )
    assertEquals(
      "il rientro dal player non deve consumare il segnale del trailer",
      trailerTokenBefore,
      viewModel.trailerDismissedToken.value
    )
  }

  @Test
  fun ilRientroDalPlayerNonToccaIlTargetPendenteDettaglioVersoLaHome() {
    val viewModel = createViewModel()
    val media = movie()
    // Provenienza salvata dal click della card Home che ha aperto la scheda.
    viewModel.saveLastFocusedMedia("section_trending_film", "tf-3", "trend_m_3")
    viewModel.openDetail(media)
    viewModel.openPlayer(media)

    viewModel.closePlayer()

    assertEquals(
      "il target pendente Dettaglio -> Home non deve essere modificato dal rientro dal player",
      "section_trending_film" to "tf-3",
      viewModel.lastFocusedTarget.value
    )
    assertEquals("tf-3", viewModel.lastFocusedMediaId.value)
    assertEquals("trend_m_3", viewModel.lastFocusedCardKey.value)
    assertFalse(
      "il rientro dal player non è una richiesta di ripristino per la Home",
      viewModel.focusRestoreRequested.value
    )
  }

  /**
   * Percorso NON proveniente dal Dettaglio: un player aperto dalla schermata Provider
   * rientra nella schermata Provider (nessuna navigazione aggiuntiva, nessun pop) e il
   * segnale di rientro viene comunque emesso senza effetti su chi non lo ascolta.
   */
  @Test
  fun ilPlayerApertoDaProviderRientraNellaSchermataProvider() {
    val viewModel = createViewModel()
    viewModel.openProvider(com.example.ui.components.ProviderConstants.ALL.first())
    assertEquals(ScreenState.PROVIDER, viewModel.screenState.value)

    viewModel.openPlayer(movie())
    viewModel.closePlayer()

    assertEquals(
      "il BACK dal player aperto da Provider deve ripristinare la schermata Provider",
      ScreenState.PROVIDER,
      viewModel.screenState.value
    )
    assertEquals(1, viewModel.playerClosedToken.value)
  }
}
