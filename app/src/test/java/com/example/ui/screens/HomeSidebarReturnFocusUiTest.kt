package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.HomeCatalogs
import com.example.ui.components.HomeFocusOrigin
import com.example.ui.components.HomeSidebarFocusLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RIPRISTINO DEL FOCUS AL RITORNO DALLA SIDEBAR (schermata REALE, [HomeMainBrowsingContent]).
 *
 * Il difetto corretto: uscendo dalla Home verso la sidebar e rientrando con RIGHT il focus era
 * scelto dalla RICERCA GEOMETRICA a destra della voce della sidebar (in cima alla colonna) e
 * atterrava quindi sull'Hero o su una riga diversa da quella di partenza, indipendentemente
 * dalla posizione verticale della Home.
 *
 * Qui la sidebar è simulata come nel layout reale: un nodo focalizzabile FUORI dal contenuto,
 * a sinistra. Il test riproduce esattamente la sequenza del telecomando:
 *   1. cella della Home a fuoco (qualsiasi riga, qualsiasi scroll);
 *   2. `captureOrigin()` — ciò che fa MainActivity PRIMA del LEFT;
 *   3. il focus passa alla voce della sidebar (il LEFT reale);
 *   4. `requestReturn(origin)` — il RIGHT dalla sidebar;
 *   5. il focus deve tornare sullo STESSO elemento (riga + id), non sulla geometria.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h900dp-tvdpi")
class HomeSidebarReturnFocusUiTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val ROW_TRENDING_FILM = "section_trending_film"
    const val ROW_POPOLARI_FILM = "section_popolari_film"
    const val FAKE_SIDEBAR_TAG = "fake_sidebar_item"
    const val CARDS_PER_ROW = 12
    const val VIEWPORT_HEIGHT_DP = 900
  }

  // ---------------------------------------------------------------- fixture

  private fun film(title: String, year: Int, index: Int): MediaItem = MediaItem(
    id = title,
    title = title,
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = year,
    tmdbId = index + 1
  )

  private fun row(prefix: String, year: Int): List<MediaItem> =
    List(CARDS_PER_ROW) { index -> film("$prefix ${index + 1}", year, index) }

  private fun catalogs(
    withTop10: Boolean = false
  ): HomeCatalogs = HomeCatalogs(
    heroItems = List(3) { index ->
      film("HERO ${index + 1}", 2099, 900 + index)
    },
    top10Movies = if (withTop10) row("T10F", 2005) else emptyList(),
    top10Series = if (withTop10) row("T10S", 2006) else emptyList(),
    trendingMovies = row("TF", 2001),
    trendingSeries = row("TS", 2002),
    forYouMovies = row("FF", 2007),
    forYouSeries = row("FS", 2008),
    popularMovies = row("PF", 2003),
    popularSeries = row("PS", 2004)
  )

  private var liveCatalogs by mutableStateOf(catalogs())
  private var liveAllMedia by mutableStateOf<List<MediaItem>>(emptyList())

  private lateinit var link: HomeSidebarFocusLink
  private lateinit var sidebarItemRequester: FocusRequester
  private var sidebarItemFocused by mutableStateOf(false)

  private fun renderHome() {
    link = HomeSidebarFocusLink()
    sidebarItemRequester = FocusRequester()
    rule.setContent {
      Box(modifier = Modifier.fillMaxSize()) {
        HomeMainBrowsingContent(
          allMedia = liveAllMedia,
          homeCatalogs = liveCatalogs,
          onMediaClick = {},
          onPlayClick = {},
          onRemoveFromContinueWatching = {},
          viewModel = null,
          // Stesso scostamento della produzione: il contenuto inizia dopo la sidebar chiusa.
          sidebarFocusLink = link,
          modifier = Modifier
            .fillMaxSize()
            .padding(start = 60.dp)
        )
        // Voce della sidebar (fuori dal contenuto, a sinistra): prende il focus sul LEFT.
        Box(
          modifier = Modifier
            .align(Alignment.CenterStart)
            .size(48.dp)
            .background(Color.DarkGray)
            .focusRequester(sidebarItemRequester)
            .onFocusChanged { sidebarItemFocused = it.hasFocus }
            .focusable()
            .testTag(FAKE_SIDEBAR_TAG)
        )
      }
    }
    rule.waitForIdle()
  }

  // ---------------------------------------------------------------- helper

  private fun focusedTexts(): List<String> =
    rule.onAllNodes(isFocused()).fetchSemanticsNodes()
      .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList() }

  @OptIn(ExperimentalTestApi::class)
  private fun focusCard(title: String) {
    rule.onNode(hasText(title)).requestFocus()
    rule.waitForIdle()
  }

  @OptIn(ExperimentalTestApi::class)
  private fun pressKey(key: Key, times: Int = 1) {
    repeat(times) {
      rule.onNode(isFocused()).performKeyInput { pressKey(key) }
      rule.waitForIdle()
    }
  }

  /** Cattura la provenienza come fa MainActivity sul LEFT, poi porta il focus nella sidebar. */
  private fun leaveHomeToSidebar(): HomeFocusOrigin? {
    val origin = link.captureOrigin()
    rule.runOnIdle { sidebarItemRequester.requestFocus() }
    rule.waitForIdle()
    assertTrue("La voce della sidebar deve avere il focus", sidebarItemFocused)
    rule.onNodeWithTag(FAKE_SIDEBAR_TAG).assertIsFocused()
    return origin
  }

  /** RIGHT dalla sidebar: ripristino deterministico della provenienza. */
  private fun returnToHome(origin: HomeFocusOrigin?) {
    assertNotNull("Provenienza non catturata", origin)
    val accepted = link.requestReturn(origin!!)
    assertTrue("La Home deve accettare il ripristino", accepted)
    rule.waitForIdle()
  }

  /**
   * Il focus è sulla card [title].
   *
   * Non basta `assertTextContains`: le card ancorate (es. «Continua a guardare») espongono il
   * nodo di focus SENZA unire i semantics dei figli, quindi il titolo vive in un nodo figlio.
   * Si accetta quindi sia il testo sul nodo focalizzato sia il contenimento del testo nella
   * geometria del nodo focalizzato (verifica visiva: il bordo del focus copre quella card).
   */
  private fun assertFocusedCard(title: String) {
    rule.waitUntil(timeoutMillis = 5_000) { focusedNodeContains(title) }
    assertTrue("Il focus non è sulla card '$title'", focusedNodeContains(title))
  }

  private fun focusedNodeContains(text: String): Boolean {
    val focused = rule.onAllNodes(isFocused()).fetchSemanticsNodes()
    if (focused.isEmpty()) return false
    val targets = rule.onAllNodes(hasText(text)).fetchSemanticsNodes()
    if (targets.isEmpty()) return false
    return focused.any { node ->
      val texts = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
      texts.contains(text) || targets.any { it.boundsInRoot.center in node.boundsInRoot }
    }
  }

  private fun assertFocusedIsProvider(name: String) {
    rule.waitForIdle()
    val focused = rule.onNode(isFocused()).fetchSemanticsNode().boundsInRoot
    val provider = rule.onNodeWithContentDescription(name).fetchSemanticsNode().boundsInRoot
    assertTrue(
      "Il focus non è sulla card della piattaforma $name (focus=$focused, card=$provider)",
      focused.contains(provider.center)
    )
  }

  private fun focusedTopPx(): Float =
    rule.onNode(isFocused()).fetchSemanticsNode().boundsInRoot.top

  // ------------------------------------------------- Prove A: riga piattaforme, Netflix

  @Test
  fun rightDallaSidebarRiportaSulPrimoElementoDellaRigaPiattaforme() {
    renderHome()

    // La riga delle piattaforme è l'ultima riga prima delle card: la si raggiunge con il D-pad.
    focusCard("TF 1")
    pressKey(Key.DirectionUp)
    assertFocusedIsProvider("Netflix")

    val origin = leaveHomeToSidebar()
    assertEquals("section_providers_hub", origin?.rowKey)
    assertEquals("netflix", origin?.identity)
    assertEquals(0, origin?.colIndex)

    returnToHome(origin)

    // NON l'Hero, NON una riga precedente: esattamente Netflix.
    assertFocusedIsProvider("Netflix")
    assertFalse(focusedTexts().contains("HERO 1"))
  }

  @Test
  fun rightDallaSidebarRiportaSullElementoNonPrimoDellaRigaPiattaforme() {
    renderHome()

    focusCard("TF 1")
    pressKey(Key.DirectionUp)
    pressKey(Key.DirectionRight, times = 2)
    assertFocusedIsProvider("Disney+")

    val origin = leaveHomeToSidebar()
    assertEquals("section_providers_hub", origin?.rowKey)
    assertEquals("disney", origin?.identity)
    assertEquals(2, origin?.colIndex)

    returnToHome(origin)

    assertFocusedIsProvider("Disney+")
  }

  // ------------------------------- Prove B/C: Home scorsa, posizione verticale indifferente

  @Test
  fun rightDallaSidebarRiportaSullaStessaCardAncheConHomeScorsa() {
    renderHome()

    // Discesa fino a una riga BASSA: la LazyColumn deve scorrere (prova B/C).
    focusCard("TF 1")
    pressKey(Key.DirectionDown, times = 4)
    pressKey(Key.DirectionRight, times = 2)
    assertFocusedCard("PF 3")

    val origin = leaveHomeToSidebar()
    assertEquals(ROW_POPOLARI_FILM, origin?.rowKey)
    assertEquals("PF 3", origin?.identity)
    assertTrue(
      "La Home deve risultare scorsa (firstVisibleItemIndex=${origin?.listFirstVisibleIndex})",
      (origin?.listFirstVisibleIndex ?: 0) > 0
    )

    returnToHome(origin)

    assertFocusedCard("PF 3")
    // La card ripristinata è realmente dentro il viewport (non fuori campo).
    val top = focusedTopPx()
    assertTrue("Card fuori dal viewport (top=$top)", top >= 0f)
  }

  @Test
  fun laPosizioneVerticaleDellaRigaNonCambiaIlRisultato() {
    renderHome()

    // PROVA A — riga piattaforme nella posizione iniziale (nessuna riga sopra l'hub).
    focusCard("TF 1")
    pressKey(Key.DirectionUp)
    pressKey(Key.DirectionRight, times = 2)
    assertFocusedIsProvider("Disney+")
    val originIniziale = leaveHomeToSidebar()
    returnToHome(originIniziale)
    assertFocusedIsProvider("Disney+")

    // PROVA C — la riga piattaforme finisce più IN BASSO: compare «Continua a guardare»
    // (riga sopra l'hub) mentre l'utente è in Home. Il layout si ricompone e la riga cambia
    // posizione verticale: il ritorno deve essere IDENTICO.
    rule.runOnIdle {
      liveAllMedia = listOf(
        film("CW 1", 2010, 50).copy(
        currentProgressMs = 10_000L,
        lastWatchedAt = 1_000L,
        inContinueWatching = true
      )
      )
    }
    rule.waitForIdle()

    focusCard("TF 1")
    pressKey(Key.DirectionUp)
    pressKey(Key.DirectionRight, times = 2)
    assertFocusedIsProvider("Disney+")

    val originSpostata = leaveHomeToSidebar()
    assertEquals("section_providers_hub", originSpostata?.rowKey)
    assertEquals("disney", originSpostata?.identity)
    // La riga di provenienza ha cambiato POSIZIONE nell'ordine verticale: l'hub passa
    // dall'ordinale 1 (subito dopo l'Hero) all'ordinale 2 (dopo «Continua a guardare»).
    assertEquals(1, originIniziale?.rowOrdinal)
    assertEquals(2, originSpostata?.rowOrdinal)

    returnToHome(originSpostata)
    assertFocusedIsProvider("Disney+")
  }

  // ------------------------------- Prove D: altre righe della Home

  @Test
  fun rightDallaSidebarRiportaSullaRigaContinuaAGuardare() {
    // «Continua a guardare» esiste quando c'è un contenuto con ripresa reale: la riga è la
    // prima sotto l'Hero e cambia l'ordine/le altezze di tutte le righe successive.
    liveAllMedia = listOf(
      film("CW 1", 2010, 50).copy(
        currentProgressMs = 10_000L,
        lastWatchedAt = 1_000L,
        inContinueWatching = true
      )
    )
    renderHome()

    focusCard("TF 1")
    pressKey(Key.DirectionUp, times = 2)
    assertFocusedCard("CW 1")

    val origin = leaveHomeToSidebar()
    assertEquals("section_continue_watching", origin?.rowKey)
    assertEquals("CW 1", origin?.identity)

    returnToHome(origin)
    assertFocusedCard("CW 1")
  }

  @Test
  fun rightDallaSidebarRiportaSullaRigaHero() {
    renderHome()

    rule.onNode(hasText("HERO 1")).requestFocus()
    rule.waitForIdle()

    val origin = leaveHomeToSidebar()
    assertEquals("hero_banner", origin?.rowKey)

    returnToHome(origin)
    assertFocusedCard("HERO 1")
  }

  // ------------------------------- Ricomposizione / aggiornamento dei contenuti

  @Test
  fun aggiornamentoDellaHomeMentreSeiInSidebar_ripristinaLoStessoContenuto() {
    renderHome()

    focusCard("TF 1")
    pressKey(Key.DirectionDown, times = 2)
    pressKey(Key.DirectionRight, times = 3)
    assertFocusedCard("FF 4")

    val origin = leaveHomeToSidebar()
    assertEquals("section_for_you_film", origin?.rowKey)
    assertEquals("FF 4", origin?.identity)

    // ONdata dati mentre l'utente è nella sidebar: compaiono due righe SOPRA quella di
    // provenienza (Top 10 film/serie). La riga si sposta in basso: il ripristino deve seguire
    // l'IDENTITÀ del contenuto, non l'indice né la geometria.
    rule.runOnIdle { liveCatalogs = catalogs(withTop10 = true) }
    rule.waitForIdle()

    returnToHome(origin)

    assertFocusedCard("FF 4")
  }

  // NOTA — caso «elemento originario non più disponibile»: la decisione (stessa riga con
  // colonna clampata, contenuto trasferitosi in un'altra riga, riga più vicina) è verificata
  // in [HomeRowReturnToOriginTest] e [com.example.ui.components.HomeSidebarFocusReturnTest].
  // Qui non si rimuovono card durante il test: la rimozione di un item mentre la sua riga è
  // «pinnata» dal `focusRestorer` è un difetto PREESISTENTE e indipendente dalla sidebar
  // (vedi il commento sulla rimozione da «Continua a guardare» in HomeScreen) e farebbe
  // fallire il teardown con "Release should only be called once".

  // ------------------------------- Navigazione ripetuta sidebar <-> Home

  @Test
  fun andataERitornoRipetuti_riportanoSempreSulloStessoElemento() {
    renderHome()

    focusCard("TF 1")
    pressKey(Key.DirectionDown, times = 4)
    pressKey(Key.DirectionRight, times = 4)
    assertFocusedCard("PF 5")

    repeat(3) {
      val origin = leaveHomeToSidebar()
      assertEquals("PF 5", origin?.identity)
      returnToHome(origin)
      assertFocusedCard("PF 5")
    }
  }
}
