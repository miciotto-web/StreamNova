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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.data.repository.HomeCatalogs
import com.example.ui.components.LocalClearLastFocusedMedia
import com.example.ui.components.LocalFocusRestoreToken
import com.example.ui.components.LocalLastFocusedMediaId
import com.example.ui.components.LocalLastFocusedTarget
import com.example.ui.components.MEDIA_CONTEXT_MENU_CANCEL_TEST_TAG
import com.example.ui.components.MEDIA_CONTEXT_MENU_TEST_TAG
import com.example.ui.components.ModalOverlayState
import com.example.ui.components.TV_LONG_PRESS_TIMEOUT_MS
import com.example.ui.components.TvFocusableBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Menu contestuale di «Continua a guardare» (long-press su una card) e gestione del focus.
 *
 * Difetto su Fire TV: il popup appariva ma, in modo INTERMITTENTE, il focus restava sulla
 * Home, il D-pad non riusciva a navigare le voci e il BACK poteva finire sul doppio-BACK di
 * uscita dell'app.
 *
 * Causa coperta dall'harness: il menu NON era una finestra modale ma un overlay composto
 * nella stessa finestra della Home. L'isolamento dipendeva solo da richieste di focus che
 * la Home poteva scavalcare. Il menu è ora un vero [androidx.compose.ui.window.Dialog]:
 * l'harness verifica che
 * 1. il popup sia una finestra Dialog attiva (input isolato dal sistema delle finestre);
 * 2. il focus iniziale atterri su una voce del popup;
 * 3. la navigazione D-pad resti tra le voci e non muova il focus latente della Home;
 * 4. BACK chiuda la finestra e ripristini il focus sulla card di origine;
 * 5. apertura/chiusura ripetute restino stabili;
 * 6. ognuna delle tre azioni venga eseguita UNA sola volta.
 *
 * Nota sull'harness: con più finestre Compose mantiene un nodo focalizzato per ciascuna
 * finestra. Il nodo della Home è LATENTE (la finestra della Home non ha il focus di input):
 * i test quindi non pretendono che la Home perda il proprio stato di focus interno, ma che
 * i tasti diretti al popup non lo muovano e che la finestra del Dialog sia quella attiva.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class ContinueWatchingContextMenuFocusTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val HERO_TITLE = "HERO 1"
    const val SIDEBAR_TAG_PREFIX = "sidebar_item_"
    const val SIDEBAR_ITEMS = 4
    val SIDEBAR_WIDTH = 60.dp

    // Etichette del menu nel locale del test (Robolectric usa en-US) e nella lingua
    // predefinita del progetto: l'harness non deve dipendere dal locale per verificare il focus.
    val RESUME_LABELS = listOf("Riprendi visione", "Resume")
    val DETAILS_LABELS = listOf("Vai ai dettagli", "View Details")
    val REMOVE_LABELS = listOf("Rimuovi da Continua a guardare", "Remove from Continue Watching")
    val CANCEL_LABELS = listOf("Annulla", "Cancel")
  }

  // ---------------------------------------------------------------- fixture

  private fun cwItem(index: Int, lastWatchedAt: Long, progressMs: Long = 10 * 60_000L): MediaItem =
    MediaItem(
      id = "cw-${index + 1}",
      title = "CW ${index + 1}",
      synopsis = "",
      videoUrl = "",
      type = MediaType.FILM,
      year = 2200 + index,
      tmdbId = 7000 + index,
      currentProgressMs = progressMs,
      totalDurationMs = 100 * 60_000L,
      lastWatchedAt = lastWatchedAt
    )

  private fun cwItems(progressMs: Long = 10 * 60_000L): List<MediaItem> = listOf(
    cwItem(0, lastWatchedAt = 5_000L, progressMs = progressMs),
    cwItem(1, lastWatchedAt = 4_000L, progressMs = progressMs),
    cwItem(2, lastWatchedAt = 3_000L, progressMs = progressMs),
    cwItem(3, lastWatchedAt = 2_000L, progressMs = progressMs)
  )

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

  /** Riga «Continua a guardare» osservabile: una ri-emissione simula gli aggiornamenti reali. */
  private var cwList by mutableStateOf(cwItems())

  /** Stato di ripristino dal Dettaglio, come lo fornisce MainActivity alla Home. */
  private var restoreTarget by mutableStateOf<Pair<String, String>?>(null)
  private var restoreToken by mutableIntStateOf(0)

  private var playClicks = 0
  private var detailClicks = 0
  private val removedIds = mutableListOf<String>()

  @Composable
  private fun App() {
    CompositionLocalProvider(
      LocalLastFocusedMediaId provides restoreTarget?.second,
      LocalLastFocusedTarget provides restoreTarget,
      LocalFocusRestoreToken provides restoreToken,
      LocalClearLastFocusedMedia provides { restoreTarget = null }
    ) {
      Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0F14))) {
        HomeMainBrowsingContent(
          allMedia = cwList,
          homeCatalogs = catalogs(),
          onMediaClick = { detailClicks++ },
          onPlayClick = { playClicks++ },
          onRemoveFromContinueWatching = { id ->
            removedIds += id
            // Rimozione REALE dall'elenco, come fa il repository: la card smonta mentre il
            // popup si chiude ed è il percorso più esposto a crash/focus orfani.
            cwList = cwList.filterNot { it.id == id }
          },
          viewModel = null,
          modifier = Modifier
            .fillMaxSize()
            .padding(start = SIDEBAR_WIDTH)
        )
        SidebarStub()
      }
    }
  }

  /**
   * Sidebar ridotta ai soli nodi focalizzabili: resta la superficie più a SINISTRA dello
   * schermo, quindi è il bersaglio che la ricerca geometrica sceglie se il focus fugge dal
   * popup verso sinistra (difetto storico della Home).
   */
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
        modifier = Modifier.fillMaxHeight().padding(vertical = 16.dp, horizontal = 4.dp)
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

  private fun render() {
    cwList = cwItems()
    restoreTarget = null
    restoreToken = 0
    playClicks = 0
    detailClicks = 0
    removedIds.clear()
    rule.setContent { App() }
    rule.waitForIdle()
  }

  // ---------------------------------------------------------------- helper

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

  private fun isInsideContextMenu(node: SemanticsNode): Boolean =
    generateSequence(node) { it.parent }
      .any { it.config.getOrNull(SemanticsProperties.TestTag) == MEDIA_CONTEXT_MENU_TEST_TAG }

  /** Nodo focalizzato DENTRO la finestra del popup (l'unica attiva mentre è aperto). */
  private val popupFocusedMatcher: SemanticsMatcher =
    isFocused() and hasAnyAncestor(hasTestTag(MEDIA_CONTEXT_MENU_TEST_TAG))

  private fun focusedLabels(): List<String> = focusedNodes().flatMap { allTextsOf(it) }

  private fun isPopupItemFocused(labels: List<String>): Boolean =
    focusedNodes().any { node ->
      isInsideContextMenu(node) && allTextsOf(node).any { labels.contains(it) }
    }

  private fun isCardFocused(title: String): Boolean =
    focusedNodes().any { node -> !isInsideContextMenu(node) && allTextsOf(node).contains(title) }

  private fun assertPopupItemFocused(labels: List<String>, step: String) {
    try {
      rule.waitUntil(timeoutMillis = 5_000) { isPopupItemFocused(labels) }
    } catch (timeout: Throwable) {
      val focused = focusedNodes().joinToString(" | ") {
        "texts=${allTextsOf(it)} tag=${testTagOf(it)} insidePopup=${isInsideContextMenu(it)}"
      }
      throw AssertionError(
        "$step: focus su [$focused] invece che su «$labels» dentro il popup",
        timeout
      )
    }
    assertTrue(
      "$step: focus su [${focusedLabels()}] invece che su «$labels» dentro il popup",
      isPopupItemFocused(labels)
    )
  }

  /** Finestra del Dialog attualmente mostrata (null quando il popup è chiuso). */
  private fun showingDialog(): android.app.Dialog? =
    ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }

  private fun assertPopupOwnsInput(step: String) {
    assertTrue(
      "$step: il popup deve essere una finestra Dialog mostrata (modale di sistema)",
      showingDialog() != null
    )
    val popupFocused = focusedNodes().filter { isInsideContextMenu(it) }
    assertEquals(
      "$step: la finestra del popup deve avere esattamente un nodo focalizzato, " +
        "trovati ${popupFocused.size} (${focusedLabels()})",
      1,
      popupFocused.size
    )
  }

  /**
   * La Home mantiene il proprio focus INTERNO (è la memoria della card di partenza), ma non
   * deve MUOVERLO: la finestra attiva è quella del Dialog. Se un tasto del popup raggiungesse
   * la Home, il nodo focalizzato latente cambierebbe (es. verso la riga provider o la sidebar).
   */
  private fun assertHomeFocusPinnedTo(title: String, step: String) {
    val outside = focusedNodes().filterNot { isInsideContextMenu(it) }
    assertEquals(
      "$step: la Home non deve muovere il focus; nodi focalizzati fuori dal popup: " +
        outside.joinToString { "${allTextsOf(it)}" },
      1,
      outside.size
    )
    assertTrue(
      "$step: il focus latente della Home deve restare sulla card «$title», " +
        "trovato ${allTextsOf(outside.single())}",
      allTextsOf(outside.single()).contains(title)
    )
  }

  private fun assertFocusOnCard(title: String, step: String) {
    rule.waitUntil(timeoutMillis = 5_000) { isCardFocused(title) }
    assertTrue(
      "$step: focus su [${focusedLabels()}] invece che sulla card «$title»",
      isCardFocused(title)
    )
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

  /**
   * Invia un evento tasto ANDROID reale alla finestra indicata.
   *
   * Serve per il rilascio del long-press: sul dispositivo il KeyDown parte nella finestra
   * della Home e il KeyUp viene consegnato alla finestra del Dialog, che non ha mai visto un
   * KeyDown corrispondente. L'API `performKeyInput` impedisce KeyUp "orfani" per owner, quindi
   * questa sequenza si riproduce con un dispatch diretto sul decorView (stessa gerarchia che
   * Android usa per consegnare i tasti alla vista focalizzata).
   */
  private fun dispatchRawKey(view: android.view.View?, action: Int, keyCode: Int) {
    requireNotNull(view) { "finestra per l'evento tasto non disponibile" }
    rule.runOnUiThread {
      view.dispatchKeyEvent(android.view.KeyEvent(action, keyCode))
    }
    rule.waitForIdle()
  }

  private fun activityDecor(): android.view.View? {
    val composeRule = rule as? AndroidComposeTestRule<*, *> ?: return null
    return composeRule.activity.window?.decorView
  }

  private fun dialogDecor(): android.view.View? = showingDialog()?.window?.decorView

  /**
   * Pressione prolungata del tasto di conferma sulla card a fuoco: KeyDown ANDROID reale,
   * avanzamento del timer (400 ms) e attesa che il popup sia visibile. Il KeyUp viene inviato
   * a parte, alla finestra del Dialog, come sul telecomando reale.
   */
  private fun longPressConfirmWithoutRelease() {
    dispatchRawKey(activityDecor(), android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER)
    rule.mainClock.advanceTimeBy(TV_LONG_PRESS_TIMEOUT_MS + 150L)
    rule.waitForIdle()
    rule.waitUntil(timeoutMillis = 5_000) {
      rule.onAllNodes(hasTestTag(MEDIA_CONTEXT_MENU_TEST_TAG), useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()
    }
    rule.waitUntil(timeoutMillis = 5_000) {
      rule.onAllNodes(popupFocusedMatcher, useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()
    }
  }

  /**
   * Rilascio del tasto dopo l'apertura: evento ANDROID ACTION_UP consegnato alla finestra del
   * Dialog. La guardia del popup lo consuma (nessuna pressione nuova ⇒ non è un click).
   */
  private fun releaseConfirm() {
    dispatchRawKey(dialogDecor(), android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_CENTER)
  }

  /** Long-press completo: apertura del popup e rilascio del tasto (come l'utente reale). */
  private fun openContextMenuWithLongPress() {
    longPressConfirmWithoutRelease()
    releaseConfirm()
  }

  @OptIn(ExperimentalTestApi::class)
  private fun pressPopupKey(key: Key) {
    rule.onAllNodes(popupFocusedMatcher, useUnmergedTree = true).onFirst()
      .performKeyInput { pressKey(key) }
    rule.waitForIdle()
  }

  /** Tasto consegnato alla Home (a popup chiuso): deve esistere un solo focus nell'Activity. */
  @OptIn(ExperimentalTestApi::class)
  private fun pressHomeKey(key: Key) {
    val focused = focusedNodes()
    assertEquals("atteso un solo focus nella Home, trovati ${focused.size}", 1, focused.size)
    rule.onNode(isFocused(), useUnmergedTree = true).performKeyInput { pressKey(key) }
    rule.waitForIdle()
  }

  /**
   * BACK di sistema. Con il popup aperto la finestra attiva è il Dialog: il tasto attraversa
   * il dispatcher DEL DIALOG (come sul dispositivo, dove la finestra della Home non lo vede).
   * A popup chiuso attraversa il dispatcher dell'Activity.
   */
  private fun pressSystemBack() {
    val composeRule = rule as? AndroidComposeTestRule<*, *> ?: return
    rule.runOnUiThread {
      val dialog = showingDialog()
      if (dialog is androidx.activity.ComponentDialog) {
        dialog.onBackPressedDispatcher.onBackPressed()
      } else {
        (composeRule.activity as? androidx.activity.ComponentActivity)
          ?.onBackPressedDispatcher
          ?.onBackPressed()
      }
    }
    rule.waitForIdle()
  }

  // ---------------------------------------------------------------- 1. apertura

  @Test
  fun laPressioneProlungataApreIlMenuEtrasferisceIlFocusAlPrimoElemento() {
    render()
    focusCard("CW 2")
    assertTrue("la card di partenza deve avere il focus", isCardFocused("CW 2"))

    openContextMenuWithLongPress()

    assertPopupItemFocused(RESUME_LABELS, "apertura del menu contestuale")
    assertPopupOwnsInput("apertura del menu contestuale")
    assertHomeFocusPinnedTo("CW 2", "apertura del menu contestuale")
    assertTrue("il menu deve essere registrato come modale aperto", ModalOverlayState.isAnyOpen)
  }

  @Test
  fun ilRilascioDelLongPressNonAttivaLaVoceDelMenu() {
    render()
    focusCard("CW 2")

    // Apertura + rilascio: il KeyUp appartiene alla pressione prolungata, non è un click.
    openContextMenuWithLongPress()
    rule.waitForIdle()

    assertEquals("il rilascio del long-press non deve avviare la riproduzione", 0, playClicks)
    assertEquals("il rilascio del long-press non deve aprire i dettagli", 0, detailClicks)
    assertTrue("il rilascio del long-press non deve rimuovere la card", removedIds.isEmpty())
    assertPopupItemFocused(RESUME_LABELS, "popup ancora aperto dopo il rilascio")
  }

  // ---------------------------------------------------------------- 2. confinamento e navigazione

  @Test
  fun laNavigazioneDpadRestaConfintaNelPopupEattraversaTutteLeVoci() {
    render()
    focusCard("CW 1")
    openContextMenuWithLongPress()
    assertPopupItemFocused(RESUME_LABELS, "primo elemento")

    // UP dalla prima voce: nessun vicino sopra, la direzione è annullata.
    pressPopupKey(Key.DirectionUp)
    assertPopupItemFocused(RESUME_LABELS, "UP dalla prima voce resta nel menu")

    // LEFT/RIGHT: nessun vicino orizzontale, la direzione è annullata (niente fuga).
    pressPopupKey(Key.DirectionLeft)
    assertPopupItemFocused(RESUME_LABELS, "LEFT dalla prima voce resta nel menu")
    pressPopupKey(Key.DirectionRight)
    assertPopupItemFocused(RESUME_LABELS, "RIGHT dalla prima voce resta nel menu")

    // Discesa completa: tutte le voci del popup sono raggiungibili.
    pressPopupKey(Key.DirectionDown)
    assertPopupItemFocused(DETAILS_LABELS, "seconda voce")
    pressPopupKey(Key.DirectionDown)
    assertPopupItemFocused(REMOVE_LABELS, "terza voce")
    pressPopupKey(Key.DirectionDown)
    assertPopupItemFocused(CANCEL_LABELS, "voce «Annulla»")

    // DOWN dall'ultima voce: nessun vicino sotto, la direzione è annullata.
    pressPopupKey(Key.DirectionDown)
    assertPopupItemFocused(CANCEL_LABELS, "DOWN dall'ultima voce resta nel menu")
    pressPopupKey(Key.DirectionRight)
    assertPopupItemFocused(CANCEL_LABELS, "RIGHT dall'ultima voce resta nel menu")

    // Risalita: la catena inversa funziona.
    pressPopupKey(Key.DirectionUp)
    assertPopupItemFocused(REMOVE_LABELS, "risalita alla terza voce")
    pressPopupKey(Key.DirectionUp)
    assertPopupItemFocused(DETAILS_LABELS, "risalita alla seconda voce")
    pressPopupKey(Key.DirectionUp)
    assertPopupItemFocused(RESUME_LABELS, "risalita alla prima voce")

    assertPopupOwnsInput("navigazione completa del popup")
    assertHomeFocusPinnedTo("CW 1", "navigazione completa del popup")
  }

  // ---------------------------------------------------------------- 3. ricomposizione

  @Test
  fun unaRicomposizioneImmediataDopoLAperturaNonSpostaIlFocusDalPopup() {
    render()
    focusCard("CW 3")
    openContextMenuWithLongPress()
    assertPopupItemFocused(RESUME_LABELS, "apertura")

    // Ondata dati reale (progresso/ordinamento della riga) mentre il popup è aperto: la
    // ricomposizione della Home non deve interrompere lo stato di focus del menu.
    cwList = cwItems(progressMs = 42 * 60_000L)
    rule.waitForIdle()

    assertPopupItemFocused(RESUME_LABELS, "dopo la ricomposizione della Home")
    assertPopupOwnsInput("dopo la ricomposizione della Home")
    assertHomeFocusPinnedTo("CW 3", "dopo la ricomposizione della Home")
  }

  @Test
  fun unaRichiestaPendenteDellaHomeNonSottraeIlFocusAlPopupAperto() {
    render()
    focusCard("CW 4")
    openContextMenuWithLongPress()
    assertPopupItemFocused(RESUME_LABELS, "apertura")

    // Riutilizza il percorso REALE di ripristino della Home (quello che al ritorno dal
    // Dettaglio/player emette una richiesta pendente): con il popup aperto il protocollo
    // deve astenersi dal muovere il focus. Senza la guardia il reassert della cella di
    // partenza riportava il focus sulla Home (difetto intermittente).
    restoreTarget = "section_continue_watching" to "cw-1"
    restoreToken += 1
    rule.waitForIdle()

    assertPopupItemFocused(RESUME_LABELS, "richiesta pendente della Home ignorata")
    assertPopupOwnsInput("richiesta pendente della Home ignorata")
    assertFalse("il ripristino della Home non deve atterrare su CW 1", isCardFocused("CW 1"))
  }

  // ---------------------------------------------------------------- 4. chiusura

  @Test
  fun laChiusuraConBackRipristinaIlFocusSullaCardCheHaApertoIlMenu() {
    render()
    focusCard("CW 3")
    openContextMenuWithLongPress()
    assertPopupItemFocused(RESUME_LABELS, "apertura")

    pressSystemBack()

    assertFocusOnCard("CW 3", "chiusura con BACK")
    assertFalse("il menu deve essere smontato", ModalOverlayState.isAnyOpen)
    assertTrue("la finestra del Dialog deve essere chiusa", showingDialog() == null)
    assertFalse(
      "il focus non deve restare nel popup",
      focusedNodes().any { isInsideContextMenu(it) }
    )

    // D-pad vivo: il focus ripristinato è una vera cella della Home.
    pressHomeKey(Key.DirectionDown)
    assertTrue("il D-pad deve essere di nuovo operativo", focusedNodes().isNotEmpty())
  }

  @Test
  fun laChiusuraConAnnullaRipristinaIlFocusSullaCardCheHaApertoIlMenu() {
    render()
    focusCard("CW 2")
    openContextMenuWithLongPress()
    assertPopupItemFocused(RESUME_LABELS, "apertura")

    pressPopupKey(Key.DirectionDown)
    pressPopupKey(Key.DirectionDown)
    pressPopupKey(Key.DirectionDown)
    rule.onNode(hasTestTag(MEDIA_CONTEXT_MENU_CANCEL_TEST_TAG), useUnmergedTree = true)
      .assertIsFocused()

    pressPopupKey(Key.DirectionCenter)

    assertFocusOnCard("CW 2", "chiusura con «Annulla»")
    assertEquals("«Annulla» non deve avviare azioni", 0, playClicks + detailClicks)
    assertTrue(removedIds.isEmpty())
  }

  @Test
  fun laRiaperturaDopoLaChiusuraRiportaOgniVoltaIlFocusNelPopup() {
    render()
    focusCard("CW 1")

    // Tre cicli completi: ogni apertura deve ripartire dal focus nel popup e ogni chiusura
    // deve riportarlo sulla stessa card (nessun latch del tasto, nessuna finestra residua).
    repeat(3) { cycle ->
      val step = "ciclo ${cycle + 1}"
      openContextMenuWithLongPress()
      assertPopupItemFocused(RESUME_LABELS, "$step: apertura")
      assertPopupOwnsInput("$step: apertura")

      pressSystemBack()
      assertFocusOnCard("CW 1", "$step: chiusura")
      assertFalse("$step: il Dialog deve essere chiuso", ModalOverlayState.isAnyOpen)
      assertTrue("$step: nessuna finestra popup residua", showingDialog() == null)
    }
  }

  // ---------------------------------------------------------------- 5. azioni

  @Test
  fun laVoceRiprendiEsegueUnaSolaAzioneEChiudeIlPopup() {
    render()
    focusCard("CW 2")
    openContextMenuWithLongPress()
    assertPopupItemFocused(RESUME_LABELS, "apertura")

    pressPopupKey(Key.DirectionCenter)

    rule.waitForIdle()
    assertEquals("«Riprendi visione» deve avviare la riproduzione UNA volta", 1, playClicks)
    assertEquals("nessuna navigazione ai dettagli", 0, detailClicks)
    assertTrue("nessuna rimozione", removedIds.isEmpty())
    assertTrue("il popup deve chiudersi", showingDialog() == null)
    assertFalse("nessun nodo del popup deve restare focalizzato", focusedNodes().any { isInsideContextMenu(it) })
  }

  @Test
  fun laVoceDettagliEsegueUnaSolaNavigazioneEChiudeIlPopup() {
    render()
    focusCard("CW 3")
    openContextMenuWithLongPress()
    assertPopupItemFocused(RESUME_LABELS, "apertura")

    pressPopupKey(Key.DirectionDown)
    assertPopupItemFocused(DETAILS_LABELS, "voce dettagli")
    pressPopupKey(Key.DirectionCenter)

    rule.waitForIdle()
    assertEquals("«Vai ai dettagli» deve aprire la scheda UNA volta", 1, detailClicks)
    assertEquals("nessuna riproduzione", 0, playClicks)
    assertTrue("nessuna rimozione", removedIds.isEmpty())
    assertTrue("il popup deve chiudersi", showingDialog() == null)
  }

  @Test
  fun laVoceRimuoviEsegueUnaSolaRimozioneEAncoraIlFocusAllaHome() {
    render()
    focusCard("CW 2")
    openContextMenuWithLongPress()
    assertPopupItemFocused(RESUME_LABELS, "apertura")

    pressPopupKey(Key.DirectionDown)
    pressPopupKey(Key.DirectionDown)
    assertPopupItemFocused(REMOVE_LABELS, "voce rimuovi")
    pressPopupKey(Key.DirectionCenter)

    rule.waitForIdle()
    assertEquals("la rimozione deve avvenire UNA sola volta", listOf("cw-2"), removedIds)
    assertEquals("nessuna riproduzione", 0, playClicks)
    assertEquals("nessuna navigazione ai dettagli", 0, detailClicks)
    assertTrue("il popup deve chiudersi", showingDialog() == null)
    // La card non esiste più: la Home ancora il focus all'Hero, mai a un nodo smontato.
    assertFocusOnCard(HERO_TITLE, "dopo la rimozione della card di origine")
    assertTrue("il D-pad deve restare operativo", focusedNodes().isNotEmpty())
  }

  @Test
  fun azioneEBackNelloStessoFrameNonEseguonoLAzioneDueVolte() {
    render()
    focusCard("CW 2")
    openContextMenuWithLongPress()
    assertPopupItemFocused(RESUME_LABELS, "apertura")

    // Attivazione della voce e BACK emessi nello STESSO frame (telecomando veloce, ripetizione
    // del tasto o dismiss del Dialog durante la navigazione): la guardia one-shot deve
    // eseguire l'azione una sola volta e ignorare la chiusura tardiva.
    rule.runOnUiThread {
      val decor = dialogDecor()
      decor?.dispatchKeyEvent(
        android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER)
      )
      decor?.dispatchKeyEvent(
        android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_CENTER)
      )
      (showingDialog() as? androidx.activity.ComponentDialog)
        ?.onBackPressedDispatcher
        ?.onBackPressed()
    }
    rule.waitForIdle()

    assertEquals("l'azione deve essere eseguita UNA sola volta", 1, playClicks)
    assertEquals("nessuna navigazione ai dettagli", 0, detailClicks)
    assertTrue("nessuna rimozione", removedIds.isEmpty())
    assertTrue("il popup deve chiudersi", showingDialog() == null)
  }
}
