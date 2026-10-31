package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.example.data.model.MediaItem
import com.example.data.model.MediaType
import com.example.ui.components.TvFocusableBox
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ritorno dal selettore delle sorgenti al Dettaglio (Fase 4: pulsante «Seleziona fonte»
 * accanto a Trailer).
 *
 * Difetto coperto: la schermata di scelta sorgenti è un overlay di MainActivity composto
 * SOPRA il NavHost, quindi [DetailScreen] resta composto sotto di essa e NESSUNO dei suoi
 * effetti di focus ri-parte alla chiusura. Smontando il nodo focalizzato del selettore, il
 * fallback di focus di Compose atterrava sulla SINOSSI (primo nodo focusabile della scheda)
 * invece che sul pulsante «Riproduci».
 *
 * Meccanismo reale riprodotto dall'harness:
 *  - la scheda parte con il focus su «Riproduci», poi D-pad destro fino a «Seleziona fonte»
 *    (il flag `hasUserNavigatedAway` è quindi true, come nel caso reale segnalato);
 *  - OK su «Seleziona fonte» apre uno stub dell'overlay che SI PRENDE il focus come fa
 *    [com.example.ui.components.TorBoxSourceSelectionScreen] con la pillola «Tutto»;
 *  - la chiusura simula `dismissSourceDialog()`: overlay smontato E token
 *    `sourceDialogDismissedToken` avanzati nella STESSA snapshot.
 *
 * Copre inoltre la non-regressione degli altri flussi di ritorno al dettaglio: un token
 * avanzato PRIMA dell'ingresso in composizione non strappa il focus (ripristino del cast
 * dopo PersonDetail) e la chiusura del dialog della sinossi ripristina la sinossi.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-tvdpi")
class DetailSourceDialogDismissFocusTest {

  @get:Rule
  val rule = createComposeRule()

  private companion object {
    const val SELECT_SOURCE_LABEL_IT = "Seleziona fonte"
    const val SELECT_SOURCE_LABEL_EN = "Select source"
    const val PLAY_LABEL_IT = "Riproduci"
    const val PLAY_LABEL_EN = "Play"
    const val CLOSE_LABEL_IT = "Chiudi"
    const val CLOSE_LABEL_EN = "Close"
    const val SYNOPSIS_TEXT = "Il film d'animazione Pixar."
    const val CAST_ACTOR = "Anna Rossi"
    const val STUB_CHIP_ALL = "stub_source_chip_all"
    const val STUB_CHIP_CANCEL = "stub_source_chip_cancel"
  }

  // Stato dell'harness: l'overlay del selettore e il token di chiusura, esattamente come
  // MainActivity osserva `showSourceDialog` / `sourceDialogDismissedToken` dal ViewModel.
  private var showSourceOverlay by mutableStateOf(false)
  private var dismissedToken by mutableIntStateOf(0)
  private var targetCastMemberId by mutableStateOf<Int?>(null)

  private var openedSelectSourceMediaId: String? = null
  private var playClicks = 0

  /**
   * Nel ritorno reale da PersonDetail il flag `hasUserNavigatedAway` è true (saveable) e
   * l'effetto di focus iniziale non ri-parte quando il target del cast viene consumato.
   * Nell'harness invece parte da false: per riprodurre fedelmente quel flusso il target
   * NON viene consumato (`onClearTargetCastMember = null`), così nessun altro effetto
   * rincorre il ripristino del cast.
   */
  private var clearCastTargetOnRestore = true

  private fun movie(withCast: Boolean = false, withSynopsis: Boolean = true) = MediaItem(
    id = "tt0117731",
    title = "Toy Story",
    synopsis = if (withSynopsis) SYNOPSIS_TEXT else "",
    videoUrl = "",
    type = MediaType.FILM,
    year = 1995,
    tmdbId = 862,
    cast = if (withCast) listOf(CAST_ACTOR) else emptyList()
  )

  // ── Matcher (stessa tecnica di DetailSelectSourceButtonTest) ────────────────────────

  private fun focusablePlay() =
    isFocusable() and hasAnyDescendant(hasText(PLAY_LABEL_IT) or hasText(PLAY_LABEL_EN))

  private fun focusableSynopsis() =
    isFocusable() and hasAnyDescendant(hasText(SYNOPSIS_TEXT))

  private fun focusableSelectSource() =
    isFocusable() and
      hasAnyDescendant(
        hasContentDescription(SELECT_SOURCE_LABEL_IT) or hasContentDescription(SELECT_SOURCE_LABEL_EN)
      )

  private fun focusableCastActor() =
    isFocusable() and hasAnyDescendant(hasText(CAST_ACTOR))

  private fun stubChipAll() = hasTestTag(STUB_CHIP_ALL)
  private fun stubChipCancel() = hasTestTag(STUB_CHIP_CANCEL)

  @OptIn(ExperimentalTestApi::class)
  private fun pressDpad(key: Key) {
    rule.onNode(isFocused()).performKeyInput { pressKey(key) }
    rule.waitForIdle()
  }

  private fun assertNodeFocused(
    matcher: SemanticsMatcher,
    step: String,
    useUnmergedTree: Boolean = false
  ) {
    rule.onNode(matcher, useUnmergedTree = useUnmergedTree).assertIsFocused()
  }

  /** true se il nodo descritto da [matcher] detiene il focus (per le attese con waitUntil). */
  private fun isFocusedNode(
    matcher: SemanticsMatcher,
    useUnmergedTree: Boolean = false
  ): Boolean =
    try {
      rule.onNode(matcher, useUnmergedTree = useUnmergedTree).assertIsFocused()
      true
    } catch (_: AssertionError) {
      false
    } catch (_: IllegalStateException) {
      false
    }

  private fun focusedLabels(): String =
    rule.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes().joinToString(" | ") { node ->
      val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("/") { it.text }
      val desc = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("/")
      "text=$text desc=$desc"
    }

  private fun focusedNodes(): List<SemanticsNode> =
    rule.onAllNodes(isFocused(), useUnmergedTree = true).fetchSemanticsNodes()

  /** BACK di sistema: attraversa l'OnBackPressedDispatcher (i BackHandler composti). */
  private fun pressSystemBack() {
    val composeRule = rule as? AndroidComposeTestRule<*, *> ?: return
    rule.runOnUiThread {
      (composeRule.activity as? androidx.activity.ComponentActivity)
        ?.onBackPressedDispatcher
        ?.onBackPressed()
    }
    rule.waitForIdle()
  }

  // ── Harness: scheda di dettaglio + stub dell'overlay selettore sorgenti ────────────

  @Composable
  private fun App(media: MediaItem) {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0F14))) {
      DetailScreen(
        media = media,
        allMedia = listOf(media),
        onBackClick = {},
        onPlayClick = { _, _ -> playClicks++ },
        onToggleFavorite = {},
        onPlayTrailer = {},
        onSelectSourceClick = { m, _ ->
          openedSelectSourceMediaId = m.id
          showSourceOverlay = true
        },
        onCastMemberClick = {},
        targetCastMemberId = targetCastMemberId,
        onClearTargetCastMember = if (clearCastTargetOnRestore) {
          { targetCastMemberId = null }
        } else {
          null
        },
        sourceDialogDismissedToken = dismissedToken
      )
      if (showSourceOverlay) {
        SourceSelectorStub()
      }
    }
  }

  @Composable
  private fun StubChip(
    tag: String,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
  ) {
    TvFocusableBox(
      modifier = modifier.testTag(tag),
      shape = RoundedCornerShape(50),
      onClick = onClick
    ) { isFocused ->
      Box(
        modifier = Modifier
          .background(
            if (isFocused) Color(0xFF155E75) else Color(0xFF1E293B),
            RoundedCornerShape(50)
          )
          .padding(horizontal = 16.dp, vertical = 8.dp)
      ) {
        Text(
          text = label,
          color = if (isFocused) Color.White else Color(0xFF94A3B8)
        )
      }
    }
  }

  /**
   * Stub fedele del comportamento di focus di
   * [com.example.ui.components.TorBoxSourceSelectionScreen]: overlay a tutto schermo che
   * alla composizione SI PRENDE il focus (pillola «Tutto») e confina la navigazione D-pad
   * nel proprio focusGroup. La chiusura («Annulla» come nell'header reale, o BACK via
   * BackHandler) applica la STESSA snapshot di `dismissSourceDialog()`: overlay smontato
   * + token avanzato.
   */
  @Composable
  private fun SourceSelectorStub() {
    val firstChipRequester = remember { FocusRequester() }

    BackHandler {
      showSourceOverlay = false
      dismissedToken += 1
    }

    LaunchedEffect(Unit) {
      repeat(8) {
        withFrameNanos { }
        try {
          firstChipRequester.requestFocus()
          return@LaunchedEffect
        } catch (_: IllegalStateException) {
          delay(20)
        }
      }
    }

    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color(0xFF101820))
        .focusGroup(),
      contentAlignment = Alignment.Center
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        StubChip(
          tag = STUB_CHIP_ALL,
          label = "Tutto",
          modifier = Modifier.focusRequester(firstChipRequester),
          onClick = {}
        )
        StubChip(
          tag = STUB_CHIP_CANCEL,
          label = "Annulla",
          onClick = {
            // Percorso «Annulla»: chiusura senza riproduzione, identica a
            // viewModel.dismissSourceDialog() (overlay via + token avanzato).
            showSourceOverlay = false
            dismissedToken += 1
          }
        )
      }
    }
  }

  private fun render(media: MediaItem) {
    showSourceOverlay = false
    dismissedToken = 0
    targetCastMemberId = null
    clearCastTargetOnRestore = true
    openedSelectSourceMediaId = null
    playClicks = 0
    rule.setContent { App(media) }
    rule.waitForIdle()
  }

  // ── 1. Regressione principale: la chiusura riporta il focus su «Riproduci» ─────────

  /**
   * Percorso completo dell'utente: Riproduci -> Cuore -> Occhio -> Trailer -> Seleziona
   * fonte -> OK (si apre il selettore, che si prende il focus) -> navigazione interna
   * libera nel selettore -> chiusura («Annulla») -> il focus torna su «Riproduci» e NON
   * sulla sinossi.
   */
  @Test
  fun laChiusuraDelSelettoreRiportaIlFocusSuRiproduci() {
    render(movie())

    // Focus iniziale sulla pulsantiera: «Riproduci».
    assertNodeFocused(focusablePlay(), "focus iniziale")

    // Percorso reale fino a «Seleziona fonte» (hasUserNavigatedAway diventa true, come
    // nel difetto segnalato): Riproduci -> Cuore -> Occhio -> Trailer -> Seleziona fonte.
    repeat(4) { pressDpad(Key.DirectionRight) }
    assertNodeFocused(focusableSelectSource(), "pulsante «Seleziona fonte» raggiunto")

    // OK apre il selettore: lo stub si prende il focus (pillola «Tutto»).
    pressDpad(Key.DirectionCenter)
    assertEquals("tt0117731", openedSelectSourceMediaId)
    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(stubChipAll()) }
    assertTrue(
      "alla apertura del selettore il focus deve stare nel selettore (trovato: ${focusedLabels()})",
      focusedNodes().none { focusablePlay().matches(it) }
    )

    // Navigazione interna al selettore NON disturbata: RIGHT passa alla pillola «Annulla»
    // e il focus non torna alla scheda sottostante.
    pressDpad(Key.DirectionRight)
    assertNodeFocused(stubChipCancel(), "navigazione interna al selettore")

    // Chiusura con «Annulla» (stessa snapshot di dismissSourceDialog): overlay smontato
    // e token avanzato. Il focus deve tornare su «Riproduci», non sulla sinossi.
    pressDpad(Key.DirectionCenter)
    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(focusablePlay()) }
    assertNodeFocused(focusablePlay(), "ritorno dal selettore")
    assertTrue(
      "il focus NON deve atterrare sulla sinossi (trovato: ${focusedLabels()})",
      focusedNodes().none { focusableSynopsis().matches(it) }
    )
    assertEquals("La chiusura del selettore non avvia la riproduzione", 0, playClicks)
  }

  /**
   * Chiusura con BACK del telecomando: il BackHandler del selettore percorre lo stesso
   * funnello (`onDismiss` -> `dismissSourceDialog`, token avanzato) e quindi il focus deve
   * tornare su «Riproduci» anche da questo percorso.
   */
  @Test
  fun laChiusuraConBackRiportaIlFocusSuRiproduci() {
    render(movie())

    repeat(4) { pressDpad(Key.DirectionRight) }
    assertNodeFocused(focusableSelectSource(), "pulsante «Seleziona fonte» raggiunto")
    pressDpad(Key.DirectionCenter)
    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(stubChipAll()) }

    // BACK: il BackHandler dello stub chiude overlay + token (stessa snapshot del
    // funnello reale onDismiss -> dismissSourceDialog).
    pressSystemBack()

    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(focusablePlay()) }
    assertNodeFocused(focusablePlay(), "ritorno dal selettore con BACK")
    assertTrue(
      "il focus NON deve atterrare sulla sinossi con BACK (trovato: ${focusedLabels()})",
      focusedNodes().none { focusableSynopsis().matches(it) }
    )
  }

  // ── 2. Non-regressione: gli altri flussi di ritorno al dettaglio ───────────────────

  /**
   * Un token avanzato PRIMA dell'ingresso in composizione è ereditato (rientri da
   * Consigliati / PersonDetail): non deve strappare il focus al ripristino del membro del
   * cast (targetCastMemberId), che è il meccanismo già esistente.
   */
  @Test
  fun unTokenEreditatoAllIngressoNonStrappaIlFocusAlRipristinoDelCast() {
    targetCastMemberId = 1000 // id assegnato dal DetailScreen al primo membro del cast
    dismissedToken = 1 // avanzato da una chiusura precedente: ereditato, NON consumato
    clearCastTargetOnRestore = false // vedi nota su hasUserNavigatedAway nell'harness

    rule.setContent { App(movie(withCast = true, withSynopsis = false)) }
    rule.waitForIdle()

    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(focusableCastActor(), useUnmergedTree = true) }
    assertNodeFocused(focusableCastActor(), "ripristino del focus sul membro del cast", useUnmergedTree = true)
    assertTrue(
      "il token ereditato NON deve portare il focus su «Riproduci»",
      focusedNodes().none { focusablePlay().matches(it) }
    )
  }

  /**
   * Il dialog della sinossi conserva il proprio meccanismo: apertura dalla sinossi e
   * chiusura con l'icona «Chiudi» ripristinano il focus sulla SINOSSI (non su «Riproduci»).
   */
  @Test
  fun ilDialogDellaSinossiRipristinaAncoraIlFocusSullaSinossi() {
    render(movie())

    assertNodeFocused(focusablePlay(), "focus iniziale")
    // UP sale alla sinossi (catena già esistente), OK apre il dialog del testo completo.
    pressDpad(Key.DirectionUp)
    assertNodeFocused(focusableSynopsis(), "sinossi focalizzata", useUnmergedTree = true)
    pressDpad(Key.DirectionCenter)

    // Chiusura con l'icona «Chiudi» del dialog.
    rule.waitUntil(timeoutMillis = 5_000) {
      rule.onAllNodes(hasContentDescription(CLOSE_LABEL_IT) or hasContentDescription(CLOSE_LABEL_EN))
        .fetchSemanticsNodes().isNotEmpty()
    }
    rule.onAllNodes(hasContentDescription(CLOSE_LABEL_IT) or hasContentDescription(CLOSE_LABEL_EN))
      .onFirst()
      .performClick()
    rule.waitForIdle()

    rule.waitUntil(timeoutMillis = 5_000) { isFocusedNode(focusableSynopsis(), useUnmergedTree = true) }
    assertNodeFocused(focusableSynopsis(), "il focus torna alla sinossi dopo il dialog", useUnmergedTree = true)
    assertTrue(
      "la chiusura del dialog della sinossi non deve portare il focus su «Riproduci»",
      focusedNodes().none { focusablePlay().matches(it) }
    )
  }

  /**
   * Senza chiusure del selettore (token mai avanzato mentre la scheda è viva) nessun
   * effetto impone il focus: la catena storica UP (sinossi) / DOWN («Riproduci») resta
   * esattamente quella di prima.
   */
  @Test
  fun senzaChiusureDelSelettoreLaCatenaSinossiRiproduciRestaInvariata() {
    render(movie())

    assertNodeFocused(focusablePlay(), "focus iniziale")
    pressDpad(Key.DirectionUp)
    assertNodeFocused(focusableSynopsis(), "sinossi focalizzata", useUnmergedTree = true)
    pressDpad(Key.DirectionDown)
    assertNodeFocused(focusablePlay(), "ritorno alla pulsantiera")
    assertEquals(0, dismissedToken)
  }
}
