package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.HomeCatalogs
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedTarget
import com.example.ui.components.TvFocusableBox
import com.example.ui.viewmodel.StreamNovaViewModel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DIFETTO: «Continua a guardare» -> long-press -> «Rimuovi da Continua a guardare» -> il focus
 * finisce fuori dal contenuto e, uscendo dalla schermata (o cambiando sezione dalla sidebar),
 * l'app va in crash.
 *
 * Meccanismo dimostrato (Compose 1.7.2):
 * 1. il menu contestuale è un overlay nella STESSA finestra: prendendo il focus fa USCIRE il
 *    focus dal gruppo della riga, e `Modifier.focusRestorer` (riga + LazyColumn) nel suo `exit`
 *    fa `pinFocusedChild()` -> `LazyLayoutPinnableItem.pin()` sull'item della card;
 * 2. «Rimuovi» toglie la card dalla lista mentre il pin è ancora trattenuto: l'item viene
 *    smontato e `LazyLayoutPinnableItem.onDisposed()` decrementa `pinsCount` a 0, lasciando il
 *    `pinnedHandle` del restorer puntato a un oggetto morto;
 * 3. alla successiva `release()` di quel restorer (rientro nella riga, uscita, o `onDetach`
 *    quando la Home viene smontata) `check(pinsCount > 0)` fallisce con
 *    `IllegalStateException: "Release should only be called once"` -> CRASH.
 *
 * Harness fedele alla MainActivity: ViewModel reale, contenuto Home reale
 * ([HomeMainBrowsingContent]) con la riga «Continua a guardare», sidebar focalizzabile composta
 * accanto al contenuto e rimozione che aggiorna la lista osservata (come fa il repository).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class ContinueWatchingRemovalFocusTest {

  @get:Rule
  val rule = createComposeRule()

  /** Etichetta REALE della voce di rimozione (il test non dipende dalla locale del device). */
  private val removeLabel: String =
    ApplicationProvider.getApplicationContext<android.content.Context>()
      .getString(R.string.context_remove_continue_watching)

  /** Titolo REALE della riga, risolto dalle risorse: nessuna dipendenza dalla locale. */
  private val cwRowTitle: String =
    ApplicationProvider.getApplicationContext<android.content.Context>()
      .getString(R.string.home_section_continue_watching)

  private companion object {
    const val HERO_TITLE = "HERO 1"
    const val SIDEBAR_TAG_PREFIX = "sidebar_item_"
    const val SIDEBAR_ITEMS = 4
    val SIDEBAR_WIDTH = 60.dp
  }

  // ---------------------------------------------------------------- fixture

  private fun cwItem(index: Int, lastWatchedAt: Long): MediaItem = MediaItem(
    id = "cw-${index + 1}",
    title = "CW ${index + 1}",
    synopsis = "",
    videoUrl = "",
    type = MediaType.FILM,
    year = 2200 + index,
    tmdbId = 7000 + index,
    currentProgressMs = 10 * 60_000L,
    totalDurationMs = 100 * 60_000L,
    lastWatchedAt = lastWatchedAt
  )

  private fun cwItems(count: Int = 4): List<MediaItem> =
    List(count) { cwItem(it, lastWatchedAt = (count - it).toLong() * 1_000L) }

  private fun catalogs(): HomeCatalogs = HomeCatalogs(
    heroItems = listOf(
      MediaItem(
        id = "hero-1",
        title = HERO_TITLE,
        synopsis = "",
        videoUrl = "",
        type = MediaType.FILM,
        year = 2099,
        tmdbId = 900
      )
    )
  )

  // ---------------------------------------------------------------- harness

  private lateinit var viewModel: StreamNovaViewModel

  /** Riga «Continua a guardare» osservabile: la rimozione la accorcia come fa il repository. */
  private var cwList by mutableStateOf(emptyList<MediaItem>())

  /** Uscita dalla schermata: la Home viene SMONTATA (cambio sezione dalla sidebar). */
  private var homeVisible by mutableStateOf(true)

  @Composable
  private fun App() {
    val lastFocusedMediaId by viewModel.lastFocusedMediaId.collectAsState()
    val lastFocusedTarget by viewModel.lastFocusedTarget.collectAsState()
    val focusRestoreToken by viewModel.focusRestoreToken.collectAsState()

    CompositionLocalProvider(
      LocalLastFocusedMediaId provides lastFocusedMediaId,
      LocalLastFocusedTarget provides lastFocusedTarget,
      LocalFocusRestoreToken provides focusRestoreToken,
      LocalClearLastFocusedMedia provides { viewModel.clearLastFocusedMedia() }
    ) {
      Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0F14))) {
        if (homeVisible) {
          HomeMainBrowsingContent(
            allMedia = cwList,
            homeCatalogs = catalogs(),
            onMediaClick = {},
            onPlayClick = {},
            // Stessa catena della MainActivity: la riga si accorcia e la card viene smontata.
            onRemoveFromContinueWatching = { mediaId ->
              cwList = cwList.filterNot { it.id == mediaId }
            },
            viewModel = viewModel,
            modifier = Modifier
              .fillMaxSize()
              .padding(start = SIDEBAR_WIDTH)
          )
        }
        SidebarStub()
      }
    }
  }

  @Composable
  private fun BoxScope.SidebarStub() {
    Box(
      modifier = Modifier
        .align(Alignment.CenterStart)
        .fillMaxHeight()
        .width(SIDEBAR_WIDTH)
        .background(Color(0xFF101820))
    ) {
      Column(
        modifier = Modifier
          .fillMaxHeight()
          .padding(vertical = 16.dp, horizontal = 4.dp)
      ) {
        Spacer(modifier = Modifier.height(48.dp))
        Column(
          modifier = Modifier.fillMaxHeight(),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          repeat(SIDEBAR_ITEMS) { index ->
            TvFocusableBox(
              modifier = Modifier
                .height(48.dp)
                .width(48.dp)
                .testTag("$SIDEBAR_TAG_PREFIX$index")
            ) { }
          }
        }
      }
    }
  }

  private fun render(items: List<MediaItem> = cwItems()) {
    viewModel = StreamNovaViewModel(ioDispatcher = StandardTestDispatcher())
    cwList = items
    homeVisible = true
    rule.setContent { App() }
    rule.waitForIdle()
  }

  // ---------------------------------------------------------------- helpers

  private fun focusedNodes(): List<SemanticsNode> =
    rule.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()

  private fun allTextsOf(node: SemanticsNode): List<String> {
    val own = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()
    return own + node.children.flatMap { allTextsOf(it) }
  }

  private fun testTagOf(node: SemanticsNode): String? =
    generateSequence(node) { it.parent }
      .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
      .firstOrNull()

  private fun isCardFocused(title: String): Boolean {
    val focusable = rule.onAllNodes(
      SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus),
      useUnmergedTree = true
    )
    return focusable.fetchSemanticsNodes().any { node ->
      node.config.getOrNull(SemanticsProperties.Focused) == true && allTextsOf(node).contains(title)
    }
  }

  @OptIn(ExperimentalTestApi::class)
  private fun focusCard(title: String) {
    val focusable = rule.onAllNodes(
      SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus),
      useUnmergedTree = true
    )
    val index = focusable.fetchSemanticsNodes().indexOfFirst { allTextsOf(it).contains(title) }
    require(index >= 0) { "card '$title' non trovata" }
    focusable[index].requestFocus()
    rule.waitForIdle()
  }

  @OptIn(ExperimentalTestApi::class)
  private fun pressKey(key: Key) {
    rule.onNode(isFocused(), useUnmergedTree = true).performKeyInput { pressKey(key) }
    rule.waitForIdle()
  }

  /** Long-press del tasto di conferma sulla card a fuoco: apre il menu contestuale. */
  @OptIn(ExperimentalTestApi::class)
  private fun longPressFocusedCard() {
    rule.onNode(isFocused(), useUnmergedTree = true).performKeyInput {
      keyDown(Key.DirectionCenter)
    }
    // Il long-press è un `delay(400)` nel TvFocusableBox: attesa reattiva (l'orologio del test
    // avanza mentre la condizione è falsa), così il caso non dipende dal meccanismo del delay.
    rule.waitUntil(timeoutMillis = 5_000) { isMenuVisible() }
    assertTrue(
      "il menu contestuale non si è aperto: focus su [${focusedNodes().flatMap { allTextsOf(it) }}]",
      isMenuVisible()
    )
    // Rilascio del tasto ancora premuto: consumato dalla guardia del menu.
    rule.onNode(isFocused(), useUnmergedTree = true).performKeyInput {
      keyUp(Key.DirectionCenter)
    }
    // `canClick` si abilita 250 ms dopo l'apertura.
    rule.mainClock.advanceTimeBy(400)
    rule.waitForIdle()
  }

  private fun isMenuVisible(): Boolean =
    rule.onAllNodesWithText(removeLabel, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

  /**
   * Scende sulla voce «Rimuovi da Continua a guardare» (terza del menu) e la attiva.
   * L'effetto (la card tolta dalla riga) è l'asserzione del chiamante: qui non si legge la
   * semantica del menu, che nei test è fusa nell'albero unito.
   */
  private fun activateRemoveMenuItem() {
    pressKey(Key.DirectionDown)
    pressKey(Key.DirectionDown)
    pressKey(Key.DirectionCenter)
    rule.waitForIdle()
  }

  /**
   * Attende che la rimozione sia stata APPLICATA: il secondo tempo della rimozione vive in un
   * effetto (attesa della conferma di focus dentro la riga), non nel gestore del click.
   */
  private fun awaitCwCount(expected: Int) {
    rule.waitUntil(timeoutMillis = 5_000) { cwList.size == expected }
    assertEquals(expected, cwList.size)
  }

  /** Rimuove la card [title] della riga e attende l'applicazione della rimozione. */
  private fun removeCardByTitle(title: String, expectedRemaining: Int) {
    focusCard(title)
    longPressFocusedCard()
    activateRemoveMenuItem()
    awaitCwCount(expectedRemaining)
  }

  /** Titoli attualmente nella riga «Continua a guardare», nell'ordine in cui sono mostrati. */
  private fun cwTitles(): List<String> = cwList.map { it.title }

  private fun isContinueWatchingRowShown(): Boolean =
    rule.onAllNodesWithText(cwRowTitle, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

  /**
   * Uscita dalla schermata: la Home viene smontata come quando la sidebar cambia sezione.
   * Una eccezione qui è un CRASH reale (disposal della composizione con lo stato del focus).
   */
  private fun leaveHomeAndReportCrash(): Throwable? {
    var thrown: Throwable? = null
    try {
      homeVisible = false
      rule.waitForIdle()
    } catch (t: Throwable) {
      thrown = t
    }
    return thrown
  }

  private fun headOfCrash(crash: Throwable?): String =
    crash?.stackTrace?.take(6)?.joinToString("\n  at ") { it.toString() }.orEmpty()

  /** Verifica che la disposal della Home (uscita dalla schermata) non abbia lanciato nulla. */
  private fun assertNoCrashOnDisposal(crash: Throwable?, what: String) {
    assertNull(
      "CRASH alla disposal $what: ${crash?.javaClass?.name}: ${crash?.message}\n  at ${headOfCrash(crash)}",
      crash
    )
  }

  // ---------------------------------------------------------------- 1. crash

  /**
   * Il percorso segnalato: rimozione di una card di «Continua a guardare» e poi uscita dalla
   * schermata. La disposal della Home NON deve lanciare eccezioni.
   */
  @Test
  fun rimozioneDallaRigaNonFaEsplodereLaDisposalDellaHome() {
    render()
    focusCard("CW 3")
    longPressFocusedCard()
    activateRemoveMenuItem()
    awaitCwCount(3)

    assertFalse("la card rimossa è ancora composta", isCardFocused("CW 3"))

    val crash = leaveHomeAndReportCrash()
    assertNull(
      "CRASH alla disposal della Home dopo la rimozione:\n" +
        "${crash?.javaClass?.name}: ${crash?.message}\n  at ${headOfCrash(crash)}",
      crash
    )
  }

  /**
   * Stesso percorso con l'ULTIMA card della riga: sparisce la card E la riga intera, quindi
   * vengono smontati sia l'item del LazyRow sia l'item del LazyColumn che la conteneva.
   */
  @Test
  fun rimozioneDellUltimaCardNonFaEsplodereLaDisposalDellaHome() {
    render(items = cwItems(count = 1))
    focusCard("CW 1")
    longPressFocusedCard()
    activateRemoveMenuItem()
    awaitCwCount(0)

    val crash = leaveHomeAndReportCrash()
    assertNull(
      "CRASH alla disposal della Home dopo la rimozione dell'ultima card:\n" +
        "${crash?.javaClass?.name}: ${crash?.message}\n  at ${headOfCrash(crash)}",
      crash
    )
  }

  // ---------------------------------------------------------------- 2. focus

  /**
   * Dopo la rimozione il focus deve restare NEL contenuto (ancora Home), mai sulla sidebar, e il
   * D-pad deve essere vivo: è il sintomo riferito dall'utente prima del crash.
   */
  @Test
  fun dopoLaRimozioneIlFocusRestaNelContenutoEIlDpadEVIvo() {
    render()
    focusCard("CW 3")
    longPressFocusedCard()
    activateRemoveMenuItem()
    awaitCwCount(3)

    // Il fuoco deve tornare su una cella del contenuto (l'ancora canonica è l'Hero).
    rule.waitUntil(timeoutMillis = 5_000) { focusedNodes().isNotEmpty() }
    val sidebarRightPx = with(rule.density) { SIDEBAR_WIDTH.toPx() }
    focusedNodes().forEach { node ->
      val tag = testTagOf(node)
      assertFalse(
        "il focus è finito sulla SIDEBAR (tag=$tag, bounds=${node.boundsInRoot})",
        tag?.startsWith(SIDEBAR_TAG_PREFIX) == true
      )
      assertTrue(
        "il focus è uscito dal contenuto (tag=$tag, bounds=${node.boundsInRoot})",
        node.boundsInRoot.left >= sidebarRightPx - 1f
      )
    }

    // D-pad vivo: GIÙ dalla cella ancorata muove il focus dentro il contenuto.
    val before = focusedNodes().joinToString("|") { "${testTagOf(it)}:${it.boundsInRoot}" }
    pressKey(Key.DirectionDown)
    val after = focusedNodes().joinToString("|") { "${testTagOf(it)}:${it.boundsInRoot}" }
    assertTrue("D-pad morto dopo la rimozione", focusedNodes().isNotEmpty())
    assertTrue("il focus non si è mosso (prima=$before, dopo=$after)", before != after)
    focusedNodes().forEach { node ->
      assertFalse(
        "dopo GIÙ il focus è sulla SIDEBAR (tag=${testTagOf(node)})",
        testTagOf(node)?.startsWith(SIDEBAR_TAG_PREFIX) == true
      )
    }
  }

  /** Rimozioni consecutive rapide: due card eliminate una dopo l'altra senza uscire dalla riga. */
  @Test
  fun rimozioniConsecutiveRapideRestanoStabili() {
    render()
    focusCard("CW 3")

    longPressFocusedCard()
    activateRemoveMenuItem()
    awaitCwCount(3)

    focusCard("CW 1")
    longPressFocusedCard()
    activateRemoveMenuItem()
    awaitCwCount(2)

    val crash = leaveHomeAndReportCrash()
    assertNull(
      "CRASH alla disposal dopo rimozioni consecutive:\n" +
        "${crash?.javaClass?.name}: ${crash?.message}\n  at ${headOfCrash(crash)}",
      crash
    )
  }

  // ---------------------------------------------------------------- 3. matrice di rimozione

  /** PRIMO elemento della riga: la card sparita è quella a sinistra. */
  @Test
  fun rimozioneDelPrimoElementoRestaStabile() {
    render()
    removeCardByTitle("CW 1", expectedRemaining = 3)

    assertEquals(listOf("CW 2", "CW 3", "CW 4"), cwTitles())
    assertTrue("la riga deve restare disegnata", isContinueWatchingRowShown())

    val crash = leaveHomeAndReportCrash()
    assertNoCrashOnDisposal(crash, "dopo la rimozione del primo elemento")
  }

  /** Elemento INTERMEDIO: la riga resta coerente, senza duplicati né buchi. */
  @Test
  fun rimozioneDiUnElementoIntermedioRestaStabile() {
    render()
    removeCardByTitle("CW 2", expectedRemaining = 3)

    assertEquals(listOf("CW 1", "CW 3", "CW 4"), cwTitles())

    val crash = leaveHomeAndReportCrash()
    assertNoCrashOnDisposal(crash, "dopo la rimozione di un elemento intermedio")
  }

  /** ULTIMO elemento DELLA RIGA (ne restano altri): la card a destra è quella che sparisce. */
  @Test
  fun rimozioneDellUltimoElementoDellaRigaRestaStabile() {
    render()
    removeCardByTitle("CW 4", expectedRemaining = 3)

    assertEquals(listOf("CW 1", "CW 2", "CW 3"), cwTitles())

    val crash = leaveHomeAndReportCrash()
    assertNoCrashOnDisposal(crash, "dopo la rimozione dell'ultimo elemento della riga")
  }

  /** TUTTI gli elementi: la riga sparisce del tutto e la Home resta navigabile. */
  @Test
  fun rimozioneDiTuttiGliElementiSvuotaLaRigaSenzaCrash() {
    render()
    removeCardByTitle("CW 1", expectedRemaining = 3)
    removeCardByTitle("CW 2", expectedRemaining = 2)
    removeCardByTitle("CW 3", expectedRemaining = 1)
    removeCardByTitle("CW 4", expectedRemaining = 0)

    assertTrue("la lista di «Continua a guardare» deve essere vuota", cwList.isEmpty())
    assertFalse("la riga «Continua a guardare» non deve più essere disegnata", isContinueWatchingRowShown())

    // Il D-pad resta vivo e dentro il contenuto.
    val sidebarRightPx = with(rule.density) { SIDEBAR_WIDTH.toPx() }
    rule.waitUntil(timeoutMillis = 5_000) { focusedNodes().isNotEmpty() }
    focusedNodes().forEach { node ->
      assertTrue(
        "il focus è uscito dal contenuto (tag=${testTagOf(node)}, bounds=${node.boundsInRoot})",
        node.boundsInRoot.left >= sidebarRightPx - 1f
      )
    }
    pressKey(Key.DirectionDown)

    val crash = leaveHomeAndReportCrash()
    assertNoCrashOnDisposal(crash, "dopo la rimozione di tutti gli elementi")
  }

  // ---------------------------------------------------------------- 4. uscita e rientro

  /**
   * Uscita dalla schermata e RIENTRO con la Home ricomposta: la riga rimontata deve riflettere
   * la rimozione e non deve esplodere (è il percorso «cambio sezione dalla sidebar e ritorno»).
   */
  @Test
  fun rientroNellaSchermataDopoLaRimozioneNonEsplode() {
    render()
    removeCardByTitle("CW 3", expectedRemaining = 3)

    homeVisible = false
    rule.waitForIdle()
    homeVisible = true
    rule.waitForIdle()

    assertEquals(3, cwList.size)
    assertTrue("la riga deve essere ridisegnata al rientro", isContinueWatchingRowShown())

    val crash = leaveHomeAndReportCrash()
    assertNoCrashOnDisposal(crash, "dopo rientro e nuova uscita")
  }
}
