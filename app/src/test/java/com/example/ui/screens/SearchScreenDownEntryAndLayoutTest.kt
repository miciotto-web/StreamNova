package com.example.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.example.HOME_CONTENT_ANCHOR_ID
import com.example.HOME_CONTENT_ANCHOR_ROW
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedTarget
import com.example.ui.viewmodel.StreamNovaViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Ingresso Giù dai filtri nella griglia e impilamento verticale della superficie Ricerca,
 * con il CABLAGGIO DI PRODUZIONE delle CompositionLocal (MainActivity): id/target/token di
 * ripristino letti dai flow del ViewModel e cancelletto rilasciato con la STESSA chiamata
 * della schermata.
 *
 * Il test esistente (SearchResultsFocusCycleTest) pilotava i local con stati del test a
 * partire da `null`: il difetto reale del dispositivo — il target STANTIO scritto dalla
 * MainActivity al rientro dal Dettaglio (l'ancora sintetica "home_content_anchor") che
 * CHIUDE il gate `canFocus` di TUTTE le card e fa rifiutare in silenzio requestFocus() —
 * non era mai stato coperto. Qui il target stantio viene creato sul ViewModel, esattamente
 * come in produzione, e il Giù deve funzionare DOPO il purge della schermata.
 *
 * La seconda metà copre il layout: nella superficie dei contenuti i rami del `when`
 * emettevano header/messaggio/griglia come fratelli DIRETTI di un Box (= Stack), quindi le
 * card disegnavano sopra l'header e sopra il badge della categoria (il giallo "COMMEDIA").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class SearchScreenDownEntryAndLayoutTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val RESULT_TITLE_PREFIX = "Risultato "
    // I test JVM risolvono le stringhe con il locale di Robolectric (en): ogni matcher
    // del testo delle UI del progetto deve includere la variante EN.
    const val FILTER_ALL_LABEL = "Tutti"
    const val FILTER_ALL_LABEL_EN = "All"
    const val FILTER_FILM_LABEL = "Film"
    const val FILTER_FILM_LABEL_EN = "Movies"
    const val FILTER_SERIE_LABEL = "Serie TV"
    const val FILTER_SERIE_LABEL_EN = "TV Shows"
    const val HEADER_FOUND_SUBSTRING = "Risultati trovati"
    const val HEADER_FOUND_SUBSTRING_EN = "Results for"
    // Badge categoria "commedia": etichetta in maiuscole dipendente dal locale del test.
    const val COMMEDIA_CHIP = "COMMEDIA"
    const val COMMEDIA_CHIP_EN = "COMEDY"
  }

  private lateinit var viewModel: StreamNovaViewModel

  private fun item(index: Int): MediaItem = MediaItem(
    id = "%02d".format(index + 1),
    title = RESULT_TITLE_PREFIX + "%02d".format(index + 1),
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = 2000 + index,
    tmdbId = index + 1
  )

  /**
   * Renderizza SearchScreen con le CompositionLocal fornite DAI FLOW del ViewModel
   * (stessa sorgente di MainActivity): solo così il purge del target stantio riapre
   * davvero i cancelletti `canFocus` delle card nel test.
   */
  private fun render(
    results: List<MediaItem>,
    withQuery: Boolean = true,
    presetStaleAnchor: Boolean = false,
    categoryId: String? = null
  ) {
    val vm = StreamNovaViewModel()
    viewModel = vm
    if (withQuery) vm.updateSearchQuery("dune")
    if (categoryId != null) vm.setSelectedSearchCategoryId(categoryId)
    if (presetStaleAnchor) {
      // come fa MainActivity al rientro dal Dettaglio con target consumato
      vm.saveLastFocusedMedia(HOME_CONTENT_ANCHOR_ROW, HOME_CONTENT_ANCHOR_ID)
    }
    rule.setContent {
      CompositionLocalProvider(
        LocalLastFocusedTarget provides vm.lastFocusedTarget.collectAsState().value,
        LocalLastFocusedMediaId provides vm.lastFocusedMediaId.collectAsState().value,
        LocalFocusRestoreToken provides vm.focusRestoreToken.collectAsState().value,
        LocalClearLastFocusedMedia provides { vm.clearLastFocusedMedia() }
      ) {
        SearchScreen(
          viewModel = vm,
          onMediaClick = {},
          resultsOverride = results
        )
      }
    }
    rule.waitForIdle()
  }

  private fun filterMatcher(label: String, labelEn: String = label) =
    hasAnyDescendant(hasText(label)) or hasAnyDescendant(hasText(labelEn))

  private fun focusFilter(label: String, labelEn: String = label) {
    rule.onNode(isFocusable() and filterMatcher(label, labelEn)).requestFocus()
    rule.waitForIdle()
  }

  private fun assertFilterFocused(label: String, labelEn: String = label) {
    rule.waitUntil(timeoutMillis = 3_000) {
      try {
        rule.onNode(isFocused() and filterMatcher(label, labelEn)).fetchSemanticsNode()
        true
      } catch (_: Throwable) {
        false
      }
    }
  }

  private fun focusedText(): String? = try {
    rule.onNode(isFocused()).fetchSemanticsNode().config
      .getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
  } catch (_: Throwable) {
    null
  }

  /**
   * Bounds del nodo FOCALIZZATO reale letti dalla proprietà `Focused`. I chip
   * TvFocusableBox NON fondono il testo dei discendenti (niente Modifier.clickable),
   * quindi il testo del nodo focalizzato può mancare: l'identità va verificata con questa
   * proprietà + geometria.
   */
  private fun focusedNodeBounds(): Rect? =
    rule.onAllNodes(isFocusable()).fetchSemanticsNodes()
      .firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }
      ?.boundsInRoot

  private fun assertFocusedCardTitle(expected: String?) {
    rule.waitUntil(timeoutMillis = 3_000) {
      focusedText()?.startsWith(expected.orEmpty()) == true
    }
    val actual = focusedText()
    assertTrue(
      "Il focus non è sulla card '$expected' ma su '$actual'",
      expected != null && actual?.startsWith(expected) == true
    )
  }

  private data class VisibleCard(val title: String, val bounds: Rect)

  /** Card dei risultati realmente composte nel viewport, con i bounds. */
  private fun visibleResultCards(): List<VisibleCard> =
    rule.onAllNodes(isFocusable() and hasText(RESULT_TITLE_PREFIX, substring = true))
      .fetchSemanticsNodes()
      .mapNotNull { node ->
        val texts = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }
          ?: return@mapNotNull null
        val title = texts.firstOrNull { it.startsWith(RESULT_TITLE_PREFIX) }
          ?: return@mapNotNull null
        VisibleCard(title, node.boundsInRoot)
      }

  /** Card visibile in alto a sinistra della griglia (quella che il Giù deve raggiungere). */
  private fun topLeftVisibleCard(): VisibleCard? =
    visibleResultCards().minWithOrNull(compareBy({ it.bounds.top }, { it.bounds.left }))

  private fun filterBottom(): Float =
    rule.onNode(isFocusable() and filterMatcher(FILTER_ALL_LABEL, FILTER_ALL_LABEL_EN))
      .fetchSemanticsNode().boundsInRoot.bottom

  @OptIn(ExperimentalTestApi::class)
  private fun pressKey(key: Key) {
    rule.onNode(isFocused()).performKeyInput { pressKey(key) }
    rule.waitForIdle()
  }

  /** Risale dai risultati ai filtri come l'utente (al massimo [maxSteps] Su). */
  @OptIn(ExperimentalTestApi::class)
  private fun pressUpUntilFilterFocused(
    label: String,
    labelEn: String = label,
    maxSteps: Int = 40
  ) {
    repeat(maxSteps) {
      rule.waitForIdle()
      val onFilter = try {
        rule.onNode(isFocused() and filterMatcher(label, labelEn)).fetchSemanticsNode()
        true
      } catch (_: Throwable) {
        false
      }
      if (onFilter) return
      pressKey(Key.DirectionUp)
    }
    assertFilterFocused(label, labelEn)
  }

  private fun searchFocusLogs(): List<ShadowLog.LogItem> =
    ShadowLog.getLogs().filter { it.tag == "SEARCH_FOCUS" }

  /**
   * DIFETTO DISPOSITIVO: il rientro dal Dettaglio lascia la stilla "home_content_anchor"
   * sul ViewModel; finché non viene rilasciata, il gate `canFocus` rifiuta in silenzio le
   * richieste di focus su OGNI card e il Giù dai filtri resta morto. La schermata deve
   * purgare il target stantio appena la lista popolata non lo contiene.
   */
  @Test
  fun giuDaFiltroEntraNeiRisultatiAncheConTargetDiRipristinoStantio() {
    render(List(24) { item(it) }, presetStaleAnchor = true)

    // Il purge effettivo: l'ancora sintetica non appartiene alla lista dei risultati.
    rule.waitUntil(timeoutMillis = 3_000) {
      viewModel.lastFocusedMediaId.value == null
    }
    assertTrue(
      "Manca il log di purge del target stantio.\n" +
        searchFocusLogs().joinToString("\n") { it.msg },
      searchFocusLogs().any {
        it.msg.contains("stale restore target 'home_content_anchor'")
      }
    )

    focusFilter(FILTER_ALL_LABEL, FILTER_ALL_LABEL_EN)
    assertFilterFocused(FILTER_ALL_LABEL, FILTER_ALL_LABEL_EN)
    ShadowLog.clear()
    val expected = topLeftVisibleCard()?.title
    pressKey(Key.DirectionDown)
    assertFocusedCardTitle(expected)
  }

  /**
   * Ciclo completo ripetuto TRE volte: filtri -> Giù -> card -> Su -> filtro -> Giù.
   * L'attivazione del chip avviene con OK (DirectionCenter), come col telecomando, così
   * `activeFilterRequester` è il chip "Film" e la risalita con Su torna a lui.
   * In coda la verifica LEFT/RIGHT tra i filtri.
   */
  @Test
  fun cicloTriploGiuSuSulFiltroFilmConNavigazioneOrizzontale() {
    render(List(24) { item(it) })

    focusFilter(FILTER_ALL_LABEL, FILTER_ALL_LABEL_EN)
    assertFilterFocused(FILTER_ALL_LABEL, FILTER_ALL_LABEL_EN)
    // RIGHT tra i filtri: si raggiunge il chip "Film"
    pressKey(Key.DirectionRight)
    assertFilterFocused(FILTER_FILM_LABEL, FILTER_FILM_LABEL_EN)
    // OK sul chip: il filtro attivo diventa FILM (il focus resta sul chip)
    pressKey(Key.DirectionCenter)
    assertFilterFocused(FILTER_FILM_LABEL, FILTER_FILM_LABEL_EN)

    repeat(3) {
      ShadowLog.clear()
      val expected = topLeftVisibleCard()?.title
      pressKey(Key.DirectionDown)
      assertFocusedCardTitle(expected)
      pressUpUntilFilterFocused(FILTER_FILM_LABEL, FILTER_FILM_LABEL_EN)
      assertFilterFocused(FILTER_FILM_LABEL, FILTER_FILM_LABEL_EN)
    }

    // LEFT/RIGHT tra i filtri continuano a funzionare dopo i cicli.
    pressKey(Key.DirectionLeft)
    assertFilterFocused(FILTER_ALL_LABEL, FILTER_ALL_LABEL_EN)
    pressKey(Key.DirectionRight)
    assertFilterFocused(FILTER_FILM_LABEL, FILTER_FILM_LABEL_EN)
    pressKey(Key.DirectionRight)
    assertFilterFocused(FILTER_SERIE_LABEL, FILTER_SERIE_LABEL_EN)
    pressKey(Key.DirectionLeft)
    assertFilterFocused(FILTER_FILM_LABEL, FILTER_FILM_LABEL_EN)
  }

  /**
   * Stato DEFAULT (query vuota, nessuna categoria): il Giù dai filtri NON ha un target
   * esplicito e deve affidarsi alla ricerca geometrica nativa, che raggiunge la prima riga
   * di badge delle categorie. Prima della rimozione del hook morto sulla superficie, un
   * requester non focalizzabile smorzava il movimento.
   */
  @Test
  fun giuConQueryVuotaRaggiungeIBadgeDelleCategorie() {
    render(emptyList(), withQuery = false)
    focusFilter(FILTER_ALL_LABEL, FILTER_ALL_LABEL_EN)
    assertFilterFocused(FILTER_ALL_LABEL, FILTER_ALL_LABEL_EN)

    pressKey(Key.DirectionDown)

    // Il focus è USCITO dalla riga filtri ed è atterrato su un nodo focalizzabile SOTTO
    // di essa (un badge della griglia categorie). L'identità si verifica via proprietà
    // `Focused` + geometria: i badge non fondono il testo dei discendenti.
    rule.waitUntil(timeoutMillis = 3_000) {
      val bounds = focusedNodeBounds()
      bounds != null && bounds.top >= filterBottom()
    }

    // Risalita: il badge della prima riga torna al filtro attivo.
    pressKey(Key.DirectionUp)
    assertFilterFocused(FILTER_ALL_LABEL, FILTER_ALL_LABEL_EN)
  }

  /**
   * LAYOUT (rama query): header "N risultati per ..." e griglia sono impilati nella stessa
   * cella del Box: la prima card NON deve mai sovrapporsi all'header né invadere l'area
   * dei filtri.
   */
  @Test
  fun laGrigliaRisultatiParteSottoLheaderESottoIFiltri() {
    render(List(24) { item(it) })

    val headerMatcher = hasText(HEADER_FOUND_SUBSTRING, substring = true) or
      hasText(HEADER_FOUND_SUBSTRING_EN, substring = true)
    val headerBounds = rule.onNode(headerMatcher).fetchSemanticsNode().boundsInRoot
    val bottom = filterBottom()
    val firstCard = topLeftVisibleCard() ?: throw AssertionError("Nessuna card visibile")

    assertTrue(
      "L'header dei risultati deve stare sotto la riga dei filtri " +
        "(headerTop=${headerBounds.top}, filterBottom=$bottom)",
      headerBounds.top >= bottom
    )
    assertTrue(
      "La prima card (top=${firstCard.bounds.top}) non deve sovrapporsi all'header " +
        "(headerBottom=${headerBounds.bottom})",
      firstCard.bounds.top >= headerBounds.bottom
    )
  }

  /**
   * LAYOUT (ramo categoria): badge "COMMEDIA", messaggio di stato e area contenuti sono
   * impilati verticalmente: il messaggio inizia SOTTO il badge, che a sua volta sta sotto
   * la riga dei filtri. Con i rami emessi come fratelli del Box il badge finiva coperto.
   */
  @Test
  fun laSuperficieCategoriaImpilaBadgeEMessaggioSenzaSovrapposizioni() {
    render(emptyList(), withQuery = false, categoryId = "commedia")

    val chipBounds = rule.onNode(hasText(COMMEDIA_CHIP) or hasText(COMMEDIA_CHIP_EN))
      .fetchSemanticsNode().boundsInRoot
    val messageBounds = rule.onNode(
      hasText("Nessun contenuto", substring = true) or
        hasText("No content found", substring = true)
    ).fetchSemanticsNode().boundsInRoot
    val bottom = filterBottom()

    assertTrue(
      "Il badge categoria deve stare sotto la riga dei filtri " +
        "(chipTop=${chipBounds.top}, filterBottom=$bottom)",
      chipBounds.top >= bottom
    )
    assertTrue(
      "Il messaggio categoria deve iniziare sotto il badge " +
        "(badgeBottom=${chipBounds.bottom}, messageTop=${messageBounds.top})",
      messageBounds.top >= chipBounds.bottom
    )
  }
}
