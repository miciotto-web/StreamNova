package com.example.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.viewModelScope
import com.example.data.model.Episode
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.opensubtitles.OpenSubtitlesSubtitleProvider
import com.example.data.repository.MediaIdentity
import com.example.domain.model.Subtitle
import com.example.ui.navigation.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Destinazione del rientro dal player quando la riproduzione è avviata da una card di riga.
 *
 * Percorso coperto: card di «Continua a guardare» -> riproduzione -> BACK dal player. Questa
 * riproduzione NON passa dal Dettaglio: al BACK la destinazione deve essere la SCHEDA DETTAGLIO
 * del contenuto appena riprodotto (non la card di «Continua a guardare», che era il
 * comportamento precedente).
 *
 * Qui si verifica il contratto del ViewModel:
 * - la provenienza registrata al click diventa la richiesta di rientro nel Dettaglio
 *   ([StreamNovaViewModel.playerDetailReturn]) e la schermata Dettaglio è già preparata
 *   (schermata + media selezionato): MainActivity deve solo navigare;
 * - l'identità è quella del contenuto RIPRODOTTO,anche quando il catalogo contiene una scheda
 *   gemella dello stesso titolo con un identificatore diverso (TMDB vs IMDb/Stremio);
 * - l'episodio appena visto è il punto di ripresa della scheda, anche se nel player si è
 *   passati all'episodio successivo;
 * - la Home NON riceve alcun target di ripristino: il focus non torna sulla card di origine;
 * - nessun altro ingresso al player cambia comportamento (provenienza assente, player aperto
 *   dalla scheda Dettaglio);
 * - la richiesta è un EVENTO: si consuma e non sopravvive al rientro che l'ha generata.
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

  private fun film(
    id: String = CW_ID,
    title: String = "Continua a guardare",
    tmdbId: Int? = null,
    year: Int = 2021
  ): MediaItem = MediaItem(
    id = id,
    title = title,
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = year,
    tmdbId = tmdbId,
    currentProgressMs = 10 * 60_000L,
    totalDurationMs = 100 * 60_000L
  )

  private fun series(
    id: String = "serie-1",
    title: String = "Serie in corso",
    tmdbId: Int = 500,
    lastSeason: Int = 3,
    lastEpisode: Int = 5
  ): MediaItem = MediaItem(
    id = id,
    title = title,
    synopsis = "",
    videoUrl = "",
    type = MediaType.SERIE_TV,
    tmdbId = tmdbId,
    currentProgressMs = 10 * 60_000L,
    totalDurationMs = 50 * 60_000L,
    lastWatchedSeason = lastSeason,
    lastWatchedEpisode = lastEpisode
  )

  private fun episode(season: Int, episode: Int, progressMs: Long = 0L): Episode = Episode(
    id = "serie-1_s${season}e$episode",
    seasonNumber = season,
    episodeNumber = episode,
    title = "Episodio $episode",
    synopsis = "",
    durationMinutes = 50,
    videoUrl = "",
    currentProgressMs = progressMs
  )

  /**
   * BACK dal player, sequenza REALE completa: `resetStreamState()` + `closePlayer()` — che per
   * il rientro nella scheda Dettaglio pubblica la richiesta e resta in attesa della navigazione
   * — e la conclusione `completePlayerClose()`, la stessa che MainActivity invoca subito dopo
   * aver applicato la destinazione. La richiesta resta pubblicata finché MainActivity non la
   * consuma (vedi [StreamNovaViewModel.consumePlayerDetailReturn]).
   */
  private fun backFromPlayer(viewModel: StreamNovaViewModel) {
    viewModel.resetStreamState()
    viewModel.closePlayer()
    viewModel.completePlayerClose()
  }

  // ------------------------------------------------- 1. riproduzione da «Continua a guardare»

  /**
   * Il percorso segnalato: card di «Continua a guardare» -> riproduzione -> BACK dal player.
   * La destinazione è la scheda Dettaglio del contenuto riprodotto, già preparata; la Home non
   * riceve nessun target (il focus non torna sulla card di origine).
   */
  @Test
  fun ilRientroDalPlayerDaContinuaAGuardareApreIlDettaglioDelContenutoRiprodotto() {
    val viewModel = createViewModel()
    val tokenBefore = viewModel.focusRestoreToken.value
    val played = film(id = CW_ID, title = "Dune", tmdbId = 7000)

    viewModel.savePlaybackFocusOrigin(CW_ROW, played.id)
    viewModel.openPlayer(played)
    assertEquals(ScreenState.PLAYER, viewModel.screenState.value)

    backFromPlayer(viewModel)

    val request = viewModel.playerDetailReturn.value
    assertEquals(
      "la destinazione deve essere il contenuto riprodotto",
      played.id,
      request?.media?.id
    )
    assertEquals(7000, request?.media?.tmdbId)
    assertEquals(
      "la scheda Dettaglio deve essere già la schermata attiva (MainActivity naviga e basta)",
      ScreenState.DETAIL,
      viewModel.screenState.value
    )
    assertEquals(played.id, viewModel.selectedMedia.value?.id)
    assertNull(
      "la Home non deve ricevere un target di ripristino: il focus non torna sulla card di origine",
      viewModel.lastFocusedTarget.value
    )
    assertNull(viewModel.lastFocusedMediaId.value)
    assertEquals(
      "il token di ripristino della Home non deve avanzare",
      tokenBefore,
      viewModel.focusRestoreToken.value
    )
  }

  /**
   * La chiusura del player è DIFFERITA finché la destinazione non è pronta: alla pressione di
   * BACK (`closePlayer`) il player è ancora composto e copre la destinazione sottostante (la
   * Home), mentre la richiesta di rientro è già pubblicata. Solo `completePlayerClose` — la
   * chiamata che MainActivity esegue DOPO aver applicato la navigazione — smonta il player e
   * prepara la scheda. È questo che impedisce alla Home di comparire tra il player e i dettagli.
   */
  @Test
  fun laChiusuraDelPlayerRestaSospesaFincheLaDestinazioneNonEPronta() {
    val viewModel = createViewModel()
    val played = film(id = CW_ID, title = "Dune", tmdbId = 7000)

    viewModel.savePlaybackFocusOrigin(CW_ROW, played.id)
    viewModel.openPlayer(played)

    viewModel.resetStreamState()
    viewModel.closePlayer()

    assertEquals(
      "il player deve restare composto (copre la Home) finché la destinazione non è composta",
      ScreenState.PLAYER,
      viewModel.screenState.value
    )
    assertTrue(
      "la sessione di riproduzione deve essere ancora viva mentre l'overlay è a schermo",
      viewModel.playbackState.value.isPlaying
    )
    assertEquals(
      "la sessione deve essere ancora quella appena chiusa",
      played.id,
      viewModel.playbackState.value.media?.id
    )
    assertEquals(
      "la richiesta di rientro deve essere già pubblicata per MainActivity",
      played.id,
      viewModel.playerDetailReturn.value?.media?.id
    )
    assertNull(
      "nessuna schermata preparata sotto il player: la scheda si prepara al completamento",
      viewModel.selectedMedia.value
    )

    // MainActivity ha applicato la destinazione: la chiusura si conclude.
    viewModel.consumePlayerDetailReturn()
    viewModel.completePlayerClose()

    assertEquals(ScreenState.DETAIL, viewModel.screenState.value)
    assertEquals(played.id, viewModel.selectedMedia.value?.id)
    assertFalse(
      "il player è smontato: la sessione di riproduzione è chiusa",
      viewModel.playbackState.value.isPlaying
    )
    assertNull(viewModel.playbackState.value.streamUrl)
    assertNull("la destinazione non chiede una seconda navigazione", viewModel.playerDetailReturn.value)
  }

  /**
   * Una seconda chiusura durante l'attesa non smonta il player due volte e non innesca una
   * seconda navigazione: la chiusura in corso è una sola.
   */
  @Test
  fun unaSecondaChiusuraDuranteLAttesaNonDuplicaLaRichiesta() {
    val viewModel = createViewModel()
    val played = film(id = CW_ID, tmdbId = 7000)

    viewModel.savePlaybackFocusOrigin(CW_ROW, played.id)
    viewModel.openPlayer(played)

    viewModel.closePlayer()
    val firstRequest = viewModel.playerDetailReturn.value
    viewModel.closePlayer()

    assertEquals(
      "il player deve restare composto durante l'attesa",
      ScreenState.PLAYER,
      viewModel.screenState.value
    )
    assertTrue(
      "la richiesta di rientro deve restare una sola",
      firstRequest === viewModel.playerDetailReturn.value
    )

    viewModel.completePlayerClose()
    // Un ulteriore completamento fuori tempo non fa nulla.
    viewModel.completePlayerClose()
    assertEquals(ScreenState.DETAIL, viewModel.screenState.value)
    assertEquals(played.id, viewModel.selectedMedia.value?.id)
  }

  /**
   * Nel player si può passare all'episodio successivo: la puntata da riprendere nella scheda è
   * quella DELLA SESSIONE (S3E6), non quella scritta sulla card di partenza (S3E5).
   */
  @Test
  fun lEpisodioAppenaVistoDiventaIlPuntoDiRipresaDellaScheda() {
    val viewModel = createViewModel()
    val played = series(lastSeason = 3, lastEpisode = 5)

    viewModel.savePlaybackFocusOrigin(CW_ROW, played.id)
    viewModel.openPlayer(played, episode(season = 3, episode = 6))
    backFromPlayer(viewModel)

    val returned = viewModel.playerDetailReturn.value?.media
    assertEquals(played.id, returned?.id)
    assertEquals(
      "la scheda deve riprendere dall'episodio appena visto, non da quello della card",
      3,
      returned?.lastWatchedSeason
    )
    assertEquals(6, returned?.lastWatchedEpisode)
    assertEquals(
      "la stagione selezionata nella scheda deve essere quella appena vista",
      3,
      viewModel.selectedSeasonNumber.value
    )
  }

  /**
   * Lo stesso titolo può esistere nel catalogo due volte (una scheda TMDB e una IMDb/Stremio,
   * stesso film e stesso anno): il rientro deve puntare al contenuto RIPRODOTTO, mai a una
   * scheda gemella con un identificatore diverso — altrimenti la rotta di destinazione perde
   * l'ID TMDB e la scheda riapre un contenuto diverso (o nessun dettaglio).
   */
  @Test
  fun lIdentitaDelContenutoRiprodottoNonSiConfondeConUnaSchedaGemella() {
    val viewModel = createViewModel()
    val played = film(id = "tmdb_movie_7000", title = "Dune", tmdbId = 7000)
    val twin = film(id = "tt1160419", title = "Dune", tmdbId = null)
    assertTrue(
      "le due schede devono essere lo stesso titolo con identificatori diversi",
      MediaIdentity.isSameTitle(played, twin)
    )

    viewModel.savePlaybackFocusOrigin(CW_ROW, played.id)
    viewModel.openPlayer(played)
    backFromPlayer(viewModel)

    val returned = viewModel.playerDetailReturn.value?.media
    assertEquals("tmdb_movie_7000", returned?.id)
    assertEquals(7000, returned?.tmdbId)
    assertEquals(
      "la rotta di destinazione deve portare l'ID TMDB del contenuto riprodotto",
      "detail/MOVIE/7000",
      Screen.Detail.createRoute(returned!!.type, returned.tmdbId ?: 0)
    )
  }

  /** La richiesta di rientro è un EVENTO: consumata, non si ripete al rientro successivo. */
  @Test
  fun laRichiestaDiRientroVieneConsumataUnaSolaVolta() {
    val viewModel = createViewModel()
    val played = film(id = CW_ID, tmdbId = 7000)

    viewModel.savePlaybackFocusOrigin(CW_ROW, played.id)
    viewModel.openPlayer(played)
    backFromPlayer(viewModel)
    assertEquals(played.id, viewModel.playerDetailReturn.value?.media?.id)

    // MainActivity ha navigato: la richiesta è consumata.
    viewModel.consumePlayerDetailReturn()
    assertNull(viewModel.playerDetailReturn.value)

    // Una seconda chiusura (nessuna nuova riproduzione da una card di riga) non la ripubblica.
    backFromPlayer(viewModel)
    assertNull(
      "la richiesta non deve sopravvivere alla sessione di riproduzione che l'ha generata",
      viewModel.playerDetailReturn.value
    )
    assertNull(viewModel.lastFocusedTarget.value)
  }

  // ------------------------------------------------- 2. altri ingressi al player (invariati)

  /** Riproduzione senza provenienza da una card: il rientro non chiede nessun Dettaglio. */
  @Test
  fun senzaProvenienzaIlRientroDalPlayerNonApreIlDettaglio() {
    val viewModel = createViewModel()
    val tokenBefore = viewModel.focusRestoreToken.value
    val played = film(id = "media-dialog-sorgenti", tmdbId = 1234)

    viewModel.openPlayer(played)
    backFromPlayer(viewModel)

    assertNull(viewModel.playerDetailReturn.value)
    assertEquals(
      "senza provenienza la destinazione resta la Home",
      ScreenState.BROWSING,
      viewModel.screenState.value
    )
    assertNull(viewModel.lastFocusedTarget.value)
    assertEquals(tokenBefore, viewModel.focusRestoreToken.value)
  }

  /**
   * Player aperto dalla scheda Dettaglio (catalogo -> Detail -> Player): il rientro è la
   * scheda stessa e non va aperta/navigata nessun'altra destinazione, nemmeno se una
   * provenienza è rimasta registrata.
   */
  @Test
  fun ilPlayerApertoDalDettaglioRientraNellaSchedaSenzaAltreRichieste() {
    val viewModel = createViewModel()
    val media = film(id = "media-dettaglio", tmdbId = 900)

    viewModel.openDetail(media)
    viewModel.savePlaybackFocusOrigin(CW_ROW, CW_ID)
    assertEquals(ScreenState.DETAIL, viewModel.screenState.value)

    viewModel.openPlayer(media)
    assertTrue(viewModel.playerOpenedFromDetail())
    backFromPlayer(viewModel)

    assertEquals(
      "il player aperto dal Dettaglio rientra nella scheda: nessuna richiesta per la Home",
      ScreenState.DETAIL,
      viewModel.screenState.value
    )
    assertNull(
      "nessuna navigazione: si resta nella scheda aperta",
      viewModel.playerDetailReturn.value
    )
    assertEquals(
      "la scheda aperta non deve essere sostituita",
      media.id,
      viewModel.selectedMedia.value?.id
    )
    assertNull(viewModel.lastFocusedTarget.value)
  }
}
