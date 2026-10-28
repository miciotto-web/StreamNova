package com.example.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.lifecycle.viewModelScope
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.components.FOCUS_ROW_ANY
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedTarget
import com.example.ui.viewmodel.StreamNovaViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ripristino del focus della sezione Preferiti ([FavoritesScreen]) al BACK dal Dettaglio.
 *
 * Difetto coperto (crocevia di tre cause, tutte esercitate qui):
 * 1. il click su una scheda non salvava alcuna identità di provenienza, quindi al ritorno il
 *    target di ripristino non esisteva e il focus restava alla prima superficie focalizzabile
 *    della schermata — la sidebar;
 * 2. il ripristino della sezione ascoltava un segnale (`focusRestoreSignal`) che per quella
 *    scheda nessuno emetteva, e che comunque andava perso perché l'emissione precede
 *    l'aggancio del collettore (la schermata viene ricomposta al ritorno);
 * 3. quando il segnale arrivava davvero, il focus veniva richiesto sulla PRIMA cella della
 *    griglia tramite un [androidx.compose.ui.focus.FocusRequester] agganciato a una Box non
 *    focalizzabile (richiesta sempre respinta), dentro un `delay(100)` arbitrario.
 *
 * Il round-trip è riprodotto come il NavHost: la composizione viene smontata e rimontata
 * (SaveableStateProvider) mentre il catalogo di test cambia, come fa l'osservatore Room di
 * `MediaRepository` quando i dettagli arricchiti dal Dettaglio vengono persistiti.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class FavoritesFocusRestoreTest {

  @get:Rule
  val rule = createComposeRule()

  // ------------------------------------------------------------- harness

  private val viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher())

  /**
   * Il ViewModel dei test è una istanza REALE (è il canale del target di ripristino): le sue
   * coroutine (`viewModelScope`) non devono sopravvivere alla classe di test. Lasciate attive
   * riemergono mentre le classi successive impostano/ripristinano `Dispatchers.Main`
   * ("Dispatchers.Main is used concurrently with setting it" nella suite completa).
   */
  @After
  fun tearDown() {
    viewModel.viewModelScope.cancel()
  }

  private var showDetail by mutableStateOf(false)
  private var liveItems by mutableStateOf<List<MediaItem>>(emptyList())
  private var clickedId: String? = null

  private fun item(index: Int, isFavorite: Boolean = true): MediaItem = MediaItem(
    id = "m%02d".format(index + 1),
    title = "Titolo %02d".format(index + 1),
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = 2000 + index,
    tmdbId = index + 1,
    rating = 10f - index * 0.1f,
    isFavorite = isFavorite
  )

  /**
   * Scheda "gemella" dello stesso titolo con identificatore DIVERSO (IMDb/Stremio invece di
   * TMDB): è il caso reale in cui il catalogo contiene due schede dello stesso film.
   */
  private fun imdbSibling(of: MediaItem, rating: Float = 9.9f): MediaItem = of.copy(
    id = "tt%07d".format(of.tmdbId ?: 1),
    imdbId = "tt%07d".format(of.tmdbId ?: 1),
    tmdbId = null,
    rating = rating
  )

  private fun render(items: List<MediaItem>) {
    liveItems = items
    rule.setContent {
      val stateHolder = rememberSaveableStateHolder()
      // Canali REALI della Home (MainActivity -> HomeScreen): target row-scoped salvato al
      // click, token che avanza ad ogni evento di ripristino, rilascio del target quando il
      // focus è davvero arrivato sulla scheda.
      val lastFocusedMediaId by viewModel.lastFocusedMediaId.collectAsState()
      val lastFocusedTarget by viewModel.lastFocusedTarget.collectAsState()
      val focusRestoreToken by viewModel.focusRestoreToken.collectAsState()
      CompositionLocalProvider(
        LocalLastFocusedMediaId provides lastFocusedMediaId,
        LocalLastFocusedTarget provides lastFocusedTarget,
        LocalFocusRestoreToken provides focusRestoreToken,
        LocalClearLastFocusedMedia provides { viewModel.clearLastFocusedMedia() }
      ) {
        if (showDetail) {
          stateHolder.SaveableStateProvider("detail") {
            Box(modifier = Modifier.fillMaxSize()) { Text("DETTAGLIO") }
          }
        } else {
          stateHolder.SaveableStateProvider("favorites") {
            FavoritesScreen(
              allMedia = liveItems,
              onMediaClick = {
                clickedId = it.id
                showDetail = true
              },
              modifier = Modifier.fillMaxSize(),
              viewModel = viewModel
            )
          }
        }
      }
    }
    rule.waitForIdle()
    // Warm-up: la prima composizione misura i testi con metriche non ancora stabilizzate; un
    // ciclo di smontaggio/rimontaggio allinea le misure (stesso accorgimento della griglia
    // delle sezioni Film/Serie TV).
    roundTrip()
    rule.onNode(hasText("Titolo 01")).assertExists()
    rule.waitForIdle()
  }

  /**
   * Preferiti -> Dettaglio -> BACK, come il NavHost + MainActivity: la composizione della
   * sezione viene smontata e rimontata, il catalogo può cambiare mentre il Dettaglio è aperto
   * ([churn]) e il ripristino viene segnalato sul canale del target salvato.
   *
   * @param targetId target da salvare prima del ritorno, per il caso in cui la scheda di
   *   provenienza non è cliccabile nell'harness (fuori dal viewport iniziale).
   */
  private fun roundTrip(
    targetId: String? = null,
    churn: (List<MediaItem>) -> List<MediaItem> = { it }
  ) {
    showDetail = true
    rule.waitForIdle()
    if (targetId != null) viewModel.saveLastFocusedMedia(FOCUS_ROW_ANY, targetId)
    liveItems = churn(liveItems)
    showDetail = false
    rule.waitForIdle()
    // MainActivity (piano RESTORE_SAVED_TARGET): il target salvato esiste ancora, il token
    // avanza e la sezione lo consuma.
    viewModel.lastFocusedMediaId.value?.let { viewModel.signalFocusRestore(it) }
    rule.waitForIdle()
  }

  /** Riordino realistico del catalogo: il rating del titolo aperto cambia e la lista esce
   *  riordinata per rating (stessa sequenza di `mapEntitiesToUiState`). */
  private fun reorderCatalog(shiftingId: String): (List<MediaItem>) -> List<MediaItem> = { list ->
    list.map { if (it.id == shiftingId) it.copy(rating = it.rating - 0.15f) else it }
      .sortedByDescending { it.rating }
  }

  private fun focusedText(): String? = try {
    rule.onNode(isFocused()).fetchSemanticsNode().config
      .getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
  } catch (_: Throwable) {
    null
  }

  private fun boundsOf(title: String): Rect? = try {
    rule.onNode(hasText(title)).fetchSemanticsNode().boundsInRoot
  } catch (_: Throwable) {
    null
  }

  private fun clickCard(title: String) {
    // La card va prima focalizzata, come fa l'utente con il D-pad: il focus reale sulla scheda
    // rilascia un eventuale target precedente.
    rule.onNode(hasText(title)).requestFocus()
    rule.waitForIdle()
    rule.onNode(hasText(title)).performClick()
    rule.waitForIdle()
  }

  private fun assertFocusedCard(title: String) {
    rule.waitUntil(timeoutMillis = 5_000) { focusedText()?.startsWith(title) == true }
    assertTrue(
      "Il focus non è su $title ma su ${focusedText()}",
      focusedText()?.startsWith(title) == true
    )
  }

  // ------------------------------------------------------------- 1. il click salva la scheda

  @Test
  fun ilClickSullaCardSalvaIlTargetDiProvenienza() {
    render(List(12) { item(it) })

    clickCard("Titolo 04")

    assertEquals("Il click non è arrivato al navigatore", "m04", clickedId)
    assertEquals(
      "L'identità della scheda aperta non è stata salvata: al BACK nessun ripristino è possibile",
      FOCUS_ROW_ANY to "m04",
      viewModel.lastFocusedTarget.value
    )
    assertEquals("m04", viewModel.lastFocusedMediaId.value)
  }

  // ------------------------------------------------------------- 2. ritorno sulla stessa scheda

  @Test
  fun rientroDalDettaglioRipristinaLaStessaCard() {
    render(List(12) { item(it) })

    clickCard("Titolo 04")
    roundTrip()

    assertFocusedCard("Titolo 04")
  }

  @Test
  fun rientriConsecutiviSuCardDiverse() {
    render(List(18) { item(it) })

    listOf("Titolo 02", "Titolo 06", "Titolo 11").forEach { title ->
      clickCard(title)
      roundTrip()
      assertFocusedCard(title)
    }
  }

  /** Scheda profonda, fuori dal viewport iniziale: il ripristino deve raggiungerla. */
  @Test
  fun ripristinoSuCardFuoriDalViewportIniziale() {
    render(List(36) { item(it) })

    roundTrip(targetId = "m30")

    assertFocusedCard("Titolo 30")
    assertNull(
      "Il ripristino non ha spostato la griglia: la card profonda non era raggiungibile",
      boundsOf("Titolo 01")
    )
  }

  // ------------------------------------------------------------- 3. D-pad dopo il ripristino

  @Test
  fun dpadFunzionanteDopoIlRipristino() {
    render(List(18) { item(it) })

    clickCard("Titolo 04")
    roundTrip()
    assertFocusedCard("Titolo 04")

    // Il D-pad deve spostare il focus tra le schede subito, senza rientri manuali.
    rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
    rule.waitForIdle()
    val afterRight = focusedText()
    assertNotNull("D-pad morto a destra dopo il ripristino", afterRight)
    assertTrue("Il focus è uscito dalla griglia: $afterRight", afterRight!!.startsWith("Titolo "))
    assertTrue(
      "Il D-pad non ha spostato il focus dalla scheda ripristinata ($afterRight)",
      !afterRight.startsWith("Titolo 04")
    )

    rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }
    rule.waitForIdle()
    assertFocusedCard("Titolo 04")

    // Anche in verticale: la griglia resta navigabile.
    val beforeDown = focusedText()
    rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
    rule.waitForIdle()
    val afterDown = focusedText()
    assertTrue(
      "Il D-pad non scende dalla riga ripristinata ($beforeDown -> $afterDown)",
      afterDown != null && afterDown != beforeDown
    )
  }

  // ------------------------------------------------------------- 4. elenco riordinato

  @Test
  fun ripristinoDopoIlRiordinoDelCatalogo() {
    render(List(24) { item(it) })

    clickCard("Titolo 08")
    roundTrip(churn = reorderCatalog("m08"))

    assertFocusedCard("Titolo 08")
  }

  // ------------------------------------------------------------- 5. duplicati con id diversi

  @Test
  fun contenutiDuplicatiConIdDiversiRipristinanoLaCardVisualizzata() {
    render(List(12) { item(it) })

    clickCard("Titolo 04")

    // Mentre il Dettaglio è aperto il catalogo si arricchisce della scheda IMDb/Stremio dello
    // stesso titolo, che con il rating più alto passa davanti alla scheda TMDB: la griglia
    // mostra ora la scheda "gemella" (id diverso) dello stesso contenuto.
    roundTrip(churn = { list -> (list + imdbSibling(list.first { it.id == "m04" })) })

    assertFocusedCard("Titolo 04")
  }

  // ------------------------------------------------------------- 6. scheda non più disponibile

  @Test
  fun schedaNonPiuDisponibileNonLasciaIlFocusAllaSidebar() {
    render(List(24) { item(it) })

    clickCard("Titolo 03")
    // Il titolo esce dai Preferiti mentre il Dettaglio è aperto.
    roundTrip(churn = { list -> list.filterNot { it.id == "m03" } })

    rule.waitUntil(timeoutMillis = 5_000) { focusedText() != null }
    val focused = focusedText()
    assertNotNull("La griglia è rimasta senza alcun focus dopo un ripristino fallito", focused)
    assertTrue(
      "Il focus non è rimasto nella griglia dei Preferiti: $focused",
      focused!!.startsWith("Titolo ")
    )

    // La griglia resta navigabile con il D-pad.
    val before = focusedText()
    rule.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
    rule.waitForIdle()
    val after = focusedText()
    assertTrue(
      "D-pad morto dopo un ripristino fallito ($after)",
      after != null && after != before
    )
  }

  // ------------------------------------------------------------- identità di contenuto

  @Test
  fun identitaDiContenutoRiconosceLaSchedaGemella() {
    val tmdb = item(3)
    val imdb = imdbSibling(tmdb)
    val list = listOf(item(0), item(1), tmdb, imdb)

    assertEquals(
      "La scheda gemella dello stesso titolo (id IMDb vs TMDB) non è stata riconosciuta",
      list.indexOfFirst { it.id == imdb.id },
      favoriteRestoreIndex(list, list, imdb.id)
    )
    assertEquals(
      "La scheda ancora in elenco non è stata risolta per posizione diretta",
      2,
      favoriteRestoreIndex(list, list, tmdb.id)
    )
    assertEquals(
      "Un id sconosciuto non deve risolvere su una scheda qualsiasi",
      -1,
      favoriteRestoreIndex(list, list, "m999")
    )
  }
}
